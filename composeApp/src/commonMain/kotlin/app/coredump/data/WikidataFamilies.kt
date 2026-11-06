package app.coredump.data

/**
 * Programmatic prompt families: one SPARQL pattern × many parameters (countries, genres, occupations…).
 * Built into the app by the CI snapshot; lists with fewer than [WikidataFamilies.MIN_ANSWERS] answers are dropped.
 */
object WikidataFamilies {
    const val MIN_ANSWERS = 24

    private class Country(val q: String, val name: String, val cityPop: Int)

    private val countries = listOf(
        Country("Q30", "the United States", 150000), Country("Q145", "the United Kingdom", 60000),
        Country("Q142", "France", 30000), Country("Q183", "Germany", 60000), Country("Q38", "Italy", 40000),
        Country("Q29", "Spain", 40000), Country("Q17", "Japan", 150000), Country("Q148", "China", 700000),
        Country("Q668", "India", 400000), Country("Q155", "Brazil", 200000), Country("Q16", "Canada", 30000),
        Country("Q408", "Australia", 20000), Country("Q96", "Mexico", 150000), Country("Q159", "Russia", 150000),
        Country("Q55", "the Netherlands", 40000), Country("Q34", "Sweden", 15000), Country("Q20", "Norway", 8000),
        Country("Q36", "Poland", 40000), Country("Q39", "Switzerland", 10000), Country("Q40", "Austria", 5000),
        Country("Q31", "Belgium", 20000), Country("Q45", "Portugal", 8000), Country("Q41", "Greece", 20000),
        Country("Q43", "Turkey", 150000), Country("Q79", "Egypt", 150000), Country("Q258", "South Africa", 60000),
        Country("Q1033", "Nigeria", 200000), Country("Q414", "Argentina", 50000), Country("Q298", "Chile", 30000),
        Country("Q739", "Colombia", 60000), Country("Q252", "Indonesia", 300000), Country("Q928", "the Philippines", 150000),
        Country("Q881", "Vietnam", 100000), Country("Q869", "Thailand", 50000), Country("Q884", "South Korea", 150000),
        Country("Q794", "Iran", 150000), Country("Q843", "Pakistan", 200000), Country("Q212", "Ukraine", 50000),
        Country("Q27", "Ireland", 5000), Country("Q35", "Denmark", 10000), Country("Q33", "Finland", 5000),
        Country("Q213", "the Czech Republic", 20000), Country("Q28", "Hungary", 20000), Country("Q218", "Romania", 40000),
        Country("Q664", "New Zealand", 3000), Country("Q801", "Israel", 20000), Country("Q851", "Saudi Arabia", 60000),
        Country("Q114", "Kenya", 30000), Country("Q1028", "Morocco", 60000), Country("Q419", "Peru", 50000),
    )
    private val byQ = countries.associateBy { it.q }
    private fun c(q: String) = byQ.getValue(q).name

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun t(id: String, prompt: String, topic: String, where: String, minLinks: Int = 0, limit: Int = 1200) =
        WikidataTemplate("wf.$id", prompt, topic, where, minLinks, limit)

    private fun person(occ: String) = "?item wdt:P31 wd:Q5 ; wdt:P106 wd:$occ ."
    // Dutch citizenship is mostly recorded as the Kingdom of the Netherlands (Q29999), not the country Q55.
    private fun personFrom(occ: String, country: String) =
        if (country == "Q55") "VALUES ?ctz { wd:Q55 wd:Q29999 } ?item wdt:P31 wd:Q5 ; wdt:P106 wd:$occ ; wdt:P27 ?ctz ."
        else "?item wdt:P31 wd:Q5 ; wdt:P106 wd:$occ ; wdt:P27 wd:$country ."
    private fun film(genre: String) = "?item wdt:P31 wd:Q11424 ; wdt:P136 wd:$genre ."
    private fun game(platform: String) = "?item wdt:P31 wd:Q7889 ; wdt:P400 wd:$platform ."

