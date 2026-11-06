package app.coredump.core

/**
 * Text normalisation for answer keys.
 * Same function runs on stored answers and on player input,
 * so both sides always land on the same key.
 */
object Norm {
    private val fold: Map<Char, String> = buildMap {
        fun put(chars: String, to: String) = chars.forEach { put(it, to) }
        put("àáâãäåāăą", "a"); put("æ", "ae"); put("çćĉċč", "c"); put("ďđ", "d")
        put("èéêëēĕėęě", "e"); put("ĝğġģ", "g"); put("ĥħ", "h"); put("ìíîïĩīĭįı", "i")
        put("ĳ", "ij"); put("ĵ", "j"); put("ķ", "k"); put("ĺļľŀł", "l"); put("ñńņňŉ", "n")
        put("òóôõöøōŏő", "o"); put("œ", "oe"); put("ŕŗř", "r"); put("śŝşšș", "s"); put("ß", "ss")
        put("ţťŧț", "t"); put("ùúûüũūŭůűų", "u"); put("ŵ", "w"); put("ýÿŷ", "y"); put("źżž", "z")
        put("þ", "th"); put("ð", "d")
    }

    private val articles = listOf("the ", "a ", "an ")

    /** Lowercase, fold accents, drop (parentheticals), map symbols, collapse spaces, drop leading article. */
    fun key(raw: String): String {
        val sb = StringBuilder(raw.length + 4)
        var depth = 0
        for (c0 in raw) {
            val c = c0.lowercaseChar()
            when {
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> if (depth > 0) depth--
                depth > 0 -> Unit
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c == '\'' || c == '’' || c == '`' || c == '"' || c == '“' || c == '”' -> Unit
                c == '+' -> sb.append('p')            // c++ -> cpp
                c == '#' -> sb.append("sharp")        // c# -> csharp
                c == '&' -> sb.append(" and ")
                fold.containsKey(c) -> sb.append(fold.getValue(c))
                c.isLetterOrDigit() -> sb.append(c)   // keep non-latin letters
                else -> sb.append(' ')
            }
        }
        var s = collapse(sb)
        for (a in articles) {
            if (s.startsWith(a) && s.length > a.length) { s = s.substring(a.length); break }
        }
        return s
    }

    fun compact(key: String): String = key.replace(" ", "")

    private fun collapse(sb: StringBuilder): String {
        val out = StringBuilder(sb.length)
        var space = true
        for (c in sb) {
            if (c == ' ') {
                if (!space) out.append(' ')
                space = true
            } else {
                out.append(c); space = false
            }
        }
        if (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    /** Simple singular / plural variants of a key. */
    fun morphVariants(k: String): List<String> {
        val out = ArrayList<String>(6)
        if (k.length > 3 && k.endsWith("ies")) out += k.dropLast(3) + "y"
        if (k.length > 3 && k.endsWith("es")) out += k.dropLast(2)
        if (k.length > 2 && k.endsWith("s")) out += k.dropLast(1)
        if (k.endsWith("y") && k.length > 2) out += k.dropLast(1) + "ies"
        out += k + "s"
        out += k + "es"
        return out
    }
}

/** 32-bit FNV-1a: same result on every platform (String.hashCode is not guaranteed). */
fun stableHash(s: String): Int {
    var h = 0x811C9DC5.toInt()
    for (c in s) {
        h = h xor c.code
        h *= 0x01000193
    }
    return h
}
