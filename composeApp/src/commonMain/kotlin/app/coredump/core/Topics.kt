package app.coredump.core

/**
 * Categories players can pick, and two per-list flags:
 *  - niche:  needs specialist knowledge (players from one country, sub-genres, obscure species…); off by default.
 *  - people: answers are people, so a surname alone is accepted.
 * Classification runs on every prompt from every source (packs, caches, downloads), so old data gets the new
 * categories without being refetched.
 */
object Topics {
    class Info(val key: String, val label: String, val icon: String)

    val all: List<Info> = listOf(
        Info("MOVIES", "Movies", "🎬"),
        Info("TV", "TV shows", "📺"),
        Info("MUSIC", "Music", "🎵"),
        Info("GAMING", "Gaming", "🎮"),
        Info("TECH", "Tech & internet", "💻"),
        Info("POP CULTURE", "Pop culture", "✨"),
        Info("BOOKS & ART", "Books & art", "🎨"),
        Info("FOOD & DRINK", "Food & drink", "🍕"),
        Info("GEOGRAPHY", "Geography", "🌍"),
        Info("LANDMARKS", "Landmarks", "🗽"),
        Info("HISTORY", "History", "🏛"),
        Info("PEOPLE", "Famous people", "👤"),
        Info("SPORT", "Sport", "⚽"),
        Info("ANIMALS", "Animals", "🐾"),
        Info("NATURE", "Nature", "🌿"),
        Info("SCIENCE", "Science & space", "🔭"),
        Info("BODY", "Body & health", "💪"),
        Info("MYTH", "Myth & religion", "⚡"),
        Info("HORROR", "Horror & occult", "💀"),
        Info("EVERYDAY", "Everyday life", "🏠"),
        Info("BRANDS", "Brands & business", "🏷"),
        Info("WORDS", "Words & language", "🔤"),
    )
    val keys: Set<String> = all.map { it.key }.toSet()
    private val byKey = all.associateBy { it.key }
    fun label(key: String): String = byKey[key]?.label ?: key.lowercase().replaceFirstChar { it.uppercase() }
    fun icon(key: String): String = byKey[key]?.icon ?: "•"

    /** Lists whose category the keyword rules would get wrong. */
    private val byId: Map<String, String> = mapOf(
        "cul.instrument" to "MUSIC", "cul.greekgod" to "MYTH", "cul.boardgame" to "EVERYDAY", "cul.color" to "EVERYDAY",
        "cul.language" to "WORDS", "cul.norse" to "MYTH", "cul.egypt" to "MYTH", "cul.painter" to "BOOKS & ART",
        "cul.composer" to "MUSIC", "cul.musical" to "BOOKS & ART", "cul.novel" to "BOOKS & ART", "cul.fictcat" to "POP CULTURE",
        "cul.superhero" to "POP CULTURE", "cul.cartoon" to "POP CULTURE", "cul.vgchar" to "GAMING", "cul.fairytale" to "BOOKS & ART",
        "cul.dance" to "BOOKS & ART", "cul.romangod" to "MYTH", "cul.genre" to "MUSIC", "cul.band" to "MUSIC", "cul.singer" to "MUSIC",
        "code.lang" to "TECH", "code.ext" to "TECH", "code.key" to "TECH", "tech.app" to "TECH", "tech.console" to "GAMING",
        "tech.brand" to "BRANDS", "tech.computer" to "TECH",
        "misc.greek" to "WORDS", "misc.zodiac" to "MYTH", "misc.gem" to "NATURE", "misc.car" to "BRANDS", "misc.font" to "WORDS",
        "misc.brand" to "BRANDS", "misc.toy" to "POP CULTURE", "misc.company" to "BRANDS", "misc.ship" to "HISTORY",
        "misc.weapon" to "HISTORY", "misc.metal" to "SCIENCE", "misc.bodypart" to "BODY", "misc.holiday" to "EVERYDAY",
        "misc.emotion" to "WORDS", "misc.school" to "EVERYDAY", "misc.room" to "EVERYDAY",
        "occ.villain" to "MOVIES", "occ.film" to "MOVIES", "occ.tv" to "TV", "occ.horrorgame" to "GAMING", "occ.author" to "BOOKS & ART",
        "occ.poison" to "NATURE", "occ.creature" to "HORROR", "occ.phobia" to "WORDS",
        "sci.disease" to "BODY", "sci.bone" to "BODY", "sci.organ" to "BODY", "sci.muscle" to "BODY", "sci.dino" to "ANIMALS",
        "sci.ology" to "WORDS", "sci.cloud" to "NATURE", "sci.rock" to "NATURE",
        "sport.yoga" to "SPORT", "his.invention" to "HISTORY", "his.philosopher" to "PEOPLE", "his.explorer" to "HISTORY",
        "geo.landmark" to "LANDMARKS", "geo.park" to "LANDMARKS", "nat.disaster" to "NATURE",
        "wd.occupation" to "EVERYDAY", "wd.discipline" to "SCIENCE", "wd.language" to "WORDS", "wd.genre" to "MUSIC",
        "wd.horrorfilm" to "MOVIES", "wd.metal" to "MUSIC", "wd.club" to "SPORT", "wd.website" to "TECH",
        "wd.magazine" to "POP CULTURE", "wd.newspaper" to "POP CULTURE", "wd.currency" to "EVERYDAY",
        "wg.brand" to "BRANDS", "wg.tool" to "EVERYDAY", "wg.vehicle" to "EVERYDAY", "wg.clothing" to "EVERYDAY",
        "wg.furniture" to "EVERYDAY", "wg.holiday" to "EVERYDAY", "wg.ideology" to "HISTORY", "wg.museum" to "LANDMARKS",
        "wg.stadium" to "LANDMARKS", "wg.legend" to "MYTH", "wg.deity" to "MYTH", "wg.religion" to "MYTH", "wg.disease" to "BODY",
        "wg.vgchar" to "GAMING", "wg.artmove" to "BOOKS & ART", "wg.dance" to "BOOKS & ART", "wg.martial" to "SPORT",
        "wg.pope" to "MYTH", "wf.cardgame" to "EVERYDAY", "wf.mineral" to "NATURE", "wf.crypto" to "TECH", "wf.social" to "TECH",
        "wp.horrorauthors" to "BOOKS & ART", "wp.cryptids" to "HORROR", "wp.games" to "GAMING", "wx.hi.oldcountry" to "HISTORY",
    )

