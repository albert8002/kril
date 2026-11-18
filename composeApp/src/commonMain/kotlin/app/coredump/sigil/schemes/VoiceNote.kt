package app.coredump.sigil.schemes

import app.coredump.sigil.Colors
import app.coredump.sigil.Fit
import app.coredump.sigil.Ink
import app.coredump.sigil.Read
import app.coredump.sigil.Scene
import app.coredump.sigil.SchemeReader
import app.coredump.sigil.SigilFrame
import app.coredump.sigil.SigilPayload
import app.coredump.sigil.SigilScheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CHAT: a messenger thread with three voice notes (two received, one sent). Each note is a play button and a waveform
 * of 14 bars; a bar's height is one of four levels (2 Gray-coded bits), so 3 × 14 bars carry the 84 bits (3 bars = one
 * symbol). The notes auto-play one after the other: a playhead runs along the bars and recolours the played part, the
 * playing note shows a pause icon and counts its seconds; a "typing…" bubble bounces, a reaction pops.
 *
 * Reader: the play buttons are big round marks (half-res contrast threshold); two of them a row apart give the screen's
 * down axis, the third (the sent note, shifted along its row) confirms the triangle and tells the mirror. From each
 * button a walk follows the 14 bars (darkest spot near each predicted place, centre from the darkness centroid along
 * the bar; the step follows perspective, rows 1–2 reuse what row 0 learned, the button-to-first-bar distance in the
 * row's own steps catches a skipped bar). All bars and buttons fit one homography (screen square → camera), through
 * which each bar's height is measured as the integral of its darkness along its axis divided by its own peak darkness
 * (blur and contrast cancel). Soft bits add up over frames.
 */
object VoiceNote : SigilScheme {
    override val name = "voice"

    // ------------------------------------------------------------------ layout (unit square)
    /** The thread is drawn at this scale round the centre, so all the reader needs stays within radius 0.45. */
    internal const val S = 0.87f
    internal const val NB = 14
    internal const val PITCH = 0.043f * S
    internal const val BW = 0.022f * S
    internal val LV = floatArrayOf(0.030f * S, 0.068f * S, 0.106f * S, 0.144f * S)
    internal val ROW_Y = floatArrayOf(0.5f + S * (0.25f - 0.5f), 0.5f + S * (0.49f - 0.5f), 0.5f + S * (0.775f - 0.5f))
    internal val BX = floatArrayOf(0.5f + S * (0.025f - 0.5f), 0.5f + S * (0.025f - 0.5f), 0.5f + S * (0.115f - 0.5f))
    internal const val BUB_W = 0.86f * S
    internal const val BUB_H = 0.22f * S
    internal const val PLAY_DX = 0.085f * S
    internal const val PLAY_R = 0.055f * S
    internal const val BAR0_DX = 0.19f * S
    /** Play-button centre to first bar, in bar pitches. */
    internal const val PLAY_GAP = (BAR0_DX - PLAY_DX) / PITCH

    /** Scheme-specific whitening of the payload bits (another scheme's reader decoding these frames gets garbage). */
    internal val MASK = BooleanArray(SigilPayload.BITS).also { m ->
        var s = 0x5EED_C4A7L
        for (i in m.indices) { s = (s * 6364136223846793005L + 1442695040888963407L); m[i] = (s ushr 61) and 1L == 1L }
    }

    internal fun level(b1: Boolean, b2: Boolean) = if (!b1) (if (!b2) 0 else 1) else (if (b2) 2 else 3)

    private const val PLAY_EACH = 2.6f
    private const val CYCLE = PLAY_EACH * 3 + 1.4f
    private const val EVENT = 1.7f   // a little event every so often

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val main = ink.main
        val plate = ink.plate
        val played = Colors.hueShift(towards(ink.alt, Colors.light(plate) + 0.85f * (Colors.light(main) - Colors.light(plate))), 18f * sin(t * 0.7f))
        val soft = ink.soft; val faint = ink.faint
        val accent = Colors.hueShift(Colors.mix(plate, ink.alt, 0.55f), 12f * sin(t * 0.23f))

        // ---- wallpaper: two layers of doodles drifting at their own pace (parallax), one twinkling now and then
        wallpaper(out, t, ink)

        // ---- the thread scrolls a hair up and down (rubber band), the whole conversation together
        val bob = 0.003f * sin(t * 0.86f) + 0.0015f * sin(t * 2.3f + 1f)

        // ---- date chip
        val dcY = 0.148f + bob
        out.rect(0.5f, dcY + 0.003f, 0.15f, 0.032f, Colors.mix(plate, main, 0.04f), corner = 0.016f)
        out.rect(0.5f, dcY, 0.15f, 0.032f, Colors.mix(plate, main, 0.075f), corner = 0.016f)
        out.rect(0.5f, dcY, 0.08f, 0.009f, Colors.mix(plate, main, 0.25f), corner = 0.0045f)

        // which note plays now
        val ph = ((t % CYCLE) + CYCLE) % CYCLE
        val playing = if (ph < PLAY_EACH * 3) (ph / PLAY_EACH).toInt() else -1
        val prog = if (playing >= 0) (ph - playing * PLAY_EACH) / PLAY_EACH else 0f

        // this window's event
        val ew = floor(t / EVENT).toInt()
        val ef = t / EVENT - ew
        val kind = (hash(ew * 7 + 1) * 5f).toInt()
        val evRow = (hash(ew * 7 + 2) * 3f).toInt()

        for (r in 0 until 3) {
            val bx = BX[r]; val cy = ROW_Y[r] + bob
            val sent = r == 2
            val fill = if (sent) Colors.hueShift(Colors.mix(plate, ink.alt, 0.13f), 10f * sin(t * 0.5f)) else Colors.mix(plate, main, 0.07f)
            // soft shadow (two layers), bubble, tail
            out.rect(bx + BUB_W / 2, cy + 0.009f, BUB_W + 0.006f, BUB_H + 0.004f, Colors.mix(plate, main, 0.025f), corner = 0.05f * S)
            out.rect(bx + BUB_W / 2, cy + 0.004f, BUB_W, BUB_H, Colors.mix(plate, main, 0.045f), corner = 0.045f * S)
            out.rect(bx + BUB_W / 2, cy, BUB_W, BUB_H, fill, corner = 0.045f * S)
            val ty = cy - BUB_H / 2
            if (sent) out.poly(floatArrayOf(bx + BUB_W - 0.05f * S, ty, bx + BUB_W + 0.022f * S, ty, bx + BUB_W - 0.01f * S, ty + 0.04f * S), fill)
            else out.poly(floatArrayOf(bx + 0.05f * S, ty, bx - 0.022f * S, ty, bx + 0.01f * S, ty + 0.04f * S), fill)

            // play / pause button
            val px = bx + PLAY_DX
            out.circle(px, cy, PLAY_R, main)
            // the icon morphs: pause bars grow out of the triangle as the note starts
            val morph = if (r == playing) ease((prog * PLAY_EACH / 0.25f).coerceIn(0f, 1f)) * ease(((1f - prog) * PLAY_EACH / 0.25f).coerceIn(0f, 1f)) else 0f
            if (morph > 0.01f) {
                val hgt = 0.046f * S * (0.4f + 0.6f * morph)
                out.rect(px - 0.016f * S, cy, 0.014f * S, hgt, plate, corner = 0.004f * S)
                out.rect(px + 0.016f * S, cy, 0.014f * S, hgt, plate, corner = 0.004f * S)
            }
            if (morph < 0.99f) {
                val k = 1f - morph
                out.poly(floatArrayOf(px - 0.016f * S, cy - 0.027f * S * k, px - 0.016f * S, cy + 0.027f * S * k, px + 0.028f * S * k, cy), plate)
            }

            // the waveform (data: heights never move)
            val head = when {
                r < playing || (playing < 0 && ph >= PLAY_EACH * 3 && ph < CYCLE - 0.4f) -> NB.toFloat()
                r == playing -> prog * NB
                else -> 0f
            }
            for (i in 0 until NB) {
                val k = r * NB + i
                val lv = level(bits[2 * k] != MASK[2 * k], bits[2 * k + 1] != MASK[2 * k + 1])
                val h = LV[lv]
                val x = bx + BAR0_DX + i * PITCH
                // the bar just reached by the playhead takes the played colour with a quick fade-through
                val f = (head - i).coerceIn(0f, 1f)
                val c = if (f >= 1f) played else if (f <= 0f) main else Colors.mix(main, played, ease(f))
                out.rect(x, cy, BW, h, c, corner = BW / 2)
            }
            // playhead: a hairline in the gap after the last played bar (it spans the whole bubble height, so it shifts
            // a bar's background and its profile alike)
            if (r == playing) {
                val hi = floor(prog * NB).toInt().coerceIn(0, NB - 1)
                val hx = bx + BAR0_DX + (hi - 0.5f) * PITCH
                out.line(hx, cy - 0.104f * S, hx, cy + 0.104f * S, 0.003f, Colors.mix(plate, played, 0.3f), round = false)
            }

            // duration "0:0s" (seconds counting while it plays), time + ticks
            val secs = if (r == playing) (prog * 7f).toInt() else 7
            val dx = bx + BUB_W - 0.072f * S
            digits(out, dx, cy - 0.04f * S, intArrayOf(0, -1, 0, secs), 0.016f * S, 0.026f * S, soft)
            out.rect(bx + BUB_W - 0.085f * S, cy + 0.072f * S, 0.04f * S, 0.008f, faint, corner = 0.004f)
            if (sent) {
                val seen = (playing < 0 || playing == 2) || (kind == 2 && ef < 0.8f)
                val flash = if (kind == 2) bump(ef, 0.1f, 0.5f) else 0f
                val tc = if (seen) Colors.mix(soft, accent, 0.6f + 0.4f * flash) else soft
                if (flash > 0.02f) out.glow(bx + BUB_W - 0.05f * S, cy + 0.072f * S, 0.03f * flash, Colors.alpha(accent, 0.35f * flash))
                tick(out, bx + BUB_W - 0.068f * S, cy + 0.072f * S, tc)
                tick(out, bx + BUB_W - 0.05f * S, cy + 0.072f * S, tc)
            }

            // a reaction pops on this note (event)
            if (kind == 0 && evRow == r) reaction(out, bx + BUB_W - 0.03f * S, cy + BUB_H / 2 + 0.004f, ef, (hash(ew * 7 + 3) * 4f).toInt(), ink, accent, t)
        }

        // ---- header: back arrow, avatar with a breathing online ring, name, "typing…" with a shimmer, call icons
        header(out, t, ink, accent, kind == 1, ef)

        // ---- bottom: a "typing…" bubble that comes and goes, the input bar with a pulsing mic
        val typingOn = smooth01(((t * 0.13f) % 1f), 0.08f, 0.62f)
        if (typingOn > 0.01f) {
            val tyc = 0.872f + bob
            val sc = 0.6f + 0.4f * easeOutBack(typingOn)
            out.rect(0.03f + 0.07f * sc, tyc, 0.14f * sc, 0.052f * sc, Colors.mix(plate, main, 0.07f * typingOn), corner = 0.026f * sc)
            for (k in 0 until 3) {
                val b = sin((t * 6.5f - k * 0.9f)).coerceAtLeast(0f)
                out.circle(0.03f + (0.035f + k * 0.035f) * sc, tyc - 0.01f * b * sc, 0.008f * sc, Colors.mix(plate, main, (0.18f + 0.15f * b) * typingOn))
            }
        }
        inputBar(out, t, ink, accent)
    }

    private fun header(out: Scene, t: Float, ink: Ink, accent: Int, toast: Boolean, ef: Float) {
        val plate = ink.plate; val main = ink.main; val soft = ink.soft; val faint = ink.faint
        // a notification toast slides down from under the header (event)
        if (toast) {
            val k = bump(ef, 0.25f, 0.3f)
            if (k > 0.01f) {
                val y = 0.075f + 0.055f * easeOutBack(k)
                out.rect(0.5f, y + 0.004f, 0.36f, 0.04f, Colors.mix(plate, main, 0.05f * k), corner = 0.02f)
                out.rect(0.5f, y, 0.36f, 0.04f, Colors.mix(plate, accent, 0.25f * k), corner = 0.02f)
                out.circle(0.345f, y, 0.011f, Colors.mix(plate, accent, 0.7f * k))
                out.rect(0.48f, y - 0.005f, 0.2f, 0.007f, Colors.mix(plate, main, 0.3f * k), corner = 0.0035f)
                out.rect(0.45f, y + 0.007f, 0.14f, 0.006f, Colors.mix(plate, main, 0.18f * k), corner = 0.003f)
            }
        }
        out.rect(0.5f, 0.055f, 1f, 0.11f, Colors.mix(plate, main, 0.06f))
        out.line(0f, 0.111f, 1f, 0.111f, 0.003f, Colors.mix(plate, main, 0.035f), round = false)
        out.line(0f, 0.115f, 1f, 0.115f, 0.003f, Colors.mix(plate, main, 0.018f), round = false)
        // back arrow
        out.line(0.03f, 0.055f, 0.045f, 0.04f, 0.005f, soft); out.line(0.03f, 0.055f, 0.045f, 0.07f, 0.005f, soft)
        // avatar and its online ring
        val ax = 0.1f; val ay = 0.055f
        val ring = (t * 0.45f) % 1f
        out.circle(ax, ay, 0.036f + 0.02f * ease(ring), Colors.alpha(Colors.mix(plate, accent, 0.5f), 0.5f * (1f - ring)), 0.003f)
        out.circle(ax, ay, 0.034f, soft)
        out.circle(ax, ay - 0.009f, 0.012f, Colors.mix(plate, main, 0.08f))
        out.oval(ax, ay + 0.02f, 0.02f, 0.011f, Colors.mix(plate, main, 0.08f))
        val on = 0.5f + 0.5f * sin(t * 2.7f)
        out.glow(ax + 0.025f, ay + 0.025f, 0.018f, Colors.alpha(Colors.hueShift(ink.alt, 120f), 0.25f + 0.2f * on))
        out.circle(ax + 0.025f, ay + 0.025f, 0.008f + 0.0015f * on, Colors.hueShift(ink.alt, 120f))
        // name and status with a shimmer sweeping along
        out.rect(0.255f, 0.042f, 0.2f, 0.018f, soft, corner = 0.009f)
        out.rect(0.225f, 0.073f, 0.14f, 0.011f, faint, corner = 0.0055f)
        val sh = ((t * 0.55f) % 1.4f) - 0.2f
        if (sh in 0f..1f) out.rect(0.155f + 0.14f * sh, 0.073f, 0.03f, 0.011f, Colors.mix(faint, accent, 0.35f * sin(sh * PI.toFloat())), corner = 0.0055f)
        // call icons
        out.arc(0.84f, 0.058f, 0.014f, 100f, 160f, 0.006f, soft)
        out.rect(0.92f, 0.055f, 0.032f, 0.022f, soft, corner = 0.004f)
        out.poly(floatArrayOf(0.936f, 0.055f, 0.95f, 0.045f, 0.95f, 0.065f), soft)
    }

    private fun inputBar(out: Scene, t: Float, ink: Ink, accent: Int) {
        val plate = ink.plate; val main = ink.main; val soft = ink.soft; val faint = ink.faint
        val y = 0.948f
        out.rect(0.5f, y + 0.012f, 1f, 0.11f, Colors.mix(plate, main, 0.04f))
        out.rect(0.43f, y, 0.76f, 0.056f, Colors.mix(plate, main, 0.085f), corner = 0.028f)
        out.circle(0.09f, y, 0.014f, soft, 0.004f)
        out.circle(0.085f, y - 0.004f, 0.002f, soft); out.circle(0.095f, y - 0.004f, 0.002f, soft)
        out.arc(0.09f, y + 0.001f, 0.008f, 20f, 140f, 0.003f, soft)
        // placeholder text with a blinking caret
        out.rect(0.2f, y, 0.12f, 0.009f, faint, corner = 0.0045f)
        if (sin(t * 6.3f) > 0f) out.line(0.13f, y - 0.012f, 0.13f, y + 0.012f, 0.003f, soft, round = false)
        out.rect(0.75f, y + 0.002f, 0.03f, 0.022f, soft, corner = 0.004f, stroke = 0.003f)   // camera
        out.circle(0.75f, y + 0.002f, 0.006f, soft, 0.003f)
        // mic button with rings rolling out of it
        val mx = 0.91f
        for (k in 0 until 2) {
            val p = ((t * 0.6f + k * 0.5f) % 1f)
            out.circle(mx, y, 0.026f + 0.03f * ease(p), Colors.alpha(accent, 0.3f * (1f - p)), 0.0025f)
        }
        out.circle(mx, y, 0.026f, Colors.mix(plate, accent, 0.75f))
        out.rect(mx, y - 0.004f, 0.009f, 0.018f, plate, corner = 0.0045f)
        out.arc(mx, y - 0.002f, 0.009f, 20f, 140f, 0.0025f, plate)
    }

    /** Doodles of the chat wallpaper: two layers at different drift speeds; one sparkles per event window. */
    private fun wallpaper(out: Scene, t: Float, ink: Ink) {
        val plate = ink.plate; val main = ink.main
        for (layer in 0 until 2) {
            val n = if (layer == 0) 10 else 12
            val c = Colors.mix(plate, main, if (layer == 0) 0.075f else 0.045f)
            val sp = if (layer == 0) 0.011f else 0.006f
            val sz = if (layer == 0) 0.016f else 0.011f
            for (k in 0 until n) {
                val gx = (((k * 0.6180339f + layer * 0.37f) + t * sp) % 1f)
                val gy = (((k * 0.4142135f + layer * 0.19f) + t * sp * 0.6f) % 1f)
                val wob = 0.004f * sin(t * (0.5f + 0.07f * k) + k)
                val x = gx * 1.1f - 0.05f; val y = gy * 1.1f - 0.05f + wob
                when ((k + layer) % 5) {
                    0 -> out.circle(x, y, sz, c, 0.003f)
                    1 -> { out.circle(x - sz * 0.45f, y - sz * 0.2f, sz * 0.5f, c, 0.003f); out.circle(x + sz * 0.45f, y - sz * 0.2f, sz * 0.5f, c, 0.003f) }
                    2 -> out.poly(floatArrayOf(x - sz, y + sz * 0.6f, x + sz, y, x - sz * 0.3f, y - sz * 0.2f), c, 0.003f)
                    3 -> { out.circle(x - sz * 0.4f, y + sz * 0.5f, sz * 0.35f, c); out.line(x - sz * 0.1f, y + sz * 0.5f, x - sz * 0.1f, y - sz * 0.7f, 0.003f, c) }
                    else -> { out.line(x - sz, y, x + sz, y, 0.003f, c); out.line(x, y - sz, x, y + sz, 0.003f, c) }
                }
            }
        }
        // a sparkle somewhere on the wallpaper (event-like, on its own clock)
        val w = floor(t / 1.3f).toInt(); val f = t / 1.3f - w
        val k = bump(f, 0.3f, 0.5f)
        if (k > 0.01f) {
            val x = 0.05f + 0.9f * hash(w * 3 + 11); val y = 0.12f + 0.76f * hash(w * 3 + 12)
            val c = Colors.mix(plate, ink.alt, 0.3f * k)
            val r = 0.016f * k
            out.line(x - r, y, x + r, y, 0.003f, c); out.line(x, y - r, x, y + r, 0.003f, c)
            out.glow(x, y, 0.02f * k, Colors.alpha(ink.alt, 0.12f * k))
        }
    }

    /** A reaction badge popping in with overshoot, a few hearts floating up from it, then fading. */
    private fun reaction(out: Scene, x: Float, y: Float, f: Float, which: Int, ink: Ink, accent: Int, t: Float) {
        val plate = ink.plate
        val k = bump(f, 0.18f, 0.25f)
        if (k < 0.01f) return
        val pop = if (f < 0.18f) easeOutBack(f / 0.18f) else k
        val s = 0.024f * pop
        out.circle(x, y + 0.003f, s * 1.18f, Colors.mix(plate, ink.main, 0.08f * k))
        out.circle(x, y, s * 1.15f, Colors.mix(plate, ink.main, 0.1f * k))
        out.circle(x, y, s, plate)
        val c = Colors.mix(plate, Colors.hueShift(ink.alt, which * 70f), 0.7f * k)
        when (which) {
            0 -> heart(out, x, y + s * 0.08f, s * 0.6f, c)
            1 -> { out.rect(x - s * 0.15f, y + s * 0.15f, s * 0.75f, s * 0.6f, c, corner = s * 0.15f); out.rect(x - s * 0.05f, y - s * 0.35f, s * 0.3f, s * 0.6f, c, rot = -20f, corner = s * 0.1f) }
            2 -> { out.circle(x, y + s * 0.2f, s * 0.45f, c); out.poly(floatArrayOf(x - s * 0.4f, y + s * 0.1f, x, y - s * 0.75f, x + s * 0.4f, y + s * 0.1f), c) }
            else -> { out.circle(x, y, s * 0.7f, c, s * 0.18f); out.arc(x, y + s * 0.05f, s * 0.38f, 20f, 140f, s * 0.15f, c) }
        }
        // hearts floating up
        for (j in 0 until 3) {
            val p = ((f - 0.12f - j * 0.12f) / 0.6f)
            if (p <= 0f || p >= 1f) continue
            val hx = x - 0.012f + 0.012f * j + 0.008f * sin(t * 4f + j * 2f)
            val hy = y - 0.01f - 0.07f * ease(p)
            heart(out, hx, hy, 0.006f * (1f - 0.4f * p), Colors.mix(plate, accent, 0.5f * (1f - p)))
        }
    }

    /** 0 → 1 → 0 over a window: rises by [inF], holds, falls over the last [outF] (eased). */
    private fun bump(f: Float, inF: Float, outF: Float): Float = when {
        f < inF -> ease(f / inF)
        f > 1f - outF -> ease((1f - f) / outF)
        else -> 1f
    }

    /** Smooth on/off over a cycle phase: on between a and b with eased edges. */
    private fun smooth01(p: Float, a: Float, b: Float): Float {
        val e = 0.06f
        return ease(((p - a) / e).coerceIn(0f, 1f)) * ease(((b - p) / e).coerceIn(0f, 1f))
    }

    private fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    private fun hash(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
    }

    /** [c] pulled to lightness [want] (its hue kept). */
    private fun towards(c: Int, want: Float): Int {
        var out = c
        repeat(8) {
            val have = Colors.light(out)
            if (abs(have - want) < 0.01f) return out
            out = if (have < want) Colors.mix(out, 0xFFFFFFFF.toInt(), min(1f, (want - have) * 1.6f)) else Colors.mix(out, 0xFF000000.toInt(), min(1f, (have - want) * 1.6f))
        }
        return out
    }

    private fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; val y = x - 1; return 1 + c3 * y * y * y + c1 * y * y }

    private fun tick(out: Scene, x: Float, y: Float, c: Int) {
        out.line(x - 0.012f * S, y, x - 0.004f * S, y + 0.008f * S, 0.004f, c)
        out.line(x - 0.004f * S, y + 0.008f * S, x + 0.011f * S, y - 0.009f * S, 0.004f, c)
    }

    private fun heart(out: Scene, x: Float, y: Float, s: Float, c: Int) {
        out.circle(x - s * 0.5f, y - s * 0.25f, s * 0.55f, c)
        out.circle(x + s * 0.5f, y - s * 0.25f, s * 0.55f, c)
        out.poly(floatArrayOf(x - s * 1.02f, y - s * 0.05f, x + s * 1.02f, y - s * 0.05f, x, y + s * 1.05f), c)
    }

    /** Seven-segment digits (-1 = colon) starting at (x, y) top-left. */
    private fun digits(out: Scene, x: Float, y: Float, d: IntArray, w: Float, h: Float, c: Int) {
        var cx = x
        val th = w * 0.22f
        for (v in d) {
            if (v < 0) { out.circle(cx + th, y + h * 0.3f, th * 0.6f, c); out.circle(cx + th, y + h * 0.75f, th * 0.6f, c); cx += th * 3; continue }
            val seg = SEG[v]
            fun hs(yy: Float) = out.line(cx + th, yy, cx + w - th, yy, th, c)
            fun vs(xx: Float, y0: Float, y1: Float) = out.line(xx, y0 + th, xx, y1 - th, th, c)
            if (seg and 1 != 0) hs(y)
            if (seg and 2 != 0) vs(cx + w, y, y + h / 2)
            if (seg and 4 != 0) vs(cx + w, y + h / 2, y + h)
            if (seg and 8 != 0) hs(y + h)
            if (seg and 16 != 0) vs(cx, y + h / 2, y + h)
            if (seg and 32 != 0) vs(cx, y, y + h / 2)
            if (seg and 64 != 0) hs(y + h / 2)
            cx += w + th * 2.5f
        }
    }

    private val SEG = intArrayOf(63, 6, 91, 79, 102, 109, 125, 7, 127, 111)

    override fun reader(): SchemeReader = VoiceReader()
}

