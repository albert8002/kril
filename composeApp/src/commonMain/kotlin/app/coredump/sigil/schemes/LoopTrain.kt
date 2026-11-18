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
 * METRO: a loop line (two rails round a rounded square) with a 14-carriage train running round it, stations at the
 * corners, a signal, and a split-flap departure board in the middle announcing the next stop.
 *
 * Each carriage has 3 × 2 windows, lit or dark: one payload symbol per carriage. Only the locomotive lights the strip
 * between the two rows of windows (a long windscreen, then a short cab lamp), which gives where the train starts and
 * which way it runs (that also undoes a mirrored camera).
 *
 * Reader: the inner rail (always fully in view) is found as a ring, its corners give a first homography; both rails'
 * visible edges refine it (Gauss–Newton); the strip between the window rows is unrolled round the loop and matched
 * against the locomotive's light pattern; then every window is sampled against the local carriage body (dark) and
 * the rails (lit), and soft votes are kept over the last frames, aligned on the locomotive.
 */
object LoopTrain : SigilScheme {
    override val name = "train"

    // ------------------------------------------------------------------ layout (unit square, centred on 0.5)
    internal const val A = 0.335f            // track centre line, half-size
    internal const val RC = 0.1f             // its corner radius
    internal const val RO = 0.03f            // window rows: ± this from the centre line
    internal const val WA = 0.03f            // window length along the track (on the centre line)
    internal const val WC = 0.032f           // window height across the track
    internal const val P = 0.045f            // window pitch
    internal const val END = 0.008f          // carriage end margin
    internal const val COUP = 0.012f         // coupling gap
    internal const val LOCO = 0.1f           // locomotive length
    internal const val CL = 3 * P + 2 * END  // carriage length
    internal const val BODY = 0.054f         // carriage half-width
    internal const val OR0 = A + 0.07f       // outer rail, inner edge
    internal const val OR1 = A + 0.092f      // outer rail, outer edge
    internal const val IR0 = A - 0.09f       // inner rail, inner edge (towards the middle)
    internal const val IR1 = A - 0.066f      // inner rail, outer edge
    internal const val SPEED = 0.055f        // units per second along the track
    internal val LEN = 8 * A - 8 * RC + 2 * PI.toFloat() * RC
    internal val TRAIN = LOCO + 14 * (COUP + CL)

    /** Distance behind the nose of window (carriage k, column c) centres. */
    internal fun windowX(k: Int, c: Int) = LOCO + COUP + k * (CL + COUP) + END + (c + 0.5f) * P