    private class Rule(val topic: String, val words: List<String>)

    /** First match wins; matched as whole words / phrases in the lower-cased prompt. */
    private val rules: List<Rule> = listOf(
        Rule("GAMING", listOf("video game", "videogame", "console", "first-person shooter", "role-playing", "game genre",
            "game franchise", "gamer", "esports", "pokémon", "game character")),
        Rule("TV", listOf("tv", "television", "sitcom", "talk show", "game show", "reality show", "reality tv", "presenter",
            "anime", "soap opera", "tv channel", "cartoon series", "streaming")),
        Rule("MOVIES", listOf("film", "movie", "oscar", "cinema", "actor", "actress", "film director", "screenwriter",
            "film producer", "box office", "blockbuster")),
        Rule("HORROR", listOf("horror", "slasher", "zombie", "occult", "cryptid", "demon", "tarot", "divination", "haunted",
            "underworld", "ghost", "vampire", "witch")),
        Rule("MUSIC", listOf("band", "singer", "song", "album", "music", "musician", "composer", "rapper", "guitarist", "dj",
            "jazz", "blues", "reggae", "pianist", "violinist", "drummer", "bassist", "conductor", "opera", "instrument",
            "k-pop", "punk", "rock band", "singer-songwriter", "hip hop", "orchestra", "songwriter", "pop star", "boy band", "girl group")),
        Rule("TECH", listOf("programming", "computer", "keyboard", "file extension", "app", "apps", "website", "browser",
            "operating system", "software", "social network", "cryptocurrency", "internet", "smartphone", "gadget",
            "tech", "robot", "ai", "aircraft", "online service", "meme", "emoji", "coding", "search engine")),
        Rule("SPORT", listOf("sport", "sports", "footballer", "football", "soccer", "player", "athlete", "olympic", "team",
            "league", "nfl", "nba", "nhl", "mlb", "serie a", "la liga", "premier league", "bundesliga", "cricketer", "golfer",
            "swimmer", "cyclist", "boxer", "wrestler", "martial art", "skier", "skater", "gymnast", "jockey", "racing driver",
            "formula one", "ball game", "sprinter", "fencer", "badminton", "table tennis", "handball", "skateboarder", "mountaineer", "chess player", "coach", "tennis", "stadium", "club")),
        Rule("BRANDS", listOf("brand", "company", "companies", "airline", "car manufacturer", "carmaker", "car model",
            "restaurant chain", "fast food chain", "entrepreneur", "ceo", "logo", "retailer", "supermarket", "store chain")),
        Rule("POP CULTURE", listOf("superhero", "cartoon", "youtuber", "influencer", "model", "celebrity", "fictional",
            "comic", "manga", "toy", "character", "magazine", "newspaper", "villain", "detective", "robot", "pirate",
            "talk show host", "fashion designer", "puppet")),
        Rule("BOOKS & ART", listOf("book", "novel", "novelist", "writer", "author", "poet", "painting", "painter", "sculptor",
            "art movement", "fairy tale", "nursery rhyme", "musical", "theatre", "theater", "ballet", "photographer",
            "cartoonist", "architect", "artist", "play by", "literature", "dance")),
        Rule("FOOD & DRINK", listOf("dish", "cheese", "bread", "cake", "pastry", "soup", "sauce", "salad", "sausage", "fruit",
            "vegetable", "spice", "herb", "dessert", "candy", "sweet", "snack", "cocktail", "drink", "beer", "wine", "coffee",
            "tea", "pasta", "pizza", "nut", "berry", "ice cream", "sandwich", "cuisine", "breakfast", "grain", "condiment",
            "food", "chef", "cereal", "chocolate", "biscuit", "cookie", "spirit", "liqueur")),
        Rule("ANIMALS", listOf("animal", "animals", "dog", "cat breed", "horse", "bird", "fish", "mammal", "reptile", "snake",
            "insect", "amphibian", "primate", "whale", "dolphin", "shark", "bear", "owl", "bee", "bat", "species", "deer",
            "duck", "goose", "frog", "toad", "turtle", "tortoise", "penguin", "crocodile", "alligator", "seal", "kangaroo",
            "ape", "monkey", "parrot", "rodent", "wolf", "fox", "raptor", "bird of prey", "sea creature", "dinosaur",
            "butterfly", "spider", "pet", "farm animal", "breed", "octopus", "antelope", "weasel", "salmon", "gull", "heron",
            "woodpecker", "hummingbird", "crow", "pigeon")),
        Rule("BODY", listOf("body", "bone", "muscle", "organ", "disease", "illness", "medicine", "vitamin", "blood", "health",
            "symptom", "drug", "epidemic", "pandemic", "hormone")),
        Rule("MYTH", listOf("god", "goddess", "deity", "myth", "mythology", "mythological", "legend", "legendary", "religion",
            "pope", "saint", "titan", "folklore", "zodiac", "angel", "prophet")),
        Rule("SCIENCE", listOf("element", "planet", "moon", "constellation", "physicist", "chemist", "mathematician",
            "scientist", "particle", "unit of measurement", "shape", "geological", "compound", "spacecraft", "space probe",
            "rocket", "astronaut", "astronomer", "biologist", "nobel prize in physics", "inventor", "galaxy", "star",
            "science", "scientific", "metal", "physics", "chemistry", "outer space")),
        Rule("NATURE", listOf("flower", "tree", "mushroom", "fungus", "plant", "cactus", "natural disaster", "weather", "cloud",
            "mineral", "rock", "gemstone", "crystal")),
        Rule("LANDMARKS", listOf("landmark", "monument", "unesco", "wonder", "skyscraper", "bridge", "tower", "castle",
            "palace", "cathedral", "church", "museum", "statue", "square", "street", "park", "zoo", "theme park", "airport",
            "building", "temple", "mosque")),
        Rule("HISTORY", listOf("president", "emperor", "monarch", "king", "queen", "war", "battle", "empire", "civilisation",
            "civilization", "revolution", "explorer", "historical", "ancient", "dynasty", "prime minister", "ideology",
            "dictator", "pharaoh", "invention", "weapon", "ship", "shipwreck", "historian", "military")),
        Rule("GEOGRAPHY", listOf("country", "countries", "city", "cities", "capital", "state", "states", "river", "lake",
            "island", "mountain", "desert", "sea", "ocean", "waterfall", "national park", "peninsula", "bay", "gulf", "cave",
            "continent", "volcano", "range", "province", "region", "border", "flag", "landlocked", "beach", "canyon", "glacier", "strait", "archipelago")),
        Rule("WORDS", listOf("language", "letter", "alphabet", "word", "words", "-ology", "font", "typeface", "phobia",
            "emotion", "idiom", "name")),
        Rule("PEOPLE", listOf("famous", "philosopher", "economist", "psychologist", "politician", "nobel", "winner of",
            "person", "people")),
        Rule("EVERYDAY", listOf("job", "occupation", "profession", "furniture", "clothing", "clothes", "shoe", "footwear",
            "tool", "room", "vehicle", "jewellery", "jewelry", "appliance", "school subject", "holiday", "festival", "hobby",
            "fabric", "textile", "colour", "color", "board game", "kitchen", "utensil", "cooking method", "currency", "household")),
    )

