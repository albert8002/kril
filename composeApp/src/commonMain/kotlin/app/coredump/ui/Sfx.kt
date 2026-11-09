package app.coredump.ui

import app.coredump.core.Tier
import app.coredump.platform.SoundOut
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Moments that make a sound. */
enum class Cue {
    TAP, NAV, KEY, SUBMIT, CHECK, MISS, OFFLINE, CONFIRM, SKIP, TICK, TIMEOUT, HIT, RANK, DISCARD, FETCHED,
}

/**
 * Sound effects, synthesised on the device (no audio files). Each look has its own instrument and phrasing:
 * a soft PC-speaker beep for the terminal, a typewriter and desk bell for the gazette, arcade pulses, quiz-show chimes,
 * handheld-console squares, cartoon boings, sonar pings in the deep, station chimes, coins and bells, chat pops,
 * an old desktop's clicks and chords, glass bells for the tarot table.
 *
 * Every pitched sound is in the key and scale of the song that is playing ([Score.keyOf]), so effects never clash
 * with the music; the rank and "fetched" sounds quote the song's hook. Wave shapes are band-limited (no harsh
 * aliasing), notes start with a short fade-in (no clicks), quick repeated sounds (taps, keys) vary a little so they
 * don't sound like a machine gun, and big moments dip the music ([Music.duck]).
 * Sounds are rendered once (mono 16-bit, [RATE] Hz) and cached.
 */
object Sfx {
    const val RATE = 22050

    private var on = true
    private var volume = 0.7f
    private val cache = HashMap<String, ShortArray>()
    private var lastKey = 0L
    private var lastTap = 0L
    private var turn = 0

    fun configure(enabled: Boolean, vol: Float) { on = enabled; volume = vol.coerceIn(0f, 1f) }

    /** Play [cue] in the active look's voice. [level]: tier heat (HIT) or rank (RANK), 0..6. */
    fun play(cue: Cue, level: Int = 0) {
        if (!on || volume <= 0.01f) return
        val style = Th.style
        val song = Music.song ?: style
        val t = app.coredump.platform.Platform.epochMillis()
        when (cue) {
            // at most one tap every 40 ms, one key click every 45 ms; a button that navigates already clicked
            Cue.TAP -> { if (t - lastTap < 40) return; lastTap = t }
            Cue.NAV -> { if (t - lastTap < 150) return }
            Cue.KEY -> { if (t - lastKey < 45) return; lastKey = t }
            else -> {}
        }
        val variant = if (cue == Cue.TAP || cue == Cue.KEY || cue == Cue.NAV) (turn++ % 3) else 0
        val k = "${style.name}|${song.name}|${cue.name}|$level|$variant"
        val pcm = cache.getOrPut(k) { runCatching { Synth.render(style, song, cue, level, variant) }.getOrElse { ShortArray(0) } }
        if (pcm.isEmpty()) return
        if (cue == Cue.RANK || cue == Cue.FETCHED || (cue == Cue.HIT && level >= 3)) runCatching { Music.duck(pcm.size.toFloat() / RATE) }
        runCatching { SoundOut.play(pcm, volume * gainOf(cue)) }
    }

    fun hit(t: Tier) = if (t.isValid) play(Cue.HIT, t.heat) else when (t) {
        Tier.TIMEOUT -> play(Cue.TIMEOUT)
        Tier.SKIPPED -> play(Cue.SKIP)
        else -> play(Cue.MISS)
    }

    /** Small, frequent sounds sit well under the music; results stay on top. */
    private fun gainOf(c: Cue): Float = when (c) {
        Cue.KEY -> 0.3f
        Cue.NAV -> 0.45f
        Cue.TAP -> 0.6f
        Cue.TICK, Cue.CHECK -> 0.6f
        else -> 1f
    }
}

/** Little additive / subtractive synth: notes, a few wave shapes, envelopes, slides, echo and a low-pass. */
internal object Synth {
    private const val R = Sfx.RATE

