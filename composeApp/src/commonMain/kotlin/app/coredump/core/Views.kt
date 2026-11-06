package app.coredump.core

/** Answers grouped under one tier (names only), used for "would have scored more". */
class TierGroup(val tier: Tier, val names: List<String>)

/** One player's play on one prompt. */
class PlayView(
    val player: String,
    val input: String,
    val canonical: String?,
    val tier: Tier,
    val elapsedMs: Long,
) {
    val points: Int get() = tier.points
}

/** Everything the result + summary screens need for one round (solo or 1v1). */
class RoundView(
    val index: Int,
    val promptId: String?,
    val prompt: String,
    val topic: String,
    val listSize: Int,
    val me: PlayView,
    val opp: PlayView?,
    /** Top entries of every valid tier, rarest first. Filter with [betterThan]. */
    val ladder: List<TierGroup>,
) {
    fun betterThan(points: Int): List<TierGroup> = ladder.filter { it.tier.points > points }
}

object Views {
    fun ladder(prompt: Prompt, perTier: Int = 5): List<TierGroup> =
        Judge.missed(prompt, points = 0, perTier = perTier).map { (t, list) -> TierGroup(t, list.map { it.name }) }

    fun play(player: String, j: Judgement, elapsedMs: Long) =
        PlayView(player, j.input, j.entry?.name, j.tier, elapsedMs)

    fun totalPoints(rounds: List<RoundView>, mine: Boolean = true): Int =
        rounds.sumOf { if (mine) it.me.points else (it.opp?.points ?: 0) }
}
