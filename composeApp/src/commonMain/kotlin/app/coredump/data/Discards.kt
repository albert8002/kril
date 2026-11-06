package app.coredump.data

import app.coredump.platform.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Prompt ids the player discarded: never shown or collected again (until restored in settings). */
object Discards {
    private const val FILE = "discarded.json"

    var ids: Set<String> = emptySet()
        private set

    fun load() {
        ids = runCatching {
            Json.parseToJsonElement(Platform.readText(FILE) ?: return).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
        }.getOrElse { emptySet() }
    }

    fun add(id: String) { ids = ids + id; save() }
    fun remove(id: String) { ids = ids - id; save() }
    fun clear() { ids = emptySet(); save() }

    private fun save() {
        runCatching { Platform.writeText(FILE, JsonArray(ids.sorted().map { JsonPrimitive(it) }).toString()) }
    }
}