    private val legacy = mapOf(
        "CULTURE" to "POP CULTURE", "CODE" to "TECH", "OCCULT" to "HORROR", "MISC" to "EVERYDAY", "PEOPLE" to "PEOPLE",
        "NATURE" to "ANIMALS", "FOOD" to "FOOD & DRINK", "GEOGRAPHY" to "GEOGRAPHY", "HISTORY" to "HISTORY",
        "SCIENCE" to "SCIENCE", "SPORT" to "SPORT",
    )

    private fun words(text: String): String =
        " " + text.lowercase().map { if (it.isLetterOrDigit() || it == '-' || it == 'é') it else ' ' }.joinToString("") + " "

    private fun has(w: String, phrase: String): Boolean = w.contains(" $phrase ") || w.contains(" ${phrase}s ")

    /** Collected Wikidata lists ("wx.<group>.<name>"): the group says the category. */
    private val poolGroup = mapOf(
        "tv" to "TV", "mv" to "MOVIES", "pc" to "POP CULTURE", "mu" to "MUSIC", "gm" to "GAMING", "tc" to "TECH",
        "bk" to "BOOKS & ART", "fd" to "FOOD & DRINK", "ge" to "GEOGRAPHY", "lm" to "LANDMARKS", "hi" to "HISTORY",
        "sp" to "SPORT", "an2" to "ANIMALS", "na" to "NATURE", "sc" to "SCIENCE", "bd" to "BODY", "my" to "MYTH",
        "ev" to "EVERYDAY", "br" to "BRANDS", "ho" to "HORROR", "wo" to "WORDS",
    )

