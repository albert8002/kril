package app.coredump.data

import app.coredump.core.AnswerIndex
import app.coredump.core.Edits
import app.coredump.core.Judge
import app.coredump.core.Prompt
import app.coredump.core.Norm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Online search for an answer that is not in the local list (pure parts: request texts, parsing, matching).
 *
 * Three lookups run side by side:
 *  1. Wikidata entity search (labels and aliases, any spelling the input starts),
 *  2. Wikipedia exact title (follows redirects: "JFK" → "John F. Kennedy"),
 *  3. Wikipedia full-text search (catches typos and other word orders).
 * Only names that really spell the input are kept ("candidates"). A candidate that is a list entry under another
 * name counts as that entry; otherwise, for lists with a Wikidata class, the candidates' items are checked for
 * membership in one small SPARQL request.
 */
object AnswerCheck {
    sealed interface Outcome {
        /** The answer is the list entry [name] under another name (redirect, alias, other spelling). */
        class Listed(val name: String) : Outcome
        /** Not in the list but a member of the list's class. links: Wikipedia editions with an article (count). */
        class Found(val label: String, val links: Int) : Outcome
        data object Missing : Outcome
        /** Nothing could be reached: nothing learned. */
        data object Unreachable : Outcome
    }

    /** A thing the input probably names: its names (title, label, aliases) and Wikidata item id (if known). */
    class Candidate(val names: List<String>, val qid: String?)

    private val json = Json { ignoreUnknownKeys = true }
    private val qidRe = Regex("""^Q\d+$""")

    fun key(s: String): String = Norm.compact(Norm.key(s))

    /** "Mercury (planet)" → "Mercury". */
    fun bare(title: String): String = title.substringBefore(" (").trim()

    /** [name] spells [input], allowing a typo or two in longer words. */
    fun same(input: String, name: String): Boolean {
        val q = key(input)
        val k = key(name)
        if (q.length < 2 || k.isEmpty()) return false
        val tol = AnswerIndex.toleranceFor(q.length).coerceAtMost(2)
        return Edits.osa(q, k, tol) <= tol
    }

    /** People lists: one typed word may be just the surname ("Jordan" → "Michael Jordan"). */
    fun surname(input: String, name: String): Boolean {
        val t = input.trim()
        if (t.contains(' ') || key(t).length < 3) return false
        val last = bare(name).split(' ').lastOrNull { it.isNotBlank() } ?: return false
        return same(t, last)
    }

    private fun spells(input: String, name: String, people: Boolean) = same(input, name) || same(input, bare(name)) || (people && surname(input, name))

    /** Spellings of the input to try as exact Wikipedia titles (titles are case-sensitive after the first letter). */
    fun titleVariants(input: String): List<String> {
        val t = input.trim().replace(Regex("\\s+"), " ").take(80)
        if (t.isEmpty()) return emptyList()
        val title = t.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        val first = t.lowercase().replaceFirstChar { it.uppercase() }
        return listOf(t, title, first, t.uppercase()).distinct().filter { '|' !in it }
    }

    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull

    /** Wikidata wbsearchentities reply → candidates whose label, alias or matched text spells the input. */
    fun parseEntitySearch(body: String, input: String, people: Boolean): List<Candidate> = runCatching {
        val rows = json.parseToJsonElement(body).jsonObject["search"]?.jsonArray ?: return emptyList()
        rows.mapNotNull { el ->
            val o = el.jsonObject
            val id = o.str("id")?.takeIf { qidRe.matches(it) } ?: return@mapNotNull null
            val label = o.str("label") ?: o["display"]?.jsonObject?.get("label")?.jsonObject?.str("value")
            val matched = o["match"]?.jsonObject?.str("text")
            val aliases = o["aliases"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val names = listOfNotNull(label, matched).plus(aliases).distinct()
            if (names.none { spells(input, it, people) }) null else Candidate(names, id)
        }
    }.getOrElse { emptyList() }

    /**
     * Wikipedia query reply (titles= or generator=search, both with redirects and pageprops) → candidates.
     * [exact]: the reply is for the input's own spellings, so every existing page counts (it is the input or
     * what the input redirects to); otherwise a page counts only if its title (or a redirect to it) spells the input.
     * Disambiguation pages never count.
     */
    fun parsePages(body: String, input: String, people: Boolean, exact: Boolean): List<Candidate> = runCatching {
        val q = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject ?: return emptyList()
        val redirectsTo = HashMap<String, MutableList<String>>()
        q["redirects"]?.jsonArray?.forEach { r ->
            val o = r.jsonObject
            val from = o.str("from") ?: return@forEach
            val to = o.str("to") ?: return@forEach
            redirectsTo.getOrPut(to) { ArrayList() } += from
        }
        val pages = q["pages"]?.jsonArray ?: return emptyList()
        pages.map { it.jsonObject }
            .filter { it["missing"] == null && it["invalid"] == null }
            .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: 0 }
            .mapNotNull { o ->
                val title = o.str("title") ?: return@mapNotNull null
                val props = o["pageprops"]?.jsonObject
                if (props?.get("disambiguation") != null) return@mapNotNull null
                val qid = props?.str("wikibase_item")?.takeIf { qidRe.matches(it) }
                val from = redirectsTo[title].orEmpty()
                val ok = exact || spells(input, title, people) || from.any { spells(input, it, people) }
                if (!ok) null else Candidate((listOf(title, bare(title)) + from).distinct(), qid)
            }
    }.getOrElse { emptyList() }

