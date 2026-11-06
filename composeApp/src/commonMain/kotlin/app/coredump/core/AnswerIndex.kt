package app.coredump.core

import kotlin.math.abs

/**
 * Hash-based answer lookup.
 *
 *  1. exact:   HashMap<normalised key, entry>     -> O(1)
 *  2. morph:   plural/singular variants            -> O(1) each
 *  3. fuzzy:   symmetric-delete index (SymSpell), two maps:
 *              pre: HashMap<delete-variant of key's first 8 chars, key ids>
 *              suf: HashMap<delete-variant of key's last 8 chars,  key ids>
 *              A candidate must hit both maps (kills shared-prefix floods),
 *              then is verified with a bounded OSA edit distance.
 */
class AnswerIndex(entries: List<AnswerEntry>, maxIndexEdit: Int = 2) {

    class Hit(val entry: Int, val distance: Int, val key: String)

    private val exact = HashMap<String, Int>(entries.size * 3)
    private val fuzzyKeys = ArrayList<String>(entries.size * 2)
    private val fuzzyEntry = ArrayList<Int>(entries.size * 2)
    private val pre = HashMap<String, IntBag>()
    private val suf = HashMap<String, IntBag>()
    val depth: Int
    val keyCount: Int get() = exact.size

    init {
        val seenCompact = HashSet<String>()
        entries.forEachIndexed { i, e ->
            addVariants(e.name, i, seenCompact)
            for (a in e.aliases) addVariants(a, i, seenCompact)
        }
        depth = when {
            fuzzyKeys.size <= 6000 -> maxIndexEdit.coerceIn(0, 2)
            else -> maxIndexEdit.coerceIn(0, 1)
        }
        if (depth > 0) {
            for (ki in fuzzyKeys.indices) {
                val k = fuzzyKeys[ki]
                for (d in Edits.deletes(k.take(PREFIX), depth)) pre.getOrPut(d) { IntBag() }.add(ki)
                for (d in Edits.deletes(k.takeLast(PREFIX), depth)) suf.getOrPut(d) { IntBag() }.add(ki)
            }
        }
    }

    private fun addVariants(raw: String, entry: Int, seenCompact: HashSet<String>) {
        val k = Norm.key(raw)
        if (k.isEmpty()) return
        if (!exact.containsKey(k)) exact[k] = entry
        val c = Norm.compact(k)
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
        exact[qc]?.let { return Hit(it, 0, qc) }
        for (v in Norm.morphVariants(qc)) exact[v]?.let { return Hit(it, 0, v) }

        val tol = minOf(maxEdit, toleranceFor(qc.length), depth)
        if (tol <= 0) return null

        val preBags = Edits.deletes(qc.take(PREFIX), tol).mapNotNull { pre[it] }
        val sufBags = Edits.deletes(qc.takeLast(PREFIX), tol).mapNotNull { suf[it] }
        if (preBags.isEmpty() || sufBags.isEmpty()) return null
        // mark the smaller side, scan the larger side
        val preTotal = preBags.sumOf { it.size }
        val sufTotal = sufBags.sumOf { it.size }
        val (small, large) = if (preTotal <= sufTotal) preBags to sufBags else sufBags to preBags
        val marked = HashSet<Int>()
        for (bag in small) for (j in 0 until bag.size) marked += bag[j]

        var best: Hit? = null
        val tried = HashSet<Int>()
        for (bag in large) {
            for (j in 0 until bag.size) {
                val ki = bag[j]
                if (ki !in marked || !tried.add(ki)) continue
                val key = fuzzyKeys[ki]
                val dist = Edits.osa(qc, key, tol)
                if (dist > tol) continue
                val entry = fuzzyEntry[ki]
                val b = best
                if (b == null || dist < b.distance || (dist == b.distance && entry < b.entry)) {
                    best = Hit(entry, dist, key)
                }
            }
        }
        return best
    }

    /**
     * Autocorrect hint for a rejected input: nearest key within a looser bound
     * (≈ 1 edit per 3 chars, max 3). Linear scan; only runs on a miss.
     */
    fun suggest(input: String): Hit? {
        val qc = Norm.compact(Norm.key(input))
        if (qc.length < 3) return null
        val bound = (qc.length / 3).coerceIn(1, 3)
        var best: Hit? = null
        for (ki in fuzzyKeys.indices) {
            val key = fuzzyKeys[ki]
            if (abs(key.length - qc.length) > bound) continue
            val d = Edits.osa(qc, key, bound)
            if (d == 0 || d > bound) continue
            val e = fuzzyEntry[ki]
            val b = best
            if (b == null || d < b.distance || (d == b.distance && e < b.entry)) best = Hit(e, d, key)
        }
        return best
    }

    companion object {
        const val PREFIX = 8

        /** Allowed typos by input length (compact form). */
        fun toleranceFor(len: Int): Int = when {
            len <= 3 -> 0
            len <= 5 -> 1
            else -> 2
        }
    }
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
