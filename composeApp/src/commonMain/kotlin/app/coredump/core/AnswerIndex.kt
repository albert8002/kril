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
                // people: a surname alone counts only spelled exactly ("Jordan", never "Jordon");
                // two-word forms ("van gogh", "john kennedy") may have a typo
                if (people) for (v in Lenient.surnameForms(raw)) {
                    addExact(v, i)
                    val vc = Norm.compact(v)
                    if (' ' in v && vc.length >= 5 && seenCompact.add(vc)) { fuzzyKeys += vc; fuzzyEntry += i }
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
        val bound = SuggestRules.typos(qc.length)
        if (bound <= 0) return null
        return nearest(qc, bound, allowExact = false)
    }

    /**
     * "Did you mean" choices for a rejected input (entry indexes, best first, at most [max]):
     *  1. the nearest name with typos ([suggest]),
     *  2. names that contain the typed word(s) as whole words: "mario" → "Mario Kart 3", "ferdinand" → "Carl Ferdinand
     *     Mungavo", "kart 3" → "Mario Kart 3",
     *  3. names with a word that starts with the typed word ("ferdin" → "Ferdinand …"; the last typed word for several),
     *  4. one typed word of 5+ letters one typo away from a word of a name ("ferdinant" → "… Ferdinand …").
     * Inside each group the most familiar answers come first. Only runs on a miss.
     * [promptWords]: words of the prompt itself ("Name an art museum" → art, museum); see [wordMatches].
     */
    fun suggestions(input: String, max: Int, promptWords: Set<String> = emptySet()): List<Int> {
        val out = LinkedHashSet<Int>()
        suggest(input)?.let { if (offerable(input, it.entry)) out += it.entry }
        if (out.size < max) for (e in wordMatches(input, promptWords)) { if (out.size >= max) break; out += e }
        return out.toList()
    }

    /** [SuggestRules.allowed] for any name of entry [e]. */
    private fun offerable(input: String, e: Int): Boolean {
        val en = entries[e]
        return SuggestRules.allowed(input, en.name, people) || en.aliases.any { SuggestRules.allowed(input, it, people) }
    }

    /** Words of every name and alias, and their initials (built on the first miss that needs them). */
    private class Words(val tokens: Array<List<List<String>>>, val byWord: HashMap<String, IntBag>, val byInitials: HashMap<String, IntBag>)

    private val words: Words by lazy {
        val tokens = Array(entries.size) { i ->
            val e = entries[i]
            (listOf(e.name) + e.aliases).map { n -> Norm.key(n).split(' ').filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        }
        val by = HashMap<String, IntBag>()
        val ini = HashMap<String, IntBag>()
        for (i in tokens.indices) {
            val seen = HashSet<String>()
            for (ts in tokens[i]) for (t in ts) if (seen.add(t)) by.getOrPut(t) { IntBag() }.add(i)
            val seenIni = HashSet<String>()
            val e = entries[i]
            for (raw in listOf(e.name) + e.aliases) for (a in Lenient.initials(raw)) if (seenIni.add(a)) ini.getOrPut(a) { IntBag() }.add(i)
        }
        Words(tokens, by, ini)
    }

    /**
     * Entry indexes whose name contains the typed words (see [suggestions] 2–4), best match first.
     * Not a way to browse the list:
     *  - words of the prompt itself and filler words say nothing: "museum" for "Name an art museum" brings up nothing,
     *  - a vague word that many answers share brings up only the [VAGUE_SHOWN] best-known ones, never the rare ones
     *    (so nobody types "museum" and picks the rarest from a list).
     */
    fun wordMatches(input: String, promptWords: Set<String> = emptySet()): List<Int> {
        val q = Norm.key(input).split(' ').filter { it.isNotEmpty() }
        if (q.isEmpty() || q.sumOf { it.length } < 3) return emptyList()
        if (q.none { it.length >= 3 && it !in fillers && it !in promptWords && it.trimEnd('s') !in promptWords }) return emptyList()
        val w = words
        val best = HashMap<Int, Int>()   // entry -> loosest-word level of its best match
        // initials: "gta v" → Grand Theft Auto V, "lotr" → The Lord of the Rings, "botw" → … Breath of the Wild
        val typedKey = q.joinToString(" ")
        val ac = Norm.compact(Lenient.numeralKey(typedKey) ?: typedKey)
        if (ac.length in 2..8 && q.size <= 2) w.byInitials[ac]?.let { bag -> for (j in 0 until bag.size) best[bag[j]] = 3 }
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
        for ((head, level) in heads) {
            val bag = w.byWord[head] ?: continue
            for (j in 0 until bag.size) {
                val e = bag[j]
                val lv = matchLevel(w.tokens[e], q, head, level) ?: continue
                if (lv < (best[e] ?: 9)) best[e] = lv
            }
        }
        // never a longer name from one or two words, never a name without the typed words (see SuggestRules)
        best.keys.retainAll { offerable(input, it) }
        if (best.isEmpty()) return emptyList()
        if (best.size > VAGUE) return best.keys.sorted().take(VAGUE_SHOWN)
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
        /** More matches than this = a vague word: only the [VAGUE_SHOWN] best-known are offered. */
        const val VAGUE = 3
        const val VAGUE_SHOWN = 2
        /** Words too common to suggest from on their own. */
        private val fillers = setOf("the", "and", "of", "for", "with", "from", "les", "los", "las", "der", "die", "das",
            "des", "del", "della", "von", "van", "une", "una", "uno")
        /** Allowed typos by input length (compact form): none up to 4 letters, 1 up to 7, then 2. */
        fun toleranceFor(len: Int): Int = when {
            len <= 4 -> 0
            len <= 7 -> 1
            else -> 2
        }
    }
}

