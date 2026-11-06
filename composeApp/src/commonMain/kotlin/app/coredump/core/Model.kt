package app.coredump.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

enum class SourceKind(val tag: String) {
    BUNDLED("LOCAL"),
    WIKIDATA("WIKIDATA"),
    WIKIPEDIA("WIKIPEDIA"),
    REMOTE("REMOTE");

    companion object {
        fun byName(n: String): SourceKind = entries.firstOrNull { it.name == n } ?: REMOTE
    }
}

/** One accepted answer. rank 0 = most familiar. */
class AnswerEntry(
    val name: String,
    val aliases: List<String>,
    val tier: Tier,
    val rank: Int,
)

/** One prompt with its full ranked answer list (entries sorted by rank). */
class Prompt(
    val id: String,
    val text: String,
    rawTopic: String,
    val source: SourceKind,
    val entries: List<AnswerEntry>,
    /** Wikidata graph pattern binding ?item: used to verify answers that are not in the list. */
    val check: String? = null,
    /** Sitelink thresholds [LEGACY min, MEMORY_LEAK min] for scoring verified unlisted answers. */
    val cuts: IntArray? = null,
) {
    /** Category key (see [Topics]); old category names from older data are mapped. */
    val topic: String = Topics.classify(id, text, rawTopic)
    /** Needs specialist knowledge: left out unless the player turns niche lists on. */
    val niche: Boolean = Topics.isNiche(id, text)
    /** Answers are people: a surname alone is accepted. */
    val people: Boolean = Topics.isPeople(id, text, check)
    val size: Int get() = entries.size
    /** Built on demand and kept only for recently used prompts (see [IndexCache]). */
    val index: AnswerIndex get() = IndexCache.of(this)
    internal val cacheKey: String = id + "#" + entries.size + "#" + (entries.firstOrNull()?.name ?: "")
    val zeroDay: AnswerEntry? get() = entries.firstOrNull { it.tier == Tier.ZERO_DAY }
    fun countOf(t: Tier): Int = entries.count { it.tier == t }
}

/** Unranked input to tiering: list must be in familiarity order (most familiar first). */
class RawAnswer(
    val name: String,
    val aliases: List<String> = emptyList(),
    val tooClever: Boolean = false,
    val zeroDay: Boolean = false,
)

object Tiering {
    /** End index (exclusive) of each tier band, by position in the ranked list. */
    class Cuts(val hello: Int, val legacy: Int, val leak: Int)

    fun cuts(n: Int): Cuts {
        val p = ceil(n * 0.04).toInt().coerceIn(1, 12)
        val s = p + max(1, ceil(n * 0.10).toInt())
        val r = s + max(1, ceil(n * 0.25).toInt())
        return Cuts(p.coerceAtMost(n), s.coerceAtMost(n), r.coerceAtMost(n))
    }

    /** Dedupe by key, assign tiers by position, mark COPYPASTA and one ZERO_DAY. */
    fun build(ranked: List<RawAnswer>, seed: String): List<AnswerEntry> {
        val seen = HashSet<String>()
        val list = ranked.filter { a ->
            val k = Norm.compact(Norm.key(a.name))
            k.isNotEmpty() && seen.add(k)
        }
        val n = list.size
        if (n == 0) return emptyList()
        val c = cuts(n)
        val tiers = Array(n) { i ->
            when {
                i < c.hello -> Tier.HELLO_WORLD
                i < c.legacy -> Tier.LEGACY
                i < c.leak -> Tier.MEMORY_LEAK
                else -> Tier.HEISENBUG
            }
        }
        for (i in 0 until n) if (list[i].tooClever) tiers[i] = Tier.COPYPASTA

        var zd = list.indexOfFirst { it.zeroDay }
        if (zd < 0 && n >= 3) {
            // Auto pick: a "gettable" deep cut, from the familiar end of the rarest band.
            val start = if (c.leak < n) c.leak else c.legacy.coerceAtMost(n - 1)
            val span = max(1, ((n - start) * 0.3).toInt())
            var pick = start + ((stableHash(seed).toLong() and 0x7FFFFFFF) % span).toInt()
            // never turn a COPYPASTA into the gem
            while (pick < n && tiers[pick] == Tier.COPYPASTA) pick++
            zd = if (pick < n) pick else -1
        }
        if (zd >= 0) tiers[zd] = Tier.ZERO_DAY

        return List(n) { i -> AnswerEntry(list[i].name, list[i].aliases, tiers[i], i) }
    }
}

/** Scores an unlisted-but-verified answer from its sitelink count (number of Wikipedia editions covering it). */
object Rarity {
    /** Defaults when a list has no measured thresholds. */
    val DEFAULT_CUTS = intArrayOf(100, 35)

    fun tierForLinks(links: Int, cuts: IntArray?): Tier {
        val c = cuts?.takeIf { it.size >= 2 } ?: DEFAULT_CUTS
        return when {
            links >= c[0] -> Tier.LEGACY
            links >= c[1] -> Tier.MEMORY_LEAK
            else -> Tier.HEISENBUG
        }
    }
}

