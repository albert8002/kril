package app.coredump.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * "CORE DUMP", the app's music: one theme in 7/4, arranged differently for every look.
 *
 * Meter: 7 quarter beats per bar, felt as 4 + 3 (like a bar of 4/4 with a bar of 3/4 glued on).
 *  - kick and bass mark the group starts, beats 1 and 5; the snare answers on beats 3 and 7,
 *  - chords change on beats 1 and 5,
 *  - the final group of three is laid back by a hair (8 ms) so it never rushes the bar line,
 *  - the bar before each return of the theme ends with a snare fill.
 * Key: D minor. Form A – B – A' (12 bars), so the theme comes back every loop:
 *  - the hook ([HOOK]): D – F – E – A, long-short-short-long, filling beats 1–4; three rising notes answer it on
 *    beats 5–7. Bars 1–3 state it three times, each a step lower (D, C, B♭), bar 4 cadences on A7sus4.
 *  - B (bars 5–8): a higher, longer-breathed answer over B♭maj7 C | Am7 Dm7 | Gm7 C | B♭ A7, falling to A.
 *  - A' (bars 9–12): the hook returns, doubled an octave up, and closes on C♯ → E, back into bar 1.
 * The same hook is the "lists fetched" and end-of-game sound ([Sfx]), so it recurs outside the music too.
 *
 * Rendered once on the device into one loop (mono 16-bit, [RATE] Hz). Note tails, echo and filter wrap around the
 * end of the loop, so it repeats without a seam.
 */
internal object Score {
    const val RATE = 22050
    /** Eighth notes per bar (7 quarter beats). */
    const val EIGHTHS = 14

    /** The hook as (start eighth, length in eighths, semitones from its first note): D F E A. */
    val HOOK = intArrayOf(0, 3, 0, 3, 1, 3, 4, 2, 2, 6, 2, -5)

    // ---- the composition

    /** A chord for half a bar: tones (MIDI note numbers) and the bass root (MIDI). */
    private class Chord(val tones: IntArray, val root: Int)

    private val DM7 = Chord(intArrayOf(50, 53, 57, 60), 38)      // D F A C
    private val BBMAJ7 = Chord(intArrayOf(46, 50, 53, 57), 34)   // B♭ D F A
    private val C = Chord(intArrayOf(48, 52, 55, 60), 36)        // C E G C
    private val GM7 = Chord(intArrayOf(43, 46, 50, 53), 31)      // G B♭ D F
    private val AM7 = Chord(intArrayOf(45, 48, 52, 55), 33)      // A C E G
    private val BB = Chord(intArrayOf(46, 50, 53, 58), 34)       // B♭ D F B♭
    private val A7SUS = Chord(intArrayOf(45, 50, 52, 55), 33)    // A D E G
    private val A7 = Chord(intArrayOf(45, 49, 52, 55), 33)       // A C♯ E G

    /** Two chords per bar: [bar * 2] for beats 1–4, [bar * 2 + 1] for beats 5–7. */
    private val halves = listOf(
        // A
        DM7, DM7, BBMAJ7, C, GM7, AM7, BB, A7SUS,
        // B
        BBMAJ7, C, AM7, DM7, GM7, C, BB, A7,
        // A'
        DM7, DM7, BBMAJ7, C, GM7, AM7, BB, A7,
    )