    /** Category of a list. [raw] is the category the source gave it (old names are mapped). */
    fun classify(id: String, text: String, raw: String): String {
        byId[id]?.let { return it }
        // lists of people by job stay with people (or their own field: music, film, sport), never in food, science…
        if (isJobList(id)) return jobTopic(text)
        if (id.startsWith("wx.")) poolGroup[id.split('.').getOrElse(1) { "" }]?.let { return it }
        if (id.startsWith("wf.film.")) return "MOVIES"
        if (id.startsWith("wf.gamegenre")) return "GAMING"
        // a source that already uses the new categories (new packs) is trusted; old names go through the rules
        val r = raw.uppercase()
        if (r in keys && r !in legacy.keys) return r
        val w = words(text.substringBefore("(").removePrefix("Name ").removePrefix("name "))
        for (r in rules) if (r.words.any { has(w, it) }) return r.topic
        return legacy[raw.uppercase()] ?: "EVERYDAY"
    }

    private val nicheIds = setOf(
        "occ.divination", "occ.tarot", "occ.demon", "occ.underworld", "occ.phobia", "sci.muscle", "sci.period", "sci.particle",
        "sci.ology", "sport.yoga", "misc.font", "code.ext", "misc.greek", "wd.metal", "wd.fname", "wd.mname", "wd.magazine",
        "wf.cardgame", "wf.mineral", "wf.gamegenre",
    )
    private val nichePrefixes = listOf(
        "wf.foot.", "wf.comp.", "wf.paint.", "wf.writer.", "wf.unesco.", "wf.river.", "wf.lake.", "wf.mountain.", "wf.island.",
        "wf.dogfrom.", "wf.cheesefrom.", "wf.uni.", "wf.castle.", "wf.band.", "wf.artist.", "wf.gamegenre.", "wf.nobel.",
        "wf.film.heist", "wf.film.slasher", "wf.film.zombie", "wf.film.filmnoir", "wf.film.documentary", "wf.film.disaster",
        "wf.film.musical", "wf.film.mystery",
        "wf.occ.astronomer", "wf.occ.biologist", "wf.occ.economist", "wf.occ.psychologist", "wf.occ.photographer",
        "wf.occ.sculptor", "wf.occ.cartoonist", "wf.occ.fashiondesigner", "wf.occ.chemist",
        "wx.an.bat", "wx.an.bovid", "wx.an.mustelid", "wx.an.monkey", "wx.an.woodpecker", "wx.an.hummingbird", "wx.an.salmonid",
        "wx.an.gull", "wx.an.heron", "wx.pl.peninsula", "wx.pl.bay", "wx.pl.cave", "wx.th.rock", "wx.th.ballet", "wx.th.radio",
        "wx.job.", "wx.nx.", "wx.sc.by.", "wx.bk.by.philosopher.", "wx.bk.by.architect.", "wx.bk.by.poet.", "wx.wo.end.",
    )
    private val broadJobs = setOf(
        "wx.job.singersongwriter", "wx.job.voiceactor", "wx.job.entrepreneur", "wx.job.CEO", "wx.job.monarch",
        "wx.job.musician", "wx.job.novelist", "wx.job.painter", "wx.job.televisionactor", "wx.job.figureskater", "wx.job.gymnast",
    )

