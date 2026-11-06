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
 * Results of online answer checks, kept on the device: the same answer to the same prompt is never looked up twice.
 * Found answers are kept 60 days, "not found" 14 days (lists change). Network failures are never stored.
 * key: "<prompt id>|<normalised answer>".
 */
object VerifyCache {
    private const val FILE = "verify_cache.json"
    private const val MAX = 3000
    private const val FOUND_MS = 60L * 24 * 3600 * 1000
    private const val MISSING_MS = 14L * 24 * 3600 * 1000

    /** label/links null = checked and not found. at: epoch ms of the check. */
    private class Entry(val label: String?, val links: Int, val at: Long)

    /** A stored outcome; [result] null = "not found". */
    class Hit(val result: WikidataTemplates.Verified?)

    private var map: LinkedHashMap<String, Entry>? = null

    private fun loaded(): LinkedHashMap<String, Entry> {
        map?.let { return it }
        val m = LinkedHashMap<String, Entry>()
        runCatching {
            val rows = Json.parseToJsonElement(Platform.readText(FILE) ?: return@runCatching).jsonObject["e"]?.jsonArray.orEmpty()
            for (r in rows) {
                val a = r.jsonArray
                val k = a.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: continue
                m[k] = Entry(a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }, a.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0,
                    a.getOrNull(3)?.jsonPrimitive?.longOrNull ?: 0L)
            }
        }
        map = m
        return m
    }

    fun get(key: String): Hit? {
        val e = loaded()[key] ?: return null
        val age = Platform.epochMillis() - e.at
        if (age > (if (e.label != null) FOUND_MS else MISSING_MS)) return null
        return Hit(e.label?.let { WikidataTemplates.Verified(it, e.links) })
    }

    fun put(key: String, v: WikidataTemplates.Verified?) {
        val m = loaded()
        m.remove(key)
        m[key] = Entry(v?.label, v?.links ?: 0, Platform.epochMillis())
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
                    JsonArray(listOf(JsonPrimitive(k), e.label?.let { JsonPrimitive(it) } ?: JsonPrimitive(""),
                        JsonPrimitive(e.links), JsonPrimitive(e.at)))
                }))
            }
            Platform.writeText(FILE, o.toString())
        }
    }
}
