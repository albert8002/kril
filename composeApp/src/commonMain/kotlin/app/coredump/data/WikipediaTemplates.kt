package app.coredump.data

import app.coredump.core.Prompt
import app.coredump.core.RawAnswer
import app.coredump.core.SourceKind
import app.coredump.core.Tiering
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live prompts scraped from Wikipedia "List of ..." pages.
 * Familiarity = number of Wikipedia language editions of each article (Wikidata sitelinks),
 * fetched for the whole list in ONE SPARQL request (page views would cost ~30 requests per list).
 */
class WikipediaTemplate(
    val id: String,
    val prompt: String,
    val topic: String,
    val page: String,
    val max: Int = 600,
)

/** One scraped list item: article title (for page views) + visible link text (answer). */
class WikiItem(val title: String, val text: String)

object WikipediaTemplates {
    /** Bumped when [extract] changes, so cached lists from older extractors are refetched. */
    const val EXTRACT_VERSION = 2

    val all: List<WikipediaTemplate> = listOf(
        WikipediaTemplate("wp.robots", "Name a fictional robot or android", "CULTURE", "List of fictional robots and androids"),
        WikipediaTemplate("wp.cryptids", "Name a cryptid", "OCCULT", "List of cryptids"),
        WikipediaTemplate("wp.horrorauthors", "Name a horror fiction author", "OCCULT", "List of horror fiction authors"),
        WikipediaTemplate("wp.games", "Name a best-selling video game", "CULTURE", "List of best-selling video games"),
        WikipediaTemplate("wp.cheeses", "Name a cheese", "FOOD", "List of cheeses"),
        WikipediaTemplate("wp.breads", "Name a type of bread", "FOOD", "List of breads"),
        WikipediaTemplate("wp.pasta", "Name a type of pasta", "FOOD", "List of pasta"),
        WikipediaTemplate("wp.dogs", "Name a dog breed", "NATURE", "List of dog breeds"),
    )

    private val cutMarkers = listOf(
        "id=\"See_also\"", "id=\"References\"", "id=\"Notes\"", "id=\"External_links\"",
        "id=\"Further_reading\"", "id=\"Bibliography\"", "id=\"Sources\"", "class=\"navbox",
    )
    private val anchor = Regex("""<a href="/wiki/([^"#?]+)"[^>]*>([\s\S]*?)</a>""")
    private val tag = Regex("<[^>]+>")
    private val segStart = Regex("""<(li|tr)[\s>]""")
    private val h2 = Regex("""<h2[\s>]""")
    private val cell = Regex("""<t[dh][\s>]""")
    private val leadJunk = Regex("""[\s"'“”‘’«»*•·\-–—|]+""")

    /** Pulls list items out of article HTML. Tables win over bullet lists when the page has many rows. */
    fun extract(html0: String, max: Int): List<WikiItem> {
        var html = html0
        var cut = html.length
        for (m in cutMarkers) {
            val i = html.indexOf(m)
            if (i in 0 until cut) cut = i
        }
        html = html.substring(0, cut)
        val firstH2 = h2.find(html)?.range?.first ?: 0
        val body = html.substring(firstH2)
        val starts = segStart.findAll(body).toList()
        val rows = starts.count { it.groupValues[1] == "tr" }
        val wanted = if (rows >= 10) "tr" else "li"

        val out = ArrayList<WikiItem>()
        val seen = HashSet<String>()
        for ((n, m) in starts.withIndex()) {
            if (m.groupValues[1] != wanted) continue
            val end = if (n + 1 < starts.size) starts[n + 1].range.first else body.length
            var seg = body.substring(m.range.first, end)
            if (wanted == "tr") {
                // the answer is the first cell with a link; skip leading number/image cells,
                // but give up on the row if a text cell comes first (an unlinked name: later links are countries, makers…)
                val cells = cell.findAll(seg).toList()
                var pick: String? = null
                for ((ci, c) in cells.withIndex()) {
                    val cs = seg.substring(c.range.first, if (ci + 1 < cells.size) cells[ci + 1].range.first else seg.length)
                    if (anchor.findAll(cs).any { ':' !in decodeTitle(it.groupValues[1]) }) { pick = cs; break }
                    if (entities(tag.replace(cs, "")).any { it.isLetter() }) break
                }
                seg = pick ?: continue
            }
            val a = anchor.findAll(seg).firstOrNull { ':' !in decodeTitle(it.groupValues[1]) } ?: continue
            // the link must BE the item, not a word in its description ("Foo – a bread from [Italy]")
            val before = entities(tag.replace(seg.substring(0, a.range.first), "")).replace(leadJunk, "")
            if (before.isNotEmpty()) continue
            val title = decodeTitle(a.groupValues[1])
            var text = entities(tag.replace(a.groupValues[2], "")).trim()
            if (text.length < 2) text = title.substringBefore(" (").trim()
            if (text.length > 60 || text.all { it.isDigit() }) continue
            if (seen.add(title)) out += WikiItem(title, text)
            if (out.size >= max) break
        }
        return out
    }

