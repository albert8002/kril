package app.coredump.data

/**
 * Extra everyday lists that are NOT bundled: the device collects a few every day (and on request).
 * Only general categories (no "works by <person>" style lists). Lists that come back with fewer than
 * [WikidataFamilies.MIN_ANSWERS] answers, or with too few widely known answers, are skipped for weeks.
 */
object WikidataPool {

    private fun t(id: String, prompt: String, topic: String, where: String, minLinks: Int = 0, limit: Int = 800) =
        WikidataTemplate("wx.$id", prompt, topic, where, minLinks, limit,
            when { id == "br.hotelchain" -> 0; id in everyday -> 2; else -> WikidataTemplates.FAMOUS_MIN })

    /** Everyday-object lists: few answers have many Wikipedia pages, but people know them. */
    private val everyday = setOf("ev.hat", "ev.shoe", "ev.cardgame", "fd.coffee", "fd.noodle", "fd.tea", "br.supermarket",
        "br.hotelchain", "br.retail", "lm.amusement", "pl.themepark", "pl.zoo", "ge.beach", "lm.fountain", "lm.lighthouse",
        "an.kangaroo", "th.candy", "th.snack", "th.toy", "th.comic", "th.tvchannel", "nx.badminton", "nx.fencer",
        "nx.handball", "nx.tabletennis")

