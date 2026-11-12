package app.coredump.data

/**
 * "Slightly specific" lists in the spirit of Krillion's daily prompts: a familiar category with one qualifying
 * condition (a size, a date, a place, a membership, a spelling rule) — "Name a town in Europe with more than 50,000
 * people", "Name a volcano higher than 5,000 m", "Name a film from the 1980s", "Name a country without the letter A".
 * The condition is the fun part: it rules out the first answer that comes to mind and rewards the second thought.
 *
 * All are collected on the device like the rest of [WikidataPool] (ids "wx.<group>.s.<name>": the group picks the
 * category, see [app.coredump.core.Topics]; ".s." marks them as specific). Ranking as everywhere: Wikipedia editions
 * of each answer (sitelinks), later refined by page views ([Rescore]). Checked by the CI pool check (POOL lines).
 *
 * Query notes (Wikidata query service, 60 s limit):
 *  - sizes use the normalized value (psn: = SI units, metres / square metres) when lists mix units (feet, miles);
 *  - "hint:Query hint:optimizer "None"" keeps the written order where the planner would start from a huge set;
 *  - current countries are sovereign states without a dissolution date (P576).
 */
object SpecificTemplates {

    /** Group key → category the collected list is filed under (same keys as the rest of the pool). */
    private val topicOf = mapOf(
        "ge" to "GEOGRAPHY", "an2" to "ANIMALS", "na" to "NATURE", "fd" to "FOOD & DRINK", "mv" to "MOVIES", "mu" to "MUSIC",
        "gm" to "GAMING", "tv" to "TV", "pc" to "POP CULTURE", "my" to "MYTH", "ho" to "HORROR", "lm" to "LANDMARKS",
        "sp" to "SPORT", "hi" to "HISTORY", "br" to "BRANDS", "tc" to "TECH", "sc" to "SCIENCE", "wo" to "WORDS", "bk" to "BOOKS & ART",
    )

    /** [group].s.[name]; [famousMin]: answers with ≥ [WikidataTemplates.FAMOUS_LINKS] Wikipedia editions needed to keep it. */
    private fun s(group: String, name: String, prompt: String, where: String, minLinks: Int = 0, famousMin: Int = 4, limit: Int = 600) =
        WikidataTemplate("wx.$group.s.$name", prompt, topicOf.getValue(group), where, minLinks, limit, famousMin)

    // ---- building blocks

