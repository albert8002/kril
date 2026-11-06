package app.coredump.data

import app.coredump.core.IndexCache
import app.coredump.core.PackCodec
import app.coredump.core.Prompt
import app.coredump.core.SourceKind
import app.coredump.platform.CrashLog
import app.coredump.platform.Platform
import app.coredump.res.Res
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.ExperimentalResourceApi

enum class SyncState { OFF, IDLE, SYNCING, OK, ERROR }

/** count: prompts held from this source. done/total: templates fetched this sync (counts). */
data class SourceStatus(
    val state: SyncState = SyncState.IDLE,
    val count: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
    val detail: String = "",
)

/**
 * Daily / on-request collection of new lists.
 * done/total: lists tried in the current batch (counts). added: new prompts kept by the last batch (count).
 * library: collected prompts held on the device (count). lastAt: epoch ms of the last finished batch (0 = never).
 */
data class CollectState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val added: Int = 0,
    val library: Int = 0,
    val lastAt: Long = 0L,
    val message: String = "",
)

/** prompts / answers: totals across all sources (counts). */
data class BankState(
    val prompts: Int = 0,
    val answers: Int = 0,
    val sources: Map<SourceKind, SourceStatus> = SourceKind.entries.associateWith { SourceStatus() },
    val collect: CollectState = CollectState(),
    val ready: Boolean = false,
)

/**
 * Holds every prompt from every source, keyed by prompt id.
 *  - boot(): bundled packs (read file by file to keep memory flat) + the collected library + caches.
 *  - collect(): fetches new Wikidata lists from [WikidataPool] (daily, and when the player asks),
 *    stores each batch as its own small file, keeps at most [MAX_LIBRARY_FILES] batches.
 * Nothing in here may crash the app: every network / parse failure ends up as a status line.
 */