    private fun inst(q: String) = "?item wdt:P31 wd:$q ."
    private fun instSub(q: String) = "?item wdt:P31/wdt:P279* wd:$q ."
    private fun subOf(q: String) = "?item wdt:P279+ wd:$q ."
    private fun famous(occ: String) = "?item wdt:P31 wd:Q5 ; wdt:P106 wd:$occ ."
    private fun species(vararg taxa: String) =
        "VALUES ?tl { ${taxa.joinToString(" ") { "\"$it\"@en" }} } ?t rdfs:label ?tl ; wdt:P105 ?rank . " +
            "?item wdt:P171+ ?t ; wdt:P105 wd:Q7432 ."

    private val animals = listOf(
        Triple("bat", "Name a bat species", arrayOf("Chiroptera", "bat")),
        Triple("pigeon", "Name a pigeon or dove", arrayOf("Columbidae", "Columbiformes")),
        Triple("mustelid", "Name a weasel, otter, badger or relative", arrayOf("Mustelidae")),
        Triple("cephalopod", "Name an octopus, squid or cuttlefish", arrayOf("Cephalopoda", "cephalopod")),
        Triple("monkey", "Name a monkey species", arrayOf("Cercopithecidae", "Atelidae", "Cebidae")),
        Triple("woodpecker", "Name a woodpecker", arrayOf("Picidae", "woodpecker")),
        Triple("hummingbird", "Name a hummingbird", arrayOf("Trochilidae", "hummingbird")),
        Triple("crow", "Name a crow, raven, jay or magpie", arrayOf("Corvidae")),
        Triple("salmonid", "Name a salmon, trout or char", arrayOf("Salmonidae")),
        Triple("penguin", "Name a penguin", arrayOf("Spheniscidae", "penguin")),
        Triple("bear", "Name a bear", arrayOf("Ursidae", "bear")),
        Triple("crocodile", "Name a crocodile, alligator or caiman", arrayOf("Crocodilia")),
        Triple("seal", "Name a seal, sea lion or walrus", arrayOf("Pinnipedia", "pinniped")),
        Triple("kangaroo", "Name a kangaroo or wallaby", arrayOf("Macropodidae")),
        Triple("ape", "Name an ape", arrayOf("Hominoidea", "ape")),
        Triple("owl", "Name an owl", arrayOf("Strigiformes", "owl")),
        Triple("gull", "Name a gull or tern", arrayOf("Laridae")),
        Triple("bee", "Name a bee", arrayOf("Apidae", "bee")),
    )

    private val jobs = listOf(
        "Q488205" to "singer-songwriter", "Q3282637" to "film producer", "Q1930187" to "journalist",
        "Q2405480" to "voice actor", "Q2865819" to "opera singer", "Q486748" to "pianist", "Q1259917" to "violinist",
        "Q158852" to "conductor", "Q386854" to "drummer", "Q584301" to "bassist", "Q753110" to "songwriter",
        "Q11513337" to "track and field athlete", "Q4144610" to "alpine skier", "Q9149093" to "mountaineer",
        "Q13219587" to "figure skater", 
        "Q15295720" to "poker player", 
        "Q1622272" to "professor", "Q39631" to "physician", "Q201788" to "historian", "Q1028181" to "painter",
        "Q639669" to "musician", "Q36180" to "novelist", "Q2259451" to "stage actor", "Q10798782" to "television actor",
        "Q3387717" to "theatre director", "Q1281618" to "sculptor", "Q131524" to "entrepreneur", "Q484876" to "CEO",
        "Q1397808" to "resistance fighter", "Q189290" to "military officer", "Q116" to "monarch",
    )

    private val places = listOf(
        Triple("castle", "Name a castle", inst("Q23413") to 40),
        Triple("palace", "Name a palace", inst("Q16560") to 30),
        Triple("cathedral", "Name a cathedral", inst("Q2977") to 15),
        Triple("church", "Name a famous church", inst("Q16970") to 20),
        Triple("tower", "Name a tower", instSub("Q12518") to 30),
        Triple("statue", "Name a famous statue", inst("Q179700") to 10),
        Triple("airport", "Name an airport", inst("Q1248784") to 40),
        Triple("street", "Name a famous street", inst("Q79007") to 15),
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
        Triple("rock", "Name a type of rock", subOf("Q8063") to 20),
        Triple("spacecraft", "Name a spacecraft or space probe", instSub("Q40218") to 30),
        Triple("weapon", "Name a type of weapon", subOf("Q728") to 30),
        Triple("instrument2", "Name a musical instrument (any culture)", subOf("Q34379") to 20),
        Triple("game", "Name a game (any kind)", subOf("Q11410") to 30),
        Triple("dog2", "Name a dog breed (any country)", inst("Q39367") to 15),
        Triple("fruit", "Name a fruit (botanical or culinary)", subOf("Q1364") to 25),
        Triple("vegetable", "Name a vegetable", subOf("Q11004") to 20),
        Triple("dessert", "Name a dessert", anyOfPool("Q182940") to 15),
        Triple("snack", "Name a snack", anyOfPool("Q749316") to 10),
        Triple("candy", "Name a sweet or candy", anyOfPool("Q185583") to 10),
        Triple("chain", "Name a restaurant chain", instSub("Q18534") to 15),
        Triple("magazine2", "Name a famous magazine", inst("Q41298") to 25),
        Triple("tvchannel", "Name a TV channel", inst("Q2001305") to 25),
        Triple("radio", "Name a radio station", inst("Q14350") to 20),
        Triple("emoji", "Name a famous internet meme", inst("Q2927074") to 15),
    )

    private fun anyOfPool(q: String) = "?item (wdt:P31|wdt:P279)/wdt:P279* wd:$q ."
    private fun topicOf(id: String) = when (id.substringBefore('.')) {
        "tv" -> "TV"; "mv" -> "MOVIES"; "pc" -> "POP CULTURE"; "mu" -> "MUSIC"; "gm" -> "GAMING"; "tc" -> "TECH"
        "bk" -> "BOOKS & ART"; "fd" -> "FOOD & DRINK"; "ge" -> "GEOGRAPHY"; "lm" -> "LANDMARKS"; "hi" -> "HISTORY"
        "sp" -> "SPORT"; "an2" -> "ANIMALS"; "na" -> "NATURE"; "sc" -> "SCIENCE"; "bd" -> "BODY"; "my" -> "MYTH"
        "ev" -> "EVERYDAY"; "br" -> "BRANDS"; else -> "PEOPLE"
    }
    private fun genre(cls: String, g: String) = "?item wdt:P31 wd:$cls ; wdt:P136 wd:$g ."
    private fun bigCityIn(continent: String) = "?item wdt:P31 wd:Q1549591 ; wdt:P17/wdt:P30 wd:$continent ."

    /** More everyday categories (id, prompt, pattern, minimum sitelinks). Checked by the CI pool check. */
    private val more: List<Pair<String, Pair<String, Pair<String, Int>>>> = listOf(
        // TV
        "tv.soap" to ("Name a soap opera" to (genre("Q5398426", "Q23739") to 12)),
        "tv.character" to ("Name a TV show character" to (inst("Q15773317") to 25)),
        // movies / pop culture characters
        "mv.character" to ("Name a film character" to (inst("Q15773347") to 25)),
        "pc.comicchar" to ("Name a comic book character" to (inst("Q1114461") to 25)),
        "pc.animchar" to ("Name an animated character" to (inst("Q15711870") to 20)),
        "pc.litchar" to ("Name a character from a book" to (inst("Q3658341") to 25)),
        // music
        "mu.rock" to ("Name a rock band" to (genre("Q215380", "Q11399") to 35)),
        "mu.indie" to ("Name an indie rock band" to (genre("Q215380", "Q183504") to 20)),
        "mu.hiphop" to ("Name a hip hop artist" to (genre("Q5", "Q11401") to 30)),
        "mu.pop" to ("Name a pop singer" to (genre("Q5", "Q37073") to 40)),
        "mu.electronic" to ("Name an electronic music artist" to (genre("Q5", "Q9778") to 25)),
        "mu.rnb" to ("Name an R&B singer" to (genre("Q5", "Q45981") to 25)),
        "mu.soul" to ("Name a soul singer" to (genre("Q5", "Q131272") to 25)),
        "mu.folk" to ("Name a folk singer" to (genre("Q5", "Q43343") to 20)),
        "mu.girlgroup" to ("Name a girl group" to (instSub("Q641066") to 15)),
        "mu.boyband" to ("Name a boy band" to (instSub("Q216337") to 15)),
        "mu.anthem" to ("Name a national anthem" to (inst("Q23691") to 20)),
        // gaming / tech
        "gm.mobile" to ("Name a mobile game" to ("?item wdt:P31 wd:Q7889 ; wdt:P400 wd:Q94 ." to 30)),
        "gm.studio" to ("Name a video game company" to (inst("Q210167") to 30)),
        // books & art
        "bk.sculpture" to ("Name a famous sculpture" to (inst("Q860861") to 20)),
        "bk.fairytale" to ("Name a fairy tale or folk tale" to (instSub("Q699") to 15)),
        // food & drink
        "fd.noodle" to ("Name a noodle dish or noodle" to (subOf("Q192874") to 10)),
        "fd.tea" to ("Name a type of tea" to (subOf("Q6097") to 8)),
        "fd.coffee" to ("Name a coffee drink" to (subOf("Q8486") to 8)),
        // geography
        "ge.africa" to ("Name a big city in Africa" to (bigCityIn("Q15") to 20)),
        "ge.europe" to ("Name a big city in Europe" to (bigCityIn("Q46") to 30)),
        "ge.namerica" to ("Name a big city in North America" to (bigCityIn("Q49") to 20)),
        "ge.archipelago" to ("Name an archipelago" to (inst("Q33837") to 25)),
        "ge.beach" to ("Name a famous beach" to (inst("Q40080") to 12)),
        "ge.strait" to ("Name a strait" to (inst("Q37901") to 30)),
        // landmarks
        "lm.monument" to ("Name a famous monument" to (inst("Q4989906") to 25)),
        "lm.lighthouse" to ("Name a lighthouse" to (inst("Q39715") to 12)),
        "lm.temple" to ("Name a temple, church or other place of worship" to (instSub("Q44539") to 20)),
        "lm.mosque" to ("Name a mosque" to (inst("Q32815") to 25)),
        "lm.amusement" to ("Name an amusement park" to (inst("Q194195") to 15)),
        "lm.archaeo" to ("Name an archaeological site" to (inst("Q839954") to 35)),
        "lm.fountain" to ("Name a famous fountain" to (inst("Q483453") to 12)),
        "lm.library" to ("Name a famous library" to (inst("Q7075") to 20)),
        "lm.opera" to ("Name an opera house" to (inst("Q153562") to 15)),
        "lm.canal" to ("Name a canal" to (inst("Q12284") to 20)),
        // history
        "hi.battle" to ("Name a battle" to (inst("Q178561") to 30)),
        "hi.dynasty" to ("Name a dynasty" to (inst("Q164950") to 25)),
        "hi.revolution" to ("Name a revolution" to (instSub("Q10931") to 25)),
        "hi.ancientcity" to ("Name an ancient city" to (inst("Q15661340") to 30)),
        "hi.shipwreck" to ("Name a famous shipwreck" to (inst("Q852190") to 12)),
        "hi.oldcountry" to ("Name a country that no longer exists" to (inst("Q3024240") to 50)),
        "hi.pandemic" to ("Name an epidemic or pandemic" to (instSub("Q44512") to 20)),
        // sport
        "sp.nba" to ("Name a basketball team" to (inst("Q13393265") to 20)),
        "sp.nfl" to ("Name an American football team" to (inst("Q17156793") to 20)),
        "sp.hockey" to ("Name an ice hockey team" to (inst("Q4498974") to 20)),
        "sp.baseball" to ("Name a baseball team" to (inst("Q13027888") to 20)),
        "sp.league" to ("Name a sports league" to (instSub("Q623109") to 30)),
        "sp.circuit" to ("Name a motor racing circuit" to (inst("Q2338524") to 25)),
        // animals (by taxon; common English names tried too)
        "an2.snake" to ("Name a snake species" to (species("Serpentes", "snake") to 20)),
        "an2.dolphin" to ("Name a dolphin" to (species("Delphinidae", "oceanic dolphin") to 15)),
        "an2.hawk" to ("Name a hawk or eagle" to (species("Accipitridae") to 30)),
        "an2.falcon" to ("Name a falcon" to (species("Falco", "Falconidae") to 15)),
        "an2.shark" to ("Name a shark" to (species("Selachimorpha", "shark", "Selachii") to 15)),
        "an2.parrot" to ("Name a parrot" to (species("Psittaciformes", "parrot", "Psittacidae") to 15)),
        "an2.rodent" to ("Name a rodent" to (species("Rodentia", "rodent") to 25)),
        // nature / science / body / myth
        "na.gemstone" to ("Name a gemstone" to (anyOfPool("Q83437") to 15)),
        "sc.telescope" to ("Name a space telescope" to (instSub("Q148578") to 15)),
        "my.saint" to ("Name a saint" to ("?item wdt:P31 wd:Q5 ; wdt:P411 wd:Q43115 ." to 60)),
        "my.text" to ("Name a religious text" to (instSub("Q179461") to 25)),
        // everyday / brands
        "ev.hat" to ("Name a type of hat" to (subOf("Q80151") to 10)),
        "ev.shoe" to ("Name a type of shoe" to (subOf("Q22676") to 10)),
        "ev.cardgame" to ("Name a card game" to (instSub("Q142714") to 15)),
        "br.supermarket" to ("Name a supermarket chain" to (inst("Q18043413") to 12)),
        "br.hotelchain" to ("Name a hotel chain" to (inst("Q1631129") to 10)),
        "br.retail" to ("Name a retail chain" to (inst("Q507619") to 15)),
        "br.bank" to ("Name a bank" to (inst("Q22687") to 30)),
        // more people
        "nx.activist" to ("Name a famous activist" to (famous("Q15253558") to 40)),
        "nx.lawyer" to ("Name a famous lawyer" to (famous("Q40348") to 40)),
        "nx.engineer" to ("Name a famous engineer" to (famous("Q81096") to 30)),
        "nx.computer" to ("Name a famous computer scientist" to (famous("Q82594") to 25)),
        "nx.screenwriter" to ("Name a famous screenwriter" to (famous("Q28389") to 40)),
        "nx.choreographer" to ("Name a famous choreographer" to (famous("Q2490358") to 20)),
        "nx.botanist" to ("Name a famous botanist" to (famous("Q2374149") to 25)),
        "nx.badminton" to ("Name a famous badminton player" to (famous("Q13141064") to 15)),
        "nx.tabletennis" to ("Name a famous table tennis player" to (famous("Q13382519") to 15)),
        "nx.sprinter" to ("Name a famous sprinter" to (famous("Q4009406") to 25)),
        "nx.handball" to ("Name a famous handball player" to (famous("Q12840545") to 15)),
        "nx.fencer" to ("Name a famous fencer" to (famous("Q13381863") to 15)),
        "nx.businessperson" to ("Name a famous businessperson" to (famous("Q43845") to 40)),
    )

    val all: List<WikidataTemplate> by lazy {
        buildList {
            for ((id, prompt, taxa) in animals) add(t("an.$id", prompt, "NATURE", species(*taxa), 10))
            for ((q, job) in jobs) add(t("job.${job.filter { it.isLetter() }}", "Name a famous $job",
                if (job.endsWith("player") || job.endsWith("skier") || job.endsWith("athlete") || job.endsWith("skater") ||
                    job == "gymnast" || job == "jockey" || job == "skateboarder" || job == "mountaineer") "SPORT" else "PEOPLE",
                famous(q), 30))
            for ((id, prompt, w) in places) add(t("pl.$id", prompt, if (id in setOf("castle", "palace", "cathedral", "church", "statue")) "HISTORY" else "GEOGRAPHY", w.first, w.second))
            for ((id, rest) in more) add(t(id, rest.first, topicOf(id), rest.second.first, rest.second.second))
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
