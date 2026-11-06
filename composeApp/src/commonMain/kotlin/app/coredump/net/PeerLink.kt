package app.coredump.net

import kotlinx.coroutines.channels.ReceiveChannel

/** A message pipe to the other player: direct Wi-Fi socket or online relay. */
interface PeerLink {
    val incoming: ReceiveChannel<Msg>
    suspend fun send(m: Msg): Boolean
    fun close()
    /** Short label for the UI, e.g. "relay · broker.hivemq.com · wss". */
    val via: String
}