// ====================================================================== reader


private const val DIV = 12

private class VoiceReader : SchemeReader {
    override var seen = false
        private set
    private var pol = true
    private val acc = FloatArray(SigilPayload.BITS)
    private var lastOk = Long.MIN_VALUE / 2

    override fun feed(f: SigilFrame): Read? {
        seen = false
        var h: DoubleArray? = null
        val half = VHalf(f)
        for (k in 0 until 2) {
            val p = if (k == 0) pol else !pol
            h = vLocate(f, p, half)
            if (h != null) { pol = p; break }
        }
        if (h == null) {
            if (f.tMs - lastOk > 1500) acc.fill(0f)
            return null
        }
        seen = true
        lastOk = f.tMs
        val soft = vMeasure(f, h, pol) ?: return null
        for (i in acc.indices) acc[i] = acc[i] * 0.75f + soft[i]
        val bits = BooleanArray(SigilPayload.BITS) { (acc[it] > 0f) != VoiceNote.MASK[it] }
        val sure = FloatArray(SigilPayload.BITS) { abs(acc[it]) }
        return SigilPayload.decodeBits(bits, sure)
    }
}

private class Btn(val x: Float, val y: Float, val r: Float)

/** Finds the three notes; returns the homography unit square → camera pixels, or null. */
internal fun vLocate(f: SigilFrame, darkMarks: Boolean, half: VHalf = VHalf(f)): DoubleArray? {
    val mk = half.marks(darkMarks, 31, 9f)
    // play buttons: big round marks (a disc with a small icon cut out)
    val bs = ArrayList<Btn>()
    for (i in 0 until mk.n) {
        val a = mk.area[i]; val bw = mk.bw(i); val bh = mk.bh(i)
        if (a < 70 || bw > 90 || bh > 90) continue
        val fill = a.toFloat() / (bw * bh)
        if (fill < 0.45f || fill > 0.95f || bw > 1.7f * bh || bh > 1.7f * bw) continue
        bs.add(Btn(mk.cx[i], mk.cy[i], sqrt(a / (0.86f * PI.toFloat()))))
    }
    if (bs.size < 3) return null
    if (bs.size > 14) { bs.sortByDescending { it.r }; while (bs.size > 14) bs.removeAt(bs.size - 1) }
    val ux = VoiceNote.BX.map { it + VoiceNote.PLAY_DX }; val uy = VoiceNote.ROW_Y.toList()
    fun ud(a: Int, b: Int) = sqrt((ux[a] - ux[b]) * (ux[a] - ux[b]) + (uy[a] - uy[b]) * (uy[a] - uy[b]))
    val u01 = ud(0, 1); val u12 = ud(1, 2); val u02 = ud(0, 2)
    val sgn = if (darkMarks) 1f else -1f
    var tries = 0
    for (a in 0 until bs.size) for (b in 0 until bs.size) {
        if (a == b) continue
        val A = bs[a]; val B = bs[b]
        if (A.r < 0.75f * B.r || A.r > 1.33f * B.r) continue
        val sAB = (A.r + B.r) / 2 / VoiceNote.PLAY_R   // px per unit, roughly
        val dab = sqrt((A.x - B.x) * (A.x - B.x) + (A.y - B.y) * (A.y - B.y)) / sAB
        if (dab < 0.7f * u01 || dab > 1.43f * u01) continue
        // C where the third button must be (either side: mirror)
        val evx0 = (B.x - A.x) / (uy[1] - uy[0]); val evy0 = (B.y - A.y) / (uy[1] - uy[0])
        val dv = uy[2] - uy[1]; val du = ux[2] - ux[1]
        var C: Btn? = null; var cd = 0.075f * VoiceNote.S * sqrt(evx0 * evx0 + evy0 * evy0)
        for (c in bs.indices) {
            if (c == a || c == b) continue
            val q = bs[c]
            if (q.r < 0.75f * B.r || q.r > 1.33f * B.r) continue
            for (sg in intArrayOf(-1, 1)) {
                val tx = B.x + evx0 * dv - evy0 * du * sg; val ty = B.y + evy0 * dv + evx0 * du * sg
                val d = sqrt((q.x - tx) * (q.x - tx) + (q.y - ty) * (q.y - ty))
                if (d < cd) { cd = d; C = q }
            }
        }
        if (C == null) continue
        // A, B, C = buttons of rows 0, 1, 2. Rows 0 and 1 are aligned: A→B is the screen's down axis; the rows run
        // across it (the shifted button C says to which side; both are tried when that is unclear)
        val evx = (B.x - A.x) / (uy[1] - uy[0]); val evy = (B.y - A.y) / (uy[1] - uy[0])
        val cx = C.x - B.x - evx * (uy[2] - uy[1]); val cy = C.y - B.y - evy * (uy[2] - uy[1])
        val side = (cx * -evy + cy * evx) / (evx * evx + evy * evy) / (ux[2] - ux[1])   // ≈ +1 or −1
        if (abs(side) > 2.2f) continue
        for (sg in (if (side > 0.45f) intArrayOf(1) else if (side < -0.45f) intArrayOf(-1) else intArrayOf(1, -1))) {
            if (++tries > 14) return null
            val eux = -evy * sg; val euy = evx * sg
            val h = vRefine(f, floatArrayOf(A.x, A.y, B.x, B.y, C.x, C.y), eux, euy, evx, evy, sgn)
            if (h != null) return h
        }
    }
    return null
}

