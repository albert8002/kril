package app.coredump.core

/**
 * A player's own look for one tier or rank. Key: "T:<tier enum name>" or "R:<rank enum name>".
 * name: own name ("" = default). color: ARGB colour (null = default). icon: an uploaded icon file exists.
 * anim: icon micro-animation (see [IconAnim]).
 */
data class Custom(val name: String = "", val color: Long? = null, val icon: Boolean = false, val anim: String = "NONE") {
    val isDefault: Boolean get() = name.isBlank() && color == null && !icon && anim == "NONE"

    companion object {
        fun keyOf(t: Tier) = "T:${t.name}"
        fun keyOf(r: Rank) = "R:${r.name}"
    }
}

/** Micro-animations a player can give an uploaded tier / rank icon. */
enum class IconAnim(val label: String) {
    NONE("still"), PULSE("pulse"), BOUNCE("bounce"), SPIN("spin"), WOBBLE("wobble"), FLOAT("float"), BLINK("blink"),
    SHAKE("shake"), FLIP("flip");

    companion object {
        fun byName(n: String?): IconAnim = entries.firstOrNull { it.name == n } ?: NONE
    }
}

/**
 * Which names players see. Plain names by default; [spicy] = the crude names (a hidden setting);
 * [custom] = the player's own names (they win over both). Set from the settings at start-up and whenever they change.
 */
object Names {
    var spicy: Boolean = false
    var custom: Map<String, Custom> = emptyMap()

    internal fun own(key: String): String? = custom[key]?.name?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Rarity tiers of one answer. Order of declaration = rising rarity.
 * Enum names are kept stable (they are stored in packs, caches and sent over the wire); [label] is what players see.
 * points: score for one answer in this tier. heat: 0..6, drives glow / particle / animation intensity.
 */
enum class Tier(
    val points: Int,
    private val plain: String,
    private val spicy: String,
    val addr: String,
    val blurb: String,
    val heat: Int,
) {
    SKIPPED(0, "SKIPPED", "SKIPPED", "0x0000", "passed. no points, no shame.", 0),
    TIMEOUT(0, "TIME'S UP", "TIME'S UP", "0x0000", "the clock won this one.", 0),
    SEGFAULT(0, "MISS", "MISS", "0xDEAD", "not on the list.", 0),
    HELLO_WORLD(10, "COMMON", "BOOTY", "0x000A", "everyone said this. basic.", 1),
    COPYPASTA(15, "FAMILIAR", "TOENAILS", "0x000F", "the 'clever' pick half the internet makes.", 2),
    LEGACY(30, "SOLID", "MID MENTALITY", "0x001E", "solid. not special.", 3),
    MEMORY_LEAK(60, "UNCOMMON", "GOLDEN GOONER", "0x003C", "slipped past almost everyone.", 4),
    HEISENBUG(85, "RARE", "PORNOGRAPHIC MEMORY", "0x0055", "how do you even know that.", 5),
    ZERO_DAY(100, "LEGENDARY", "OILED UP", "0x0064", "the rarest answer on the list.", 6);

    /** Name before any custom name (plain or spicy). */
    val defaultLabel: String get() = if (Names.spicy) spicy else plain
    val label: String get() = (if (isValid) Names.own(Custom.keyOf(this)) else null) ?: defaultLabel
    val isValid: Boolean get() = points > 0

    /** .reversed(): the most common answer scores most (the points ladder mirrored); misses still score 0. */
    val reversedPoints: Int get() = when (this) {
        HELLO_WORLD -> 100
        COPYPASTA -> 85
        LEGACY -> 60
        MEMORY_LEAK -> 30
        HEISENBUG -> 15
        ZERO_DAY -> 10
        else -> 0
    }

    companion object {
        /** Valid tiers, rarest first. */
        val validDescending: List<Tier> = entries.filter { it.isValid }.sortedByDescending { it.points }
        fun byName(name: String): Tier? = entries.firstOrNull { it.name == name }
    }
}

/** Player title at game end, from average points per prompt (plain / spicy names, see [Names]). */
enum class Rank(private val plain: String, private val spicy: String, val minAvg: Int, val line: String) {
    INTERN("BEGINNER", "NPC", 0, "you said what everyone says."),
    JUNIOR("AMATEUR", "CASUAL", 15, "a few deep cuts. mostly the usual."),
    ON_CALL("ENTHUSIAST", "TRIVIA GREMLIN", 25, "you collect odd facts."),
    NIGHT_SHIFT("SHARP", "MAIN CHARACTER", 35, "your answers stand out."),
    DEBUGGER("EXPERT", "LORE GOBLIN", 48, "nobody asked. you knew anyway."),
    EXORCIST("MASTER", "WALKING WIKIPEDIA", 62, "citation not needed."),
    ROOT("GRANDMASTER", "OMNISCIENT", 78, "you are the list.");

    val defaultTitle: String get() = if (Names.spicy) spicy else plain
    val title: String get() = Names.own(Custom.keyOf(this)) ?: defaultTitle

    companion object {
        fun of(avgPoints: Double): Rank = entries.last { avgPoints >= it.minAvg }
    }
}

fun hexAddr(value: Int, digits: Int = 4): String =
    "0x" + value.toString(16).uppercase().padStart(digits, '0')
