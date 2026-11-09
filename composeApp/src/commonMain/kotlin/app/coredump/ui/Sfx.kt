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
 * Sound effects, synthesised on the device (no audio files). Each theme has its own instrument and phrasing:
 * a PC-speaker beep for the terminal, a typewriter and desk bell for the gazette, arcade pulses, quiz-show chimes,
 * handheld-console squares, cartoon boings, sonar pings in the deep, station chimes, coins and bells, chat pops,
 * an old desktop's clicks and chords, glass bells for the tarot table.
 * Sounds are rendered once (mono 16-bit, [RATE] Hz) and cached.
 */
object Sfx {
    const val RATE = 22050

    private var on = true
    private var volume = 0.7f
    private val cache = HashMap<String, ShortArray>()
    private var lastKey = 0L
    private var lastTap = 0L

    fun configure(enabled: Boolean, vol: Float) { on = enabled; volume = vol.coerceIn(0f, 1f) }

    /** Play [cue] in the active theme's voice. [level]: tier heat (HIT) or rank (RANK), 0..6. */
    fun play(cue: Cue, level: Int = 0) {
        if (!on || volume <= 0.01f) return
        val style = Th.style
        val t = app.coredump.platform.Platform.epochMillis()
        if (cue == Cue.TAP) lastTap = t
        if (cue == Cue.NAV && t - lastTap < 150) return   // a button that navigates already clicked
        if (cue == Cue.KEY) {
            // typing: at most one click every 45 ms
            val now = app.coredump.platform.Platform.epochMillis()
            if (now - lastKey < 45) return
            lastKey = now
        }
        val k = "${style.name}|${cue.name}|$level"
        val pcm = cache.getOrPut(k) { runCatching { Synth.render(style, cue, level) }.getOrElse { ShortArray(0) } }
        if (pcm.isEmpty()) return
        runCatching { SoundOut.play(pcm, volume * gainOf(cue)) }
    }

    fun hit(t: Tier) = if (t.isValid) play(Cue.HIT, t.heat) else when (t) {
        Tier.TIMEOUT -> play(Cue.TIMEOUT)
        Tier.SKIPPED -> play(Cue.SKIP)
        else -> play(Cue.MISS)
    }

    private fun gainOf(c: Cue): Float = when (c) {
        Cue.KEY -> 0.35f
        Cue.TAP, Cue.NAV, Cue.TICK -> 0.6f
        else -> 1f
    }
}

/** Little additive / subtractive synth: notes, a few wave shapes, envelopes, slides, echo and a low-pass. */
internal object Synth {
    private const val R = Sfx.RATE

    enum class Wave { SINE, TRI, SQUARE, PULSE, SAW, NOISE, BELL, GLASS, CHIME }

    /** One note. semi: semitones from the base pitch; at / dur: s; slide: semitones over the note; gain 0..1. */
    class Note(val semi: Float, val at: Float, val dur: Float, val gain: Float = 1f, val slide: Float = 0f, val wave: Wave? = null)

    /** Instrument. attack / decay: s (decay = time to fall to ~5 %). echo: feedback 0..0.6, delay s. tone: low-pass 0..1 (1 = open). */
    class Voice(
        val wave: Wave, val base: Float, val attack: Float = 0.004f, val decay: Float = 0.25f,
        val echo: Float = 0f, val delay: Float = 0.12f, val tone: Float = 1f, val vibrato: Float = 0f, val level: Float = 0.5f,
    )

    private fun hz(base: Float, semi: Float) = base * 2f.pow(semi / 12f)

    private fun osc(w: Wave, phase: Double, rnd: Random): Float {
        val p = phase - kotlin.math.floor(phase)
        return when (w) {
            Wave.SINE -> sin(2 * PI * p).toFloat()
            Wave.TRI -> (4 * abs(p - 0.5) - 1).toFloat()
            Wave.SQUARE -> if (p < 0.5) 0.7f else -0.7f
            Wave.PULSE -> if (p < 0.125) 0.7f else -0.7f
            Wave.SAW -> (2 * p - 1).toFloat() * 0.8f
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
                    t < v.attack -> t / v.attack
                    t < note.dur -> exp(-3f * (t - v.attack) / decay.coerceAtLeast(0.01f) * 0.35f)
                    else -> exp(-3f * (note.dur - v.attack) / decay.coerceAtLeast(0.01f) * 0.35f) * exp(-3f * (t - note.dur) / (decay * 0.5f).coerceAtLeast(0.01f))
                }
                out[idx] += osc(w, phase, rnd) * env * note.gain
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
        // gentle fade at the very end (longer when the 4 s cap cut a tail), then scale
        val tail = ((if (n >= R * 4) 0.25f else 0.01f) * R).toInt().coerceAtMost(n)
        for (i in 0 until tail) out[n - 1 - i] *= i.toFloat() / tail
        var peak = 0.0001f
        for (x in out) peak = maxOf(peak, abs(x))
        val g = (v.level / maxOf(peak, 1f)) * 32767f
        return ShortArray(n) { (out[it] * g).coerceIn(-32767f, 32767f).toInt().toShort() }
    }

