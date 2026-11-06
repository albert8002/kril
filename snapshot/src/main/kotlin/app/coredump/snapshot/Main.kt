package app.coredump.snapshot

import app.coredump.core.PackCodec
import app.coredump.core.Prompt
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
            if ((code == 429 || code == 503) && attempt < 1) {
                val wait = res.headers().firstValue("Retry-After").orElse("5").toLongOrNull()?.coerceIn(1, 10) ?: 5
                Thread.sleep(wait * 1000)
                return@repeat
            }
            error("HTTP $code ${res.body().replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(160)}")
        }
        error("rate limited")
    }

    fun get(url: String, accept: String = "application/json") = send(
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(50))
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

    // ---- wikidata: 3 workers (WDQS allows a few concurrent queries per client)
    val wd = Collections.synchronizedList(ArrayList<Prompt>())
    val pool = Executors.newFixedThreadPool(3)
    for (t in WikidataTemplates.all) pool.submit {
        if (overBudget()) { log("SNAP skip (budget) ${t.id}"); return@submit }
        val t0 = System.currentTimeMillis()
        try {
            val body = get("${WikiNet.WDQS}?format=json&query=${enc(WikidataTemplates.query(t))}", "application/sparql-results+json")
            val p = WikidataTemplates.parse(t, body)
            val ms = System.currentTimeMillis() - t0
            val keep = p.size >= WikidataTemplates.minAnswers(t)
            if (keep) wd += p
            log("SNAP wikidata  ${t.id.padEnd(30)} ${if (keep) "OK  " else "SMALL"} ${p.size.toString().padStart(5)}  ${ms}ms  top: ${p.entries.take(3).joinToString { it.name }}")
        } catch (e: Exception) {
            log("SNAP wikidata  ${t.id.padEnd(30)} FAIL ${System.currentTimeMillis() - t0}ms ${e::class.simpleName}: ${e.message?.take(160)}")
        }
        Thread.sleep(250)
    }
    pool.shutdown()
    pool.awaitTermination(budgetMs + 120_000, TimeUnit.MILLISECONDS)

    // ---- verification self-test (same query the app sends for unlisted answers)
    val dog = WikidataTemplates.all.first { it.id == "wd.dog" }
    val cheese = WikidataTemplates.all.first { it.id == "wd.cheese" }
    for ((t, q) in listOf(dog to "Kai Ken", dog to "Shikoku", dog to "banana", cheese to "Tête de Moine", cheese to "toaster")) {
        try {
            val body = get("${WikiNet.WDQS}?format=json&query=${enc(WikidataTemplates.verifyQuery(t.where, q))}", "application/sparql-results+json")
            val v = WikidataTemplates.parseVerify(body, q)
            log("SNAP verify ${t.id} '$q' -> ${v?.let { "${it.label} (${it.links} sitelinks)" } ?: "rejected"}")
        } catch (e: Exception) { log("SNAP verify ${t.id} '$q' -> FAIL ${e.message?.take(160)}") }
    }

    // ---- wikipedia
    val wp = ArrayList<Prompt>()
    for (t in WikipediaTemplates.all) {
        if (overBudget()) { log("SNAP skip (budget) ${t.id}"); continue }
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
            if (p.size >= 24) wp += p
            log("SNAP wikipedia ${t.id.padEnd(30)} OK   ${p.size.toString().padStart(5)}  ranked ${links.size}/${items.size}" +
                (rankErr?.let { "  rankErr: $it" } ?: "") + "  top: ${p.entries.take(3).joinToString { it.name }}")
        } catch (e: Exception) {
            log("SNAP wikipedia ${t.id.padEnd(30)} FAIL ${e.message?.take(200)}")
        }
        Thread.sleep(300)
    }

    val now = System.currentTimeMillis()
    val written = ArrayList<String>()
    val wdSorted = wd.sortedBy { it.id }
    if (wdSorted.isNotEmpty()) { File(dir, "live_wikidata.json").writeText(PackCodec.encodeRanked(wdSorted, now)); written += "live_wikidata.json" }
    if (wp.isNotEmpty()) { File(dir, "live_wikipedia.json").writeText(PackCodec.encodeRanked(wp, now)); written += "live_wikipedia.json" }

    val index = File(dir, "index.json")
    val files = Regex("\"([^\"]+\\.json)\"").findAll(if (index.exists()) index.readText() else "").map { it.groupValues[1] }.toMutableList()
    for (w in written) if (w !in files) files += w
    index.writeText("{\"packs\":[" + files.joinToString(",") { "\"$it\"" } + "]}")

    log("SNAP SUMMARY wikidata ${wd.size}/${WikidataTemplates.all.size} prompts (${wd.sumOf { it.size }} answers), " +
        "wikipedia ${wp.size}/${WikipediaTemplates.all.size} prompts (${wp.sumOf { it.size }} answers), " +
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

