package app.coredump.snapshot

import app.coredump.core.PackCodec
import app.coredump.core.Prompt
import app.coredump.core.SourceKind
import app.coredump.core.stableHash
import app.coredump.data.WikiNet
import app.coredump.data.WikidataTemplates
import app.coredump.data.WikipediaTemplates
import app.coredump.net.Brokers
import app.coredump.net.Mqtt
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Usage: snapshot <packsDir>
 * Writes live_wikidata.json + live_wikipedia.json (ranked format) and registers them in index.json.
 * Also self-tests answer verification and the MQTT relay against the real services.
 * Never fails the build: whatever succeeds is written.
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "packs" })
    dir.mkdirs()
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
    val budgetMs = (System.getenv("SNAP_BUDGET_MIN")?.toLongOrNull() ?: 15L) * 60_000
    val t0All = System.currentTimeMillis()
    fun overBudget() = System.currentTimeMillis() - t0All > budgetMs
    fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    fun log(s: String) = synchronized(System.out) { println(s); System.out.flush() }

    fun send(req: HttpRequest): String {
        repeat(2) { attempt ->
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            val code = res.statusCode()
            if (code in 200..299) return res.body()
            if ((code == 429 || code == 503 || code == 502) && attempt < 1) {
                val wait = res.headers().firstValue("Retry-After").orElse("5").toLongOrNull()?.coerceIn(1, 10) ?: 5
                Thread.sleep(wait * 1000)
                return@repeat
            }
            error("HTTP $code ${res.body().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(160)}")
        }
        error("rate limited")
    }

    fun get(url: String, accept: String = "application/json") = send(
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(65))
            .header("User-Agent", WikiNet.USER_AGENT).header("Accept", accept).GET().build()
    )

    fun postForm(url: String, form: Map<String, String>) = send(
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(50))
            .header("User-Agent", WikiNet.USER_AGENT)
            .header("Accept", "application/sparql-results+json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }))
            .build()
    )

    log("UA: ${WikiNet.USER_AGENT}  templates: ${WikidataTemplates.all.size} wikidata, ${WikipediaTemplates.all.size} wikipedia")

    // ---- relay self-test first (fast, independent of Wikimedia)
    relaySelfTest(http, ::log)

    // ---- previous snapshot (restored from the CI cache): keep what is fresh, fetch what is missing or stale
    val stateFile = File(dir, "snap_state.tsv")          // id \t fetchedAtMs \t templateHash \t kept(1/0)
    class St(val at: Long, val hash: Int, val kept: Boolean)
    val state = java.util.concurrent.ConcurrentHashMap<String, St>()
    runCatching {
        if (stateFile.exists()) for (l in stateFile.readLines()) {
            val f = l.split('\t'); if (f.size >= 4) state[f[0]] = St(f[1].toLong(), f[2].toInt(), f[3] == "1")
        }
    }
    fun loadPrev(name: String, kind: SourceKind): MutableMap<String, Prompt> {
        val m = java.util.concurrent.ConcurrentHashMap<String, Prompt>()
        runCatching { File(dir, name).takeIf { it.exists() }?.let { f -> PackCodec.parse(f.readText(), kind).forEach { m[it.id] = it } } }
        return m
    }
    val wdOut = loadPrev("live_wikidata.json", SourceKind.WIKIDATA)
    val wpOut = loadPrev("live_wikipedia.json", SourceKind.WIKIPEDIA)
    // older caches have no state file: trust their prompts with the file's own timestamp
    fun fileAt(name: String) = runCatching { PackCodec.cacheTime(File(dir, name).readText()) }.getOrNull() ?: System.currentTimeMillis()
    log("SNAP restored ${wdOut.size} wikidata + ${wpOut.size} wikipedia prompts, ${state.size} state rows")
    val freshMs = 10L * 24 * 3600 * 1000
    fun hashWd(t: app.coredump.data.WikidataTemplate) = stableHash("${t.where}|${t.prompt}|${t.limit}|${t.minLinks}|${t.topic}")
    fun hashWp(t: app.coredump.data.WikipediaTemplate) = stableHash("${t.page}|${t.prompt}|${t.max}|${t.topic}")
    fun isFresh(id: String, hash: Int, have: Boolean): Boolean {
        val st = state[id] ?: return false
        return st.hash == hash && System.currentTimeMillis() - st.at < freshMs && (have || !st.kept)
    }
    for (id in wdOut.keys) WikidataTemplates.all.firstOrNull { it.id == id }?.let { t ->
        state.putIfAbsent(id, St(fileAt("live_wikidata.json"), hashWd(t), true)) }
    for (id in wpOut.keys) WikipediaTemplates.all.firstOrNull { it.id == id }?.let { t ->
        state.putIfAbsent(id, St(fileAt("live_wikipedia.json"), hashWp(t), true)) }
    // drop outputs whose template was removed or changed
    val wdIds = WikidataTemplates.all.associateBy { it.id }
    wdOut.keys.retainAll { id -> wdIds[id]?.let { state[id]?.hash == hashWd(it) } == true }
    val wpIds = WikipediaTemplates.all.associateBy { it.id }
    wpOut.keys.retainAll { id -> wpIds[id]?.let { state[id]?.hash == hashWp(it) } == true }

    // ---- wikipedia first (19 small pages, capped at 4 minutes)
    val wpEnd = System.currentTimeMillis() + 4 * 60_000
    for (t in WikipediaTemplates.all) {
        if (isFresh(t.id, hashWp(t), t.id in wpOut)) continue
        if (System.currentTimeMillis() > wpEnd || overBudget()) { log("SNAP skip (budget) ${t.id}"); continue }
        try {
            val body = get("${WikiNet.WP_API}?action=parse&format=json&formatversion=2&redirects=1&prop=text&page=${enc(t.page)}")
            val html = WikipediaTemplates.parseHtml(body) ?: error("no html")
            val items = WikipediaTemplates.extract(html, t.max)
            val links = HashMap<String, Int>()
            var rankErr: String? = null
            for (chunk in items.chunked(300)) {
                try {
                    links.putAll(WikipediaTemplates.parseSitelinks(postForm(WikiNet.WDQS,
                        mapOf("query" to WikipediaTemplates.sitelinksQuery(chunk.map { it.title }), "format" to "json"))))
                } catch (e: Exception) { rankErr = e.message?.take(120) }
                Thread.sleep(300)
            }
            val p = WikipediaTemplates.rank(t, items, links)
            val keep = p.size >= 24 && rankErr == null
            if (keep) wpOut[t.id] = p else wpOut.remove(t.id)
            if (rankErr == null) state[t.id] = St(System.currentTimeMillis(), hashWp(t), keep)
            log("SNAP wikipedia ${t.id.padEnd(30)} ${if (keep) "OK  " else "SMALL"} ${p.size.toString().padStart(5)}  ranked ${links.size}/${items.size}" +
                (rankErr?.let { "  rankErr: $it" } ?: "") + "  top: ${p.entries.take(3).joinToString { it.name }}")
        } catch (e: Exception) {
            log("SNAP wikipedia ${t.id.padEnd(30)} FAIL ${e.message?.take(200)}")
        }
        Thread.sleep(300)
    }

    // ---- verification self-test (same query the app sends for unlisted answers)
    val dog = WikidataTemplates.all.first { it.id == "wd.dog" }
    val cheese = WikidataTemplates.all.first { it.id == "wd.cheese" }
    for ((t, q) in listOf(dog to "Kai Ken", dog to "Shikoku Ken", dog to "banana", cheese to "Tête de Moine", cheese to "toaster")) {
        try {
            val body = get("${WikiNet.WDQS}?format=json&query=${enc(WikidataTemplates.verifyQuery(t.where, q))}", "application/sparql-results+json")
            val v = WikidataTemplates.parseVerify(body, q)
            log("SNAP verify ${t.id} '$q' -> ${v?.let { "${it.label} (${it.links} sitelinks)" } ?: "rejected"}")
        } catch (e: Exception) { log("SNAP verify ${t.id} '$q' -> FAIL ${e.message?.take(160)}") }
    }

    // ---- wikidata: missing first, then stale; 3 workers, then one retry pass for failures
    val todo = WikidataTemplates.all.filter { !isFresh(it.id, hashWd(it), it.id in wdOut) }
        .sortedBy { state[it.id]?.at ?: 0L }
    log("SNAP wikidata todo ${todo.size}/${WikidataTemplates.all.size}")
    val failed = Collections.synchronizedList(ArrayList<app.coredump.data.WikidataTemplate>())
    fun fetchWd(t: app.coredump.data.WikidataTemplate, pass: Int) {
        if (overBudget()) { log("SNAP skip (budget) ${t.id}"); return }
        val t0 = System.currentTimeMillis()
        try {
            val body = get("${WikiNet.WDQS}?format=json&query=${enc(WikidataTemplates.query(t))}", "application/sparql-results+json")
            val p = WikidataTemplates.parse(t, body)
            val ms = System.currentTimeMillis() - t0
            val keep = p.size >= WikidataTemplates.minAnswers(t)
            if (keep) wdOut[t.id] = p else wdOut.remove(t.id)
            state[t.id] = St(System.currentTimeMillis(), hashWd(t), keep)
            log("SNAP wikidata  ${t.id.padEnd(30)} ${if (keep) "OK  " else "SMALL"} ${p.size.toString().padStart(5)}  ${ms}ms  top: ${p.entries.take(3).joinToString { it.name }}")
        } catch (e: Exception) {
            if (pass == 0) failed += t
            log("SNAP wikidata  ${t.id.padEnd(30)} FAIL${pass} ${System.currentTimeMillis() - t0}ms ${e::class.simpleName}: ${e.message?.take(120)}")
        }
        Thread.sleep(250)
    }
    val pool = Executors.newFixedThreadPool(3)
    for (t in todo) pool.submit { fetchWd(t, 0) }
    pool.shutdown()
    pool.awaitTermination(budgetMs + 120_000, TimeUnit.MILLISECONDS)
    for (t in failed.toList()) { Thread.sleep(1500); fetchWd(t, 1) }

    val now = System.currentTimeMillis()
    val written = ArrayList<String>()
    val wdSorted = wdOut.values.sortedBy { it.id }
    val wpSorted = wpOut.values.sortedBy { it.id }
    if (wdSorted.isNotEmpty()) { File(dir, "live_wikidata.json").writeText(PackCodec.encodeRanked(wdSorted, now)); written += "live_wikidata.json" }
    if (wpSorted.isNotEmpty()) { File(dir, "live_wikipedia.json").writeText(PackCodec.encodeRanked(wpSorted, now)); written += "live_wikipedia.json" }
    stateFile.writeText(state.entries.sortedBy { it.key }.joinToString("\n") { (k, v) -> "$k\t${v.at}\t${v.hash}\t${if (v.kept) 1 else 0}" })

    val index = File(dir, "index.json")
    val files = Regex("\"([^\"]+\\.json)\"").findAll(if (index.exists()) index.readText() else "").map { it.groupValues[1] }.toMutableList()
    for (w in written) if (w !in files) files += w
    index.writeText("{\"packs\":[" + files.joinToString(",") { "\"$it\"" } + "]}")

    log("SNAP SUMMARY wikidata ${wdSorted.size}/${WikidataTemplates.all.size} prompts (${wdSorted.sumOf { it.size }} answers), " +
        "wikipedia ${wpSorted.size}/${WikipediaTemplates.all.size} prompts (${wpSorted.sumOf { it.size }} answers), " +
        "${(System.currentTimeMillis() - t0All) / 1000}s; index=$files")
}

