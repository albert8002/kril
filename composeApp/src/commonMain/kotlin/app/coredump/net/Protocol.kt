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

/** Join info carried by the QR code: coredump://join?h=<ip>&p=<port>&k=<pin>&n=<host name> */
data class JoinTicket(val host: String, val port: Int, val pin: String, val hostName: String) {
    fun toUri(): String = "coredump://join?h=$host&p=$port&k=$pin&n=${encode(hostName)}"

    companion object {
        fun parse(raw: String): JoinTicket? {
            val s = raw.trim()
            if (s.startsWith("coredump://join?")) {
                val q = s.substringAfter('?').split('&').mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to decode(it.substring(i + 1))
                }.toMap()
                val h = q["h"] ?: return null
                val p = q["p"]?.toIntOrNull() ?: return null
                return JoinTicket(h, p, q["k"].orEmpty(), q["n"] ?: "HOST")
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
    data class Hello(val name: String, val pin: String, val version: Int = PROTOCOL_VERSION) : Msg()
    data class Welcome(val hostName: String) : Msg()
    data class Reject(val reason: String) : Msg()
    data class Start(val rounds: Int, val seconds: Int, val difficulty: String) : Msg()
    data class Round(val i: Int, val text: String, val topic: String, val size: Int, val seconds: Int) : Msg()
    data class Answer(val i: Int, val text: String?, val elapsedMs: Long) : Msg()
    data class Locked(val i: Int) : Msg()
    data class Result(val i: Int, val host: PlayView, val guest: PlayView, val ladder: List<TierGroup>) : Msg()
    data class Ready(val i: Int) : Msg()
    data object Over : Msg()
    data class Bye(val reason: String) : Msg()

    fun encode(): String = toJson().toString()

    private fun toJson(): JsonObject = when (this) {
        is Hello -> obj("hello") { put("name", name); put("pin", pin); put("v", version) }
        is Welcome -> obj("welcome") { put("name", hostName) }
        is Reject -> obj("reject") { put("reason", reason) }
        is Start -> obj("start") { put("rounds", rounds); put("sec", seconds); put("diff", difficulty) }
        is Round -> obj("round") { put("i", i); put("q", text); put("t", topic); put("n", size); put("sec", seconds) }
        is Answer -> obj("answer") { put("i", i); put("a", text?.let { JsonPrimitive(it) } ?: JsonNull); put("ms", elapsedMs) }
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
        Over -> obj("over") {}
        is Bye -> obj("bye") { put("reason", reason) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(line: String): Msg? = runCatching {
            val o = json.parseToJsonElement(line).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            fun i(k: String) = o[k]?.jsonPrimitive?.intOrNull ?: 0
            when (s("k")) {
                "hello" -> Hello(s("name"), s("pin"), i("v"))
                "welcome" -> Welcome(s("name"))
                "reject" -> Reject(s("reason"))
                "start" -> Start(i("rounds"), i("sec"), s("diff"))
                "round" -> Round(i("i"), s("q"), s("t"), i("n"), i("sec"))
                "answer" -> Answer(i("i"), o["a"]?.jsonPrimitive?.contentOrNull, o["ms"]?.jsonPrimitive?.long ?: 0L)
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
                "over" -> Over
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
        }

        private fun unplay(o: JsonObject): PlayView = PlayView(
            player = o["p"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            input = o["in"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            canonical = o["c"]?.jsonPrimitive?.contentOrNull,
            tier = Tier.byName(o["t"]?.jsonPrimitive?.contentOrNull.orEmpty()) ?: Tier.SEGFAULT,
            elapsedMs = o["ms"]?.jsonPrimitive?.long ?: 0L,
        )
    }
}
