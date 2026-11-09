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
    const val WD_API = "https://www.wikidata.org/w/api.php"
}

class WikidataTemplate(
    val id: String,
    val prompt: String,
    val topic: String,
    /** SPARQL graph pattern binding ?item. */
    val where: String,
    val minLinks: Int = 0,
    val limit: Int = 1500,
    /** Widely known answers (≥ [WikidataTemplates.FAMOUS_LINKS] sitelinks) needed to keep the list. Everyday things
     *  (hats, noodles, zoos) have few Wikipedia pages each, so their lists use a lower bar. */
    val famousMin: Int = WikidataTemplates.FAMOUS_MIN,
)

/**
 * Wikidata classes whose members are often filed under a narrower class: many actors are only "film actor" or
 * "television actor", many films only "animated feature film". Answer checks take the whole family; lists keep the
 * plain class (joining a whole family makes the big list queries, all films or all actors, time out).
 */
object ClassFamilies {
    /** occupation (P106) → itself and its common narrower occupations */
    val occupations: Map<String, List<String>> = mapOf(
        // actor: film, television, voice, stage, child, character actor
        "Q33999" to listOf("Q33999", "Q10800557", "Q10798782", "Q2405480", "Q2259451", "Q970153", "Q948329"),
        // singer: singer-songwriter
        "Q177220" to listOf("Q177220", "Q488205"),
        // writer: novelist, poet, playwright, screenwriter
        "Q36180" to listOf("Q36180", "Q6625963", "Q49757", "Q214917", "Q28389"),
        // musician: guitarist, drummer, pianist, composer
        "Q639669" to listOf("Q639669", "Q855091", "Q386854", "Q486748", "Q36834"),
    )

    /** class (P31) → itself and its common narrower classes */
    val classes: Map<String, List<String>> = mapOf(
        // film: feature film, animated feature film, animated film, television film
        "Q11424" to listOf("Q11424", "Q24869", "Q29168811", "Q202866", "Q506240"),
        // TV series: animated television series, anime television series, miniseries
        "Q5398426" to listOf("Q5398426", "Q117467246", "Q63952888", "Q1259759"),
    )

    fun of(p: String, q: String): List<String> = when (p) {
        "P106" -> occupations[q]
        "P31" -> classes[q]
        else -> null
    } ?: listOf(q)
}

object WikidataTemplates {
    private fun inst(q: String) = "?item wdt:P31 wd:$q ."
    private fun instSub(q: String) = "?item wdt:P31/wdt:P279* wd:$q ."
    private fun sub(q: String) = "?item wdt:P279+ wd:$q ."

    /** Hand-picked templates + everyday categories + programmatic families. */
    val all: List<WikidataTemplate> by lazy { base + generic + WikidataFamilies.all }

