package app.coredump.data

import app.coredump.core.Difficulty
import app.coredump.core.GameConfig
import app.coredump.core.Tier
import app.coredump.platform.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
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
    /** Sound effects on/off and volume (0..1, dimensionless). */
    val sound: Boolean = true,
    val volume: Float = 0.7f,
    /** Background music (the 7/4 theme, arranged per look) on/off and its volume (0..1, dimensionless). */
    val music: Boolean = true,
    val musicVolume: Float = 0.5f,
    /** Which song plays: "MATCH" = the active look's own variation (default), or a look's name = always that one. */
    val song: String = "MATCH",
    /** Hidden setting: crude tier and rank names instead of the plain ones. */
    val spicyNames: Boolean = false,
    /** Fetch screen: categories to collect (empty = all) and lists per request (count). */
    val fetchTopics: Set<String> = emptySet(),
    val fetchCount: Int = 20,
    /** Share collected lists with the other player during 1v1 (silently, in the background). */
    val shareLists: Boolean = true,
    /** The player's own names, colours, icons and icon animations for tiers and ranks (see [app.coredump.core.Custom]). */
    val custom: Map<String, app.coredump.core.Custom> = emptyMap(),
    /** Looks (style names) switched to ADHD-fy: more motion, more colour, flourishes everywhere. */
    val hyper: Set<String> = emptySet(),
    /** The travel animation when points are scored (accelerate, count, slow down). */
    val scoreRuns: Boolean = true,
    /** My own online room code (made once, kept): hosting always uses it, so a code sent to friends stays valid. */
    val room: String = "",
    /** Relay that last worked for [room]. */
    val roomBroker: Int = 0,
    /** Friends: people I played or added by code. */
    val friends: List<Friend> = emptyList(),
    /** MELTDOWN games finished / won. */
    val meltGames: Int = 0,
    val meltWins: Int = 0,
    /** People lists ("Name a famous French actor") in the mix at all. */
    val peopleLists: Boolean = true,
    /** People lists: 0 = only the best-known (global) ones … 1 = niche ones too (by country, by sub-job). */
    val peopleNiche: Float = 0.25f,
    /** People lists: at most this share of the prompts in a game (percent, 0–100). */
    val peopleMax: Int = 20,
    /** When the lists were last rescored (epoch ms; see the rescoring in the question bank). */
    val rescoredAt: Long = 0L,
    /** .reversed(): the most common answer scores most. */
    val reversed: Boolean = false,
    /** 1v1: once one player has answered, the other loses points for every second they still take. */
    val speedPenalty: Boolean = false,
    /** My rating for ranked and friend 1v1 (Elo, starts at 1000). */
    val elo: Int = 1000,
    /** Ranked games played / won, current and best win streak (ranked and 1v1). */
    val rankedGames: Int = 0,
    val rankedWins: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** Friend requests waiting for my answer: code → name. */
    val requests: List<Friend> = emptyList(),
) {
    fun gameConfig() = GameConfig(rounds, seconds, difficulty, topics, niche, reversed = reversed, speedPenalty = speedPenalty,
        peopleLists = peopleLists, peopleNiche = peopleNiche, peopleMax = peopleMax)
}

