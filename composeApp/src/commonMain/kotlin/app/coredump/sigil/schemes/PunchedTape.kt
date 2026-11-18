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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GAZETTE: a newspaper front page (masthead, headline, columns, a rubber stamp, a curl of cut tape) whose lead picture
 * is a ruled box with a teleprinter's punched tape feeding through it, right to left, as it is punched (the thick left
 * rule is the punch head). Baudot layout: five hole tracks and a smaller sprocket track between tracks 2 and 3.
 *
 * Data: the tape repeats an 18-column loop: a sync column (sprocket missing, all five holes punched) and 17 data
 * columns of 5 bits (85 slots, 84 used). About 15 columns are in view at once; the loop is complete within a second
 * of scrolling.
 *
 * Reader: the box rule is one big ring-shaped mark; its outer outline (hull → 4 corners → edges refined on the
 * luminance) gives a homography. The box's long side is the tape's direction, the thick rule tells left from right
 * (mirror), the sprocket row (nearly every column punched) tells top from bottom. The hole lattice's phase comes from
 * the summed row profiles; the missing sprocket names the columns; each hole is read against plain paper next to it,
 * and votes add up over frames.
 */
object PunchedTape : SigilScheme {
    override val name = "tape"

    // ------------------------------------------------------------------ layout (unit square)
    internal const val P = 0.049f            // column pitch = track pitch
    internal const val CYC = 18
    internal const val ROW0 = 0.5f - 2.5f * P   // track 1; rows r = 0..5 at ROW0 + r·P, sprocket at r = 2
    internal const val HOLE_R = 0.0155f
    internal const val SPROCKET_R = 0.0102f
    internal const val SPEED = 3.0f          // columns per second
    // the box (outer edge of its rule)
    internal const val BX0 = 0.095f
    internal const val BX1 = 0.905f
    internal const val BY0 = 0.30f
    internal const val BY1 = 0.70f
    internal const val RULE = 0.018f
    internal const val RULE_L = 0.038f      // the punch head side
    // readable window between the guide rollers
    internal const val WX0 = BX0 + RULE_L + 0.02f
    internal const val WX1 = BX1 - RULE - 0.02f
    internal val ROWS = intArrayOf(0, 1, 3, 4, 5)   // the five hole tracks (r = 2 is the sprocket)

    internal val MASK = BooleanArray(SigilPayload.BITS).also { m ->
        var s = 0x7A9E_0B5DL
        for (i in m.indices) { s = (s * 6364136223846793005L + 1442695040888963407L); m[i] = (s ushr 61) and 1L == 1L }
    }

    internal fun rowY(r: Int) = ROW0 + r * P

    private const val EVENT = 1.6f

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val plate = ink.plate; val main = ink.main
        val soft = ink.soft; val faint = ink.faint

        // ---- the page: a desk lamp's pool of light wandering over it, darker corners, dust in the light
        val lx = 0.5f + 0.28f * sin(t * 0.11f) + 0.06f * sin(t * 0.37f)
        val ly = 0.5f + 0.22f * cos(t * 0.083f)
        out.glow(lx, ly, 0.62f, Colors.alpha(Colors.mix(plate, ink.alt, 0.25f), 0.10f))
        val vig = Colors.alpha(Colors.mix(plate, main, 0.35f), 0.22f)
        for (k in 0 until 4) out.glow((k % 2).toFloat(), (k / 2).toFloat(), 0.42f, vig)
        for (k in 0 until 8) {
            val ph = t * (0.021f + 0.004f * k) + k * 0.37f
            val mx = lx + 0.3f * sin(ph * 6.1f + k)
            val my = ly + 0.28f * ((ph * 1.3f + hash(k) * 3f) % 1f - 0.5f) * 2f
            val tw = 0.5f + 0.5f * sin(t * (1.1f + 0.3f * k) + k * 2f)
            out.circle(mx, my, 0.0018f + 0.0012f * tw, Colors.mix(plate, main, 0.07f + 0.08f * tw))
        }
        // paper grain
        for (k in 0 until 18) {
            val sx = ((k * 0.6180339f + 0.13f) % 1f) * 0.9f + 0.05f
            val sy = ((k * 0.4142135f + 0.71f) % 1f) * 0.95f + 0.02f
            if (sy in 0.27f..0.73f) continue
            out.circle(sx, sy, 0.0016f + 0.001f * ((k * 7) % 3), Colors.mix(plate, main, 0.15f))
        }

        // ---- masthead
        out.line(0.045f, 0.035f, 0.955f, 0.035f, 0.004f, main, round = false)
        out.line(0.045f, 0.046f, 0.955f, 0.046f, 0.0015f, main, round = false)
        // title: blackletter-ish glyphs (stems with diamond heads and feet), a telegraph-pole emblem between words
        var x = 0.2f
        val kinds = intArrayOf(0, 1, 2, 3, 1, 4, -1, 2, 1, 0, 3, 1)
        for (kd in kinds) {
            if (kd < 0) { emblem(out, x + 0.03f, 0.085f, main, plate, t); x += 0.07f; continue }
            x += blackletter(out, x, 0.085f, kd, main, plate) + 0.012f
        }
        out.line(0.045f, 0.127f, 0.955f, 0.127f, 0.0015f, main, round = false)
        out.line(0.045f, 0.137f, 0.955f, 0.137f, 0.004f, main, round = false)
        // the wire ticker: dots and dashes running in from the right, fading at both ends
        ticker(out, t, ink)

        // ---- headline (two bold bars of "words"), now and then a glint of wet ink sweeping across
        val w1 = floatArrayOf(0.13f, 0.09f, 0.16f, 0.07f, 0.12f, 0.17f)
        headline(out, 0.065f, 0.192f, w1, 0.03f, main)
        headline(out, 0.065f, 0.243f, floatArrayOf(0.1f, 0.15f, 0.08f, 0.2f, 0.11f), 0.018f, Colors.mix(plate, main, 0.55f))
        val gl = ((t * 0.19f) % 1f) * 1.6f - 0.3f
        if (gl in -0.1f..1.1f) {
            val gx = 0.05f + 0.9f * gl
            out.rect(gx, 0.192f, 0.018f, 0.04f, Colors.alpha(Colors.mix(main, plate, 0.6f), 0.35f * sin(((gl + 0.1f) / 1.2f) * PI.toFloat())), rot = 18f)
        }

