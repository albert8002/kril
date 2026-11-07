package app.coredump.data

import app.coredump.platform.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Results of online answer searches, kept on the device: the same answer to the same prompt is never looked up twice.
 * Found answers are kept 60 days, "not found" 14 days (lists change). Failed lookups are never stored.
 * key: "<prompt id>|<normalised answer>". (v2 file: results of the older, weaker search are dropped.)
 */
object VerifyCache {
    private const val FILE = "verify_cache_v2.json"
    private const val OLD_FILE = "verify_cache.json"
    private const val MAX = 3000
    private const val FOUND_MS = 60L * 24 * 3600 * 1000
    private const val MISSING_MS = 14L * 24 * 3600 * 1000

    /** kind: L = list entry under another name, F = unlisted member, M = not found. at: epoch ms of the search. */
    private class Entry(val kind: Char, val label: String?, val links: Int, val at: Long)

    private var map: LinkedHashMap<String, Entry>? = null

    private fun loaded(): LinkedHashMap<String, Entry> {
        map?.let { return it }
        val m = LinkedHashMap<String, Entry>()
        runCatching { Platform.delete(OLD_FILE) }
        runCatching {
            val rows = Json.parseToJsonElement(Platform.readText(FILE) ?: return@runCatching).jsonObject["e"]?.jsonArray.orEmpty()
            for (r in rows) {
                val a = r.jsonArray
                val k = a.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                val kind = a.getOrNull(4)?.jsonPrimitive?.contentOrNull?.firstOrNull() ?: continue
                m[k] = Entry(kind, a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    a.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0, a.getOrNull(3)?.jsonPrimitive?.longOrNull ?: 0L)
            }
        }
        map = m
        return m
    }

    fun get(key: String): AnswerCheck.Outcome? {
        val e = loaded()[key] ?: return null
        val age = Platform.epochMillis() - e.at
        if (age > (if (e.kind == 'M') MISSING_MS else FOUND_MS)) return null
        return when (e.kind) {
            'L' -> e.label?.let { AnswerCheck.Outcome.Listed(it) }
            'F' -> e.label?.let { AnswerCheck.Outcome.Found(it, e.links) }
            else -> AnswerCheck.Outcome.Missing
        }
    }

    fun put(key: String, v: AnswerCheck.Outcome) {
        val e = when (v) {
            is AnswerCheck.Outcome.Listed -> Entry('L', v.name, 0, Platform.epochMillis())
            is AnswerCheck.Outcome.Found -> Entry('F', v.label, v.links, Platform.epochMillis())
            AnswerCheck.Outcome.Missing -> Entry('M', null, 0, Platform.epochMillis())
            AnswerCheck.Outcome.Unreachable -> return
        }
        val m = loaded()
        m.remove(key)
        m[key] = e
        while (m.size > MAX) m.remove(m.keys.first())
        save(m)
    }

    fun size(): Int = loaded().size

    fun clear() {
        map = LinkedHashMap()
        runCatching { Platform.delete(FILE) }
    }

    private fun save(m: Map<String, Entry>) {
        runCatching {
            val o = buildJsonObject {
                put("e", JsonArray(m.map { (k, e) ->
                    JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(e.label ?: ""), JsonPrimitive(e.links), JsonPrimitive(e.at),
                        JsonPrimitive(e.kind.toString())))
                }))
            }
            Platform.writeText(FILE, o.toString())
        }
    }
}
