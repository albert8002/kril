package app.coredump.core

/**
 * 1v1 speed penalty (a match setting; always on in ranked): once one player has given a real answer, the other loses
 * [PER_SECOND] points for every second they still take (whole seconds, at most [MAX]). Skipping stops it: the
 * skipper keeps only the loss so far.
 */
object SpeedTax {
    const val PER_SECOND = 3
    const val MAX = 60

    /** Points lost for answering [lateMs] after the other player. */
    fun of(lateMs: Long): Int = if (lateMs <= 0) 0 else (((lateMs + 999) / 1000).toInt() * PER_SECOND).coerceIn(0, MAX)
}