/**
 * Walks along each row from its button: every bar is found by the darkest spot near its predicted place (the
 * prediction follows the bars found so far, so perspective is tracked), then its centre by the darkness centroid along
 * its axis. Rows 1 and 2 start from what row 0 taught (step, button-to-first-bar offset). Returns the homography fitted
 * to all bars and buttons, or null if the bars are not there.
 */
private fun vRefine(f: SigilFrame, btn: FloatArray, eux0: Float, euy0: Float, evx: Float, evy: Float, sgn: Float): DoubleArray? {
    fun img(px: Float, py: Float) = f.at(px, py)
    // rough local contrast: button vs bubble just outside it
    var cRef = 0f
    for (r in 0 until 3) {
        val bx = btn[2 * r]; val by = btn[2 * r + 1]
        fun l(du: Float, dv: Float) = img(bx + eux0 * du + evx * dv, by + euy0 * du + evy * dv)
        val q = VoiceNote.S
        val inside = (l(-0.04f * q, 0f) + l(0f, -0.04f * q) + l(0f, 0.04f * q)) / 3
        val out = (l(0f, -0.085f * q) + l(0f, 0.085f * q)) / 2
        cRef += sgn * (out - inside)
    }
    cRef /= 3
    if (cRef < 12f) return null
    // the scale along the rows from the buttons' widths (the screen may be foreshortened either way)
    val el = sqrt(eux0 * eux0 + euy0 * euy0)
    val vl = sqrt(evx * evx + evy * evy)
    var wsum = 0f; var wn = 0
    for (r in 0 until 3) {
        // the button's own aspect (width along the row / width across it): blur and threshold widen both alike
        val wu = vWidth(f, btn[2 * r], btn[2 * r + 1], eux0 / el, euy0 / el, VoiceNote.PLAY_R * el, sgn)
        val wv = vWidth(f, btn[2 * r], btn[2 * r + 1], evx / vl, evy / vl, VoiceNote.PLAY_R * vl, sgn)
        if (wu > 0f && wv > 0f) { wsum += wu / wv; wn++ }
    }
    val scale = if (wn > 0) (wsum / wn * vl / el).coerceIn(0.7f, 1.4f) else 1f
    val eux = eux0 * scale; val euy = euy0 * scale
    val pit = VoiceNote.PITCH
    val gapU = VoiceNote.BAR0_DX - VoiceNote.PLAY_DX
    var stepX = eux * pit; var stepY = euy * pit
    var gapX = eux * gapU; var gapY = euy * gapU
    val n = 3 * VoiceNote.NB + 3
    val src = FloatArray(2 * n); val dst = FloatArray(2 * n)
    var k = 0
    var good = 0
    val w = VWalk(f, evx, evy, sgn, cRef)
    for (r in 0 until 3) {
        val bx = btn[2 * r]; val by = btn[2 * r + 1]
        var shift = 0
        var ok = false
        for (attempt in 0 until 3) {
            val sx0 = bx + gapX + shift * stepX; val sy0 = by + gapY + shift * stepY
            w.run(sx0, sy0, stepX, stepY)
            if (w.found < 9) break
            // the first bar must stand PLAY_GAP of its own row's steps from the button
            val c = min(4, w.found - 1)
            val dc = (w.idx[c] - w.idx[0]).toFloat()
            val s0x = (w.px[c] - w.px[0]) / dc; val s0y = (w.py[c] - w.py[0]) / dc
            val d = ((w.px[0] - bx) * s0x + (w.py[0] - by) * s0y) / (s0x * s0x + s0y * s0y) - w.idx[0]
            val after = w.barAt(w.endX, w.endY)
            val off = (d - VoiceNote.PLAY_GAP).roundToInt()
            if (abs(d - VoiceNote.PLAY_GAP - off) > 0.33f) break
            if (off != 0) { if (attempt < 2) { shift -= off; continue } else break }
            ok = after < 0.45f
            break
        }
        if (!ok) continue
        val v0 = VoiceNote.ROW_Y[r]
        val ub0 = VoiceNote.BX[r] + VoiceNote.BAR0_DX
        for (j in 0 until w.found) {
            src[2 * k] = ub0 + w.idx[j] * pit; src[2 * k + 1] = v0; dst[2 * k] = w.px[j]; dst[2 * k + 1] = w.py[j]; k++
        }
        good += w.found
        src[2 * k] = VoiceNote.BX[r] + VoiceNote.PLAY_DX; src[2 * k + 1] = v0
        dst[2 * k] = bx; dst[2 * k + 1] = by; k++
        if (r == 0 && w.found >= 10) {
            // learn the step and the button-to-bar offset for the other rows
            val a = 0; val b = w.found - 1
            val di = (w.idx[b] - w.idx[a]).toFloat()
            stepX = (w.px[b] - w.px[a]) / di; stepY = (w.py[b] - w.py[a]) / di
            // step at the start of the row (perspective): from the first few bars
            val c = min(4, b)
            val dc = (w.idx[c] - w.idx[a]).toFloat()
            val s0x = (w.px[c] - w.px[a]) / dc; val s0y = (w.py[c] - w.py[a]) / dc
            gapX = w.px[a] - w.idx[a] * s0x - bx; gapY = w.py[a] - w.idx[a] * s0y - by
            stepX = s0x; stepY = s0y
        }
    }
    if (good < 30) return null
    return robustH(src, dst, k, 0.28f * pit * sqrt(eux * eux + euy * euy))
}

