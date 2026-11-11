package app.coredump.net

import app.coredump.core.PlayView
import app.coredump.core.Tier
import app.coredump.core.TierGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val PROTOCOL_VERSION = 1

/**
 * Join info carried by the QR code.
 *  relay:  coredump://join?r=<ROOM>&b=<broker index>&n=<host name>
 *  direct: coredump://join?h=<ip>&p=<port>&k=<pin>&n=<host name>
 */
data class JoinTicket(
    val host: String,
    val port: Int,
    val pin: String,
    val hostName: String,
    val room: String? = null,
    val broker: Int = 0,
) {
    val isRelay: Boolean get() = room != null

    fun toUri(): String =
        if (room != null) "coredump://join?r=$room&b=$broker&n=${encode(hostName)}"
        else "coredump://join?h=$host&p=$port&k=$pin&n=${encode(hostName)}"

    companion object {
        /** Room code alphabet: no 0/O, 1/I/L confusion. */
        const val ROOM_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val ROOM_LEN = 8

        fun newRoom(rnd: kotlin.random.Random = kotlin.random.Random.Default): String =
            (1..ROOM_LEN).map { ROOM_ALPHABET[rnd.nextInt(ROOM_ALPHABET.length)] }.joinToString("")

        fun pretty(room: String) = room.take(4) + "-" + room.drop(4)

        fun parse(raw: String): JoinTicket? {
            val s = raw.trim()
            if (s.startsWith("coredump://join?")) {
                val q = s.substringAfter('?').split('&').mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to decode(it.substring(i + 1))
                }.toMap()
                q["r"]?.let { r ->
                    val room = r.uppercase().filter { it in ROOM_ALPHABET }
                    if (room.length != ROOM_LEN) return null
                    return JoinTicket("", 0, "", q["n"] ?: "HOST", room, q["b"]?.toIntOrNull() ?: 0)
                }
                val h = q["h"] ?: return null
                val p = q["p"]?.toIntOrNull() ?: return null
                return JoinTicket(h, p, q["k"].orEmpty(), q["n"] ?: "HOST")
            }
            // typed room code: "K7Q2-M9XA" (optionally "/1" broker suffix)
            val code = s.uppercase().substringBefore('/').filter { it.isLetterOrDigit() }
            if (code.length == ROOM_LEN && code.all { it in ROOM_ALPHABET }) {
                return JoinTicket("", 0, "", "HOST", code, s.substringAfter('/', "0").trim().toIntOrNull() ?: 0)
            }
            // manual form: "192.168.1.5:47000 1234"
            val m = Regex("""^\s*([0-9a-zA-Z.\-]+):(\d{2,5})\s+(\d{3,8})\s*$""").find(s) ?: return null
            return JoinTicket(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3], "HOST")
        }

        private fun encode(s: String): String = buildString {
            for (c in s) if (c.isLetterOrDigit() || c == '-' || c == '_') append(c) else append('_')
        }

        private fun decode(s: String) = s
    }
}

/** Wire messages: one JSON object per line. */
sealed class Msg {
    /** [me]: the sender's own room code (friends list, 1v1 history); older versions send none. */
    data class Hello(val name: String, val pin: String, val version: Int = PROTOCOL_VERSION, val me: String = "") : Msg()
    data class Welcome(val hostName: String, val me: String = "") : Msg()
    data class Reject(val reason: String) : Msg()
    data class Start(val rounds: Int, val seconds: Int, val difficulty: String) : Msg()
    data class Round(val i: Int, val text: String, val topic: String, val size: Int, val seconds: Int) : Msg()
    /** Guest → host: no answer this round (time ran out, or [skip] = gave up). */
    data class Answer(val i: Int, val text: String?, val elapsedMs: Long, val skip: Boolean = false) : Msg()
    data class Locked(val i: Int) : Msg()
    data class Result(val i: Int, val host: PlayView, val guest: PlayView, val ladder: List<TierGroup>) : Msg()
    data class Ready(val i: Int) : Msg()
    /** Guest → host: one typed attempt (host judges; free retries until accepted or timeout). */
    /** [raw] = the guest rejected the autocorrect: judge exactly what was typed (then check online). */
    data class Attempt(val i: Int, val text: String, val elapsedMs: Long, val raw: Boolean = false) : Msg()
    /** Host → guest: verdict for an attempt. status = miss | verifying | ok | confirm (suggestion = answer to offer) | spam. */
    data class Verdict(val i: Int, val text: String, val status: String, val suggestion: String?, val more: List<String> = emptyList()) : Msg() {
        /** All "did you mean" choices (older hosts send only [suggestion]). */
        val choices: List<String> get() = more.ifEmpty { listOfNotNull(suggestion) }
    }
    /** Keep-alive both ways (relay links detect a dead peer by silence). */
    data object Ping : Msg()
    /** List sharing (both ways, in the background): ids of collected lists I have. Older versions ignore these. */
    data class LibHave(val ids: List<String>) : Msg()
    /** Lists I would like to get. */
    data class LibWant(val ids: List<String>) : Msg()
    /** One piece of a list (ranked pack JSON split into [of] pieces). */
    data class LibPart(val id: String, val part: Int, val of: Int, val data: String) : Msg()
    /** Host → guest after a [Result]: one piece of the round's full answer list (older guests ignore it). */
    data class Answers(val i: Int, val groups: List<TierGroup>) : Msg()
    data object Over : Msg()
    /** Battle royale (MELTDOWN) traffic: a JSON body of its own (see app.coredump.game.Party). */
    data class Party(val body: String) : Msg()
    data class Bye(val reason: String) : Msg()

    fun encode(): String = toJson().toString()

