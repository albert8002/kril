package app.coredump.data

import app.coredump.core.AnswerEntry
import app.coredump.core.Prompt
import app.coredump.core.RawAnswer
import app.coredump.core.SourceKind
import app.coredump.core.Tier
import app.coredump.core.Tiering
import app.coredump.core.stableHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.ln

/**
 * Rescoring: re-ranks the answers of the Wikidata / Wikipedia lists by how much their English Wikipedia articles are
 * read (page views, last [DAYS] days), combined with the list's own order (Wikipedia editions covering each answer).
 * Editions alone get some answers wrong both ways: an animal everybody knows can have few editions (so it scored
 * as a rare gem), and an obscure species or village can have hundreds of bot-made ones (so it scored as common).
 *
 * Only the [HEAD] best-known answers of a list are measured (they hold the common, solid and uncommon bands and the
 * gem pick of all but the longest lists); the rest keep their place after them. The result is stored as a new order
 * of the original list ([Stored]: original positions + a signature of the list it applies to), so bundled lists can
 * be rescored too, and a list that changes (a newer snapshot, a refetch) simply drops its stale order.
 *
 * Pure parts only (request parameters, parsing, the reorder, the stored orders); [QuestionBank] sends the requests.
 */
object Rescore {
    /** A list is rescored again after this long (3.5 days: twice a week). */
    const val EVERY_MS = 84L * 3600 * 1000
    /** "Rescore now" skips lists rescored less than this long ago. */
    const val FORCE_AGE_MS = 6L * 3600 * 1000
    /** Answers measured per list (most familiar first). */
    const val HEAD = 250
    /** Titles per request (the API's limit). */
    const val BATCH = 50
    /** Days of page views summed. */
    const val DAYS = 60
    /** Weight of page views against the list's own order (0..1). */
    const val W_VIEWS = 0.65f
    /** Lists shorter than this keep their order. */
    const val MIN_SIZE = 10

    /** Page view results that are not a count. */
    const val MISSING = -1L    // no article with that title (or redirect): little known in English
    const val DISAMBIG = -2L   // a disambiguation page: says nothing about the answer
    const val UNKNOWN = -3L    // not measured (bad title, no data in the reply)

    fun due(lastAt: Long, nowMs: Long): Boolean = nowMs - lastAt >= EVERY_MS

    /** Ranked by Wikipedia editions, so worth rescoring (hand-made packs keep their curated tiers). */
    fun eligible(p: Prompt): Boolean = (p.source == SourceKind.WIKIDATA || p.source == SourceKind.WIKIPEDIA) && p.size >= MIN_SIZE

    // ---------------- requests

    /** An answer as an article title, or null when it can't be one (characters titles can't hold, too long). */
    fun title(name: String): String? {
        val t = name.trim()
        if (t.isEmpty() || t.length > 200 || t.any { it in "|#<>[]{}" || it.code < 32 }) return null
        return t
    }

    /** MediaWiki API parameters (GET [WikiNet.WP_API]) for the page views of up to [BATCH] [titles]. */
    fun params(titles: List<String>, cont: Map<String, String> = emptyMap()): List<Pair<String, String>> = listOf(
        "action" to "query", "prop" to "pageviews|pageprops", "ppprop" to "disambiguation",
        "titles" to titles.joinToString("|"), "redirects" to "1", "pvipdays" to DAYS.toString(),
        "maxlag" to "5", "format" to "json", "formatversion" to "2",
    ) + cont.filterKeys { it != "action" && it != "titles" }.toList()