/**
 * Full width (px) at half contrast of a button along direction (ux, uy), or −1. The outside reference is taken on the
 * diagonals and the scan starts just outside the rim, so the first bar's blurred edge never counts.
 */
private fun vWidth(f: SigilFrame, cx: Float, cy: Float, ux: Float, uy: Float, r: Float, sgn: Float): Float {
    val ring = (f.at(cx + ux * 0.78f * r, cy + uy * 0.78f * r) + f.at(cx - ux * 0.78f * r, cy - uy * 0.78f * r)) / 2
    val d = 0.95f * r
    val out = (f.at(cx + (ux - uy) * d, cy + (uy + ux) * d) + f.at(cx + (-ux - uy) * d, cy + (-uy + ux) * d) +
        f.at(cx + (ux + uy) * d, cy + (uy - ux) * d) + f.at(cx + (-ux + uy) * d, cy + (-uy - ux) * d)) / 4
    if (sgn * (out - ring) < 10f) return -1f
    val half = (ring + out) / 2
    var tot = 0f
    for (side in intArrayOf(1, -1)) {
        var t = 1.3f * r
        var prev = f.at(cx + side * ux * t, cy + side * uy * t)
        var hit = -1f
        while (t > 0.6f * r) {
            val tn = t - 0.25f
            val v = f.at(cx + side * ux * tn, cy + side * uy * tn)
            if (sgn * (half - v) > 0) { hit = tn + 0.25f * (sgn * (half - v)) / max(1e-3f, sgn * (prev - v)); break }
            prev = v; t = tn
        }
        if (hit < 0) return -1f
        tot += hit
    }
    return tot
}