    internal val MASK: BooleanArray = run {
        var s = 0x7A1B3C5D
        BooleanArray(SigilPayload.BITS) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            (s ushr 9) and 1 == 1
        }
    }

    /** Point on a rounded square round (0.5, 0.5) at arc length [s0], clockwise from the top side's left end: x, y, tx, ty. */
    internal fun pathAt(a: Float, rc: Float, s0: Float, out: FloatArray) {
        val st = 2 * (a - rc); val ar = HALF_PI * rc
        val per = 4 * (st + ar)
        var s = s0 - per * floor(s0 / per)
        for (side in 0 until 4) {
            val dx = when (side) { 0 -> 1f; 2 -> -1f; else -> 0f }
            val dy = when (side) { 1 -> 1f; 3 -> -1f; else -> 0f }
            val sx = when (side) { 0 -> -a + rc; 1 -> a; 2 -> a - rc; else -> -a }
            val sy = when (side) { 0 -> -a; 1 -> -a + rc; 2 -> a; else -> a - rc }
            if (s < st) { out[0] = 0.5f + sx + dx * s; out[1] = 0.5f + sy + dy * s; out[2] = dx; out[3] = dy; return }
            s -= st
            if (s < ar || side == 3) {
                val ccx = sx + dx * st - dy * rc
                val ccy = sy + dy * st + dx * rc
                // angle within the quarter turn (0..π/2), from a table: cos / sin of start + v
                val v = (min(s, ar) / ar).coerceIn(0f, 1f) * QN
                val i = min(QN - 1, v.toInt()); val f = v - i
                val cv = QCOS[i] + (QCOS[i + 1] - QCOS[i]) * f
                val sv = QSIN[i] + (QSIN[i + 1] - QSIN[i]) * f
                // start angle side·π/2 - π/2: rotate (cv, sv) by it
                val c0 = when (side) { 0 -> 0f; 1 -> 1f; 2 -> 0f; else -> -1f }
                val s0a = when (side) { 0 -> -1f; 1 -> 0f; 2 -> 1f; else -> 0f }
                val cp = c0 * cv - s0a * sv
                val sp = s0a * cv + c0 * sv
                out[0] = 0.5f + ccx + rc * cp; out[1] = 0.5f + ccy + rc * sp; out[2] = -sp; out[3] = cp
                return
            }
            s -= ar
        }
    }

    private const val HALF_PI = (PI / 2).toFloat()
    private const val QN = 128
    private val QCOS = FloatArray(QN + 1) { cos(it * HALF_PI / QN) }
    private val QSIN = FloatArray(QN + 1) { sin(it * HALF_PI / QN) }

    /** Where the nose is at time t (arc length on the centre line); the train runs clockwise (+s). */
    internal fun nose(t: Float): Float = (0.37f + SPEED * t) % LEN

    // ------------------------------------------------------------------ colours

    private class Pal(val ink: Ink) {
        val lp = Colors.light(ink.plate)
        val lm = Colors.light(ink.main)
        fun lv(f: Float) = lp + (lm - lp) * f
        val dark = lp < 0.5f
        val lineHue = hueOf(ink.alt, 44f)
        val rail = trainTone(lineHue, 0.85f, lv(0.85f))
        val lit = trainTone(if (dark) 44f else 222f, if (dark) 0.3f else 0.55f, lv(1.0f))
        val lit2 = trainTone(if (dark) 30f else 205f, if (dark) 0.45f else 0.7f, lv(1.0f))
        val railHi = trainTone(lineHue, 0.35f, lv(1.0f))
        val map1 = trainTone(lineHue + 160f, 0.25f, Colors.light(ink.faint))
        val water = trainTone(205f, 0.45f, Colors.light(ink.faint))
        val park = trainTone(130f, 0.35f, Colors.light(ink.faint))
        val body = trainTone(215f, 0.08f, Colors.light(ink.soft))
        val body2 = trainTone(lineHue, 0.25f, Colors.light(ink.soft))
        val bed = Colors.mix(ink.plate, if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.18f)
        val tile = trainTone(220f, 0.1f, lv(0.16f))
        val letter = trainTone(46f, 0.9f, lv(0.95f))
        val station = trainTone(0f, 0f, lv(1.0f))
        val red = trainTone(2f, 0.85f, lv(0.6f))
        val green = trainTone(140f, 0.8f, lv(0.6f))
    }

    private var palInk: Ink? = null
    private var palVal: Pal? = null
    private fun pal(ink: Ink): Pal {
        val p = palVal
        if (p != null && palInk === ink) return p
        return Pal(ink).also { palInk = ink; palVal = it }
    }

    // ------------------------------------------------------------------ drawing

    private val STATIONS = arrayOf("CORE", "BYTE", "HEAP", "ROOT")

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val p = pal(ink)
        val q = FloatArray(4)
        val sn = nose(t)
        map(out, t, p, ink)

        // the track bed with sleepers, between the rails
        out.rect(0.5f, 0.5f, 2 * A + 0.002f, 2 * A + 0.002f, p.bed, 0f, RC, OR0 - IR1)
        val ns = 64
        val sl = Colors.alpha(ink.faint, 0.9f)
        for (i in 0 until ns) {
            LoopTrain.pathAt(A, RC, LEN * i / ns, q)
            val nx = q[3]; val ny = -q[2]
            out.line(q[0] + nx * 0.062f, q[1] + ny * 0.062f, q[0] - nx * 0.062f, q[1] - ny * 0.062f, 0.006f, sl, false)
        }
        // rails, with gleams sliding along them
        out.rect(0.5f, 0.5f, OR0 + OR1, OR0 + OR1, p.rail, 0f, RC + (OR0 + OR1) / 2 - A, OR1 - OR0)
        out.rect(0.5f, 0.5f, IR0 + IR1, IR0 + IR1, p.rail, 0f, max(0.004f, RC + (IR0 + IR1) / 2 - A), IR1 - IR0)
        for (j in 0 until 4) {
            val off = if (j < 2) (OR0 + OR1) / 2 - A else (IR0 + IR1) / 2 - A
            val sp = (if (j % 2 == 0) 0.21f else -0.13f) * (1f + 0.3f * j)
            LoopTrain.pathAt(A, RC, (t * sp + j * 0.63f) * LEN, q)
            out.glow(q[0] + q[3] * off, q[1] - q[2] * off, 0.028f, Colors.alpha(p.railHi, 0.75f))
        }

        stations(out, t, p, ink, sn)
        signal(out, t, p, ink, sn)

        // headlight beam on the track ahead of the nose (it breathes a little)
        LoopTrain.pathAt(A, RC, sn + 0.06f, q)
        out.glow(q[0], q[1], 0.065f + 0.008f * sin(t * 2.3f), Colors.alpha(ink.soft, 0.8f))

        // carriages, back to front: body, then windows (lit ones shimmer between two warm tones)
        for (k in 13 downTo 0) {
            val x0 = LOCO + COUP + k * (CL + COUP)
            band(out, sn - x0 - CL, sn - x0, -BODY, BODY, if (k % 2 == 0) p.body else p.body2, q)
            for (c in 0 until 3) {
                // one column: both windows share the track geometry
                val sc = sn - windowX(k, c)
                LoopTrain.pathAt(A, RC, sc - WA / 2, q)
                val ax = q[0]; val ay = q[1]; val anx = q[3]; val any = -q[2]
                LoopTrain.pathAt(A, RC, sc + WA / 2, q)
                val bx = q[0]; val by = q[1]; val bnx = q[3]; val bny = -q[2]
                LoopTrain.pathAt(A, RC, sc, q)
                val cx = q[0]; val cy = q[1]; val nx = q[3]; val ny = -q[2]
                val rot = if (q[3] == 0f) (if (q[2] > 0f) 0f else 180f) else if (q[2] == 0f) (if (q[3] > 0f) 90f else 270f) else atan2(q[3], q[2]) * 57.29578f
                for (row in 0 until 2) {
                    val bi = 6 * k + 2 * c + row
                    val on = bits[bi] xor MASK[bi]
                    val off = if (row == 0) RO else -RO
                    val dx = (bx + bnx * off) - (ax + anx * off); val dy = (by + bny * off) - (ay + any * off)
                    val len = sqrt(dx * dx + dy * dy)
                    val wx = cx + nx * off; val wy = cy + ny * off
                    out.rect(wx, wy, len, WC, if (on) p.lit else ink.plate, rot, 0.003f)
                    if (on) {
                        val sh = 0.5f + 0.5f * sin(t * (0.5f + 0.4f * fr(bi * 0.618f)) + bi * 2.1f)
                        if (sh > 0.1f) out.rect(wx, wy, len, WC, Colors.alpha(p.lit2, 0.7f * sh), rot, 0.003f)
                    }
                }
            }
        }
        // tail lamp (it glimmers)
        band(out, sn - TRAIN + 0.003f, sn - TRAIN + 0.015f, -0.01f, 0.01f, p.red, q)
        LoopTrain.pathAt(A, RC, sn - TRAIN + 0.009f, q)
        out.glow(q[0], q[1], 0.022f + 0.004f * sin(t * 5f), Colors.alpha(p.red, 0.45f))
        // locomotive: body with a tapering nose, windscreen (both rows and the strip between), cab lamp
        band(out, sn - LOCO, sn - 0.016f, -BODY, BODY, p.body2, q)
        for (i in 0 until 4) band(out, sn - 0.016f + i * 0.004f, sn - 0.012f + i * 0.004f, -BODY + 0.006f * i, BODY - 0.006f * i, p.body2, q)
        band(out, sn - 0.045f, sn - 0.006f, -0.048f, 0.048f, p.lit, q)
        band(out, sn - 0.085f, sn - 0.065f, -0.012f, 0.012f, p.lit, q)
        LoopTrain.pathAt(A, RC, sn - 0.004f, q)
        out.glow(q[0], q[1], 0.03f, Colors.alpha(p.lit, 0.55f))
        // event: the pantograph sparks now and then (on the loco's outer edge, away from the cue strip)
        run {
            val w = 3.4f; val tt = t + 0.9f; val n = floor(tt / w).toInt(); val u = tt / w - n
            val e = env(u, 0.05f, 0.3f)
            if (e > 0.01f) {
                LoopTrain.pathAt(A, RC, sn - 0.075f, q)
                val bx = q[0] + q[3] * (BODY + 0.006f); val by = q[1] - q[2] * (BODY + 0.006f)
                out.glow(bx, by, 0.03f * e, Colors.alpha(ink.soft, e))
                for (i in 0 until 3) {
                    val d = 6.2832f * hash(n * 3 + i, 71)
                    val r = 0.01f + 0.02f * smooth(0f, 0.4f, u)
                    out.line(bx + 0.004f * cos(d), by + 0.004f * sin(d), bx + r * cos(d), by + r * sin(d), 0.0025f, Colors.alpha(p.lit2, e))
                }
            }
        }

        board(out, t, p, ink, sn)
    }

    private fun fr(x: Float) = x - floor(x)
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val k = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return k * k * (3 - 2 * k) }
    private fun env(u: Float, a: Float = 0.18f, b: Float = 0.7f) = smooth(0f, a, u) * (1f - smooth(b, 1f, u))
    private fun hash(n: Int, salt: Int): Float {
        var x = n * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFFFF) / 16777216f
    }

    /** The city round the line: blocks and streets, a park, a river with ripples, a second line with a ghost train. */
    private fun map(out: Scene, t: Float, p: Pal, ink: Ink) {
        val f = Colors.alpha(p.map1, 0.9f)
        // blocks in the corners (stopping short of the outer rail)
        for (i in 0 until 8) {
            val c = i % 4
            val sx = if (c == 1 || c == 2) 1f else -1f; val sy = if (c >= 2) 1f else -1f
            val d = 0.455f + 0.025f * (i / 4)
            val bx = 0.5f + sx * (d + 0.01f * (i / 4)); val by = 0.5f + sy * (d - 0.03f - 0.06f * (i / 4))
            out.rect(bx, by, 0.05f, 0.035f, f, 0f, 0.006f)
            out.rect(0.5f + sx * (d - 0.03f - 0.06f * (i / 4)), 0.5f + sy * (d + 0.01f * (i / 4)), 0.035f, 0.05f, f, 0f, 0.006f)
        }
        // a park with a pond, top left
        out.glow(0.08f, 0.1f, 0.13f, Colors.alpha(p.park, 0.95f))
        // the river: a wide soft band across the bottom-left corner, ripples drifting down it
        val rv = floatArrayOf(-0.05f, 0.6f, 0.07f, 0.77f, 0.2f, 0.92f, 0.34f, 1.05f)
        for (i in 0 until 3) out.line(rv[2 * i], rv[2 * i + 1], rv[2 * i + 2], rv[2 * i + 3], 0.06f, Colors.alpha(p.water, 0.95f))
        for (j in 0 until 4) {
            val u = fr(t * 0.06f + j * 0.25f)
            val seg = min(2, (u * 3).toInt())
            val v = u * 3 - seg
            val x = rv[2 * seg] + (rv[2 * seg + 2] - rv[2 * seg]) * v; val y = rv[2 * seg + 1] + (rv[2 * seg + 3] - rv[2 * seg + 1]) * v
            val w = 0.012f * env(u, 0.1f, 0.9f)
            out.line(x - w, y + w * 0.3f + 0.012f * sin(j * 2f), x + w, y - w * 0.3f + 0.012f * sin(j * 2f), 0.004f, Colors.alpha(ink.soft, 0.6f))
        }
        // a second line, dashed, cutting across the map behind everything; its train glides along now and then
        val gx0 = -0.05f; val gy0 = 0.16f; val gx1 = 1.05f; val gy1 = 0.42f
        for (i in 0 until 12) {
            val a0 = i / 12f; val a1 = a0 + 0.055f
            out.line(gx0 + (gx1 - gx0) * a0, gy0 + (gy1 - gy0) * a0, gx0 + (gx1 - gx0) * a1, gy0 + (gy1 - gy0) * a1, 0.008f, f, false)
        }
        val gu = fr(t / 11f)
        val gk = smooth(0f, 1f, gu)
        out.rect(gx0 + (gx1 - gx0) * gk, gy0 + (gy1 - gy0) * gk, 0.07f, 0.016f, Colors.alpha(ink.soft, 0.8f * env(gu, 0.05f, 0.95f)), atan2(gy1 - gy0, gx1 - gx0) * 57.29578f, 0.007f)
    }

    /** Stations at the corners: interchange rings, a few waiting passengers, a chime ring now and then. */
    private fun stations(out: Scene, t: Float, p: Pal, ink: Ink, sn: Float) {
        val w = 1.9f; val n = floor(t / w).toInt(); val u = t / w - n
        val chime = (hash(n, 81) * 4).toInt()
        for (c in 0 until 4) {
            val sx = if (c == 1 || c == 2) 1f else -1f
            val sy = if (c >= 2) 1f else -1f
            val cx = 0.5f + sx * (A - RC + (RC + 0.081f) * 0.7071f)
            val cy = 0.5f + sy * (A - RC + (RC + 0.081f) * 0.7071f)
            if (c == chime) {
                for (k in 0 until 2) {
                    val uk = (u - 0.18f * k).coerceIn(0f, 1f)
                    out.circle(cx, cy, 0.032f + 0.05f * smooth(0f, 1f, uk), Colors.alpha(p.rail, 0.5f * env(uk, 0.1f, 0.4f)), 0.003f)
                }
            }
            out.circle(cx, cy, 0.03f, p.rail)
            out.circle(cx, cy, 0.021f, p.station)
            out.circle(cx, cy, 0.009f, ink.plate)
            // passengers on the platform outside the corner
            for (k in 0 until 3) {
                val ph = t * (0.5f + 0.2f * k) + c * 1.7f + k * 2.3f
                val r = 0.048f + 0.006f * sin(ph * 0.7f)
                val ang = (0.785f + 0.32f * (k - 1)) 
                out.circle(cx + sx * r * cos(ang) + 0.003f * sin(ph), cy + sy * r * sin(ang), 0.0045f, Colors.alpha(ink.soft, 0.6f + 0.4f * sin(ph)))
            }
        }
    }

    /** The signal at the top-right corner: red while the train nears, then green, the lamps fading across. */
    private fun signal(out: Scene, t: Float, p: Pal, ink: Ink, sn: Float) {
        val gx = 0.94f; val gy = 0.18f
        val stationS = 2 * (A - RC) + PI.toFloat() / 4 * RC
        val ahead = ((stationS - sn) % LEN + LEN) % LEN
        val red = smooth(0.32f, 0.26f, ahead) * (1f - smooth(0.02f, 0f, ahead) * 0f)
        val green = 1f - red
        out.rect(gx, gy + 0.05f, 0.006f, 0.07f, ink.soft)
        out.rect(gx, gy, 0.034f, 0.062f, Colors.mix(ink.plate, ink.soft, 0.6f), 0f, 0.01f)
        out.circle(gx, gy - 0.014f, 0.01f, Colors.alpha(p.red, 0.25f + 0.75f * red))
        out.circle(gx, gy + 0.014f, 0.01f, Colors.alpha(p.green, 0.25f + 0.75f * green))
        out.glow(gx, gy - 0.014f, 0.03f, Colors.alpha(p.red, 0.5f * red))
        out.glow(gx, gy + 0.014f, 0.03f, Colors.alpha(p.green, 0.5f * green))
    }

    /** A strip of the track between arc lengths [s0]..[s1] and offsets [o0]..[o1] (outwards +). */
    private fun band(out: Scene, s0: Float, s1: Float, o0: Float, o1: Float, col: Int, q: FloatArray) {
        val n = max(1, ((s1 - s0) / 0.03f).roundToInt())
        val pts = FloatArray(4 * (n + 1))
        var m = 0
        for (i in 0..n) {
            LoopTrain.pathAt(A, RC, s0 + (s1 - s0) * i / n, q)
            pts[m++] = q[0] + q[3] * o1; pts[m++] = q[1] - q[2] * o1
        }
        for (i in n downTo 0) {
            LoopTrain.pathAt(A, RC, s0 + (s1 - s0) * i / n, q)
            pts[m++] = q[0] + q[3] * o0; pts[m++] = q[1] - q[2] * o0
        }
        out.poly(pts, col)
    }

    private fun board(out: Scene, t: Float, p: Pal, ink: Ink, sn: Float) {
        val quarter = LEN / 4
        // the next station: the corner the nose reaches next
        val corner0 = 2 * (A - RC) + PI.toFloat() / 4 * RC
        val rel = ((sn - corner0) % LEN + LEN) % LEN
        val idx = (floor(rel / quarter).toInt() + 1) % 4
        val since = (rel % quarter) / SPEED
        // header: line badge and a small "NEXT"
        out.circle(0.375f, 0.355f, 0.032f, p.rail)
        glyphs(out, "1", 0.375f, 0.355f, 0.0095f, ink.plate)
        glyphs(out, "NEXT", 0.53f, 0.355f, 0.0085f, Colors.alpha(p.letter, 0.85f))
        // split-flap tiles
        val name = STATIONS[idx]
        for (i in 0 until 4) {
            val cx = 0.5f + (i - 1.5f) * 0.085f
            val cy = 0.49f
            out.rect(cx, cy, 0.074f, 0.1f, p.tile, 0f, 0.008f)
            var flip = since - i * 0.08f
            // event: now and then one tile riffles on its own and settles back
            run {
                val w = 2.6f; val tt = t + 0.5f; val n = floor(tt / w).toInt(); val u = tt / w - n
                if ((hash(n, 91) * 4).toInt() == i && u * w < 0.6f && flip !in 0f..0.6f) flip = u * w
            }
            var ch = name[i]
            var squash = 1f
            if (flip in 0f..0.6f) {
                // letters riffle through before they settle
                ch = "ABCDEHMNOPRTUXY"[((flip * 25).toInt() + i * 3) % 15]
                squash = abs(cos(flip * 25f))
            }
            glyphs(out, ch.toString(), cx, cy, 0.0145f, p.letter, squash)
            out.line(cx - 0.037f, cy, cx + 0.037f, cy, 0.003f, Colors.mix(p.tile, ink.plate, 0.6f), false)
        }
        // minutes to the next stop
        val left = (quarter - rel % quarter) / SPEED / 10f
        val mins = max(1, left.toInt() + 1)
        // the number flaps over when it changes
        val edge = min(left - floor(left), 1f - (left - floor(left)))
        val sq = if (edge < 0.025f) abs(cos(edge / 0.025f * PI.toFloat() / 2f + PI.toFloat() / 2f)) else 1f
        glyphs(out, "$mins MIN", 0.5f, 0.615f, 0.0085f, Colors.alpha(p.letter, 0.8f), max(0.15f, if (edge < 0.025f) 1f - sq else 1f))
        // a progress dot running along under the board
        val fr = (rel % quarter) / quarter
        out.line(0.37f, 0.665f, 0.63f, 0.665f, 0.004f, ink.soft)
        out.circle(0.37f + 0.26f * fr, 0.665f, 0.008f, p.rail)
    }

    /** Text in a 3 × 5 pixel font, centred on (x, y), pixel size [px], squashed vertically by [sy] (a flap turning). */
    private fun glyphs(out: Scene, s: String, x: Float, y: Float, px: Float, col: Int, sy: Float = 1f) {
        val w = s.length * 4 - 1
        for ((i, ch) in s.withIndex()) {
            val code = ch.code
            val g = if (code < 128) FONT[code] else 0
            if (g == 0) continue
            for (r in 0 until 5) for (c in 0 until 3) {
                if ((g shr (14 - (r * 3 + c))) and 1 == 0) continue
                val gx = x + (i * 4 + c - (w - 1) / 2f) * px
                val gy = y + (r - 2) * px * sy
                out.rect(gx, gy, px * 0.92f, px * 0.92f * max(0.15f, sy), col)
            }
        }
    }

    private val FONT: IntArray = IntArray(128).also { f ->
        for ((c, g) in listOf('A' to 0b010_101_111_101_101, 'B' to 0b110_101_110_101_110, 'C' to 0b011_100_100_100_011, 'D' to 0b110_101_101_101_110,
        'E' to 0b111_100_110_100_111, 'H' to 0b101_101_111_101_101, 'I' to 0b111_010_010_010_111, 'M' to 0b101_111_111_101_101,
        'N' to 0b110_101_101_101_101, 'O' to 0b111_101_101_101_111, 'P' to 0b110_101_110_100_100, 'R' to 0b110_101_110_101_101,
        'T' to 0b111_010_010_010_010, 'U' to 0b101_101_101_101_111, 'X' to 0b101_101_010_101_101, 'Y' to 0b101_101_010_010_010,
        '0' to 0b111_101_101_101_111, '1' to 0b010_110_010_010_111, '2' to 0b110_001_010_100_111, '3' to 0b110_001_010_001_110,
        '4' to 0b101_101_111_001_001, '5' to 0b111_100_110_001_110, '6' to 0b011_100_111_101_111, '7' to 0b111_001_010_010_010,
        '8' to 0b111_101_111_101_111, '9' to 0b111_101_111_001_110)) f[c.code] = g
    }

    override fun reader(): SchemeReader = TrainReader()
}

