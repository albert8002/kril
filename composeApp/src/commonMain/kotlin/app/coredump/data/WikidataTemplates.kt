package app.coredump.data

import app.coredump.core.AnswerIndex
import app.coredump.core.Edits
import app.coredump.core.Norm
import app.coredump.core.Prompt
import app.coredump.core.RawAnswer
import app.coredump.core.SourceKind
import app.coredump.core.Tiering
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live prompts built from Wikidata.
 * Familiarity = number of Wikipedia language editions linking the item ("sitelinks").
 * More sitelinks = more familiar = lower tier.
 */
/** Shared by the app and the CI snapshot tool. Wikimedia blocks clients without contact info in the UA. */
object WikiNet {
    const val VERSION = "1.1"
    const val CONTACT = "https://github.com/albert8002/kril"
    const val USER_AGENT = "CoreDumpTrivia/$VERSION ($CONTACT)"
    const val WDQS = "https://query.wikidata.org/sparql"
    const val WP_API = "https://en.wikipedia.org/w/api.php"
}

class WikidataTemplate(
    val id: String,
    val prompt: String,
    val topic: String,
    /** SPARQL graph pattern binding ?item. */
    val where: String,
    val minLinks: Int = 0,
    val limit: Int = 1500,
)

object WikidataTemplates {
    private fun inst(q: String) = "?item wdt:P31 wd:$q ."
    private fun instSub(q: String) = "?item wdt:P31/wdt:P279* wd:$q ."
    private fun sub(q: String) = "?item wdt:P279+ wd:$q ."

    /** Hand-picked templates + programmatic families. */
    val all: List<WikidataTemplate> by lazy { base + WikidataFamilies.all }

    /** Minimum list size to keep a fetched prompt. */
    fun minAnswers(t: WikidataTemplate): Int = if (t.id.startsWith("wf.")) WikidataFamilies.MIN_ANSWERS else 5