private const val U6 = 0.006f * VoiceNote.S
private const val U12 = 0.012f * VoiceNote.S
private const val U85 = 0.085f * VoiceNote.S
private const val U98 = 0.098f * VoiceNote.S

/** One walk along a row of bars. */
private class VWalk(val f: SigilFrame, val ax0: Float, val ay0: Float, val sgn: Float, val cRef: Float) {
    val px = FloatArray(VoiceNote.NB); val py = FloatArray(VoiceNote.NB); val idx = IntArray(VoiceNote.NB)
    var found = 0
    var endX = 0f; var endY = 0f
    private val off = FloatArray(9)

    private fun dark3(qx: Float, qy: Float) =
        -sgn * (f.at(qx - ax0 * U6, qy - ay0 * U6) + f.at(qx, qy) + f.at(qx + ax0 * U6, qy + ay0 * U6))

    private var sx = 0f; private var sy = 0f

    /** How much a bar stands at (qx, qy) against the gaps beside it (≈ 1 for a bar, ≈ 0 for none). */
    fun barAt(qx: Float, qy: Float): Float {
        var best = -1e9f
        for (a in -2..2) for (j in -2..2) {
            val x = qx + ax0 * a * 0.01f * VoiceNote.S + sx * j * 0.12f; val y = qy + ay0 * a * 0.01f * VoiceNote.S + sy * j * 0.12f
            val centre = dark3(x, y) / 3
            val gaps = -sgn * (f.at(x - sx / 2, y - sy / 2) + f.at(x + sx / 2, y + sy / 2)) / 2
            best = max(best, (centre - gaps) / cRef)
        }
        return best
    }

