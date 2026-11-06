package app.coredump.net

import app.coredump.platform.Platform
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Byte pipe to an MQTT broker. */
private interface MqttTransport {
    val packets: ReceiveChannel<Mqtt.Packet>
    suspend fun send(bytes: ByteArray)
    fun close()
    val label: String
}

/** MQTT over secure WebSocket (port 8884/8084/8081): passes most firewalls, TLS, no local-network permission. */
private class WssTransport(
    private val client: HttpClient,
    private val ws: DefaultClientWebSocketSession,
    scope: CoroutineScope,
    override val label: String,
) : MqttTransport {
    override val packets = Channel<Mqtt.Packet>(Channel.UNLIMITED)
    private val decoder = Mqtt.Decoder()
    private val reader: Job = scope.launch(Dispatchers.Default) {
        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Binary) decoder.feed(frame.readBytes()).forEach { packets.send(it) }
            }
        } catch (_: Throwable) {
        } finally { packets.close() }
    }

    override suspend fun send(bytes: ByteArray) { ws.send(Frame.Binary(true, bytes)) }

    override fun close() {
        reader.cancel()
        packets.close()
        CoroutineScope(Dispatchers.Default).launch { runCatching { ws.close() }; client.close() }
    }

    companion object {
        suspend fun open(scope: CoroutineScope, b: Broker): WssTransport {
            val client = HttpClient { install(WebSockets) }
            val ws = try {
                withTimeout(8_000) { client.webSocketSession(b.wss) { header("Sec-WebSocket-Protocol", "mqtt") } }
            } catch (e: Throwable) { client.close(); throw e }
            return WssTransport(client, ws, scope, "${b.host} · wss")
        }
    }
}

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

/**
 * 1v1 link through a public MQTT broker. Host publishes on .../h and listens on .../g; the guest mirrors it.
 * App-level Ping every 4 s; the peer is declared gone after 25 s of silence.
 */
class RelayLink private constructor(
    private val transport: MqttTransport,
    private val scope: CoroutineScope,
    private val pubTopic: String,
    private val subTopic: String,
) : PeerLink {
    override val via: String = "relay · " + transport.label
    override val incoming = Channel<Msg>(Channel.UNLIMITED)
    private val jobs = ArrayList<Job>()
    private var lastPeer = 0L
    private var closed = false

    private fun start() {
        jobs += scope.launch(Dispatchers.Default) {
            for (p in transport.packets) {
                if (p is Mqtt.Packet.Publish && p.topic == subTopic) {
                    val m = Msg.decode(p.payload.decodeToString()) ?: continue
                    lastPeer = Platform.epochMillis()
                    if (m !is Msg.Ping) incoming.send(m)
                }
            }
            close()
        }
        jobs += scope.launch(Dispatchers.Default) {
            var tick = 0
            while (isActive && !closed) {
                delay(4_000)
                tick++
                if (!send(Msg.Ping)) break
                if (tick % 4 == 0) runCatching { transport.send(Mqtt.pingReq()) }
                if (lastPeer > 0 && Platform.epochMillis() - lastPeer > 25_000) break
            }
            close()
        }
    }

    override suspend fun send(m: Msg): Boolean = runCatching {
        transport.send(Mqtt.publish(pubTopic, m.encode().encodeToByteArray()))
    }.isSuccess

    override fun close() {
        if (closed) return
        closed = true
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { transport.send(Mqtt.disconnect()) }
            transport.close()
        }
        jobs.forEach { it.cancel() }
        incoming.close()
    }

    companion object {
        /**
         * Connects to broker [brokerIndex] (or tries all, in order, when null) via WSS then TCP.
         * Returns the link and the broker index actually used.
         */
        suspend fun open(scope: CoroutineScope, room: String, asHost: Boolean, brokerIndex: Int?): Pair<RelayLink, Int> {
            val order = if (brokerIndex != null) listOf(brokerIndex.coerceIn(0, Brokers.all.lastIndex)) else Brokers.all.indices.toList()
            var last: Throwable? = null
            for (bi in order) {
                val b = Brokers.all[bi]
                for (useWss in listOf(true, false)) {
                    val t: MqttTransport = try {
                        if (useWss) WssTransport.open(scope, b) else TcpTransport.open(scope, b)
                    } catch (e: Throwable) { last = e; continue }
                    try {
                        handshake(t, Brokers.topic(room, fromHost = !asHost))
                        val link = RelayLink(t, scope, Brokers.topic(room, fromHost = asHost), Brokers.topic(room, fromHost = !asHost))
                        link.start()
                        return link to bi
                    } catch (e: Throwable) {
                        last = e
                        t.close()
                    }
                }
            }
            throw IllegalStateException("no relay reachable (${last?.message ?: "offline"})")
        }

        private suspend fun handshake(t: MqttTransport, subscribeTo: String) {
            val id = "cd" + (1..12).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
            t.send(Mqtt.connect(id, 60))
            val ack = withTimeoutOrNull(6_000) { t.packets.receive() }
            if (ack !is Mqtt.Packet.ConnAck || ack.returnCode != 0) error("broker refused (${ack ?: "timeout"})")
            t.send(Mqtt.subscribe(1, subscribeTo))
            val sub = withTimeoutOrNull(6_000) {
                var p = t.packets.receive()
                while (p !is Mqtt.Packet.SubAck) p = t.packets.receive()
                p
            }
            if (sub == null) error("subscribe timeout")
        }
    }
}
