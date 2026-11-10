package app.coredump.game

import app.coredump.core.Prompt
import app.coredump.net.Brokers
import app.coredump.net.Msg
import app.coredump.net.RelayLink
import app.coredump.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * MELTDOWN online: the host's device runs the game; guests send answers and draw the host's snapshots.
 * Same relay as 1v1, room "<my code>/p" (so a friend's code works for both): the host publishes to every guest at
 * once, every guest publishes to the host. Bodies are small JSON objects with "t":
 *   guest → host: join {id, n} · answer {id, a} · leave {id}
 *   host → all:   state {s} · verdict {id, a, k} · bye
 */

private val pjson = Json { ignoreUnknownKeys = true }

private fun body(t: String, b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = Msg.Party(buildJsonObject { put("t", t); b() }.toString())

private fun parse(m: Msg): Map<String, String>? {
    if (m !is Msg.Party) return null
    return runCatching {
        pjson.parseToJsonElement(m.body).jsonObject.mapValues { (_, v) -> runCatching { v.jsonPrimitive.contentOrNull.orEmpty() }.getOrDefault(v.toString()) }
    }.getOrNull()
}

data class PartyLobby(val status: String = "idle", val players: List<MeltPlayer> = emptyList(), val error: String? = null, val open: Boolean = false)

/** Party host: keeps the room open (same code, reconnects by itself), seats guests, then runs and broadcasts the game. */
class PartyHost(
    private val scope: CoroutineScope,
    val room: String,
    private val me: MeltPlayer,
    private val preferBroker: Int?,
    private val onBroker: (Int) -> Unit,
) {
    private val _lobby = MutableStateFlow(PartyLobby("contacting relay…", listOf(me)))
    val lobby: StateFlow<PartyLobby> = _lobby.asStateFlow()
    private var link: RelayLink? = null
    private var job: Job? = null
    var game: MeltdownGame? = null
        private set
    private val gameJobs = ArrayList<Job>()
    /** Last message time per guest id (a guest silent for 20 s is dropped). */
    private val seen = HashMap<String, Long>()
    private var watch: Job? = null

    fun open() {
        if (watch == null) watch = scope.launch {
            while (true) {
                delay(3_000)
                val now = Platform.epochMillis()
                val gone = seen.filter { (id, t) -> id != me.id && now - t > 20_000 }.keys.toList()
                for (id in gone) {
                    seen.remove(id)
                    val g = game
                    if (g == null) { _lobby.update { it.copy(players = it.players.filter { p -> p.id != id }) }; sendLobby() } else g.drop(id)
                }
            }
        }
        if (job != null) return
        job = scope.launch {
            var broker = preferBroker
            var failures = 0
            while (true) {
                _lobby.update { it.copy(status = if (it.open) "reconnecting… (same code)" else "contacting relay…") }
                val opened = runCatching { RelayLink.open(scope, "$room/p", asHost = true, brokerIndex = broker) }.getOrNull()
                    ?: runCatching { RelayLink.open(scope, "$room/p", asHost = true, brokerIndex = null) }.getOrNull()
                if (opened == null) {
                    failures++
                    _lobby.update { it.copy(status = "relay unreachable · retrying", error = "no internet? the code stays the same.") }
                    delay((2_000L * failures).coerceAtMost(15_000L))
                    continue
                }
                failures = 0
                val (l, b) = opened
                broker = b
                onBroker(b)
                link = l
                _lobby.update { it.copy(status = if (game == null) "open · waiting for players" else "open · in game", error = null, open = true) }
                val g = game
                if (g == null) sendLobby() else send(body("state") { put("s", Meltdown.encode(g.state.value)) })
                for (m in l.incoming) handle(parse(m) ?: continue)
                link = null
                delay(1_000)
            }
        }
    }

    private suspend fun send(m: Msg) { link?.send(m) }

    private suspend fun sendLobby() {
        val s = MeltState(phase = MeltPhase.LOBBY, players = _lobby.value.players)
        send(body("state") { put("s", Meltdown.encode(s)) })
    }

    private suspend fun handle(o: Map<String, String>) {
        val id = o["id"].orEmpty()
        if (id.isNotBlank() && o["t"] != "leave") seen[id] = Platform.epochMillis()
        when (o["t"]) {
            "join" -> {
                val g = game
                if (g == null) {
                    if (_lobby.value.players.none { it.id == id } && _lobby.value.players.size < Meltdown.MAX_PLAYERS && id.isNotBlank()) {
                        val name = o["n"].orEmpty().ifBlank { "GUEST" }.take(16)
                        _lobby.update { it.copy(players = it.players + MeltPlayer(id, name, bot = false)) }
                    }
                    sendLobby()
                } else send(body("state") { put("s", Meltdown.encode(g.state.value)) })
            }
            "answer" -> game?.submit(id, o["a"].orEmpty().take(80))
            "leave" -> {
                seen.remove(id)
                val g = game
                if (g == null) { _lobby.update { it.copy(players = it.players.filter { p -> p.id != id }) }; sendLobby() } else g.drop(id)
            }
        }
    }

    /** Starts the game with everyone seated plus [bots] bots; the state goes to every guest as it changes. */
    fun start(bots: Int, level: Int, pick: () -> Prompt?): MeltdownGame {
        val seated = _lobby.value.players
        val used = seated.map { it.name }.toMutableSet()
        val extra = Meltdown.botNames.filter { it !in used }.shuffled().take(bots.coerceIn(0, Meltdown.MAX_PLAYERS - seated.size))
            .map { MeltPlayer("bot-" + it.lowercase(), it, bot = true) }
        val g = MeltdownGame(scope, (seated + extra).shuffled(), pick, level)
        game = g
        gameJobs += scope.launch {
            var lastSent = -1
            g.state.collect { s ->
                if (s.seq == lastSent) return@collect
                lastSent = s.seq
                send(body("state") { put("s", Meltdown.encode(s)) })
            }
        }
        gameJobs += scope.launch {
            g.verdicts.collect { (id, a, k) -> if (id != me.id) send(body("verdict") { put("id", id); put("a", a); put("k", k) }) }
        }
        g.start()
        return g
    }

    fun close() {
        val l = link
        game?.stop()
        gameJobs.forEach { it.cancel() }
        watch?.cancel()
        watch = null
        job?.cancel()
        job = null
        link = null
        if (l != null) scope.launch { runCatching { l.send(body("bye") { }) }; delay(300); l.close() }
    }
}

/**
 * Party guest: finds the host's room (any relay), takes a seat, mirrors the host's game and sends my answers.
 * [lobby] shows the seating until the host starts.
 */
class PartyGuest(private val scope: CoroutineScope, private val myName: String, val room: String, private val broker: Int = 0) : MeltSession {
    override val myId: String = Meltdown.newId()
    override val online: Boolean = true
    private val _state = MutableStateFlow(MeltState())
    override val state: StateFlow<MeltState> = _state.asStateFlow()
    private val _v = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 16)
    override val verdicts: SharedFlow<Pair<String, String>> = _v.asSharedFlow()
    private val _lobby = MutableStateFlow(PartyLobby("dialing…"))
    val lobby: StateFlow<PartyLobby> = _lobby.asStateFlow()
    private var link: RelayLink? = null
    private var job: Job? = null

    fun connect() {
        job?.cancel()
        job = scope.launch {
            var order = listOf(broker) + Brokers.all.indices.filter { it != broker }
            var rejoins = 0
            while (true) {
                val seatedOn = seat(order, firstTime = rejoins == 0)
                val l = link
                if (seatedOn == null || l == null) {
                    _lobby.value = if (rejoins == 0) PartyLobby("no answer", error = "host not found. is their MELTDOWN host screen open?")
                    else PartyLobby("connection lost", error = "could not get back in.")
                    if (rejoins > 0 && _state.value.phase != MeltPhase.OVER) _state.update { it.copy(event = "lost the host", eventKind = "out", eventBy = myId, evn = it.evn + 1) }
                    return@launch
                }
                order = listOf(seatedOn)
                _lobby.update { it.copy(status = if (_state.value.phase == MeltPhase.LOBBY) "seated · waiting for the host to start" else "in game",
                    open = true, players = _state.value.players, error = null) }
                val beat = launch { while (true) { delay(5_000); l.send(body("hb") { put("id", myId) }) } }
                var bye = false
                for (m in l.incoming) {
                    val o = parse(m) ?: continue
                    when (o["t"]) {
                        "state" -> Meltdown.decode(o["s"].orEmpty())?.let { s ->
                            _state.value = s
                            if (s.phase == MeltPhase.LOBBY) _lobby.update { it.copy(players = s.players) }
                        }
                        "verdict" -> if (o["id"] == myId) _v.tryEmit(o["a"].orEmpty() to o["k"].orEmpty())
                        "bye" -> { bye = true; break }
                    }
                }
                beat.cancel()
                l.close()
                link = null
                if (bye) {
                    _lobby.value = PartyLobby("host closed the party", error = "the host left.")
                    if (_state.value.phase != MeltPhase.OVER) _state.update { it.copy(event = "the host closed the party", eventKind = "out", eventBy = myId, evn = it.evn + 1) }
                    return@launch
                }
                if (_state.value.phase == MeltPhase.OVER) return@launch
                // link dropped (app in background, network switch): same id, same room, take the seat back
                rejoins++
                if (rejoins > 6) {
                    _lobby.value = PartyLobby("connection lost", error = "could not get back in.")
                    _state.update { it.copy(event = "lost the host", eventKind = "out", eventBy = myId, evn = it.evn + 1) }
                    return@launch
                }
                _lobby.update { it.copy(status = "reconnecting…", open = false) }
                delay(1_500)
            }
        }
    }

    /** Opens the room on the first broker in [order] that the host answers on; returns that broker's index. */
    private suspend fun seat(order: List<Int>, firstTime: Boolean): Int? {
        for ((n, b) in order.withIndex()) {
            val l = runCatching { RelayLink.open(scope, "$room/p", asHost = false, brokerIndex = b).first }.getOrNull() ?: continue
            link = l
            // ask for a seat until the host's seating lists me (the host may subscribe late)
            for (attempt in 0 until (if (n == 0) 10 else 6)) {
                l.send(body("join") { put("id", myId); put("n", myName) })
                val relay = if (order.size > 1) " · relay ${n + 1}/${order.size}" else ""
                _lobby.update { it.copy(status = (if (firstTime) "waiting for host… (" else "rejoining… (") + "${attempt + 1}$relay)") }
                val got = withTimeoutOrNull(1_500) {
                    var s: MeltState? = null
                    while (s == null) {
                        val m = l.incoming.receiveCatching().getOrNull() ?: break
                        val o = parse(m) ?: continue
                        if (o["t"] == "state") s = Meltdown.decode(o["s"].orEmpty())?.takeIf { st -> st.players.any { it.id == myId } }
                    }
                    s
                }
                if (got != null) { _state.value = got; return b }
            }
            l.close()
            link = null
        }
        return null
    }

    override fun submit(text: String) {
        val l = link ?: return
        scope.launch { l.send(body("answer") { put("id", myId); put("a", text) }) }
    }

    override fun leave() {
        val l = link
        job?.cancel()
        link = null
        if (l != null) scope.launch { runCatching { l.send(body("leave") { put("id", myId) }) }; delay(300); l.close() }
    }
}
