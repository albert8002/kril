package app.coredump.net

import kotlinx.coroutines.CoroutineScope

/** A browser has no raw sockets: no direct Wi-Fi games and no MQTT over plain TCP (WebSockets only). */
actual val lanSupported: Boolean = false

actual class LanHost actual constructor(private val scope: CoroutineScope) {
    actual suspend fun bind(): Int = throw UnsupportedOperationException("direct Wi-Fi needs the phone app")
    actual suspend fun accept(): PeerLink = throw UnsupportedOperationException("direct Wi-Fi needs the phone app")
    actual fun close() {}
}

actual object LanClient {
    actual suspend fun connect(scope: CoroutineScope, host: String, port: Int, timeoutMs: Long): PeerLink =
        throw UnsupportedOperationException("direct Wi-Fi needs the phone app")
}

internal actual suspend fun openTcpMqtt(scope: CoroutineScope, b: Broker): MqttTransport =
    throw UnsupportedOperationException("no raw TCP in a browser")