    enum class Wave { SINE, TRI, SQUARE, PULSE, SAW, NOISE, BELL, GLASS, CHIME }

    /** One note. semi: semitones from the base pitch; at / dur: s; slide: semitones over the note; gain 0..1. */
    class Note(val semi: Float, val at: Float, val dur: Float, val gain: Float = 1f, val slide: Float = 0f, val wave: Wave? = null)

    /**
     * Instrument. base: Hz of the key's root. attack / decay: s (decay = time to fall to ~5 %).
     * echo: feedback 0..0.6, delay s. tone: low-pass 0..1 (1 = open). vibrato: semitones.
     */
    class Voice(
        val wave: Wave, val base: Float, val attack: Float = 0.004f, val decay: Float = 0.25f,
        val echo: Float = 0f, val delay: Float = 0.12f, val tone: Float = 1f, val vibrato: Float = 0f, val level: Float = 0.5f,
    )

    private fun hz(base: Float, semi: Float) = base * 2f.pow(semi / 12f)

    /** Sum of the first harmonics of a saw that stay under ~10 kHz (no aliasing); [p] in 0..1. */
    private fun saw(p: Double, f: Float): Double {
        val top = (10000f / f.coerceAtLeast(20f)).toInt().coerceIn(1, 8)
        var x = 0.0
        for (k in 1..top) x += sin(2 * PI * k * p) / k
        return x * (2 / PI)
    }

    private fun osc(w: Wave, phase: Double, f: Float, rnd: Random): Float {
        val p = phase - kotlin.math.floor(phase)
        return when (w) {
            Wave.SINE -> sin(2 * PI * p).toFloat()
            Wave.TRI -> (4 * abs(p - 0.5) - 1).toFloat()
            // square = saw minus a saw half a cycle later; pulse (25 %) the same a quarter cycle later
            Wave.SQUARE -> ((saw(p, f) - saw(p + 0.5, f)) * 0.55).toFloat()
            Wave.PULSE -> ((saw(p, f) - saw(p + 0.25, f)) * 0.6).toFloat()
            Wave.SAW -> (saw(p, f) * 0.75).toFloat()
            Wave.NOISE -> rnd.nextFloat() * 2f - 1f
            // inharmonic partials: struck metal / glass / tubular chime
            Wave.BELL -> (sin(2 * PI * p) + 0.5 * sin(2 * PI * p * 2.76) + 0.25 * sin(2 * PI * p * 5.4)).toFloat() * 0.6f
            Wave.GLASS -> (sin(2 * PI * p) + 0.35 * sin(2 * PI * p * 2.41) + 0.2 * sin(2 * PI * p * 3.93)).toFloat() * 0.7f
            Wave.CHIME -> (sin(2 * PI * p) + 0.4 * sin(2 * PI * p * 3.0) + 0.15 * sin(2 * PI * p * 4.07)).toFloat() * 0.7f
        }
    }

