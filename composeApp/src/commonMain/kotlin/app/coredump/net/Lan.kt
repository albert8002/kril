package app.coredump.net

import kotlinx.coroutines.CoroutineScope

/** Direct Wi-Fi play and MQTT over plain TCP need raw sockets: Android and iOS have them, the web app does not. */
expect val lanSupported: Boolean

/** Host side of a direct Wi-Fi game: listens on an ephemeral port of every interface. */
expect class LanHost(scope: CoroutineScope) {
    /** Binds and returns the chosen TCP port (number). */
    suspend fun bind(): Int
    suspend fun accept(): PeerLink
    fun close()
}

/** Guest side of a direct Wi-Fi game. */
expect object LanClient {
    suspend fun connect(scope: CoroutineScope, host: String, port: Int, timeoutMs: Long = 8_000): PeerLink
}

/** MQTT over plain TCP 1883 (the fallback when WebSockets fail). Throws where there are no raw sockets (web). */
internal expect suspend fun openTcpMqtt(scope: CoroutineScope, b: Broker): MqttTransport