class QuestionBank(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val byId = LinkedHashMap<String, Prompt>()
    private val _state = MutableStateFlow(BankState())
    val state: StateFlow<BankState> = _state.asStateFlow()
    private var syncJob: Job? = null
    private val collectLock = Mutex()

    private val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 70_000
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    private var snapshot: List<Prompt> = emptyList()

    fun all(): List<Prompt> = snapshot

    fun get(id: String): Prompt? = snapshot.firstOrNull { it.id == id }

    fun topics(): List<Pair<String, Int>> =
        snapshot.groupingBy { it.topic }.eachCount().toList().sortedBy { it.first }

    private suspend fun putAll(list: List<Prompt>) = lock.withLock {
        val gone = Discards.ids
        for (p in list) if (p.id !in gone) byId[p.id] = p
        publish()
    }

    private suspend fun removeAll(ids: Collection<String>) = lock.withLock {
        for (id in ids) byId.remove(id)
        publish()
    }

    /** Call with [lock] held. */
    private fun publish() {
        snapshot = byId.values.toList()
        val counts = snapshot.groupingBy { it.source }.eachCount()
        _state.update { s ->
            s.copy(
                prompts = snapshot.size,
                answers = snapshot.sumOf { it.size },
                sources = s.sources.mapValues { (k, v) -> v.copy(count = counts[k] ?: 0) },
            )
        }
    }

    private fun status(kind: SourceKind, f: (SourceStatus) -> SourceStatus) =
        _state.update { s -> s.copy(sources = s.sources + (kind to f(s.sources.getValue(kind)))) }

    private fun collectStatus(f: (CollectState) -> CollectState) = _state.update { it.copy(collect = f(it.collect)) }

    /** Fast part of boot. [log] receives terminal lines for the boot screen. */
    suspend fun boot(settings: Settings, log: (String) -> Unit) {
        withContext(Dispatchers.Default) {
            Discards.load()
            val bundled = guarded("bundled packs", 0) { loadBundled(log) }
            status(SourceKind.BUNDLED) { it.copy(state = SyncState.OK, detail = "$bundled prompts") }

            val lib = guarded("library", 0) { loadLibrary() }
            log(if (lib > 0) "[ OK ] /var/lib: $lib collected lists" else "[ -- ] /var/lib: no collected lists yet")

            for (kind in listOf(SourceKind.WIKIPEDIA, SourceKind.REMOTE)) {
                val cached = guarded("cache", emptyList()) {
                    // drop lists whose template no longer exists (too specialised lists were retired)
                    val known = WikipediaTemplates.all.mapTo(HashSet()) { it.id }
                    readCache(kind).filter { kind != SourceKind.WIKIPEDIA || it.id in known }
                }
                if (cached.isNotEmpty()) {
                    putAll(cached)
                    log("[ OK ] cache /var/${kind.tag.lowercase()}: ${cached.size} prompts")
                }
            }
            // Pre-v8 devices kept refreshed Wikidata lists here; they are still valid prompts.
            guarded("legacy cache", Unit) {
                val known = WikidataTemplates.all.mapTo(HashSet()) { it.id }
                val old = readCache(SourceKind.WIKIDATA)
                if (old.isNotEmpty()) putAll(old.filter { it.id in known && get(it.id) == null })
            }
            log("[ OK ] ${_state.value.answers} answers across ${_state.value.prompts} lists")
            _state.update { it.copy(ready = true) }
        }
        sync(settings, force = false)
    }

    /** Background work after boot: daily collection + stale Wikipedia / remote refresh. */
    fun sync(settings: Settings, force: Boolean) {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.Default) {
            guarded("sync", Unit) {
                val now = Platform.epochMillis()
                if (settings.useWikidata) {
                    status(SourceKind.WIKIDATA) { it.copy(state = SyncState.OK, detail = "") }
                    val meta = readMeta()
                    if (force || now - meta.lastAuto > AUTO_EVERY) collectNow(DAILY_BATCH, auto = true)
                } else status(SourceKind.WIKIDATA) { it.copy(state = SyncState.OFF) }

                if (settings.useWikipedia) {
                    if (force || isStale(SourceKind.WIKIPEDIA, now, 2 * WEEK)) syncWikipedia()
                    else status(SourceKind.WIKIPEDIA) { it.copy(state = SyncState.OK, detail = "cached") }
                } else status(SourceKind.WIKIPEDIA) { it.copy(state = SyncState.OFF) }

                if (settings.remoteUrls.isNotEmpty()) {
                    if (force || isStale(SourceKind.REMOTE, now, DAY)) syncRemote(settings.remoteUrls)
                    else status(SourceKind.REMOTE) { it.copy(state = SyncState.OK, detail = "cached") }
                } else status(SourceKind.REMOTE) { it.copy(state = SyncState.OFF) }
            }
        }
    }

    /** "FETCH MORE" button: collect a batch now, in the background. */
    fun requestMore(n: Int = DAILY_BATCH) {
        scope.launch(Dispatchers.Default) { guarded("collect", Unit) { collectNow(n, auto = false) } }
    }

    fun purgeCaches() {
        for (k in listOf(SourceKind.WIKIDATA, SourceKind.WIKIPEDIA, SourceKind.REMOTE)) Platform.delete(cacheFile(k))
        val meta = readMeta()
        for (f in meta.files) Platform.delete(f)
        Platform.delete(META)
        IndexCache.clear()
    }

    /** Runs [block]; on any failure (except cancellation) logs it and returns [fallback]. */
    private suspend fun <T> guarded(what: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        CrashLog.note("bank/$what", e)
        fallback
    }

    // ---------------- bundled ----------------

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun loadBundled(log: (String) -> Unit): Int {
        val index = runCatching { Res.readBytes("files/packs/index.json").decodeToString() }.getOrNull()
            ?: return 0.also { log("[FAIL] /packs/index.json missing") }
        val files = Json.parseToJsonElement(index).jsonObject["packs"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        var total = 0
        var liveFiles = 0
        var livePrompts = 0
        for (f in files) {
            // one file at a time: text + JSON tree are garbage before the next file is read
            val prompts = try {
                val text = Res.readBytes("files/packs/$f").decodeToString()
                val ps = PackCodec.parse(text, SourceKind.BUNDLED)
                PackCodec.cacheTime(text)?.let { at -> ps.firstOrNull()?.source?.let { bundledAt[it] = at } }
                ps
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log("[FAIL] /packs/$f: ${e.message?.take(60)}"); emptyList()
            }
            putAll(prompts)
            total += prompts.size
            if (f.startsWith("live_")) { liveFiles++; livePrompts += prompts.size }
            else log("[ OK ] mount /packs/$f  ${prompts.size} prompts / ${prompts.sumOf { it.size }} answers")
        }
        if (liveFiles > 0) log("[ OK ] mount /packs/live_*  $liveFiles chunks · $livePrompts ranked lists")
        return total
    }

    // ---------------- collected library ----------------

    /** files: batch files, oldest first. cursor: position in [WikidataPool.order]. skip: template id -> retry-after (epoch ms). */
    private class Meta(
        val files: List<String> = emptyList(),
        val next: Int = 1,
        val cursor: Int = 0,
        val lastAuto: Long = 0L,
        val lastAt: Long = 0L,
        val skip: Map<String, Long> = emptyMap(),
    )

    private fun readMeta(): Meta = runCatching {
        val o = Json.parseToJsonElement(Platform.readText(META) ?: return Meta()).jsonObject
        Meta(
            files = o["files"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            next = o["next"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1,
            cursor = o["cursor"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            lastAuto = o["lastAuto"]?.jsonPrimitive?.longOrNull ?: 0L,
            lastAt = o["lastAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            skip = o["skip"]?.jsonObject?.mapValues { it.value.jsonPrimitive.longOrNull ?: 0L }.orEmpty(),
        )
    }.getOrElse { Meta() }

    private fun writeMeta(m: Meta) {
        val now = Platform.epochMillis()
        val o = buildJsonObject {
            put("files", JsonArray(m.files.map { JsonPrimitive(it) }))
            put("next", m.next)
            put("cursor", m.cursor)
            put("lastAuto", m.lastAuto)
            put("lastAt", m.lastAt)
            put("skip", buildJsonObject { for ((k, v) in m.skip) if (v > now) put(k, v) })
        }
        Platform.writeText(META, o.toString())
    }

    private suspend fun loadLibrary(): Int {
        val meta = readMeta()
        val pool = WikidataPool.all.mapTo(HashSet()) { it.id }
        var n = 0
        val keptFiles = ArrayList<String>()
        for (f in meta.files) {
            val ps = runCatching { PackCodec.parse(Platform.readText(f) ?: return@runCatching emptyList(), SourceKind.WIKIDATA) }
                .getOrElse { emptyList() }
            // retired (too specialised) or discarded lists are deleted from the device
            val keep = ps.filter { it.id in pool && it.id !in Discards.ids }
            if (keep.size != ps.size) {
                if (keep.isEmpty()) runCatching { Platform.delete(f) }
                else runCatching { Platform.writeText(f, PackCodec.encodeRanked(keep, Platform.epochMillis())) }
            }
            if (keep.isNotEmpty()) keptFiles += f
            putAll(keep)
            n += keep.size
        }
        if (keptFiles.size != meta.files.size) writeMeta(Meta(keptFiles, meta.next, meta.cursor, meta.lastAuto, meta.lastAt, meta.skip))
        collectStatus { it.copy(library = n, lastAt = meta.lastAt) }
        return n
    }

    /**
     * Discard a prompt: hidden forever; collected lists are also deleted from the device's files.
     * Returns the removed prompt (for undo) or null.
     */
    suspend fun discard(id: String): Prompt? = withContext(Dispatchers.Default) {
        val p = get(id)
        Discards.add(id)
        lock.withLock { byId.remove(id); publish() }
        guarded("discard", Unit) {
            val meta = readMeta()
            val files = ArrayList<String>()
            for (f in meta.files) {
                val ps = runCatching { PackCodec.parse(Platform.readText(f) ?: "", SourceKind.WIKIDATA) }.getOrElse { emptyList() }
                if (ps.none { it.id == id }) { files += f; continue }
                val keep = ps.filter { it.id != id }
                if (keep.isEmpty()) Platform.delete(f) else { Platform.writeText(f, PackCodec.encodeRanked(keep, Platform.epochMillis())); files += f }
            }
            if (files.size != meta.files.size) writeMeta(Meta(files, meta.next, meta.cursor, meta.lastAuto, meta.lastAt, meta.skip))
            for (k in listOf(SourceKind.WIKIPEDIA, SourceKind.REMOTE, SourceKind.WIKIDATA)) {
                val c = readCache(k)
                if (c.any { it.id == id }) writeCache(k, c.filter { it.id != id }).also { if (c.size == 1) Platform.delete(cacheFile(k)) }
            }
            collectStatus { it.copy(library = snapshot.count { q -> q.id.startsWith("wx.") }) }
        }
        p
    }

    /** Undo a discard: back in play (a collected list is written back as its own small batch). */
    suspend fun restore(p: Prompt): Unit = withContext(Dispatchers.Default) {
        Discards.remove(p.id)
        putAll(listOf(p))
        if (p.id.startsWith("wx.")) guarded("restore", Unit) {
            val meta = readMeta()
            val name = "lib_" + meta.next.toString().padStart(5, '0') + ".json"
            Platform.writeText(name, PackCodec.encodeRanked(listOf(p), Platform.epochMillis()))
            writeMeta(Meta(meta.files + name, meta.next + 1, meta.cursor, meta.lastAuto, meta.lastAt, meta.skip))
        } else if (p.source != SourceKind.BUNDLED) guarded("restore", Unit) {
            val c = readCache(p.source)
            if (c.none { it.id == p.id }) writeCache(p.source, c + p)
        }
        Unit
    }

    /** "Restore discarded questions": forget the list; built-in ones come back on the next start. */
    fun clearDiscards() = Discards.clear()

    /**
     * Fetches up to [n] lists the device has not got yet. Lists that come back too small are skipped for
     * 60 days; network failures for 2 days. Stops early when the service is clearly unreachable.
     */
    private suspend fun collectNow(n: Int, auto: Boolean) {
        if (!collectLock.tryLock()) return
        try {
            var meta = readMeta()
            val pool = WikidataPool.order
            val now = Platform.epochMillis()
            val have = snapshot.mapTo(HashSet()) { it.id }.apply { addAll(Discards.ids) }
            val picked = ArrayList<WikidataTemplate>()
            var cursor = meta.cursor
            var scanned = 0
            while (picked.size < n && scanned < pool.size) {
                val t = pool[cursor.mod(pool.size)]
                cursor++; scanned++
                if (t.id in have || (meta.skip[t.id] ?: 0L) > now) continue
                picked += t
            }
            if (picked.isEmpty()) {
                collectStatus { it.copy(running = false, message = "nothing new left to collect right now") }
                return
            }
            collectStatus { it.copy(running = true, done = 0, total = picked.size, added = 0, message = "") }
            status(SourceKind.WIKIDATA) { it.copy(state = SyncState.SYNCING, done = 0, total = picked.size, detail = "") }

            val got = ArrayList<Prompt>()
            val skip = HashMap(meta.skip)
            var failures = 0
            var lastErr: String? = null
            var nextIndex = 0
            var stop = false
            val mx = Mutex()
            // a few requests at a time; each finished list is classified (category, niche, people) and playable at once
            coroutineScope {
                repeat(PARALLEL) {
                    launch {
                        while (true) {
                            val t = mx.withLock { if (stop || nextIndex >= picked.size) null else picked[nextIndex++] } ?: break
                            var fetched: Pair<Prompt, Int>? = null
                            var err: Throwable? = null
                            try { fetched = fetchWikidata(t) } catch (e: CancellationException) { throw e } catch (e: Throwable) { err = e }
                            val p = fetched?.takeIf { it.second >= t.famousMin && it.first.size >= WikidataTemplates.minAnswers(t) }?.first
                            mx.withLock {
                                when {
                                    err != null -> { failures++; lastErr = err.message ?: err::class.simpleName; skip[t.id] = now + 2 * DAY }
                                    p == null -> skip[t.id] = now + 60 * DAY
                                    else -> got += p
                                }
                                if (got.isEmpty() && failures >= 4) stop = true
                            }
                            if (p != null) putAll(listOf(p))
                            collectStatus { it.copy(done = it.done + 1, added = got.size) }
                            status(SourceKind.WIKIDATA) { it.copy(done = it.done + 1) }
                            delay(250)
                        }
                    }
                }
            }

            var files = meta.files
            var next = meta.next
            if (got.isNotEmpty()) {
                val name = "lib_" + next.toString().padStart(5, '0') + ".json"
                Platform.writeText(name, PackCodec.encodeRanked(got, now))
                files = files + name
                next++
                // keep the library bounded: drop the oldest batches
                while (files.size > MAX_LIBRARY_FILES) {
                    val old = files.first()
                    files = files.drop(1)
                    val ids = runCatching {
                        PackCodec.parse(Platform.readText(old) ?: "", SourceKind.WIKIDATA).map { it.id }
                    }.getOrElse { emptyList() }
                    removeAll(ids)
                    Platform.delete(old)
                }
            }
            meta = Meta(files, next, cursor, if (auto) now else meta.lastAuto, if (got.isNotEmpty()) now else meta.lastAt, skip)
            writeMeta(meta)

            val libCount = snapshot.count { it.id.startsWith("wx.") }
            val msg = when {
                got.isNotEmpty() -> "+${got.size} new list${if (got.size == 1) "" else "s"}"
                failures > 0 -> "offline? ${lastErr?.take(60) ?: ""}"
                else -> "no new lists in this batch"
            }
            collectStatus { it.copy(running = false, added = got.size, library = libCount, lastAt = meta.lastAt, message = msg) }
            status(SourceKind.WIKIDATA) {
                it.copy(state = if (got.isEmpty() && failures > 0) SyncState.ERROR else SyncState.OK, detail = msg)
            }
        } finally {
            collectStatus { if (it.running) it.copy(running = false) else it }
            collectLock.unlock()
        }
    }

    // ---------------- cache (wikipedia / remote / legacy wikidata) ----------------

    private fun cacheFile(kind: SourceKind) = "cache_${kind.name.lowercase()}.json"

    private fun readCache(kind: SourceKind): List<Prompt> = runCatching {
        val t = Platform.readText(cacheFile(kind)) ?: return emptyList()
        PackCodec.parse(t, kind)
    }.getOrElse { emptyList() }

    private val bundledAt = HashMap<SourceKind, Long>()

    private fun isStale(kind: SourceKind, now: Long, maxAge: Long): Boolean {
        val cached = runCatching { Platform.readText(cacheFile(kind))?.let { PackCodec.cacheTime(it) } }.getOrNull()
        val at = maxOf(cached ?: 0L, bundledAt[kind] ?: 0L)
        return at == 0L || now - at > maxAge
    }

    private fun writeCache(kind: SourceKind, prompts: List<Prompt>) {
        if (prompts.isEmpty()) return
        runCatching { Platform.writeText(cacheFile(kind), PackCodec.encodeRanked(prompts, Platform.epochMillis())) }
    }

    // ---------------- http helpers ----------------

    private val lastError = HashMap<SourceKind, String>()

    private class Fail(msg: String) : Exception(msg)

    /** Runs a request; on 429/503 waits Retry-After (≤ 20 s) and retries once. Throws [Fail] with a readable reason. */
    private suspend fun call(block: suspend () -> HttpResponse): String {
        repeat(2) { attempt ->
            val res = try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                throw Fail("${e::class.simpleName ?: "error"}: ${e.message?.take(70) ?: "no message"}")
            }
            val code = res.status.value
            if (res.status.isSuccess()) return res.bodyAsText()
            val body = runCatching { res.bodyAsText() }.getOrDefault("")
            if ((code == 429 || code == 503) && attempt == 0) {
                val wait = res.headers["Retry-After"]?.toLongOrNull()?.coerceIn(1, 20) ?: 5
                delay(wait * 1000)
                return@repeat
            }
            val hint = body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(80)
            throw Fail("HTTP $code${if (hint.isNotEmpty()) " · $hint" else ""}")
        }
        throw Fail("HTTP 429 · rate limited")
    }

    private fun failDetail(ok: Int, failed: Int, kind: SourceKind): String {
        val base = if (failed > 0) "$ok ok · $failed failed" else "$ok ok"
        val err = lastError[kind]
        return if (failed > 0 && err != null) "$base · $err" else base
    }

    /** The parsed list and how many of its answers are widely known. */
    private suspend fun fetchWikidata(t: WikidataTemplate): Pair<Prompt, Int> {
        val body = call {
            http.get(WikiNet.WDQS) {
                parameter("query", WikidataTemplates.query(t))
                parameter("format", "json")
                header("Accept", "application/sparql-results+json")
                header("User-Agent", WikiNet.USER_AGENT)
            }
        }
        return WikidataTemplates.parse(t, body) to WikidataTemplates.famousCount(body)
    }

    // ---------------- wikipedia ----------------

    private suspend fun syncWikipedia() {
        val templates = WikipediaTemplates.all
        lastError.remove(SourceKind.WIKIPEDIA)
        status(SourceKind.WIKIPEDIA) { it.copy(state = SyncState.SYNCING, done = 0, total = templates.size, detail = "") }
        val got = ArrayList<Prompt>()
        var errors = 0
        for (t in templates) {
            val p = try { fetchWikipedia(t) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                lastError[SourceKind.WIKIPEDIA] = e.message ?: "error"; null
            }
            if (p != null && p.size >= 24) { putAll(listOf(p)); got += p } else errors++
            status(SourceKind.WIKIPEDIA) { it.copy(done = it.done + 1) }
            if (got.isEmpty() && errors >= 4) break
            delay(300)
        }
        writeCache(SourceKind.WIKIPEDIA, merged(SourceKind.WIKIPEDIA, got))
        status(SourceKind.WIKIPEDIA) {
            it.copy(state = if (got.isEmpty()) SyncState.ERROR else SyncState.OK,
                detail = failDetail(got.size, templates.size - got.size, SourceKind.WIKIPEDIA))
        }
    }

    /** 1 request for the list page + 1–2 SPARQL requests for the ranking. */
    private suspend fun fetchWikipedia(t: WikipediaTemplate): Prompt? {
        val body = call {
            http.get(WikiNet.WP_API) {
                parameter("action", "parse")
                parameter("page", t.page)
                parameter("prop", "text")
                parameter("format", "json")
                parameter("formatversion", "2")
                parameter("redirects", "1")
                header("User-Agent", WikiNet.USER_AGENT)
                header("Api-User-Agent", WikiNet.USER_AGENT)
            }
        }
        val html = WikipediaTemplates.parseHtml(body) ?: throw Fail("parse: no html")
        val items = WikipediaTemplates.extract(html, t.max)
        if (items.size < 8) throw Fail("parse: only ${items.size} items")

        val links = HashMap<String, Int>()
        for (chunk in items.chunked(300)) {
            val res = try {
                call {
                    http.submitForm(
                        url = WikiNet.WDQS,
                        formParameters = parameters {
                            append("query", WikipediaTemplates.sitelinksQuery(chunk.map { it.title }))
                            append("format", "json")
                        },
                    ) {
                        header("Accept", "application/sparql-results+json")
                        header("User-Agent", WikiNet.USER_AGENT)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { null } ?: continue
            runCatching { links.putAll(WikipediaTemplates.parseSitelinks(res)) }
        }
        return WikipediaTemplates.rank(t, items, links)
    }

    // ---------------- remote packs ----------------

    private suspend fun syncRemote(urls: List<String>) {
        status(SourceKind.REMOTE) { it.copy(state = SyncState.SYNCING, done = 0, total = urls.size, detail = "") }
        val got = ArrayList<Prompt>()
        val queue = ArrayDeque(urls)
        val visited = HashSet<String>()
        var errors = 0
        while (queue.isNotEmpty() && visited.size < 40) {
            val url = queue.removeFirst().trim()
            if (url.isEmpty() || !visited.add(url)) continue
            try {
                val res = http.get(url) { header("User-Agent", WikiNet.USER_AGENT) }
                if (!res.status.isSuccess()) error("HTTP ${res.status.value}")
                val text = res.bodyAsText()
                val root = Json.parseToJsonElement(text).jsonObject
                val more = root["packs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (more != null) {
                    val base = url.substringBeforeLast('/')
                    more.forEach { queue.addLast(if (it.startsWith("http")) it else "$base/$it") }
                } else {
                    val prompts = PackCodec.parse(text, SourceKind.REMOTE).map {
                        Prompt("rm." + it.id, it.text, it.topic, SourceKind.REMOTE, it.entries)
                    }
                    putAll(prompts); got += prompts
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { errors++ }
            status(SourceKind.REMOTE) { it.copy(done = it.done + 1) }
        }
        writeCache(SourceKind.REMOTE, got)
        status(SourceKind.REMOTE) {
            it.copy(state = if (got.isEmpty()) SyncState.ERROR else SyncState.OK,
                detail = if (errors > 0) "${got.size} ok · $errors failed" else "${got.size} ok")
        }
    }

    /** New results + previously cached prompts of that source that failed this time. */
    private fun merged(kind: SourceKind, fresh: List<Prompt>): List<Prompt> {
        val ids = fresh.map { it.id }.toHashSet()
        return fresh + readCache(kind).filter { it.id !in ids }
    }

    // ---------------- verification of unlisted answers ----------------

    private val verifyLock = Mutex()

    /**
     * Checks an answer that is not in the list against Wikidata (one SPARQL request, ≤ 6 s).
     * Returns null when the prompt has no class pattern, the answer is not found, or anything fails.
     */
    /** Online check of an answer that is not in the list (Wikidata). Results are cached on the device. */
    suspend fun verify(prompt: Prompt, input: String): WikidataTemplates.Verified? {
        val where = prompt.check ?: return null
        val key = prompt.id + "|" + app.coredump.core.Norm.compact(app.coredump.core.Norm.key(input))
        verifyLock.withLock { VerifyCache.get(key) }?.let { return it.result }
        var answered = false
        val result = try {
            withTimeoutOrNull(6_000) {
                val body = call {
                    http.get(WikiNet.WDQS) {
                        parameter("query", WikidataTemplates.verifyQuery(where, input))
                        parameter("format", "json")
                        header("Accept", "application/sparql-results+json")
                        header("User-Agent", WikiNet.USER_AGENT)
                    }
                }
                answered = true
                WikidataTemplates.parseVerify(body, input)
            }
        } catch (e: CancellationException) {
            null   // timeout or screen left: treat as "not verified" (and don't remember it)
        } catch (e: Throwable) {
            null
        }
        if (answered) verifyLock.withLock { VerifyCache.put(key, result) }
        return result
    }

    companion object {
        /** Lists fetched per batch (daily and on request). */
        const val DAILY_BATCH = 30
        /** Lists fetched at the same time. */
        const val PARALLEL = 2
        const val MAX_LIBRARY_FILES = 80
        const val DAY = 24L * 3600 * 1000
        const val WEEK = 7 * DAY
        /** A new batch at most every 20 h (so "daily" also works when the app is opened at varying times). */
        const val AUTO_EVERY = 20L * 3600 * 1000
        private const val META = "lib_meta.json"
    }
}
