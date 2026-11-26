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
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.isActive
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

actual val lanSupported: Boolean = true

/** Host side: listens on an ephemeral port of every interface. */
actual class LanHost actual constructor(private val scope: CoroutineScope) {
    private val selector = SelectorManager(Dispatchers.Default)
    private var server: ServerSocket? = null

    /** Binds and returns the chosen TCP port (number). */
    actual suspend fun bind(): Int {
        val s = aSocket(selector).tcp().bind("0.0.0.0", 0)
        server = s
        return (s.localAddress as InetSocketAddress).port
    }

    actual suspend fun accept(): PeerLink {
        val sock = server!!.accept()
        return LanLink(sock, scope).also { it.start() }
    }

    actual fun close() {
        runCatching { server?.close() }
        runCatching { selector.close() }
    }
}

actual object LanClient {
    actual suspend fun connect(scope: CoroutineScope, host: String, port: Int, timeoutMs: Long): PeerLink {
        val selector = SelectorManager(Dispatchers.Default)
        val sock = withTimeout(timeoutMs) { aSocket(selector).tcp().connect(host, port) }
        return LanLink(sock, scope).also { it.start() }
    }
}

internal actual suspend fun openTcpMqtt(scope: CoroutineScope, b: Broker): MqttTransport = TcpTransport.open(scope, b)

/** Plain MQTT over TCP 1883: fallback when WebSockets fail. Raw sockets need no ATS or local-network permission. */
private class TcpTransport(
    private val selector: SelectorManager,
    private val socket: Socket,
    scope: CoroutineScope,
    override val label: String,
) : MqttTransport {
    override val packets = Channel<Mqtt.Packet>(Channel.UNLIMITED)
    private val input = socket.openReadChannel()
    private val output = socket.openWriteChannel(autoFlush = true)
    private val decoder = Mqtt.Decoder()
    private val lock = Mutex()
    private val reader: Job = scope.launch(Dispatchers.Default) {
        val buf = ByteArray(8192)
        try {
            while (isActive) {
                val n = input.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) decoder.feed(buf.copyOf(n)).forEach { packets.send(it) }
            }
        } catch (_: Throwable) {
        } finally { packets.close() }
    }

    override suspend fun send(bytes: ByteArray) {
        lock.withLock { output.writeFully(bytes, 0, bytes.size); output.flush() }
    }

    override fun close() {
        reader.cancel()
        packets.close()
        runCatching { socket.close() }
        runCatching { selector.close() }
    }

    companion object {
        suspend fun open(scope: CoroutineScope, b: Broker): TcpTransport {
            val selector = SelectorManager(Dispatchers.Default)
            val socket = try {
                withTimeout(8_000) { aSocket(selector).tcp().connect(b.host, b.tcpPort) }
            } catch (e: Throwable) { selector.close(); throw e }
            return TcpTransport(selector, socket, scope, "${b.host} · tcp")
        }
    }
}

