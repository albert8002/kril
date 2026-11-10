package app.coredump.game

import app.coredump.core.GameConfig
import app.coredump.core.Prompt
import app.coredump.net.JoinTicket
import app.coredump.net.LanClient
import app.coredump.net.LanHost
import app.coredump.net.Msg
import app.coredump.net.PeerLink
import app.coredump.net.RelayLink
import app.coredump.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** RELAY: through a public MQTT broker (works on any network, incl. LiveContainer). DIRECT: same Wi-Fi socket. */
enum class LinkMode(val label: String) { RELAY("ONLINE RELAY"), DIRECT("DIRECT WI-FI") }

data class LobbyState(
    val status: String = "idle",
    val ticket: JoinTicket? = null,
    val peer: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val via: String? = null,
    /** The other player's own room code (for the friends list and 1v1 history). */
    val peerCode: String? = null,
)

/** Who I last played (name → room code), so the 1v1 record and "add friend" know their code. */
object PeerBook {
    private val codes = HashMap<String, String>()
    fun note(name: String?, code: String?) { if (!name.isNullOrBlank() && !code.isNullOrBlank()) codes[name] = code }
    fun codeOf(name: String?): String? = name?.let { codes[it] }
}

/**
 * Host lobby: open a relay room (or bind a socket), publish the QR ticket, accept exactly one peer.
 * Relay rooms use [room], my own code that never changes, so the code or QR sent to a friend stays valid: when the
 * connection drops (the phone left the app, the network changed), the lobby reconnects to the same room by itself.
 * [onBroker]: the relay that worked (tried first next time, so the QR stays the same too).
 */
class HostLobby(private val scope: CoroutineScope, private val myName: String, val mode: LinkMode,
                private val wrap: (PeerLink) -> PeerLink = { it },
                private val room: String = JoinTicket.newRoom(), private val preferBroker: Int? = null,
                private val onBroker: (Int) -> Unit = {}) {
    private val _s = MutableStateFlow(LobbyState())
    val state: StateFlow<LobbyState> = _s.asStateFlow()
    private var lanHost: LanHost? = null
    private var link: PeerLink? = null
    private var job: Job? = null

    fun open() {
        close()
        job = scope.launch { if (mode == LinkMode.RELAY) openRelay() else openDirect() }
    }

    private suspend fun openRelay() {
        var broker = preferBroker
        var failures = 0
        while (true) {
            val ticketNow = _s.value.ticket
            _s.value = LobbyState(status = if (ticketNow == null) "contacting relay…" else "reconnecting… (same code)", ticket = ticketNow, busy = true)
            // the relay that worked before first (same QR), then any other
            val opened = runCatching { RelayLink.open(scope, room, asHost = true, brokerIndex = broker) }.getOrNull()
                ?: runCatching { RelayLink.open(scope, room, asHost = true, brokerIndex = null) }.getOrNull()
            if (opened == null) {
                failures++
                _s.value = LobbyState(status = "relay unreachable · retrying", ticket = ticketNow,
                    error = "no internet, or all relays blocked. the code stays the same.", busy = true)
                delay((2_000L * failures).coerceAtMost(15_000L))
                continue
            }
            val (l, b) = opened
            failures = 0
            broker = b
            onBroker(b)
            link = l
            _s.value = LobbyState(status = "listening", ticket = JoinTicket("", 0, "", myName, room, b), busy = true, via = l.via)
            for (m in l.incoming) {
                if (m is Msg.Hello && m.pin == room) {
                    l.send(Msg.Welcome(myName, room))
                    link = wrap(l)
                    PeerBook.note(m.name.ifBlank { "PEER" }, m.me)
                    _s.update { it.copy(status = "peer connected", peer = m.name.ifBlank { "PEER" }, busy = false, peerCode = m.me.ifBlank { null }) }
                    return
                }
            }
            // the link dropped before anyone joined (app in the background, network change): same room again
            link = null
            if (_s.value.peer != null) return
            delay(1_000)
        }
    }

    private suspend fun openDirect() {
        _s.value = LobbyState(status = "binding socket…", busy = true)
        val ip = Platform.localIpv4()
        if (ip == null) {
            _s.value = LobbyState(status = "no wi-fi address", error = "join a Wi-Fi network (or hotspot) first.")
            return
        }
        val h = LanHost(scope)
        lanHost = h
        val port = runCatching { h.bind() }.getOrElse {
            _s.value = LobbyState(status = "bind failed", error = it.message ?: "socket error"); return
        }
        val pin = Random.nextInt(1000, 10000).toString()
        _s.value = LobbyState(status = "listening", ticket = JoinTicket(ip, port, pin, myName), busy = true, via = "direct wi-fi")
        while (true) {
            val l = runCatching { h.accept() }.getOrElse {
                _s.update { s -> s.copy(status = "accept failed", error = it.message, busy = false) }; return
            }
            val hello = withTimeoutOrNull(10_000) { l.incoming.receiveCatching().getOrNull() }
            if (hello !is Msg.Hello) { l.close(); continue }
            if (hello.pin != pin) { l.send(Msg.Reject("bad pin")); l.close(); continue }
            l.send(Msg.Welcome(myName, room))
            link = wrap(l)
            PeerBook.note(hello.name.ifBlank { "PEER" }, hello.me)
            _s.update { it.copy(status = "peer connected", peer = hello.name.ifBlank { "PEER" }, busy = false, peerCode = hello.me.ifBlank { null }) }
            return
        }
    }

    /** Starts the match with the connected peer, or null if none. */
    fun begin(prompts: List<Prompt>, cfg: GameConfig, arbiter: Arbiter): HostSession? {
        val l = link ?: return null
        if (_s.value.peer == null) return null
        val peer = _s.value.peer ?: "PEER"
        link = null
        job?.cancel()
        runCatching { lanHost?.close() }
        return HostSession(l, prompts, cfg, myName, peer, scope, arbiter).also { it.start() }
    }

    fun close() {
        job?.cancel()
        link?.let { l -> scope.launch { l.send(Msg.Bye("lobby closed")); l.close() } }
        link = null
        lanHost?.close()
        lanHost = null
    }
}

