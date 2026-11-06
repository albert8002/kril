package app.coredump.data

import app.coredump.core.Prompt
import app.coredump.core.RawAnswer
import app.coredump.core.SourceKind
import app.coredump.core.Tiering
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live prompts built from Wikidata.
 * Familiarity = number of Wikipedia language editions linking the item ("sitelinks").
 * More sitelinks = more familiar = lower tier.
 */
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

    val all: List<WikidataTemplate> = listOf(
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
        WikidataTemplate("wd.console", "Name a video game console", "CODE", instSub("Q8076")),
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
        WikidataTemplate("wd.opera", "Name an opera", "CULTURE", inst("Q1344"), minLinks = 8),
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
        WikidataTemplate("wd.megacity", "Name a city with over a million people", "GEOGRAPHY", inst("Q1637706")),
        WikidataTemplate("wd.peak7k", "Name a mountain over 7,000 metres", "GEOGRAPHY",
            "?item wdt:P31 wd:Q8502 ; wdt:P2044 ?elev . FILTER(?elev > 7000)"),
        WikidataTemplate("wd.lake", "Name a lake", "GEOGRAPHY", inst("Q23397"), minLinks = 15),
        WikidataTemplate("wd.volcano", "Name a volcano", "GEOGRAPHY", instSub("Q8072"), minLinks = 8),
        WikidataTemplate("wd.sea", "Name a sea", "GEOGRAPHY", inst("Q165"), minLinks = 3),
        WikidataTemplate("wd.desert", "Name a desert", "GEOGRAPHY", inst("Q8514"), minLinks = 5),
        WikidataTemplate("wd.instrument", "Name a musical instrument", "CULTURE", sub("Q34379"), minLinks = 8),
    )

    fun query(t: WikidataTemplate): String {
        val filter = if (t.minLinks > 0) "FILTER(?links >= ${t.minLinks})" else ""
        return """
            SELECT ?item ?label ?links (GROUP_CONCAT(DISTINCT ?alt; separator="|") AS ?alts) WHERE {
              { SELECT DISTINCT ?item ?links WHERE { ${t.where} ?item wikibase:sitelinks ?links . $filter }
                ORDER BY DESC(?links) LIMIT ${t.limit} }
              ?item rdfs:label ?label . FILTER(LANG(?label) = "en")
              OPTIONAL { ?item skos:altLabel ?alt . FILTER(LANG(?alt) = "en") }
            } GROUP BY ?item ?label ?links ORDER BY DESC(?links)
        """.trimIndent()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val qid = Regex("""^Q\d+$""")

    /** Parses a SPARQL JSON result into a ranked prompt. */
    fun parse(t: WikidataTemplate, body: String): Prompt {
        val bindings = json.parseToJsonElement(body).jsonObject["results"]!!.jsonObject["bindings"]!!.jsonArray
        val raw = ArrayList<RawAnswer>(bindings.size)
        for (b in bindings) {
            val o = b.jsonObject
            val label = o["label"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull?.trim() ?: continue
            if (label.isEmpty() || qid.matches(label) || label.length > 60) continue
            val alts = o["alts"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull.orEmpty()
                .split('|').map { it.trim() }
                .filter { it.length in 2..40 && !it.equals(label, ignoreCase = true) }
                .take(6)
            raw += RawAnswer(label, alts)
        }
        return Prompt(t.id, t.prompt, t.topic, SourceKind.WIKIDATA, Tiering.build(raw, t.id))
    }
}
