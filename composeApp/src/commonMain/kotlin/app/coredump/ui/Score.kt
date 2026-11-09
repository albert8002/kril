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
 * "CORE DUMP", the app's music: one theme in 7/4 and a variation of it for every look (own tempo, scale, groove,
 * bass line and instruments; [arrOf]). Terminal's variation, "Deep Sea", is the standard.
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

    /** Scale the song is played in: aeolian (D minor as written), dorian (B♮), major (F♯ C♯ B♮), harmonic (C♯). */
    enum class Mode { AEOLIAN, DORIAN, MAJOR, HARMONIC }

    /**
     * Bass line. GROUPS: root, fifth, octave on the 4 + 3 groups (as written). WALK: a quarter-note walk through the
     * chord into the next one. PULSE: driving eighths on the root. OCTAVE: eighths jumping root ↔ octave. DRONE: one
     * long root per chord with a soft fifth above.
     */
    enum class Bass { GROUPS, WALK, PULSE, OCTAVE, DRONE }

    /**
     * One variation of the song.
     * bpm: quarter beats per minute. tone: low-pass 0..1 (1 = open). echo: feedback 0..0.6 after [echoBeats] beats.
     * swing: off-beat eighths late by this part of an eighth. drums: drum level 0..1.
     * mode / bassStyle: see [Mode], [Bass]. twoTwoThree: drums felt 2 + 2 + 3 instead of 4 + 3.
     * ornament: long melody notes turn around their upper neighbour. legato: held part of each melody note (0..1).
     */
    class Arr(
        val name: String,
        val bpm: Float, val lead: Inst, val bass: Inst, val pad: Inst?, val arp: Inst?, val kit: Kit,
        val tone: Float = 1f, val echo: Float = 0f, val echoBeats: Float = 0.75f, val swing: Float = 0f,
        val transpose: Int = 0, val drums: Float = 0.5f,
        val mode: Mode = Mode.AEOLIAN, val bassStyle: Bass = Bass.GROUPS, val twoTwoThree: Boolean = false,
        val ornament: Boolean = false, val legato: Float = 0.92f,
    )

    /** Every look has its own variation; Terminal's is the deep-sea one. */
    fun arrOf(s: Style): Arr = when (s) {
        // "Deep Sea": slow sine lead in a long echo, pad, glassy arpeggio, a heartbeat (the standard)
        Style.TERMINAL -> Arr("Deep Sea", 80f, Inst(W.SINE, 0.05f, 0.6f, 0.42f, decay = 0.3f, vibrato = 0.003f), Inst(W.SINE, 0.03f, 0.3f, 0.5f),
            Inst(W.SINE, 0.6f, 1.2f, 0.12f), Inst(W.GLASS, 0.004f, 0.5f, 0.06f, decay = 2f, octave = 1), Kit.HEART,
            tone = 0.3f, echo = 0.38f, echoBeats = 1.5f, transpose = -2)
        // "Morning Edition": the song in D major, felt piano, stride-like bass, brushes, a little swing
        Style.PAPER -> Arr("Morning Edition", 100f, Inst(W.BELL, 0.003f, 0.35f, 0.42f, decay = 1.8f), Inst(W.TRI, 0.006f, 0.08f, 0.5f, decay = 2f),
            Inst(W.SINE, 0.25f, 0.6f, 0.08f), null, Kit.BRUSH, tone = 0.6f, echo = 0.1f, swing = 0.1f,
            mode = Mode.MAJOR, bassStyle = Bass.OCTAVE, legato = 0.7f)
        // "Night Drive": synthwave, driving eighth bass, arpeggio echo, ornamented saw lead
        Style.NEON -> Arr("Night Drive", 116f, Inst(W.SAW, 0.01f, 0.25f, 0.28f, decay = 0.4f, vibrato = 0.004f), Inst(W.SAW, 0.004f, 0.06f, 0.4f, decay = 3f),
            Inst(W.SAW, 0.3f, 0.7f, 0.07f), Inst(W.PULSE, 0.002f, 0.06f, 0.12f, decay = 4f, octave = 1), Kit.SYNTH,
            tone = 0.4f, echo = 0.3f, echoBeats = 0.75f, drums = 0.6f, bassStyle = Bass.PULSE, ornament = true)
        // "Studio Lights": game-show major, marimba, bright 2 + 2 + 3 kit
        Style.CLEAN -> Arr("Studio Lights", 112f, Inst(W.TRI, 0.002f, 0.12f, 0.45f, decay = 5f), Inst(W.SINE, 0.006f, 0.08f, 0.6f),
            Inst(W.SINE, 0.2f, 0.5f, 0.08f), null, Kit.SOFT, tone = 0.85f, echo = 0.1f, mode = Mode.MAJOR, twoTwoThree = true)
        // "Overworld": fast 8-bit dorian, pulse lead with turns, octave-jumping triangle bass, noise drums
        Style.PIXEL -> Arr("Overworld", 138f, Inst(W.PULSE, 0.003f, 0.05f, 0.3f, decay = 0.8f), Inst(W.TRI, 0.003f, 0.04f, 0.55f),
            null, Inst(W.SQUARE, 0.002f, 0.03f, 0.08f, decay = 7f, octave = 1), Kit.CHIP, tone = 0.5f,
            mode = Mode.DORIAN, bassStyle = Bass.OCTAVE, ornament = true)
        // "Slapstick": major, pizzicato, bouncy staccato, swing
        Style.COMIC -> Arr("Slapstick", 126f, Inst(W.SINE, 0.002f, 0.1f, 0.5f, decay = 7f, octave = 1), Inst(W.TRI, 0.003f, 0.05f, 0.55f, decay = 5f),
            null, null, Kit.SOFT, tone = 0.9f, swing = 0.14f, drums = 0.45f, mode = Mode.MAJOR, legato = 0.45f, twoTwoThree = true)
        // "Trench": lower, slower and darker than Deep Sea: harmonic minor, drone bass, long echo
        Style.ABYSS -> Arr("Trench", 68f, Inst(W.SINE, 0.08f, 0.8f, 0.42f, decay = 0.2f, vibrato = 0.004f), Inst(W.SINE, 0.06f, 0.6f, 0.55f),
            Inst(W.SINE, 0.8f, 1.6f, 0.13f), Inst(W.GLASS, 0.004f, 0.6f, 0.05f, decay = 1.5f), Kit.HEART,
            tone = 0.25f, echo = 0.45f, echoBeats = 2f, transpose = -7, mode = Mode.HARMONIC, bassStyle = Bass.DRONE)
        // "Rush Hour": motorik eighth bass, chimes, steady 2 + 2 + 3 rail clicks, dorian
        Style.METRO -> Arr("Rush Hour", 120f, Inst(W.CHIME, 0.003f, 0.4f, 0.38f, decay = 1.5f), Inst(W.TRI, 0.004f, 0.05f, 0.5f, decay = 3f),
            null, null, Kit.SOFT, tone = 0.7f, echo = 0.15f, drums = 0.55f, mode = Mode.DORIAN, bassStyle = Bass.PULSE, twoTwoThree = true)
        // "Lounge": vibraphone, walking bass, brushes, heavy swing
        Style.JACKPOT -> Arr("Lounge", 104f, Inst(W.BELL, 0.004f, 0.5f, 0.38f, decay = 1.2f, vibrato = 0.006f), Inst(W.SINE, 0.008f, 0.1f, 0.6f),
            Inst(W.TRI, 0.25f, 0.6f, 0.07f), null, Kit.BRUSH, tone = 0.6f, echo = 0.14f, swing = 0.2f,
            mode = Mode.DORIAN, bassStyle = Bass.WALK)
        // "Lo-fi": electric-piano keys, round bass, dusty drums, everything filtered
        Style.CHAT -> Arr("Lo-fi", 84f, Inst(W.KEYS, 0.005f, 0.3f, 0.42f, decay = 1.1f), Inst(W.SINE, 0.01f, 0.1f, 0.6f),
            Inst(W.KEYS, 0.2f, 0.6f, 0.08f), null, Kit.LOFI, tone = 0.3f, echo = 0.16f, swing = 0.16f, drums = 0.55f, legato = 0.8f)
        // "Startup": old-desktop FM keys in major, square bass, soft kit
        Style.DESKTOP -> Arr("Startup", 108f, Inst(W.TRI, 0.004f, 0.2f, 0.42f, decay = 1.8f, vibrato = 0.003f), Inst(W.SQUARE, 0.004f, 0.06f, 0.3f),
            Inst(W.TRI, 0.2f, 0.5f, 0.07f), Inst(W.BELL, 0.002f, 0.2f, 0.05f, decay = 5f, octave = 1), Kit.SOFT, tone = 0.5f, echo = 0.12f,
            mode = Mode.MAJOR)
        // "Ritual": harmonic minor, glass bells, drone, chime arpeggio, heartbeat
        Style.ORACLE -> Arr("Ritual", 76f, Inst(W.GLASS, 0.006f, 0.8f, 0.36f, decay = 0.9f, vibrato = 0.004f), Inst(W.SINE, 0.05f, 0.5f, 0.5f),
            Inst(W.SINE, 0.5f, 1.0f, 0.1f), Inst(W.CHIME, 0.004f, 0.4f, 0.06f, decay = 2.5f, octave = 1), Kit.HEART,
            tone = 0.55f, echo = 0.38f, echoBeats = 1f, mode = Mode.HARMONIC, bassStyle = Bass.DRONE, ornament = true)
    }

    /** Name of the song of [s] (shown in the settings). */
    fun nameOf(s: Style): String = arrOf(s).name

    /** Key of the song of [s]: semitones from D (the key as written) and the scale; the effects play in it ([Sfx]). */
    fun keyOf(s: Style): Pair<Int, Mode> = arrOf(s).let { it.transpose to it.mode }

    /** [midi] in the variation's [Mode]. */
    private fun inMode(midi: Int, m: Mode): Int {
        val pc = ((midi % 12) + 12) % 12
        val up = when (m) {
            Mode.AEOLIAN -> false
            Mode.DORIAN -> pc == 10
            Mode.MAJOR -> pc == 10 || pc == 5 || pc == 0
            Mode.HARMONIC -> pc == 0
        }
        return if (up) midi + 1 else midi
    }

    /** The next note of the scale above [midi] (for turns). */
    private fun stepUp(midi: Int, m: Mode): Int {
        for (d in 1..2) {
            val n = midi + d
            if (inMode(n, m) == n && isInScale(n, m)) return n
        }
        return midi + 2
    }

    private fun isInScale(midi: Int, m: Mode): Boolean {
        val pc = ((midi % 12) + 12) % 12
        val aeolian = setOf(2, 4, 5, 7, 9, 10, 0)
        val scale = when (m) {
            Mode.AEOLIAN -> aeolian
            Mode.DORIAN -> aeolian - 10 + 11
            Mode.MAJOR -> setOf(2, 4, 6, 7, 9, 11, 1)
            Mode.HARMONIC -> aeolian - 0 + 1
        }
        return pc in scale
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

        /** A note as written (put into the variation's scale). */
        fun play(inst: Inst, midi: Int, t: Float, len: Float, gain: Float) =
            note(out, inst, hz(inMode(midi, a.mode) + a.transpose + inst.octave * 12), t, len, gain)
        /** A note already in the scale. */
        fun playExact(inst: Inst, midi: Int, t: Float, len: Float, gain: Float) =
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
            // bass
            val r = ch.root
            val next = halves[(h + 1) % halves.size].root
            when (a.bassStyle) {
                // group of four = root (dotted), root, fifth, octave; group of three = root, fifth, approach note
                Bass.GROUPS -> if (first) {
                    play(a.bass, r, at(b, 0), 2.6f * e8, 1f)
                    play(a.bass, r, at(b, 3), 0.9f * e8, 0.7f)
                    play(a.bass, r + 7, at(b, 4), 1.8f * e8, 0.85f)
                    play(a.bass, r + 12, at(b, 6), 1.8f * e8, 0.8f)
                } else {
                    play(a.bass, r, at(b, 8), 1.8f * e8, 0.95f)
                    play(a.bass, r + 7, at(b, 10), 1.8f * e8, 0.8f)
                    play(a.bass, next - 1, at(b, 12), 1.8f * e8, 0.75f)   // chromatic step up into the next bar
                }
                // quarter notes: root, third, fifth, approach (four beats) or root, fifth, approach (three beats)
                Bass.WALK -> {
                    val third = ch.tones[1] - 12
                    val steps = if (first) listOf(r, third, r + 7, next - 1) else listOf(r, r + 7, next - 1)
                    for ((k, n) in steps.withIndex()) play(a.bass, n, at(b, e0 + k * 2), 1.85f * e8, if (k == 0) 1f else 0.8f)
                }
                Bass.PULSE -> for (k in 0 until span) play(a.bass, r, at(b, e0 + k), 0.8f * e8, if (k == 0) 1f else if (k % 2 == 0) 0.75f else 0.6f)
                Bass.OCTAVE -> for (k in 0 until span) play(a.bass, if (k % 2 == 0) r else r + 12, at(b, e0 + k), 0.85f * e8, if (k == 0) 1f else 0.7f)
                Bass.DRONE -> {
                    play(a.bass, r, at(b, e0), span * e8 * 0.98f, 1f)
                    play(a.bass, r + 19, at(b, e0 + 1), (span - 1) * e8 * 0.9f, 0.18f)
                }
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
                val n0 = m[i + 2]
                if (a.ornament && len >= 3) {
                    // a turn: the note, its upper neighbour, the note again (held)
                    play(a.lead, n0, at(b, start), 0.9f * e8, g)
                    playExact(a.lead, stepUp(inMode(n0, a.mode), a.mode), at(b, start + 1), 0.9f * e8, g * 0.8f)
                    play(a.lead, n0, at(b, start + 2), (len - 2) * e8 * a.legato, g * 0.9f)
                } else play(a.lead, n0, at(b, start), len * e8 * a.legato, g)
                if (b in doubled && start < 8) play(a.lead, n0 + 12, at(b, start), len * e8 * a.legato, g * 0.32f)
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

        // level: every variation about equally loud (RMS), peaks rounded off softly
        var peak = 0.0001f
        var sum = 0.0
        for (x in out) { peak = maxOf(peak, abs(x)); sum += x * x }
        val rms = kotlin.math.sqrt(sum / n).toFloat().coerceAtLeast(0.0001f)
        val g = minOf(TARGET_RMS / rms, 1.6f / peak)
        return ShortArray(n) { (kotlin.math.tanh(out[it] * g) * 32000f).toInt().toShort() }
    }

    /** Loudness every variation is brought to (RMS of the loop, full scale = 1). */
    private const val TARGET_RMS = 0.2f

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
        val f0 = if (chip) 160f else 110f
        if (a.twoTwoThree) {
            // 2 + 2 + 3: kick on every group start (beats 1, 3, 5), snare in between (beats 2, 4, 7)
            kick(out, at(b, 0), 1f * lv, f0, rnd, soft = soft, chip = chip)
            kick(out, at(b, 4), 0.75f * lv, f0, rnd, soft = soft, chip = chip)
            kick(out, at(b, 8), 0.9f * lv, f0, rnd, soft = soft, chip = chip)
            if (fill) {
                for ((k, e) in (9..13).withIndex()) snare(out, at(b, e), (0.35f + 0.15f * k) * lv, rnd, a.kit)
            } else {
                snare(out, at(b, 2), 0.8f * lv, rnd, a.kit)
                snare(out, at(b, 6), 0.8f * lv, rnd, a.kit)
                snare(out, at(b, 12), 0.85f * lv, rnd, a.kit)
            }
        } else {
            // 4 + 3: kick on beats 1 and 5 (and a push on the "and" of 2), snare on 3 and 7
            kick(out, at(b, 0), 1f * lv, f0, rnd, soft = soft, chip = chip)
            kick(out, at(b, 3), 0.55f * lv, f0, rnd, soft = soft, chip = chip)
            kick(out, at(b, 8), 0.9f * lv, f0, rnd, soft = soft, chip = chip)
            if (fill) {
                for ((k, e) in (9..13).withIndex()) snare(out, at(b, e), (0.35f + 0.15f * k) * lv, rnd, a.kit)
            } else {
                snare(out, at(b, 4), 0.9f * lv, rnd, a.kit)
                snare(out, at(b, 11), 0.22f * lv, rnd, a.kit)   // ghost note
                snare(out, at(b, 12), 0.85f * lv, rnd, a.kit)
            }
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
