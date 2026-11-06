package app.coredump.net

/**
 * Minimal MQTT 3.1.1 client codec (QoS 0 only): enough to use a public broker as a message relay.
 * Pure Kotlin so the CI snapshot tool can test it against a real broker.
 */
object Mqtt {
    sealed class Packet {
        data class ConnAck(val returnCode: Int) : Packet()
        data class SubAck(val packetId: Int) : Packet()
        class Publish(val topic: String, val payload: ByteArray) : Packet()
        data object PingResp : Packet()
        data class Other(val type: Int) : Packet()
    }

    private fun str(s: String): ByteArray {
        val b = s.encodeToByteArray()
        return byteArrayOf((b.size shr 8).toByte(), (b.size and 0xFF).toByte()) + b
    }

    private fun remainingLength(n: Int): ByteArray {
        val out = ArrayList<Byte>(4)
        var x = n
        do {
            var b = x % 128
            x /= 128
            if (x > 0) b = b or 0x80
            out += b.toByte()
        } while (x > 0)
        return out.toByteArray()
    }

    private fun packet(header: Int, body: ByteArray): ByteArray =
        byteArrayOf(header.toByte()) + remainingLength(body.size) + body

    /** CONNECT, clean session, keep-alive in seconds. */
    fun connect(clientId: String, keepAliveSec: Int = 30): ByteArray {
        val vh = str("MQTT") + byteArrayOf(4, 0x02, (keepAliveSec shr 8).toByte(), (keepAliveSec and 0xFF).toByte())
        return packet(0x10, vh + str(clientId))
    }

    fun subscribe(packetId: Int, topic: String): ByteArray =
        packet(0x82, byteArrayOf((packetId shr 8).toByte(), (packetId and 0xFF).toByte()) + str(topic) + byteArrayOf(0))

    fun publish(topic: String, payload: ByteArray): ByteArray = packet(0x30, str(topic) + payload)

    fun pingReq(): ByteArray = byteArrayOf(0xC0.toByte(), 0)

    fun disconnect(): ByteArray = byteArrayOf(0xE0.toByte(), 0)

    /** Incremental decoder: feed raw bytes as they arrive, get whole packets out. */
    class Decoder {
        private var buf = ByteArray(0)

        fun feed(bytes: ByteArray): List<Packet> {
            buf += bytes
            val out = ArrayList<Packet>()
            while (true) {
                if (buf.size < 2) break
                var mult = 1
                var len = 0
                var i = 1
                var complete = false
                while (i < buf.size && i <= 4) {
                    val b = buf[i].toInt() and 0xFF
                    len += (b and 0x7F) * mult
                    mult *= 128
                    i++
                    if (b and 0x80 == 0) { complete = true; break }
                }
                if (!complete) break
                if (buf.size < i + len) break
                val first = buf[0].toInt() and 0xFF
                val body = buf.copyOfRange(i, i + len)
                buf = buf.copyOfRange(i + len, buf.size)
                out += decode(first, body)
            }
            return out
        }

        private fun decode(first: Int, body: ByteArray): Packet {
            val type = first ushr 4
            return when (type) {
                2 -> Packet.ConnAck(if (body.size >= 2) body[1].toInt() and 0xFF else -1)
                9 -> Packet.SubAck(if (body.size >= 2) ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF) else 0)
                3 -> {
                    val qos = (first shr 1) and 3
                    val tl = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    val topic = body.copyOfRange(2, 2 + tl).decodeToString()
                    var p = 2 + tl
                    if (qos > 0) p += 2
                    Packet.Publish(topic, body.copyOfRange(p, body.size))
                }
                13 -> Packet.PingResp
                else -> Packet.Other(type)
            }
        }
    }
}

/** Public brokers used as relays. Both players must use the same index (carried in the QR / code). */
class Broker(val host: String, val wss: String, val tcpPort: Int = 1883)

object Brokers {
    val all = listOf(
        Broker("broker.hivemq.com", "wss://broker.hivemq.com:8884/mqtt"),
        Broker("broker.emqx.io", "wss://broker.emqx.io:8084/mqtt"),
        Broker("test.mosquitto.org", "wss://test.mosquitto.org:8081/"),
    )

    fun topic(room: String, fromHost: Boolean) = "coredump/v1/$room/" + if (fromHost) "h" else "g"
}