/** Hue (degrees) of [c], or [fallback] if it is nearly grey. */
private fun hueOf(c: Int, fallback: Float): Float {
    val r = Colors.r(c) / 255f; val g = Colors.g(c) / 255f; val b = Colors.b(c) / 255f
    val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
    if (d < 0.15f) return fallback
    var h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
    if (h < 0) h += 360f
    return h
}

private fun trainTone(h: Float, s: Float, l: Float): Int {
    val top = Colors.hsv(h, s, 1f)
    if (Colors.light(top) < l) {
        var a = 0f; var b = 1f
        repeat(14) { val m = (a + b) / 2; if (Colors.light(Colors.mix(top, 0xFFFFFFFF.toInt(), m)) < l) a = m else b = m }
        return Colors.mix(top, 0xFFFFFFFF.toInt(), (a + b) / 2)
    }
    var lo = 0f; var hi = 1f
    repeat(14) { val m = (lo + hi) / 2; if (Colors.light(Colors.hsv(h, s, m)) < l) lo = m else hi = m }
    return Colors.hsv(h, s, (lo + hi) / 2)
}

// ====================================================================== reader


private class TrainReader : SchemeReader {
    private var seenNow = false
    override val seen: Boolean get() = seenNow

    private var hPrev: DoubleArray? = null
    private val small = TSmall()
    private var pol = 0
    private var lastLock = -1e9
    // soft votes per bit over recent frames
    private val votes = ArrayList<Pair<Double, FloatArray>>()