        // ---- this moment's event (and the stamps of the last few, fading)
        val ew = floor(t / EVENT).toInt()
        val ef = t / EVENT - ew
        for (back in 2 downTo 0) {
            val w = ew - back
            if (kindOf(w) <= 1) stampEvent(out, w, (ef + back) / 3f, ink)
        }

        // ---- the box with the tape
        drawBox(bits, t, ink, out)

        // ---- body text columns, one line being set by a caret, column rules
        typeset(out, t, ink)

        // ---- a curl of cut tape lying on the page (bottom left)
        curl(out, 0.135f, 0.905f, t, ink)

        // ---- the events that are not stamps
        when (kindOf(ew)) {
            2 -> pencil(out, ew, ef, w1, ink)
            3 -> coffee(out, ew, ef, ink)
            4 -> chads(out, ew, ef, ink)
        }
    }

    private fun kindOf(w: Int) = (hash(w * 13 + 5) * 5f).toInt()

    /** Dots and dashes of the wire service running right to left under the masthead. */
    private fun ticker(out: Scene, t: Float, ink: Ink) {
        val cell = 0.024f
        val sh = t * 0.06f
        val n0 = floor(sh / cell).toInt()
        val y = 0.155f
        for (k in 0 until 40) {
            val n = n0 + k
            val x = 0.06f + n * cell - sh
            if (x < 0.06f || x > 0.94f) continue
            val e = min(1f, min(x - 0.06f, 0.94f - x) / 0.08f)
            val c = Colors.mix(ink.plate, ink.main, 0.45f * ease(e))
            when ((hash(n * 3 + 1) * 4f).toInt()) {
                0, 1 -> out.circle(x, y, 0.0032f, c)
                2 -> out.rect(x, y, 0.014f, 0.006f, c, corner = 0.003f)
                else -> {}
            }
        }
    }

    /** Body text in three columns; one line is being set (it grows behind a blinking caret), then the next. */
    private fun typeset(out: Scene, t: Float, ink: Ink) {
        val faint = ink.faint
        val per = 2.3f
        val cyc = floor(t / per).toInt()
        val fr = t / per - cyc
        val active = (hash(cyc * 7 + 3) * 24f).toInt()
        for (col in 0 until 3) {
            val cx0 = 0.045f + col * 0.31f
            for (ln in 0 until 8) {
                val y = 0.76f + ln * 0.022f
                if (y > 0.965f) break
                var len = 0.27f - (if (ln == 7) 0.11f else 0f) - ((col * 7 + ln * 3) % 5) * 0.006f
                if (col == 0 && ln in 3..7) len -= 0.14f   // room for the curl
                if (len <= 0.02f) continue
                if (col * 8 + ln == active) {
                    val shown = len * ease((fr / 0.75f).coerceIn(0f, 1f))
                    if (shown > 0.004f) out.rect(cx0 + shown / 2, y, shown, 0.008f, faint)
                    if (sin(t * 9f) > -0.2f) out.line(cx0 + shown + 0.004f, y - 0.008f, cx0 + shown + 0.004f, y + 0.008f, 0.0025f, ink.soft, round = false)
                } else out.rect(cx0 + len / 2, y, len, 0.008f, faint)
            }
            if (col > 0) out.line(cx0 - 0.02f, 0.75f, cx0 - 0.02f, 0.965f, 0.0015f, Colors.mix(ink.plate, ink.main, 0.08f), round = false)
        }
    }

    /** A rubber stamp lands at a random spot of the lower page and its ink slowly fades ([age] 0..1 over three events). */
    private fun stampEvent(out: Scene, w: Int, age: Float, ink: Ink) {
        if (age >= 1f) return
        val cx = 0.2f + 0.6f * hash(w * 13 + 7)
        val cy = 0.82f + 0.1f * hash(w * 13 + 8)
        val rot = -16f + 32f * hash(w * 13 + 9)
        val design = (hash(w * 13 + 10) * 3f).toInt()
        val f = age * 3f   // 0..1 within its own event window
        val land = if (f < 0.16f) f / 0.16f else 1f
        val drop = if (f < 0.16f) 1.5f - 0.5f * easeOutBack(land) else 1f
        val fade = (if (f < 0.16f) ease(land) else 1f) * (1f - ease(((age - 0.45f) / 0.55f).coerceIn(0f, 1f)))
        val c = Colors.alpha(if (design == 1) ink.alt else Colors.mix(ink.alt, ink.main, 0.25f * design), 0.8f * fade)
        // a little splash ring as it lands
        if (f in 0.12f..0.4f) { val k = (f - 0.12f) / 0.28f; out.circle(cx, cy, 0.05f + 0.06f * ease(k), Colors.alpha(ink.alt, 0.18f * (1f - k)), 0.002f) }
        when (design) {
            0 -> stampTelegram(out, cx, cy, rot, drop, c, ink.plate, fade)
            1 -> stampPostmark(out, cx, cy, rot, drop, c, ink.plate, fade)
            else -> stampUrgent(out, cx, cy, rot, drop, c, ink.plate, fade)
        }
    }

    /** The editor's pencil rings a headline word (drawn round, ticked, then rubbed out). */
    private fun pencil(out: Scene, w: Int, f: Float, words: FloatArray, ink: Ink) {
        val wi = (hash(w * 13 + 11) * words.size).toInt().coerceIn(0, words.size - 1)
        var x = 0.065f
        for (i in 0 until wi) x += words[i] + 0.021f
        if (x + words[wi] > 0.955f) return
        val cx = x + words[wi] / 2; val cy = 0.192f
        val draw = ease((f / 0.45f).coerceIn(0f, 1f))
        val fade = 1f - ease(((f - 0.75f) / 0.25f).coerceIn(0f, 1f))
        val c = Colors.alpha(Colors.mix(ink.plate, ink.alt, 0.85f), 0.85f * fade)
        val rx = words[wi] / 2 + 0.018f; val ry = 0.028f
        // the ring as short strokes (an oval traced by hand, slightly open)
        val n = 18
        val m = (n * draw).toInt()
        for (i in 0 until m) {
            val a0 = -2.2f + i * 6.6f / n; val a1 = -2.2f + (i + 1) * 6.6f / n
            val wob0 = 1f + 0.06f * sin(a0 * 3f + w); val wob1 = 1f + 0.06f * sin(a1 * 3f + w)
            out.line(cx + rx * cos(a0) * wob0, cy + ry * sin(a0) * wob0, cx + rx * cos(a1) * wob1, cy + ry * sin(a1) * wob1, 0.0035f, c)
        }
        if (f > 0.48f) {
            val k = ease(((f - 0.48f) / 0.12f).coerceIn(0f, 1f))
            val tx = cx + rx + 0.012f; val ty = cy - ry
            out.line(tx, ty, tx + 0.008f * k, ty + 0.01f * k, 0.004f, c)
            if (k > 0.6f) out.line(tx + 0.008f, ty + 0.01f, tx + 0.008f + 0.02f * (k - 0.6f) / 0.4f, ty + 0.01f - 0.026f * (k - 0.6f) / 0.4f, 0.004f, c)
        }
    }

    /** A coffee cup's ring appears on the page and dries away. */
    private fun coffee(out: Scene, w: Int, f: Float, ink: Ink) {
        val cx = 0.25f + 0.5f * hash(w * 13 + 12); val cy = 0.84f + 0.08f * hash(w * 13 + 13)
        val k = ease((f / 0.25f).coerceIn(0f, 1f)) * (1f - ease(((f - 0.6f) / 0.4f).coerceIn(0f, 1f)))
        if (k < 0.01f) return
        val c = Colors.mix(ink.plate, Colors.mix(ink.alt, ink.main, 0.4f), 0.2f * k)
        out.circle(cx, cy, 0.05f, c, 0.006f)
        out.arc(cx, cy, 0.047f, 30f + 40f * hash(w), 200f, 0.003f, Colors.mix(ink.plate, ink.main, 0.12f * k))
        out.circle(cx + 0.03f, cy + 0.035f, 0.004f, c)
    }

    /** Chads flicked out of the punch head, tumbling down the left margin. */
    private fun chads(out: Scene, w: Int, f: Float, ink: Ink) {
        for (j in 0 until 5) {
            val p = ((f - j * 0.06f) / 0.7f)
            if (p <= 0f || p >= 1f) continue
            val x = BX0 - 0.025f - 0.045f * ease(p) * (0.6f + 0.4f * hash(w * 5 + j))
            val y = rowY(2) + (-0.03f + 0.06f * hash(w * 5 + j + 1)) + 0.25f * p * p
            out.circle(x, y, 0.004f, Colors.mix(ink.plate, ink.main, 0.25f * (1f - p)))
        }
    }

    private fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }
    private fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1; val y = x - 1; return 1 + c3 * y * y * y + c1 * y * y }
    private fun hash(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777216f
    }

    private fun drawBox(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val plate = ink.plate; val main = ink.main
        val desk = Colors.mix(plate, main, 0.15f)
        val ix0 = BX0 + RULE_L; val ix1 = BX1 - RULE; val iy0 = BY0 + RULE; val iy1 = BY1 - RULE
        // the box interior (desk) with a faint hatch of light
        out.rect((ix0 + ix1) / 2, (iy0 + iy1) / 2, ix1 - ix0, iy1 - iy0, desk)
        // tape: paper band, its shadow, edges
        val ty0 = rowY(0) - 0.042f; val ty1 = rowY(5) + 0.042f
        out.rect((ix0 + ix1) / 2, (ty0 + ty1) / 2 + 0.008f, ix1 - ix0, ty1 - ty0, Colors.mix(plate, main, 0.16f))
        out.rect((ix0 + ix1) / 2, (ty0 + ty1) / 2, ix1 - ix0, ty1 - ty0, Colors.mix(plate, ink.alt, 0.035f))
        // its edges flutter a hair as it runs
        val ec = Colors.mix(plate, main, 0.2f)
        for (e in 0 until 2) {
            val ye = if (e == 0) ty0 else ty1
            var px = ix0; var py = ye
            for (i in 1..8) {
                val xx = ix0 + (ix1 - ix0) * i / 8
                val yy = ye + 0.0012f * sin(xx * 31f + t * (2.1f + e * 0.6f))
                out.line(px, py, xx, yy, 0.002f, ec, round = false)
                px = xx; py = yy
            }
        }
        // holes: tape coordinate u = x + SPEED·P·t (the tape runs right to left)
        val shift = SPEED * P * t
        val c0 = floor((ix0 + shift) / P).toInt() - 1
        val c1 = floor((ix1 + shift) / P).toInt() + 1
        for (c in c0..c1) {
            val cx = c * P - shift
            if (cx < ix0 - HOLE_R || cx > ix1 + HOLE_R) continue
            val k = ((c % CYC) + CYC) % CYC
            if (k != 0) out.circle(cx, rowY(2), SPROCKET_R, main)
            for (j in 0 until 5) {
                val on = if (k == 0) true else {
                    val b = (k - 1) * 5 + j
                    if (b >= SigilPayload.BITS) true else bits[b] != MASK[b]
                }
                if (on) out.circle(cx, rowY(ROWS[j]), HOLE_R, main)
            }
        }
        // guide rollers at both ends of the window (they hide the tape's ends)
        roller(out, ix0, WX0, iy0, iy1, ink, t)
        roller(out, WX1, ix1, iy0, iy1, ink, -t)
        // the box rule, thick on the punch side, with rivets
        out.rect(BX0 + RULE_L / 2, (BY0 + BY1) / 2, RULE_L, BY1 - BY0, main)
        out.rect(BX1 - RULE / 2, (BY0 + BY1) / 2, RULE, BY1 - BY0, main)
        out.rect((BX0 + BX1) / 2, BY0 + RULE / 2, BX1 - BX0, RULE, main)
        out.rect((BX0 + BX1) / 2, BY1 - RULE / 2, BX1 - BX0, RULE, main)
        for (k in 0 until 2) out.circle(BX0 + RULE_L / 2, BY0 + 0.045f + k * (BY1 - BY0 - 0.09f), 0.007f, Colors.mix(main, plate, 0.35f))
        // the punch flashes as a column is punched
        val ph = (SPEED * t) % 1f
        val flash = (1f - ph * 3f).coerceAtLeast(0f)
        if (flash > 0f) out.glow(BX0 + RULE_L + 0.004f, rowY(2), 0.035f, Colors.alpha(Colors.mix(plate, ink.alt, 0.5f), 0.35f * flash))
    }

    private fun roller(out: Scene, x0: Float, x1: Float, y0: Float, y1: Float, ink: Ink, t: Float) {
        val c = Colors.mix(ink.plate, ink.main, 0.13f)
        out.rect((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0, c)
        // knurling that turns with the tape
        val n = 7
        for (k in 0 until n) {
            val y = y0 + (y1 - y0) * (((k + (t * SPEED * 0.5f)) % n + n) % n) / n
            out.line(x0 + 0.004f, y, x1 - 0.004f, y, 0.0025f, Colors.mix(ink.plate, ink.main, 0.19f), round = false)
        }
    }

    /** A blackletter-ish masthead letter (shapes only); returns its width. */
    private fun blackletter(out: Scene, x: Float, y: Float, kind: Int, c: Int, plate: Int): Float {
        val h = 0.052f; val st = 0.012f
        fun stem(sx: Float, top: Float = y - h / 2, bot: Float = y + h / 2) {
            out.rect(sx, (top + bot) / 2, st, bot - top, c)
            out.poly(floatArrayOf(sx - st * 0.75f, top + st * 0.2f, sx, top - st * 0.55f, sx + st * 0.75f, top + st * 0.2f, sx, top + st * 0.9f), c)
            out.poly(floatArrayOf(sx - st * 0.75f, bot - st * 0.2f, sx, bot - st * 0.9f, sx + st * 0.75f, bot - st * 0.2f, sx, bot + st * 0.55f), c)
        }
        return when (kind) {
            0 -> { stem(x + 0.008f); out.line(x - 0.004f, y - h / 2, x + 0.04f, y - h / 2 - 0.006f, 0.008f, c); out.line(x + 0.04f, y - h / 2 - 0.006f, x + 0.034f, y - h / 2 + 0.01f, 0.006f, c); 0.04f }   // T
            1 -> { stem(x + 0.007f); stem(x + 0.031f); out.line(x + 0.007f, y - 0.004f, x + 0.031f, y - 0.01f, 0.007f, c); 0.038f }                                                          // H / N
            2 -> { stem(x + 0.007f, y - h / 2 - 0.006f); out.line(x + 0.007f, y + h / 2 - 0.004f, x + 0.03f, y + h / 2 - 0.012f, 0.008f, c); out.line(x + 0.007f, y - 0.002f, x + 0.026f, y - 0.008f, 0.006f, c); 0.033f } // E / L
            3 -> { out.oval(x + 0.019f, y, 0.017f, h / 2, c, rot = -18f, stroke = 0.011f); 0.04f }                                                                                         // O
            else -> { stem(x + 0.007f); 0.017f }                                                                                                                                         // I
        }
    }

    /** A telegraph pole with wires, between the masthead words (the insulators blink in turn). */
    private fun emblem(out: Scene, x: Float, y: Float, c: Int, plate: Int, t: Float) {
        out.line(x, y - 0.03f, x, y + 0.032f, 0.006f, c)
        out.line(x - 0.022f, y - 0.018f, x + 0.022f, y - 0.018f, 0.005f, c)
        out.line(x - 0.016f, y - 0.004f, x + 0.016f, y - 0.004f, 0.004f, c)
        for (k in 0 until 3) {
            val on = ((t * 2.2f).toInt() % 3) == k
            out.circle(x - 0.018f + k * 0.018f, y - 0.023f, if (on) 0.0045f else 0.0032f, c)
        }
        val sag = 0.004f * sin(t * 1.3f)
        out.line(x - 0.035f, y - 0.025f + sag, x - 0.018f, y - 0.023f, 0.0015f, c)
        out.line(x + 0.018f, y - 0.023f, x + 0.035f, y - 0.026f - sag, 0.0015f, c)
    }

    private fun headline(out: Scene, x0: Float, y: Float, words: FloatArray, h: Float, c: Int) {
        var x = x0
        for (w in words) {
            if (x + w > 0.955f) break
            out.rect(x + w / 2, y, w, h, c, corner = h * 0.15f)
            x += w + h * 0.7f
        }
    }

    /** A cut piece of tape curling on the page: a ribbon coiling inward (two turns), its holes riding along. */
    private fun curl(out: Scene, cx: Float, cy: Float, t: Float, ink: Ink) {
        val front = Colors.mix(ink.plate, ink.main, 0.05f)
        val back = Colors.mix(ink.plate, ink.main, 0.14f)
        val edge = Colors.mix(ink.plate, ink.main, 0.32f)
        val hole = Colors.mix(ink.plate, ink.main, 0.4f)
        val sway = 0.12f * sin(t * 0.8f)
        val n = 30
        // the free end runs out to the right, the coil winds in
        fun at(f: Float): FloatArray {
            val a = -0.3f + f * 10.5f + sway * f
            val r = 0.085f * (1f - 0.78f * f)
            return floatArrayOf(cx + cos(a) * r, cy + sin(a) * r * 0.55f, a)
        }
        for (i in 0 until n - 1) {
            val f0 = i / (n - 1f); val f1 = (i + 1) / (n - 1f)
            val p0 = at(f0); val p1 = at(f1)
            val w0 = 0.017f * (1f - 0.5f * f0); val w1 = 0.017f * (1f - 0.5f * f1)
            // the ribbon's width shows when it faces us, it narrows to an edge when it turns
            val k0 = 0.35f + 0.65f * abs(sin(p0[2])); val k1 = 0.35f + 0.65f * abs(sin(p1[2]))
            val col = if (sin(p0[2]) > 0f) front else back
            out.poly(floatArrayOf(p0[0], p0[1] - w0 * k0, p1[0], p1[1] - w1 * k1, p1[0], p1[1] + w1 * k1, p0[0], p0[1] + w0 * k0), col)
            out.line(p0[0], p0[1] - w0 * k0, p1[0], p1[1] - w1 * k1, 0.0018f, edge)
            out.line(p0[0], p0[1] + w0 * k0, p1[0], p1[1] + w1 * k1, 0.0018f, edge)
            if (i % 2 == 0 && sin(p0[2]) > 0.3f) {
                out.circle(p0[0], p0[1] - w0 * k0 * 0.45f, 0.0035f * (1f - 0.4f * f0), hole)
                if (i % 4 == 0) out.circle(p0[0], p0[1] + w0 * k0 * 0.4f, 0.0042f * (1f - 0.4f * f0), hole)
            }
        }
    }

    private fun rot(cx: Float, cy: Float, rot: Float): (Float, Float) -> FloatArray {
        val ca = cos(rot * PI.toFloat() / 180f); val sa = sin(rot * PI.toFloat() / 180f)
        return { u, v -> floatArrayOf(cx + u * ca - v * sa, cy + u * sa + v * ca) }
    }

    /** TELEGRAM: a double frame of worn ink with letter blocks. */
    private fun stampTelegram(out: Scene, cx: Float, cy: Float, rot: Float, s: Float, c: Int, plate: Int, fade: Float) {
        val w = 0.23f * s; val h = 0.095f * s
        val p = rot(cx, cy, rot)
        out.rect(cx, cy, w, h, c, rot = rot, corner = 0.008f, stroke = 0.0065f * s)
        out.rect(cx, cy, w - 0.02f * s, h - 0.02f * s, c, rot = rot, corner = 0.005f, stroke = 0.0022f * s)
        for (k in 0 until 8) {
            val u = (-0.5f + (k + 0.5f) / 8) * (w - 0.05f * s)
            val q = p(u, 0f)
            out.rect(q[0], q[1], 0.017f * s, 0.036f * s, c, rot = rot)
            val n = p(u + 0.003f * s, ((k * 5) % 3 - 1) * 0.008f * s)
            out.rect(n[0], n[1], 0.008f * s, 0.007f * s, Colors.alpha(plate, fade), rot = rot)
        }
        for (k in 0 until 5) { val q = p((-0.45f + k * 0.22f) * w, (if (k % 2 == 0) -0.5f else 0.5f) * h); out.circle(q[0], q[1], 0.0035f * s, Colors.alpha(plate, 0.9f * fade)) }
    }

    /** A round postmark: two rings, a date bar and wavy cancellation lines. */
    private fun stampPostmark(out: Scene, cx: Float, cy: Float, rot: Float, s: Float, c: Int, plate: Int, fade: Float) {
        val r = 0.045f * s
        val p = rot(cx, cy, rot)
        out.circle(cx, cy, r, c, 0.005f * s)
        out.circle(cx, cy, r * 0.72f, c, 0.0022f * s)
        val d = p(0f, 0f); out.rect(d[0], d[1], r * 1.1f, 0.012f * s, c, rot = rot)
        for (k in 0 until 3) {
            val v = (k - 1) * 0.016f * s
            var prev = p(r * 1.15f, v)
            for (i in 1..6) {
                val u = r * 1.15f + i * 0.02f * s
                val q = p(u, v + 0.006f * s * sin(i * 1.7f))
                out.line(prev[0], prev[1], q[0], q[1], 0.003f * s, c)
                prev = q
            }
        }
        val n = p(-r * 0.3f, r * 0.85f); out.circle(n[0], n[1], 0.004f * s, Colors.alpha(plate, 0.9f * fade))
    }

    /** URGENT: a slanted bar box with a star. */
    private fun stampUrgent(out: Scene, cx: Float, cy: Float, rot: Float, s: Float, c: Int, plate: Int, fade: Float) {
        val w = 0.17f * s; val h = 0.06f * s
        val p = rot(cx, cy, rot)
        out.rect(cx, cy, w, h, c, rot = rot, corner = 0.004f, stroke = 0.006f * s)
        val st = p(-w / 2 + 0.022f * s, 0f)
        val pts = FloatArray(20)
        for (i in 0 until 10) {
            val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5 + rot * PI.toFloat() / 180f
            val rr = if (i % 2 == 0) 0.016f * s else 0.007f * s
            pts[2 * i] = st[0] + rr * cos(a); pts[2 * i + 1] = st[1] + rr * sin(a)
        }
        out.poly(pts, c)
        for (k in 0 until 5) {
            val q = p(-w / 2 + 0.05f * s + k * 0.024f * s, 0f)
            out.rect(q[0], q[1], 0.014f * s, 0.03f * s, c, rot = rot)
        }
        val n = p(w * 0.3f, -h / 2); out.circle(n[0], n[1], 0.004f * s, Colors.alpha(plate, 0.9f * fade))
    }

    override fun reader(): SchemeReader = TapeReader()
}

