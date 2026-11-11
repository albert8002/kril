package app.coredump.game

import app.coredump.net.Brokers
import app.coredump.net.Msg
import app.coredump.net.RelayLink
import app.coredump.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/*
 * Game invites. Everyone listens on the relay at "<my code>/i" while the app is open (their inbox). Inviting a
 * friend sends to their inbox on every relay, again every 3 s for a minute (they may be just coming back to the app),
 * until they answer. Bodies (Msg.Party JSON, "t"):
 *   inviter → inbox: invite {id, from, code, mode ("duel" | "party"), b (my relay)}
 *   inbox → inviter: seen {id} · accept {id} · decline {id}
 */

/** An invite to join [from]'s game: their room [code] on relay [broker]; [mode] "duel" (1v1) or "party" (MELTDOWN). */
data class Invite(val id: String, val from: String, val code: String, val mode: String, val broker: Int, val at: Long)

private val ijson = Json { ignoreUnknownKeys = true }

private fun ibody(t: String, b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    Msg.Party(buildJsonObject { put("t", t); b() }.toString())

private fun iparse(m: Msg): Map<String, String>? {
    if (m !is Msg.Party) return null
    return runCatching {
        ijson.parseToJsonElement(m.body).jsonObject.mapValues { (_, v) -> runCatching { v.jsonPrimitive.contentOrNull.orEmpty() }.getOrDefault("") }
    }.getOrNull()
}

/** My inbox: stays connected (reconnects by itself) and reports each new invite once. */
class Inbox(
    private val scope: CoroutineScope,
    private val room: () -> String,
    private val broker: () -> Int,
    private val onInvite: (Invite) -> Unit,
) {
    private var job: Job? = null
    private var link: RelayLink? = null
    private val seen = LinkedHashSet<String>()

    fun start() {
        if (job != null) return
        job = scope.launch {
            var fails = 0
            while (true) {
                val r = room()
                val opened = runCatching { RelayLink.open(scope, "$r/i", asHost = true, brokerIndex = broker()) }.getOrNull()
                if (opened == null) {
                    fails++
                    delay((3_000L * fails).coerceAtMost(30_000L))
                    continue
                }
                fails = 0
                val l = opened.first
                link = l
                for (m in l.incoming) {
                    val o = iparse(m) ?: continue
                    if (o["t"] != "invite") continue
                    val id = o["id"].orEmpty()
                    if (id.isBlank()) continue
                    l.send(ibody("seen") { put("id", id) })
                    if (!seen.add(id)) continue
                    if (seen.size > 200) seen.remove(seen.first())
                    val code = o["code"].orEmpty()
                    if (code.isBlank() || code == r) continue
                    onInvite(Invite(id, o["from"].orEmpty().ifBlank { "SOMEONE" }.take(24), code,
                        if (o["mode"] == "party") "party" else "duel", o["b"]?.toIntOrNull() ?: 0, Platform.epochMillis()))
                }
                link = null
                delay(1_000)
            }
        }
    }

    /** Tells the inviter (who is still sending) that I accepted or declined. */
    fun answer(inv: Invite, accept: Boolean) {
        val l = link ?: return
        scope.launch { repeat(2) { l.send(ibody(if (accept) "accept" else "decline") { put("id", inv.id) }); delay(400) } }
    }

    fun stop() {
        job?.cancel(); job = null
        link?.close(); link = null
    }
}

/** Sending one invite: to every relay, repeated until answered or a minute has passed. */
class Inviter(
    private val scope: CoroutineScope,
    val friendName: String,
    private val friendCode: String,
    private val myName: String,
    private val myCode: String,
    private val myBroker: Int,
    val mode: String,
) {
    enum class Status { SENDING, SEEN, ACCEPTED, DECLINED, NO_ANSWER }

    private val _status = MutableStateFlow(Status.SENDING)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val id = (1..10).map { "0123456789abcdefghijklmnopqrstuvwxyz"[Random.nextInt(36)] }.joinToString("")
    private val jobs = ArrayList<Job>()
    private val links = ArrayList<RelayLink>()

    fun send() {
        if (jobs.isNotEmpty()) return
        for (b in Brokers.all.indices) jobs += scope.launch {
            val l = runCatching { RelayLink.open(scope, "$friendCode/i", asHost = false, brokerIndex = b).first }.getOrNull() ?: return@launch
            links += l
            val listen = launch {
                for (m in l.incoming) {
                    val o = iparse(m) ?: continue
                    if (o["id"] != id) continue
                    when (o["t"]) {
                        "seen" -> { if (_status.value == Status.SENDING) _status.value = Status.SEEN }
                        "accept" -> { _status.value = Status.ACCEPTED }
                        "decline" -> { _status.value = Status.DECLINED }
                    }
                }
            }
            val until = Platform.epochMillis() + 60_000
            while (Platform.epochMillis() < until && _status.value != Status.ACCEPTED && _status.value != Status.DECLINED) {
                l.send(ibody("invite") { put("id", id); put("from", myName); put("code", myCode); put("mode", mode); put("b", myBroker.toString()) })
                delay(3_000)
            }
            // a last moment to hear the answer, then hang up
            delay(1_500)
            listen.cancel()
            l.close()
        }
        scope.launch {
            delay(63_000)
            if (_status.value == Status.SENDING || _status.value == Status.SEEN) _status.value = Status.NO_ANSWER
        }
    }

    fun cancel() {
        jobs.forEach { it.cancel() }
        links.forEach { it.close() }
    }
}
