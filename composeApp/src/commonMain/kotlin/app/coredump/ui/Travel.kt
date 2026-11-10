package app.coredump.ui

/**
 * Travel profile of a score run, t and position in 0..1: speeds up over the first [A] of the time (constant
 * acceleration), cruises, and slows down over the last [D] (constant deceleration) to a stop at 1.
 * Speed is 0 at both ends and 1 while cruising.
 */
internal object Travel {
    private const val A = 0.28f
    private const val D = 0.42f
    /** Cruising speed (positions per unit time) so the three parts add up to 1. */
    private val vmax = 1f / (1f - A / 2f - D / 2f)

    fun pos(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when {
            x < A -> vmax * x * x / (2f * A)
            x < 1f - D -> vmax * (A / 2f + (x - A))
            else -> 1f - vmax * (1f - x) * (1f - x) / (2f * D)
        }
    }

    fun speed(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when {
            x < A -> x / A
            x < 1f - D -> 1f
            else -> (1f - x) / D
        }
    }

    /** Time (0..1) at which the position reaches [p] (inverse of [pos]). */
    fun timeAt(p: Float): Float {
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (pos(mid) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    /** Travel time (ms) for [points] scored: 1.5 s for the commonest answer, 3.1 s for the rarest. */
    fun durationMs(points: Int): Int = 1300 + points.coerceIn(0, 100) * 18
}
