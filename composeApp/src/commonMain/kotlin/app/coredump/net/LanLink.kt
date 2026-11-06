package app.coredump.net

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** One TCP connection carrying newline-delimited JSON messages. */
class LanLink(private val socket: Socket, private val scope: CoroutineScope) : PeerLink {
    override val via: String = "direct wi-fi"
    private val input = socket.openReadChannel()
    private val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)
    private val writeLock = Mutex()
    override val incoming = Channel<Msg>(Channel.UNLIMITED)
    private var reader: Job? = null

    fun start() {
        reader = scope.launch(Dispatchers.Default) {
            try {
                while (true) {
                    val line = input.readUTF8Line(256 * 1024) ?: break
                    if (line.isBlank()) continue
                    Msg.decode(line)?.let { incoming.send(it) }
                }
            } catch (_: Throwable) {
            } finally {
                incoming.close()
            }
        }
    }

    override suspend fun send(m: Msg): Boolean = runCatching {
        writeLock.withLock {
            output.writeStringUtf8(m.encode() + "\n")
            output.flush()
        }
    }.isSuccess

    override fun close() {
        reader?.cancel()
        runCatching { socket.close() }
        incoming.close()
    }
}

/** Host side: listens on an ephemeral port of every interface. */
class LanHost(private val scope: CoroutineScope) {
    private val selector = SelectorManager(Dispatchers.Default)
    private var server: ServerSocket? = null

    /** Binds and returns the chosen TCP port (number). */
    suspend fun bind(): Int {
        val s = aSocket(selector).tcp().bind("0.0.0.0", 0)
        server = s
        return (s.localAddress as InetSocketAddress).port
    }

    suspend fun accept(): LanLink {
        val sock = server!!.accept()
        return LanLink(sock, scope).also { it.start() }
    }

    fun close() {
        runCatching { server?.close() }
        runCatching { selector.close() }
    }
}

object LanClient {
    suspend fun connect(scope: CoroutineScope, host: String, port: Int, timeoutMs: Long = 8_000): LanLink {
        val selector = SelectorManager(Dispatchers.Default)
        val sock = withTimeout(timeoutMs) { aSocket(selector).tcp().connect(host, port) }
        return LanLink(sock, scope).also { it.start() }
    }
}
