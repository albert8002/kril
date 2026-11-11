package app.coredump.game

import app.coredump.core.Difficulty
import app.coredump.core.GameConfig
import app.coredump.net.Msg
import app.coredump.net.RelayLink
import app.coredump.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Ranked: random online opponents, fixed rules, rating (Elo). Everyone looking for a game announces themselves on one
 * shared relay channel every 2 s ("seek": id, name, rating, own room code). Of two seekers close enough in rating, the
 * one with the smaller id proposes; the other accepts the first proposal it gets. The proposer hosts in its own room,
 * the other joins it like a friend would. The search window widens the longer one waits.
 */

object Ranked {
    const val TOPIC = "coredump/v1/ranked/q"
    /** The rules of every ranked match (cannot be changed): 5 prompts, 25 s, normal typos, speed penalty on. */
    val CONFIG = GameConfig(rounds = 5, secondsPerRound = 25, difficulty = Difficulty.NORMAL, topics = emptySet(), niche = false,
        speedPenalty = true, ranked = true, peopleLists = true, peopleNiche = 0f, peopleMax = 20)
    const val K_RANKED = 32
    const val K_FRIENDLY = 16

    /** New rating after a game: [score] 1 win, 0.5 draw, 0 loss. */
    fun elo(mine: Int, theirs: Int, score: Double, k: Int): Int {
        val expected = 1.0 / (1.0 + 10.0.pow((theirs - mine) / 400.0))
        return (mine + k * (score - expected)).roundToInt().coerceAtLeast(100)
    }

    /** Rating difference accepted after waiting [ms]: 150, growing 60 per second, at most 1,500. */
    fun window(ms: Long): Int = (150 + 60 * (ms / 1000)).toInt().coerceAtMost(1500)
}

/** Who I was matched with: [host] = I host in my room; else I join [oppCode] on relay [broker]. */
data class RankedMatch(val host: Boolean, val oppName: String, val oppCode: String, val broker: Int, val oppElo: Int)

class Matchmaker(
    private val scope: CoroutineScope,
    private val myName: String,
    private val myCode: String,
    private val myBroker: Int,
    private val myElo: Int,
    private val onMatch: (RankedMatch) -> Unit,
) {
    data class Status(val text: String = "connecting…", val waiting: Int = 0, val searching: Boolean = true, val error: String? = null)

    private class Seeker(val id: String, val name: String, val code: String, val broker: Int, val elo: Int, var seen: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val id = Meltdown.newId() + Meltdown.newId()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val seekers = HashMap<String, Seeker>()
    private var job: Job? = null
    private var link: RelayLink? = null
    private var proposedTo: String? = null
    private var proposedAt = 0L
    private var done = false

    private fun body(t: String, b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        Msg.Party(buildJsonObject { put("t", t); put("from", id); b() }.toString())

    private fun parse(m: Msg): Map<String, String>? {
        if (m !is Msg.Party) return null
        return runCatching {
            json.parseToJsonElement(m.body).jsonObject.mapValues { (_, v) -> runCatching { v.jsonPrimitive.contentOrNull.orEmpty() }.getOrDefault("") }
        }.getOrNull()
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            val opened = runCatching { RelayLink.openShared(scope, Ranked.TOPIC, 0) }.getOrNull()
                ?: runCatching { RelayLink.openShared(scope, Ranked.TOPIC, null) }.getOrNull()
            if (opened == null) {
                _status.value = Status("no connection", error = "can't reach the matchmaking relay. internet?", searching = false)
                return@launch
            }
            val l = opened.first
            link = l
            val since = Platform.epochMillis()
            _status.value = Status("searching…")
            // announce myself every 2 s and look for a fair opponent
            launch {
                while (!done) {
                    l.send(body("seek") { put("n", myName); put("code", myCode); put("b", myBroker.toString()); put("e", myElo.toString()) })
                    tick(since)
                    delay(2_000)
                }
            }
            for (m in l.incoming) {
                val o = parse(m) ?: continue
                val from = o["from"].orEmpty()
                if (from == id || done) continue
                when (o["t"]) {
                    "seek" -> {
                        val s = seekers[from]
                        if (s != null) s.seen = Platform.epochMillis()
                        else seekers[from] = Seeker(from, o["n"].orEmpty().ifBlank { "PLAYER" }.take(16), o["code"].orEmpty(),
                            o["b"]?.toIntOrNull() ?: 0, o["e"]?.toIntOrNull() ?: 1000, Platform.epochMillis())
                    }
                    "propose" -> if (o["to"] == id && (proposedTo == null || proposedTo == from)) {
                        // first proposal wins: accept and join them
                        val code = o["code"].orEmpty()
                        if (code.isBlank()) continue
                        repeat(2) { l.send(body("accept") { put("to", from); put("n", myName); put("e", myElo.toString()) }) }
                        finish(RankedMatch(false, o["n"].orEmpty().ifBlank { "PLAYER" }.take(16), code, o["b"]?.toIntOrNull() ?: 0,
                            o["e"]?.toIntOrNull() ?: 1000))
                    }
                    "accept" -> if (o["to"] == id && proposedTo == from) {
                        val s = seekers[from]
                        finish(RankedMatch(true, o["n"].orEmpty().ifBlank { s?.name ?: "PLAYER" }.take(16), s?.code.orEmpty(), myBroker,
                            o["e"]?.toIntOrNull() ?: s?.elo ?: 1000))
                    }
                    "bye" -> { seekers.remove(from); if (proposedTo == from) proposedTo = null }
                }
            }
            if (!done) _status.value = Status("connection lost", error = "the matchmaking link dropped. try again.", searching = false)
        }
    }

    private suspend fun tick(since: Long) {
        val now = Platform.epochMillis()
        seekers.values.removeAll { now - it.seen > 7_000 }
        val waited = now - since
        _status.update { it.copy(text = "searching… ${waited / 1000}s · rating window ±${Ranked.window(waited)}", waiting = seekers.size) }
        if (proposedTo != null && now - proposedAt > 4_000) proposedTo = null
        if (proposedTo != null) return
        // the fairest opponent in the window; only the smaller id proposes (so two never propose to each other)
        val best = seekers.values.filter { it.id > id && abs(it.elo - myElo) <= Ranked.window(waited) }
            .minByOrNull { abs(it.elo - myElo) } ?: return
        proposedTo = best.id
        proposedAt = now
        link?.send(body("propose") { put("to", best.id); put("n", myName); put("code", myCode); put("b", myBroker.toString()); put("e", myElo.toString()) })
    }

    private fun finish(m: RankedMatch) {
        if (done) return
        done = true
        _status.value = Status("matched with ${m.oppName} (${m.oppElo})", searching = false)
        val l = link
        scope.launch {
            runCatching { l?.send(body("bye") { }) }
            delay(400)
            l?.close()
        }
        onMatch(m)
    }

    fun cancel() {
        if (!done) {
            done = true
            val l = link
            scope.launch { runCatching { l?.send(body("bye") { }) }; delay(300); l?.close() }
        }
        job?.cancel()
        job = null
    }
}
