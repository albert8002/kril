package app.coredump.data

/**
 * Extra everyday lists that are NOT bundled: the device collects a few every day (and on request).
 * Only general categories (no "works by <person>" style lists). Lists that come back with fewer than
 * [WikidataFamilies.MIN_ANSWERS] answers, or with too few widely known answers, are skipped for weeks.
 */
object WikidataPool {

    private fun t(id: String, prompt: String, topic: String, where: String, minLinks: Int = 0, limit: Int = 800) =
        WikidataTemplate("wx.$id", prompt, topic, where, minLinks, limit)

    private fun inst(q: String) = "?item wdt:P31 wd:$q ."
    private fun instSub(q: String) = "?item wdt:P31/wdt:P279* wd:$q ."
    private fun subOf(q: String) = "?item wdt:P279+ wd:$q ."
    private fun famous(occ: String) = "?item wdt:P31 wd:Q5 ; wdt:P106 wd:$occ ."
    private fun species(vararg taxa: String) =
        "VALUES ?tl { ${taxa.joinToString(" ") { "\"$it\"@en" }} } ?t rdfs:label ?tl ; wdt:P105 ?rank . " +
            "?item wdt:P171+ ?t ; wdt:P105 wd:Q7432 ."

    private val animals = listOf(
        Triple("bat", "Name a bat species", arrayOf("Chiroptera")),
        Triple("pigeon", "Name a pigeon or dove", arrayOf("Columbidae")),
        Triple("mustelid", "Name a weasel, otter, badger or relative", arrayOf("Mustelidae")),
        Triple("cephalopod", "Name an octopus, squid or cuttlefish", arrayOf("Cephalopoda")),
        Triple("bovid", "Name an antelope, wild goat or wild cattle", arrayOf("Bovidae")),
        Triple("monkey", "Name a monkey species", arrayOf("Cercopithecidae", "Atelidae", "Cebidae")),
        Triple("woodpecker", "Name a woodpecker", arrayOf("Picidae")),
        Triple("hummingbird", "Name a hummingbird", arrayOf("Trochilidae")),
        Triple("crow", "Name a crow, raven, jay or magpie", arrayOf("Corvidae")),
        Triple("salmonid", "Name a salmon, trout or char", arrayOf("Salmonidae")),
        Triple("penguin", "Name a penguin", arrayOf("Spheniscidae")),
        Triple("bear", "Name a bear", arrayOf("Ursidae")),
        Triple("crocodile", "Name a crocodile, alligator or caiman", arrayOf("Crocodilia")),
        Triple("seal", "Name a seal, sea lion or walrus", arrayOf("Pinnipedia")),
        Triple("kangaroo", "Name a kangaroo or wallaby", arrayOf("Macropodidae")),
        Triple("ape", "Name an ape", arrayOf("Hominoidea")),
        Triple("owl", "Name an owl", arrayOf("Strigiformes")),
        Triple("gull", "Name a gull or tern", arrayOf("Laridae")),
        Triple("heron", "Name a heron or egret", arrayOf("Ardeidae")),
        Triple("bee", "Name a bee", arrayOf("Apidae")),
    )

    private val jobs = listOf(
        "Q488205" to "singer-songwriter", "Q3282637" to "film producer", "Q1930187" to "journalist",
        "Q2405480" to "voice actor", "Q2865819" to "opera singer", "Q486748" to "pianist", "Q1259917" to "violinist",
        "Q158852" to "conductor", "Q386854" to "drummer", "Q584301" to "bassist", "Q753110" to "songwriter",
        "Q11513337" to "track and field athlete", "Q4144610" to "alpine skier", "Q9149093" to "mountaineer",
        "Q13219587" to "figure skater", "Q16029547" to "gymnast", "Q846750" to "jockey", "Q15117302" to "volleyball player",
        "Q14089670" to "rugby player", "Q15295720" to "poker player", "Q17361156" to "skateboarder",
        "Q1622272" to "professor", "Q39631" to "physician", "Q201788" to "historian", "Q1028181" to "painter",
        "Q639669" to "musician", "Q36180" to "novelist", "Q2259451" to "stage actor", "Q10798782" to "television actor",
        "Q3387717" to "theatre director", "Q1281618" to "sculptor", "Q131524" to "entrepreneur", "Q484876" to "CEO",
        "Q1397808" to "resistance fighter", "Q189290" to "military officer", "Q116" to "monarch",
    )

    private val places = listOf(
        Triple("castle", "Name a castle", inst("Q23413") to 40),
        Triple("palace", "Name a palace", inst("Q16560") to 30),
        Triple("cathedral", "Name a cathedral", inst("Q2977") to 40),
        Triple("church", "Name a famous church", inst("Q16970") to 50),
        Triple("tower", "Name a tower", instSub("Q12518") to 30),
        Triple("statue", "Name a famous statue", inst("Q179700") to 30),
        Triple("airport", "Name an airport", inst("Q1248784") to 40),
        Triple("street", "Name a famous street", inst("Q79007") to 40),
        Triple("square", "Name a famous square or plaza", inst("Q174782") to 30),
        Triple("park", "Name a famous park", inst("Q22698") to 30),
        Triple("zoo", "Name a zoo", inst("Q43501") to 15),
        Triple("themepark", "Name a theme park", instSub("Q2416723") to 15),
        Triple("island", "Name an island anywhere", inst("Q23442") to 80),
        Triple("river", "Name a river anywhere", inst("Q4022") to 80),
        Triple("mountain", "Name a mountain anywhere", inst("Q8502") to 60),
        Triple("range", "Name a mountain range", inst("Q46831") to 40),
        Triple("peninsula", "Name a peninsula", inst("Q34763") to 40),
        Triple("bay", "Name a bay or gulf", inst("Q39594") to 40),
        Triple("waterfall", "Name a waterfall", inst("Q34038") to 20),
        Triple("cave", "Name a cave", inst("Q35509") to 15),
    )

    private val things = listOf(
        Triple("carmodel", "Name a car model", inst("Q3231690") to 30, ),
        Triple("aircraft", "Name an aircraft model", instSub("Q15056993") to 30),
        Triple("franchise", "Name a video game franchise", inst("Q7058673") to 20),
        Triple("comic", "Name a comic book series", inst("Q14406742") to 20),
        Triple("manga", "Name a manga", inst("Q21198342") to 30),
        Triple("anime", "Name an anime series", inst("Q63952888") to 25),
        Triple("toy", "Name a toy", subOf("Q11422") to 20),
        Triple("spice", "Name a spice", instSub("Q42527") to 20),
        Triple("rock", "Name a type of rock", subOf("Q8063") to 20),
        Triple("spacecraft", "Name a spacecraft or space probe", instSub("Q40218") to 30),
        Triple("rocket", "Name a rocket", instSub("Q41291") to 20),
        Triple("ballet", "Name a ballet", inst("Q2742") to 15),
        Triple("weapon", "Name a type of weapon", subOf("Q728") to 30),
        Triple("instrument2", "Name a musical instrument (any culture)", subOf("Q34379") to 20),
        Triple("game", "Name a game (any kind)", subOf("Q11410") to 30),
        Triple("ballgame", "Name a ball game", subOf("Q1186224") to 15),
        Triple("dog2", "Name a dog breed (any country)", inst("Q39367") to 15),
        Triple("fruit", "Name a fruit (botanical or culinary)", subOf("Q1364") to 25),
        Triple("vegetable", "Name a vegetable", subOf("Q11004") to 20),
        Triple("dessert", "Name a dessert", anyOfPool("Q182940") to 15),
        Triple("snack", "Name a snack", anyOfPool("Q749316") to 10),
        Triple("candy", "Name a sweet or candy", anyOfPool("Q185583") to 10),
        Triple("beer", "Name a beer brand", inst("Q14943511") to 10),
        Triple("softdrink", "Name a soft drink brand", instSub("Q147538") to 10),
        Triple("chain", "Name a restaurant chain", instSub("Q18534") to 15),
        Triple("magazine2", "Name a famous magazine", inst("Q41298") to 25),
        Triple("tvchannel", "Name a TV channel", inst("Q2001305") to 25),
        Triple("radio", "Name a radio station", inst("Q14350") to 20),
        Triple("app", "Name an app or online service", instSub("Q1371849") to 30),
        Triple("emoji", "Name a famous internet meme", inst("Q2927074") to 15),
    )

    private fun anyOfPool(q: String) = "?item (wdt:P31|wdt:P279)/wdt:P279* wd:$q ."

    val all: List<WikidataTemplate> by lazy {
        buildList {
            for ((id, prompt, taxa) in animals) add(t("an.$id", prompt, "NATURE", species(*taxa), 10))
            for ((q, job) in jobs) add(t("job.${job.filter { it.isLetter() }}", "Name a famous $job",
                if (job.endsWith("player") || job.endsWith("skier") || job.endsWith("athlete") || job.endsWith("skater") ||
                    job == "gymnast" || job == "jockey" || job == "skateboarder" || job == "mountaineer") "SPORT" else "PEOPLE",
                famous(q), 30))
            for ((id, prompt, w) in places) add(t("pl.$id", prompt, if (id in setOf("castle", "palace", "cathedral", "church", "statue")) "HISTORY" else "GEOGRAPHY", w.first, w.second))
            for ((id, prompt, w) in things) add(t("th.$id", prompt,
                when (id) {
                    "fruit", "vegetable", "dessert", "snack", "candy", "beer", "softdrink", "chain", "spice" -> "FOOD"
                    "rock", "spacecraft", "rocket" -> "SCIENCE"
                    "dog2" -> "NATURE"
                    "weapon" -> "HISTORY"
                    else -> "CULTURE"
                }, w.first, w.second))
        }
    }

    /** Order in which the device works through the pool: interleaves the families so each daily batch is varied. */
    val order: List<WikidataTemplate> by lazy {
        val groups = all.groupBy { it.id.split('.').getOrElse(1) { "" } }.values.map { it.toMutableList() }
        val out = ArrayList<WikidataTemplate>(all.size)
        var i = 0
        while (out.size < all.size) {
            for (g in groups) if (i < g.size) out += g[i]
            i++
        }
        out
    }
}