    fun run(x0: Float, y0: Float, stepX: Float, stepY: Float) {
        var x = x0; var y = y0; sx = stepX; sy = stepY
        found = 0
        var lastI = -1; var lx = 0f; var ly = 0f
        for (i in 0 until VoiceNote.NB) {
            var best = -1e9f; var bj = 0; var bv = 0f
            val across = if (lastI < 0) 4 else 2
            for (aj in -across..across) {
                val av = aj * 0.008f * VoiceNote.S
                val bxp = x + ax0 * av; val byp = y + ay0 * av
                for (j in 0 until 9) {
                    val t = (j - 4) * 0.1f
                    val d = dark3(bxp + sx * t, byp + sy * t)
                    if (d > best) { best = d; bj = j; bv = av }
                }
            }
            val cx = x + ax0 * bv; val cy = y + ay0 * bv
            for (j in 0 until 9) { val t = (j - 4) * 0.1f; off[j] = dark3(cx + sx * t, cy + sy * t) }
            var t = (bj - 4) * 0.1f
            if (bj in 1..7) {
                val a = off[bj - 1]; val b = off[bj]; val c = off[bj + 1]
                val den = a - 2 * b + c
                if (den < 0f) t += (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f) * 0.1f
            }
            val qx = cx + sx * t; val qy = cy + sy * t
            val centre = dark3(qx, qy) / 3
            val gaps = -sgn * (f.at(qx - sx / 2, qy - sy / 2) + f.at(qx + sx / 2, qy + sy / 2) +
                f.at(qx - sx / 2 + ax0 * U12, qy - sy / 2 + ay0 * U12) + f.at(qx + sx / 2 - ax0 * U12, qy + sy / 2 - ay0 * U12)) / 4
            val con = (centre - gaps) / cRef
            if (con > 0.12f && abs(t) < 0.38f) {
                // centre line: darkness centroid along the bar
                val outer = (f.at(qx - ax0 * U98, qy - ay0 * U98) + f.at(qx + ax0 * U98, qy + ay0 * U98)) / 2
                var sw = 0f; var sv = 0f
                var vv = -U85
                while (vv <= U85 * 1.001f) {
                    val d = sgn * (outer - f.at(qx + ax0 * vv, qy + ay0 * vv)) - 0.3f * cRef
                    if (d > 0) { sw += d; sv += d * vv }
                    vv += U6
                }
                val dv = if (sw > 0) (sv / sw).coerceIn(-0.02f * VoiceNote.S, 0.02f * VoiceNote.S) else 0f
                val fx = qx + ax0 * dv; val fy = qy + ay0 * dv
                px[found] = fx; py[found] = fy; idx[found] = i; found++
                if (lastI >= 0) {
                    val g = (i - lastI).toFloat()
                    sx = 0.5f * sx + 0.5f * (fx - lx) / g; sy = 0.5f * sy + 0.5f * (fy - ly) / g
                }
                lastI = i; lx = fx; ly = fy
                x = fx + sx; y = fy + sy
            } else {
                x += sx; y += sy
            }
        }
        endX = x; endY = y
    }
}