    /**
     * One reply: views per page title (or [MISSING] / [DISAMBIG] / [UNKNOWN]), the title changes the API made
     * (normalized, redirects: from → to) and the parameters to continue with (empty when complete).
     */
    class Reply(val pages: Map<String, Long>, val moved: Map<String, String>, val cont: Map<String, String>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a reply; null when it is an API error or not the expected shape. */
    fun parse(body: String): Reply? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        if (o["error"] != null) return null
        val q = o["query"]?.jsonObject ?: return null
        val moved = HashMap<String, String>()
        for (key in listOf("normalized", "redirects")) {
            (q[key] as? JsonArray)?.forEach { el ->
                val m = el.jsonObject
                val from = m["from"]?.jsonPrimitive?.contentOrNull
                val to = m["to"]?.jsonPrimitive?.contentOrNull
                if (from != null && to != null) moved[from] = to
            }
        }
        val pages = HashMap<String, Long>()
        (q["pages"] as? JsonArray)?.forEach { el ->
            val p = el.jsonObject
            val t = p["title"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val pv = p["pageviews"] as? JsonObject
            pages[t] = when {
                p["missing"] != null || p["invalid"] != null -> MISSING
                (p["pageprops"] as? JsonObject)?.containsKey("disambiguation") == true -> DISAMBIG
                pv != null -> pv.values.sumOf { v -> (v as? JsonPrimitive)?.let { runCatching { it.longOrNull }.getOrNull() } ?: 0L }
                else -> UNKNOWN
            }
        }
        val cont = HashMap<String, String>()
        (o["continue"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { cont[k] = it } }
        Reply(pages, moved, cont)
    }.getOrNull()

    /** Views of each [asked] title across the replies of one batch (a later reply fills what an earlier one left open). */
    fun viewsFor(asked: List<String>, replies: List<Reply>): LongArray {
        val pages = HashMap<String, Long>()
        val moved = HashMap<String, String>()
        for (r in replies) {
            moved.putAll(r.moved)
            for ((t, v) in r.pages) if (v != UNKNOWN || t !in pages) pages[t] = v
        }
        return LongArray(asked.size) { i ->
            var t = asked[i]
            repeat(3) { t = moved[t] ?: return@repeat }
            pages[t] ?: UNKNOWN
        }
    }

    // ---------------- the new order

    /**
     * New order of the measured answers. [orig]: original position (by Wikipedia editions) of each measured answer;
     * [views]: its page views or [MISSING] / [DISAMBIG] / [UNKNOWN]. Returns the original positions, best known first.
     *
     * Each answer gets two 0..1 scores among the measured ones: its place in the list's own order, and its page views
     * (half percentile — ties share one —, half log scale between the least and the most read, so an answer read
     * a hundred times more than the rest stands out). Fame = [W_VIEWS] × views + the rest × own order. An answer
     * without an English article under that name counts as little known in English (views score 0.2 × its order
     * score); one that could not be measured keeps its order score. Fewer than two measured answers: the order stays.
     */
    fun order(orig: IntArray, views: LongArray): IntArray {
        val h = orig.size
        if (h <= 1) return orig.copyOf()
        val rs = FloatArray(h)
        for ((r, k) in (0 until h).sortedBy { orig[it] }.withIndex()) rs[k] = 1f - r / (h - 1f)
        val known = (0 until h).filter { views[it] >= 0 }
        if (known.size < 2) return (0 until h).sortedBy { orig[it] }.map { orig[it] }.toIntArray()
        val vs = FloatArray(h)
        val sorted = known.sortedBy { views[it] }
        val lo = ln(views[sorted.first()] + 1.0)
        val span = (ln(views[sorted.last()] + 1.0) - lo).coerceAtLeast(1e-9)
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && views[sorted[j + 1]] == views[sorted[i]]) j++
            val pct = ((i + j) / 2f) / (sorted.size - 1f)
            val mag = ((ln(views[sorted[i]] + 1.0) - lo) / span).toFloat()
            for (m in i..j) vs[sorted[m]] = 0.5f * pct + 0.5f * mag
            i = j + 1
        }
        val fame = FloatArray(h) { k ->
            val v = when {
                views[k] >= 0 -> vs[k]
                views[k] == MISSING -> rs[k] * 0.2f
                else -> rs[k]
            }
            W_VIEWS * v + (1f - W_VIEWS) * rs[k]
        }
        return (0 until h).sortedWith(compareByDescending<Int> { fame[it] }.thenBy { orig[it] }).map { orig[it] }.toIntArray()
    }

    /** Original position of every answer when [perm] (head order) is applied to a list of [n]: head, then the rest in order. */
    fun arrangement(n: Int, perm: IntArray): IntArray {
        val used = BooleanArray(n)
        val out = IntArray(n)
        var k = 0
        for (o in perm) if (o in 0 until n && !used[o]) { used[o] = true; out[k++] = o }
        for (o in 0 until n) if (!used[o]) out[k++] = o
        return out
    }

    /** [perm] can be applied to a list of [n] answers (distinct positions inside the list). */
    fun valid(n: Int, perm: IntArray): Boolean {
        if (perm.size > n) return false
        val seen = BooleanArray(n)
        for (o in perm) { if (o !in 0 until n || seen[o]) return false; seen[o] = true }
        return true
    }

    /**
     * The answers of [current] in their original order, given the head order [applied] it is shown in (null = it is
     * in its original order).
     */
    fun originalEntries(current: List<AnswerEntry>, applied: IntArray?): List<AnswerEntry> {
        if (applied == null || !valid(current.size, applied)) return current
        val pos = arrangement(current.size, applied)
        val out = arrayOfNulls<AnswerEntry>(current.size)
        for (k in current.indices) out[pos[k]] = current[k]
        return out.map { it!! }
    }

    /** Identifies a list's content in its original order (the stored order is only applied to that list). */
    fun signature(original: List<AnswerEntry>): Int =
        stableHash(original.size.toString() + "|" + original.joinToString("\n") { it.name })

    /** [p] with its answers in the order [perm] gives its [original] list, tiers rebuilt (gem picked again). */
    fun rebuild(p: Prompt, original: List<AnswerEntry>, perm: IntArray): Prompt {
        val raws = arrangement(original.size, perm).map { o ->
            val e = original[o]
            RawAnswer(e.name, e.aliases, tooClever = e.tier == Tier.COPYPASTA)
        }
        return Prompt(p.id, p.text, p.topic, p.source, Tiering.build(raws, p.id), p.check, p.cuts)
    }

    // ---------------- stored orders ("rescore.json")

    /** When the list was rescored (epoch ms), the [signature] of the original list, and its new head order. */
    class Stored(val at: Long, val sig: Int, val perm: IntArray)

    fun encode(lists: Map<String, Stored>): String = buildJsonObject {
        put("v", 1)
        put("lists", buildJsonObject {
            for ((id, s) in lists) put(id, buildJsonObject {
                put("at", s.at)
                put("sig", s.sig)
                put("o", s.perm.joinToString(".") { it.toString(36) })
            })
        })
    }.toString()

    fun decode(text: String?): HashMap<String, Stored> {
        val out = HashMap<String, Stored>()
        if (text.isNullOrBlank()) return out
        runCatching {
            val lists = json.parseToJsonElement(text).jsonObject["lists"]?.jsonObject ?: return out
            for ((id, el) in lists) runCatching {
                val o = el.jsonObject
                val at = o["at"]?.jsonPrimitive?.longOrNull ?: 0L
                val sig = o["sig"]?.jsonPrimitive?.intOrNull ?: 0
                val s = o["o"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val perm = if (s.isEmpty()) IntArray(0) else s.split('.').map { it.toInt(36) }.toIntArray()
                out[id] = Stored(at, sig, perm)
            }
        }
        return out
    }
}