    val all: List<WikidataTemplate> = buildList {
        // ---- cities per country
        for (k in countries) if (k.q != "Q27") add(t("city.${slug(k.name)}", "Name a city in ${k.name}", "GEOGRAPHY",
            "?item wdt:P17 wd:${k.q} ; wdt:P1082 ?pop . FILTER(?pop >= ${k.cityPop}) ?item wdt:P31/wdt:P279* wd:Q515 ."))

        // ---- rivers / mountains / islands / lakes per country
        for (q in listOf("Q30", "Q183", "Q142", "Q159", "Q148", "Q668", "Q155", "Q145", "Q38", "Q29", "Q36", "Q16", "Q408"))
            add(t("river.${slug(c(q))}", "Name a river in ${c(q)}", "GEOGRAPHY", "?item wdt:P31 wd:Q4022 ; wdt:P17 wd:$q .", 5))
        for ((q, name) in listOf("Q39" to "Switzerland", "Q40" to "Austria", "Q38" to "Italy", "Q142" to "France",
            "Q30" to "the United States", "Q837" to "Nepal", "Q17" to "Japan", "Q16" to "Canada", "Q298" to "Chile", "Q414" to "Argentina"))
            add(t("mountain.${slug(name)}", "Name a mountain in $name", "GEOGRAPHY", "?item wdt:P31 wd:Q8502 ; wdt:P17 wd:$q .", 3))
        for ((q, name) in listOf("Q41" to "Greece", "Q38" to "Italy", "Q252" to "Indonesia", "Q928" to "the Philippines",
            "Q17" to "Japan", "Q145" to "the United Kingdom", "Q20" to "Norway", "Q34" to "Sweden", "Q16" to "Canada",
            "Q224" to "Croatia", "Q35" to "Denmark"))
            add(t("island.${slug(name)}", "Name an island of $name", "GEOGRAPHY", "?item wdt:P31 wd:Q23442 ; wdt:P17 wd:$q .", 5))
        for ((q, name) in listOf("Q33" to "Finland", "Q34" to "Sweden", "Q16" to "Canada", "Q39" to "Switzerland",
            "Q38" to "Italy", "Q183" to "Germany", "Q30" to "the United States", "Q159" to "Russia"))
            add(t("lake.${slug(name)}", "Name a lake in $name", "GEOGRAPHY", "?item wdt:P31 wd:Q23397 ; wdt:P17 wd:$q .", 5))
        for ((q, name) in listOf("Q46" to "Europe", "Q48" to "Asia", "Q49" to "North America"))
            add(t("countryin.${slug(name)}", "Name a country in $name", "GEOGRAPHY", "?item wdt:P31 wd:Q3624078 ; wdt:P30 wd:$q ."))
        for ((q, name) in listOf("Q183" to "Germany", "Q142" to "France", "Q145" to "the United Kingdom", "Q29" to "Spain", "Q38" to "Italy"))
            add(t("castle.${slug(name)}", "Name a castle in $name", "HISTORY", "?item wdt:P31 wd:Q23413 ; wdt:P17 wd:$q .", 3))
        for ((q, name) in listOf("Q38" to "Italy", "Q148" to "China", "Q29" to "Spain", "Q183" to "Germany", "Q142" to "France",
            "Q668" to "India", "Q96" to "Mexico", "Q145" to "the United Kingdom", "Q159" to "Russia", "Q794" to "Iran"))
            add(t("unesco.${slug(name)}", "Name a UNESCO World Heritage Site in $name", "HISTORY",
                "?item wdt:P1435 wd:Q9259 ; wdt:P17 wd:$q .", 2))
        for ((q, name) in listOf("Q30" to "the United States", "Q145" to "the United Kingdom", "Q183" to "Germany"))
            add(t("uni.${slug(name)}", "Name a university in $name", "MISC", "?item wdt:P31 wd:Q3918 ; wdt:P17 wd:$q .", 12))

        // ---- people by occupation
        for ((occ, label, min) in listOf(
            Triple("Q1028181", "painter", 25), Triple("Q36834", "composer", 25), Triple("Q169470", "physicist", 25),
            Triple("Q593644", "chemist", 20), Triple("Q170790", "mathematician", 25), Triple("Q4964182", "philosopher", 30),
            Triple("Q11631", "astronaut", 15), Triple("Q10873124", "chess player", 15), Triple("Q10833314", "tennis player", 30),
            Triple("Q3665646", "basketball player", 30), Triple("Q378622", "racing driver", 25), Triple("Q42973", "architect", 20),
            Triple("Q205375", "inventor", 20), Triple("Q49757", "poet", 40), Triple("Q6625963", "novelist", 40),
            Triple("Q2526255", "film director", 40), Triple("Q2252262", "rapper", 20), Triple("Q855091", "guitarist", 25),
            Triple("Q11063", "astronomer", 20), Triple("Q188094", "economist", 25), Triple("Q1281618", "sculptor", 20),
            Triple("Q33231", "photographer", 15), Triple("Q11900058", "explorer", 20), Triple("Q10841764", "Formula One driver", 15),
            Triple("Q864503", "biologist", 20), Triple("Q212980", "psychologist", 20),
            Triple("Q177220", "singer", 60), Triple("Q10800557", "film actor", 60), Triple("Q937857", "footballer", 60),
            Triple("Q11338576", "boxer", 20), Triple("Q245068", "comedian", 25), Triple("Q3501317", "fashion designer", 15),
            Triple("Q3499072", "chef", 10), Triple("Q1114448", "cartoonist", 15),
        )) add(t("occ.${slug(label)}", "Name a famous $label", "PEOPLE", person(occ), min))
        // ---- people by occupation × country
        for (q in listOf("Q155", "Q414", "Q183", "Q145", "Q29", "Q38", "Q142", "Q55", "Q45"))
            add(t("foot.${slug(c(q))}", "Name a footballer from ${c(q)}", "SPORT", personFrom("Q937857", q), 15))
        for (q in listOf("Q142", "Q38", "Q55", "Q29"))
            add(t("paint.${slug(c(q))}", "Name a painter from ${c(q)}", "CULTURE", personFrom("Q1028181", q), 10))
        for (q in listOf("Q145", "Q30", "Q142", "Q159", "Q183", "Q17"))
            add(t("writer.${slug(c(q))}", "Name a writer from ${c(q)}", "CULTURE", personFrom("Q36180", q), 25))
        for (q in listOf("Q183", "Q40", "Q38", "Q159", "Q142"))
            add(t("comp.${slug(c(q))}", "Name a composer from ${c(q)}", "CULTURE", personFrom("Q36834", q), 10))

        // ---- films
        for ((g, label, min) in listOf(
            Triple("Q471839", "science fiction film", 10), Triple("Q172980", "western", 8), Triple("Q842256", "musical film", 8),
            Triple("Q369747", "war film", 10), Triple("Q157443", "comedy film", 18), Triple("Q2484376", "thriller", 15),
            Triple("Q188473", "action film", 15), Triple("Q157394", "fantasy film", 10), Triple("Q959790", "crime film", 15),
            Triple("Q1054574", "romance film", 15), Triple("Q93204", "documentary film", 8), Triple("Q846544", "disaster film", 5),
            Triple("Q1535153", "superhero film", 8), Triple("Q1200678", "mystery film", 8), Triple("Q496523", "heist film", 5),
            Triple("Q1342372", "zombie film", 4), Triple("Q853630", "slasher film", 4), Triple("Q185867", "film noir", 5),
        )) add(t("film.${slug(label)}", "Name a $label", if (g in setOf("Q1342372", "Q853630")) "OCCULT" else "CULTURE", film(g), min))
        add(t("film.animated", "Name an animated film", "CULTURE", "?item wdt:P31 wd:Q202866 .", 15))
        for ((q, name) in listOf("Q7374" to "Alfred Hitchcock", "Q8877" to "Steven Spielberg", "Q41148" to "Martin Scorsese",
            "Q8006" to "Akira Kurosawa", "Q95008" to "John Carpenter", "Q56008" to "Tim Burton", "Q55424" to "David Cronenberg",
            "Q56005" to "Ridley Scott", "Q25089" to "Woody Allen", "Q43203" to "Clint Eastwood", "Q7546" to "Ingmar Bergman",
            "Q7371" to "Federico Fellini", "Q53001" to "Jean-Luc Godard", "Q56094" to "Francis Ford Coppola"))
            // matched by English label (safer than hand-typed QIDs); any film subclass counts
            add(t("dir.${slug(name)}", "Name a film directed by $name", "CULTURE",
                "?dir rdfs:label \"$name\"@en ; wdt:P31 wd:Q5 . ?item wdt:P57 ?dir ; wdt:P31/wdt:P279* wd:Q11424 ."))
        for ((aw, label) in listOf("Q102427" to "a Best Picture Oscar winner", "Q103916" to "a Best Actor Oscar winner",
            "Q103618" to "a Best Actress Oscar winner", "Q103360" to "a Best Director Oscar winner"))
            add(t("oscar.${aw.lowercase()}", "Name $label", "CULTURE",
                if (aw == "Q102427") "?item wdt:P166 wd:$aw ; wdt:P31/wdt:P279* wd:Q11424 ." else "?item wdt:P166 wd:$aw ; wdt:P31 wd:Q5 ."))

        // ---- video games
        for ((q, name) in listOf("Q172742" to "NES", "Q183259" to "Super Nintendo", "Q184839" to "Nintendo 64",
            "Q10677" to "PlayStation", "Q10680" to "PlayStation 2", "Q10683" to "PlayStation 3", "Q5014725" to "PlayStation 4",
            "Q186437" to "Game Boy", "Q188642" to "Game Boy Advance", "Q182172" to "GameCube", "Q8079" to "Wii", "Q56942" to "Wii U",
            "Q19610114" to "Nintendo Switch", "Q170323" to "Nintendo DS", "Q203597" to "Nintendo 3DS", "Q170325" to "PSP",
            "Q10676" to "Sega Mega Drive / Genesis", "Q184198" to "Dreamcast", "Q132020" to "Xbox", "Q48263" to "Xbox 360",
            "Q206261" to "Atari 2600", "Q99775" to "Commodore 64", "Q47604" to "MS-DOS"))
            add(t("game.${slug(name)}", "Name a video game released on $name", "CULTURE", game(q), 4))
        for ((g, label) in listOf("Q828322" to "platform game", "Q185029" to "first-person shooter", "Q744038" to "role-playing video game"))
            add(t("gamegenre.${slug(label)}", "Name a $label", "CULTURE", "?item wdt:P31 wd:Q7889 ; wdt:P136 wd:$g .", 10))

        // ---- music
        for ((g, label, min) in listOf(
            Triple("Q3071", "punk rock", 8), Triple("Q11365", "grunge", 5), Triple("Q132438", "black metal", 5),
            Triple("Q483251", "death metal", 5), Triple("Q485395", "gothic rock", 5), Triple("Q211756", "post-punk", 5),
            Triple("Q187760", "new wave", 8), Triple("Q1298934", "synth-pop", 8), Triple("Q49451", "progressive rock", 8),
            Triple("Q186472", "emo", 4), Triple("Q213665", "K-pop", 8), Triple("Q164444", "funk", 5), Triple("Q58339", "disco", 5),
        )) add(t("band.${slug(label)}", "Name a $label band", "CULTURE",
            "?item wdt:P136 wd:$g . ?item wdt:P31/wdt:P279* wd:Q215380 .", min))
        for ((g, label) in listOf("Q8341" to "jazz musician", "Q9759" to "blues musician", "Q83440" to "country music singer", "Q9794" to "reggae artist"))
            add(t("artist.${slug(label)}", "Name a $label", "CULTURE", "?item wdt:P31 wd:Q5 ; wdt:P136 wd:$g .", 12))

        // ---- awards
        for ((q, label) in listOf("Q38104" to "Physics", "Q44585" to "Chemistry", "Q37922" to "Literature",
            "Q35637" to "Peace", "Q80061" to "Physiology or Medicine", "Q47170" to "Economics"))
            add(t("nobel.${slug(label)}", "Name a winner of the Nobel Prize in $label", "SCIENCE", "?item wdt:P166 wd:$q ."))

        // ---- sport leagues
        for ((q, label) in listOf("Q155223" to "NBA", "Q1215884" to "NFL", "Q1163715" to "MLB", "Q1215892" to "NHL",
            "Q9448" to "Premier League", "Q82595" to "Bundesliga", "Q324867" to "La Liga", "Q15804" to "Serie A", "Q13394" to "Ligue 1"))
            add(t("league.${slug(label)}", "Name a team that has played in the $label", "SPORT",
                "?item wdt:P118 wd:$q . FILTER EXISTS { VALUES ?k { wd:Q12973014 wd:Q847017 } ?item wdt:P31/wdt:P279* ?k . }"))

        // ---- breeds, varieties, misc classes
        add(t("cat", "Name a cat breed", "NATURE", "?item wdt:P31 wd:Q43577 ."))
        add(t("horse", "Name a horse breed", "NATURE", "?item wdt:P31 wd:Q1160573 .", 3))
        for ((q, name) in listOf("Q183" to "Germany", "Q145" to "the United Kingdom", "Q142" to "France"))
            add(t("dogfrom.${slug(name)}", "Name a dog breed from $name", "NATURE", "?item wdt:P31 wd:Q39367 ; wdt:P495 wd:$q ."))
        for ((q, name) in listOf("Q142" to "France", "Q38" to "Italy"))
            add(t("cheesefrom.${slug(name)}", "Name a cheese from $name", "FOOD", "?item wdt:P31/wdt:P279* wd:Q10943 ; wdt:P495 wd:$q ."))
        add(t("mineral", "Name a mineral", "SCIENCE", "?item wdt:P31 wd:Q12089225 .", 5))
        add(t("typeface", "Name a typeface", "CODE", "?item wdt:P31/wdt:P279* wd:Q17451 .", 3))
        add(t("crypto", "Name a cryptocurrency", "CODE", "?item wdt:P31 wd:Q13479982 .", 3))
        add(t("social", "Name a social network", "CODE", "?item wdt:P31 wd:Q3220391 .", 5))
        add(t("gamegenre", "Name a video game genre", "CULTURE", "?item wdt:P31 wd:Q659563 .", 5))
        add(t("cardgame", "Name a card game", "CULTURE", "?item wdt:P31 wd:Q142714 .", 3))
        add(t("exoplanet", "Name an exoplanet", "SCIENCE", "?item wdt:P31 wd:Q44559 .", 5))
        add(t("asteroid", "Name an asteroid", "SCIENCE", "?item wdt:P31 wd:Q3863 .", 15))
        add(t("comet", "Name a comet", "SCIENCE", "?item wdt:P31 wd:Q3559 .", 5))
    }
}