/**
 * Guest: connect to a ticket (relay room or direct socket), handshake, hand back a session that waits for the host.
 * A typed code does not say which relay the host is on: the ticket's relay is tried first, then the others.
 */
class GuestJoin(private val scope: CoroutineScope, private val myName: String, private val wrap: (PeerLink) -> PeerLink = { it },
                private val myCode: String = "") {
    private val _s = MutableStateFlow(LobbyState())
    val state: StateFlow<LobbyState> = _s.asStateFlow()
    private var job: Job? = null

    fun connect(t: JoinTicket, onJoined: (GuestSession) -> Unit) {
        job?.cancel()
        job = scope.launch {
            val where = if (t.isRelay) "room ${JoinTicket.pretty(t.room!!)}" else "${t.host}:${t.port}"
            _s.value = LobbyState(status = "dialing $where…", ticket = t, busy = true)
            val pin = if (t.isRelay) t.room!! else t.pin
            // relay: the ticket's relay first, then the others (a typed code carries no relay)
            val order = if (t.isRelay) (listOf(t.broker) + app.coredump.net.Brokers.all.indices.filter { it != t.broker }) else listOf(-1)
            var link: PeerLink? = null
            var reply: Msg? = null
            for ((n, b) in order.withIndex()) {
                val l: PeerLink = runCatching {
                    if (t.isRelay) RelayLink.open(scope, t.room!!, asHost = false, brokerIndex = b).first
                    else LanClient.connect(scope, t.host, t.port)
                }.getOrNull() ?: continue
                // QoS-0 relay: repeat the hello until the host answers (it may subscribe late)
                val tries = if (n == 0) 10 else 6
                for (attempt in 0 until tries) {
                    l.send(Msg.Hello(myName, pin, me = myCode))
                    reply = withTimeoutOrNull(1_500) {
                        var m = l.incoming.receiveCatching().getOrNull()
                        while (m != null && m !is Msg.Welcome && m !is Msg.Reject) m = l.incoming.receiveCatching().getOrNull()
                        m
                    }
                    if (reply != null) break
                    _s.update { it.copy(status = "waiting for host… (${attempt + 1}${if (order.size > 1) " · relay ${n + 1}/${order.size}" else ""})") }
                }
                if (reply != null) { link = l; break }
                l.close()
            }
            if (link == null) {
                _s.value = LobbyState(status = "no answer", ticket = t,
                    error = if (t.isRelay) "host not found. is the host screen open? (codes stay the same, try again)"
                    else "can't reach host. same Wi-Fi? local network allowed?")
                return@launch
            }
            when (reply) {
                is Msg.Welcome -> {
                    PeerBook.note(reply.hostName, reply.me.ifBlank { t.room })
                    _s.value = LobbyState(status = "linked", ticket = t, peer = reply.hostName, via = link.via, peerCode = reply.me.ifBlank { t.room })
                    val session = GuestSession(wrap(link), myName, reply.hostName, scope)
                    session.start()
                    onJoined(session)
                }
                is Msg.Reject -> {
                    link.close()
                    _s.value = LobbyState(status = "rejected", ticket = t, error = reply.reason)
                }
                else -> {
                    link.close()
                    _s.value = LobbyState(status = "no answer", ticket = t, error = "host not found. is the host screen still open?")
                }
            }
        }
    }

    fun cancel() { job?.cancel() }
}