    /** Rising arpeggio for a tier: more notes the rarer it is. */
    private fun arp(heat: Int, step: Float, len: Float, scale: IntArray = intArrayOf(0, 4, 7, 12, 16, 19, 24)): List<Note> {
        val k = heat.coerceIn(1, 6) + if (heat >= 6) 1 else 0
        return List(k) { Note(scale[it.coerceAtMost(scale.lastIndex)].toFloat(), it * step, len, if (it == k - 1) 1f else 0.8f) }
    }

    fun render(style: Style, cue: Cue, level: Int): ShortArray {
        val v = voiceOf(style)
        val notes = phrase(style, cue, level)
        return mix(v, notes)
    }

    private fun voiceOf(s: Style): Voice = when (s) {
        Style.TERMINAL -> Voice(Wave.SQUARE, 880f, decay = 0.12f, tone = 0.35f, level = 0.32f)
        Style.PAPER -> Voice(Wave.BELL, 660f, decay = 0.6f, tone = 0.6f, level = 0.42f)
        Style.NEON -> Voice(Wave.PULSE, 523f, decay = 0.18f, echo = 0.25f, delay = 0.09f, tone = 0.5f, level = 0.34f)
        Style.CLEAN -> Voice(Wave.TRI, 784f, attack = 0.006f, decay = 0.35f, tone = 0.8f, level = 0.48f)
        Style.PIXEL -> Voice(Wave.SQUARE, 659f, decay = 0.1f, tone = 0.45f, level = 0.32f)
        Style.COMIC -> Voice(Wave.SINE, 523f, decay = 0.2f, level = 0.5f)
        Style.ABYSS -> Voice(Wave.SINE, 392f, attack = 0.01f, decay = 0.9f, echo = 0.42f, delay = 0.21f, tone = 0.25f, level = 0.45f)
        Style.METRO -> Voice(Wave.CHIME, 659f, decay = 0.7f, tone = 0.7f, level = 0.42f)
        Style.JACKPOT -> Voice(Wave.BELL, 1047f, decay = 0.35f, echo = 0.15f, delay = 0.06f, level = 0.4f)
        Style.CHAT -> Voice(Wave.SINE, 880f, attack = 0.003f, decay = 0.12f, level = 0.45f)
        Style.DESKTOP -> Voice(Wave.TRI, 523f, decay = 0.4f, tone = 0.7f, level = 0.42f)
        Style.ORACLE -> Voice(Wave.GLASS, 784f, attack = 0.008f, decay = 1.1f, echo = 0.35f, delay = 0.17f, tone = 0.6f, vibrato = 0.05f, level = 0.4f)
    }