// ====================================================================== reader


private class TapeReader : SchemeReader {
    override var seen = false
        private set
    private var pol = true
    private val acc = FloatArray(PunchedTape.CYC * 5)   // per data column k (1..17) × track
    private var lastOk = Long.MIN_VALUE / 2

    override fun feed(f: SigilFrame): Read? {
        seen = false
        val half = THalf(f)
        var g: TGeo? = null
        for (k in 0 until 2) {
            val p = if (k == 0) pol else !pol
            g = tLocate(f, p, half)
            if (g != null) { pol = p; break }
        }
        if (g == null) {
            if (f.tMs - lastOk > 2000) acc.fill(0f)
            return null
        }
        seen = true
        lastOk = f.tMs
        val cols = tRead(f, g, if (pol) 1f else -1f) ?: return null
        // add this frame's votes
        for (i in acc.indices) acc[i] *= 0.85f
        for ((k, v) in cols) for (j in 0 until 5) acc[k * 5 + j] += v[j]
        val bits = BooleanArray(SigilPayload.BITS)
        val sure = FloatArray(SigilPayload.BITS)
        for (b in 0 until SigilPayload.BITS) {
            val k = b / 5 + 1; val j = b % 5
            val a = acc[k * 5 + j]
            bits[b] = (a > 0f) != PunchedTape.MASK[b]
            sure[b] = abs(a)
        }
        // every column must have been seen
        for (k in 1 until PunchedTape.CYC) { var s = 0f; for (j in 0 until 5) s += abs(acc[k * 5 + j]); if (s == 0f) return null }
        return SigilPayload.decodeBits(bits, sure)
    }
}

