package app.coredump.data

import app.coredump.core.Norm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The measurements behind [app.coredump.core.Fame] (pure parts: addresses and parsing; the app and the CI check send
 * exactly these). All free, no keys:
 *  - Wikidata: the English Wikipedia title of the item,
 *  - Wikimedia REST: page views of that article, last 60 days,
 *  - Wikipedia search: articles mentioning the exact name; articles containing a one-word answer,
 *  - Deezer: fans of an artist / popularity of a song,
 *  - Open Library: readers who logged a book,
 *  - TVmaze: popularity of a show.
 */
object FameCheck {
    private val json = Json { ignoreUnknownKeys = true }
    const val PAGEVIEWS = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user"
    const val DAYS = 60

    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull

    fun enc(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') append(c)
            else append('%').append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1).uppercase())
        }
    }

    // ---- English Wikipedia title of an item

    fun titleParams(qid: String) = listOf("action" to "wbgetentities", "ids" to qid, "props" to "sitelinks",
        "sitefilter" to "enwiki", "format" to "json")

    fun parseTitle(body: String, qid: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject?.get(qid)?.jsonObject
            ?.get("sitelinks")?.jsonObject?.get("enwiki")?.jsonObject?.str("title")
    }.getOrNull()

    // ---- page views

    /** Civil date yyyymmdd of a day number (days since 1970-01-01). */
    fun ymd(epochDay: Long): String {
        val z = epochDay + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return y.toString().padStart(4, '0') + m.toString().padStart(2, '0') + d.toString().padStart(2, '0')
    }

    /** Daily views of [title] for the [DAYS] days ending two days before [nowMs] (the newest days are not in yet). */
    fun pageviewsUrl(title: String, nowMs: Long): String {
        val today = nowMs / 86_400_000L
        val end = ymd(today - 2)
        val start = ymd(today - 1 - DAYS)
        return "$PAGEVIEWS/${enc(title.replace(' ', '_'))}/daily/$start/$end"
    }

    /** Average views per day (days without data count as 0). */
    fun parsePageviews(body: String): Int? = runCatching {
        val items = json.parseToJsonElement(body).jsonObject["items"]?.jsonArray ?: return null
        val sum = items.sumOf { it.jsonObject["views"]?.jsonPrimitive?.longOrNull ?: 0L }
        (sum / DAYS).toInt()
    }.getOrNull()

    // ---- mentions

    /** English Wikipedia articles containing [phrase] exactly (quoted) or, [exact] false, all its words. */
    fun hitsParams(phrase: String, exact: Boolean): List<Pair<String, String>> {
        val p = phrase.replace('"', ' ').trim()
        return listOf("action" to "query", "list" to "search", "srsearch" to (if (exact) "\"$p\"" else p), "srinfo" to "totalhits",
            "srlimit" to "1", "srprop" to "", "srnamespace" to "0", "format" to "json", "formatversion" to "2")
    }

    fun parseHits(body: String): Int? = runCatching {
        json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("searchinfo")?.jsonObject?.get("totalhits")?.jsonPrimitive?.intOrNull
    }.getOrNull()

    // ---- genre measures

    private fun close(a: String, b: String): Boolean {
        val x = Norm.compact(Norm.key(a))
        val y = Norm.compact(Norm.key(b))
        return x.isNotEmpty() && (x == y || (x.length >= 5 && (y.startsWith(x) || x.startsWith(y))))
    }

    fun deezerArtistUrl(name: String) = "https://api.deezer.com/search/artist?q=${enc(name)}&limit=10"
    fun deezerTrackUrl(name: String) = "https://api.deezer.com/search/track?q=${enc(name)}&limit=10"
    fun openLibraryUrl(title: String) = "https://openlibrary.org/search.json?title=${enc(title)}&limit=5&fields=title,readinglog_count,edition_count"
    fun tvmazeUrl(name: String) = "https://api.tvmaze.com/search/shows?q=${enc(name)}"

    /** Fans of the artist named [name] (the one with that name and most fans). */
    fun parseDeezerFans(body: String, name: String): Int? = runCatching {
        json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { close(name, it.str("name").orEmpty()) }
            .maxOfOrNull { it["nb_fan"]?.jsonPrimitive?.longOrNull ?: 0L }?.toInt()
    }.getOrNull()

    /** Deezer popularity (0–1,000,000) of the song called [name] (best of its versions). */
    fun parseDeezerRank(body: String, name: String): Int? = runCatching {
        json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { close(name, it.str("title_short") ?: it.str("title").orEmpty()) }
            .maxOfOrNull { it["rank"]?.jsonPrimitive?.longOrNull ?: 0L }?.toInt()
    }.getOrNull()

    /** Readers (reading-log entries) of the book called [title]. */
    fun parseReaders(body: String, title: String): Int? = runCatching {
        json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { close(title, it.str("title").orEmpty()) }
            .maxOfOrNull { it["readinglog_count"]?.jsonPrimitive?.intOrNull ?: 0 }
    }.getOrNull()

    /** TVmaze popularity (0–100) of the show called [name]. */
    fun parseTvWeight(body: String, name: String): Int? = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { it.jsonObject["show"]?.jsonObject }
            .filter { close(name, it.str("name").orEmpty()) }
            .maxOfOrNull { it["weight"]?.jsonPrimitive?.intOrNull ?: 0 }
    }.getOrNull()
}
