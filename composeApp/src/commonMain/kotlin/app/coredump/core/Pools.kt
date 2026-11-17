package app.coredump.core

/**
 * Merged pools for MELTDOWN: several lists of one kind joined into one big category ("Name an animal" = mammals +
 * birds + fish + insects …), so a round has hundreds of right answers and nobody runs the list dry.
 *
 * Joining keeps each list's own order of familiarity: an answer's place is its position in its own list as a share
 * of that list's length (the 3rd of 100 and the 6th of 200 count as equally familiar), the joined list is sorted by
 * that share and then ranked into tiers again ([Tiering.build]). The same answer in two lists counts once, at its
 * most familiar place, with the aliases of both.
 */
object Pools {
    /** One merged pool: [members] are list ids, or id prefixes when they end with "." (a whole family of lists). */
    class Group(val id: String, val text: String, val topic: String, val members: List<String>)

    /** A pool is only offered with at least this many answers (count, after merging). */
    const val MIN_SIZE = 150

    val groups: List<Group> = listOf(
        Group("mix.animal", "Name an animal", "ANIMALS", listOf("nat.mammal", "nat.bird", "nat.fish", "nat.insect", "nat.reptile",
            "nat.snake", "nat.marine", "nat.primate", "nat.amphibian", "nat.farm", "nat.pet", "nat.african", "nat.raptor")),
        Group("mix.food", "Name a food or dish", "FOOD & DRINK", listOf("food.fruit", "food.veg", "food.cheese", "food.pasta",
            "food.dessert", "food.candy", "food.soup", "food.breakfast", "food.nut", "food.berry", "food.sandwich", "food.italian",
            "food.mexican", "food.chinese", "food.indian", "food.french", "food.snack", "food.cake", "food.grain", "wf.cheesefrom.")),
        Group("mix.drink", "Name a drink", "FOOD & DRINK", listOf("food.cocktail", "food.coffee", "food.drink", "food.alcohol")),
        Group("mix.city", "Name a city", "GEOGRAPHY", listOf("geo.capital", "geo.uscity", "geo.europecity", "geo.uscap", "gk.eucap",
            "gk.asiacap", "gk.africap", "gk.amcap", "gk.olympic", "wf.city.")),
        Group("mix.water", "Name a river, lake or sea", "GEOGRAPHY", listOf("geo.river", "gk.sea", "wf.river.", "wf.lake.")),
        Group("mix.peak", "Name a mountain or mountain range", "GEOGRAPHY", listOf("geo.mountain", "gk.range", "wf.mountain.")),
        Group("mix.island", "Name an island", "GEOGRAPHY", listOf("geo.island", "wf.island.")),
        Group("mix.myth", "Name a god, demon or mythical creature", "MYTH", listOf("cul.greekgod", "cul.norse", "cul.egypt",
            "cul.romangod", "occ.demon", "occ.creature", "occ.cryptid")),
        Group("mix.fiction", "Name a fictional character", "POP CULTURE", listOf("cul.fictcat", "cul.superhero", "cul.cartoon",
            "cul.vgchar", "mov.villain", "occ.villain", "pop.detective", "pop.robot")),
        Group("mix.scientist", "Name a famous scientist", "SCIENCE", listOf("sci.physicist", "sci.mathematician", "sci.scientist",
            "wf.occ.physicist", "wf.occ.chemist", "wf.occ.mathematician", "wf.occ.astronomer", "wf.occ.biologist", "wf.nobel.physics")),
        Group("mix.painter", "Name a painter or sculptor", "BOOKS & ART", listOf("cul.painter", "wf.occ.painter", "wf.occ.sculptor", "wf.paint.")),
        Group("mix.composer", "Name a composer", "MUSIC", listOf("cul.composer", "wf.occ.composer", "wf.comp.")),
        Group("mix.music", "Name a singer, musician or band", "MUSIC", listOf("cul.band", "cul.singer", "wf.band.", "wf.artist.",
            "wf.occ.singer", "wf.occ.rapper", "wf.occ.guitarist")),
        Group("mix.film", "Name a film", "MOVIES", listOf("occ.film", "wf.film.", "wf.oscar.q102427")),
        Group("mix.videogame", "Name a video game", "GAMING", listOf("gk.mobilegame", "occ.horrorgame", "wf.gamegenre.")),
        Group("mix.tv", "Name a TV show", "TV", listOf("tv.reality", "tv.gameshow", "occ.tv")),
        Group("mix.sport", "Name a sport or game", "SPORT", listOf("sport.sport", "sport.olympicsport", "sport.team", "sport.martial",
            "cul.boardgame", "gk.cardgame", "wf.cardgame")),
        Group("mix.athlete", "Name a famous athlete", "SPORT", listOf("sport.athlete", "wf.occ.tennisplayer", "wf.occ.basketballplayer",
            "wf.occ.racingdriver", "wf.occ.formulaonedriver", "wf.occ.boxer", "wf.occ.footballer", "wf.foot.")),
        Group("mix.writer", "Name a writer or poet", "BOOKS & ART", listOf("occ.author", "wf.writer.", "wf.occ.novelist", "wf.occ.poet")),
        Group("mix.ruler", "Name a king, queen, emperor or president", "HISTORY", listOf("his.president", "his.emperor", "his.england", "gk.queen")),
        Group("mix.team", "Name a sports team", "SPORT", listOf("wf.league.")),
        Group("mix.brand", "Name a brand", "BRANDS", listOf("misc.car", "misc.brand", "misc.company", "food.fastfood", "tech.brand")),
        Group("mix.home", "Name something you find in a home", "EVERYDAY", listOf("misc.furniture", "misc.appliance", "food.utensil",
            "misc.tool", "misc.room")),
        Group("mix.wear", "Name something you wear", "EVERYDAY", listOf("misc.clothing", "misc.shoe", "misc.jewellery")),
        Group("mix.plant", "Name a plant, flower or tree", "NATURE", listOf("nat.flower", "nat.tree", "occ.poison")),
        Group("mix.breed", "Name a dog, cat or horse breed", "ANIMALS", listOf("nat.dog", "wf.cat", "wf.horse", "wf.dogfrom.")),
        Group("mix.sight", "Name a famous landmark or sight", "LANDMARKS", listOf("geo.landmark", "geo.park", "geo.waterfall",
            "wf.unesco.", "wf.castle.", "gk.wonder")),
        Group("mix.rock", "Name a rock, mineral, gem or metal", "SCIENCE", listOf("sci.rock", "misc.gem", "wf.mineral", "misc.metal")),
        Group("mix.conflict", "Name a war, battle or revolution", "HISTORY", listOf("his.war", "gk.battle", "gk.revolution")),
        Group("mix.body", "Name a part of the human body", "BODY", listOf("misc.bodypart", "sci.bone", "sci.organ", "sci.muscle")),
        Group("mix.space", "Name something in space", "SCIENCE", listOf("sci.moon", "sci.constellation", "gk.solar")),
    )