enum class Difficulty(
    val label: String,
    val maxEdit: Int,
    val minBreadth: Int,
    val maxBreadth: Int,
    val blurb: String,
) {
    EASY("EASY", 2, 60, Int.MAX_VALUE, "wide lists · 2 typos ok"),
    NORMAL("NORMAL", 2, 0, Int.MAX_VALUE, "any list · 2 typos ok"),
    HARD("HARD", 1, 0, 400, "narrow lists · 1 typo"),
    NIGHTMARE("NIGHTMARE", 0, 0, 150, "tiny lists · exact spelling");

    companion object {
        fun byName(n: String): Difficulty = entries.firstOrNull { it.name == n } ?: NORMAL
    }
}

/**
 * rounds: number of prompts in one game (count).
 * secondsPerRound: time limit per prompt in seconds; 0 = no limit.
 * topics: allowed topic names; empty = all.
 */
data class GameConfig(
    val rounds: Int,
    val secondsPerRound: Int,
    val difficulty: Difficulty,
    val topics: Set<String>,
    /** Include hyper-specific lists. */
    val niche: Boolean = false,
)

/** Result of judging one answer. */
class Judgement(
    val input: String,
    val entry: AnswerEntry?,
    val tier: Tier,
    val distance: Int,
) {
    val points: Int get() = tier.points
}

object Judge {
    fun judge(prompt: Prompt, input: String?, maxEdit: Int): Judgement {
        if (input == null || input.isBlank()) return Judgement(input ?: "", null, Tier.TIMEOUT, -1)
        val hit = prompt.index.lookup(input, maxEdit)
            ?: return Judgement(input, null, Tier.SEGFAULT, -1)
        val e = prompt.entries[hit.entry]
        return Judgement(input, e, e.tier, hit.distance)
    }

    /** Judgement for an answer verified online (not in the list). */
    fun verified(input: String, label: String, links: Int, prompt: Prompt): Judgement {
        val tier = Rarity.tierForLinks(links, prompt.cuts)
        return Judgement(input, AnswerEntry(label, emptyList(), tier, -1), tier, 0)
    }

    /**
     * Answers that would have scored more than [points], grouped by tier (rarest first).
     * Takes the most familiar entries of each tier: the ones a player could have known.
     */
    fun missed(prompt: Prompt, points: Int, perTier: Int = 5): List<Pair<Tier, List<AnswerEntry>>> =
        Tier.validDescending
            .filter { it.points > points }
            .mapNotNull { t ->
                val take = if (t == Tier.ZERO_DAY) 1 else perTier
                val list = prompt.entries.asSequence().filter { it.tier == t }.take(take).toList()
                if (list.isEmpty()) null else t to list
            }
}

object PromptPicker {
    /**
     * Picks [GameConfig.rounds] distinct prompts.
     * Order of preference: not shown in the last [cooldownMs] (per [seen]), general (not niche), matching the
     * difficulty's list size; then the same without the size rule; then niche lists; then prompts shown least recently.
     */
    fun pick(
        all: Collection<Prompt>,
        cfg: GameConfig,
        recent: Set<String>,
        rnd: Random,
        seen: Map<String, Long> = emptyMap(),
        cooldownMs: Long = 0L,
        now: Long = 0L,
    ): List<Prompt> {
        val usable = all.filter { it.size >= 3 }
        val inTopic = usable.filter { cfg.topics.isEmpty() || it.topic in cfg.topics }
        val general = inTopic.filter { cfg.niche || !it.niche }
        fun fresh(p: Prompt) = cooldownMs <= 0L || now - (seen[p.id] ?: Long.MIN_VALUE / 2) >= cooldownMs
        fun sized(p: Prompt) = p.size in cfg.difficulty.minBreadth..cfg.difficulty.maxBreadth

        val out = LinkedHashMap<String, Prompt>()   // keyed by prompt text: no duplicates across sources
        fun fill(list: List<Prompt>, shuffle: Boolean = true) {
            val ordered = if (shuffle) list.shuffled(rnd).sortedBy { if (it.id in recent) 1 else 0 } else list
            for (p in ordered) {
                if (out.size >= cfg.rounds) return
                val k = Norm.key(p.text)
                if (!out.containsKey(k)) out[k] = p
            }
        }
        fill(general.filter { fresh(it) && sized(it) })
        fill(general.filter { fresh(it) })
        fill(inTopic.filter { fresh(it) })
        fill(general.sortedBy { seen[it.id] ?: 0L }, shuffle = false)
        fill(inTopic.sortedBy { seen[it.id] ?: 0L }, shuffle = false)
        if (out.isEmpty()) fill(usable)
        return out.values.toList()
    }
}

/** One finished round of one player. */
class RoundRecord(
    val prompt: Prompt,
    val judgement: Judgement,
    val elapsedMs: Long,
) {
    val points: Int get() = judgement.points
}
