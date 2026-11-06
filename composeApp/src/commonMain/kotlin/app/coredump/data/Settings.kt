package app.coredump.data

import app.coredump.core.Difficulty
import app.coredump.core.GameConfig
import app.coredump.core.Tier
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
    /** Text size multiplier (dimensionless): 0.9 / 1.0 / 1.15 / 1.3. */
    val textScale: Float = 1f,
    /** Check unlisted answers online (Wikidata). */
    val verifyOnline: Boolean = true,
    /** Theme style name (TERMINAL / PAPER / NEON / CLEAN / PIXEL) and palette id within it. */
    val theme: String = "TERMINAL",
    val palette: String = "amber",
    /** Include hyper-specific lists (players from one country, sub-genres, obscure species…). */
    val niche: Boolean = false,
    /** A prompt that was shown is not shown again for this many days (0 = no cooldown). */
    val cooldownDays: Int = 2,
) {
    fun gameConfig() = GameConfig(rounds, seconds, difficulty, topics, niche)
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
            // categories were renamed: keep only names that still exist
            topics = list("topics")?.filter { it in app.coredump.core.Topics.keys }?.toSet() ?: d.topics,
            useWikidata = b("useWikidata") ?: d.useWikidata,
            useWikipedia = b("useWikipedia") ?: d.useWikipedia,
            remoteUrls = list("remoteUrls") ?: d.remoteUrls,
            scanlines = b("scanlines") ?: d.scanlines,
            flicker = b("flicker") ?: d.flicker,
            glow = o["glow"]?.jsonPrimitive?.floatOrNull ?: d.glow,
            haptics = b("haptics") ?: d.haptics,
            reduceMotion = b("reduceMotion") ?: d.reduceMotion,
            textScale = (o["textScale"]?.jsonPrimitive?.floatOrNull ?: d.textScale).coerceIn(0.8f, 1.4f),
            verifyOnline = b("verifyOnline") ?: d.verifyOnline,
            theme = s("theme") ?: d.theme,
            palette = s("palette") ?: d.palette,
            niche = b("niche") ?: d.niche,
            cooldownDays = (i("cooldownDays") ?: d.cooldownDays).coerceIn(0, 30),
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
            put("textScale", s.textScale)
            put("verifyOnline", s.verifyOnline)
            put("theme", s.theme)
            put("palette", s.palette)
            put("niche", s.niche)
            put("cooldownDays", s.cooldownDays)
        }
        runCatching { Platform.writeText(FILE, o.toString()) }
    }
}

/** One finished run. points: total for the run; tiers: my tier per prompt (in order). */
data class RunRecord(
    val at: Long,
    val mode: String,
    val points: Int,
    val tiers: List<Tier>,
    val topics: List<String>,
    val daily: Int?,
    val opponent: String?,
    val oppPoints: Int?,
)

/** Run history (for stats), recently played prompt ids (to avoid repeats), best score. */
object History {
    private const val FILE = "history.json"
    private const val KEEP_IDS = 600
    private const val KEEP_RUNS = 300
    private val json = Json { ignoreUnknownKeys = true }

    var recent: List<String> = emptyList(); private set
    /** Prompt id -> epoch ms it was last shown (for the "don't repeat for N days" cooldown). */
    var seen: Map<String, Long> = emptyMap(); private set
    var runs: List<RunRecord> = emptyList(); private set
    val best: Int get() = runs.maxOfOrNull { it.points } ?: 0
    val games: Int get() = runs.size

    fun load() {
        runCatching {
            val o = json.parseToJsonElement(Platform.readText(FILE) ?: return).jsonObject
            recent = o["recent"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            seen = o["seen"]?.jsonObject?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.toLongOrNull()?.let { k to it } }?.toMap().orEmpty()
            runs = o["runs"]?.jsonArray?.mapNotNull { el ->
                runCatching {
                    val r = el.jsonObject
                    RunRecord(
                        at = r["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@runCatching null,
                        mode = r["m"]?.jsonPrimitive?.contentOrNull ?: "solo",
                        points = r["p"]?.jsonPrimitive?.intOrNull ?: 0,
                        tiers = r["t"]?.jsonPrimitive?.contentOrNull.orEmpty().split(',').mapNotNull { Tier.byName(it) },
                        topics = r["tp"]?.jsonPrimitive?.contentOrNull.orEmpty().split(',').filter { it.isNotBlank() },
                        daily = r["d"]?.jsonPrimitive?.intOrNull,
                        opponent = r["o"]?.jsonPrimitive?.contentOrNull,
                        oppPoints = r["op"]?.jsonPrimitive?.intOrNull,
                    )
                }.getOrNull()
            }.orEmpty()
        }
    }

    fun playedDaily(n: Int): RunRecord? = runs.firstOrNull { it.daily == n }

    /** A prompt was put on screen (kept 30 days, newest 3,000). */
    fun markSeen(id: String) {
        val now = Platform.epochMillis()
        val keep = seen.filterValues { now - it < 30L * 24 * 3600 * 1000 }
        seen = (if (keep.size > 3000) keep.entries.sortedByDescending { it.value }.take(3000).associate { it.key to it.value } else keep) + (id to now)
        save()
    }

    fun recordRun(
        promptIds: List<String>,
        tiers: List<Tier>,
        mode: String,
        daily: Int?,
        topics: List<String>,
        opponent: String? = null,
        oppPoints: Int? = null,
        points: Int = tiers.sumOf { it.points },
    ) {
        recent = (promptIds + recent).distinct().take(KEEP_IDS)
        // a daily run counts once: replays of the same day are not recorded as daily
        val d = if (daily != null && playedDaily(daily) == null) daily else null
        val rec = RunRecord(Platform.epochMillis(), if (daily != null && d == null) "solo" else mode,
            points, tiers, topics, d, opponent, oppPoints)
        runs = (listOf(rec) + runs).take(KEEP_RUNS)
        save()
    }

    private fun save() {
        val o = buildJsonObject {
            put("recent", JsonArray(recent.map { JsonPrimitive(it) }))
            put("seen", buildJsonObject { for ((k, v) in seen) put(k, v) })
            put("runs", JsonArray(runs.map { r ->
                buildJsonObject {
                    put("at", r.at); put("m", r.mode); put("p", r.points)
                    put("t", r.tiers.joinToString(",") { it.name })
                    put("tp", r.topics.joinToString(","))
                    r.daily?.let { put("d", it) }
                    r.opponent?.let { put("o", it) }
                    r.oppPoints?.let { put("op", it) }
                }
            }))
        }
        runCatching { Platform.writeText(FILE, o.toString()) }
    }
}