    /** What to play: shared phrasing, with each theme's own touches. */
    private fun phrase(s: Style, c: Cue, level: Int): List<Note> {
        val N = Wave.NOISE
        return when (c) {
            Cue.TAP -> when (s) {
                Style.PAPER -> listOf(Note(-24f, 0f, 0.012f, 0.6f, wave = N))
                Style.CHAT -> listOf(Note(0f, 0f, 0.03f, 0.6f, slide = -5f))
                Style.DESKTOP -> listOf(Note(-12f, 0f, 0.008f, 0.5f, wave = N))
                Style.ABYSS -> listOf(Note(5f, 0f, 0.05f, 0.4f, slide = 7f))
                else -> listOf(Note(7f, 0f, 0.025f, 0.45f))
            }
            Cue.NAV -> when (s) {
                Style.METRO, Style.CHAT -> listOf(Note(0f, 0f, 0.04f, 0.35f, slide = 5f))
                Style.ABYSS -> listOf(Note(-5f, 0f, 0.12f, 0.35f, slide = -7f))
                Style.ORACLE -> listOf(Note(12f, 0f, 0.08f, 0.3f))
                Style.DESKTOP -> emptyList()
                else -> listOf(Note(0f, 0f, 0.03f, 0.3f, slide = 12f))
            }
            Cue.KEY -> when (s) {
                Style.PAPER -> listOf(Note(-20f, 0f, 0.01f, 0.7f, wave = N))
                Style.TERMINAL, Style.PIXEL -> listOf(Note(12f, 0f, 0.006f, 0.4f))
                Style.ABYSS -> listOf(Note(12f, 0f, 0.03f, 0.25f, slide = 5f))
                Style.CHAT, Style.DESKTOP, Style.METRO -> listOf(Note(-24f, 0f, 0.006f, 0.35f, wave = N))
                Style.JACKPOT -> listOf(Note(5f, 0f, 0.01f, 0.25f))
                else -> listOf(Note(0f, 0f, 0.008f, 0.25f, wave = N))
            }
            Cue.SUBMIT -> when (s) {
                Style.CHAT -> listOf(Note(-12f, 0f, 0.12f, 0.6f, slide = 14f, wave = N), Note(5f, 0.02f, 0.05f, 0.5f, slide = 4f))
                Style.METRO -> listOf(Note(4f, 0f, 0.18f), Note(0f, 0.18f, 0.3f))
                Style.JACKPOT -> listOf(Note(-24f, 0f, 0.05f, 0.5f, wave = N), Note(0f, 0.04f, 0.04f, 0.5f), Note(-24f, 0.09f, 0.05f, 0.4f, wave = N))
                Style.ABYSS -> listOf(Note(0f, 0f, 0.25f, 0.7f, slide = -2f))
                Style.PAPER -> listOf(Note(-20f, 0f, 0.015f, 0.9f, wave = N), Note(-26f, 0.03f, 0.03f, 0.7f, wave = N))
                else -> listOf(Note(0f, 0f, 0.04f, 0.6f), Note(7f, 0.045f, 0.05f, 0.7f))
            }
            Cue.CHECK -> when (s) {
                Style.ABYSS -> listOf(Note(12f, 0f, 0.3f, 0.7f))   // sonar ping
                Style.DESKTOP -> listOf(Note(-24f, 0f, 0.01f, 0.4f, wave = N), Note(-24f, 0.08f, 0.01f, 0.4f, wave = N))
                Style.CHAT -> listOf(Note(7f, 0f, 0.03f, 0.3f), Note(7f, 0.12f, 0.03f, 0.3f))
                else -> listOf(Note(12f, 0f, 0.05f, 0.35f), Note(14f, 0.08f, 0.05f, 0.3f))
            }
            Cue.MISS -> when (s) {
                Style.CLEAN -> listOf(Note(-24f, 0f, 0.35f, 1f, wave = Wave.SAW))   // buzzer
                Style.COMIC -> listOf(Note(0f, 0f, 0.18f, 0.9f, slide = -2f), Note(-2f, 0.2f, 0.18f, 0.9f, slide = -2f), Note(-4f, 0.4f, 0.4f, 0.9f, slide = -3f))
                Style.DESKTOP -> listOf(Note(-5f, 0f, 0.25f, 0.9f, wave = Wave.SQUARE))
                Style.JACKPOT -> listOf(Note(-24f, 0f, 0.12f, 0.6f, wave = Wave.SQUARE), Note(-26f, 0.12f, 0.2f, 0.6f, wave = Wave.SQUARE))
                Style.ABYSS -> listOf(Note(-12f, 0f, 0.35f, 0.7f, slide = -5f))
                Style.CHAT -> listOf(Note(-5f, 0f, 0.05f, 0.5f), Note(-9f, 0.07f, 0.07f, 0.5f))
                else -> listOf(Note(-5f, 0f, 0.08f, 0.8f), Note(-9f, 0.09f, 0.14f, 0.8f))
            }
            Cue.OFFLINE -> listOf(Note(-12f, 0f, 0.1f, 0.6f), Note(-12f, 0.15f, 0.1f, 0.6f))
            Cue.CONFIRM -> when (s) {
                Style.CHAT -> listOf(Note(0f, 0f, 0.06f, 0.6f), Note(5f, 0.08f, 0.08f, 0.6f))
                Style.METRO -> listOf(Note(7f, 0f, 0.2f), Note(4f, 0.2f, 0.2f), Note(0f, 0.4f, 0.3f))
                else -> listOf(Note(0f, 0f, 0.08f, 0.7f, slide = 3f), Note(5f, 0.1f, 0.12f, 0.8f, slide = 2f))
            }
            Cue.SKIP -> when (s) {
                Style.ABYSS -> listOf(Note(-7f, 0f, 0.5f, 0.6f, slide = -12f))
                Style.JACKPOT -> listOf(Note(-24f, 0f, 0.2f, 0.6f, wave = N, slide = -12f))
                else -> listOf(Note(0f, 0f, 0.18f, 0.6f, slide = -12f))
            }
            Cue.TICK -> when (s) {
                Style.PAPER, Style.DESKTOP, Style.METRO -> listOf(Note(-18f, 0f, 0.01f, 0.6f, wave = N))
                Style.ORACLE -> listOf(Note(19f, 0f, 0.05f, 0.25f))
                Style.ABYSS -> listOf(Note(19f, 0f, 0.08f, 0.35f))
                else -> listOf(Note(19f, 0f, 0.015f, 0.4f))
            }
            Cue.TIMEOUT -> when (s) {
                Style.CLEAN -> listOf(Note(-24f, 0f, 0.6f, 1f, wave = Wave.SAW))
                Style.METRO -> listOf(Note(0f, 0f, 0.12f, 0.8f, wave = Wave.SQUARE), Note(0f, 0.2f, 0.12f, 0.8f, wave = Wave.SQUARE), Note(0f, 0.4f, 0.12f, 0.8f, wave = Wave.SQUARE))
                else -> listOf(Note(0f, 0f, 0.1f, 0.8f), Note(-5f, 0.12f, 0.1f, 0.8f), Note(-12f, 0.24f, 0.3f, 0.8f))
            }
            Cue.HIT -> when (s) {
                Style.JACKPOT -> {
                    // coins: random high clinks, more for rarer answers, then a bell
                    val r = Random(level * 31 + 5)
                    List(2 + level * 2) { Note(12f + r.nextInt(8), it * 0.055f, 0.05f, 0.6f) } + Note(0f, (2 + level * 2) * 0.055f, 0.4f, 1f)
                }
                Style.CLEAN -> if (level >= 4) arp(level, 0.09f, 0.25f) else listOf(Note(4f, 0f, 0.25f), Note(0f, 0.22f, 0.4f))  // ding-dong
                Style.COMIC -> listOf(Note(-12f, 0f, 0.15f, 0.8f, slide = 12f)) + arp(level, 0.06f, 0.12f).map { Note(it.semi, it.at + 0.15f, it.dur, it.gain) }
                Style.ABYSS -> arp(level, 0.16f, 0.5f, intArrayOf(0, 7, 12, 14, 19, 24, 26))
                Style.METRO -> arp(level, 0.14f, 0.35f, intArrayOf(0, 4, 7, 12, 16, 19, 24))
                Style.ORACLE -> arp(level, 0.13f, 0.6f, intArrayOf(0, 7, 12, 16, 19, 23, 26))
                Style.DESKTOP -> arp(level, 0.07f, 0.3f, intArrayOf(0, 4, 7, 12, 7, 12, 16))
                Style.PIXEL -> arp(level, 0.05f, 0.08f, intArrayOf(0, 7, 12, 16, 19, 24, 28))
                Style.CHAT -> listOf(Note(0f, 0f, 0.05f, 0.7f, slide = 5f)) + arp(level, 0.07f, 0.08f).map { Note(it.semi + 7f, it.at + 0.06f, it.dur, it.gain * 0.8f) }
                else -> arp(level, 0.07f, 0.12f)
            }
            Cue.RANK -> {
                // the music's hook, then the rank chord (major from rank 3 up)
                val e = if (s == Style.ORACLE || s == Style.ABYSS) 0.13f else 0.1f
                val lead = hook(e, -12f, 0.6f)
                val after = Score.HOOK[Score.HOOK.size - 3] * e + Score.HOOK[Score.HOOK.size - 2] * e
                val chord = if (level >= 3) intArrayOf(0, 4, 7, 12) else intArrayOf(0, 3, 7)
                val roll = if (s == Style.ORACLE || s == Style.ABYSS) 0.12f else 0.05f
                lead + chord.mapIndexed { i, st -> Note(st.toFloat() - 12f, after + i * roll, 0.6f + level * 0.08f, 0.8f) } +
                    if (level >= 5) listOf(Note(12f, after + chord.size * roll + 0.1f, 0.5f, 0.9f)) else emptyList()
            }
            Cue.DISCARD -> listOf(Note(-20f, 0f, 0.12f, 0.6f, slide = -8f, wave = N))
            // lists arrived: the music's hook, quick
            Cue.FETCHED -> hook(0.075f, 0f, 0.6f)
        }
    }

    /** The music's hook ([Score.HOOK]: D F E A, long-short-short-long) as a phrase; [e]: s per eighth. */
    private fun hook(e: Float, base: Float, gain: Float): List<Note> {
        val h = Score.HOOK
        return (0 until h.size / 3).map { k ->
            Note(base + h[k * 3 + 2], h[k * 3] * e, h[k * 3 + 1] * e * 0.9f, if (k == 0) gain + 0.1f else gain)
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
