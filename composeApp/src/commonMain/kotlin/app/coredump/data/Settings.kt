package app.coredump.data

import app.coredump.core.Difficulty
import app.coredump.core.GameConfig
import app.coredump.platform.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * rounds: prompts per game (count). seconds: time per prompt (s), 0 = no limit.
 * glow: text glow strength, 0..1.5 (dimensionless multiplier).
 */
data class Settings(
    val handle: String = "ANON",
    val rounds: Int = 7,
    val seconds: Int = 25,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val topics: Set<String> = emptySet(),
    val useWikidata: Boolean = true,
    val useWikipedia: Boolean = true,
    val remoteUrls: List<String> = emptyList(),
    val scanlines: Boolean = true,
    val flicker: Boolean = true,
    val glow: Float = 1f,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,
) {
    fun gameConfig() = GameConfig(rounds, seconds, difficulty, topics)
}

object SettingsStore {
    private const val FILE = "settings.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Settings = runCatching {
        val o = json.parseToJsonElement(Platform.readText(FILE) ?: return Settings()).jsonObject
        val d = Settings()
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        fun i(k: String) = o[k]?.jsonPrimitive?.intOrNull
        fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull
        fun list(k: String) = o[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        Settings(
            handle = s("handle") ?: d.handle,
            rounds = (i("rounds") ?: d.rounds).coerceIn(1, 50),
            seconds = (i("seconds") ?: d.seconds).coerceIn(0, 300),
            difficulty = s("difficulty")?.let { Difficulty.byName(it) } ?: d.difficulty,
            topics = list("topics")?.toSet() ?: d.topics,
            useWikidata = b("useWikidata") ?: d.useWikidata,
            useWikipedia = b("useWikipedia") ?: d.useWikipedia,
            remoteUrls = list("remoteUrls") ?: d.remoteUrls,
            scanlines = b("scanlines") ?: d.scanlines,
            flicker = b("flicker") ?: d.flicker,
            glow = o["glow"]?.jsonPrimitive?.floatOrNull ?: d.glow,
            haptics = b("haptics") ?: d.haptics,
            reduceMotion = b("reduceMotion") ?: d.reduceMotion,
        )
    }.getOrElse { Settings() }

    fun save(s: Settings) {
        val o = buildJsonObject {
            put("handle", s.handle)
            put("rounds", s.rounds)
            put("seconds", s.seconds)
            put("difficulty", s.difficulty.name)
            put("topics", JsonArray(s.topics.map { JsonPrimitive(it) }))
            put("useWikidata", s.useWikidata)
            put("useWikipedia", s.useWikipedia)
            put("remoteUrls", JsonArray(s.remoteUrls.map { JsonPrimitive(it) }))
            put("scanlines", s.scanlines)
            put("flicker", s.flicker)
            put("glow", s.glow)
            put("haptics", s.haptics)
            put("reduceMotion", s.reduceMotion)
        }
        runCatching { Platform.writeText(FILE, o.toString()) }
    }
}

/** Recently played prompt ids + best score, so games avoid repeats. */
object History {
    private const val FILE = "history.json"
    private const val KEEP = 400
    private val json = Json { ignoreUnknownKeys = true }

    var recent: List<String> = emptyList(); private set
    var best: Int = 0; private set
    var games: Int = 0; private set

    fun load() {
        runCatching {
            val o = json.parseToJsonElement(Platform.readText(FILE) ?: return).jsonObject
            recent = o["recent"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            best = o["best"]?.jsonPrimitive?.intOrNull ?: 0
            games = o["games"]?.jsonPrimitive?.intOrNull ?: 0
        }
    }

    fun record(promptIds: List<String>, points: Int) {
        recent = (promptIds + recent).distinct().take(KEEP)
        if (points > best) best = points
        games += 1
        val o = buildJsonObject {
            put("recent", JsonArray(recent.map { JsonPrimitive(it) }))
            put("best", best)
            put("games", games)
        }
        runCatching { Platform.writeText(FILE, o.toString()) }
    }
}