    override fun feed(f: SigilFrame): Read? {
        val t = f.tMs / 1000.0
        if (t - lastLock > 1.5) votes.clear()
        val h = locate(f, t)
        seenNow = h != null
        if (h == null) return null
        lastLock = t
        val v = read(f, h) ?: return null
        votes.add(t to v)
        while (votes.isNotEmpty() && t - votes[0].first > 1.6) votes.removeAt(0)
        // decode this frame alone, then the frames together
        decodeSoft(v)?.let { return it }
        if (votes.size < 2) return null
        val sum = FloatArray(SigilPayload.BITS)
        for ((_, w) in votes) for (i in sum.indices) sum[i] += w[i]
        return decodeSoft(sum)
    }

    private fun decodeSoft(v: FloatArray): Read? {
        val bits = BooleanArray(SigilPayload.BITS) { (v[it] > 0f) xor LoopTrain.MASK[it] }
        val sure = FloatArray(SigilPayload.BITS) { abs(v[it]) }
        return SigilPayload.decodeBits(bits, sure)
    }

    // ------------------------------------------------------------------ geometry

    private fun locate(f: SigilFrame, t: Double): DoubleArray? {
        val prev = hPrev
        if (prev != null && t - lastLock < 0.6) {
            val h = refine(f, prev, pol, 2)
            if (h != null) { hPrev = h; return h }
        }
        hPrev = null
        val sm = small
        sm.load(f)
        val cls = sm.classes(12, 9)
        val lab = sm.lab
        for (want in intArrayOf(1, -1)) {
            val comps = sm.label(cls, want.toByte(), lab, if (want == 1) 1 else 1_000_000)
            val rings = comps.filter { c ->
                val bw = c.x1 - c.x0 + 1; val bh = c.y1 - c.y0 + 1
                val big = max(bw, bh)
                big in 28..240 && min(bw, bh) > big * 0.55f && c.area >= 60 && c.area < 0.4f * bw * bh &&
                    c.x0 > 0 && c.y0 > 0 && c.x1 < sm.w - 1 && c.y1 < sm.h - 1 &&
                    abs(c.cx - (c.x0 + c.x1) / 2f) < 0.15f * big && abs(c.cy - (c.y0 + c.y1) / 2f) < 0.15f * big &&
                    lab[c.cy.toInt() * sm.w + c.cx.toInt()] != c.id
            }.sortedByDescending { it.area }.take(4).let { l ->
                // of two concentric rings the inner one is the inner rail: try it first
                l.sortedBy { c -> if (l.any { o -> o !== c && o.area > c.area && abs(o.cx - c.cx) + abs(o.cy - c.cy) < 0.08f * (o.x1 - o.x0) }) 0 else 1 }
            }
            for (c in rings) {
                val h0 = innerSquare(sm, lab, c) ?: continue
                val h = refine(f, h0, want, 3)
                if (h == null) continue
                hPrev = h; pol = want
                return h
            }
        }
        return null
    }

