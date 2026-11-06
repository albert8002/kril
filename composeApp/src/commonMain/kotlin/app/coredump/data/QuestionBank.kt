package app.coredump.data

import app.coredump.core.PackCodec
import app.coredump.core.Prompt
import app.coredump.core.SourceKind
import app.coredump.platform.Platform
import app.coredump.res.Res
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.parameters
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** prompts / answers: totals across all sources (counts). */
data class BankState(
    val prompts: Int = 0,
    val answers: Int = 0,
    val sources: Map<SourceKind, SourceStatus> = SourceKind.entries.associateWith { SourceStatus() },
    val ready: Boolean = false,
)

/**
 * Holds every prompt from every source, keyed by prompt id.
 * boot(): loads bundled packs + caches (fast, awaited by the boot screen),
 * then refreshes stale live sources in the background.
 */
class QuestionBank(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val byId = LinkedHashMap<String, Prompt>()
    private val _state = MutableStateFlow(BankState())
    val state: StateFlow<BankState> = _state.asStateFlow()
    private var syncJob: Job? = null

    private val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 70_000
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    fun all(): List<Prompt> = snapshot
    private var snapshot: List<Prompt> = emptyList()

    fun get(id: String): Prompt? = snapshot.firstOrNull { it.id == id }

    fun topics(): List<Pair<String, Int>> =
        snapshot.groupingBy { it.topic }.eachCount().toList().sortedBy { it.first }

    private suspend fun putAll(list: List<Prompt>) = lock.withLock {
        for (p in list) byId[p.id] = p
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

    /** Fast part of boot. [log] receives terminal lines for the boot screen. */
    suspend fun boot(settings: Settings, log: (String) -> Unit) {
        withContext(Dispatchers.Default) {
            val bundled = loadBundled(log)
            putAll(bundled)
            status(SourceKind.BUNDLED) { it.copy(state = SyncState.OK, detail = "${bundled.size} prompts") }
            for (kind in listOf(SourceKind.WIKIDATA, SourceKind.WIKIPEDIA, SourceKind.REMOTE)) {
                val cached = readCache(kind)
                if (cached.isNotEmpty()) {
                    putAll(cached)
                    log("[ OK ] cache /var/${kind.tag.lowercase()}: ${cached.size} prompts, ${cached.sumOf { it.size }} answers")
                } else {
                    log("[ -- ] cache /var/${kind.tag.lowercase()}: empty")
                }
            }
            // Warm hash indexes of a sample so the first game starts instantly.
            snapshot.shuffled().take(24).forEach { it.index }
            log("[ OK ] hashed ${_state.value.answers} answers into ${_state.value.prompts} indexes")
            _state.update { it.copy(ready = true) }
        }
        sync(settings, force = false)
    }

    /** Background refresh of live sources (stale caches only, unless [force]). */
    fun sync(settings: Settings, force: Boolean) {
        syncJob?.cancel()
        syncJob = scope.launch(Dispatchers.Default) {
            val now = Platform.epochMillis()
            if (settings.useWikidata) {
                if (force || isStale(SourceKind.WIKIDATA, now, 2 * WEEK)) syncWikidata()
                else status(SourceKind.WIKIDATA) { it.copy(state = SyncState.OK, detail = "cached") }
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

    fun purgeCaches() {
        for (k in listOf(SourceKind.WIKIDATA, SourceKind.WIKIPEDIA, SourceKind.REMOTE)) Platform.delete(cacheFile(k))
    }

    // ---------------- bundled ----------------

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun loadBundled(log: (String) -> Unit): List<Prompt> {
        val index = runCatching { Res.readBytes("files/packs/index.json").decodeToString() }.getOrNull()
            ?: return emptyList<Prompt>().also { log("[FAIL] /packs/index.json missing") }
        val files = Json.parseToJsonElement(index).jsonObject["packs"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val out = ArrayList<Prompt>()
        for (f in files) {
            val text = runCatching { Res.readBytes("files/packs/$f").decodeToString() }.getOrNull()
            if (text == null) { log("[FAIL] /packs/$f unreadable"); continue }
            val prompts = runCatching { PackCodec.parse(text, SourceKind.BUNDLED) }.getOrElse {
                log("[FAIL] /packs/$f: ${it.message}"); emptyList()
            }
            out += prompts
            // build-time snapshot of a live source: remember its age so boot doesn't refetch a fresh one
            PackCodec.cacheTime(text)?.let { at -> prompts.firstOrNull()?.source?.let { bundledAt[it] = at } }
            log("[ OK ] mount /packs/$f  ${prompts.size} prompts / ${prompts.sumOf { it.size }} answers")
        }
        return out
    }

    // ---------------- cache ----------------

    private fun cacheFile(kind: SourceKind) = "cache_${kind.name.lowercase()}.json"

    private fun readCache(kind: SourceKind): List<Prompt> = runCatching {
        val t = Platform.readText(cacheFile(kind)) ?: return emptyList()
        PackCodec.parse(t, kind)
    }.getOrElse { emptyList() }

    private val bundledAt = HashMap<SourceKind, Long>()

    private fun isStale(kind: SourceKind, now: Long, maxAge: Long): Boolean {
        val cached = Platform.readText(cacheFile(kind))?.let { PackCodec.cacheTime(it) }
        val at = maxOf(cached ?: 0L, bundledAt[kind] ?: 0L)
        return at == 0L || now - at > maxAge
    }

    private fun writeCache(kind: SourceKind, prompts: List<Prompt>) {
        if (prompts.isEmpty()) return
        runCatching { Platform.writeText(cacheFile(kind), PackCodec.encodeRanked(prompts, Platform.epochMillis())) }
    }

    // ---------------- http helpers ----------------

    /** Short human-readable reason for the last failure of each source (shown in the UI). */
    private val lastError = HashMap<SourceKind, String>()

    private class Fail(msg: String) : Exception(msg)

    /** Runs a request; on 429/503 waits Retry-After (≤ 20 s) and retries once. Throws [Fail] with a readable reason. */
    private suspend fun call(kind: SourceKind, block: suspend () -> HttpResponse): String {
        repeat(2) { attempt ->
            val res = try { block() } catch (e: Throwable) {
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

    // ---------------- wikidata ----------------

    private suspend fun syncWikidata() {
        // Devices refresh a rotating slice (the full set ships pre-ranked inside the app).
        val all = WikidataTemplates.all
        val start = (Platform.readText("wd_rotor.txt")?.trim()?.toIntOrNull() ?: 0).mod(all.size)
        val templates = (all.drop(start) + all.take(start)).take(DEVICE_SLICE)
        runCatching { Platform.writeText("wd_rotor.txt", ((start + DEVICE_SLICE) % all.size).toString()) }
        lastError.remove(SourceKind.WIKIDATA)
        status(SourceKind.WIKIDATA) { it.copy(state = SyncState.SYNCING, done = 0, total = templates.size, detail = "") }
        val got = ArrayList<Prompt>()
        var errors = 0
        // WDQS: sequential, polite gap; stop early if the service is clearly blocking us.
        for (t in templates) {
            val p = try { fetchWikidata(t) } catch (e: Fail) { lastError[SourceKind.WIKIDATA] = e.message ?: "error"; null }
            if (p != null && p.size >= WikidataTemplates.minAnswers(t)) { putAll(listOf(p)); got += p } else errors++
            status(SourceKind.WIKIDATA) { it.copy(done = it.done + 1) }
            if (got.isEmpty() && errors >= 4) break
            delay(250)
        }
        writeCache(SourceKind.WIKIDATA, merged(SourceKind.WIKIDATA, got))
        status(SourceKind.WIKIDATA) {
            it.copy(state = if (got.isEmpty()) SyncState.ERROR else SyncState.OK,
                detail = failDetail(got.size, templates.size - got.size, SourceKind.WIKIDATA))
        }
    }

    private suspend fun fetchWikidata(t: WikidataTemplate): Prompt {
        val body = call(SourceKind.WIKIDATA) {
            http.get(WikiNet.WDQS) {
                parameter("query", WikidataTemplates.query(t))
                parameter("format", "json")
                header("Accept", "application/sparql-results+json")
                header("User-Agent", WikiNet.USER_AGENT)
            }
        }
        return WikidataTemplates.parse(t, body)
    }

    // ---------------- wikipedia ----------------

    private suspend fun syncWikipedia() {
        val templates = WikipediaTemplates.all
        lastError.remove(SourceKind.WIKIPEDIA)
        status(SourceKind.WIKIPEDIA) { it.copy(state = SyncState.SYNCING, done = 0, total = templates.size, detail = "") }
        val got = ArrayList<Prompt>()
        var errors = 0
        for (t in templates) {
            val p = try { fetchWikipedia(t) } catch (e: Fail) { lastError[SourceKind.WIKIPEDIA] = e.message ?: "error"; null }
            if (p != null && p.size >= 8) { putAll(listOf(p)); got += p } else errors++
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
        val body = call(SourceKind.WIKIPEDIA) {
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
            val res = runCatching {
                call(SourceKind.WIKIPEDIA) {
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
            }.getOrNull() ?: continue   // unranked items still play; they just keep page order
            links.putAll(WikipediaTemplates.parseSitelinks(res))
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
            runCatching {
                val res = http.get(url) { header("User-Agent", WikiNet.USER_AGENT) }
                if (!res.status.isSuccess()) error("HTTP ${res.status.value}")
                val text = res.bodyAsText()
                val root = Json.parseToJsonElement(text).jsonObject
                val more = root["packs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (more != null) {
                    // index file: relative entries resolve against the index URL
                    val base = url.substringBeforeLast('/')
                    more.forEach { queue.addLast(if (it.startsWith("http")) it else "$base/$it") }
                } else {
                    val prompts = PackCodec.parse(text, SourceKind.REMOTE).map {
                        Prompt("rm." + it.id, it.text, it.topic, SourceKind.REMOTE, it.entries)
                    }
                    putAll(prompts); got += prompts
                }
            }.onFailure { errors++ }
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

    private val verifyCache = HashMap<String, WikidataTemplates.Verified?>()
    private val verifyLock = Mutex()

    /**
     * Checks an answer that is not in the list against Wikidata (one SPARQL request, ≤ 6 s).
     * Returns null when the prompt has no class pattern, the answer is not found, or the network fails.
     */
    suspend fun verify(prompt: Prompt, input: String): WikidataTemplates.Verified? {
        val where = prompt.check ?: return null
        val key = prompt.id + "|" + app.coredump.core.Norm.key(input)
        verifyLock.withLock { if (verifyCache.containsKey(key)) return verifyCache[key] }
        val result = withTimeoutOrNull(6_000) {
            runCatching {
                val body = call(SourceKind.WIKIDATA) {
                    http.get(WikiNet.WDQS) {
                        parameter("query", WikidataTemplates.verifyQuery(where, input))
                        parameter("format", "json")
                        header("Accept", "application/sparql-results+json")
                        header("User-Agent", WikiNet.USER_AGENT)
                    }
                }
                WikidataTemplates.parseVerify(body, input)
            }.getOrNull()
        }
        verifyLock.withLock { verifyCache[key] = result }
        return result
    }

    companion object {
        const val DEVICE_SLICE = 30
        const val DAY = 24L * 3600 * 1000
        const val WEEK = 7 * DAY
    }
}
