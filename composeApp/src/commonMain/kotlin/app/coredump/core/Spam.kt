package app.coredump.core

/**
 * Key-mashing detector. A rejected answer that looks like random typing costs [PENALTY] points
 * (at most [MAX_PER_ROUND] per prompt). Real words and names should never trip it.
 */
object Spam {
    /** Points lost per mashed answer. */
    const val PENALTY = 3
    /** Most mashed answers charged in one prompt (count). */
    const val MAX_PER_ROUND = 3

    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890")
    private val repeat = Regex("(.)\\1{3,}")
    private val unit = Regex("(..)\\1{2,}")

    fun isGibberish(input: String): Boolean {
        val k = Norm.compact(Norm.key(input))
        val letters = k.filter { it in 'a'..'z' }
        if (letters.length < 4 || letters.length < k.length - 1) return false   // non-latin or mostly digits: can't judge
        if (repeat.containsMatchIn(k)) return true                              // "aaaa", "jjjjj"
        if (letters.length >= 5 && letters.none { it in "aeiouy" }) return true  // "ghjklm", "xvbnt"
        for (row in rows) for (i in 0..row.length - 5) {                         // "asdfg", "poiuy"
            val run = row.substring(i, i + 5)
            if (k.contains(run) || k.contains(run.reversed())) return true
        }
        if (letters.length >= 6 && unit.containsMatchIn(k)) return true          // "lololol", "asasas"
        return false
    }
}