/** A sighting: homography unit square → camera pixels (already oriented: tape left to right, track 1 on top). */
internal class TGeo(val h: DoubleArray)

internal fun tLocate(f: SigilFrame, darkMarks: Boolean, half: THalf = THalf(f)): TGeo? {
    val mk = half.marks(darkMarks, 31, 9f, keepRuns = true)
    // the box rule: a big, hollow mark
    val order = (0 until mk.n).filter {
        val bw = mk.bw(it); val bh = mk.bh(it)
        mk.area[it] > 1500 && max(bw, bh) > 150
    }.sortedByDescending { mk.area[it] }
    val sgn = if (darkMarks) 1f else -1f
    for (id in order.take(4)) {
        val quad = tQuad(mk, id) ?: continue
        val corners = tRefine(f, quad, sgn)
        if (corners == null) continue
        val g = tOrient(f, corners, sgn) ?: continue
        return g
    }
    return null
}

/** Four hull corners (x0,y0 … x3,y3, in hull order) of mark [id]. */
private fun tQuad(mk: TMarks, id: Int): FloatArray? {
    // the mark's outline: its leftmost and rightmost pixel in every row (rows come in order)
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
    var curY = -1; var lo = 0; var hi = 0
    for (q in 0 until mk.nRuns) {
        if (mk.runId[q] != id) continue
        val y = mk.runY[q]
        if (y != curY) {
            if (curY >= 0) { xs.add(lo * 2f); ys.add(curY * 2f + 1f); xs.add(hi * 2f + 2f); ys.add(curY * 2f + 1f) }
            curY = y; lo = mk.runS[q]; hi = mk.runE[q]
        } else hi = max(hi, mk.runE[q])
    }
    if (curY >= 0) { xs.add(lo * 2f); ys.add(curY * 2f + 1f); xs.add(hi * 2f + 2f); ys.add(curY * 2f + 1f) }
    if (xs.size < 20) return null
    val hull = convexHull(xs, ys)
    if (hull.size < 8) return null
    val hx = FloatArray(hull.size / 2) { hull[2 * it] }; val hy = FloatArray(hull.size / 2) { hull[2 * it + 1] }
    val n = hx.size
    var mx = 0f; var my = 0f
    for (i in 0 until n) { mx += hx[i]; my += hy[i] }
    mx /= n; my /= n
    // c1: farthest from centroid; c3: farthest from c1; c2, c4: farthest from the c1–c3 line on each side
    var i1 = 0; var best = -1f
    for (i in 0 until n) { val d = (hx[i] - mx) * (hx[i] - mx) + (hy[i] - my) * (hy[i] - my); if (d > best) { best = d; i1 = i } }
    var i3 = 0; best = -1f
    for (i in 0 until n) { val d = (hx[i] - hx[i1]) * (hx[i] - hx[i1]) + (hy[i] - hy[i1]) * (hy[i] - hy[i1]); if (d > best) { best = d; i3 = i } }
    val lx = hx[i3] - hx[i1]; val ly = hy[i3] - hy[i1]
    var i2 = -1; var i4 = -1; var bp = 0f; var bn = 0f
    for (i in 0 until n) {
        val c = (hx[i] - hx[i1]) * ly - (hy[i] - hy[i1]) * lx
        if (c > bp) { bp = c; i2 = i }
        if (c < bn) { bn = c; i4 = i }
    }
    if (i2 < 0 || i4 < 0) return null
    // hull order: walk from i1
    val idx = intArrayOf(i1, i2, i3, i4).sortedBy { ((it - i1) % n + n) % n }
    return FloatArray(8) { k -> if (k % 2 == 0) hx[idx[k / 2]] else hy[idx[k / 2]] }
}