/** Least-squares homography with one round of outlier rejection; null if the fit is poor. */
private fun robustH(src: FloatArray, dst: FloatArray, n: Int, tolPx: Float): DoubleArray? {
    var h = fitH(src, dst, n) ?: return null
    val p = FloatArray(2)
    val err = FloatArray(n)
    for (round in 0 until 3) {
        for (i in 0 until n) {
            Fit.apply(h, src[2 * i], src[2 * i + 1], p)
            err[i] = sqrt((p[0] - dst[2 * i]) * (p[0] - dst[2 * i]) + (p[1] - dst[2 * i + 1]) * (p[1] - dst[2 * i + 1]))
        }
        val srt = err.copyOf(); srt.sort()
        val med = srt[n / 2]
        val lim = max(tolPx, 2.5f * med)
        var bad = 0
        for (i in 0 until n) if (err[i] > lim) bad++
        if (med > tolPx || bad > n / 3) return null
        if (bad == 0) return h
        val s2 = FloatArray(2 * (n - bad)); val d2 = FloatArray(2 * (n - bad))
        var k = 0
        for (i in 0 until n) if (err[i] <= lim) { s2[2 * k] = src[2 * i]; s2[2 * k + 1] = src[2 * i + 1]; d2[2 * k] = dst[2 * i]; d2[2 * k + 1] = dst[2 * i + 1]; k++ }
        h = fitH(s2, d2, k) ?: return null
    }
    return h
}

/** Homography (unit square → pixels) from n point pairs, least squares on normalised coordinates. */
private fun fitH(src: FloatArray, dst: FloatArray, n: Int): DoubleArray? {
    if (n < 4) return null
    var mx = 0.0; var my = 0.0
    for (i in 0 until n) { mx += dst[2 * i]; my += dst[2 * i + 1] }
    mx /= n; my /= n
    var sd = 0.0
    for (i in 0 until n) sd += sqrt((dst[2 * i] - mx) * (dst[2 * i] - mx) + (dst[2 * i + 1] - my) * (dst[2 * i + 1] - my))
    sd /= n
    if (sd < 1e-6) return null
    val k = 1.0 / sd
    val m = Array(8) { DoubleArray(9) }
    val r = DoubleArray(8)
    for (i in 0 until n) {
        val u = src[2 * i] - 0.5; val v = src[2 * i + 1] - 0.5
        val x = (dst[2 * i] - mx) * k; val y = (dst[2 * i + 1] - my) * k
        for (row in 0 until 2) {
            r.fill(0.0)
            val b: Double
            if (row == 0) { r[0] = u; r[1] = v; r[2] = 1.0; r[6] = -x * u; r[7] = -x * v; b = x }
            else { r[3] = u; r[4] = v; r[5] = 1.0; r[6] = -y * u; r[7] = -y * v; b = y }
            for (p in 0 until 8) { if (r[p] == 0.0) continue; for (q in 0 until 8) m[p][q] += r[p] * r[q]; m[p][8] += r[p] * b }
        }
    }
    val s = Fit.solve(m) ?: return null
    // H = Tdst⁻¹ · Hn · Tsrc
    val hn = doubleArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], 1.0)
    val ts = doubleArrayOf(1.0, 0.0, -0.5, 0.0, 1.0, -0.5, 0.0, 0.0, 1.0)
    val td = doubleArrayOf(sd, 0.0, mx, 0.0, sd, my, 0.0, 0.0, 1.0)
    val out = mul3(td, mul3(hn, ts))
    val z = out[8]
    if (abs(z) < 1e-12) return null
    for (i in 0 until 9) out[i] /= z
    return out
}

private fun mul3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
    val r = i / 3; val c = i % 3
    a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
}

/** Soft drawn bits (positive = 1) from bar heights, or null if the picture is not readable. */
internal fun vMeasure(f: SigilFrame, h: DoubleArray, darkMarks: Boolean): FloatArray? {
    val out = FloatArray(SigilPayload.BITS)
    val sgn = if (darkMarks) 1f else -1f
    val p = FloatArray(2)
    val half = 0.104f * VoiceNote.S
    val prof = FloatArray(200)
    val lv = VoiceNote.LV
    val step = lv[1] - lv[0]
    val sh = 0.002f * VoiceNote.S
    val b01 = (lv[0] + lv[1]) / 2 - sh; val b12 = (lv[1] + lv[2]) / 2 - sh; val b23 = (lv[2] + lv[3]) / 2 - sh
    var good = 0
    for (r in 0 until 3) for (i in 0 until VoiceNote.NB) {
        val ux = VoiceNote.BX[r] + VoiceNote.BAR0_DX + i * VoiceNote.PITCH; val uy = VoiceNote.ROW_Y[r]
        Fit.apply(h, ux, uy - 0.05f, p); val ax = p[0]; val ay = p[1]
        Fit.apply(h, ux, uy + 0.05f, p)
        val lam = sqrt((p[0] - ax) * (p[0] - ax) + (p[1] - ay) * (p[1] - ay)) / 0.1f
        val ns = (2 * half * lam / 1.3f).toInt().coerceIn(24, 150)
        val dv = 2 * half / ns
        for (s in 0..ns) {
            val v = uy - half + s * dv
            var acc = 0f
            for (t in -1..1) {
                Fit.apply(h, ux + t * 0.0045f * VoiceNote.S, v, p)
                acc += f.at(p[0], p[1])
            }
            prof[s] = acc / 3f
        }
        val nb = max(2, (0.014f * VoiceNote.S / dv).toInt())
        var top = 0f; var bot = 0f
        for (s in 0 until nb) { top += prof[s]; bot += prof[ns - s] }
        top /= nb; bot /= nb
        var peak = 0f; var np = 0; var integ = 0f
        for (s in 0..ns) {
            val v = -half + s * dv
            val bg = top + (bot - top) * (s.toFloat() / ns)
            val d = sgn * (bg - prof[s])
            if (abs(v) <= 0.0075f * VoiceNote.S) { peak += d; np++ }
            if (abs(v) <= 0.089f * VoiceNote.S) integ += d * dv
        }
        peak /= max(1, np)
        val k = r * VoiceNote.NB + i
        if (peak < 10f) continue
        good++
        val len = integ / peak
        out[2 * k] = ((len - b12) / step).coerceIn(-1f, 1f)
        out[2 * k + 1] = (min(len - b01, b23 - len) / step).coerceIn(-1f, 1f)
    }
    return if (good >= 30) out else null
}