    /** The hook on [first] (MIDI) at the start of a bar, then [answer]: (start, length, note) triples. */
    private fun hookBar(first: Int, vararg answer: Int): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i + 2 < HOOK.size) { out += HOOK[i]; out += HOOK[i + 1]; out += first + HOOK[i + 2]; i += 3 }
        return (out + answer.toList()).toIntArray()
    }

    // MIDI: D4 62, F4 65, G4 67, A4 69, B♭4 70, C5 72, C♯5 73, D5 74, E5 76, F5 77, G5 79
    private val themeA: List<IntArray> = listOf(
        hookBar(74, 8, 2, 67, 10, 2, 69, 12, 2, 72),          // D F E A | G A C
        hookBar(72, 8, 2, 65, 10, 2, 67, 12, 2, 70),          // C E D G | F G B♭   (a step lower)
        hookBar(70, 8, 2, 67, 10, 2, 69, 12, 2, 72),          // B♭ D C F | G A C   (a step lower again)
    )

    /** Melody per bar: (start eighth, length in eighths, MIDI note) triples. */
    private val melody: List<IntArray> = themeA + listOf(
        intArrayOf(0, 6, 74, 6, 2, 72, 8, 4, 74, 12, 2, 76),                                  // D C | D E (over A7sus4)
        // B
        intArrayOf(0, 4, 77, 4, 2, 76, 6, 2, 74, 8, 4, 76, 12, 2, 79),                        // F E D | E G
        intArrayOf(0, 6, 76, 6, 2, 72, 8, 4, 69, 12, 2, 74),                                  // E C | A D
        intArrayOf(0, 3, 70, 3, 1, 72, 4, 2, 74, 6, 2, 77, 8, 2, 76, 10, 2, 74, 12, 2, 72),   // B♭ C D F | E D C
        intArrayOf(0, 6, 74, 6, 2, 77, 8, 2, 76, 10, 2, 73, 12, 2, 69),                       // D F | E C♯ A → the hook
    ) + themeA + listOf(
        intArrayOf(0, 6, 74, 6, 2, 72, 8, 4, 73, 12, 2, 76),                                  // D C | C♯ E → bar 1
    )

    /** Bars where the hook returns brighter (doubled an octave up). */
    private val doubled = 8..10

    val BARS: Int get() = melody.size

    /** Arpeggio order over a chord's four tones, per eighth (4-beat half, then 3-beat half). */
    private val arpLong = intArrayOf(0, 2, 1, 3, 2, 1, 3, 2)
    private val arpShort = intArrayOf(0, 2, 1, 3, 2, 1)

    // ---- instruments and arrangements

    enum class W { SINE, TRI, SQUARE, PULSE, SAW, BELL, GLASS, CHIME, KEYS }

    /** attack / release: s. decay: sustain fall per second (0 = organ-like). level: 0..1. octave: shift (octaves). */
    class Inst(val wave: W, val attack: Float, val release: Float, val level: Float, val decay: Float = 0f,
               val vibrato: Float = 0f, val octave: Int = 0)

    enum class Kit { NONE, CHIP, SOFT, SYNTH, BRUSH, LOFI, HEART }

    /**
     * bpm: quarter beats per minute. tone: low-pass 0..1 (1 = open). echo: feedback 0..0.6 after [echoBeats] beats.
     * swing: off-beat eighths late by this part of an eighth. drums: drum level 0..1.
     */
    class Arr(
        val bpm: Float, val lead: Inst, val bass: Inst, val pad: Inst?, val arp: Inst?, val kit: Kit,
        val tone: Float = 1f, val echo: Float = 0f, val echoBeats: Float = 0.75f, val swing: Float = 0f,
        val transpose: Int = 0, val drums: Float = 0.5f,
    )

    fun arrOf(s: Style): Arr = when (s) {
        // green-screen chiptune: square lead, triangle bass, beeper drums
        Style.TERMINAL -> Arr(108f, Inst(W.SQUARE, 0.004f, 0.06f, 0.32f, decay = 1.2f), Inst(W.TRI, 0.004f, 0.05f, 0.55f),
            null, Inst(W.PULSE, 0.002f, 0.04f, 0.12f, decay = 6f, octave = 1), Kit.CHIP, tone = 0.42f, echo = 0.18f)
        // newsroom: felt piano, soft upright bass, brushes
        Style.PAPER -> Arr(96f, Inst(W.BELL, 0.003f, 0.35f, 0.42f, decay = 1.6f), Inst(W.TRI, 0.01f, 0.12f, 0.5f),
            Inst(W.SINE, 0.25f, 0.6f, 0.1f), null, Kit.BRUSH, tone = 0.55f, echo = 0.12f, swing = 0.08f)
        // synthwave: saw lead with vibrato, saw bass, pads, arpeggio echo
        Style.NEON -> Arr(116f, Inst(W.SAW, 0.01f, 0.25f, 0.28f, decay = 0.4f, vibrato = 0.004f), Inst(W.SAW, 0.004f, 0.08f, 0.42f),
            Inst(W.SAW, 0.3f, 0.7f, 0.07f), Inst(W.PULSE, 0.002f, 0.06f, 0.12f, decay = 4f, octave = 1), Kit.SYNTH,
            tone = 0.4f, echo = 0.3f, echoBeats = 0.75f, drums = 0.6f)
        // quiz show: marimba, round bass, light kit
        Style.CLEAN -> Arr(100f, Inst(W.TRI, 0.002f, 0.12f, 0.45f, decay = 5f), Inst(W.SINE, 0.006f, 0.08f, 0.6f),
            Inst(W.SINE, 0.2f, 0.5f, 0.08f), null, Kit.SOFT, tone = 0.8f, echo = 0.1f)
        // handheld RPG: pulse lead, triangle bass, square arpeggio, noise drums
        Style.PIXEL -> Arr(126f, Inst(W.PULSE, 0.003f, 0.05f, 0.3f, decay = 0.8f), Inst(W.TRI, 0.003f, 0.04f, 0.55f),
            null, Inst(W.SQUARE, 0.002f, 0.03f, 0.1f, decay = 7f, octave = 1), Kit.CHIP, tone = 0.5f)
        // cartoon: pizzicato lead, bouncy bass
        Style.COMIC -> Arr(120f, Inst(W.SINE, 0.002f, 0.1f, 0.5f, decay = 7f, octave = 1), Inst(W.TRI, 0.003f, 0.05f, 0.55f, decay = 4f),
            null, null, Kit.SOFT, tone = 0.9f, swing = 0.1f, drums = 0.45f)
        // the deep: slow sine lead in a long echo, pad, glassy arpeggio, a heartbeat
        Style.ABYSS -> Arr(80f, Inst(W.SINE, 0.05f, 0.6f, 0.42f, decay = 0.3f, vibrato = 0.003f), Inst(W.SINE, 0.03f, 0.3f, 0.5f),
            Inst(W.SINE, 0.6f, 1.2f, 0.12f), Inst(W.GLASS, 0.004f, 0.5f, 0.06f, decay = 2f, octave = 1), Kit.HEART,
            tone = 0.3f, echo = 0.38f, echoBeats = 1.5f, transpose = -2)
        // station: chimes, steady rail-click hats
        Style.METRO -> Arr(112f, Inst(W.CHIME, 0.003f, 0.4f, 0.38f, decay = 1.5f), Inst(W.TRI, 0.006f, 0.08f, 0.5f),
            null, null, Kit.SOFT, tone = 0.7f, echo = 0.15f, drums = 0.55f)
        // lounge: vibraphone, walking-ish bass, brushes, swing
        Style.JACKPOT -> Arr(104f, Inst(W.BELL, 0.004f, 0.5f, 0.38f, decay = 1.2f, vibrato = 0.006f), Inst(W.SINE, 0.008f, 0.1f, 0.6f),
            Inst(W.TRI, 0.25f, 0.6f, 0.07f), null, Kit.BRUSH, tone = 0.6f, echo = 0.14f, swing = 0.18f)
        // lo-fi: electric-piano keys, round bass, dusty drums, everything filtered
        Style.CHAT -> Arr(88f, Inst(W.KEYS, 0.005f, 0.3f, 0.42f, decay = 1.1f), Inst(W.SINE, 0.01f, 0.1f, 0.6f),
            Inst(W.KEYS, 0.2f, 0.6f, 0.08f), null, Kit.LOFI, tone = 0.32f, echo = 0.16f, swing = 0.14f, drums = 0.55f)
        // old desktop: FM-ish keys, square bass, soft kit
        Style.DESKTOP -> Arr(108f, Inst(W.TRI, 0.004f, 0.2f, 0.42f, decay = 1.8f, vibrato = 0.003f), Inst(W.SQUARE, 0.004f, 0.06f, 0.3f),
            Inst(W.TRI, 0.2f, 0.5f, 0.07f), null, Kit.SOFT, tone = 0.5f, echo = 0.12f)
        // tarot table: glass bells, drone pad, chime arpeggio, heartbeat
        Style.ORACLE -> Arr(84f, Inst(W.GLASS, 0.006f, 0.8f, 0.36f, decay = 0.9f, vibrato = 0.004f), Inst(W.SINE, 0.03f, 0.25f, 0.5f),
            Inst(W.SINE, 0.5f, 1.0f, 0.1f), Inst(W.CHIME, 0.004f, 0.4f, 0.06f, decay = 2.5f, octave = 1), Kit.HEART,
            tone = 0.55f, echo = 0.38f, echoBeats = 1f)
    }

    // ---- rendering

    /** Length of the loop for [s] (s). */
    fun seconds(s: Style): Float = 60f / arrOf(s).bpm * 7 * BARS

    /** The loop for [s]; [stop] is asked now and then (true = give up, an empty array is returned). */
    fun render(s: Style, stop: () -> Boolean = { false }): ShortArray {
        val a = arrOf(s)
        val beat = 60f / a.bpm
        val e8 = beat / 2f
        val bar = beat * 7
        val n = (bar * BARS * RATE).roundToInt()
        val out = FloatArray(n)
        val rnd = Random(77)

        // when an eighth sounds: swing on the off-beats, the last group of three laid back a little
        fun at(b: Int, eighth: Int): Float {
            var t = b * bar + eighth * e8
            if (a.swing > 0f && eighth % 2 == 1) t += a.swing * e8
            if (eighth >= 12) t += 0.008f
            return t
        }

        fun play(inst: Inst, midi: Int, t: Float, len: Float, gain: Float) =
            note(out, inst, hz(midi + a.transpose + inst.octave * 12), t, len, gain)

        for (h in 0 until BARS * 2) {
            if (stop()) return ShortArray(0)
            val b = h / 2
            val first = h % 2 == 0
            val ch = halves[h]
            val e0 = if (first) 0 else 8
            val span = if (first) 8 else 6
            // pad: the chord held for the half bar
            a.pad?.let { p -> for (tn in ch.tones) play(p, tn, at(b, e0), span * e8 * 0.97f, 0.9f) }
            // bass: group of four = root (dotted), root, fifth, octave; group of three = root, fifth, approach note
            val r = ch.root
            if (first) {
                play(a.bass, r, at(b, 0), 2.6f * e8, 1f)
                play(a.bass, r, at(b, 3), 0.9f * e8, 0.7f)
                play(a.bass, r + 7, at(b, 4), 1.8f * e8, 0.85f)
                play(a.bass, r + 12, at(b, 6), 1.8f * e8, 0.8f)
            } else {
                val next = halves[(h + 1) % halves.size].root
                play(a.bass, r, at(b, 8), 1.8f * e8, 0.95f)
                play(a.bass, r + 7, at(b, 10), 1.8f * e8, 0.8f)
                play(a.bass, next - 1, at(b, 12), 1.8f * e8, 0.75f)   // chromatic step up into the next bar
            }
            // arpeggio: eighths through the chord, an octave up
            a.arp?.let { p ->
                val order = if (first) arpLong else arpShort
                for ((i, k) in order.withIndex()) play(p, ch.tones[k] + 12, at(b, e0 + i), 0.9f * e8, if (i == 0) 1f else 0.75f)
            }
        }
        // melody (the returning hook doubled an octave up, softly)
        for ((b, m) in melody.withIndex()) {
            var i = 0
            while (i + 2 < m.size) {
                val start = m[i]
                val len = m[i + 1]
                val g = if (start == 0 || start == 8) 1f else 0.85f
                play(a.lead, m[i + 2], at(b, start), len * e8 * 0.92f, g)
                if (b in doubled && start < 8) play(a.lead, m[i + 2] + 12, at(b, start), len * e8 * 0.92f, g * 0.32f)
                i += 3
            }
        }
        // drums
        if (stop()) return ShortArray(0)
        if (a.kit != Kit.NONE) for (b in 0 until BARS) drumBar(out, a, b, ::at, rnd)
        if (stop()) return ShortArray(0)

        // filter and echo, both around the loop
        if (a.tone < 1f) lowPass(out, a.tone)
        if (a.echo > 0f) echo(out, (a.echoBeats * beat * RATE).roundToInt(), a.echo)

        // level: peak to 0.85, soft limit
        var peak = 0.0001f
        for (x in out) peak = maxOf(peak, abs(x))
        val g = 0.85f / peak
        return ShortArray(n) {
            val x = out[it] * g
            ((x / (1f + abs(x) * 0.25f)) * 1.25f * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
        }
    }

    private fun hz(midi: Int): Float = 440f * 2f.pow((midi - 69) / 12f)

    private fun osc(w: W, phase: Double): Float {
        val p = phase - floor(phase)
        val tp = 2 * PI * p
        return when (w) {
            W.SINE -> sin(tp).toFloat()
            W.TRI -> (4 * abs(p - 0.5) - 1).toFloat()
            W.SQUARE -> if (p < 0.5) 0.6f else -0.6f
            W.PULSE -> if (p < 0.25) 0.6f else -0.6f
            W.SAW -> (2 * p - 1).toFloat() * 0.7f
            W.BELL -> (sin(tp) + 0.45 * sin(tp * 2.0) + 0.18 * sin(tp * 3.0) + 0.08 * sin(tp * 4.07)).toFloat() * 0.6f
            W.GLASS -> (sin(tp) + 0.3 * sin(tp * 2.41) + 0.16 * sin(tp * 3.93)).toFloat() * 0.7f
            W.CHIME -> (sin(tp) + 0.35 * sin(tp * 3.0) + 0.12 * sin(tp * 4.07)).toFloat() * 0.7f
            // electric piano: fundamental, a soft octave and a little bark on top
            W.KEYS -> (sin(tp) + 0.25 * sin(tp * 2.0) + 0.06 * sin(tp * 7.0)).toFloat() * 0.75f
        }
    }

    /** One note into the loop (wraps around the end). len: held time (s); the release follows. */
    private fun note(out: FloatArray, inst: Inst, f: Float, t: Float, len: Float, gain: Float) {
        val n = out.size
        val start = (t * RATE).toInt()
        val held = maxOf(len, inst.attack)
        val total = ((held + inst.release) * RATE).toInt()
        val dph = f.toDouble() / RATE
        val endLevel = exp(-inst.decay * (held - inst.attack))
        var ph = 0.0
        for (i in 0 until total) {
            val s = i.toFloat() / RATE
            val env = when {
                s < inst.attack -> s / inst.attack
                s < held -> exp(-inst.decay * (s - inst.attack))
                else -> endLevel * exp(-(s - held) * 5f / inst.release)
            }
            ph += if (inst.vibrato > 0f) dph * (1.0 + inst.vibrato * sin(2 * PI * 5.2 * s)) else dph
            out[(start + i) % n] += osc(inst.wave, ph) * env * gain * inst.level
        }
    }

    /** One bar of drums. Kick on 1 and 5 (+ a push on the "and" of 2), snare on 3 and 7, hats on eighths; bar 8 fills. */
    private fun drumBar(out: FloatArray, a: Arr, b: Int, at: (Int, Int) -> Float, rnd: Random) {
        val lv = a.drums
        // fill into each return of the theme (and into the B section)
        val fill = b == BARS - 1 || b == 7
        when (a.kit) {
            Kit.HEART -> {
                // just a soft double heartbeat on the group starts
                kick(out, at(b, 0), 0.7f * lv, 70f, rnd, soft = true)
                kick(out, at(b, 1), 0.4f * lv, 70f, rnd, soft = true)
                kick(out, at(b, 8), 0.55f * lv, 70f, rnd, soft = true)
                return
            }
            else -> Unit
        }
        val chip = a.kit == Kit.CHIP
        val soft = a.kit == Kit.SOFT || a.kit == Kit.BRUSH || a.kit == Kit.LOFI
        kick(out, at(b, 0), 1f * lv, if (chip) 160f else 110f, rnd, soft = soft, chip = chip)
        kick(out, at(b, 3), 0.55f * lv, if (chip) 160f else 110f, rnd, soft = soft, chip = chip)
        kick(out, at(b, 8), 0.9f * lv, if (chip) 160f else 110f, rnd, soft = soft, chip = chip)
        if (fill) {
            for ((k, e) in (9..13).withIndex()) snare(out, at(b, e), (0.35f + 0.15f * k) * lv, rnd, a.kit)
        } else {
            snare(out, at(b, 4), 0.9f * lv, rnd, a.kit)
            snare(out, at(b, 11), 0.22f * lv, rnd, a.kit)   // ghost note
            snare(out, at(b, 12), 0.85f * lv, rnd, a.kit)
        }
        val hats = if (fill) 0..8 else 0..13
        for (e in hats) {
            val accent = when { e == 0 || e == 8 -> 0.55f; e % 2 == 0 -> 0.4f; else -> 0.26f }
            val open = !fill && e == 13
            hat(out, at(b, e), accent * lv * (if (a.kit == Kit.BRUSH) 0.6f else 1f), rnd, open = open, chip = chip)
        }
    }

    private fun kick(out: FloatArray, t: Float, gain: Float, f0: Float, rnd: Random, soft: Boolean = false, chip: Boolean = false) {
        val n = out.size
        val start = (t * RATE).toInt()
        val len = (0.3f * RATE).toInt()
        var ph = 0.0
        for (i in 0 until len) {
            val s = i.toFloat() / RATE
            val f = 42f + (f0 - 42f) * exp(-s * 28f)
            ph += f / RATE
            val p = ph - floor(ph)
            val x = if (chip) (if (p < 0.5) 0.6f else -0.6f) else sin(2 * PI * p).toFloat()
            val click = if (!soft && i < 60) (rnd.nextFloat() * 2f - 1f) * 0.25f * (1f - i / 60f) else 0f
            out[(start + i) % n] += (x * exp(-s * (if (soft) 14f else 10f)) + click) * gain * 0.9f
        }
    }

    private fun snare(out: FloatArray, t: Float, gain: Float, rnd: Random, kit: Kit) {
        val n = out.size
        val start = (t * RATE).toInt()
        val brush = kit == Kit.BRUSH
        val chip = kit == Kit.CHIP
        val len = ((if (brush) 0.28f else 0.2f) * RATE).toInt()
        var held = 0f
        var lp = 0f
        for (i in 0 until len) {
            val s = i.toFloat() / RATE
            // chip: sample-and-hold noise (crunchy); others: white noise, softened for brushes and lo-fi
            val noise = if (chip) { if (i % 4 == 0) held = rnd.nextFloat() * 2f - 1f; held } else rnd.nextFloat() * 2f - 1f
            lp += (if (brush || kit == Kit.LOFI) 0.25f else 0.7f) * (noise - lp)
            val body = if (brush || chip) 0f else sin(2 * PI * 185.0 * s).toFloat() * exp(-s * 35f) * 0.5f
            val env = exp(-s * (if (brush) 11f else 22f))
            out[(start + i) % n] += (lp * env * 0.75f + body) * gain
        }
    }

    private fun hat(out: FloatArray, t: Float, gain: Float, rnd: Random, open: Boolean, chip: Boolean) {
        val n = out.size
        val start = (t * RATE).toInt()
        val len = ((if (open) 0.22f else 0.05f) * RATE).toInt()
        var prev = 0f
        var held = 0f
        for (i in 0 until len) {
            val s = i.toFloat() / RATE
            val w = if (chip) { if (i % 2 == 0) held = rnd.nextFloat() * 2f - 1f; held } else rnd.nextFloat() * 2f - 1f
            val hp = w - prev   // high-passed noise: only the hiss
            prev = w
            out[(start + i) % n] += hp * exp(-s * (if (open) 14f else 70f)) * gain * 0.35f
        }
    }

    /** One-pole low-pass around the loop (two passes so the start continues from the end). */
    private fun lowPass(out: FloatArray, tone: Float) {
        val k = tone.coerceIn(0.02f, 1f)
        var y = 0f
        for (x in out) y += k * (x - y)
        for (i in out.indices) { y += k * (out[i] - y); out[i] = y }
    }

    /** Feedback echo around the loop: y[i] = x[i] + fb · y[i − d] (two passes settle the wrap-around). */
    private fun echo(out: FloatArray, d: Int, fb: Float) {
        val n = out.size
        if (d <= 0 || d >= n) return
        val x = out.copyOf()
        repeat(2) {
            for (i in 0 until n) out[i] = x[i] + fb * out[(i - d + n) % n]
        }
    }
}
