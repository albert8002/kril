package app.coredump.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live prompts scraped from Wikipedia "List of ..." pages.
 * Familiarity = article page views over the last 30 days.
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
    val all: List<WikipediaTemplate> = listOf(
        WikipediaTemplate("wp.unix", "Name a Unix or Linux command", "CODE", "List of Unix commands"),
        WikipediaTemplate("wp.editors", "Name a text editor", "CODE", "List of text editors"),
        WikipediaTemplate("wp.hash", "Name a hash function", "CODE", "List of hash functions"),
        WikipediaTemplate("wp.distros", "Name a Linux distribution", "CODE", "List of Linux distributions"),
        WikipediaTemplate("wp.fictcomp", "Name a fictional computer or AI", "CODE", "List of fictional computers"),
        WikipediaTemplate("wp.robots", "Name a fictional robot or android", "CULTURE", "List of fictional robots and androids"),
        WikipediaTemplate("wp.cryptids", "Name a cryptid", "OCCULT", "List of cryptids"),
        WikipediaTemplate("wp.haunted", "Name a reportedly haunted place", "OCCULT", "List of reportedly haunted locations"),
        WikipediaTemplate("wp.horror80s", "Name a horror film from the 1980s", "OCCULT", "List of horror films of the 1980s"),
        WikipediaTemplate("wp.horror90s", "Name a horror film from the 1990s", "OCCULT", "List of horror films of the 1990s"),
        WikipediaTemplate("wp.horrorauthors", "Name a horror fiction author", "OCCULT", "List of horror fiction authors"),
        WikipediaTemplate("wp.games", "Name a best-selling video game", "CULTURE", "List of best-selling video games"),
        WikipediaTemplate("wp.chess", "Name a chess opening", "CULTURE", "List of chess openings"),
        WikipediaTemplate("wp.cheeses", "Name a cheese", "FOOD", "List of cheeses"),
        WikipediaTemplate("wp.sauces", "Name a sauce", "FOOD", "List of sauces"),
        WikipediaTemplate("wp.breads", "Name a type of bread", "FOOD", "List of breads"),
        WikipediaTemplate("wp.pasta", "Name a type of pasta", "FOOD", "List of pasta"),
        WikipediaTemplate("wp.japanese", "Name a Japanese dish", "FOOD", "List of Japanese dishes"),
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
            val seg = body.substring(m.range.first, end)
            val a = anchor.findAll(seg).firstOrNull { ':' !in decodeTitle(it.groupValues[1]) } ?: continue
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