    val base: List<WikidataTemplate> = listOf(
        WikidataTemplate("wd.dog", "Name a dog breed", "NATURE", inst("Q39367")),
        WikidataTemplate("wd.cheese", "Name a cheese", "FOOD", instSub("Q10943")),
        WikidataTemplate("wd.cocktail", "Name a cocktail", "FOOD", instSub("Q134768")),
        WikidataTemplate("wd.dish", "Name a dish from anywhere in the world", "FOOD", inst("Q746549"), minLinks = 5),
        WikidataTemplate("wd.proglang", "Name a programming language", "CODE", instSub("Q9143"), minLinks = 2),
        WikidataTemplate(
            "wd.proglang2000", "Name a programming language first released in 2000 or later", "CODE",
            "?item wdt:P31/wdt:P279* wd:Q9143 ; wdt:P571 ?inc . FILTER(YEAR(?inc) >= 2000)",
        ),
        WikidataTemplate("wd.os", "Name an operating system", "CODE", instSub("Q9135"), minLinks = 2),
        WikidataTemplate("wd.distro", "Name a Linux distribution", "CODE", instSub("Q131669")),
        WikidataTemplate("wd.browser", "Name a web browser", "CODE", instSub("Q6368")),
        WikidataTemplate("wd.editor", "Name a text editor", "CODE", instSub("Q522972")),
        WikidataTemplate("wd.website", "Name a website", "CODE", inst("Q35127"), minLinks = 12),
        WikidataTemplate("wd.videogame", "Name a video game", "CULTURE", inst("Q7889"), minLinks = 25),
        WikidataTemplate("wd.compsci", "Name a computer scientist", "CODE",
            "?item wdt:P31 wd:Q5 ; wdt:P106 wd:Q82594 .", minLinks = 10),
        WikidataTemplate("wd.programmer", "Name a famous programmer", "CODE",
            "?item wdt:P31 wd:Q5 ; wdt:P106 wd:Q5482740 .", minLinks = 6),
        WikidataTemplate("wd.horrorfilm", "Name a horror film", "OCCULT",
            "?item wdt:P31 wd:Q11424 ; wdt:P136 wd:Q200092 .", minLinks = 3),
        WikidataTemplate("wd.horrordir", "Name a director of a horror film", "OCCULT",
            "?film wdt:P31 wd:Q11424 ; wdt:P136 wd:Q200092 ; wdt:P57 ?item .", minLinks = 5),
        WikidataTemplate("wd.metal", "Name a heavy metal band", "CULTURE",
            "?item wdt:P31 wd:Q5741069 ; wdt:P136 wd:Q38848 ."),
        WikidataTemplate("wd.genre", "Name a music genre", "CULTURE", inst("Q188451"), minLinks = 6),
        WikidataTemplate("wd.filmseries", "Name a film series or franchise", "CULTURE", inst("Q24856"), minLinks = 10),
        WikidataTemplate("wd.boardgame", "Name a board game", "CULTURE", inst("Q131436")),
        WikidataTemplate("wd.magazine", "Name a magazine", "CULTURE", inst("Q41298"), minLinks = 8),
        WikidataTemplate("wd.newspaper", "Name a newspaper", "CULTURE", inst("Q11032"), minLinks = 8),
        WikidataTemplate("wd.carmaker", "Name a car manufacturer", "MISC", inst("Q786820"), minLinks = 3),
        WikidataTemplate("wd.airline", "Name an airline", "MISC", inst("Q46970"), minLinks = 5),
        WikidataTemplate("wd.currency", "Name a currency", "MISC", inst("Q8142"), minLinks = 5),
        WikidataTemplate("wd.occupation", "Name an occupation", "MISC", inst("Q12737077"), minLinks = 10),
        WikidataTemplate("wd.discipline", "Name an academic discipline", "SCIENCE", inst("Q11862829"), minLinks = 10),
        WikidataTemplate("wd.constellation", "Name a constellation", "SCIENCE", inst("Q8928")),
        WikidataTemplate("wd.language", "Name a language", "MISC", inst("Q34770"), minLinks = 10),
        WikidataTemplate("wd.fname", "Name a female given name", "MISC", inst("Q11879590"), minLinks = 10),
        WikidataTemplate("wd.mname", "Name a male given name", "MISC", inst("Q12308941"), minLinks = 10),
        WikidataTemplate("wd.sport", "Name a sport", "SPORT", inst("Q31629"), minLinks = 3),
        WikidataTemplate("wd.club", "Name a football (soccer) club", "SPORT", inst("Q476028"), minLinks = 20),
        WikidataTemplate("wd.megacity", "Name a city with over a million people", "GEOGRAPHY",
            "VALUES ?cls { wd:Q515 wd:Q1549591 wd:Q1637706 wd:Q200250 wd:Q5119 } ?item wdt:P31 ?cls ; wdt:P1082 ?pop . FILTER(?pop >= 1000000)"),
        WikidataTemplate("wd.peak7k", "Name a mountain over 7,000 metres", "GEOGRAPHY",
            "?item wdt:P31 wd:Q8502 ; wdt:P2044 ?elev . FILTER(?elev > 7000)"),
        WikidataTemplate("wd.lake", "Name a lake", "GEOGRAPHY", inst("Q23397"), minLinks = 15),
        WikidataTemplate("wd.volcano", "Name a volcano", "GEOGRAPHY", instSub("Q8072"), minLinks = 8),
        WikidataTemplate("wd.sea", "Name a sea", "GEOGRAPHY", inst("Q165"), minLinks = 3),
        WikidataTemplate("wd.desert", "Name a desert", "GEOGRAPHY", inst("Q8514"), minLinks = 5),
        WikidataTemplate("wd.instrument", "Name a musical instrument", "CULTURE", sub("Q34379"), minLinks = 8),
    )