// ---------------------------------------------------------------------- fast marks (connected components)

/**
 * Marks of one polarity, found on a 2× smaller image: pixels darker (or lighter) than the mean of a window of
 * [win] half-res pixels round them by more than [delta] grey levels, joined into 8-connected components. Coordinates
 * are in full-frame pixels. Unlike plain local thresholding, flat noisy areas give nothing, so it stays cheap.
 */
internal class VMarks(cap: Int) {
    var n = 0
    var cx = FloatArray(cap); var cy = FloatArray(cap); var area = IntArray(cap); var lum = FloatArray(cap)
    var x0 = IntArray(cap); var y0 = IntArray(cap); var x1 = IntArray(cap); var y1 = IntArray(cap)
    fun bw(i: Int) = x1[i] - x0[i] + 1
    fun bh(i: Int) = y1[i] - y0[i] + 1
}

internal class VHalf(val f: SigilFrame) {
    val hw = f.w / 2; val hh = f.h / 2
    /** 2×2 sums (0..1020). */
    val img = IntArray(hw * hh)
    val integ = IntArray((hw + 1) * (hh + 1))
    init {
        for (y in 0 until hh) {
            val r0 = f.offset + 2 * y * f.stride; val r1 = r0 + f.stride
            for (x in 0 until hw) {
                img[y * hw + x] = (f.lum[r0 + 2 * x].toInt() and 0xFF) + (f.lum[r0 + 2 * x + 1].toInt() and 0xFF) +
                    (f.lum[r1 + 2 * x].toInt() and 0xFF) + (f.lum[r1 + 2 * x + 1].toInt() and 0xFF)
            }
        }
        val iw = hw + 1
        for (y in 0 until hh) {
            var row = 0
            for (x in 0 until hw) { row += img[y * hw + x]; integ[(y + 1) * iw + x + 1] = integ[y * iw + x + 1] + row }
        }
    }

    /** Runs of "on" pixels (half-res) of one polarity, row by row. */
    class Runs(cap: Int, hh: Int) {
        var n = 0
        val y = IntArray(cap); val s = IntArray(cap); val e = IntArray(cap)
        val rowStart = IntArray(hh + 1)
    }
    private var sweepKey = -1
    private var sweepDark: Runs? = null
    private var sweepLight: Runs? = null

    /** One sweep thresholds both polarities (the window sums are shared); cached per window and margin. */
    fun sweep(win: Int, delta: Float, darkMarks: Boolean): Runs {
        val key = win * 4096 + (delta * 8).toInt()
        if (key != sweepKey) {
            sweepKey = key
            val rad = win / 2
            val iw = hw + 1
            val d4 = (delta * 4).toInt()
            val cap = hw * hh / 3 + 16
            val rd = Runs(cap, hh); val rl = Runs(cap, hh)
            for (y in 0 until hh) {
                rd.rowStart[y] = rd.n; rl.rowStart[y] = rl.n
                val ya = max(0, y - rad); val yb = min(hh, y + rad + 1)
                var inD = false; var inL = false
                for (x in 0 until hw) {
                    val xa = max(0, x - rad); val xb = min(hw, x + rad + 1)
                    val sum = integ[yb * iw + xb] - integ[ya * iw + xb] - integ[yb * iw + xa] + integ[ya * iw + xa]
                    val ar = (yb - ya) * (xb - xa)
                    val diff = sum - img[y * hw + x] * ar
                    val lim = d4 * ar
                    val onD = diff > lim; val onL = -diff > lim
                    if (onD != inD) {
                        if (onD) { if (rd.n < cap - 1) { rd.y[rd.n] = y; rd.s[rd.n] = x; inD = true } }
                        else { rd.e[rd.n] = x - 1; rd.n++; inD = false }
                    }
                    if (onL != inL) {
                        if (onL) { if (rl.n < cap - 1) { rl.y[rl.n] = y; rl.s[rl.n] = x; inL = true } }
                        else { rl.e[rl.n] = x - 1; rl.n++; inL = false }
                    }
                }
                if (inD) { rd.e[rd.n] = hw - 1; rd.n++ }
                if (inL) { rl.e[rl.n] = hw - 1; rl.n++ }
            }
            rd.rowStart[hh] = rd.n; rl.rowStart[hh] = rl.n
            sweepDark = rd; sweepLight = rl
        }
        return if (darkMarks) sweepDark!! else sweepLight!!
    }

    fun marks(darkMarks: Boolean, win: Int, delta: Float, maxN: Int = 3000): VMarks {
        val rs = sweep(win, delta, darkMarks)
        val runY = rs.y; val runS = rs.s; val runE = rs.e; val rowStart = rs.rowStart; val nr = rs.n
        // union-find over runs
        val par = IntArray(nr) { it }
        fun find(a: Int): Int { var x = a; while (par[x] != x) { par[x] = par[par[x]]; x = par[x] }; return x }
        for (y in 1 until hh) {
            var p = rowStart[y - 1]; val pe = rowStart[y]
            for (q in rowStart[y] until rowStart[y + 1]) {
                while (p < pe && runE[p] < runS[q] - 1) p++
                var k = p
                while (k < pe && runS[k] <= runE[q] + 1) {
                    val a = find(k); val b = find(q); if (a != b) par[max(a, b)] = min(a, b)
                    k++
                }
            }
        }
        // stats
        val id = IntArray(nr) { -1 }
        val m = VMarks(min(maxN, nr) + 1)
        val sx = DoubleArray(m.cx.size); val sy = DoubleArray(m.cx.size); val sl = DoubleArray(m.cx.size)
        for (q in 0 until nr) {
            val r = find(q)
            var k = id[r]
            if (k < 0) {
                if (m.n >= maxN) continue
                k = m.n++; id[r] = k
                m.x0[k] = Int.MAX_VALUE; m.y0[k] = Int.MAX_VALUE; m.x1[k] = -1; m.y1[k] = -1
            }
            val y = runY[q]; val a = runS[q]; val b = runE[q]; val len = b - a + 1
            m.area[k] += len
            sx[k] += (a + b) * 0.5 * len; sy[k] += y.toDouble() * len
            var s = 0L; for (x in a..b) s += img[y * hw + x]; sl[k] += s.toDouble()
            if (a < m.x0[k]) m.x0[k] = a; if (b > m.x1[k]) m.x1[k] = b
            if (y < m.y0[k]) m.y0[k] = y; if (y > m.y1[k]) m.y1[k] = y
        }
        // to full-res pixels (areas in full-res pixels too)
        for (k in 0 until m.n) {
            val a = m.area[k]
            m.cx[k] = (sx[k] / a).toFloat() * 2f + 0.5f; m.cy[k] = (sy[k] / a).toFloat() * 2f + 0.5f
            m.lum[k] = (sl[k] / a / 4.0).toFloat()
            m.area[k] = a * 4
            m.x0[k] *= 2; m.y0[k] *= 2; m.x1[k] = m.x1[k] * 2 + 1; m.y1[k] = m.y1[k] * 2 + 1
        }
        return m
    }
}
