package app.coredump.sigil

import app.coredump.net.ShortCode

/**
 * One look's animated code: how it is drawn ([draw]) and how a camera reads it ([reader]). Every look has its own
 * concept (a particle ball, punched tape, poker chips, a night sky …); one scanner ([SigilScanner]) tries them all.
 *
 * Contract:
 *  - [draw] adds the frame at time [t] (seconds since the code appeared) to [out], in the unit square, from the 84
 *    payload [bits] ([SigilPayload.bits]). Data must live in lightness: [Ink.main] / [Ink.alt] against [Ink.plate]
 *    (hue and [Ink.soft] / [Ink.faint] may move freely as decoration). It must be cheap (it runs 30 times a second)
 *    and must not keep state between calls (t alone decides the frame).
 *  - [reader] gives a fresh reader; the reader keeps whatever it needs between frames (temporal codes) and returns a
 *    [Read] once it has the code. It must stay quick (a few milliseconds for a 640×480 frame) and never invent a code
 *    for a frame that does not show one (use [SigilPayload] with its default parity budget).
 */
interface SigilScheme {
    /** Short name for logs, e.g. "particles". */
    val name: String

    fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene)

    fun reader(): SchemeReader
}

interface SchemeReader {
    /** One camera frame (frames come in time order). The code, or null (not yet / not here). */
    fun feed(f: SigilFrame): Read?

    /** Whether the last [feed] saw this scheme's shape (the scanner then keeps feeding it first). */
    val seen: Boolean
}

/** Every look's scheme, in [app.coredump.ui.Style] order. */
object SigilSchemes {
    val all: List<SigilScheme> by lazy {
        listOf(
            app.coredump.sigil.schemes.ParticleSphere,   // TERMINAL
            app.coredump.sigil.schemes.PunchedTape,      // GAZETTE
            app.coredump.sigil.schemes.SynthSun,         // ARCADE
            app.coredump.sigil.schemes.BuzzerLamps,      // QUIZ SHOW
            app.coredump.sigil.schemes.RuneCircle,       // QUEST
            app.coredump.sigil.schemes.Kapow,            // COMIC
            app.coredump.sigil.schemes.Sonar,            // ABYSS
            app.coredump.sigil.schemes.LoopTrain,        // METRO
            app.coredump.sigil.schemes.ChipStack,        // JACKPOT
            app.coredump.sigil.schemes.VoiceNote,        // CHAT
            app.coredump.sigil.schemes.Defrag,           // DESKTOP
            app.coredump.sigil.schemes.Constellation,    // ORACLE
        )
    }

    fun forLook(ordinal: Int): SigilScheme = all[ordinal.mod(all.size)]
}

/**
 * The camera side: feeds every frame to every look's reader (the ones that recently saw their shape first, the rest in
 * turn), and answers with a short code ("CD1:…") once it is sure: a read that needed no repair, or the same code
 * twice within a few seconds.
 */
class SigilScanner(schemes: List<SigilScheme> = SigilSchemes.all) {
    private val readers = schemes.map { it.reader() }
    private val names = schemes.map { it.name }
    private val seenAt = LongArray(readers.size) { Long.MIN_VALUE / 2 }
    private var turn = 0
    private val heard = ArrayList<Pair<String, Long>>()

    /** Which scheme answered last (for logs). */
    var lastScheme: String = ""
        private set

    fun feed(frame: SigilFrame): String? {
        // big frames: halve (the codes are drawn big; this keeps every reader quick)
        val f = if (kotlin.math.max(frame.w, frame.h) > 1000) frame.shrink(2) else frame
        val now = f.tMs
        val locked = readers.indices.filter { now - seenAt[it] < 1500 }
        val order = if (locked.isEmpty()) readers.indices.toList()
        else locked + listOf(readers.indices.filter { it !in locked }.let { if (it.isEmpty()) -1 else it[turn++ % it.size] }).filter { it >= 0 }
        for (i in order) {
            val r = readers[i]
            val read = runCatching { r.feed(f) }.getOrNull()
            if (r.seen) seenAt[i] = now
            if (read != null) {
                val code = ShortCode.encode(read.code.room, read.code.broker, read.code.party)
                heard.removeAll { now - it.second > 6000 }
                if (read.fixes == 0 || heard.any { it.first == code }) { lastScheme = names[i]; return code }
                heard.add(code to now)
            }
        }
        return null
    }
}