    // ---- requests (the app and the CI check send exactly these)

    fun entityParams(term: String) = listOf("action" to "wbsearchentities", "search" to term, "language" to "en",
        "uselang" to "en", "type" to "item", "limit" to "20", "format" to "json")

    fun titleParams(term: String) = listOf("action" to "query", "titles" to titleVariants(term).joinToString("|"),
        "redirects" to "1", "prop" to "pageprops", "ppprop" to "wikibase_item|disambiguation", "format" to "json", "formatversion" to "2")

    fun searchParams(term: String) = listOf("action" to "query", "generator" to "search", "gsrsearch" to term, "gsrlimit" to "8",
        "gsrnamespace" to "0", "redirects" to "1", "prop" to "pageprops", "ppprop" to "wikibase_item|disambiguation",
        "format" to "json", "formatversion" to "2")

    /** Candidates from the three replies (null = that lookup failed), most reliable first. */
    fun candidates(input: String, people: Boolean, entity: String?, titles: String?, search: String?): List<Candidate> = buildList {
        if (titles != null) addAll(parsePages(titles, input, people, exact = true))
        if (entity != null) addAll(parseEntitySearch(entity, input, people))
        if (search != null) addAll(parsePages(search, input, people, exact = false))
    }

    /** A candidate that is an entry of [prompt]'s list under one of its names, or null. */
    fun listed(prompt: Prompt, cands: List<Candidate>): Outcome.Listed? {
        for (c in cands) for (n in c.names) {
            val j = Judge.judge(prompt, n, 0)
            val e = j.entry
            if (j.tier.isValid && e != null) return Outcome.Listed(e.name)
        }
        return null
    }

    /** Wikidata items worth checking against the list's class (at most 20). */
    fun qids(cands: List<Candidate>): List<String> = cands.mapNotNull { it.qid }.distinct().take(20)

    /** One SPARQL request: which of [qids] belong to the list's class ([where] binds ?item)? */
    fun memberQuery(where: String, qids: List<String>): String = """
        SELECT ?item ?itemLabel ?links WHERE {
          hint:Query hint:optimizer "None" .
          VALUES ?item { ${qids.joinToString(" ") { "wd:$it" }} }
          ${WikidataTemplates.relax(where)}
          OPTIONAL { ?item wikibase:sitelinks ?links . }
          SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
        }
    """.trimIndent()

    class Member(val qid: String, val label: String?, val links: Int)

    /** The best-known member in a [memberQuery] reply (most Wikipedia editions), or null. */
    fun parseMember(body: String): Member? = runCatching {
        val rows = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray ?: return null
        rows.mapNotNull { el ->
            val o = el.jsonObject
            val uri = o["item"]?.jsonObject?.str("value") ?: return@mapNotNull null
            val label = o["itemLabel"]?.jsonObject?.str("value")?.takeIf { !qidRe.matches(it) }
            Member(uri.substringAfterLast('/'), label, o["links"]?.jsonObject?.str("value")?.toIntOrNull() ?: 0)
        }.maxByOrNull { it.links }
    }.getOrNull()

    /** True when a JSON reply reports an API error (e.g. bad request), so it should not count as "checked". */
    fun isApiError(body: String): Boolean = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        o["error"] != null || o["success"]?.jsonPrimitive?.intOrNull == 0 || o["success"]?.jsonPrimitive?.booleanOrNull == false
    }.getOrDefault(true)
}
