package app.coredump.core

/**
 * Rarity tiers. Order of declaration = rising rarity.
 * points: score for one answer in this tier.
 * heat:   0..6, drives glow / animation intensity in the UI.
 */
enum class Tier(
    val points: Int,
    val label: String,
    val addr: String,
    val blurb: String,
    val heat: Int,
) {
    TIMEOUT(0, "TIMEOUT", "0x0000", "process killed: deadline exceeded.", 0),
    SEGFAULT(0, "SEGFAULT", "0xDEAD", "not in memory. core dumped.", 0),
    HELLO_WORLD(10, "HELLO_WORLD", "0x000A", "everyone writes this first.", 1),
    COPYPASTA(15, "COPYPASTA", "0x000F", "the 'clever' pick. half the internet pasted it.", 2),
    LEGACY(30, "LEGACY", "0x001E", "known, but nobody touches it anymore.", 3),
    MEMORY_LEAK(60, "MEMORY_LEAK", "0x003C", "slipped past almost everyone.", 4),
    HEISENBUG(85, "HEISENBUG", "0x0055", "vanishes when anyone looks for it.", 5),
    ZERO_DAY(100, "ZERO_DAY", "0x0064", "the one exploit nobody patched.", 6);

    val isValid: Boolean get() = points > 0

    companion object {
        /** Valid tiers, rarest first. */
        val validDescending: List<Tier> = entries.filter { it.isValid }.sortedByDescending { it.points }
        fun byName(name: String): Tier? = entries.firstOrNull { it.name == name }
    }
}

/** Player title at game end, from average points per prompt. */
enum class Rank(val title: String, val minAvg: Int, val line: String) {
    INTERN("INTERN", 0, "you pushed to main on day one."),
    JUNIOR("JUNIOR DEV", 15, "you still trust the stack trace."),
    ON_CALL("ON-CALL", 25, "the pager owns you now."),
    NIGHT_SHIFT("NIGHT SHIFT", 35, "3 a.m. logs read like scripture."),
    DEBUGGER("DEBUGGER", 48, "you hear the bugs breathing."),
    EXORCIST("EXORCIST", 62, "you cast out what others can't see."),
    ROOT("ROOT", 78, "uid=0. the machine answers to you.");

    companion object {
        fun of(avgPoints: Double): Rank = entries.last { avgPoints >= it.minAvg }
    }
}

/** 1 point = 10 frames of stack depth. */
const val FRAMES_PER_POINT = 10

fun depthFrames(points: Int): Int = points * FRAMES_PER_POINT

fun hexAddr(value: Int, digits: Int = 4): String =
    "0x" + value.toString(16).uppercase().padStart(digits, '0')
