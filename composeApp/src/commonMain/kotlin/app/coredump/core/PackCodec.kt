package app.coredump.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Pack formats.
 *
 * Authoring format (bundled + remote):
 *   {"topic":"GEOGRAPHY","prompts":[{"id":"geo.country","q":"Name a country",
 *     "a":"United States=USA=US|China|~Liechtenstein|^Comoros"}]}
 *   - answers separated by '|', most familiar first
 *   - aliases after '=', prefix '~' = COPYPASTA (too clever), '^' = ZERO_DAY
 *
 * Ranked format (cache + remote, tiers already assigned):
 *   {"v":1,"at":<epoch ms>,"prompts":[{"id","q","t":topic,"s":source,
 *     "e":[["name","alias|alias","TIER"], ...]}]}
 */
object PackCodec {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseAuthoring(a: String): List<RawAnswer> =
        a.split('|').mapNotNull { tok0 ->
            var tok = tok0.trim()
            if (tok.isEmpty()) return@mapNotNull null
            var tooClever = false
            var zeroDay = false
            while (tok.startsWith("~") || tok.startsWith("^")) {
                if (tok[0] == '~') tooClever = true else zeroDay = true
                tok = tok.substring(1)
            }
            val parts = tok.split('=').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) null
            else RawAnswer(parts[0], parts.drop(1), tooClever, zeroDay)
        }

    /** Parses either format. [fallbackSource] tags prompts without an explicit source. */
    fun parse(text: String, fallbackSource: SourceKind, fallbackTopic: String = "MISC"): List<Prompt> {
        val root = json.parseToJsonElement(text).jsonObject
        val packTopic = root["topic"]?.jsonPrimitive?.contentOrNull ?: fallbackTopic
        val prompts = root["prompts"]?.jsonArray ?: return emptyList()
        return prompts.mapNotNull { el -> runCatching { parsePrompt(el.jsonObject, packTopic, fallbackSource) }.getOrNull() }
            .filter { it.size >= 3 }
    }

    private fun parsePrompt(o: JsonObject, packTopic: String, fallbackSource: SourceKind): Prompt? {
        val q = o["q"]?.jsonPrimitive?.contentOrNull ?: return null
        val topic = o["t"]?.jsonPrimitive?.contentOrNull ?: o["topic"]?.jsonPrimitive?.contentOrNull ?: packTopic
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: ("p." + stableHash(q).toUInt().toString(16))
        val src = o["s"]?.jsonPrimitive?.contentOrNull?.let { SourceKind.byName(it) } ?: fallbackSource
        val a = o["a"]?.jsonPrimitive?.contentOrNull
        val entries: List<AnswerEntry> = if (a != null) {
            Tiering.build(parseAuthoring(a), id)
        } else {
            val e = o["e"]?.jsonArray ?: return null
            e.mapIndexedNotNull { i, row ->
                val arr = row.jsonArray
                val name = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
                val al = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty()
                    .split('|').filter { it.isNotBlank() }
                val tier = arr.getOrNull(2)?.jsonPrimitive?.contentOrNull?.let { Tier.byName(it) }
                    ?: Tier.HEISENBUG
                AnswerEntry(name, al, tier, i)
            }
        }
        val check = o["w"]?.jsonPrimitive?.contentOrNull
        val cuts = o["k"]?.let { k -> runCatching { k.jsonArray.map { it.jsonPrimitive.content.toInt() }.toIntArray() }.getOrNull() }
        return Prompt(id, q, topic.uppercase(), src, entries, check, cuts)
    }

    fun encodeRanked(prompts: List<Prompt>, at: Long): String {
        val root = buildJsonObject {
            put("v", 1)
            put("at", at)
            put("prompts", buildJsonArray {
                for (p in prompts) add(buildJsonObject {
                    put("id", p.id)
                    put("q", p.text)
                    put("t", p.topic)
                    put("s", p.source.name)
                    p.check?.let { put("w", it) }
                    p.cuts?.let { c -> put("k", JsonArray(c.map { JsonPrimitive(it) })) }
                    put("e", buildJsonArray {
                        for (e in p.entries) add(JsonArray(listOf(
                            JsonPrimitive(e.name),
                            JsonPrimitive(e.aliases.joinToString("|")),
                            JsonPrimitive(e.tier.name),
                        )))
                    })
                })
            })
        }
        return root.toString()
    }

    /** Timestamp of a ranked cache file, or null. */
    fun cacheTime(text: String): Long? = runCatching {
        json.parseToJsonElement(text).jsonObject["at"]?.jsonPrimitive?.longOrNull
    }.getOrNull()
}