    fun mix(v: Voice, notes: List<Note>): ShortArray {
        if (notes.isEmpty()) return ShortArray(0)
        val end = notes.maxOf { it.at + it.dur + v.decay * 0.6f } + v.echo * v.delay * 6f
        val n = (end * R).toInt().coerceIn(1, R * 4)
        val out = FloatArray(n)
        val rnd = Random(7)
        for (note in notes) {
            val w = note.wave ?: v.wave
            val start = (note.at * R).toInt()
            // noise (clicks, swooshes) dies away fast whatever the instrument
            val decay = if (w == Wave.NOISE) 0.06f else v.decay
            // a short fade-in on every note: no click at the start
            val attack = if (w == Wave.NOISE) 0.001f else v.attack.coerceAtLeast(0.003f)
            val len = ((note.dur + decay * 0.6f) * R).toInt()
            var phase = 0.0
            val f0 = hz(v.base, note.semi)
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= n) break
                val t = i.toFloat() / R
                val prog = (t / note.dur).coerceIn(0f, 1f)
                val vib = if (v.vibrato > 0f) sin(2 * PI * 5.5 * t).toFloat() * v.vibrato else 0f
                val f = hz(f0, note.slide * prog + vib)
                phase += f / R
                val env = when {
                    t < attack -> t / attack
                    t < note.dur -> exp(-3f * (t - attack) / decay.coerceAtLeast(0.01f) * 0.35f)
                    else -> exp(-3f * (note.dur - attack).coerceAtLeast(0f) / decay.coerceAtLeast(0.01f) * 0.35f) * exp(-3f * (t - note.dur) / (decay * 0.5f).coerceAtLeast(0.01f))
                }
                out[idx] += osc(w, phase, f, rnd) * env * note.gain
            }
        }
        // low-pass (one pole) then echo
        if (v.tone < 1f) {
            val a = v.tone.coerceIn(0.02f, 1f)
            var y = 0f
            for (i in 0 until n) { y += a * (out[i] - y); out[i] = y }
        }
        if (v.echo > 0f) {
            val d = (v.delay * R).toInt().coerceAtLeast(1)
            for (i in d until n) out[i] += out[i - d] * v.echo
        }
        // gentle fade at the very end (longer when the 4 s cap cut a tail), then scale; loud peaks are rounded off
        val tail = ((if (n >= R * 4) 0.25f else 0.01f) * R).toInt().coerceAtMost(n)
        for (i in 0 until tail) out[n - 1 - i] *= i.toFloat() / tail
        var peak = 0.0001f
        for (x in out) peak = maxOf(peak, abs(x))
        val g = v.level / maxOf(peak, 1f)
        return ShortArray(n) { (kotlin.math.tanh(out[it] * g * 1.2f) / 1.2f * 32767f).coerceIn(-32767f, 32767f).toInt().toShort() }
    }

    /** The song's scale: semitones of the 7 degrees from the root. */
    private fun scaleOf(m: Score.Mode): IntArray = when (m) {
        Score.Mode.AEOLIAN -> intArrayOf(0, 2, 3, 5, 7, 8, 10)
        Score.Mode.DORIAN -> intArrayOf(0, 2, 3, 5, 7, 9, 10)
        Score.Mode.MAJOR -> intArrayOf(0, 2, 4, 5, 7, 9, 11)
        Score.Mode.HARMONIC -> intArrayOf(0, 2, 3, 5, 7, 8, 11)
    }

    /** Phrases written in scale degrees (0 = root, 2 = third, 4 = fifth, 7 = root an octave up; negative = below). */
    private class Key(val scale: IntArray) {
        fun s(d: Int): Float {
            val o = kotlin.math.floor(d / 7.0).toInt()
            return (scale[d - o * 7] + 12 * o).toFloat()
        }

        /** A chromatic step from the root (as written in D minor) moved into this scale, e.g. for the hook. */
        fun fit(semi: Int): Float {
            val o = kotlin.math.floor(semi / 12.0).toInt()
            val pc = semi - o * 12
            val aeolian = intArrayOf(0, 2, 3, 5, 7, 8, 10)
            val i = aeolian.indexOf(pc)
            return (if (i >= 0) scale[i] else pc) + 12f * o
        }
    }

    /** Rising arpeggio on scale [degrees] for a tier: more notes the rarer it is. */
    private fun arp(k: Key, heat: Int, step: Float, len: Float, degrees: IntArray = intArrayOf(0, 2, 4, 7, 9, 11, 14)): List<Note> {
        val n = heat.coerceIn(1, 6) + if (heat >= 6) 1 else 0
        return List(n) { Note(k.s(degrees[it.coerceAtMost(degrees.lastIndex)]), it * step, if (it == n - 1) len * 2.2f else len, if (it == n - 1) 1f else 0.75f) }
    }

    fun render(style: Style, song: Style, cue: Cue, level: Int, variant: Int): ShortArray {
        val (transpose, mode) = Score.keyOf(song)
        // keep effects in a comfortable range: a key a fifth down plays a fourth up instead
        val shift = ((transpose % 12) + 18) % 12 - 6
        // quick repeated sounds vary a hair: ±7 cents
        val cents = when (variant) { 1 -> 7f; 2 -> -7f; else -> 0f }
        val v = voiceOf(style, ROOT * 2f.pow(shift / 12f + cents / 1200f))
        val notes = phrase(style, cue, level, Key(scaleOf(mode)), v.base > 400f)
        val g = when (variant) { 1 -> 0.9f; 2 -> 0.95f; else -> 1f }
        return mix(v, if (g == 1f) notes else notes.map { Note(it.semi, it.at, it.dur, it.gain * g, it.slide, it.wave) })
    }

    /** D4 (Hz): the song's root as written. */
    private const val ROOT = 293.66f

    /** [r]: the key's root (D4 as written, moved to the song's key). Most looks play an octave above it. */
    private fun voiceOf(s: Style, r: Float): Voice = when (s) {
        Style.TERMINAL -> Voice(Wave.SQUARE, r * 2, decay = 0.12f, tone = 0.4f, level = 0.3f)
        Style.PAPER -> Voice(Wave.BELL, r * 2, decay = 0.6f, tone = 0.6f, level = 0.4f)
        Style.NEON -> Voice(Wave.PULSE, r * 2, decay = 0.18f, echo = 0.25f, delay = 0.09f, tone = 0.5f, level = 0.32f)
        Style.CLEAN -> Voice(Wave.TRI, r * 2, attack = 0.006f, decay = 0.35f, tone = 0.8f, level = 0.46f)
        Style.PIXEL -> Voice(Wave.SQUARE, r * 2, decay = 0.1f, tone = 0.45f, level = 0.3f)
        Style.COMIC -> Voice(Wave.SINE, r * 2, decay = 0.2f, level = 0.48f)
        Style.ABYSS -> Voice(Wave.SINE, r, attack = 0.01f, decay = 0.9f, echo = 0.42f, delay = 0.21f, tone = 0.25f, level = 0.45f)
        Style.METRO -> Voice(Wave.CHIME, r * 2, decay = 0.7f, tone = 0.7f, level = 0.4f)
        Style.JACKPOT -> Voice(Wave.BELL, r * 4, decay = 0.35f, echo = 0.15f, delay = 0.06f, tone = 0.8f, level = 0.36f)
        Style.CHAT -> Voice(Wave.SINE, r * 2, attack = 0.003f, decay = 0.12f, level = 0.44f)
        Style.DESKTOP -> Voice(Wave.TRI, r * 2, decay = 0.4f, tone = 0.7f, level = 0.42f)
        Style.ORACLE -> Voice(Wave.GLASS, r * 2, attack = 0.008f, decay = 1.1f, echo = 0.35f, delay = 0.17f, tone = 0.6f, vibrato = 0.05f, level = 0.38f)
    }

    /** What to play: shared phrasing in the song's scale [k], with each look's own touches. [high]: the voice sits an octave up. */
    private fun phrase(s: Style, c: Cue, level: Int, k: Key, high: Boolean): List<Note> {
        val N = Wave.NOISE
        return when (c) {
            // a short, round tock on the fifth, falling a hair
            Cue.TAP -> when (s) {
                Style.PAPER -> listOf(Note(-24f, 0f, 0.012f, 0.4f, wave = N))
                Style.DESKTOP -> listOf(Note(-12f, 0f, 0.008f, 0.3f, wave = N))
                Style.CHAT -> listOf(Note(k.s(4), 0f, 0.03f, 0.5f, slide = 5f))
                Style.ABYSS -> listOf(Note(k.s(4), 0f, 0.05f, 0.4f, slide = 5f))
                Style.COMIC -> listOf(Note(k.s(4), 0f, 0.035f, 0.45f, slide = 7f))
                Style.TERMINAL, Style.PIXEL -> listOf(Note(k.s(7), 0f, 0.018f, 0.4f, slide = -1f))
                Style.JACKPOT -> listOf(Note(k.s(4), 0f, 0.02f, 0.4f))
                else -> listOf(Note(k.s(4), 0f, 0.022f, 0.45f, slide = -1.5f))
            }
            // screen change: root to fifth, soft
            Cue.NAV -> when (s) {
                Style.DESKTOP -> emptyList()
                Style.PAPER -> listOf(Note(-24f, 0f, 0.05f, 0.25f, wave = N))
                Style.ABYSS -> listOf(Note(k.s(4), 0f, 0.14f, 0.3f, slide = -7f))
                Style.ORACLE -> listOf(Note(k.s(7), 0f, 0.09f, 0.25f))
                Style.METRO, Style.CHAT -> listOf(Note(k.s(2), 0f, 0.035f, 0.28f), Note(k.s(4), 0.04f, 0.05f, 0.3f))
                else -> listOf(Note(k.s(0), 0f, 0.025f, 0.26f), Note(k.s(4), 0.03f, 0.05f, 0.3f))
            }
            Cue.KEY -> when (s) {
                Style.PAPER -> listOf(Note(-20f, 0f, 0.01f, 0.6f, wave = N))
                Style.TERMINAL, Style.PIXEL -> listOf(Note(k.s(7), 0f, 0.006f, 0.3f))
                Style.ABYSS -> listOf(Note(k.s(9), 0f, 0.03f, 0.2f, slide = 5f))
                Style.JACKPOT -> listOf(Note(k.s(4), 0f, 0.01f, 0.2f))
                Style.CHAT, Style.DESKTOP, Style.METRO -> listOf(Note(-24f, 0f, 0.006f, 0.3f, wave = N))
                else -> listOf(Note(0f, 0f, 0.008f, 0.2f, wave = N))
            }
            Cue.SUBMIT -> when (s) {
                Style.CHAT -> listOf(Note(-12f, 0f, 0.12f, 0.5f, slide = 14f, wave = N), Note(k.s(4), 0.02f, 0.05f, 0.45f, slide = 5f))
                Style.METRO -> listOf(Note(k.s(2), 0f, 0.18f), Note(k.s(0), 0.18f, 0.3f))
                Style.JACKPOT -> listOf(Note(-24f, 0f, 0.05f, 0.45f, wave = N), Note(k.s(0), 0.04f, 0.04f, 0.45f), Note(-24f, 0.09f, 0.05f, 0.35f, wave = N))
                Style.ABYSS -> listOf(Note(k.s(0), 0f, 0.25f, 0.65f, slide = -2f))
                Style.PAPER -> listOf(Note(-20f, 0f, 0.015f, 0.8f, wave = N), Note(-26f, 0.03f, 0.03f, 0.6f, wave = N))
                else -> listOf(Note(k.s(0), 0f, 0.04f, 0.55f), Note(k.s(4), 0.045f, 0.07f, 0.65f))
            }
            // checking: two soft pings
            Cue.CHECK -> when (s) {
                Style.ABYSS -> listOf(Note(k.s(7), 0f, 0.3f, 0.6f))   // sonar ping
                Style.DESKTOP -> listOf(Note(-24f, 0f, 0.01f, 0.35f, wave = N), Note(-24f, 0.08f, 0.01f, 0.35f, wave = N))
                Style.CHAT -> listOf(Note(k.s(4), 0f, 0.03f, 0.3f), Note(k.s(4), 0.12f, 0.03f, 0.3f))
                else -> listOf(Note(k.s(7), 0f, 0.05f, 0.32f), Note(k.s(8), 0.08f, 0.05f, 0.28f))
            }
            // wrong: a low falling third, soft (no harsh buzz)
            Cue.MISS -> when (s) {
                Style.CLEAN -> listOf(Note(k.s(-14), 0f, 0.32f, 0.7f, wave = Wave.SQUARE), Note(k.s(-14) + 0.3f, 0f, 0.32f, 0.5f, wave = Wave.SQUARE))   // soft buzzer
                Style.COMIC -> listOf(Note(0f, 0f, 0.18f, 0.8f, slide = -1f), Note(-1f, 0.2f, 0.18f, 0.8f, slide = -1f), Note(-2f, 0.4f, 0.45f, 0.85f, slide = -1f))   // sad trombone
                Style.DESKTOP -> listOf(Note(k.s(-3), 0f, 0.22f, 0.75f, wave = Wave.SQUARE))
                Style.JACKPOT -> listOf(Note(k.s(-14), 0f, 0.12f, 0.55f, wave = Wave.SQUARE), Note(k.s(-15), 0.12f, 0.2f, 0.55f, wave = Wave.SQUARE))
                Style.ABYSS -> listOf(Note(k.s(-7), 0f, 0.35f, 0.65f, slide = -5f))
                Style.CHAT -> listOf(Note(k.s(0), 0f, 0.05f, 0.45f), Note(k.s(-2), 0.07f, 0.07f, 0.45f))
                else -> listOf(Note(k.s(-5), 0f, 0.08f, 0.7f), Note(k.s(-7), 0.09f, 0.16f, 0.7f))
            }
            Cue.OFFLINE -> listOf(Note(k.s(-7), 0f, 0.1f, 0.5f), Note(k.s(-7), 0.15f, 0.1f, 0.5f))
            // "did you mean …?": a rising fifth, like a question
            Cue.CONFIRM -> when (s) {
                Style.CHAT -> listOf(Note(k.s(0), 0f, 0.06f, 0.55f), Note(k.s(3), 0.08f, 0.08f, 0.55f))
                Style.METRO -> listOf(Note(k.s(4), 0f, 0.2f), Note(k.s(2), 0.2f, 0.2f), Note(k.s(0), 0.4f, 0.3f))
                else -> listOf(Note(k.s(0), 0f, 0.08f, 0.6f), Note(k.s(4), 0.1f, 0.14f, 0.7f))
            }
            Cue.SKIP -> when (s) {
                Style.ABYSS -> listOf(Note(k.s(4), 0f, 0.5f, 0.55f, slide = -12f))
                Style.JACKPOT -> listOf(Note(-24f, 0f, 0.2f, 0.5f, wave = N, slide = -12f))
                else -> listOf(Note(k.s(4), 0f, 0.18f, 0.55f, slide = -7f))
            }
            Cue.TICK -> when (s) {
                Style.PAPER, Style.DESKTOP, Style.METRO -> listOf(Note(-18f, 0f, 0.01f, 0.5f, wave = N))
                Style.ORACLE -> listOf(Note(k.s(11), 0f, 0.05f, 0.25f))
                Style.ABYSS -> listOf(Note(k.s(11), 0f, 0.08f, 0.32f))
                else -> listOf(Note(k.s(11), 0f, 0.015f, 0.35f))
            }
            Cue.TIMEOUT -> when (s) {
                Style.CLEAN -> listOf(Note(k.s(-14), 0f, 0.55f, 0.7f, wave = Wave.SQUARE), Note(k.s(-14) + 0.3f, 0f, 0.55f, 0.5f, wave = Wave.SQUARE))
                Style.METRO -> List(3) { Note(k.s(0), it * 0.2f, 0.12f, 0.7f, wave = Wave.SQUARE) }
                else -> listOf(Note(k.s(4), 0f, 0.1f, 0.7f), Note(k.s(2), 0.12f, 0.1f, 0.7f), Note(k.s(-7), 0.24f, 0.32f, 0.7f))
            }
            // a valid answer: up the chord, more notes and longer the rarer; rare ones get a soft root below
            Cue.HIT -> (when (s) {
                Style.JACKPOT -> {
                    // coins: random high clinks in the scale, more for rarer answers, then a bell
                    val r = Random(level * 31 + 5)
                    List(2 + level * 2) { Note(k.s(7 + r.nextInt(5)), it * 0.055f, 0.05f, 0.55f) } + Note(k.s(0), (2 + level * 2) * 0.055f, 0.4f, 1f)
                }
                Style.CLEAN -> if (level >= 4) arp(k, level, 0.09f, 0.25f) else listOf(Note(k.s(2), 0f, 0.25f), Note(k.s(0), 0.22f, 0.4f))  // ding-dong
                Style.COMIC -> listOf(Note(k.s(-7), 0f, 0.15f, 0.7f, slide = 12f)) + arp(k, level, 0.06f, 0.12f).map { Note(it.semi, it.at + 0.15f, it.dur, it.gain) }
                Style.ABYSS -> arp(k, level, 0.16f, 0.5f, intArrayOf(0, 4, 7, 8, 11, 14, 15))
                Style.METRO -> arp(k, level, 0.14f, 0.35f)
                Style.ORACLE -> arp(k, level, 0.13f, 0.6f, intArrayOf(0, 4, 7, 9, 11, 13, 15))
                Style.DESKTOP -> arp(k, level, 0.07f, 0.3f, intArrayOf(0, 2, 4, 7, 4, 7, 9))
                Style.PIXEL -> arp(k, level, 0.05f, 0.08f, intArrayOf(0, 4, 7, 9, 11, 14, 16))
                Style.CHAT -> listOf(Note(k.s(0), 0f, 0.05f, 0.65f, slide = 5f)) + arp(k, level, 0.07f, 0.08f).map { Note(it.semi + k.s(4), it.at + 0.06f, it.dur, it.gain * 0.8f) }
                else -> arp(k, level, 0.07f, 0.12f)
            }) + if (level >= 4 && s != Style.JACKPOT) listOf(Note(k.s(-7), 0f, 0.5f, 0.35f, wave = Wave.SINE)) else emptyList()
            Cue.RANK -> {
                // the music's hook, then the rank chord: the song's own triad, major from rank 3 up
                val e = if (s == Style.ORACLE || s == Style.ABYSS) 0.13f else 0.1f
                val base = if (high) -12f else 0f
                val lead = hook(k, e, base, 0.6f)
                val after = Score.HOOK[Score.HOOK.size - 3] * e + Score.HOOK[Score.HOOK.size - 2] * e
                val chord = if (level >= 3) floatArrayOf(0f, 4f, 7f, 12f) else floatArrayOf(k.s(0), k.s(2), k.s(4), k.s(7))
                val roll = if (s == Style.ORACLE || s == Style.ABYSS) 0.12f else 0.05f
                lead + chord.mapIndexed { i, st -> Note(st + base, after + i * roll, 0.6f + level * 0.08f, 0.75f) } +
                    if (level >= 5) listOf(Note(12f + base + 12f, after + chord.size * roll + 0.1f, 0.5f, 0.8f)) else emptyList()
            }
            Cue.DISCARD -> listOf(Note(-20f, 0f, 0.12f, 0.5f, slide = -8f, wave = N))
            // lists arrived: the music's hook, quick
            Cue.FETCHED -> hook(k, 0.075f, if (high) 0f else 12f, 0.55f)
        }
    }

    /** The music's hook ([Score.HOOK]: D F E A, long-short-short-long) in the song's scale; [e]: s per eighth. */
    private fun hook(k: Key, e: Float, base: Float, gain: Float): List<Note> {
        val h = Score.HOOK
        return (0 until h.size / 3).map { i ->
            Note(base + k.fit(h[i * 3 + 2]), h[i * 3] * e, h[i * 3 + 1] * e * 0.9f, if (i == 0) gain + 0.1f else gain)
        }
    }
}

/** RIFF/WAVE container for 16-bit mono PCM (iOS players read it from memory). */
object Wav {
    fun encode(pcm: ShortArray, rate: Int): ByteArray {
        val data = pcm.size * 2
        val b = ByteArray(44 + data)
        fun s(o: Int, t: String) { for (i in t.indices) b[o + i] = t[i].code.toByte() }
        fun i32(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        s(0, "RIFF"); i32(4, 36 + data); s(8, "WAVE")
        s(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1); i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16)
        s(36, "data"); i32(40, data)
        var o = 44
        for (x in pcm) { b[o] = x.toInt().toByte(); b[o + 1] = (x.toInt() shr 8).toByte(); o += 2 }
        return b
    }
}