    /**
     * Rank query. The inner sub-select (pattern + sitelinks, sorted, limited) runs first; English labels and
     * aliases come from the label service afterwards. (Joining rdfs:label directly made WDQS scan every label
     * and time out.)
     */
    fun query(t: WikidataTemplate): String {
        val filter = if (t.minLinks > 0) "FILTER(?links >= ${t.minLinks})" else ""
        return """
            SELECT ?item ?itemLabel ?itemAltLabel ?links WHERE {
              { SELECT DISTINCT ?item ?links WHERE { ${t.where} ?item wikibase:sitelinks ?links . $filter }
                ORDER BY DESC(?links) LIMIT ${t.limit} }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            } ORDER BY DESC(?links)
        """.trimIndent()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val qid = Regex("""^Q\d+$""")

    private fun JsonObject.v(key: String): String? = this[key]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull

    /** Parses a SPARQL JSON result into a ranked prompt (with sitelink cut-offs for scoring unlisted answers). */
    fun parse(t: WikidataTemplate, body: String): Prompt {
        val bindings = json.parseToJsonElement(body).jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray
        val raw = ArrayList<RawAnswer>(bindings.size)
        val linksByKey = HashMap<String, Int>()
        for (b in bindings) {
            val o = b.jsonObject
            val label = (o.v("itemLabel") ?: o.v("label"))?.trim() ?: continue
            if (label.isEmpty() || qid.matches(label) || label.length > 60) continue
            val alts = splitAliases(o.v("itemAltLabel") ?: o.v("alts"))
                .filter { it.length in 2..40 && !it.equals(label, ignoreCase = true) }
                .take(6)
            raw += RawAnswer(label, alts)
            val k = Norm.compact(Norm.key(label))
            if (!linksByKey.containsKey(k)) linksByKey[k] = o.v("links")?.toIntOrNull() ?: 0
        }
        val entries = Tiering.build(raw, t.id)
        val c = Tiering.cuts(entries.size)
        fun linksAt(i: Int) = entries.getOrNull(i)?.let { linksByKey[Norm.compact(Norm.key(it.name))] } ?: 0
        val cuts = if (entries.size >= 10) intArrayOf(linksAt(c.legacy - 1).coerceAtLeast(2), linksAt(c.leak - 1).coerceAtLeast(1)) else null
        return Prompt(t.id, t.prompt, t.topic, SourceKind.WIKIDATA, entries, t.where, cuts)
    }

    /** Label-service aliases come joined by ", "; the old GROUP_CONCAT form used "|". */
    fun splitAliases(s: String?): List<String> =
        s.orEmpty().split(if ('|' in s.orEmpty()) "|" else ", ").map { it.trim() }.filter { it.isNotEmpty() }

    /** One-request check of an unlisted answer: Wikidata entity search, constrained by the prompt's pattern. */
    /** For checking one found item: also accept subclasses (many foods, breeds and genres are modelled as P279). */
    fun relax(where: String): String =
        where.replaceFirst(Regex("""\?item wdt:P31(/wdt:P279\*)? """), "?item (wdt:P31|wdt:P279)/wdt:P279* ")

    fun verifyQuery(where: String, input: String): String {
        val term = input.trim().take(60).replace("\\", " ").replace("\"", " ")
        return """
            SELECT ?item ?itemLabel ?itemAltLabel ?links WHERE {
              SERVICE wikibase:mwapi {
                bd:serviceParam wikibase:endpoint "www.wikidata.org" ;
                                wikibase:api "EntitySearch" ;
                                mwapi:search "$term" ;
                                mwapi:language "en" .
                ?item wikibase:apiOutputItem mwapi:item .
              }
              hint:Prior hint:runFirst "true".
              ${relax(where)}
              ?item wikibase:sitelinks ?links .
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            } ORDER BY DESC(?links) LIMIT 5
        """.trimIndent()
    }

    class Verified(val label: String, val links: Int)

    /** Accepts only when the input really names the found item (label or alias within a small edit distance). */
    fun parseVerify(body: String, input: String): Verified? {
        val q = Norm.compact(Norm.key(input))
        if (q.length < 2) return null
        val tol = AnswerIndex.toleranceFor(q.length).coerceAtMost(2)
        val bindings = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray ?: return null
        for (b in bindings) {
            val o = b.jsonObject
            val label = (o.v("itemLabel") ?: o.v("label")) ?: continue
            if (qid.matches(label)) continue
            val names = listOf(label) + splitAliases(o.v("itemAltLabel") ?: o.v("alts"))
            val ok = names.any { n ->
                val k = Norm.compact(Norm.key(n))
                k.isNotEmpty() && Edits.osa(q, k, tol) <= tol
            }
            if (ok) return Verified(label, o.v("links")?.toIntOrNull() ?: 0)
        }
        return null
    }
}