    private val placeWord = Regex("""\b(from|of|in) (the )?[A-Z]""")

    /** Hyper-specific list (needs specialist knowledge). */
    fun isNiche(id: String, text: String): Boolean {
        // qualifier lists are hand-picked for a general audience ("a mountain in Europe higher than 4,000 m" is not
        // niche although it names a place); only a few need specialist knowledge
        if (isSpecific(id)) return id in nicheSpecific
        if (id in nicheIds) return true
        if (id in broadJobs) return false
        if (nichePrefixes.any { id.startsWith(it) }) return true
        val t = text.lowercase()
        return placeWord.containsMatchIn(text.substringAfter("Name ")) &&
            (t.contains("footballer from") || t.contains("writer from") || t.contains("composer from") ||
                t.contains("painter from") || t.contains("heritage site") || t.contains("island of") ||
                t.contains("lake in") || t.contains("river in") || t.contains("mountain in") || t.contains("castle in"))
    }

    private val peopleWords = listOf(
        "president", "emperor", "monarch", "king", "queen", "explorer", "philosopher", "painter", "composer", "scientist",
        "physicist", "mathematician", "athlete", "singer", "rapper", "actor", "actress", "director", "writer", "author",
        "novelist", "poet", "footballer", "player", "driver", "astronaut", "inventor", "architect", "chemist", "guitarist",
        "dj", "model", "youtuber", "presenter", "comedian", "designer", "photographer", "sculptor", "economist",
        "psychologist", "astronomer", "biologist", "boxer", "wrestler", "swimmer", "cyclist", "cricketer", "golfer", "pope",
        "dancer", "musician", "skier", "skater", "gymnast", "jockey", "mountaineer", "pianist", "violinist", "conductor",
        "drummer", "bassist", "songwriter", "producer", "journalist", "historian", "physician", "professor", "entrepreneur",
        "ceo", "politician", "pirate", "talk show host", "host", "dictator", "prime minister", "pharaoh", "chef",
        "skateboarder", "fighter", "officer", "cartoonist", "scientists", "celebrity", "influencer", "singer-songwriter",
        "detective", "villain", "queen or empress",
    )
    private val notPeople = listOf("film", "movie", "band", "group", "team", "club", "character", "song", "album", "game",
        "car model", "aircraft model", "car", "aircraft", "phone", "smartphone", "smartphone model")

    /** Answers are real people (surname-only answers allowed). */
    fun isPeople(id: String, text: String, check: String?): Boolean {
        if (check != null && (check.contains("wd:Q5 ") || check.contains("wd:Q5;"))) return true
        // qualifier lists say exactly what they hold ("a Stephen King book" is not about kings)
        if (isSpecific(id)) return false
        if (id.startsWith("wf.oscar.q102427")) return false
        val w = words(text.substringBefore("("))
        if (notPeople.any { has(w, it) } && !has(w, "film director") && !has(w, "film actor") && !has(w, "film producer")) return false
        return peopleWords.any { has(w, it) } || has(w, "winner of the nobel prize")
    }

    // ---------------- qualifier lists ("wx.<group>.s.<name>", see data/SpecificTemplates)

    /** A qualifier list: a category with one condition ("a river longer than 1,000 km"). */
    fun isSpecific(id: String): Boolean = id.startsWith("wx.") && id.split('.').getOrNull(2) == "s"