    /** First homography from the ring component taken as the inner rail: its inner edge along rays from the centre. */
    private fun innerSquare(sm: TSmall, lab: IntArray, c: TSmall.Comp): DoubleArray? {
        val nr = 72
        val d = DoubleArray(nr) { Double.NaN }
        val big = max(c.x1 - c.x0, c.y1 - c.y0).toDouble()
        for (i in 0 until nr) {
            val ang = 2 * PI * i / nr
            val dx = cos(ang); val dy = sin(ang)
            var r = 1.0
            while (r < big) {
                val x = (c.cx + dx * r).toInt(); val y = (c.cy + dy * r).toInt()
                if (x < 0 || y < 0 || x >= sm.w || y >= sm.h) break
                if (lab[y * sm.w + x] == c.id) { d[i] = r; break }
                r += 0.5
            }
        }
        val pts = squareLines(d, nr, 2.0 * c.cx + 1, 2.0 * c.cy + 1, 2.0, big * 0.06) ?: return null
        val a = LoopTrain.IR0
        val mod = floatArrayOf(0.5f - a, 0.5f - a, 0.5f + a, 0.5f - a, 0.5f + a, 0.5f + a, 0.5f - a, 0.5f + a)
        return Fit.homography(mod, pts)
    }

    /** Edge model: (half-size of a rounded square edge, +1 if the rail lies inside it / -1 outside). */
    private val edges = arrayOf(
        doubleArrayOf(LoopTrain.IR0.toDouble(), -1.0), doubleArrayOf(LoopTrain.IR1.toDouble(), 1.0),
        doubleArrayOf(LoopTrain.OR0.toDouble(), -1.0), doubleArrayOf(LoopTrain.OR1.toDouble(), 1.0),
    )

    /** Edge search on the straight parts of both rails' edges, then a homography fit to all edge points. */
    private fun refine(f: SigilFrame, h0: DoubleArray, pol: Int, rounds: Int): DoubleArray? {
        var h = h0
        val maxPts = 4 * 4 * 9
        val ix = DoubleArray(maxPts); val iy = DoubleArray(maxPts); val axis = IntArray(maxPts); val value = DoubleArray(maxPts); val wt = DoubleArray(maxPts)
        val pa = DoubleArray(2); val pb = DoubleArray(2)
        val prof = FloatArray(40)
        var rms = 0.0
        for (round in 0 until rounds) {
            var n = 0
            var outerN = 0
            val reach = if (round == 0) 0.016 else 0.01
            for ((ei, e) in edges.withIndex()) {
                val half = e[0]
                val span = if (ei < 2) 0.17 else 0.22
                for (side in 0 until 4) {
                    val (nx, ny) = tSideNormal(side)
                    for (i in 0 until 9) {
                        val u = -span + 2 * span * i / 8
                        val x = 0.5 + tSideX(side, u, half); val y = 0.5 + tSideY(side, u, half)
                        // from the rail across its edge: the rail is on the -e[1] side of the outward normal
                        val sx = nx * e[1]; val sy = ny * e[1]
                        tApply(h, x - sx * reach, y - sy * reach, pa)
                        tApply(h, x + sx * reach, y + sy * reach, pb)
                        if (!tInside(f, pa[0], pa[1]) || !tInside(f, pb[0], pb[1])) continue
                        val len = sqrt((pb[0] - pa[0]) * (pb[0] - pa[0]) + (pb[1] - pa[1]) * (pb[1] - pa[1]))
                        val m = (len / 0.7).toInt().coerceIn(8, 40)
                        for (k in 0 until m) { val qq = k / (m - 1.0); prof[k] = f.at((pa[0] + (pb[0] - pa[0]) * qq).toFloat(), (pa[1] + (pb[1] - pa[1]) * qq).toFloat()) }
                        // going from rail (lit) to not-rail: pol·value drops
                        var bi = -1; var bv = 0f
                        for (k in 1 until m - 1) { val g = -pol * (prof[k + 1] - prof[k - 1]); if (g > bv) { bv = g; bi = k } }
                        if (bi < 0 || bv < 8f) continue
                        var off = 0.0
                        if (bi in 2 until m - 2) {
                            val e0 = -pol * (prof[bi] - prof[bi - 2]); val e2 = -pol * (prof[bi + 2] - prof[bi])
                            val den = e0 - 2 * bv + e2
                            if (den < 0) off = (0.5 * (e0 - e2) / den).coerceIn(-0.5, 0.5)
                        }
                        val qq = (bi + off) / (m - 1.0)
                        ix[n] = pa[0] + (pb[0] - pa[0]) * qq; iy[n] = pa[1] + (pb[1] - pa[1]) * qq
                        axis[n] = if (side == 0 || side == 2) 1 else 0
                        value[n] = 0.5 + (if (side == 0 || side == 3) -half else half)
                        wt[n] = 1.0
                        if (ei >= 2) outerN++
                        n++
                    }
                }
            }
            if (n < 24) return null
            val r = fitHomography(h, ix, iy, axis, value, wt, n) ?: return null
            h = r.first; rms = r.second
            if (round == 0 && rms > 0.012) return null
        }
        // pixels per unit at the centre: residuals were in model units
        val p0 = DoubleArray(2); val p1 = DoubleArray(2)
        tApply(h, 0.5, 0.5, p0); tApply(h, 0.6, 0.5, p1)
        val scale = sqrt((p1[0] - p0[0]) * (p1[0] - p0[0]) + (p1[1] - p0[1]) * (p1[1] - p0[1])) * 10
        if (rms * scale > 1.6) return null
        // both rails must stand out from the bed and the margin
        val pt = DoubleArray(2)
        var good = 0; var tot = 0
        for (side in 0 until 4) for (i in 0 until 5) {
            val u = -0.16 + 0.08 * i
            for ((mid, inn, outr) in listOf(Triple((LoopTrain.IR0 + LoopTrain.IR1) / 2.0, LoopTrain.IR0 - 0.02, LoopTrain.IR1 + 0.008),
                Triple((LoopTrain.OR0 + LoopTrain.OR1) / 2.0, LoopTrain.OR0 - 0.01, LoopTrain.OR1 + 0.015))) {
                val vr = tSample(f, h, 0.5 + tSideX(side, u, mid), 0.5 + tSideY(side, u, mid), pt, 0.8f)
                val vi = tSample(f, h, 0.5 + tSideX(side, u, inn), 0.5 + tSideY(side, u, inn), pt, 0.8f)
                val vo = tSample(f, h, 0.5 + tSideX(side, u, outr), 0.5 + tSideY(side, u, outr), pt, 0.8f)
                if (vr.isNaN() || vi.isNaN() || vo.isNaN()) continue
                tot++
                if (pol * (vr - vi) > 10 && pol * (vr - vo) > 10) good++
            }
        }
        if (tot < 16 || good < 0.7 * tot) return null
        return h
    }

