package app.coredump.core

/**
 * Rarity tiers of one answer. Order of declaration = rising rarity.
 * Enum names are kept stable (they are stored in packs, caches and sent over the wire); [label] is what players see.
 * points: score for one answer in this tier. heat: 0..6, drives glow / particle / animation intensity.
 */
enum class Tier(
    val points: Int,
    val label: String,
    val addr: String,
    val blurb: String,
    val heat: Int,
) {
    SKIPPED(0, "SKIPPED", "0x0000", "passed. no points, no shame.", 0),
    TIMEOUT(0, "TIME'S UP", "0x0000", "the clock won this one.", 0),
    SEGFAULT(0, "MISS", "0xDEAD", "not on the list.", 0),
    HELLO_WORLD(10, "BOOTY", "0x000A", "everyone said this. basic.", 1),
    COPYPASTA(15, "TOENAILS", "0x000F", "the 'clever' pick half the internet makes.", 2),
    LEGACY(30, "MID MENTALITY", "0x001E", "solid. not special. mid.", 3),
    MEMORY_LEAK(60, "GOLDEN GOONER", "0x003C", "slipped past almost everyone.", 4),
    HEISENBUG(85, "PORNOGRAPHIC MEMORY", "0x0055", "how do you even know that.", 5),
    ZERO_DAY(100, "GALAXY BRAIN", "0x0064", "the rarest answer on the list.", 6);

    val isValid: Boolean get() = points > 0

    companion object {
        /** Valid tiers, rarest first. */
        val validDescending: List<Tier> = entries.filter { it.isValid }.sortedByDescending { it.points }
        fun byName(name: String): Tier? = entries.firstOrNull { it.name == name }
    }
}

/** Player title at game end, from average points per prompt. */
enum class Rank(val title: String, val minAvg: Int, val line: String) {
    INTERN("NPC", 0, "you said what everyone says."),
    JUNIOR("CASUAL", 15, "a few deep cuts. mostly vibes."),
    ON_CALL("TRIVIA GREMLIN", 25, "you hoard weird facts."),
    NIGHT_SHIFT("MAIN CHARACTER", 35, "the plot bends around your answers."),
    DEBUGGER("LORE GOBLIN", 48, "nobody asked. you knew anyway."),
    EXORCIST("WALKING WIKIPEDIA", 62, "citation not needed."),
    ROOT("OMNISCIENT", 78, "you are the list.");

    companion object {
        fun of(avgPoints: Double): Rank = entries.last { avgPoints >= it.minAvg }
    }
}

fun hexAddr(value: Int, digits: Int = 4): String =
    "0x" + value.toString(16).uppercase().padStart(digits, '0')
