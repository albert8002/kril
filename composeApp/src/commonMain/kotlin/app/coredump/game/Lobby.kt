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
)

/** Host lobby: open a relay room (or bind a socket), publish the QR ticket, accept exactly one peer. */
class HostLobby(private val scope: CoroutineScope, private val myName: String, val mode: LinkMode) {
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
        _s.value = LobbyState(status = "contacting relay…", busy = true)
        val room = JoinTicket.newRoom()
        val (l, broker) = runCatching { RelayLink.open(scope, room, asHost = true, brokerIndex = null) }.getOrElse {
            _s.value = LobbyState(status = "relay unreachable", error = "no internet, or all relays blocked. ${it.message ?: ""}")
            return
        }
        link = l
        _s.value = LobbyState(status = "listening", ticket = JoinTicket("", 0, "", myName, room, broker), busy = true, via = l.via)
        for (m in l.incoming) {
            if (m is Msg.Hello && m.pin == room) {
                l.send(Msg.Welcome(myName))
                _s.update { it.copy(status = "peer connected", peer = m.name.ifBlank { "PEER" }, busy = false) }
                return
            }
        }
        if (_s.value.peer == null) _s.update { it.copy(status = "relay dropped", error = "connection to relay lost. retry.", busy = false) }
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
            l.send(Msg.Welcome(myName))
            link = l
            _s.update { it.copy(status = "peer connected", peer = hello.name.ifBlank { "PEER" }, busy = false) }
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

/** Guest: connect to a ticket (relay room or direct socket), handshake, hand back a session that waits for the host. */
class GuestJoin(private val scope: CoroutineScope, private val myName: String) {
    private val _s = MutableStateFlow(LobbyState())
    val state: StateFlow<LobbyState> = _s.asStateFlow()
    private var job: Job? = null

    fun connect(t: JoinTicket, onJoined: (GuestSession) -> Unit) {
        job?.cancel()
        job = scope.launch {
            val where = if (t.isRelay) "room ${JoinTicket.pretty(t.room!!)}" else "${t.host}:${t.port}"
            _s.value = LobbyState(status = "dialing $where…", ticket = t, busy = true)
            val link: PeerLink = runCatching {
                if (t.isRelay) RelayLink.open(scope, t.room!!, asHost = false, brokerIndex = t.broker).first
                else LanClient.connect(scope, t.host, t.port)
            }.getOrElse {
                _s.value = LobbyState(status = "connection failed", ticket = t,
                    error = if (t.isRelay) "relay unreachable. check internet." else "can't reach host. same Wi-Fi? local network allowed?")
                return@launch
            }
            val pin = if (t.isRelay) t.room!! else t.pin
            // QoS-0 relay: repeat the hello until the host answers (it may subscribe late).
            var reply: Msg? = null
            for (attempt in 0 until 14) {
                link.send(Msg.Hello(myName, pin))
                reply = withTimeoutOrNull(1_500) {
                    var m = link.incoming.receiveCatching().getOrNull()
                    while (m != null && m !is Msg.Welcome && m !is Msg.Reject) m = link.incoming.receiveCatching().getOrNull()
                    m
                }
                if (reply != null) break
                _s.update { it.copy(status = "waiting for host… (${attempt + 1})") }
            }
            when (reply) {
                is Msg.Welcome -> {
                    _s.value = LobbyState(status = "linked", ticket = t, peer = reply.hostName, via = link.via)
                    val session = GuestSession(link, myName, reply.hostName, scope)
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