    private val nicheSpecific = setOf("wx.an2.s.spider", "wx.an2.s.butterfly", "wx.hi.s.pyramid", "wx.sc.s.comet")

    // ---------------- people lists by job (the "Name a famous <nationality> <job>" kind)

    /** Ids of the generated "famous <job>" lists (with or without a nationality). */
    private val jobListIds = setOf(
        "wg.actor", "wg.writer", "wg.politician", "wg.model", "wg.youtuber", "wg.dancer", "wg.cricketer", "wg.swimmer",
        "wg.cyclist", "wg.hockey", "wg.baseball", "wg.nfl", "wg.wrestler", "wg.presenter", "wg.dj", "wx.tc.programmer",
    )
    private val jobListPrefixes = listOf("wx.job.", "wx.nx.", "wf.occ.", "wf.foot.", "wf.paint.", "wf.writer.", "wf.comp.")

    /** A generated list of people by occupation (the kind that used to show up in every category). */
    fun isJobList(id: String): Boolean =
        id in jobListIds || jobListPrefixes.any { id.startsWith(it) } || (id.startsWith("wx.") && id.split('.').getOrNull(2) == "by")

    private val sportJobs = listOf("player", "footballer", "athlete", "swimmer", "cyclist", "boxer", "wrestler", "cricketer",
        "skier", "skater", "gymnast", "jockey", "racing driver", "formula one", "sprinter", "fencer", "mountaineer", "golfer")
    private val musicJobs = listOf("singer", "rapper", "musician", "composer", "guitarist", "pianist", "violinist", "drummer",
        "bassist", "conductor", "songwriter", "dj", "opera singer", "singer-songwriter")
    private val filmJobs = listOf("actor", "actress", "film director", "film producer", "screenwriter", "voice actor",
        "stage actor", "television actor")

    /** Category of a people-by-job list: sport, music or film people stay there; everyone else is "Famous people". */
    fun jobTopic(text: String): String {
        val w = words(text.substringBefore("("))
        return when {
            sportJobs.any { has(w, it) } -> "SPORT"
            musicJobs.any { has(w, it) } -> "MUSIC"
            filmJobs.any { has(w, it) } -> "MOVIES"
            else -> "PEOPLE"
        }
    }

    /** Countries most players know plenty of names from (country codes of the nationality lists). */
    private val bigCountries = setOf("us", "uk", "fr", "de", "it", "es", "jp")
    /** Jobs whose famous members are known well beyond their field. */
    private val wideJobs = setOf("actor", "singer", "filmdirector", "tennisplayer", "racingdriver", "rapper")
    /** Jobs that make a nationality list a deep cut ("a famous Swiss architect"). */
    private val narrowJobs = setOf("chef", "model", "fashiondesigner", "architect", "chessplayer", "mathematician", "philosopher")
    private val fromPlace = Regex("""\bfrom (the )?[A-Z]""")

    /**
     * How specific a people list is, 0..1: 0 = best-known, global ("Name a famous actor or actress"); 0.2 = global but
     * a narrower job ("a famous drummer"); 0.5 = by nationality, a big country and a broad job ("a famous French
     * actor"); 0.75 = other nationality lists; 0.9 = nationality and a narrow job ("a famous Swiss architect").
     * Compared with the player's "common ↔ niche" setting ([GameConfig.peopleNiche]) when prompts are picked.
     */
    fun peopleLevel(id: String, text: String): Float {
        val p = id.split('.')
        if (p.size == 5 && p[0] == "wx" && p[2] == "by") {
            val job = p[3]
            val cc = p[4]
            return when {
                job in narrowJobs -> 0.9f
                cc in bigCountries && job in wideJobs -> 0.5f
                else -> 0.75f
            }
        }
        if (id.startsWith("wf.foot.") || id.startsWith("wf.paint.") || id.startsWith("wf.writer.") || id.startsWith("wf.comp.")) return 0.5f
        if (id.startsWith("wx.job.") || id.startsWith("wx.nx.") || id == "wx.tc.programmer") return 0.2f
        if (id.startsWith("wf.occ.") && isNiche(id, text)) return 0.2f
        // other sources: "Name a footballer from Brazil"
        if (fromPlace.containsMatchIn(text.substringAfter("Name "))) return 0.5f
        return 0f
    }
}
