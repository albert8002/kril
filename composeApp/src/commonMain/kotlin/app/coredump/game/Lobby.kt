package app.coredump.game

import app.coredump.core.GameConfig
import app.coredump.core.Prompt
import app.coredump.net.JoinTicket
import app.coredump.net.LanClient
import app.coredump.net.LanHost
import app.coredump.net.LanLink
import app.coredump.net.Msg
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

data class LobbyState(
    val status: String = "idle",
    val ticket: JoinTicket? = null,
    val peer: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
)

/** Host lobby: bind socket, publish QR ticket, accept exactly one peer. */
class HostLobby(private val scope: CoroutineScope, private val myName: String) {
    private val _s = MutableStateFlow(LobbyState())
    val state: StateFlow<LobbyState> = _s.asStateFlow()
    private var host: LanHost? = null
    private var link: LanLink? = null
    private var job: Job? = null
    private val pin = Random.nextInt(1000, 10000).toString()

    fun open() {
        close()
        job = scope.launch {
            _s.value = LobbyState(status = "binding socket…", busy = true)
            val ip = Platform.localIpv4()
            if (ip == null) {
                _s.value = LobbyState(status = "no wi-fi address", error = "join a Wi-Fi network (or hotspot) first.")
                return@launch
            }
            val h = LanHost(scope)
            host = h
            val port = runCatching { h.bind() }.getOrElse {
                _s.value = LobbyState(status = "bind failed", error = it.message ?: "socket error"); return@launch
            }
            _s.value = LobbyState(status = "listening", ticket = JoinTicket(ip, port, pin, myName), busy = true)
            while (true) {
                val l = runCatching { h.accept() }.getOrElse {
                    _s.update { s -> s.copy(status = "accept failed", error = it.message, busy = false) }; return@launch
                }
                val hello = withTimeoutOrNull(10_000) { l.incoming.receiveCatching().getOrNull() }
                if (hello !is Msg.Hello) { l.close(); continue }
                if (hello.pin != pin) {
                    l.send(Msg.Reject("bad pin")); l.close(); continue
                }
                l.send(Msg.Welcome(myName))
                link = l
                _s.update { it.copy(status = "peer connected", peer = hello.name.ifBlank { "PEER" }, busy = false) }
                break
            }
        }
    }

    /** Starts the match with the connected peer, or null if none. */
    fun begin(prompts: List<Prompt>, cfg: GameConfig): HostSession? {
        val l = link ?: return null
        val peer = _s.value.peer ?: "PEER"
        link = null
        runCatching { host?.close() }
        return HostSession(l, prompts, cfg, myName, peer, scope).also { it.start() }
    }

    fun close() {
        job?.cancel()
        link?.let { l -> scope.launch { l.send(Msg.Bye("lobby closed")); l.close() } }
        link = null
        host?.close()
        host = null
    }
}

/** Guest: connect to a ticket, handshake, hand back a session that waits for the host. */
class GuestJoin(private val scope: CoroutineScope, private val myName: String) {
    private val _s = MutableStateFlow(LobbyState())
    val state: StateFlow<LobbyState> = _s.asStateFlow()
    private var job: Job? = null

    fun connect(t: JoinTicket, onJoined: (GuestSession) -> Unit) {
        job?.cancel()
        job = scope.launch {
            _s.value = LobbyState(status = "dialing ${t.host}:${t.port}…", ticket = t, busy = true)
            val link = runCatching { LanClient.connect(scope, t.host, t.port) }.getOrElse {
                _s.value = LobbyState(status = "connection refused", ticket = t,
                    error = "can't reach host. same Wi-Fi? local network allowed?")
                return@launch
            }
            link.send(Msg.Hello(myName, t.pin))
            when (val reply = withTimeoutOrNull(8_000) { link.incoming.receiveCatching().getOrNull() }) {
                is Msg.Welcome -> {
                    _s.value = LobbyState(status = "linked", ticket = t, peer = reply.hostName)
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
                    _s.value = LobbyState(status = "no handshake", ticket = t, error = "host did not answer.")
                }
            }
        }
    }

    fun cancel() { job?.cancel() }
}
