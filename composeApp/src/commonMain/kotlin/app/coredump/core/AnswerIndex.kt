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
class AnswerIndex(private val entries: List<AnswerEntry>, private val people: Boolean = false) {

    class Hit(val entry: Int, val distance: Int, val key: String)

    private val exact = HashMap<String, Int>(entries.size * 4)
    private val fuzzyKeys = ArrayList<String>(entries.size * 2)
    private val fuzzyEntry = ArrayList<Int>(entries.size * 2)
    /** byLength[n] = indexes into fuzzyKeys of keys with length n. */
    private val byLength: Array<IntBag>
    val keyCount: Int get() = exact.size

    init {
        val seenCompact = HashSet<String>()
        // 1) full names and aliases (most familiar first, so a shared key belongs to the best-known answer)
        entries.forEachIndexed { i, e ->
            addVariants(e.name, i, seenCompact)
            for (a in e.aliases) addVariants(a, i, seenCompact)
        }
        // 2) looser forms, only where no full name already owns the key
        entries.forEachIndexed { i, e ->
            for (raw in listOf(e.name) + e.aliases) {
                for (v in Lenient.indexForms(raw)) {
                    addExact(v, i)
                    val vc = Norm.compact(v)
                    if (vc.length >= 5 && seenCompact.add(vc)) { fuzzyKeys += vc; fuzzyEntry += i }
                }
                if (people) for (v in Lenient.surnameForms(raw)) {
                    addExact(v, i)
                    if (v.length >= 5 && seenCompact.add(v)) { fuzzyKeys += v; fuzzyEntry += i }
                }
            }
        }
        val maxLen = fuzzyKeys.maxOfOrNull { it.length } ?: 0
        byLength = Array(maxLen + 1) { IntBag() }
        for (ki in fuzzyKeys.indices) byLength[fuzzyKeys[ki].length].add(ki)
    }

    private fun addExact(k: String, entry: Int) {
        if (k.isEmpty()) return
        if (!exact.containsKey(k)) exact[k] = entry
        val c = Norm.compact(k)
        if (c.length >= 3 && !exact.containsKey(c)) exact[c] = entry
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
        val sorted = Lenient.sortedKey(k)
        if (sorted != null && !exact.containsKey(sorted)) exact[sorted] = entry
    }