    private fun subOf(q: String) = "?item wdt:P279+ wd:$q ."
    private fun anyOf(q: String) = "?item (wdt:P31|wdt:P279)/wdt:P279* wd:$q ."
    private fun famous(occ: String) = "?item wdt:P31 wd:Q5 ; wdt:P106 wd:$occ ."
    /** Species below a taxon given by its scientific name (labels of taxa are unique). */
    private fun species(vararg taxa: String) =
        "VALUES ?tl { ${taxa.joinToString(" ") { "\"$it\"@en" }} } ?t rdfs:label ?tl ; wdt:P105 ?rank . " +
            "?item wdt:P171+ ?t ; wdt:P105 wd:Q7432 ."

    /** Everyday categories anyone can name a few of (validated by the CI snapshot). */
    val generic: List<WikidataTemplate> = listOf(
        WikidataTemplate("wg.film", "Name a famous film", "CULTURE", inst("Q11424"), minLinks = 60),
        WikidataTemplate("wg.tv", "Name a TV series", "CULTURE", inst("Q5398426"), minLinks = 40),
        WikidataTemplate("wg.sitcom", "Name a sitcom", "CULTURE", "?item wdt:P31 wd:Q5398426 ; wdt:P136 wd:Q170238 .", minLinks = 15),
        WikidataTemplate("wg.song", "Name a famous song", "CULTURE", "VALUES ?k { wd:Q7366 wd:Q134556 } ?item wdt:P31 ?k .", minLinks = 30),
        WikidataTemplate("wg.album", "Name a famous album", "CULTURE", inst("Q482994"), minLinks = 20, limit = 2000),
        WikidataTemplate("wg.band", "Name a band or music group", "CULTURE", inst("Q215380"), minLinks = 30, limit = 2000),
        WikidataTemplate("wg.painting", "Name a famous painting", "CULTURE", inst("Q3305213"), minLinks = 25),
        WikidataTemplate("wg.book", "Name a famous book", "CULTURE", inst("Q7725634"), minLinks = 50),
        WikidataTemplate("wg.vgchar", "Name a video game character", "CULTURE", inst("Q1569167"), minLinks = 8),
        WikidataTemplate("wg.brand", "Name a brand", "MISC", inst("Q431289"), minLinks = 30),
        WikidataTemplate("wg.skyscraper", "Name a skyscraper", "GEOGRAPHY", inst("Q11303"), minLinks = 15),
        WikidataTemplate("wg.bridge", "Name a famous bridge", "GEOGRAPHY", instSub("Q12280"), minLinks = 25),
        WikidataTemplate("wg.stadium", "Name a stadium", "SPORT", inst("Q483110"), minLinks = 20),
        WikidataTemplate("wg.museum", "Name a museum", "HISTORY", inst("Q33506"), minLinks = 40),
        WikidataTemplate("wg.city", "Name a city anywhere in the world", "GEOGRAPHY",
            "VALUES ?cls { wd:Q515 wd:Q1549591 wd:Q1637706 wd:Q200250 wd:Q5119 } ?item wdt:P31 ?cls .", minLinks = 120),
        WikidataTemplate("wg.disease", "Name a disease", "SCIENCE", instSub("Q12136"), minLinks = 60),
        WikidataTemplate("wg.religion", "Name a religion", "HISTORY", instSub("Q9174"), minLinks = 25),
        WikidataTemplate("wg.holiday", "Name a public holiday or festival", "MISC", instSub("Q1445650"), minLinks = 25),
        WikidataTemplate("wg.dance", "Name a dance", "CULTURE", anyOf("Q11639"), minLinks = 20),
        WikidataTemplate("wg.martial", "Name a martial art", "SPORT", anyOf("Q11417"), minLinks = 15),
        WikidataTemplate("wg.artmove", "Name an art movement", "CULTURE", inst("Q968159"), minLinks = 30),
        WikidataTemplate("wg.ideology", "Name a political ideology", "HISTORY", instSub("Q12909644"), minLinks = 30),
        WikidataTemplate("wg.legend", "Name a legendary or mythical creature", "OCCULT", instSub("Q2239243"), minLinks = 15),
        WikidataTemplate("wg.deity", "Name a god or goddess from any religion", "HISTORY", instSub("Q178885"), minLinks = 30),
        WikidataTemplate("wg.clothing", "Name an item of clothing", "MISC", subOf("Q11460"), minLinks = 20),
        WikidataTemplate("wg.furniture", "Name a piece of furniture", "MISC", subOf("Q14745"), minLinks = 15),
        WikidataTemplate("wg.tool", "Name a tool", "MISC", subOf("Q39546"), minLinks = 30),
        WikidataTemplate("wg.vehicle", "Name a type of vehicle", "MISC", subOf("Q42889"), minLinks = 30),
        WikidataTemplate("wg.drink", "Name a drink", "FOOD", subOf("Q40050"), minLinks = 30),
        WikidataTemplate("wg.cake", "Name a cake", "FOOD", anyOf("Q13276"), minLinks = 8),
        WikidataTemplate("wg.bread", "Name a bread", "FOOD", anyOf("Q7802"), minLinks = 10),
        WikidataTemplate("wg.sausage", "Name a sausage", "FOOD", anyOf("Q131419"), minLinks = 8),
        WikidataTemplate("wg.pastry", "Name a pastry", "FOOD", anyOf("Q477248"), minLinks = 10),
        WikidataTemplate("wg.soup", "Name a soup", "FOOD", anyOf("Q41415"), minLinks = 10),
        WikidataTemplate("wg.sauce", "Name a sauce", "FOOD", anyOf("Q178359"), minLinks = 10),
        WikidataTemplate("wg.salad", "Name a salad", "FOOD", anyOf("Q9266"), minLinks = 8),
        WikidataTemplate("wg.whale", "Name a whale, dolphin or porpoise", "NATURE", species("Cetacea"), minLinks = 10),
        WikidataTemplate("wg.wildcat", "Name a wild cat species", "NATURE", species("Felidae"), minLinks = 10),
        WikidataTemplate("wg.canid", "Name a wolf, fox, jackal or wild dog", "NATURE", species("Canidae"), minLinks = 10),
        WikidataTemplate("wg.rodent", "Name a rodent", "NATURE", species("Rodentia", "rodent"), minLinks = 25),
        WikidataTemplate("wg.raptor", "Name a bird of prey species", "NATURE", species("Accipitriformes", "Falconiformes", "Strigiformes", "owl"), minLinks = 25),
        WikidataTemplate("wg.parrot", "Name a parrot species", "NATURE", species("Psittaciformes", "parrot"), minLinks = 15),
        WikidataTemplate("wg.duck", "Name a duck, goose or swan", "NATURE", species("Anatidae"), minLinks = 20),
        WikidataTemplate("wg.shark", "Name a shark", "NATURE", species("Selachimorpha", "shark"), minLinks = 15),
        WikidataTemplate("wg.turtle", "Name a turtle or tortoise", "NATURE", species("Testudines"), minLinks = 15),
        WikidataTemplate("wg.frog", "Name a frog or toad", "NATURE", species("Anura"), minLinks = 20),
        WikidataTemplate("wg.deer", "Name a deer", "NATURE", species("Cervidae"), minLinks = 10),
        WikidataTemplate("wg.cactus", "Name a cactus", "NATURE", species("Cactaceae"), minLinks = 8),
        WikidataTemplate("wg.actor", "Name a famous actor or actress", "PEOPLE", famous("Q33999"), minLinks = 60),
        WikidataTemplate("wg.writer", "Name a famous writer", "PEOPLE", famous("Q36180"), minLinks = 80),
        WikidataTemplate("wg.politician", "Name a famous politician", "PEOPLE", famous("Q82955"), minLinks = 100),
        WikidataTemplate("wg.model", "Name a famous model", "PEOPLE", famous("Q4610556"), minLinks = 30),
        WikidataTemplate("wg.youtuber", "Name a famous YouTuber", "PEOPLE", famous("Q17125263"), minLinks = 15),
        WikidataTemplate("wg.dancer", "Name a famous dancer", "PEOPLE", famous("Q5716684"), minLinks = 30),
        WikidataTemplate("wg.cricketer", "Name a famous cricketer", "SPORT", famous("Q12299841"), minLinks = 15),
        WikidataTemplate("wg.swimmer", "Name a famous swimmer", "SPORT", famous("Q10843402"), minLinks = 20),
        WikidataTemplate("wg.cyclist", "Name a famous cyclist", "SPORT", famous("Q2309784"), minLinks = 20),
        WikidataTemplate("wg.hockey", "Name a famous ice hockey player", "SPORT", famous("Q11774891"), minLinks = 20),
        WikidataTemplate("wg.baseball", "Name a famous baseball player", "SPORT", famous("Q10871364"), minLinks = 15),
        WikidataTemplate("wg.nfl", "Name a famous American football player", "SPORT", famous("Q19204627"), minLinks = 15),
        WikidataTemplate("wg.wrestler", "Name a famous pro wrestler", "SPORT", famous("Q13474373"), minLinks = 15),
        WikidataTemplate("wg.presenter", "Name a famous TV presenter", "PEOPLE", famous("Q947873"), minLinks = 25),
        WikidataTemplate("wg.dj", "Name a famous DJ", "PEOPLE", famous("Q130857"), minLinks = 20),
        WikidataTemplate("wg.pope", "Name a pope", "HISTORY", "?item wdt:P39 wd:Q19546 .", minLinks = 20),
    )

    /**
     * How many answers in a fetched list are widely known (covered by at least [FAMOUS_LINKS] Wikipedias).
     * Lists with fewer than [FAMOUS_MIN] such answers are too obscure for a general audience and are dropped.
     */
    fun famousCount(body: String): Int = runCatching {
        val b = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray ?: return 0
        b.count { (it.jsonObject.v("links")?.toIntOrNull() ?: 0) >= FAMOUS_LINKS }
    }.getOrDefault(0)

    const val FAMOUS_LINKS = 40
    const val FAMOUS_MIN = 8

    /** Minimum list size to keep a fetched prompt. */
    fun minAnswers(t: WikidataTemplate): Int =
        if (t.id.startsWith("wf.") || t.id.startsWith("wx.") || t.id.startsWith("wg.")) WikidataFamilies.MIN_ANSWERS else 5

    val base: List<WikidataTemplate> = listOf(
        WikidataTemplate("wd.dog", "Name a dog breed", "NATURE", inst("Q39367")),
        WikidataTemplate("wd.cheese", "Name a cheese", "FOOD", instSub("Q10943")),
        WikidataTemplate("wd.cocktail", "Name a cocktail", "FOOD", instSub("Q134768")),
        WikidataTemplate("wd.dish", "Name a dish from anywhere in the world", "FOOD", inst("Q746549"), minLinks = 5),
        WikidataTemplate("wd.proglang", "Name a programming language", "CODE", instSub("Q9143"), minLinks = 2),
        WikidataTemplate("wd.os", "Name an operating system", "CODE", instSub("Q9135"), minLinks = 2),
        WikidataTemplate("wd.browser", "Name a web browser", "CODE", instSub("Q6368")),
        WikidataTemplate("wd.website", "Name a website", "CODE", inst("Q35127"), minLinks = 12),
        WikidataTemplate("wd.videogame", "Name a video game", "CULTURE", inst("Q7889"), minLinks = 12, limit = 2500),
        WikidataTemplate("wd.horrorfilm", "Name a horror film", "OCCULT",
            "?item wdt:P31 wd:Q11424 ; wdt:P136 wd:Q200092 .", minLinks = 3),
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
        val bindings = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray
            ?: throw IllegalStateException("unexpected reply")
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
            // an occupation also counts through its narrower ones ("film actor" for "actor")
            .replace("wdt:P106 wd:", "wdt:P106/wdt:P279* wd:")

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
