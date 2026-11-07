package app.coredump.core

/** Answers grouped under one tier (names only), used for "would have scored more". */
class TierGroup(val tier: Tier, val names: List<String>)

/** One player's play on one prompt. */
/** penalty: points taken off for key-mashed answers in this prompt (≥ 0). points can go below 0. */
class PlayView(
    val player: String,
    val input: String,
    val canonical: String?,
    val tier: Tier,
    val elapsedMs: Long,
    val penalty: Int = 0,
) {
    val points: Int get() = tier.points - penalty
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
    /** Every answer on the list, most common tier first, most familiar first inside a tier (empty = not known yet). */
    val all: List<TierGroup> = emptyList(),
) {
    fun betterThan(points: Int): List<TierGroup> = ladder.filter { it.tier.points > points }
    fun withAll(groups: List<TierGroup>) = RoundView(index, promptId, prompt, topic, listSize, me, opp, ladder, groups)
}

object Views {
    fun ladder(prompt: Prompt, perTier: Int = 5): List<TierGroup> =
        Judge.missed(prompt, points = 0, perTier = perTier).map { (t, list) -> TierGroup(t, list.map { it.name }) }

    /** Every answer of [prompt] grouped by tier, most common tier first (list order inside each tier). */
    fun all(prompt: Prompt): List<TierGroup> =
        Tier.validDescending.reversed().mapNotNull { t ->
            val names = prompt.entries.filter { it.tier == t }.map { it.name }
            if (names.isEmpty()) null else TierGroup(t, names)
        }

    /** Splits [groups] into pieces of about [chars] characters each (for sending over a 1v1 link), order kept. */
    fun pieces(groups: List<TierGroup>, chars: Int = 9_000, maxNames: Int = 6_000): List<List<TierGroup>> {
        val out = ArrayList<List<TierGroup>>()
        var cur = ArrayList<TierGroup>()
        var size = 0
        var count = 0
        for (g in groups) {
            var names = ArrayList<String>()
            for (n in g.names) {
                if (count >= maxNames) break
                if (size + n.length > chars && (names.isNotEmpty() || cur.isNotEmpty())) {
                    if (names.isNotEmpty()) cur.add(TierGroup(g.tier, names))
                    out.add(cur); cur = ArrayList(); names = ArrayList(); size = 0
                }
                names.add(n); size += n.length + 4; count++
            }
            if (names.isNotEmpty()) cur.add(TierGroup(g.tier, names))
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** Adds a received piece to the groups so far (same tier = names appended). */
    fun merge(have: List<TierGroup>, piece: List<TierGroup>): List<TierGroup> {
        val m = LinkedHashMap<Tier, ArrayList<String>>()
        for (g in have) m.getOrPut(g.tier) { ArrayList() }.addAll(g.names)
        for (g in piece) m.getOrPut(g.tier) { ArrayList() }.addAll(g.names)
        return Tier.validDescending.reversed().mapNotNull { t -> m[t]?.let { TierGroup(t, it) } }
    }

    fun play(player: String, j: Judgement, elapsedMs: Long, spam: Int = 0) =
        PlayView(player, j.input, j.entry?.name, j.tier, elapsedMs, spam.coerceAtMost(Spam.MAX_PER_ROUND) * Spam.PENALTY)

    fun totalPoints(rounds: List<RoundView>, mine: Boolean = true): Int =
        rounds.sumOf { if (mine) it.me.points else (it.opp?.points ?: 0) }
}