/** Connects host + guest through each broker (TCP and WSS) and checks a message makes the round trip. */
private fun relaySelfTest(http: HttpClient, log: (String) -> Unit) {
    val room = "SELFTEST" + (1000..9999).random()
    for (b in Brokers.all) {
        // TCP
        try {
            val host = TcpMqtt(b.host, b.tcpPort, Brokers.topic(room, fromHost = false))
            val guest = TcpMqtt(b.host, b.tcpPort, Brokers.topic(room, fromHost = true))
            guest.publish(Brokers.topic(room, fromHost = false), "{\"k\":\"ping\"}")
            val got = host.awaitPublish(5000)
            log("SNAP relay ${b.host} tcp -> ${if (got != null) "OK ($got)" else "no message"}")
            host.close(); guest.close()
        } catch (e: Exception) { log("SNAP relay ${b.host} tcp -> FAIL ${e::class.simpleName}: ${e.message}") }
        // WSS (what the app tries first)
        try {
            val q = LinkedBlockingQueue<ByteArray>()
            val dec = Mqtt.Decoder()
            val ws = http.newWebSocketBuilder().subprotocols("mqtt").connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI(b.wss), object : WebSocket.Listener {
                    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                        val arr = ByteArray(data.remaining()); data.get(arr); q.add(arr)
                        webSocket.request(1); return null
                    }
                }).get(12, TimeUnit.SECONDS)
            ws.sendBinary(ByteBuffer.wrap(Mqtt.connect("cdselftest" + (1000..9999).random(), 30)), true).get(5, TimeUnit.SECONDS)
            val first = q.poll(6, TimeUnit.SECONDS)
            val pk = first?.let { dec.feed(it).firstOrNull() }
            log("SNAP relay ${b.host} wss -> ${pk ?: "no CONNACK"}")
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
        } catch (e: Exception) { log("SNAP relay ${b.host} wss -> FAIL ${e::class.simpleName}: ${e.message}") }
    }
}