    private const val NOW = "FILTER NOT EXISTS { ?item wdt:P576 ?gone . }"
    /** A sovereign state that still exists. */
    private const val STATE = "?item wdt:P31 wd:Q3624078 . $NOW"
    /** English label of ?item passing [test] (on ?l). */
    private fun label(test: String) = "?item rdfs:label ?l . FILTER(LANG(?l) = \"en\" && $test)"
    /** City classes used for population lists (like the bundled "city with over a million people"). */
    private const val CITY = "VALUES ?cls { wd:Q515 wd:Q1549591 wd:Q1637706 wd:Q200250 wd:Q5119 } ?item wdt:P31 ?cls ;"
    /** Film classes (film, feature, animated feature, animated film). */
    private const val FILM = "VALUES ?cls { wd:Q11424 wd:Q24869 wd:Q29168811 wd:Q202866 } ?item wdt:P31 ?cls ;"
    /** ?[v] in [from, to), and no earlier date on [path] (re-releases and ports would put old works in a later decade). */
    private fun years(v: String, from: Int, to: Int, path: String) =
        "FILTER(YEAR(?$v) >= $from && YEAR(?$v) < $to) FILTER NOT EXISTS { ?item $path ?${v}0 . FILTER(YEAR(?${v}0) < $from) }"
    private fun before(v: String, year: Int) = "FILTER(YEAR(?$v) < $year)"
    /** Species below the taxa named (scientific names), written order kept (see [WikidataPool]). */
    private fun species(vararg taxa: String) =
        "hint:Query hint:optimizer \"None\" . VALUES ?tn { ${taxa.joinToString(" ") { "\"$it\"" }} } ?t wdt:P225 ?tn . " +
            "?t wdt:P105 ?rank . ?item wdt:P171+ ?t . ?item wdt:P105 wd:Q7432 ."
    /** A conservation status (IUCN, P141) among animals; the status first, then up the taxon tree. */
    private fun status(vararg q: String, under: String = "Q729") =
        "hint:Query hint:optimizer \"None\" . VALUES ?st { ${q.joinToString(" ") { "wd:$it" }} } ?item wdt:P141 ?st . ?item wdt:P171+ wd:$under ."
    /** Dishes and foods from one country (P495 = country of origin). */
    private fun dishes(country: String) =
        "VALUES ?cls { wd:Q746549 wd:Q2095 wd:Q41415 wd:Q182940 wd:Q178359 wd:Q13276 wd:Q7802 wd:Q477248 wd:Q9266 wd:Q131419 } " +
            "?item wdt:P31 ?cls ; wdt:P495 wd:$country ."

    /** Countries of Europe (Russia and Turkey only west of the Urals / the Bosporus, see [EU_TOWN]). */
    private val europe = listOf("Q222", "Q228", "Q40", "Q184", "Q31", "Q225", "Q219", "Q224", "Q213", "Q35", "Q191", "Q33", "Q142",
        "Q183", "Q41", "Q28", "Q189", "Q27", "Q38", "Q1246", "Q211", "Q347", "Q37", "Q32", "Q233", "Q217", "Q235", "Q236", "Q55",
        "Q29999", "Q221", "Q20", "Q36", "Q45", "Q218", "Q159", "Q238", "Q403", "Q214", "Q215", "Q29", "Q34", "Q39", "Q43", "Q212",
        "Q145", "Q237")
    private val EU_TOWN = "hint:Query hint:optimizer \"None\" . ?item wdt:P1082 ?pop . FILTER(?pop >= 50000) ?item wdt:P17 ?c . " +
        "FILTER(?c IN (${europe.joinToString(", ") { "wd:$it" }})) ?item wdt:P625 ?xy . " +
        "FILTER((?c != wd:Q159 || geof:longitude(?xy) < 60) && (?c != wd:Q43 || geof:longitude(?xy) < 29.2)) " +
        "?item wdt:P31/wdt:P279* wd:Q486972 ."

    val all: List<WikidataTemplate> = listOf(
        // ---------------- geography: sizes, places, spelling
        s("ge", "eutown50k", "Name a town or city in Europe with more than 50,000 people", EU_TOWN, minLinks = 12, famousMin = 8, limit = 900),
        s("ge", "africacity1m", "Name a city in Africa with more than 1 million people",
            "$CITY wdt:P1082 ?pop . FILTER(?pop >= 1000000) ?item wdt:P17/wdt:P30 wd:Q15 .", famousMin = 6),
        s("ge", "samcity1m", "Name a city in South America with more than 1 million people",
            "$CITY wdt:P1082 ?pop . FILTER(?pop >= 1000000) ?item wdt:P17/wdt:P30 wd:Q18 .", famousMin = 6),
        s("ge", "city10m", "Name a city with more than 10 million people",
            "$CITY wdt:P1082 ?pop . FILTER(?pop >= 10000000)", famousMin = 8),
        s("ge", "tinycountry", "Name a country with fewer than 1 million people", "$STATE ?item wdt:P1082 ?pop . FILTER(?pop < 1000000)", famousMin = 8),
        s("ge", "bigcountry", "Name a country with more than 50 million people", "$STATE ?item wdt:P1082 ?pop . FILTER(?pop >= 50000000)", famousMin = 8),
        s("ge", "hugecountry", "Name a country larger than 1 million km²",
            "$STATE ?item p:P2046/psn:P2046/wikibase:quantityAmount ?a . FILTER(?a >= 1.0E12)", famousMin = 8),
        s("ge", "endsia", "Name a country whose name ends in -ia", "$STATE ${label("STRENDS(LCASE(?l), \"ia\")")}", famousMin = 8),
        s("ge", "startss", "Name a country whose name starts with S", "$STATE ${label("STRSTARTS(?l, \"S\")")}", famousMin = 8),
        s("ge", "noa", "Name a country without the letter A in its name", "$STATE ${label("!CONTAINS(LCASE(?l), \"a\")")}", famousMin = 8),
        s("ge", "english", "Name a country where English is an official language", "$STATE ?item wdt:P37 wd:Q1860 .", famousMin = 8),
        s("ge", "french", "Name a country where French is an official language", "$STATE ?item wdt:P37 wd:Q150 .", famousMin = 8),
        s("ge", "monarchy", "Name a country that is a monarchy", "$STATE ?item wdt:P122/wdt:P279* wd:Q7269 .", famousMin = 8),
        s("ge", "islandnation", "Name an island country", "?item wdt:P31 wd:Q112099 ; wdt:P31 wd:Q3624078 . $NOW", famousMin = 8),
        s("ge", "southcapital", "Name a national capital south of the equator",
            "?c wdt:P31 wd:Q3624078 ; wdt:P36 ?item . FILTER NOT EXISTS { ?c wdt:P576 ?gone . } ?item wdt:P625 ?xy . FILTER(geof:latitude(?xy) < 0)",
            famousMin = 8),
        s("ge", "volcano5000", "Name a volcano higher than 5,000 m",
            "?item wdt:P31/wdt:P279* wd:Q8072 ; p:P2044/psn:P2044/wikibase:quantityAmount ?h . FILTER(?h >= 5000)", famousMin = 4),
        s("ge", "bigisland", "Name an island larger than 10,000 km²",
            "hint:Query hint:optimizer \"None\" . ?item wdt:P31 wd:Q23442 ; wikibase:sitelinks ?sl . FILTER(?sl >= 10) " +
                "?item p:P2046/psn:P2046/wikibase:quantityAmount ?a . FILTER(?a >= 1.0E10)", famousMin = 8),
        s("ge", "longriver", "Name a river longer than 1,000 km",
            "hint:Query hint:optimizer \"None\" . ?item wdt:P31 wd:Q4022 ; wikibase:sitelinks ?sl . FILTER(?sl >= 8) " +
                "?item p:P2043/psn:P2043/wikibase:quantityAmount ?len . FILTER(?len >= 1.0E6)", famousMin = 8),
        s("ge", "medisland", "Name an island in the Mediterranean", "?item wdt:P31 wd:Q23442 ; wdt:P206/wdt:P361* wd:Q4918 .",
            minLinks = 5, famousMin = 5),
        s("ge", "caribisland", "Name a Caribbean island", "?item wdt:P31 wd:Q23442 ; wdt:P206/wdt:P361* wd:Q1247 .", minLinks = 3, famousMin = 4),

        // ---------------- animals & nature
        s("an2", "marsupial", "Name a marsupial", species("Marsupialia"), minLinks = 5, famousMin = 4),
        s("an2", "lizard", "Name a lizard species",
            species("Iguanidae", "Agamidae", "Gekkota", "Scincidae", "Varanidae", "Chamaeleonidae", "Lacertidae", "Anguidae", "Helodermatidae"),
            minLinks = 8, famousMin = 3),
        s("an2", "spider", "Name a spider species",
            species("Theraphosidae", "Araneidae", "Salticidae", "Lycosidae", "Theridiidae", "Sicariidae", "Atracidae", "Ctenidae", "Pholcidae"),
            minLinks = 5, famousMin = 2),
        s("an2", "decapod", "Name a crab, lobster or shrimp", species("Decapoda"), minLinks = 8, famousMin = 3),
        s("an2", "flightless", "Name a flightless bird",
            species("Struthioniformes", "Rheiformes", "Casuariiformes", "Apterygiformes", "Sphenisciformes", "Dinornithiformes", "Aepyornithiformes"),
            famousMin = 4),
        s("an2", "venomsnake", "Name a venomous snake (cobra, viper, mamba…)", species("Elapidae", "Viperidae"), minLinks = 8, famousMin = 4),
        s("an2", "antelope", "Name an antelope or gazelle",
            species("Antilopinae", "Alcelaphinae", "Reduncinae", "Hippotraginae", "Cephalophinae", "Aepycerotinae"), minLinks = 5, famousMin = 4),
        s("an2", "butterfly", "Name a butterfly", species("Papilionidae", "Nymphalidae", "Pieridae", "Lycaenidae", "Hesperiidae"),
            minLinks = 8, famousMin = 2),
        s("na", "africapark", "Name a national park in Africa", "?item wdt:P31 wd:Q46169 ; wdt:P17/wdt:P30 wd:Q15 .", minLinks = 5, famousMin = 4),

        // ---------------- food & drink: dishes by country

        // ---------------- movies: awards, studios, decades, countries
        s("mv", "palme", "Name a film that won the Palme d'Or", "?item wdt:P166 wd:Q179808 ; wdt:P31/wdt:P279* wd:Q11424 .", famousMin = 8),
        s("mv", "billion", "Name a film that made more than \$1 billion",
            "$FILM p:P2142/psv:P2142 ?bo . ?bo wikibase:quantityAmount ?usd ; wikibase:quantityUnit wd:Q4917 . FILTER(?usd >= 1000000000)",
            famousMin = 8),
        s("mv", "pre1960", "Name a film released before 1960", "?item wdt:P31 wd:Q11424 ; wdt:P577 ?d . ${before("d", 1960)}", minLinks = 25, famousMin = 8),
        s("mv", "1980s", "Name a film from the 1980s", "?item wdt:P31 wd:Q11424 ; wdt:P577 ?d . ${years("d", 1980, 1990, "wdt:P577")}", minLinks = 30, famousMin = 8),
        s("mv", "1990s", "Name a film from the 1990s", "?item wdt:P31 wd:Q11424 ; wdt:P577 ?d . ${years("d", 1990, 2000, "wdt:P577")}", minLinks = 30, famousMin = 8),
        s("mv", "korean", "Name a South Korean film", "?item wdt:P31 wd:Q11424 ; wdt:P495 wd:Q884 .", minLinks = 10, famousMin = 3),

        // ---------------- music: decades, instrument families
        s("mu", "album1970s", "Name an album released in the 1970s", "?item wdt:P31 wd:Q482994 ; wdt:P577 ?d . ${years("d", 1970, 1980, "wdt:P577")}",
            minLinks = 15, famousMin = 6),
        s("mu", "album1990s", "Name an album released in the 1990s", "?item wdt:P31 wd:Q482994 ; wdt:P577 ?d . ${years("d", 1990, 2000, "wdt:P577")}",
            minLinks = 15, famousMin = 6),
        s("mu", "strings", "Name a string instrument", "?item wdt:P279+ wd:Q1798603 .", minLinks = 5, famousMin = 5),
        s("mu", "winds", "Name a wind instrument", "?item wdt:P279+ wd:Q173453 .", minLinks = 5, famousMin = 5),
        s("mu", "percussion", "Name a percussion instrument", "?item wdt:P279+ wd:Q133163 .", minLinks = 5, famousMin = 5),

        // ---------------- gaming: eras, publisher, platforms
        s("gm", "pre1990", "Name a video game released before 1990", "?item wdt:P31 wd:Q7889 ; wdt:P577 ?d . ${before("d", 1990)}",
            minLinks = 10, famousMin = 6),
        s("gm", "1990s", "Name a video game from the 1990s", "?item wdt:P31 wd:Q7889 ; wdt:P577 ?d . ${years("d", 1990, 2000, "wdt:P577")}",
            minLinks = 15, famousMin = 8),
        s("gm", "2000s", "Name a video game from the 2000s", "?item wdt:P31 wd:Q7889 ; wdt:P577 ?d . ${years("d", 2000, 2010, "wdt:P577")}",
            minLinks = 15, famousMin = 8),
        s("gm", "nintendo", "Name a game published by Nintendo", "?item wdt:P31 wd:Q7889 ; wdt:P123 wd:Q8093 .", minLinks = 8, famousMin = 8),
        s("gm", "wii", "Name a Wii game", "?item wdt:P31 wd:Q7889 ; wdt:P400 wd:Q8079 .", minLinks = 5, famousMin = 3),
        s("gm", "ps1", "Name a PlayStation (PS1) game", "?item wdt:P31 wd:Q7889 ; wdt:P400 wd:Q10677 .", minLinks = 5, famousMin = 3),
        s("gm", "ps2", "Name a PlayStation 2 game", "?item wdt:P31 wd:Q7889 ; wdt:P400 wd:Q10680 .", minLinks = 5, famousMin = 3),
        s("gm", "nes", "Name an NES game", "?item wdt:P31 wd:Q7889 ; wdt:P400 wd:Q172742 .", minLinks = 5, famousMin = 3),
        s("gm", "ds", "Name a Nintendo DS game", "?item wdt:P31 wd:Q7889 ; wdt:P400 wd:Q170323 .", minLinks = 5, famousMin = 3),
        s("gm", "gamecube", "Name a GameCube game", "?item wdt:P31 wd:Q7889 ; wdt:P400 wd:Q182172 .", minLinks = 5, famousMin = 3),

        // ---------------- TV
        s("tv", "hbo", "Name an HBO series", "?item wdt:P31 wd:Q5398426 ; wdt:P449 wd:Q23633 .", minLinks = 5, famousMin = 6),
        s("tv", "netflix", "Name a Netflix series", "?item wdt:P31 wd:Q5398426 ; wdt:P449 wd:Q907311 .", minLinks = 10, famousMin = 6),
        s("tv", "sitcom1990s", "Name a sitcom that started in the 1990s",
            "?item wdt:P31 wd:Q5398426 ; wdt:P136 wd:Q170238 ; wdt:P580|wdt:P577 ?d . ${years("d", 1990, 2000, "wdt:P580|wdt:P577")}", minLinks = 5, famousMin = 4),
        s("tv", "series2010s", "Name a TV series that started in the 2010s",
            "?item wdt:P31 wd:Q5398426 ; wdt:P580|wdt:P577 ?d . ${years("d", 2010, 2020, "wdt:P580|wdt:P577")}", minLinks = 30, famousMin = 8),
        s("tv", "tenseasons", "Name a TV series with 10 or more seasons", "?item wdt:P31 wd:Q5398426 ; wdt:P2437 ?n . FILTER(?n >= 10)",
            minLinks = 10, famousMin = 6),

        // ---------------- pop culture, myth, horror
        s("pc", "marvel", "Name a Marvel Comics character", "?item wdt:P1080 wd:Q931597 ; wdt:P31/wdt:P279* wd:Q95074 .", minLinks = 10, famousMin = 6),
        s("pc", "dc", "Name a DC Comics character", "?item wdt:P1080 wd:Q1152150 ; wdt:P31/wdt:P279* wd:Q95074 .", minLinks = 10, famousMin = 6),
        s("my", "greekbeast", "Name a creature from Greek mythology", "?item wdt:P1080 wd:Q34726 ; wdt:P31/wdt:P279* wd:Q2239243 .",
            minLinks = 5, famousMin = 5),
        s("ho", "horror1980s", "Name a horror film from the 1980s",
            "?item wdt:P31 wd:Q11424 ; wdt:P136 wd:Q200092 ; wdt:P577 ?d . ${years("d", 1980, 1990, "wdt:P577")}", minLinks = 8, famousMin = 5),
        s("ho", "king", "Name a Stephen King book or story", "?item wdt:P50 wd:Q39829 . FILTER NOT EXISTS { ?item wdt:P31 wd:Q3331189 . }",
            minLinks = 3, famousMin = 5),

        // ---------------- landmarks
        s("lm", "supertall", "Name a skyscraper taller than 300 m",
            "?item wdt:P31 wd:Q11303 ; p:P2048/psn:P2048/wikibase:quantityAmount ?h . FILTER(?h >= 300)", minLinks = 3, famousMin = 6),
        s("lm", "longbridge", "Name a bridge longer than 1 km",
            "?item wdt:P31/wdt:P279* wd:Q12280 ; p:P2043/psn:P2043/wikibase:quantityAmount ?len . FILTER(?len >= 1000)", minLinks = 5, famousMin = 4),
        s("lm", "busyairport", "Name an airport with more than 30 million passengers a year",
            "?item wdt:P31/wdt:P279* wd:Q1248784 ; wdt:P3872 ?pax . FILTER(?pax >= 30000000)", famousMin = 6),
        s("lm", "gothic", "Name a Gothic cathedral", "?item wdt:P31 wd:Q2977 ; wdt:P149/wdt:P279* wd:Q176483 .", minLinks = 8, famousMin = 6),
        s("lm", "parismuseum", "Name a museum in Paris", "?item wdt:P131+ wd:Q90 ; wdt:P31/wdt:P279* wd:Q33506 .", minLinks = 5, famousMin = 4),
        s("lm", "londonmuseum", "Name a museum in London", "?item wdt:P131+ wd:Q84 ; wdt:P31/wdt:P279* wd:Q33506 .", minLinks = 5, famousMin = 4),
        s("lm", "unescoafrica", "Name a UNESCO World Heritage Site in Africa", "?item wdt:P1435 wd:Q9259 ; wdt:P17/wdt:P30 wd:Q15 .", famousMin = 4),
        s("lm", "unescosam", "Name a UNESCO World Heritage Site in South America", "?item wdt:P1435 wd:Q9259 ; wdt:P17/wdt:P30 wd:Q18 .", famousMin = 4),

        // ---------------- sport (places and clubs, not people)
        s("sp", "bigstadium", "Name a stadium with more than 50,000 seats",
            "?item wdt:P31/wdt:P279* wd:Q483110 ; wdt:P1083 ?cap . FILTER(?cap >= 50000)", minLinks = 5, famousMin = 6),
        s("sp", "oldclub", "Name a football club founded before 1900", "?item wdt:P31 wd:Q476028 ; wdt:P571 ?d . ${before("d", 1900)}",
            minLinks = 10, famousMin = 8),

        // ---------------- history
        s("hi", "ww1", "Name a battle of World War I",
            "VALUES ?cls { wd:Q178561 wd:Q188055 wd:Q645883 } ?item wdt:P31 ?cls ; wdt:P361+ wd:Q361 .", minLinks = 8, famousMin = 5),
        s("hi", "ww2", "Name a battle or operation of World War II",
            "VALUES ?cls { wd:Q178561 wd:Q188055 wd:Q645883 } ?item wdt:P31 ?cls ; wdt:P361+ wd:Q362 .", minLinks = 10, famousMin = 6),
        s("hi", "war1800s", "Name a war that began in the 1800s",
            "VALUES ?cls { wd:Q198 wd:Q8465 } ?item wdt:P31 ?cls ; wdt:P580 ?d . ${years("d", 1800, 1900, "wdt:P580")}", minLinks = 10, famousMin = 6),
        s("hi", "oldempire", "Name an empire founded before the year 1000", "?item wdt:P31 wd:Q48349 ; wdt:P571 ?d . ${before("d", 1000)}",
            minLinks = 5, famousMin = 6),
        s("hi", "pyramid", "Name an Egyptian pyramid", "?item wdt:P31/wdt:P279* wd:Q12516 ; wdt:P17 wd:Q79 .", minLinks = 2, famousMin = 2),

        // ---------------- brands & tech
        s("br", "carde", "Name a German car maker", "?item wdt:P31 wd:Q786820 ; wdt:P17 wd:Q183 .", minLinks = 3, famousMin = 4),
        s("br", "carit", "Name an Italian car maker", "?item wdt:P31 wd:Q786820 ; wdt:P17 wd:Q38 .", minLinks = 3, famousMin = 4),
        s("br", "euairline", "Name a European airline", "?item wdt:P31 wd:Q46970 ; wdt:P17/wdt:P30 wd:Q46 .", minLinks = 5, famousMin = 5),
        s("br", "oldcompany", "Name a company founded before 1900", "?item wdt:P31 wd:Q891723 ; wdt:P571 ?d . ${before("d", 1900)}",
            minLinks = 20, famousMin = 8),
        s("br", "japanco", "Name a Japanese company", "?item wdt:P31 wd:Q891723 ; wdt:P17 wd:Q17 .", minLinks = 15, famousMin = 6),
        s("tc", "apple", "Name an Apple product or app", "{ ?item wdt:P176 wd:Q312 } UNION { ?item wdt:P178 wd:Q312 }", minLinks = 10, famousMin = 6),
        s("tc", "google", "Name a Google product or service",
            "{ ?item wdt:P178 wd:Q95 } UNION { ?item wdt:P176 wd:Q95 } UNION { ?item wdt:P127 wd:Q95 }", minLinks = 10, famousMin = 6),
        s("tc", "microsoft", "Name a Microsoft product",
            "{ ?item wdt:P178 wd:Q2283 } UNION { ?item wdt:P176 wd:Q2283 }", minLinks = 10, famousMin = 6),
        s("tc", "oldlang", "Name a programming language created before 1980",
            "?item wdt:P31/wdt:P279* wd:Q9143 ; wdt:P571 ?d . ${before("d", 1980)}", minLinks = 5, famousMin = 6),
        s("tc", "oldsite", "Name a website launched before 2005", "?item wdt:P31 wd:Q35127 ; wdt:P571 ?d . ${before("d", 2005)}",
            minLinks = 15, famousMin = 8),

        // ---------------- science, words, books & art
        s("sc", "ium", "Name a chemical element ending in -ium", "?item wdt:P31 wd:Q11344 . ${label("STRENDS(LCASE(?l), \"ium\")")}", famousMin = 8),
        s("sc", "moons", "Name a moon of Jupiter or Saturn",
            "VALUES ?planet { wd:Q319 wd:Q193 } ?item wdt:P397 ?planet ; wdt:P31/wdt:P279* wd:Q2537 .", famousMin = 6),
        s("sc", "comet", "Name a comet", "?item wdt:P31/wdt:P279* wd:Q3559 .", minLinks = 3, famousMin = 3),
        s("wo", "biglanguage", "Name a language with more than 10 million speakers",
            "?item wdt:P1098 ?n . FILTER(?n >= 10000000) ?item wdt:P31/wdt:P279* wd:Q34770 .", famousMin = 8),
        s("bk", "oldnovel", "Name a novel published before 1900",
            "{ ?item wdt:P7937 wd:Q8261 } UNION { ?item wdt:P31 wd:Q8261 } ?item wdt:P577 ?d . ${before("d", 1900)}", minLinks = 15, famousMin = 8),
        s("bk", "oldpainting", "Name a painting made before 1700",
            "hint:Query hint:optimizer \"None\" . ?item wdt:P31 wd:Q3305213 ; wikibase:sitelinks ?sl . FILTER(?sl >= 8) " +
                "?item wdt:P571 ?d . ${before("d", 1700)}", famousMin = 6),
        s("bk", "shakespeare", "Name a play or poem by Shakespeare", "?item wdt:P50 wd:Q692 .", minLinks = 10, famousMin = 8),
        s("bk", "louvre", "Name a work of art in the Louvre", "?item wdt:P195 wd:Q19675 .", minLinks = 5, famousMin = 4),
    )

    val ids: Set<String> by lazy { all.mapTo(HashSet()) { it.id } }
}