object SettingsStore {
    private const val FILE = "settings.json"
    /** Longest own tier / rank name (characters). */
    const val MAX_NAME = 24
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
            sound = b("sound") ?: d.sound,
            volume = (o["volume"]?.jsonPrimitive?.floatOrNull ?: d.volume).coerceIn(0f, 1f),
            music = b("music") ?: d.music,
            musicVolume = (o["musicVolume"]?.jsonPrimitive?.floatOrNull ?: d.musicVolume).coerceIn(0f, 1f),
            song = o["song"]?.jsonPrimitive?.contentOrNull ?: d.song,
            spicyNames = b("spicyNames") ?: d.spicyNames,
            fetchTopics = list("fetchTopics")?.filter { it in app.coredump.core.Topics.keys }?.toSet() ?: d.fetchTopics,
            fetchCount = (i("fetchCount") ?: d.fetchCount).coerceIn(1, 200),
            shareLists = b("shareLists") ?: d.shareLists,
            hyper = list("hyper")?.toSet() ?: d.hyper,
            room = s("room")?.takeIf { r -> r.length == app.coredump.net.JoinTicket.ROOM_LEN } ?: d.room,
            roomBroker = o["roomBroker"]?.jsonPrimitive?.intOrNull ?: d.roomBroker,
            meltGames = o["meltGames"]?.jsonPrimitive?.intOrNull ?: d.meltGames,
            meltWins = o["meltWins"]?.jsonPrimitive?.intOrNull ?: d.meltWins,
            peopleLists = o["peopleLists"]?.jsonPrimitive?.booleanOrNull ?: d.peopleLists,
            peopleNiche = o["peopleNiche"]?.jsonPrimitive?.floatOrNull ?: d.peopleNiche,
            peopleMax = o["peopleMax"]?.jsonPrimitive?.intOrNull ?: d.peopleMax,
            rescoredAt = o["rescoredAt"]?.jsonPrimitive?.longOrNull ?: d.rescoredAt,
            reversed = o["reversed"]?.jsonPrimitive?.booleanOrNull ?: d.reversed,
            speedPenalty = o["speedPenalty"]?.jsonPrimitive?.booleanOrNull ?: d.speedPenalty,
            elo = o["elo"]?.jsonPrimitive?.intOrNull ?: d.elo,
            rankedGames = o["rankedGames"]?.jsonPrimitive?.intOrNull ?: d.rankedGames,
            rankedWins = o["rankedWins"]?.jsonPrimitive?.intOrNull ?: d.rankedWins,
            streak = o["streak"]?.jsonPrimitive?.intOrNull ?: d.streak,
            bestStreak = o["bestStreak"]?.jsonPrimitive?.intOrNull ?: d.bestStreak,
            requests = (o["requests"] as? JsonArray)?.mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                val c = f["c"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Friend(f["n"]?.jsonPrimitive?.contentOrNull ?: "", c, f["at"]?.jsonPrimitive?.longOrNull ?: 0L)
            } ?: d.requests,
            friends = o["friends"]?.jsonArray?.mapNotNull { el ->
                runCatching {
                    val f = el.jsonObject
                    Friend(f["n"]?.jsonPrimitive?.contentOrNull.orEmpty().take(24), f["c"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        f["at"]?.jsonPrimitive?.longOrNull ?: 0L, f["e"]?.jsonPrimitive?.intOrNull ?: 0, f["s"]?.jsonPrimitive?.intOrNull ?: 0)
                }.getOrNull()?.takeIf { it.name.isNotBlank() }
            } ?: d.friends,
            scoreRuns = b("scoreRuns") ?: d.scoreRuns,
            custom = o["custom"]?.jsonObject?.mapNotNull { (k, v) ->
                runCatching {
                    val c = v.jsonObject
                    k to app.coredump.core.Custom(
                        name = c["n"]?.jsonPrimitive?.contentOrNull.orEmpty().take(MAX_NAME),
                        color = c["c"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(16),
                        icon = c["i"]?.jsonPrimitive?.booleanOrNull ?: false,
                        anim = c["a"]?.jsonPrimitive?.contentOrNull ?: "NONE",
                    )
                }.getOrNull()
            }?.toMap()?.filterValues { !it.isDefault } ?: d.custom,
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
            put("sound", s.sound)
            put("volume", s.volume)
            put("music", s.music)
            put("musicVolume", s.musicVolume)
            put("song", s.song)
            put("spicyNames", s.spicyNames)
            put("fetchTopics", JsonArray(s.fetchTopics.map { JsonPrimitive(it) }))
            put("fetchCount", s.fetchCount)
            put("shareLists", s.shareLists)
            put("hyper", JsonArray(s.hyper.map { JsonPrimitive(it) }))
            put("room", s.room)
            put("roomBroker", s.roomBroker)
            put("meltGames", s.meltGames)
            put("meltWins", s.meltWins)
            put("peopleLists", s.peopleLists)
            put("peopleNiche", s.peopleNiche)
            put("peopleMax", s.peopleMax)
            put("rescoredAt", s.rescoredAt)
            put("reversed", s.reversed)
            put("speedPenalty", s.speedPenalty)
            put("elo", s.elo)
            put("rankedGames", s.rankedGames)
            put("rankedWins", s.rankedWins)
            put("streak", s.streak)
            put("bestStreak", s.bestStreak)
            put("requests", JsonArray(s.requests.map { f -> buildJsonObject { put("n", f.name); put("c", f.code); put("at", f.at) } }))
            put("friends", JsonArray(s.friends.map { f -> buildJsonObject { put("n", f.name); put("c", f.code); put("at", f.at); put("e", f.elo); put("s", f.streak) } }))
            put("scoreRuns", s.scoreRuns)
            put("custom", buildJsonObject {
                for ((k, c) in s.custom) if (!c.isDefault) put(k, buildJsonObject {
                    put("n", c.name)
                    c.color?.let { put("c", it.toString(16)) }
                    put("i", c.icon)
                    put("a", c.anim)
                })
            })
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
    /** The opponent's room code (1v1), when they sent it. */
    val oppCode: String? = null,
)

/** A friend: shown name, their room code (blank if unknown: added before codes were exchanged), when added. */
/** A friend by room code; [elo] / [streak]: their rating and win streak as last seen (0 = unknown). */
data class Friend(val name: String, val code: String, val at: Long, val elo: Int = 0, val streak: Int = 0)

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
                        oppCode = r["oc"]?.jsonPrimitive?.contentOrNull,
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
        oppCode: String? = null,
    ) {
        recent = (promptIds + recent).distinct().take(KEEP_IDS)
        // a daily run counts once: replays of the same day are not recorded as daily
        val d = if (daily != null && playedDaily(daily) == null) daily else null
        val rec = RunRecord(Platform.epochMillis(), if (daily != null && d == null) "solo" else mode,
            points, tiers, topics, d, opponent, oppPoints, oppCode)
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
                    r.oppCode?.let { put("oc", it) }
                }
            }))
        }
        runCatching { Platform.writeText(FILE, o.toString()) }
    }
}