    // ------------------------------------------------------------------ reading the train

    /** Soft window votes (+ lit, − dark, in drawn-bit order) or null if the locomotive is not found. */
    private fun read(f: SigilFrame, h: DoubleArray): FloatArray? {
        val q = FloatArray(4)
        val pt = DoubleArray(2)
        val p0 = DoubleArray(2); val p1 = DoubleArray(2)
        tApply(h, 0.5, 0.5, p0); tApply(h, 0.6, 0.5, p1)
        val scale = (sqrt((p1[0] - p0[0]) * (p1[0] - p0[0]) + (p1[1] - p0[1]) * (p1[1] - p0[1])) * 10).toFloat()
        val len = LoopTrain.LEN
        // the strip between the window rows, unrolled; and the outer rail beside it (lit reference)
        val n = NS
        val mid = FloatArray(n); val rail = FloatArray(n)
        val rMid = max(0.6f, 0.006f * scale)
        for (i in 0 until n) {
            LoopTrain.pathAt(LoopTrain.A, LoopTrain.RC, len * i / n, q)
            mid[i] = tSampleXY(f, h, q[0].toDouble(), q[1].toDouble(), pt, rMid)
            val ro = (LoopTrain.OR0 + LoopTrain.OR1) / 2 - LoopTrain.A
            rail[i] = tSampleXY(f, h, (q[0] + q[3] * ro).toDouble(), (q[1] - q[2] * ro).toDouble(), pt, 0.6f)
        }
        val mids = mid.filter { !it.isNaN() }.sorted()
        val rails = rail.filter { !it.isNaN() }.sorted()
        if (mids.size < n / 2 || rails.size < n / 4) return null
        val darkG = mids[mids.size / 3]
        val litG = rails[rails.size / 2]
        val c = litG - darkG
        if (pol * c < 15f) return null
        // match the locomotive's light pattern (both directions)
        val tpl = TEMPLATE
        var best = -1e9; var bestI = -1; var bestD = 0
        for (d in intArrayOf(1, -1)) for (i in 0 until n) {
            var sc = 0.0; var used = 0
            for (k in tpl.indices step 2) {
                val j = ((i - d * (tpl[k] / len * n).roundToInt()) % n + n) % n
                val v = mid[j]
                if (v.isNaN()) continue
                sc += tpl[k + 1] * (((v - darkG) / c).coerceIn(-0.3f, 1.3f) - 0.5f); used++
            }
            if (used < tpl.size / 2 * 0.8) continue
            sc /= used
            if (sc > best) { best = sc; bestI = i; bestD = d }
        }
        if (best < 0.25) return null
        // refine the nose position by a finer search round the best sample
        var sNose = len * bestI / n
        var fine = -1e9; var fineS = sNose
        for (k in -6..6) {
            val s = sNose + k * len / n / 6
            var sc = 0.0; var used = 0
            for (j in tpl.indices step 2) {
                LoopTrain.pathAt(LoopTrain.A, LoopTrain.RC, s - bestD * tpl[j], q)
                val v = tSampleXY(f, h, q[0].toDouble(), q[1].toDouble(), pt, rMid)
                if (v.isNaN()) continue
                sc += tpl[j + 1] * (((v - darkG) / c).coerceIn(-0.3f, 1.3f) - 0.5f); used++
            }
            if (used > 0 && sc / used > fine) { fine = sc / used; fineS = s }
        }
        sNose = fineS
        // windows
        val out = FloatArray(SigilPayload.BITS)
        val rWin = max(0.6f, 0.007f * scale)
        val ro = (LoopTrain.OR0 + LoopTrain.OR1) / 2 - LoopTrain.A
        val ri = (LoopTrain.IR0 + LoopTrain.IR1) / 2 - LoopTrain.A
        for (k in 0 until 14) for (cc in 0 until 3) {
            val s = sNose - bestD * LoopTrain.windowX(k, cc)
            LoopTrain.pathAt(LoopTrain.A, LoopTrain.RC, s, q)
            val x = q[0].toDouble(); val y = q[1].toDouble(); val nx = q[3].toDouble(); val ny = -q[2].toDouble()
            // local references: the carriage body between the rows (dark), the rails (lit)
            val dk = tSampleXY(f, h, x, y, pt, rMid)
            val l1 = tSampleXY(f, h, x + nx * ro, y + ny * ro, pt, 0.6f)
            val l2 = tSampleXY(f, h, x + nx * ri, y + ny * ri, pt, 0.6f)
            val lit = if (!l1.isNaN() && !l2.isNaN()) (l1 + l2) / 2 else if (!l1.isNaN()) l1 else if (!l2.isNaN()) l2 else litG
            val dark = if (dk.isNaN()) darkG else dk
            val cl = lit - dark
            for (row in 0 until 2) {
                val o = if (row == 0) LoopTrain.RO.toDouble() else -LoopTrain.RO.toDouble()
                val v = tSampleXY(f, h, x + nx * o, y + ny * o, pt, rWin)
                val bi = 6 * k + 2 * cc + row
                if (v.isNaN() || pol * cl < 0.4f * pol * c) { out[bi] = 0f; continue }
                val pv = (v - dark) / cl
                out[bi] = ((pv - 0.5f) * 2.5f).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    companion object {
        const val NS = 600
        /** Locomotive light pattern along the strip between the window rows: distance behind the nose, weight. */
        val TEMPLATE: FloatArray = run {
            val l = ArrayList<Float>()
            fun span(a: Float, b: Float, w: Float, step: Float = 0.005f) { var x = a; while (x <= b + 1e-6f) { l.add(x); l.add(w); x += step } }
            span(-0.07f, -0.015f, -1f)
            span(0.012f, 0.04f, 1f)
            span(0.05f, 0.058f, -1f)
            span(0.07f, 0.08f, 1.5f)
            span(0.093f, 0.13f, -1f)
            l.toFloatArray()
        }
    }
}

// ====================================================================== geometry helpers (private to this file)

private fun tApply(h: DoubleArray, x: Double, y: Double, out: DoubleArray) {
    val d = h[6] * x + h[7] * y + h[8]
    out[0] = (h[0] * x + h[1] * y + h[2]) / d
    out[1] = (h[3] * x + h[4] * y + h[5]) / d
}

private fun tInside(f: SigilFrame, x: Double, y: Double, m: Double = 2.0) = x >= m && y >= m && x <= f.w - 1 - m && y <= f.h - 1 - m

private fun tCross(f: SigilFrame, x: Float, y: Float, r: Float): Float {
    if (r < 0.6f) return f.at(x, y)
    return (2 * f.at(x, y) + f.at(x - r, y) + f.at(x + r, y) + f.at(x, y - r) + f.at(x, y + r)) / 6f
}

private fun tSample(f: SigilFrame, h: DoubleArray, x: Double, y: Double, pt: DoubleArray, r: Float): Float = tSampleXY(f, h, x, y, pt, r)

private fun tSampleXY(f: SigilFrame, h: DoubleArray, x: Double, y: Double, pt: DoubleArray, r: Float): Float {
    tApply(h, x, y, pt)
    if (!tInside(f, pt[0], pt[1], 2.0 + r)) return Float.NaN
    return tCross(f, pt[0].toFloat(), pt[1].toFloat(), r)
}

private fun tSideX(side: Int, u: Double, a: Double) = when (side) { 0 -> u; 1 -> a; 2 -> -u; else -> -a }
private fun tSideY(side: Int, u: Double, a: Double) = when (side) { 0 -> -a; 1 -> u; 2 -> a; else -> -u }
private fun tSideNormal(side: Int): Pair<Double, Double> = when (side) { 0 -> 0.0 to -1.0; 1 -> 1.0 to 0.0; 2 -> 0.0 to 1.0; else -> -1.0 to 0.0 }

/** 3×3 inverse (row-major). */
private fun inv3(m: DoubleArray): DoubleArray? {
    val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
    val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
    val det = a * A + b * B + c * C
    if (abs(det) < 1e-12) return null
    return doubleArrayOf(A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
        B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
        C / det, -(a * h - b * g) / det, (a * e - b * d) / det)
}

/**
 * Least-squares homography (model → image) such that image points (ix, iy) map back onto model lines
 * (model coordinate [axis] = [value]); Gauss–Newton on the image → model map, started from [h0]. Returns the
 * homography and the RMS residual (model units), after dropping gross outliers once.
 */
private fun fitHomography(h0: DoubleArray, ix: DoubleArray, iy: DoubleArray, axis: IntArray, value: DoubleArray, wt: DoubleArray, n: Int): Pair<DoubleArray, Double>? {
    // normalised image coordinates (centred, unit spread) keep the normal equations well conditioned
    var mx = 0.0; var my = 0.0
    for (k in 0 until n) { mx += ix[k]; my += iy[k] }
    mx /= n; my /= n
    var sc = 0.0
    for (k in 0 until n) sc += abs(ix[k] - mx) + abs(iy[k] - my)
    sc = max(1.0, sc / (2 * n))
    val tn = doubleArrayOf(1 / sc, 0.0, -mx / sc, 0.0, 1 / sc, -my / sc, 0.0, 0.0, 1.0)
    val tInv = doubleArrayOf(sc, 0.0, mx, 0.0, sc, my, 0.0, 0.0, 1.0)
    var g = inv3(tMul3(tn, h0)) ?: return null
    val s0 = g[8]; g = DoubleArray(9) { g[it] / s0 }
    val nx = DoubleArray(n) { (ix[it] - mx) / sc }; val ny = DoubleArray(n) { (iy[it] - my) / sc }
    val use = BooleanArray(n) { true }
    fun res(gg: DoubleArray, k: Int): Double {
        val x = nx[k]; val y = ny[k]
        val d = gg[6] * x + gg[7] * y + gg[8]
        return if (axis[k] == 0) (gg[0] * x + gg[1] * y + gg[2]) / d - value[k] else (gg[3] * x + gg[4] * y + gg[5]) / d - value[k]
    }
    var rms = 0.0
    for (pass in 0 until 2) {
        for (iter in 0 until 4) {
            val jtj = Array(8) { DoubleArray(9) }
            val jr = DoubleArray(8)
            val jac = DoubleArray(8)
            for (k in 0 until n) {
                if (!use[k]) continue
                val x = nx[k]; val y = ny[k]
                val d = g[6] * x + g[7] * y + g[8]
                val num = if (axis[k] == 0) g[0] * x + g[1] * y + g[2] else g[3] * x + g[4] * y + g[5]
                val r = num / d - value[k]
                jac.fill(0.0)
                val o = if (axis[k] == 0) 0 else 3
                jac[o] = x / d; jac[o + 1] = y / d; jac[o + 2] = 1 / d
                jac[6] = -num * x / (d * d); jac[7] = -num * y / (d * d)
                val w = wt[k]
                for (p in 0 until 8) { jr[p] += w * jac[p] * r; for (q in 0 until 8) jtj[p][q] += w * jac[p] * jac[q] }
            }
            for (p in 0 until 8) { jtj[p][p] = jtj[p][p] * 1.001 + 1e-9; jtj[p][8] = -jr[p] }
            val step = Fit.solve(jtj) ?: return null
            for (p in 0 until 8) g[p] += step[p]
        }
        var ss = 0.0; var cnt = 0
        for (k in 0 until n) if (use[k]) { val r = res(g, k); ss += r * r; cnt++ }
        rms = sqrt(ss / max(1, cnt))
        if (pass == 1) break
        var kept = 0
        for (k in 0 until n) { use[k] = abs(res(g, k)) < max(0.004, 3 * rms); if (use[k]) kept++ }
        if (kept < 20) return null
    }
    val hn = inv3(g) ?: return null
    return tMul3(tInv, hn) to rms
}

private fun tMul3(a: DoubleArray, b: DoubleArray): DoubleArray {
    val r = DoubleArray(9)
    for (i in 0 until 3) for (j in 0 until 3) { var s = 0.0; for (k in 0 until 3) s += a[3 * i + k] * b[3 * k + j]; r[3 * i + j] = s }
    return r
}

/**
 * Corners (image, clockwise from the model's top-left) of a square from per-ray edge distances [d] round (cx, cy)
 * ([scale]: image pixels per ray unit): fourfold harmonic for the turn, a line per side through the rays near its
 * normal, outliers (further than [tol] ray units from the harmonic fit) dropped.
 */
private fun squareLines(d: DoubleArray, nr: Int, cx: Double, cy: Double, scale: Double, tol: Double): FloatArray? {
    var sol: DoubleArray? = null
    for (pass in 0 until 3) {
        val m = Array(5) { DoubleArray(6) }
        var valid = 0
        for (i in 0 until nr) {
            if (d[i].isNaN()) continue
            val ang = 2 * PI * i / nr
            val row = doubleArrayOf(1.0, cos(ang), sin(ang), cos(4 * ang), sin(4 * ang))
            val prev = sol
            if (prev != null) {
                val fit = prev[0] + prev[1] * row[1] + prev[2] * row[2] + prev[3] * row[3] + prev[4] * row[4]
                if (abs(d[i] - fit) > (if (pass == 1) 2.5 else 1.0) * tol) continue
            }
            for (p in 0 until 5) { for (q in 0 until 5) m[p][q] += row[p] * row[q]; m[p][5] += row[p] * d[i] }
            valid++
        }
        if (valid < nr / 3) return null
        sol = Fit.solve(m) ?: return null
    }
    val so = sol!!
    val corner = atan2(so[4], so[3]) / 4
    val lines = Array(4) { DoubleArray(3) }
    val px = DoubleArray(nr); val py = DoubleArray(nr); val pw = DoubleArray(nr)
    for (sd in 0 until 4) {
        val nrm = corner + PI / 4 + sd * PI / 2
        var n = 0
        for (i in 0 until nr) {
            if (d[i].isNaN()) continue
            val ang = 2 * PI * i / nr
            var da = (ang - nrm) % (2 * PI); if (da > PI) da -= 2 * PI; if (da < -PI) da += 2 * PI
            if (abs(da) > 30 * PI / 180) continue
            val fit = so[0] + so[1] * cos(ang) + so[2] * sin(ang) + so[3] * cos(4 * ang) + so[4] * sin(4 * ang)
            if (abs(d[i] - fit) > tol) continue
            px[n] = cx + cos(ang) * d[i] * scale; py[n] = cy + sin(ang) * d[i] * scale; pw[n] = 1.0; n++
        }
        if (n < 3) return null
        lines[sd] = tFitLine(px, py, pw, n) ?: return null
    }
    val img = FloatArray(8)
    for (k in 0 until 4) {
        val l1 = lines[(k + 3) % 4]; val l2 = lines[k]
        val dd = l1[0] * l2[1] - l1[1] * l2[0]
        if (abs(dd) < 1e-6) return null
        img[2 * k] = ((l1[1] * l2[2] - l2[1] * l1[2]) / dd).toFloat()
        img[2 * k + 1] = ((l2[0] * l1[2] - l1[0] * l2[2]) / dd).toFloat()
    }
    return img
}

private fun tFitLine(x: DoubleArray, y: DoubleArray, w: DoubleArray, n: Int): DoubleArray? {
    val use = BooleanArray(n) { true }
    var line: DoubleArray? = null
    for (pass in 0 until 2) {
        var sw = 0.0; var mx = 0.0; var my = 0.0
        for (i in 0 until n) if (use[i]) { sw += w[i]; mx += w[i] * x[i]; my += w[i] * y[i] }
        if (sw <= 0) return null
        mx /= sw; my /= sw
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) if (use[i]) { val dx = x[i] - mx; val dy = y[i] - my; sxx += w[i] * dx * dx; syy += w[i] * dy * dy; sxy += w[i] * dx * dy }
        val ang = 0.5 * atan2(2 * sxy, sxx - syy)
        val a = -sin(ang); val b = cos(ang)
        line = doubleArrayOf(a, b, -(a * mx + b * my))
        if (pass == 1) break
        var rms = 0.0
        for (i in 0 until n) { val dd = a * x[i] + b * y[i] + line[2]; rms += dd * dd }
        rms = sqrt(rms / n)
        var kept = 0
        for (i in 0 until n) { val dd = abs(a * x[i] + b * y[i] + line[2]); use[i] = dd <= max(1.5, 2.2 * rms); if (use[i]) kept++ }
        if (kept < min(3, n)) return null
    }
    return line
}

/** A half-size copy of a frame with an integral image, a contrast-gated threshold and component labelling (buffers reused). */
private class TSmall {
    var w = 0
    var h = 0
    var v = IntArray(0)
    private var ii = IntArray(0)
    private var cls = ByteArray(0)
    var lab = IntArray(0)
    private var stack = IntArray(0)

    fun load(f: SigilFrame) {
        w = f.w / 2; h = f.h / 2
        if (v.size != w * h) { v = IntArray(w * h); ii = IntArray((w + 1) * (h + 1)); cls = ByteArray(w * h); lab = IntArray(w * h); stack = IntArray(w * h) }
        val iw = w + 1
        val lum = f.lum
        for (y in 0 until h) {
            var r0 = f.offset + 2 * y * f.stride
            var r1 = r0 + f.stride
            var row = 0
            val o = y * w
            val io = (y + 1) * iw + 1; val ip = y * iw + 1
            for (x in 0 until w) {
                val q = ((lum[r0].toInt() and 255) + (lum[r0 + 1].toInt() and 255) + (lum[r1].toInt() and 255) + (lum[r1 + 1].toInt() and 255)) shr 2
                r0 += 2; r1 += 2
                v[o + x] = q
                lab[o + x] = 0
                row += q
                ii[io + x] = ii[ip + x] + row
            }
        }
    }

    fun classes(rad: Int, delta: Int): ByteArray {
        val out = cls
        val iw = w + 1
        val nFull = (2 * rad + 1) * (2 * rad + 1)
        val dFull = delta * nFull
        for (y in 0 until h) {
            val y0 = max(0, y - rad); val y1 = min(h, y + rad + 1)
            val inY = y - rad >= 0 && y + rad + 1 <= h
            val r0 = y0 * iw; val r1 = y1 * iw
            val row = y * w
            for (x in 0 until w) {
                if (inY && x >= rad && x + rad + 1 <= w) {
                    val x0 = x - rad; val x1 = x + rad + 1
                    val d = v[row + x] * nFull - (ii[r1 + x1] - ii[r0 + x1] - ii[r1 + x0] + ii[r0 + x0])
                    out[row + x] = if (d > dFull) 1 else if (d < -dFull) -1 else 0
                    continue
                }
                val x0 = max(0, x - rad); val x1 = min(w, x + rad + 1)
                val sum = ii[r1 + x1] - ii[r0 + x1] - ii[r1 + x0] + ii[r0 + x0]
                val n = (y1 - y0) * (x1 - x0)
                val d = v[row + x] * n - sum
                out[row + x] = if (d > delta * n) 1 else if (d < -delta * n) -1 else 0
            }
        }
        return out
    }

    class Comp(val id: Int) {
        var area = 0; var sx = 0L; var sy = 0L
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        val cx get() = sx.toFloat() / area
        val cy get() = sy.toFloat() / area
    }

    fun label(cls: ByteArray, want: Byte, lab: IntArray, firstId: Int, minArea: Int = 40): List<Comp> {
        val out = ArrayList<Comp>()
        var id = firstId
        for (start in 0 until w * h) {
            if (cls[start] != want || lab[start] != 0) continue
            val c = Comp(id)
            var sp = 0
            stack[sp++] = start
            lab[start] = id
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                c.area++; c.sx += x; c.sy += y
                if (x < c.x0) c.x0 = x; if (x > c.x1) c.x1 = x; if (y < c.y0) c.y0 = y; if (y > c.y1) c.y1 = y
                if (x > 0) { val q = p - 1; if (cls[q] == want && lab[q] == 0) { lab[q] = id; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (cls[q] == want && lab[q] == 0) { lab[q] = id; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (cls[q] == want && lab[q] == 0) { lab[q] = id; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (cls[q] == want && lab[q] == 0) { lab[q] = id; stack[sp++] = q } }
            }
            if (c.area >= minArea) out.add(c)
            id++
        }
        return out
    }
}
