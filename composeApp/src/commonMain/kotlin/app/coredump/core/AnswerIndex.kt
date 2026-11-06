package app.coredump.core

import kotlin.math.abs

/**
 * Answer lookup, sized for phones.
 *
 *  1. exact:  HashMap<normalised key, entry>      -> O(1)
 *  2. morph:  plural/singular variants             -> O(1) each
 *  3. fuzzy:  keys bucketed by length; only buckets within the typo tolerance are scanned,
 *             each candidate checked with a bounded OSA edit distance (early exit).
 *
 * Memory: about 3 map keys per answer. (The old symmetric-delete index cost ~17 MB per
 * 1,500-answer list and was the cause of out-of-memory crashes.)
 */
class AnswerIndex(entries: List<AnswerEntry>, @Suppress("UNUSED_PARAMETER") maxIndexEdit: Int = 2) {

    class Hit(val entry: Int, val distance: Int, val key: String)

    private val exact = HashMap<String, Int>(entries.size * 3)
    private val fuzzyKeys = ArrayList<String>(entries.size * 2)
    private val fuzzyEntry = ArrayList<Int>(entries.size * 2)
    /** byLength[n] = indexes into fuzzyKeys of keys with length n. */
    private val byLength: Array<IntBag>
    val keyCount: Int get() = exact.size

    init {
        val seenCompact = HashSet<String>()
        entries.forEachIndexed { i, e ->
            addVariants(e.name, i, seenCompact)
            for (a in e.aliases) addVariants(a, i, seenCompact)
        }
        val maxLen = fuzzyKeys.maxOfOrNull { it.length } ?: 0
        byLength = Array(maxLen + 1) { IntBag() }
        for (ki in fuzzyKeys.indices) byLength[fuzzyKeys[ki].length].add(ki)
    }

    private fun addVariants(raw: String, entry: Int, seenCompact: HashSet<String>) {
        val k = Norm.key(raw)
        if (k.isEmpty()) return
        if (!exact.containsKey(k)) exact[k] = entry
        val c = Norm.compact(k)
        if (c.isEmpty()) return
        if (!exact.containsKey(c)) exact[c] = entry
        if (seenCompact.add(c)) {
            fuzzyKeys += c
            fuzzyEntry += entry
        }
    }

    fun lookup(input: String, maxEdit: Int): Hit? {
        val q = Norm.key(input)
        if (q.isEmpty()) return null
        val qc = Norm.compact(q)
        exact[q]?.let { return Hit(it, 0, q) }
        if (qc.isEmpty()) return null
        exact[qc]?.let { return Hit(it, 0, qc) }
        for (v in Norm.morphVariants(qc)) exact[v]?.let { return Hit(it, 0, v) }

        val tol = minOf(maxEdit, toleranceFor(qc.length))
        if (tol <= 0) return null
        return nearest(qc, tol, allowExact = true)
    }

    /**
     * Autocorrect hint for a rejected input: nearest key within a looser bound
     * (about 1 edit per 3 chars, max 3). Only runs on a miss.
     */
    fun suggest(input: String): Hit? {
        val qc = Norm.compact(Norm.key(input))
        if (qc.length < 3) return null
        return nearest(qc, (qc.length / 3).coerceIn(1, 3), allowExact = false)
    }

    private fun nearest(qc: String, bound: Int, allowExact: Boolean): Hit? {
        var best: Hit? = null
        val lo = (qc.length - bound).coerceAtLeast(0)
        val hi = (qc.length + bound).coerceAtMost(byLength.size - 1)
        for (len in lo..hi) {
            val bag = byLength[len]
            for (j in 0 until bag.size) {
                val ki = bag[j]
                val key = fuzzyKeys[ki]
                val limit = best?.distance ?: bound
                val d = Edits.osa(qc, key, limit)
                if (d > limit || (d == 0 && !allowExact)) continue
                val e = fuzzyEntry[ki]
                val b = best
                if (b == null || d < b.distance || (d == b.distance && e < b.entry)) best = Hit(e, d, key)
            }
        }
        return best
    }

    companion object {
        /** Allowed typos by input length (compact form). */
        fun toleranceFor(len: Int): Int = when {
            len <= 3 -> 0
            len <= 5 -> 1
            else -> 2
        }
    }
}

/**
 * Keeps answer indexes for the few prompts in play; older ones are dropped and rebuilt on demand
 * (building one takes a few ms), so memory does not grow with every list ever played.
 */
object IndexCache {
    private const val MAX = 16
    private val map = LinkedHashMap<String, AnswerIndex>()
    private val lock = kotlinx.coroutines.sync.Mutex()

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* held only for a few map operations */ }
        try { return block() } finally { lock.unlock() }
    }

    fun of(p: Prompt): AnswerIndex {
        val k = p.cacheKey
        val hit = locked { map.remove(k)?.also { map[k] = it } }
        if (hit != null) return hit
        val built = AnswerIndex(p.entries)
        locked {
            map[k] = built
            while (map.size > MAX) map.remove(map.keys.first())
        }
        return built
    }

    fun clear() = locked { map.clear() }
}

/** Growable int list without boxing. */
class IntBag {
    private var data = IntArray(2)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
}

object Edits {
    /** All strings reachable by deleting up to [depth] chars, including [s]. */
    fun deletes(s: String, depth: Int): Set<String> {
        val out = HashSet<String>()
        out += s
        var frontier: Collection<String> = listOf(s)
        repeat(depth) {
            val next = ArrayList<String>()
            for (w in frontier) {
                if (w.length <= 1) continue
                for (i in w.indices) {
                    val d = w.removeRange(i, i + 1)
                    if (out.add(d)) next += d
                }
            }
            frontier = next
        }
        return out
    }

    /** Optimal-string-alignment distance; returns max+1 as soon as the bound is exceeded. */
    fun osa(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return max + 1
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        var prevPrev = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prevPrev[j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prevPrev; prevPrev = prev; prev = cur; cur = t
        }
        return prev[m]
    }
}