    private fun matches(member: String, id: String) = if (member.endsWith(".")) id.startsWith(member) else id == member

    /** The lists of [all] that make up [g] (each once, in the order of [Group.members]). */
    fun membersOf(g: Group, all: List<Prompt>): List<Prompt> {
        val out = LinkedHashMap<String, Prompt>()
        for (m in g.members) for (p in all) if (matches(m, p.id) && p.size > 0) out.getOrPut(p.id) { p }
        return out.values.toList()
    }

    /** Ids of lists that belong to some pool (used to keep a list out once its pool is on offer). */
    fun isMember(id: String): Boolean = groups.any { g -> g.members.any { matches(it, id) } }

    /** [g] joined from [parts] (see the class note); null with fewer than 2 parts. */
    fun merge(g: Group, parts: List<Prompt>): Prompt? {
        if (parts.size < 2) return null
        class Item(val share: Double, val part: Int, val name: String, val aliases: List<String>, val clever: Boolean)
        val items = ArrayList<Item>()
        for ((k, p) in parts.withIndex()) {
            val n = p.size.toDouble()
            for (e in p.entries) items += Item((e.rank + 0.5) / n, k, e.name, e.aliases, e.tier == Tier.COPYPASTA)
        }
        items.sortWith(compareBy<Item> { it.share }.thenBy { it.part })
        // the same answer in two lists: once, at its most familiar place, with every alias
        val byKey = LinkedHashMap<String, Pair<Item, MutableSet<String>>>()
        for (x in items) {
            val key = Norm.compact(Norm.key(x.name))
            if (key.isEmpty()) continue
            val slot = byKey[key]
            if (slot == null) byKey[key] = x to LinkedHashSet(x.aliases) else slot.second += x.aliases
        }
        val raws = byKey.values.map { (x, al) -> RawAnswer(x.name, al.filter { a -> a != x.name }, tooClever = x.clever) }
        val entries = Tiering.build(raws, g.id)
        return Prompt(g.id, g.text, g.topic, SourceKind.BUNDLED, entries)
    }

    /** Every pool [all] can make, with at least [MIN_SIZE] answers. */
    fun build(all: List<Prompt>): List<Prompt> = groups.mapNotNull { g ->
        merge(g, membersOf(g, all))?.takeIf { it.size >= MIN_SIZE }
    }
}
