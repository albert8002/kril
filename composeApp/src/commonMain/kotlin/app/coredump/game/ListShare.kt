package app.coredump.game

import app.coredump.core.PackCodec
import app.coredump.data.QuestionBank
import app.coredump.net.Msg
import app.coredump.net.PeerLink
import app.coredump.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wraps a 1v1 link and swaps collected lists with the other player in the background, without any UI:
 *  1. each side announces the ids of its collected lists (Wikidata pool / Wikipedia lists only),
 *  2. each side asks for the ones it lacks (not discarded, at most [MAX_GET] per session),
 *  3. lists travel as ranked-pack JSON in pieces of ≤ [PIECE] characters, one piece every [GAP_MS] ms,
 *     so game messages are never held up.
 * Received lists are rebuilt from the app's own template (prompt text, category, check pattern); only the
 * ranked answers come from the peer. Game messages pass through unchanged.
 */
class SharingLink(
    private val inner: PeerLink,
    private val bank: QuestionBank,
    private val scope: CoroutineScope,
    private val enabled: Boolean,
) : PeerLink {
    override val incoming = Channel<Msg>(Channel.UNLIMITED)
    override val via: String get() = inner.via
    private val out = Channel<Msg>(Channel.UNLIMITED)
    private val jobs = ArrayList<Job>()
    private val pieces = HashMap<String, Array<String?>>()
    private val asked = HashSet<String>()
    private val sent = HashSet<String>()

    init {
        jobs += scope.launch {
            for (m in inner.incoming) when (m) {
                is Msg.LibHave -> if (enabled) onHave(m)
                is Msg.LibWant -> if (enabled) onWant(m)
                is Msg.LibPart -> if (enabled) onPart(m)
                else -> incoming.send(m)
            }
            incoming.close()
        }
        // background sender: one sharing message at a time, spaced out
        jobs += scope.launch {
            for (m in out) {
                if (!inner.send(m)) break
                delay(GAP_MS)
            }
        }
        if (enabled) jobs += scope.launch {
            delay(2_000)
            announce()
            delay(90_000)   // lists collected meanwhile (or after the peer caught up) get a second chance
            announce()
        }
    }

    private fun shareable(id: String) = bank.isShareable(id)

    private suspend fun announce() {
        val ids = bank.all().asSequence().map { it.id }.filter { shareable(it) }.toList()
        for (chunk in ids.chunked(300)) out.send(Msg.LibHave(chunk))
    }

    private suspend fun onHave(m: Msg.LibHave) {
        val want = m.ids.asSequence()
            .filter { shareable(it) && it !in asked && bank.get(it) == null && it !in app.coredump.data.Discards.ids }
            .take((MAX_GET - asked.size).coerceAtLeast(0)).toList()
        if (want.isEmpty()) return
        asked += want
        for (chunk in want.chunked(25)) out.send(Msg.LibWant(chunk))
    }

    private suspend fun onWant(m: Msg.LibWant) {
        for (id in m.ids.take(200)) {
            if (!shareable(id) || !sent.add(id)) continue
            val p = bank.get(id) ?: continue
            val text = PackCodec.encodeRanked(listOf(p), Platform.epochMillis())
            if (text.length > PIECE * MAX_PIECES) continue
            val n = (text.length + PIECE - 1) / PIECE
            for (i in 0 until n) out.send(Msg.LibPart(id, i, n, text.substring(i * PIECE, minOf(text.length, (i + 1) * PIECE))))
        }
    }

    private suspend fun onPart(m: Msg.LibPart) {
        if (m.id !in asked || m.of !in 1..MAX_PIECES || m.part !in 0 until m.of || m.data.length > PIECE) return
        val arr = pieces.getOrPut(m.id) { arrayOfNulls(m.of) }
        if (arr.size != m.of) return
        arr[m.part] = m.data
        if (arr.any { it == null }) return
        pieces.remove(m.id)
        val text = arr.joinToString("") { it.orEmpty() }
        bank.addShared(m.id, text)
    }

    override suspend fun send(m: Msg): Boolean = inner.send(m)

    override fun close() {
        jobs.forEach { it.cancel() }
        out.close()
        inner.close()
        incoming.close()
    }

    companion object {
        /** Characters per piece (fits public relays comfortably). */
        const val PIECE = 12_000
        const val MAX_PIECES = 40
        /** Lists taken from one peer per session (count). */
        const val MAX_GET = 150
        const val GAP_MS = 300L
    }
}