    fun decodeTitle(s: String): String = percentDecode(s).replace('_', ' ')

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null) {
                bytes += s.substring(i + 1, i + 3).toInt(16).toByte(); i += 3
            } else {
                for (b in c.toString().encodeToByteArray()) bytes += b
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private val numEntity = Regex("&#(x?)([0-9a-fA-F]+);")

    fun entities(s0: String): String = numEntity.replace(s0) { m ->
        val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
        if (code == null || code !in 1..0xFFFF) m.value else code.toChar().toString()
    }
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&#160;", " ")

    fun parseHtml(body: String): String? {
        val o = Json.parseToJsonElement(body).jsonObject
        val parse = o["parse"]?.jsonObject ?: return null
        val text = parse["text"] ?: return null
        // formatversion=2 gives a string; v1 gives {"*": "..."}
        return runCatching { text.jsonPrimitive.contentOrNull }.getOrNull()
            ?: (text as? JsonObject)?.get("*")?.jsonPrimitive?.contentOrNull
    }

    /** SPARQL that maps English article titles to their sitelink counts. Send as POST (long). */
    fun sitelinksQuery(titles: List<String>): String {
        val values = titles.joinToString(" ") { "\"" + sparqlEscape(it) + "\"@en" }
        return """
            SELECT ?name ?links WHERE {
              VALUES ?name { $values }
              ?article schema:name ?name ; schema:isPartOf <https://en.wikipedia.org/> ; schema:about ?item .
              ?item wikibase:sitelinks ?links .
            }
        """.trimIndent()
    }

    private fun sparqlEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    /** title -> sitelinks from a SPARQL JSON result. */
    fun parseSitelinks(body: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        val bindings = Json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray ?: return out
        for (b in bindings) {
            val o = b.jsonObject
            val name = o["name"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: continue
            val links = o["links"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
            out[name] = maxOf(out[name] ?: 0, links)
        }
        return out
    }

    /** Ranks scraped items by sitelinks (unknown = 0, keeps page order as tie-break) and builds the prompt. */
    fun rank(t: WikipediaTemplate, items: List<WikiItem>, links: Map<String, Int>): Prompt {
        val ranked = items.withIndex()
            .sortedWith(compareByDescending<IndexedValue<WikiItem>> { links[it.value.title] ?: 0 }.thenBy { it.index })
            .map { (_, item) ->
                val alias = item.title.substringBefore(" (").trim()
                RawAnswer(item.text, if (!alias.equals(item.text, ignoreCase = true)) listOf(alias) else emptyList())
            }
        return Prompt(t.id, t.prompt, t.topic, SourceKind.WIKIPEDIA, Tiering.build(ranked, t.id))
    }

    /** Page views result: title -> summed views. Also returns the continue params, if any. */
    class Views(val views: Map<String, Long>, val normalized: Map<String, String>, val cont: Map<String, String>)

    fun parseViews(body: String): Views {
        val o = Json.parseToJsonElement(body).jsonObject
        val q = o["query"]?.jsonObject
        val map = HashMap<String, String>()
        for (key in listOf("normalized", "redirects")) {
            q?.get(key)?.jsonArray?.forEach { el ->
                val from = el.jsonObject["from"]?.jsonPrimitive?.contentOrNull
                val to = el.jsonObject["to"]?.jsonPrimitive?.contentOrNull
                if (from != null && to != null) map[from] = to
            }
        }
        val views = HashMap<String, Long>()
        q?.get("pages")?.jsonArray?.forEach { el ->
            val p = el.jsonObject
            val title = p["title"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val pv = p["pageviews"] as? JsonObject ?: return@forEach
            var sum = 0L
            for ((_, v) in pv) sum += runCatching { v.jsonPrimitive.intOrNull ?: 0 }.getOrDefault(0)
            views[title] = sum
        }
        val cont = HashMap<String, String>()
        (o["continue"] as? JsonObject)?.forEach { (k, v) ->
            v.jsonPrimitive.contentOrNull?.let { cont[k] = it }
        }
        return Views(views, map, cont)
    }

    /** Follows normalized -> redirect chain (max 3 hops). */
    fun resolve(title: String, map: Map<String, String>): String {
        var t = title
        repeat(3) { t = map[t] ?: return t }
        return t
    }
}