/** Convex hull (x0, y0, x1, y1 …) of points already sorted by (y, x): monotone chain. */
private fun convexHull(xs: List<Float>, ys: List<Float>): FloatArray {
    val n = xs.size
    val h = IntArray(2 * n + 2); var k = 0
    fun cross(a: Int, b: Int, c: Int) = (xs[b] - xs[a]) * (ys[c] - ys[a]) - (ys[b] - ys[a]) * (xs[c] - xs[a])
    for (i in 0 until n) { while (k >= 2 && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
    val lo = k + 1
    for (i in n - 2 downTo 0) { while (k >= lo && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
    k--
    return FloatArray(2 * max(k, 0)) { j -> if (j % 2 == 0) xs[h[j / 2]] else ys[h[j / 2]] }
}

/** Refines the box's outer edges on the luminance (half-contrast crossing) and intersects them. */
private fun tRefine(f: SigilFrame, q: FloatArray, sgn: Float): FloatArray? {
    var cx = 0f; var cy = 0f
    for (k in 0 until 4) { cx += q[2 * k]; cy += q[2 * k + 1] }
    cx /= 4; cy /= 4
    val lines = FloatArray(12)   // a, b, c per edge (a x + b y = c, (a, b) unit outward normal)
    val ex = FloatArray(10); val ey = FloatArray(10)
    for (e in 0 until 4) {
        val x0 = q[2 * e]; val y0 = q[2 * e + 1]; val x1 = q[(2 * e + 2) % 8]; val y1 = q[(2 * e + 3) % 8]
        val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
        if (len < 20f) return null
        var nx = (y1 - y0) / len; var ny = -(x1 - x0) / len
        if ((x0 + x1) / 2 * nx + (y0 + y1) / 2 * ny < cx * nx + cy * ny) { nx = -nx; ny = -ny }
        var m = 0
        for (s in 0 until 10) {
            val fr = 0.12f + 0.76f * s / 9f
            val px = x0 + (x1 - x0) * fr; val py = y0 + (y1 - y0) * fr
            // profile along the normal: inside (rule) → outside (page)
            var inside = Float.MAX_VALUE; var outside = 0f
            for (d in -6..-2) { val v = sgn * f.at(px + nx * d, py + ny * d); inside = min(inside, v) }
            for (d in 5..8) outside += sgn * f.at(px + nx * d, py + ny * d)
            outside /= 4
            if (outside - inside < 15f) continue
            val halfv = (inside + outside) / 2
            var hit = Float.NaN
            var d = -5f
            var prev = sgn * f.at(px + nx * d, py + ny * d)
            while (d < 7f) {
                val dn = d + 0.5f
                val v = sgn * f.at(px + nx * dn, py + ny * dn)
                if (prev <= halfv && v > halfv) { hit = d + 0.5f * (halfv - prev) / (v - prev); break }
                prev = v; d = dn
            }
            if (hit.isNaN()) continue
            ex[m] = px + nx * hit; ey[m] = py + ny * hit; m++
        }
        if (m < 5) return null
        // least-squares line through the points (total least squares)
        var mx = 0f; var my = 0f
        for (i in 0 until m) { mx += ex[i]; my += ey[i] }
        mx /= m; my /= m
        var sxx = 0f; var sxy = 0f; var syy = 0f
        for (i in 0 until m) { val dx = ex[i] - mx; val dy = ey[i] - my; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
        val ang = 0.5f * atan2(2 * sxy, sxx - syy)
        var a = -sin(ang); var b = cos(ang)
        if (a * nx + b * ny < 0) { a = -a; b = -b }
        lines[3 * e] = a; lines[3 * e + 1] = b; lines[3 * e + 2] = a * mx + b * my
    }
    val out = FloatArray(8)
    for (k in 0 until 4) {
        // corner k between edge k-1 and edge k
        val e1 = (k + 3) % 4; val e2 = k
        val a1 = lines[3 * e1]; val b1 = lines[3 * e1 + 1]; val c1 = lines[3 * e1 + 2]
        val a2 = lines[3 * e2]; val b2 = lines[3 * e2 + 1]; val c2 = lines[3 * e2 + 2]
        val det = a1 * b2 - a2 * b1
        if (abs(det) < 0.2f) return null
        out[2 * k] = (c1 * b2 - c2 * b1) / det; out[2 * k + 1] = (a1 * c2 - a2 * c1) / det
    }
    return out
}

/**
 * The box's corners → the homography, oriented: the long sides run along the tape, the thick rule is on the left,
 * the sprocket row (nearly every column punched) lies between tracks 2 and 3.
 */
private fun tOrient(f: SigilFrame, c: FloatArray, sgn: Float): TGeo? {
    fun d(i: Int, j: Int) = sqrt((c[2 * i] - c[2 * j]) * (c[2 * i] - c[2 * j]) + (c[2 * i + 1] - c[2 * j + 1]) * (c[2 * i + 1] - c[2 * j + 1]))
    val l01 = d(0, 1) + d(2, 3); val l12 = d(1, 2) + d(3, 0)
    val want = (PunchedTape.BX1 - PunchedTape.BX0) / (PunchedTape.BY1 - PunchedTape.BY0)
    val ratio = max(l01, l12) / min(l01, l12)
    if (ratio < 0.55f * want || ratio > 1.7f * want) return null
    val start = if (l01 > l12) 0 else 1   // corner index where a long side starts
    val unit = floatArrayOf(PunchedTape.BX0, PunchedTape.BY0, PunchedTape.BX1, PunchedTape.BY0, PunchedTape.BX1, PunchedTape.BY1, PunchedTape.BX0, PunchedTape.BY1)
    var best: TGeo? = null; var bestScore = 0f; var second = 0f
    for (rot in intArrayOf(start, start + 2)) for (mir in 0 until 2) {
        val dst = FloatArray(8)
        for (k in 0 until 4) {
            val ci = if (mir == 0) (rot + k) % 4 else ((rot + 1 - k) % 4 + 4) % 4
            dst[2 * k] = c[2 * ci]; dst[2 * k + 1] = c[2 * ci + 1]
        }
        val h = Fit.homography(unit, dst) ?: continue
        val p = FloatArray(2)
        fun lum(u: Float, v: Float): Float { Fit.apply(h, u, v, p); return f.at(p[0], p[1]) }
        // thick rule on the left: inside it is ink, at the mirrored spot on the right it is not
        val ym = (PunchedTape.BY0 + PunchedTape.BY1) / 2
        var left = 0f; var right = 0f
        for (v in floatArrayOf(ym - 0.12f, ym - 0.05f, ym + 0.05f, ym + 0.12f)) {
            left += lum(PunchedTape.BX0 + PunchedTape.RULE_L * 0.72f, v)
            right += lum(PunchedTape.BX1 - PunchedTape.RULE_L * 0.72f, v)
        }
        val ruleScore = sgn * (right - left) / 4
        if (ruleScore < 15f) continue
        // sprocket row: the track at r = 2 is punched in (nearly) every column, the one at r = 3 is random
        val regs = tRowRegularity(f, h, sgn) ?: continue
        val r2 = regs[2]; val r3 = regs[3]
        val score = if (r2 >= 0.75f) r2 - r3 else 0f
        if (score > bestScore) { second = bestScore; bestScore = score; best = TGeo(h) } else if (score > second) second = score
    }
    if (best == null || bestScore < 0.06f) return null
    return best
}

/** Share of the window's columns punched in each of the six rows (row 2 = sprocket row if the orientation is right). */
private fun tRowRegularity(f: SigilFrame, h: DoubleArray, sgn: Float): FloatArray? {
    val ph = tPhase(f, h, sgn) ?: return null
    val p = FloatArray(2)
    fun lum(u: Float, v: Float): Float { Fit.apply(h, u, v, p); return f.at(p[0], p[1]) }
    val P = PunchedTape.P
    val ds = ArrayList<FloatArray>()
    var x = ph
    while (x <= PunchedTape.WX1 - 0.45f * P) {
        if (x >= PunchedTape.WX0 + 0.45f * P) ds.add(FloatArray(6) { rr ->
            val y = PunchedTape.rowY(rr)
            sgn * ((lum(x - P / 2, y) + lum(x + P / 2, y)) / 2 - lum(x, y))
        })
        x += P
    }
    if (ds.size < 8) return null
    val all = FloatArray(ds.size * 6) { ds[it / 6][it % 6] }
    all.sort()
    val level = all[(all.size * 0.85f).toInt()]
    if (level < 10f) return null
    return FloatArray(6) { r -> ds.count { it[r] > 0.3f * level }.toFloat() / ds.size }
}

/** x (unit) of the first column at or right of WX0 − P, from the summed darkness of all six rows. */
private fun tPhase(f: SigilFrame, h: DoubleArray, sgn: Float): Float? {
    val p = FloatArray(2)
    val steps = 26
    val P = PunchedTape.P
    var bestS = 0f; var bestK = 0
    val score = FloatArray(steps)
    for (k in 0 until steps) {
        val off = k * P / steps
        var s = 0f
        var x = PunchedTape.WX0 + off
        while (x < PunchedTape.WX1) {
            for (r in 0 until 6) {
                Fit.apply(h, x, PunchedTape.rowY(r), p); val a = f.at(p[0], p[1])
                Fit.apply(h, x + P / 2, PunchedTape.rowY(r), p); val b = f.at(p[0], p[1])
                s += sgn * (b - a)
            }
            x += P
        }
        score[k] = s
        if (s > bestS) { bestS = s; bestK = k }
    }
    if (bestS <= 0f) return null
    // parabolic refinement (circular)
    val a = score[(bestK + steps - 1) % steps]; val b = score[bestK]; val c = score[(bestK + 1) % steps]
    var fk = bestK.toFloat()
    val den = a - 2 * b + c
    if (den < 0f) fk += (0.5f * (a - c) / den).coerceIn(-0.5f, 0.5f)
    return PunchedTape.WX0 + fk * P / steps
}

/** Votes (data column index 1..17 → five soft track values, positive = punched) from one oriented sighting. */
private fun tRead(f: SigilFrame, g: TGeo, sgn: Float): List<Pair<Int, FloatArray>>? {
    val h = g.h
    val ph = tPhase(f, h, sgn) ?: return null
    val p = FloatArray(2)
    fun lum(u: Float, v: Float): Float { Fit.apply(h, u, v, p); return f.at(p[0], p[1]) }
    fun disc(u: Float, v: Float, r: Float): Float {
        var s = lum(u, v)
        for (k in 0 until 6) { val a = k * PI.toFloat() / 3; s += lum(u + r * cos(a), v + r * sin(a)) }
        return s / 7
    }
    val P = PunchedTape.P
    // columns fully in the window
    val xs = ArrayList<Float>()
    var x = ph
    while (x <= PunchedTape.WX1 - 0.45f * P) { if (x >= PunchedTape.WX0 + 0.45f * P) xs.add(x); x += P }
    if (xs.size < 10) return null
    val n = xs.size
    // darkness of each hole place against the paper beside it (same track, half a pitch either side)
    val dark = Array(n) { FloatArray(6) }
    for (i in 0 until n) for (r in 0 until 6) {
        val y = PunchedTape.rowY(r)
        val paper = (lum(xs[i] - P / 2, y) + lum(xs[i] + P / 2, y)) / 2
        val rr = if (r == 2) 0.35f * PunchedTape.SPROCKET_R else 0.35f * PunchedTape.HOLE_R
        dark[i][r] = sgn * (paper - disc(xs[i], y, rr))
    }
    // the sprocket reference and the sync column (the one sprocket missing)
    val sp = FloatArray(n) { dark[it][2] }
    val srt = sp.copyOf(); srt.sort()
    val spRef = srt[(n * 0.6f).toInt()]
    if (spRef < 8f) return null
    var sync = -1; var nMiss = 0
    for (i in 0 until n) if (sp[i] < 0.35f * spRef) { nMiss++; sync = i }
    if (nMiss != 1) return null
    // the sync column has all five holes punched: it is the "hole" reference; plain paper is 0
    var holeRef = 0f
    for (j in 0 until 5) holeRef += dark[sync][PunchedTape.ROWS[j]]
    holeRef /= 5
    if (holeRef < 0.6f * spRef) return null
    val thr = 0.5f * holeRef
    val out = ArrayList<Pair<Int, FloatArray>>()
    for (i in 0 until n) {
        if (i == sync) continue
        val k = ((i - sync) % PunchedTape.CYC + PunchedTape.CYC) % PunchedTape.CYC
        if (k == 0) continue
        val v = FloatArray(5) { j -> ((dark[i][PunchedTape.ROWS[j]] - thr) / thr).coerceIn(-1f, 1f) }
        out.add(k to v)
    }
    return out
}

// ---------------------------------------------------------------------- fast marks (connected components)

internal class TMarks(cap: Int) {
    var n = 0
    var cx = FloatArray(cap); var cy = FloatArray(cap); var area = IntArray(cap); var lum = FloatArray(cap)
    var x0 = IntArray(cap); var y0 = IntArray(cap); var x1 = IntArray(cap); var y1 = IntArray(cap)
    fun bw(i: Int) = x1[i] - x0[i] + 1
    fun bh(i: Int) = y1[i] - y0[i] + 1
    // the runs (half-res) and the mark each belongs to (−1: dropped)
    var nRuns = 0
    var runY = IntArray(0); var runS = IntArray(0); var runE = IntArray(0); var runId = IntArray(0)
}

/**
 * The frame at half resolution with its integral image; [marks] finds the pixels darker (or lighter) than the mean of
 * a window round them by more than a margin, joined into 8-connected components (flat noisy areas give nothing).
 */
internal class THalf(val f: SigilFrame) {
    val hw = f.w / 2; val hh = f.h / 2
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

    fun marks(darkMarks: Boolean, win: Int, delta: Float, maxN: Int = 3000, keepRuns: Boolean = false): TMarks {
        val rs = sweep(win, delta, darkMarks)
        val runY = rs.y; val runS = rs.s; val runE = rs.e; val rowStart = rs.rowStart; val nr = rs.n
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
        val id = IntArray(nr) { -1 }
        val m = TMarks(min(maxN, nr) + 1)
        val sx = DoubleArray(m.cx.size); val sy = DoubleArray(m.cx.size); val sl = DoubleArray(m.cx.size)
        val runId = if (keepRuns) IntArray(nr) else IntArray(0)
        for (q in 0 until nr) {
            val r = find(q)
            var k = id[r]
            if (k < 0) {
                if (m.n >= maxN) { if (keepRuns) runId[q] = -1; continue }
                k = m.n++; id[r] = k
                m.x0[k] = Int.MAX_VALUE; m.y0[k] = Int.MAX_VALUE; m.x1[k] = -1; m.y1[k] = -1
            }
            if (keepRuns) runId[q] = k
            val y = runY[q]; val a = runS[q]; val b = runE[q]; val len = b - a + 1
            m.area[k] += len
            sx[k] += (a + b) * 0.5 * len; sy[k] += y.toDouble() * len
            var s = 0L; for (x in a..b) s += img[y * hw + x]; sl[k] += s.toDouble()
            if (a < m.x0[k]) m.x0[k] = a; if (b > m.x1[k]) m.x1[k] = b
            if (y < m.y0[k]) m.y0[k] = y; if (y > m.y1[k]) m.y1[k] = y
        }
        for (k in 0 until m.n) {
            val a = m.area[k]
            m.cx[k] = (sx[k] / a).toFloat() * 2f + 0.5f; m.cy[k] = (sy[k] / a).toFloat() * 2f + 0.5f
            m.lum[k] = (sl[k] / a / 4.0).toFloat()
            m.area[k] = a * 4
            m.x0[k] *= 2; m.y0[k] *= 2; m.x1[k] = m.x1[k] * 2 + 1; m.y1[k] = m.y1[k] * 2 + 1
        }
        if (keepRuns) { m.nRuns = nr; m.runY = runY; m.runS = runS; m.runE = runE; m.runId = runId }
        return m
    }
}
