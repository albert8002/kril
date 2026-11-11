package app.coredump.core

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * How well known an answer found online (real, but not on the list) is, measured, so it is scored by its fame
 * instead of always counting as rare. -1 = not measured (no reply, or no such measure for this kind of list).
 *
 * links:    Wikipedia editions with an article on it (Wikidata sitelinks): reach across languages.
 * views:    English Wikipedia page views per day, average of the last 60 days: current interest.
 * mentions: English Wikipedia articles that mention the exact name: how present it is in writing (like a hit count).
 * extra:    one measure for the kind of list ([extraKind]):
 *           "fans"    Deezer fans of an artist or band,
 *           "rank"    Deezer popularity of a song (0–1,000,000),
 *           "readers" Open Library readers who logged a book,
 *           "weight"  TVmaze popularity of a TV show (0–100).
 * shared:   how many members of the list's class the typed text could mean (a surname shared by several people).
 * wordHits: for a one-word answer, English Wikipedia articles containing that word: how guessable it is.
 */
data class Fame(
    val links: Int,
    val views: Int = -1,
    val mentions: Int = -1,
    val extra: Int = -1,
    val extraKind: String = "",
    val shared: Int = 1,
    val wordHits: Int = -1,
) {
    fun encode(): String = listOf(links, views, mentions, extra, extraKind, shared, wordHits).joinToString(",")

    companion object {
        fun decode(s: String?): Fame? {
            val p = s?.split(',') ?: return null
            if (p.size < 7) return null
            return Fame(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: -1, p[2].toIntOrNull() ?: -1,
                p[3].toIntOrNull() ?: -1, p[4], p[5].toIntOrNull() ?: 1, p[6].toIntOrNull() ?: -1)
        }
    }
}

/**
 * Scores a found answer from its [Fame]. Rarer = more points, as everywhere, but measured:
 *
 *  1. each measure → 0..1 on a log scale (1 = as famous as things get):
 *       links    1 → 0.13, 10 → 0.45, 50 → 0.74, 200+ → 1
 *       views    10/day → 0.26, 100 → 0.5, 1,000 → 0.75, 10,000+ → 1
 *       mentions 100 → 0.25, 1,000 → 0.5, 10,000 → 0.75, 100,000+ → 1
 *       fans     10k → 0.25, 100k → 0.5, 1M → 0.75, 10M+ → 1;   rank 100k → 0, 900k+ → 1
 *       readers  10 → 0.25, 100 → 0.5, 1,000 → 0.75, 10k+ → 1;  weight 0–100 → 0–1
 *  2. fame = weighted mean of the measures there are; the weights depend on the kind of list ([weights]):
 *     reach across languages counts most for places and things, current interest most for people and screen,
 *     the genre's own measure a lot for music, books and TV;
 *  3. fame → tier: ≥ [SOLID] SOLID (30), ≥ [UNCOMMON] UNCOMMON (60), below that RARE (85);
 *  4. guessing costs: a typed text that could mean [SHARED]+ members of the class (a common surname), or one word
 *     found in [COMMON_WORD]+ articles (a common word or first name), each lowers the tier one step, never below
 *     SOLID. Found answers are never thrown out, only balanced.
 */
object FameScore {
    const val SOLID = 0.62f
    const val UNCOMMON = 0.42f
    const val SHARED = 3
    const val COMMON_WORD = 50_000

    /** Weights (links, views, mentions, extra) per category key (see [Topics]). */
    fun weights(topic: String): FloatArray = when (topic) {
        "MUSIC" -> floatArrayOf(0.20f, 0.30f, 0.15f, 0.35f)
        "BOOKS & ART" -> floatArrayOf(0.25f, 0.30f, 0.15f, 0.30f)
        "TV" -> floatArrayOf(0.20f, 0.30f, 0.15f, 0.35f)
        "MOVIES", "GAMING", "HORROR", "POP CULTURE" -> floatArrayOf(0.30f, 0.45f, 0.25f, 0f)
        "SPORT", "PEOPLE", "HISTORY" -> floatArrayOf(0.35f, 0.45f, 0.20f, 0f)
        else -> floatArrayOf(0.45f, 0.30f, 0.25f, 0f)
    }

    private fun lg(x: Int): Float = log10(x.toFloat() + 1f)