    /**
     * Exact-ish match first (distance 0: spacing, accents, articles, plurals, "River"/"Lake" words, word order,
     * St./Saint, surnames for people), then typo-tolerant match (distance > 0: the caller should confirm it).
     */
    fun lookup(input: String, maxEdit: Int): Hit? {
        val q = Norm.key(input)
        if (q.isEmpty()) return null
        val qc = Norm.compact(q)
        exact[q]?.let { return Hit(it, 0, q) }
        if (qc.isEmpty()) return null
        exact[qc]?.let { return Hit(it, 0, qc) }
        for (v in Norm.morphVariants(qc)) exact[v]?.let { return Hit(it, 0, v) }
        for (v in Lenient.inputForms(input, q)) {
            exact[v]?.let { return Hit(it, 0, v) }
            val vc = Norm.compact(v)
            exact[vc]?.let { return Hit(it, 0, vc) }
        }
        Lenient.sortedKey(q)?.let { s -> exact[s]?.let { return Hit(it, 0, s) } }

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

    /**
     * "Did you mean" choices for a rejected input (entry indexes, best first, at most [max]):
     *  1. the nearest name with typos ([suggest]),
     *  2. names that contain the typed word(s) as whole words: "mario" → "Mario Kart 3", "ferdinand" → "Carl Ferdinand
     *     Mungavo", "kart 3" → "Mario Kart 3",
     *  3. names with a word that starts with the typed word ("ferdin" → "Ferdinand …"; the last typed word for several),
     *  4. one typed word of 5+ letters one typo away from a word of a name ("ferdinant" → "… Ferdinand …").
     * Inside each group the most familiar answers come first. Only runs on a miss.
     */
    fun suggestions(input: String, max: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        suggest(input)?.let { out += it.entry }
        if (out.size < max) for (e in wordMatches(input)) { if (out.size >= max) break; out += e }
        return out.toList()
    }

    /** Words of every name and alias (built on the first miss that needs them). */
    private class Words(val tokens: Array<List<List<String>>>, val byWord: HashMap<String, IntBag>)

    private val words: Words by lazy {
        val tokens = Array(entries.size) { i ->
            val e = entries[i]
            (listOf(e.name) + e.aliases).map { n -> Norm.key(n).split(' ').filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        }
        val by = HashMap<String, IntBag>()
        for (i in tokens.indices) {
            val seen = HashSet<String>()
            for (ts in tokens[i]) for (t in ts) if (seen.add(t)) by.getOrPut(t) { IntBag() }.add(i)
        }
        Words(tokens, by)
    }

    /** Entry indexes whose name contains the typed words (see [suggestions] 2–4), best match first. */
    fun wordMatches(input: String): List<Int> {
        val q = Norm.key(input).split(' ').filter { it.isNotEmpty() }
        if (q.isEmpty() || q.sumOf { it.length } < 3) return emptyList()
        if (q.size == 1 && q[0] in fillers) return emptyList()
        val w = words
        // words in the names that may stand for the first typed word, with how loose the match is (0 = same word)
        val first = q[0]
        val heads = HashMap<String, Int>()
        if (w.byWord.containsKey(first)) heads[first] = 0
        val prefixOk = q.size == 1 && first.length >= 4
        val typoOk = first.length >= 5
        if (prefixOk || typoOk) for (k in w.byWord.keys) {
            if (k == first) continue
            if (prefixOk && k.length > first.length && k.startsWith(first)) heads[k] = minOf(heads[k] ?: 9, 1)
            else if (typoOk && abs(k.length - first.length) <= 1 && k.length >= 5 && Edits.osa(first, k, 1) <= 1) heads[k] = minOf(heads[k] ?: 9, 2)
        }
        if (heads.isEmpty()) return emptyList()
        val best = HashMap<Int, Int>()   // entry -> loosest-word level of its best match
        for ((head, level) in heads) {
            val bag = w.byWord[head] ?: continue
            for (j in 0 until bag.size) {
                val e = bag[j]
                val lv = matchLevel(w.tokens[e], q, head, level) ?: continue
                if (lv < (best[e] ?: 9)) best[e] = lv
            }
        }
        return best.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }
    }

    /** Level (0 = whole words) when [q] appears as consecutive words in one of [names], its first word read as [head]. */
    private fun matchLevel(names: List<List<String>>, q: List<String>, head: String, headLevel: Int): Int? {
        var best: Int? = null
        for (ts in names) for (start in ts.indices) {
            if (ts[start] != head || start + q.size > ts.size) continue
            var lv = headLevel
            var ok = true
            for (k in 1 until q.size) {
                val t = ts[start + k]
                val typed = q[k]
                lv = when {
                    t == typed -> lv
                    k == q.size - 1 && t.startsWith(typed) -> maxOf(lv, 1)
                    typed.length >= 5 && Edits.osa(typed, t, 1) <= 1 -> maxOf(lv, 2)
                    else -> { ok = false; lv }
                }
                if (!ok) break
            }
            if (ok && (best == null || lv < best)) best = lv
        }
        return best
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
        /** Words too common to suggest from on their own. */
        private val fillers = setOf("the", "and", "of", "for", "with", "from", "les", "los", "las", "der", "die", "das",
            "des", "del", "della", "von", "van", "une", "una", "uno")
        /** Allowed typos by input length (compact form). */
        fun toleranceFor(len: Int): Int = when {
            len <= 3 -> 0
            len <= 5 -> 1
            else -> 2
        }
    }
}

/** Looser answer forms shared by the index side and the input side. */
object Lenient {
    /** Category words people add or leave out: "River Thames" = "Thames", "Lake Garda" = "Garda". */
    private val leadWords = listOf("river", "lake", "mount", "mt", "isle of", "gulf of", "bay of", "university of",
        "republic of", "kingdom of", "state of", "city of", "planet")
    private val tailWords = listOf("river", "lake", "mountain", "mountains", "island", "islands", "sea", "ocean", "desert",
        "falls", "waterfall", "castle", "palace", "cathedral", "bridge", "tower", "stadium", "national park", "park",
        "fc", "f c", "cf", "afc", "football club", "club", "cheese", "cat", "dog", "language", "people", "sauce", "soup",
        "salad", "bread", "cake", "breed", "film", "movie", "band", "game", "video game", "series", "tv series",
        "city", "province", "state", "empire", "dynasty")
    private val swaps = listOf("saint" to "st", "st" to "saint", "mount" to "mt", "mt" to "mount", "fort" to "ft",
        "ft" to "fort", "and" to "n", "doctor" to "dr", "dr" to "doctor", "number" to "no")
    private val particles = setOf("van", "von", "de", "da", "di", "du", "del", "della", "der", "den", "le", "la", "mac",
        "mc", "bin", "ibn", "al", "el", "ter", "ten", "dos", "das", "do", "st", "saint", "o")
    private val suffixes = setOf("jr", "sr", "ii", "iii", "iv", "v", "junior", "senior")