    private fun toJson(): JsonObject = when (this) {
        is Hello -> obj("hello") { put("name", name); put("pin", pin); put("v", version); if (me.isNotEmpty()) put("me", me) }
        is Welcome -> obj("welcome") { put("name", hostName); if (me.isNotEmpty()) put("me", me) }
        is Reject -> obj("reject") { put("reason", reason) }
        is Start -> obj("start") { put("rounds", rounds); put("sec", seconds); put("diff", difficulty) }
        is Round -> obj("round") { put("i", i); put("q", text); put("t", topic); put("n", size); put("sec", seconds) }
        is Answer -> obj("answer") { put("i", i); put("a", text?.let { JsonPrimitive(it) } ?: JsonNull); put("ms", elapsedMs); if (skip) put("skip", true) }
        is Locked -> obj("locked") { put("i", i) }
        is Result -> obj("result") {
            put("i", i); put("host", play(host)); put("guest", play(guest))
            put("ladder", buildJsonArray {
                for (g in ladder) add(buildJsonObject {
                    put("t", g.tier.name)
                    put("n", JsonArray(g.names.map { JsonPrimitive(it) }))
                })
            })
        }
        is Ready -> obj("ready") { put("i", i) }
        is Attempt -> obj("attempt") { put("i", i); put("a", text); put("ms", elapsedMs); if (raw) put("raw", true) }
        is Verdict -> obj("verdict") {
            put("i", i); put("a", text); put("st", status)
            put("sug", suggestion?.let { JsonPrimitive(it) } ?: JsonNull)
            if (more.size > 1 || (status == "confirm" && more.isNotEmpty())) put("sugs", JsonArray(more.map { JsonPrimitive(it) }))
        }
        is Answers -> obj("answers") {
            put("i", i)
            put("g", buildJsonArray {
                for (g in groups) add(buildJsonObject {
                    put("t", g.tier.name)
                    put("n", JsonArray(g.names.map { JsonPrimitive(it) }))
                })
            })
        }
        Ping -> obj("ping") {}
        is LibHave -> obj("lib_have") { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }
        is LibWant -> obj("lib_want") { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }
        is LibPart -> obj("lib_part") { put("id", id); put("n", part); put("of", of); put("d", data) }
        Over -> obj("over") {}
        is Party -> obj("party") { put("d", body) }
        is Bye -> obj("bye") { put("reason", reason) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(line: String): Msg? = runCatching {
            val o = json.parseToJsonElement(line).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            fun i(k: String) = o[k]?.jsonPrimitive?.intOrNull ?: 0
            when (s("k")) {
                "hello" -> Hello(s("name"), s("pin"), i("v"), s("me").take(12))
                "welcome" -> Welcome(s("name"), s("me").take(12))
                "reject" -> Reject(s("reason"))
                "start" -> Start(i("rounds"), i("sec"), s("diff"))
                "round" -> Round(i("i"), s("q"), s("t"), i("n"), i("sec"))
                "answer" -> Answer(i("i"), o["a"]?.jsonPrimitive?.contentOrNull, o["ms"]?.jsonPrimitive?.long ?: 0L,
                    o["skip"]?.jsonPrimitive?.booleanOrNull ?: false)
                "locked" -> Locked(i("i"))
                "result" -> Result(
                    i("i"),
                    unplay(o["host"]!!.jsonObject),
                    unplay(o["guest"]!!.jsonObject),
                    o["ladder"]?.jsonArray?.mapNotNull { el ->
                        val g = el.jsonObject
                        val t = Tier.byName(g["t"]?.jsonPrimitive?.contentOrNull.orEmpty()) ?: return@mapNotNull null
                        TierGroup(t, g["n"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty())
                    }.orEmpty(),
                )
                "ready" -> Ready(i("i"))
                "attempt" -> Attempt(i("i"), s("a"), o["ms"]?.jsonPrimitive?.long ?: 0L, o["raw"]?.jsonPrimitive?.booleanOrNull ?: false)
                "verdict" -> Verdict(i("i"), s("a"), s("st"), o["sug"]?.jsonPrimitive?.contentOrNull,
                    o["sugs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().take(8))
                "answers" -> Answers(i("i"), o["g"]?.jsonArray?.mapNotNull { el ->
                    val g = el.jsonObject
                    val t = Tier.byName(g["t"]?.jsonPrimitive?.contentOrNull.orEmpty())?.takeIf { it.isValid } ?: return@mapNotNull null
                    TierGroup(t, g["n"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty())
                }.orEmpty())
                "ping" -> Ping
                "lib_have" -> LibHave(o["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().take(2000))
                "lib_want" -> LibWant(o["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().take(200))
                "lib_part" -> LibPart(s("id"), i("n"), i("of"), s("d"))
                "over" -> Over
                "party" -> Party(s("d"))
                "bye" -> Bye(s("reason"))
                else -> null
            }
        }.getOrNull()

        private inline fun obj(kind: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
            buildJsonObject { put("k", kind); body() }

        private fun play(p: PlayView): JsonElement = buildJsonObject {
            put("p", p.player); put("in", p.input)
            put("c", p.canonical?.let { JsonPrimitive(it) } ?: JsonNull)
            put("t", p.tier.name); put("ms", p.elapsedMs)
            if (p.penalty != 0) put("pen", p.penalty)
            p.note?.let { put("n", it) }
        }

        private fun unplay(o: JsonObject): PlayView = PlayView(
            player = o["p"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            input = o["in"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            canonical = o["c"]?.jsonPrimitive?.contentOrNull,
            tier = Tier.byName(o["t"]?.jsonPrimitive?.contentOrNull.orEmpty()) ?: Tier.SEGFAULT,
            elapsedMs = o["ms"]?.jsonPrimitive?.long ?: 0L,
            penalty = o["pen"]?.jsonPrimitive?.intOrNull ?: 0,
            note = o["n"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