private class TcpMqtt(host: String, port: Int, subscribeTo: String) {
    private val s = Socket()
    private val dec = Mqtt.Decoder()
    private val packets = LinkedBlockingQueue<Mqtt.Packet>()
    private val reader: Thread

    init {
        s.connect(InetSocketAddress(host, port), 8000)
        s.soTimeout = 0
        reader = Thread {
            val buf = ByteArray(4096)
            try {
                val inp = s.getInputStream()
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    dec.feed(buf.copyOf(n)).forEach { packets.add(it) }
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
        s.getOutputStream().write(Mqtt.connect("cdst" + (100000..999999).random(), 30))
        val ack = packets.poll(6, TimeUnit.SECONDS)
        require(ack is Mqtt.Packet.ConnAck && ack.returnCode == 0) { "connack: $ack" }
        s.getOutputStream().write(Mqtt.subscribe(1, subscribeTo))
        var p = packets.poll(6, TimeUnit.SECONDS)
        while (p != null && p !is Mqtt.Packet.SubAck) p = packets.poll(6, TimeUnit.SECONDS)
        require(p is Mqtt.Packet.SubAck) { "no suback" }
    }

    fun publish(topic: String, text: String) { s.getOutputStream().write(Mqtt.publish(topic, text.encodeToByteArray())) }

    fun awaitPublish(ms: Long): String? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val p = packets.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return null
            if (p is Mqtt.Packet.Publish) return p.payload.decodeToString()
        }
        return null
    }

    fun close() { runCatching { s.getOutputStream().write(Mqtt.disconnect()); s.close() } }
}