    fun linksPart(l: Int): Float = (lg(l) / log10(201f)).coerceIn(0f, 1f)
    fun viewsPart(v: Int): Float = (lg(v) / 4f).coerceIn(0f, 1f)
    fun mentionsPart(m: Int): Float = ((lg(m) - 1f) / 4f).coerceIn(0f, 1f)
    fun extraPart(kind: String, x: Int): Float = when (kind) {
        "fans" -> ((lg(x) - 3f) / 4f)
        "rank" -> ((x - 100_000) / 800_000f)
        "readers" -> (lg(x) / 4f)
        "weight" -> (x / 100f)
        else -> 0f
    }.coerceIn(0f, 1f)

    /** 0..1: how famous (1 = as famous as things get). */
    fun fame(f: Fame, topic: String): Float {
        val w = weights(topic)
        var sum = 0f
        var wsum = 0f
        fun add(weight: Float, part: Float) { if (weight > 0f) { sum += weight * part; wsum += weight } }
        add(w[0], linksPart(f.links.coerceAtLeast(0)))
        if (f.views >= 0) add(w[1], viewsPart(f.views))
        if (f.mentions >= 0) add(w[2], mentionsPart(f.mentions))
        if (f.extra >= 0 && f.extraKind.isNotEmpty()) add(w[3], extraPart(f.extraKind, f.extra))
        return if (wsum <= 0f) 0f else sum / wsum
    }

    /** Steps the tier goes down for guessing (0–2). */
    fun guessSteps(f: Fame): Int = (if (f.shared >= SHARED) 1 else 0) + (if (f.wordHits >= COMMON_WORD) 1 else 0)

    fun tier(f: Fame, topic: String): Tier {
        val x = fame(f, topic)
        val byFame = when {
            x >= SOLID -> 0
            x >= UNCOMMON -> 1
            else -> 2
        }
        return when ((byFame - guessSteps(f)).coerceAtLeast(0)) {
            0 -> Tier.LEGACY
            1 -> Tier.MEMORY_LEAK
            else -> Tier.HEISENBUG
        }
    }

    private fun short(n: Int): String = when {
        n >= 1_000_000 -> ((n / 100_000) / 10f).toString().removeSuffix(".0") + "M"
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> ((n / 100) / 10f).toString().removeSuffix(".0") + "k"
        else -> "$n"
    }

    /** What was measured, for the reveal: "found online · 34 Wikipedias · 1.2k views/day · fame 48%". */
    fun note(f: Fame, topic: String): String = buildList {
        add("not on the list · found online")
        add("${f.links} Wikipedia${if (f.links == 1) "" else "s"}")
        if (f.views >= 0) add("${short(f.views)} views/day")
        if (f.mentions >= 0) add("${short(f.mentions)} mentions")
        if (f.extra >= 0) add(when (f.extraKind) {
            "fans" -> "${short(f.extra)} fans"
            "rank" -> "chart ${short(f.extra)}"
            "readers" -> "${short(f.extra)} readers"
            "weight" -> "TV ${f.extra}/100"
            else -> short(f.extra)
        })
        add("fame ${(fame(f, topic) * 100).roundToInt()}%")
        if (f.shared >= SHARED) add("name shared by ${f.shared}")
        if (f.wordHits >= COMMON_WORD) add("common word")
    }.joinToString(" · ")

    /** The genre measure worth fetching for a list, or "" (prompt text and category decide). */
    fun extraKind(topic: String, text: String, people: Boolean): String {
        val t = text.lowercase()
        return when {
            topic == "MUSIC" && (people || listOf("band", "singer", "rapper", "artist", "group", "musician", "dj", "composer", "duo")
                .any { Regex("\\b$it").containsMatchIn(t) }) -> "fans"
            topic == "MUSIC" && listOf("song", "single", "track", "hit").any { Regex("\\b$it").containsMatchIn(t) } -> "rank"
            topic == "BOOKS & ART" && listOf("book", "novel", "poem", "story", "stories", "comic", "manga").any { it in t } -> "readers"
            topic == "TV" && listOf("show", "series", "sitcom", "anime", "cartoon", "soap", "drama").any { it in t } -> "weight"
            else -> ""
        }
    }
}