    private fun strip(k: String): List<String> {
        val out = ArrayList<String>(4)
        for (w in leadWords) if (k.startsWith("$w ") && k.length - w.length > 3) out += k.substring(w.length + 1)
        for (w in tailWords) if (k.endsWith(" $w") && k.length - w.length > 3) out += k.substring(0, k.length - w.length - 1)
        return out
    }

    private fun swapped(k: String): List<String> {
        val toks = k.split(' ')
        val out = ArrayList<String>(2)
        for ((a, b) in swaps) if (a in toks) out += toks.joinToString(" ") { if (it == a) b else it }
        return out
    }

    /** Extra keys stored for one answer (besides its own name). */
    fun indexForms(raw: String): List<String> {
        val k = Norm.key(raw)
        if (k.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += strip(k)
        out += swapped(k)
        if (',' in raw) Norm.key(raw.substringBefore(',')).takeIf { it.length >= 3 }?.let { out += it }
        // subtitle: "Star Wars: Episode IV – A New Hope" -> "a new hope"
        for (sep in listOf(": ", " – ", " - ")) if (sep in raw) {
            val sub = Norm.key(raw.substringAfterLast(sep))
            if (sub.count { it == ' ' } >= 1 && sub.length >= 6) out += sub
        }
        out.remove(k)
        return out.toList()
    }

    /** Extra keys tried for a typed answer when the plain key missed. */
    fun inputForms(raw: String, k: String): List<String> {
        val out = LinkedHashSet<String>()
        out += strip(k)
        out += swapped(k)
        if (',' in raw) Norm.key(raw.substringBefore(',')).takeIf { it.length >= 3 }?.let { out += it }
        out.remove(k)
        return out.toList()
    }

    /** Word-order-free key ("gogh van vincent" == "vincent van gogh"), only for 2–4 word names. */
    fun sortedKey(k: String): String? {
        val toks = k.split(' ').filter { it.isNotEmpty() }
        if (toks.size !in 2..4) return null
        return "§" + toks.sorted().joinToString(" ")
    }

    /** For people: surname ("einstein"), particle + surname ("van gogh"), first + last ("john kennedy"). */
    fun surnameForms(raw: String): List<String> {
        val toks = Norm.key(raw).split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (toks.size > 1 && toks.last() in suffixes) toks.removeAt(toks.size - 1)
        if (toks.size < 2) return emptyList()
        val out = ArrayList<String>(3)
        val last = toks.last()
        if (last.length >= 3 && last !in particles) out += last
        val prev = toks[toks.size - 2]
        if (prev in particles) {
            out += "$prev $last"
            if (toks.size >= 3 && toks[toks.size - 3] in particles) out += "${toks[toks.size - 3]} $prev $last"
        }
        if (toks.size >= 3) out += "${toks.first()} $last"
        return out.filter { Norm.compact(it).length >= 3 }
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
        val built = AnswerIndex(p.entries, p.people)
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