/**
 * Which names may be offered as "did you mean" for a typed answer:
 *  - the name must hold every typed word, in order: the same word, a typo of it (words of 5+ letters), or for the last
 *    typed word its start (4+ letters) — "long" never brings up a name without "long";
 *  - a name with more words than were typed only from 3 typed words on: "robin" never brings up "The Batman and Robin
 *    Adventures", "batman and robin" may;
 *  - or the whole name is the typed text with a typo or two ("beatls" → "The Beatles", "spidermn" → "Spider-Man"),
 *  - or the typed text is the name's initials, 3+ letters ("lotr"),
 *  - people: one typed word only when it is exactly the surname.
 */
object SuggestRules {
    /** Typos allowed when comparing a whole name (compact length): none up to 4 letters, then 1, 2 from 8, 3 from 12. */
    fun typos(len: Int): Int = when {
        len <= 4 -> 0
        len <= 7 -> 1
        len <= 11 -> 2
        else -> 3
    }

    fun words(s: String): List<String> = Norm.key(s).split(' ').filter { it.isNotEmpty() }

    private fun wordOk(typed: String, word: String, last: Boolean): Boolean = when {
        typed == word -> true
        typed.length >= 5 && Edits.osa(typed, word, 1) <= 1 -> true
        typed.length >= 9 && Edits.osa(typed, word, 2) <= 2 -> true
        last && typed.length >= 4 && word.length > typed.length && word.startsWith(typed) -> true
        else -> false
    }

    fun allowed(typed: String, name: String, people: Boolean = false): Boolean {
        val t = words(typed)
        val o = words(name)
        if (t.isEmpty() || o.isEmpty()) return false
        val tc = t.joinToString("")
        val oc = o.joinToString("")
        // the whole name, misspelt
        val tol = typos(tc.length)
        if (tol > 0 && Edits.osa(tc, oc, tol) <= tol) return true
        // initials ("lotr", "gta5")
        if (t.size == 1 && tc.length >= 3 && o.size >= 3 && tc in Lenient.initials(name)) return true
        if (people && t.size == 1) return o.size >= 2 && o.last() == t[0]
        // longer names only from three typed words on
        if (o.size > t.size && t.size < 3) return false
        var j = 0
        for ((i, w) in t.withIndex()) {
            while (j < o.size && !wordOk(w, o[j], i == t.size - 1)) j++
            if (j >= o.size) return false
            j++
        }
        return true
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
            if (sub.count { it == ' ' } >= 1 && sub.length >= 6) { out += sub; numeralKey(sub)?.let { out += it } }
        }
        // numbers: "Final Fantasy VII" also as "final fantasy 7"
        numeralKey(k)?.let { out += it }
        out.remove(k)
        return out.toList()
    }

    /** Extra keys tried for a typed answer when the plain key missed. */
    fun inputForms(raw: String, k: String): List<String> {
        val out = LinkedHashSet<String>()
        out += strip(k)
        out += swapped(k)
        if (',' in raw) Norm.key(raw.substringBefore(',')).takeIf { it.length >= 3 }?.let { out += it }
        numeralKey(k)?.let { out += it }
        out.remove(k)
        return out.toList()
    }

    private val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8",
        "ix" to "9", "x" to "10", "xi" to "11", "xii" to "12", "xiii" to "13", "xiv" to "14", "xv" to "15", "xvi" to "16",
        "xvii" to "17", "xviii" to "18", "xix" to "19", "xx" to "20")

    /**
     * Roman numbers as digits, from the second word on: "final fantasy vii" → "final fantasy 7", "henry viii" →
     * "henry 8". The first word stays ("x men", "v for vendetta"). Null = nothing to change.
     */
    fun numeralKey(k: String): String? {
        val toks = k.split(' ')
        if (toks.size < 2) return null
        var changed = false
        val out = toks.mapIndexed { i, t -> if (i > 0) roman[t]?.also { changed = true } ?: t else t }
        return if (changed) out.joinToString(" ") else null
    }

    private val skipFirst = setOf("the", "a", "an")

    /**
     * Initials a player may type for a name of 2+ words (numbers kept whole): "Grand Theft Auto V" → "gta5" and "gta",
     * "The Lord of the Rings" → "lotr", and the same for a subtitle ("… Breath of the Wild" → "botw").
     */
    fun initials(raw: String): List<String> {
        val parts = listOf(raw) + listOf(": ", " – ", " - ").filter { it in raw }.map { raw.substringAfterLast(it) }
        val out = LinkedHashSet<String>()
        for (p in parts) {
            val k = Norm.key(p)
            var toks = (numeralKey(k) ?: k).split(' ').filter { it.isNotEmpty() }
            if (toks.size > 2 && toks.first() in skipFirst) toks = toks.drop(1)
            if (toks.size < 2) continue
            val full = toks.joinToString("") { t -> if (t.all { it.isDigit() }) t else t.take(1) }
            if (full.length in 2..8) out += full
            val words = toks.filter { t -> !t.all { it.isDigit() } }
            if (words.size >= 2 && words.size < toks.size) words.joinToString("") { it.take(1) }.takeIf { it.length in 2..8 }?.let { out += it }
        }
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
