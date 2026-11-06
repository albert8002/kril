package app.coredump.data

import app.coredump.core.PackCodec
import app.coredump.core.Prompt
import app.coredump.core.RawAnswer
import app.coredump.core.SourceKind
import app.coredump.core.Tiering
import app.coredump.platform.Platform
import app.coredump.res.Res
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
                if (force || isStale(SourceKind.WIKIDATA, now, WEEK)) syncWikidata()
                else status(SourceKind.WIKIDATA) { it.copy(state = SyncState.OK, detail = "cached") }
            } else status(SourceKind.WIKIDATA) { it.copy(state = SyncState.OFF) }

            if (settings.useWikipedia) {
                if (force || isStale(SourceKind.WIKIPEDIA, now, WEEK)) syncWikipedia()
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

    private fun isStale(kind: SourceKind, now: Long, maxAge: Long): Boolean {
        val t = Platform.readText(cacheFile(kind)) ?: return true
        val at = PackCodec.cacheTime(t) ?: return true
        return now - at > maxAge
    }

    private fun writeCache(kind: SourceKind, prompts: List<Prompt>) {
        if (prompts.isEmpty()) return
        runCatching { Platform.writeText(cacheFile(kind), PackCodec.encodeRanked(prompts, Platform.epochMillis())) }
    }

    // ---------------- wikidata ----------------

    private suspend fun syncWikidata() {
        val templates = WikidataTemplates.all
        status(SourceKind.WIKIDATA) { it.copy(state = SyncState.SYNCING, done = 0, total = templates.size, detail = "") }
        val gate = Semaphore(2) // WDQS asks clients to keep concurrency low
        val got = ArrayList<Prompt>()
        val gotLock = Mutex()
        var errors = 0
        coroutineScope {
          templates.map { t ->
            async {
                gate.withPermit {
                    val p = runCatching { fetchWikidata(t) }.getOrNull()
                    if (p != null && p.size >= 5) {
                        putAll(listOf(p))
                        gotLock.withLock { got += p }
                    } else errors++
                    status(SourceKind.WIKIDATA) { it.copy(done = it.done + 1) }
                }
            }
          }.awaitAll()
        }
        writeCache(SourceKind.WIKIDATA, merged(SourceKind.WIKIDATA, got))
        status(SourceKind.WIKIDATA) {
            it.copy(state = if (got.isEmpty()) SyncState.ERROR else SyncState.OK,
                detail = if (errors > 0) "${got.size} ok · $errors failed" else "${got.size} ok")
        }
    }

    private suspend fun fetchWikidata(t: WikidataTemplate): Prompt? {
        val res = http.get("https://query.wikidata.org/sparql") {
            parameter("query", WikidataTemplates.query(t))
            parameter("format", "json")
            header("Accept", "application/sparql-results+json")
            header("User-Agent", USER_AGENT)
        }
        if (!res.status.isSuccess()) return null
        return WikidataTemplates.parse(t, res.bodyAsText())
    }

    // ---------------- wikipedia ----------------

    private suspend fun syncWikipedia() {
        val templates = WikipediaTemplates.all
        status(SourceKind.WIKIPEDIA) { it.copy(state = SyncState.SYNCING, done = 0, total = templates.size, detail = "") }
        val got = ArrayList<Prompt>()
        var errors = 0
        for (t in templates) {
            val p = runCatching { fetchWikipedia(t) }.getOrNull()
            if (p != null && p.size >= 8) { putAll(listOf(p)); got += p } else errors++
            status(SourceKind.WIKIPEDIA) { it.copy(done = it.done + 1) }
        }
        writeCache(SourceKind.WIKIPEDIA, merged(SourceKind.WIKIPEDIA, got))
        status(SourceKind.WIKIPEDIA) {
            it.copy(state = if (got.isEmpty()) SyncState.ERROR else SyncState.OK,
                detail = if (errors > 0) "${got.size} ok · $errors failed" else "${got.size} ok")
        }
    }

    private suspend fun fetchWikipedia(t: WikipediaTemplate): Prompt? {
        val res = http.get(WP_API) {
            parameter("action", "parse")
            parameter("page", t.page)
            parameter("prop", "text")
            parameter("format", "json")
            parameter("formatversion", "2")
            parameter("redirects", "1")
            header("User-Agent", USER_AGENT)
        }
        if (!res.status.isSuccess()) return null
        val html = WikipediaTemplates.parseHtml(res.bodyAsText()) ?: return null
        val items = WikipediaTemplates.extract(html, t.max)
        if (items.size < 8) return null

        // page views, 20 titles per request, following "continue"
        val views = HashMap<String, Long>()
        for (chunk in items.chunked(20)) {
            var cont: Map<String, String> = emptyMap()
            var hops = 0
            do {
                val r = http.get(WP_API) {
                    parameter("action", "query")
                    parameter("prop", "pageviews")
                    parameter("pvipdays", "30")
                    parameter("titles", chunk.joinToString("|") { it.title })
                    parameter("redirects", "1")
                    parameter("format", "json")
                    parameter("formatversion", "2")
                    for ((k, v) in cont) parameter(k, v)
                    header("User-Agent", USER_AGENT)
                }
                if (!r.status.isSuccess()) break
                val v = WikipediaTemplates.parseViews(r.bodyAsText())
                for (item in chunk) {
                    val resolved = WikipediaTemplates.resolve(item.title, v.normalized)
                    val n = v.views[resolved] ?: continue
                    views[item.title] = maxOf(views[item.title] ?: 0L, n)
                }
                cont = v.cont
                hops++
            } while (v.cont.containsKey("pvipcontinue") && hops < 4)
            delay(60) // be polite
        }
        val ranked = items.sortedByDescending { views[it.title] ?: 0L }.map { item ->
            val alias = item.title.substringBefore(" (").trim()
            RawAnswer(item.text, if (!alias.equals(item.text, ignoreCase = true)) listOf(alias) else emptyList())
        }
        return Prompt(t.id, t.prompt, t.topic, SourceKind.WIKIPEDIA, Tiering.build(ranked, t.id))
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
                val res = http.get(url) { header("User-Agent", USER_AGENT) }
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

    companion object {
        const val USER_AGENT = "CoreDumpTrivia/1.0 (Kotlin Multiplatform; open-source trivia game)"
        const val WP_API = "https://en.wikipedia.org/w/api.php"
        const val DAY = 24L * 3600 * 1000
        const val WEEK = 7 * DAY
    }
}
