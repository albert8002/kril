package app.coredump.snapshot

import app.coredump.core.PackCodec
import app.coredump.core.Prompt
import app.coredump.core.SourceKind
import app.coredump.data.WikiNet
import app.coredump.data.WikidataTemplates
import app.coredump.data.WikipediaTemplates
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Usage: snapshot <packsDir>
 * Writes live_wikidata.json + live_wikipedia.json (ranked format) and registers them in index.json.
 * Never fails the build: whatever succeeds is written.
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "packs" })
    dir.mkdirs()
    val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    val budgetMs = (System.getenv("SNAP_BUDGET_MIN")?.toLongOrNull() ?: 15L) * 60_000
    val t0All = System.currentTimeMillis()
    fun overBudget() = System.currentTimeMillis() - t0All > budgetMs

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
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(45))
            .header("User-Agent", WikiNet.USER_AGENT).header("Accept", accept).GET().build()
    )

    fun postForm(url: String, form: Map<String, String>) = send(
        HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(45))
            .header("User-Agent", WikiNet.USER_AGENT)
            .header("Accept", "application/sparql-results+json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }))
            .build()
    )

    println("UA: ${WikiNet.USER_AGENT}")
    // ---- probes: raw status + headers, to diagnose blocking vs throttling
    for ((name, url) in listOf(
        "wdqs" to "${WikiNet.WDQS}?format=json&query=${enc("SELECT ?x WHERE { wd:Q42 wdt:P31 ?x }")}",
        "wpapi" to "${WikiNet.WP_API}?action=query&meta=siteinfo&format=json",
    )) {
        for (ua in listOf(WikiNet.USER_AGENT, "CoreDumpTrivia/1.0 (Kotlin Multiplatform; open-source trivia game)")) {
            try {
                val t = System.currentTimeMillis()
                val r = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", ua).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                println("SNAP probe $name ua='${ua.take(40)}' -> ${r.statusCode()} in ${System.currentTimeMillis() - t}ms " +
                    "retry-after=${r.headers().firstValue("retry-after").orElse("-")} body=${r.body().replace(Regex("\\s+"), " ").take(140)}")
            } catch (e: Exception) { println("SNAP probe $name -> ${e::class.simpleName}: ${e.message}") }
        }
    }

    // ---- wikidata
    val wd = ArrayList<Prompt>()
    for (t in WikidataTemplates.all) {
        if (overBudget()) { println("SNAP budget exhausted before ${t.id}"); break }
        val t0 = System.currentTimeMillis()
        try {
            val body = get("${WikiNet.WDQS}?format=json&query=${enc(WikidataTemplates.query(t))}", "application/sparql-results+json")
            val p = WikidataTemplates.parse(t, body)
            val ms = System.currentTimeMillis() - t0
            if (p.size >= 5) wd += p
            println("SNAP wikidata  ${t.id.padEnd(18)} OK   ${p.size.toString().padStart(5)} answers  ${ms}ms  top: ${p.entries.take(4).joinToString { it.name }}")
        } catch (e: Exception) {
            println("SNAP wikidata  ${t.id.padEnd(18)} FAIL ${System.currentTimeMillis() - t0}ms ${e::class.simpleName}: ${e.message?.take(200)}")
        }
        Thread.sleep(400)
    }

    // ---- wikipedia
    val wp = ArrayList<Prompt>()
    for (t in WikipediaTemplates.all) {
        if (overBudget()) { println("SNAP budget exhausted before ${t.id}"); break }
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
            if (p.size >= 8) wp += p
            println("SNAP wikipedia ${t.id.padEnd(18)} OK   ${p.size.toString().padStart(5)} answers  ranked ${links.size}/${items.size}" +
                (rankErr?.let { "  rankErr: $it" } ?: "") + "  top: ${p.entries.take(4).joinToString { it.name }}")
        } catch (e: Exception) {
            println("SNAP wikipedia ${t.id.padEnd(18)} FAIL ${e.message?.take(200)}")
        }
        Thread.sleep(400)
    }

    val now = System.currentTimeMillis()
    val written = ArrayList<String>()
    if (wd.isNotEmpty()) { File(dir, "live_wikidata.json").writeText(PackCodec.encodeRanked(wd, now)); written += "live_wikidata.json" }
    if (wp.isNotEmpty()) { File(dir, "live_wikipedia.json").writeText(PackCodec.encodeRanked(wp, now)); written += "live_wikipedia.json" }

    val index = File(dir, "index.json")
    val files = Regex("\"([^\"]+\\.json)\"").findAll(if (index.exists()) index.readText() else "").map { it.groupValues[1] }.toMutableList()
    for (w in written) if (w !in files) files += w
    index.writeText("{\"packs\":[" + files.joinToString(",") { "\"$it\"" } + "]}")

    println("SNAP SUMMARY wikidata ${wd.size}/${WikidataTemplates.all.size} prompts (${wd.sumOf { it.size }} answers), " +
        "wikipedia ${wp.size}/${WikipediaTemplates.all.size} prompts (${wp.sumOf { it.size }} answers); index=$files")
    println("SNAP sources: ${SourceKind.WIKIDATA.tag}, ${SourceKind.WIKIPEDIA.tag}")
}
