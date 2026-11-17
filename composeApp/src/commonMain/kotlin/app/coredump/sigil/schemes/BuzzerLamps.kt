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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * QUIZ SHOW: a big glossy buzzer in a round marquee. Sixteen bulbs ring the button and flash in hard-cut slots of 1/3 s
 * (the loop: FLASH – every bulb on –, DARK, then 7 data slots: 3 s); their halos chase round the ring, confetti tumbles
 * in the corners, sparkles twinkle, the dome gleams.
 *
 * Data: in each data slot, bulbs 1–3 + 5–7 and 9–11 + 13–15 (clockwise from the top) show two 6-bit symbols (bits XOR a
 * scheme mask). Bulb 0 (the star, the "start" lamp) and bulb 8 are a clock, lit in turn; bulb 4 is always lit and bulb
 * 12 always dark (that tells a mirrored camera image). The marquee rim has a gap at the top (under the pointer) and
 * three bolts at E / S / W: the reader's homography each frame.
 * Reader: rim → homography, each bulb's centre brightness per frame, slot phase fitted to the clock and the flash,
 * per-bulb lit / dark levels from FLASH and DARK, decode once every slot has been seen.
 */
object BuzzerLamps : SigilScheme {
    override val name = "buzzer"

    /** Debug hook (lab only). */
    internal var trace: ((String) -> Unit)? = null

    // ------------------------------------------------------------------ layout (unit square, centre 0.5 / 0.5)
    private const val BEZEL = 0.415f
    private const val BEZEL_W = 0.018f
    private const val GAP = 5f
    private const val RHO_TICK = 1.053f   // the rim's bolts (outside it) cross this circle (ring radii)
    private val TICKS = floatArrayOf(45f, 135f, 225f, 315f)
    private const val LAMPS = 16
    private const val LR = 0.345f          // bulb circle radius
    private const val BULB = 0.033f
    private const val DOME = 0.19f

    // time
    private const val SLOT = 1f / 3f
    private const val SLOTS = 9

    private val DATA_A = intArrayOf(1, 2, 3, 5, 6, 7)
    private val DATA_B = intArrayOf(9, 10, 11, 13, 14, 15)

    private val MASK: BooleanArray = run {
        var x = 0x3B0F9D21L
        BooleanArray(SigilPayload.BITS) { x = x * 6364136223846793005L + 1442695040888963407L; ((x ushr 41) and 1L) == 1L }
    }

    private fun hash(i: Int): Float { var h = i * 374761393 + 668265263; h = (h xor (h ushr 13)) * 1274126177; h = h xor (h ushr 16); return (h and 0xFFFFFF) / 16777216f }
    private fun rad(d: Float) = d * (PI.toFloat() / 180f)

    private val LX = FloatArray(LAMPS) { 0.5f + LR * sin(rad(it * 360f / LAMPS)) }
    private val LY = FloatArray(LAMPS) { 0.5f - LR * cos(rad(it * 360f / LAMPS)) }

    /** Which bulbs are lit in slot [s]. */
    private fun lit(bits: BooleanArray, s: Int, out: BooleanArray) {
        when (s) {
            0 -> { out.fill(true); out[12] = false }
            1 -> { out.fill(false); out[4] = true }
            else -> {
                val i = s - 2
                out.fill(false); out[4] = true
                out[0] = i % 2 == 0; out[8] = i % 2 == 1
                for (b in 0 until 6) {
                    out[DATA_A[b]] = bits[12 * i + b] != MASK[12 * i + b]
                    out[DATA_B[b]] = bits[12 * i + 6 + b] != MASK[12 * i + 6 + b]
                }
            }
        }
    }


    /** [c] with its saturation raised by [boost], pulled back to [c]'s lightness (the camera's contrast stays). */
    private fun vivid(c: Int, boost: Float): Int {
        val r = Colors.r(c) / 255f; val g = Colors.g(c) / 255f; val b = Colors.b(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d < 0.02f) return c
        var h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        if (h < 0) h += 360f
        val s = min(1f, d / mx * boost)
        var out = Colors.hsv(h, s, mx, Colors.a(c) / 255f)
        val want = Colors.light(c)
        repeat(8) {
            val have = Colors.light(out)
            if (abs(have - want) < 0.008f) return out
            out = if (have < want) Colors.mix(out, 0xFFFFFFFF.toInt(), min(1f, (want - have) * 1.5f)) else Colors.mix(out, 0xFF000000.toInt(), min(1f, (have - want) * 1.5f))
        }
        return out
    }

    private fun hueOf(c: Int): Float {
        val r = Colors.r(c) / 255f; val g = Colors.g(c) / 255f; val b = Colors.b(c) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d < 0.02f) return 355f
        val h = when (mx) { r -> 60f * (((g - b) / d) % 6f); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        return if (h < 0) h + 360f else h
    }

    /** The data ink that looks best lit: [Ink.alt] when it stands well off the plate, else [Ink.main]. */
    private fun lamp(ink: Ink): Int = if (abs(Colors.light(ink.alt) - Colors.light(ink.plate)) >= 0.5f) vivid(ink.alt, 1.6f) else ink.main

    // ------------------------------------------------------------------ drawing

    private val litTmp = BooleanArray(LAMPS)

    private fun star(out: Scene, x: Float, y: Float, r: Float, spin: Float, c: Int) {
        val pts = FloatArray(20)
        for (i in 0 until 10) {
            val a = rad(spin + i * 36f)
            val rr = if (i % 2 == 0) r else r * 0.48f
            pts[2 * i] = x + rr * sin(a); pts[2 * i + 1] = y - rr * cos(a)
        }
        out.poly(pts, c)
    }

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val tt = max(0f, t)
        val slot = (floor(tt / SLOT).toInt()) % SLOTS
        val inSlot = tt % SLOT
        val lit = litTmp
        lit(bits, slot, lit)
        val domeBase = vivid(ink.alt, 1.5f)
        val dome = Colors.hueShift(domeBase, 355f - hueOf(domeBase) + 12f * sin(tt * 0.6f))
        val halo = Colors.hueShift(vivid(ink.alt, 1.6f), 40f + 30f * sin(tt * 0.9f))
        val bulb = if (ink.dark) Colors.hueShift(ink.main, 8f * sin(tt * 1.3f)) else Colors.hueShift(lamp(ink), 30f * sin(tt * 0.8f))

        // stage lights: soft beams from the bottom corners (decoration, near the plate)
        out.glow(0.5f, 0.5f, 0.47f, Colors.alpha(ink.faint, 0.9f))
        out.circle(0.5f, 0.5f, 0.378f, Colors.alpha(ink.faint, 1f), 0.07f)

        // bulbs: halos (chasing), then the bulbs
        val chase = tt * 2.2f
        for (k in 0 until LAMPS) {
            if (!lit[k]) continue
            val w = 0.5f + 0.5f * cos((k / LAMPS.toFloat() - chase) * 2f * PI.toFloat())
            out.glow(LX[k], LY[k], BULB * (1.75f + 0.6f * w), Colors.alpha(halo, 0.25f + 0.45f * w * w))
        }
        for (k in 0 until LAMPS) {
            val x = LX[k]; val y = LY[k]
            if (k == 0) {
                star(out, x, y, BULB * 1.45f, 10f * sin(tt * 1.7f), if (lit[0]) bulb else ink.soft)
                continue
            }
            if (lit[k]) {
                out.circle(x, y, BULB, bulb)
                out.circle(x - BULB * 0.32f, y - BULB * 0.32f, BULB * 0.28f, Colors.alpha(Colors.mix(bulb, 0xFFFFFFFF.toInt(), 0.7f), 0.75f))
            } else {
                out.circle(x, y, BULB, ink.soft)
                out.circle(x, y, BULB * 0.82f, Colors.alpha(ink.faint, 1f), 0.004f)
            }
        }

        // the buzzer: shadow, dome, rim, gloss
        val press = if (slot == 0) 1f - min(1f, inSlot / SLOT) else 0f
        val dr = DOME * (1f - 0.04f * press)
        out.glow(0.5f, 0.52f, DOME * 1.38f, Colors.alpha(ink.soft, 0.9f))
        if (press > 0f) out.glow(0.5f, 0.5f, DOME * 1.45f, Colors.alpha(halo, 0.55f * press))
        out.circle(0.5f, 0.5f, DOME + 0.016f, ink.main)
        out.circle(0.5f, 0.5f, dr, dome)
        out.circle(0.5f, 0.5f + dr * 0.18f, dr * 0.8f, Colors.alpha(Colors.mix(dome, 0xFF000000.toInt(), 0.35f), 0.35f))
        out.circle(0.5f, 0.5f, dr * 0.78f, Colors.alpha(dome, 0.85f))
        out.oval(0.5f - dr * 0.25f, 0.5f - dr * 0.42f, dr * 0.42f, dr * 0.22f, Colors.alpha(0xFFFFFFFF.toInt(), 0.55f), -28f)
        out.oval(0.5f - dr * 0.36f, 0.5f - dr * 0.47f, dr * 0.12f, dr * 0.06f, Colors.alpha(0xFFFFFFFF.toInt(), 0.85f), -28f)
        out.circle(0.5f + dr * 0.42f, 0.5f + dr * 0.38f, dr * 0.07f, Colors.alpha(0xFFFFFFFF.toInt(), 0.35f))

        // the marquee rim: a gap at the top under the pointer, bolts at E / S / W
        out.arc(0.5f, 0.5f, BEZEL, -90f + GAP, 360f - 2 * GAP, BEZEL_W, ink.main)
        out.poly(floatArrayOf(0.5f, 0.5f - 0.43f, 0.5f + 0.014f, 0.5f - 0.464f, 0.5f - 0.014f, 0.5f - 0.464f), halo)
        for (k in 0 until 4) {
            val a = rad(45f + 90f * k)
            out.rect(0.5f + 0.435f * sin(a), 0.5f - 0.435f * cos(a), 0.026f, 0.022f, ink.main, 45f + 90f * k, 0.005f)
        }

        // confetti and sparkles in the corners
        val confA = vivid(ink.alt, 1.8f); val confM = if (ink.dark) confA else vivid(ink.main, 2f)
        for (i in 0 until 28) {
            val q = i % 4
            val left = q % 2 == 0; val top = q < 2
            val ph = (tt * (0.14f + 0.08f * hash(i + 3)) + hash(i)) % 1f
            val x0 = 0.018f + 0.115f * hash(i + 7) + 0.014f * sin(tt * 2.3f + i)
            // stay in the corner (outside the marquee)
            val x = if (left) x0 else 1f - x0
            val yy = 0.008f + ph * (0.14f - 0.6f * max(0f, x0 - 0.08f))
            val y = if (top) yy else 1f - yy
            val a = sin(ph * PI.toFloat())
            val c = Colors.hueShift(if (i % 3 == 0) confM else confA, i * 67f + tt * 40f)
            out.rect(x, y, 0.022f, 0.011f * (0.25f + 0.75f * abs(cos(tt * 4.5f + i))), Colors.alpha(c, 0.95f * a), tt * 180f * (0.5f + hash(i + 9)) + i * 40f, 0.0025f)
        }
        for (i in 0 until 8) {
            val q = i % 4
            val x = if (q % 2 == 0) 0.04f + 0.08f * hash(i + 60) else 0.96f - 0.08f * hash(i + 60)
            val y = if (q < 2) 0.04f + 0.08f * hash(i + 70) else 0.96f - 0.08f * hash(i + 70)
            val tw = sin(tt * (2f + hash(i + 80) * 2f) + i * 2.1f)
            if (tw > 0.2f) star(out, x, y, 0.014f * tw, tt * 40f, Colors.alpha(halo, tw))
        }
    }

    // ------------------------------------------------------------------ geometry kit

    /** An ellipse: p = e + L·u for u on the unit circle, L = [a b; b c] symmetric positive. */
    private class Ell(val ex: Float, val ey: Float, val a: Float, val b: Float, val c: Float) {
        fun px(ux: Float, uy: Float) = ex + a * ux + b * uy
        fun py(ux: Float, uy: Float) = ey + b * ux + c * uy
        val det: Float get() = a * c - b * b
        val radius: Float get() = sqrt(abs(det))
        fun ux(x: Float, y: Float): Float { val dx = x - ex; val dy = y - ey; return (c * dx - b * dy) / det }
        fun uy(x: Float, y: Float): Float { val dx = x - ex; val dy = y - ey; return (-b * dx + a * dy) / det }
        /** Axis ratio (minor / major). */
        val round: Float get() { val tr = a + c; val d = sqrt((a - c) * (a - c) + 4 * b * b); val l1 = (tr + d) / 2; val l2 = (tr - d) / 2; return if (l1 <= 0f) 0f else l2 / l1 }
    }

    /** Symmetric square root of the positive 2×2 [p q; q r] (null if not positive). */
    private fun sqrt2(p: Double, q: Double, r: Double): DoubleArray? {
        val det = p * r - q * q
        if (det <= 0 || p <= 0) return null
        val d = sqrt(det); val tau = sqrt(p + r + 2 * d)
        return doubleArrayOf((p + d) / tau, q / tau, (r + d) / tau)
    }

    /** Least-squares conic through the points (image px), as an ellipse; null if it is not one. */
    private fun fitEllipse(xs: FloatArray, ys: FloatArray, n: Int, ox: Float, oy: Float, s: Float): Ell? {
        if (n < 6) return null
        val m = Array(5) { DoubleArray(6) }
        val row = DoubleArray(5)
        for (i in 0 until n) {
            val x = ((xs[i] - ox) / s).toDouble(); val y = ((ys[i] - oy) / s).toDouble()
            row[0] = x * x; row[1] = x * y; row[2] = y * y; row[3] = x; row[4] = y
            for (p in 0 until 5) { for (q in 0 until 5) m[p][q] += row[p] * row[q]; m[p][5] += row[p] }
        }
        val k = Fit.solve(m) ?: return null
        val a = k[0]; val b = k[1]; val c = k[2]; val d = k[3]; val e = k[4]
        val den = 4 * a * c - b * b
        if (den <= 1e-9) return null
        val x0 = (-2 * c * d + b * e) / den; val y0 = (b * d - 2 * a * e) / den
        val f0 = a * x0 * x0 + b * x0 * y0 + c * y0 * y0 + d * x0 + e * y0 - 1
        if (f0 >= 0) return null
        // Q = [a b/2; b/2 c] / -f0 ; L = Q^(-1/2)
        val sq = sqrt2(a / -f0, b / 2 / -f0, c / -f0) ?: return null
        val dt = sq[0] * sq[2] - sq[1] * sq[1]
        if (dt <= 0) return null
        val la = sq[2] / dt; val lb = -sq[1] / dt; val lc = sq[0] / dt
        return Ell((ox + s * x0).toFloat(), (oy + s * y0).toFloat(), (s * la).toFloat(), (s * lb).toFloat(), (s * lc).toFloat())
    }

    /** Coarse search: rings (thin closed loops) lighter or darker than their surroundings, in a half-size image. */
    private class Finder {
        private var sw = 0; private var sh = 0
        private var sm = IntArray(0); private var ii = IntArray(0); private var cls = ByteArray(0)
        private var lab = IntArray(0); private var stk = IntArray(0)

        /** Candidates: [ex, ey, a, b, c, light(1/0), score] in full-res px (ellipse guess from moments). */
        fun run(f: SigilFrame, out: ArrayList<FloatArray>) {
            out.clear()
            val w = f.w / 2; val h = f.h / 2
            if (w < 40 || h < 40) return
            if (w != sw || h != sh) {
                sw = w; sh = h
                sm = IntArray(w * h); ii = IntArray((w + 1) * (h + 1)); cls = ByteArray(w * h); lab = IntArray(w * h); stk = IntArray(w * h)
            }
            val lum = f.lum
            for (y in 0 until h) {
                val r0 = f.offset + 2 * y * f.stride; val r1 = r0 + f.stride
                for (x in 0 until w) {
                    val a = r0 + 2 * x; val b = r1 + 2 * x
                    sm[y * w + x] = (lum[a].toInt() and 255) + (lum[a + 1].toInt() and 255) + (lum[b].toInt() and 255) + (lum[b + 1].toInt() and 255)
                }
            }
            val iw = w + 1
            for (y in 0 until h) {
                var rs = 0
                for (x in 0 until w) { rs += sm[y * w + x]; ii[(y + 1) * iw + x + 1] = ii[y * iw + x + 1] + rs }
            }
            val rad = max(6, min(w, h) / 16)
            val m4 = 40
            for (y in 0 until h) {
                val y0 = max(0, y - rad); val y1 = min(h, y + rad + 1)
                for (x in 0 until w) {
                    val x0 = max(0, x - rad); val x1 = min(w, x + rad + 1)
                    val area = (y1 - y0) * (x1 - x0)
                    val sum = ii[y1 * iw + x1] - ii[y0 * iw + x1] - ii[y1 * iw + x0] + ii[y0 * iw + x0]
                    val v = sm[y * w + x] * area
                    cls[y * w + x] = if (v > sum + m4 * area) 1 else if (v < sum - m4 * area) 2 else 0
                }
            }
            lab.fill(-1)
            val minD = min(w, h) * 0.26f; val maxD = min(w, h) * 1.05f
            var comps = 0
            for (start in 0 until w * h) {
                val k = cls[start]
                if (k.toInt() == 0 || lab[start] >= 0) continue
                comps++
                var sp = 0; stk[sp++] = start; lab[start] = comps
                var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
                var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
                while (sp > 0) {
                    val p = stk[--sp]
                    val x = p % w; val y = p / w
                    n++; sx += x; sy += y; sxx += x.toDouble() * x; sxy += x.toDouble() * y; syy += y.toDouble() * y
                    if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
                    val ya = max(0, y - 1); val yb = min(h - 1, y + 1); val xa = max(0, x - 1); val xb = min(w - 1, x + 1)
                    for (yy in ya..yb) for (xx in xa..xb) {
                        val q = yy * w + xx
                        if (cls[q] == k && lab[q] < 0) { lab[q] = comps; stk[sp++] = q }
                    }
                }
                val bw = bx1 - bx0 + 1; val bh = by1 - by0 + 1
                val big = max(bw, bh).toFloat(); val small = min(bw, bh).toFloat()
                if (n < 60 || big < minD || big > maxD || small / big < 0.45f) continue
                val fill = n.toFloat() / (bw * bh)
                if (fill > 0.3f) continue
                val mx = sx / n; val my = sy / n
                // hollow: the middle is not part of the loop
                if (lab[(my.toInt()) * w + mx.toInt()] == comps) continue
                // the centre must be off the loop and the centroid near the box centre
                if (abs(mx - (bx0 + bx1) / 2.0) > 0.12 * bw || abs(my - (by0 + by1) / 2.0) > 0.12 * bh) continue
                val cxx = sxx / n - mx * mx; val cxy = sxy / n - mx * my; val cyy = syy / n - my * my
                val l = sqrt2(2 * cxx, 2 * cxy, 2 * cyy) ?: continue
                out.add(floatArrayOf((2 * mx + 1).toFloat(), (2 * my + 1).toFloat(), (2 * l[0]).toFloat(), (2 * l[1]).toFloat(), (2 * l[2]).toFloat(),
                    if (k.toInt() == 1) 1f else 0f, n.toFloat() * (1f - fill) * (1f - fill)))
            }
            out.sortByDescending { it[6] }
            while (out.size > 6) out.removeAt(out.size - 1)
        }
    }

    /** Ray-based ring fitting and tick finding round one ellipse guess. */
    private class Ring {
        private val xs = FloatArray(64); private val ys = FloatArray(64)
        private val prof = FloatArray(200)
        private val pa = FloatArray(180); private val pn = FloatArray(180)
        var inliers = 0

        /** The ring's stroke along 48 rays in [lo, hi] (ring radii) round [g]; refit. */
        fun refine(f: SigilFrame, g: Ell, light: Boolean, lo: Float, hi: Float, stroke: Float, minC: Float): Ell? {
            val rays = 48
            var n = 0
            for (k in 0 until rays) {
                val ang = k * 2f * PI.toFloat() / rays
                val ux = cos(ang); val uy = sin(ang)
                val dx = g.a * ux + g.b * uy; val dy = g.b * ux + g.c * uy
                val len = sqrt(dx * dx + dy * dy)
                val steps = min(199, max(8, ((hi - lo) * len / 0.6f).toInt()))
                for (i in 0..steps) {
                    val r = lo + (hi - lo) * i / steps
                    val v = f.at(g.ex + dx * r, g.ey + dy * r)
                    prof[i] = if (light) v else -v
                }
                val side = max(2, (2.2f * stroke * steps / (hi - lo)).toInt())
                var best = -1; var bestC = 0f
                for (i in 1 until steps) {
                    if (prof[i] < prof[i - 1] || prof[i] < prof[i + 1]) continue
                    val c = prof[i] - max(prof[max(0, i - side)], prof[min(steps, i + side)])
                    if (c > bestC) { bestC = c; best = i }
                }
                if (best < 0 || bestC < minC) continue
                val y0 = prof[best - 1]; val y1 = prof[best]; val y2 = prof[best + 1]
                val dd = y0 - 2 * y1 + y2
                val off = if (dd < 0) (0.5f * (y0 - y2) / dd).coerceIn(-0.5f, 0.5f) else 0f
                val r = lo + (hi - lo) * (best + off) / steps
                xs[n] = g.ex + dx * r; ys[n] = g.ey + dy * r; n++
            }
            if (n < 28) return null
            var e = fitEllipse(xs, ys, n, g.ex, g.ey, g.radius) ?: return null
            val res = FloatArray(64)
            repeat(3) {
                for (i in 0 until n) res[i] = abs(sqrt(e.ux(xs[i], ys[i]).let { it * it } + e.uy(xs[i], ys[i]).let { it * it }) - 1f)
                val sorted = res.copyOf(n); sorted.sort()
                val lim = max(0.01f, 3f * sorted[n / 2])
                var m = 0
                for (i in 0 until n) if (res[i] <= lim) { xs[m] = xs[i]; ys[m] = ys[i]; m++ }
                if (m < 24) return null
                if (m == n) { inliers = n; return e }
                n = m
                e = fitEllipse(xs, ys, n, e.ex, e.ey, e.radius) ?: return null
            }
            inliers = n
            return e
        }

        private fun sample(f: SigilFrame, g: Ell, rho: Float, ang: Float, light: Boolean): Float {
            val ux = cos(ang); val uy = sin(ang)
            var s = 0f
            for (d in -1..1) { val r = rho + d * 0.012f; s += f.at(g.px(ux * r, uy * r), g.py(ux * r, uy * r)) }
            return if (light) s / 3 else -s / 3
        }

        /**
         * North = the gap in the ring; then one tick near each of [deg] (degrees clockwise from north) on the circle of
         * [rho] ring radii (outside > 1, inside < 1). Angles (radians, [g]'s normalised frame, clockwise = increasing)
         * into [out] (north first); returns the gap's depth, 0 if the pattern is not there.
         */
        fun ticks(f: SigilFrame, g: Ell, light: Boolean, rho: Float, deg: FloatArray, minC: Float, out: FloatArray): Float {
            val k = 180
            for (i in 0 until k) { val a = i * 2f * PI.toFloat() / k; pa[i] = sample(f, g, rho, a, light); pn[i] = sample(f, g, 1f, a, light) }
            val sa = pa.copyOf(); sa.sort(); val ba = sa[k / 2]
            val sn = pn.copyOf(); sn.sort(); val bn = sn[k / 2]
            // the gap: the deepest dip of the ring, and the only one
            var ni = 0
            for (i in 1 until k) if (pn[i] < pn[ni]) ni = i
            val depth = bn - pn[ni]
            if (depth < minC || depth < 0.45f * (bn - ba)) return 0f
            for (i in 0 until k) {
                val d = min((i - ni + k) % k, (ni - i + k) % k)
                if (d > 6 && bn - pn[i] > 0.5f * depth) return 0f
            }
            run {
                var s = 0f; var sw = 0f
                for (d in -5..5) { val i = (ni + d + k) % k; val w = max(0f, bn - pn[i] - 0.3f * depth); s += w * d; sw += w }
                out[0] = (ni + (if (sw > 0) s / sw else 0f)) * 2f * PI.toFloat() / k
            }
            for ((q, dq) in deg.withIndex()) {
                val c0 = ni + (dq / 2f).toInt()
                var bi = -1; var bv = -1e9f; var bd = 0
                for (d in -13..13) { val i = ((c0 + d) % k + k) % k; if (pa[i] > bv) { bv = pa[i]; bi = i; bd = d } }
                // a real peak inside the window, standing well off the plate
                if (abs(bd) > 10 || bv - ba < max(minC, 0.25f * depth)) return 0f
                out[q + 1] = centroid(pa, bi, ba, k)
            }
            return depth
        }

        private fun centroid(p: FloatArray, i0: Int, base: Float, k: Int): Float {
            var s = 0f; var sw = 0f
            for (d in -3..3) { val i = (i0 + d + k) % k; val w = max(0f, p[i] - base); s += w * d; sw += w }
            val off = if (sw > 0) s / sw else 0f
            return (i0 + off) * 2f * PI.toFloat() / k
        }
    }

    /**
     * Homography (world unit square → image) from the north gap and the ticks at [deg] on the ring of world radius [r]
     * round (0.5, 0.5); tick angles were measured on the circle of [rho] ring radii. Least squares over all points.
     */
    private fun tickHomography(g: Ell, ang: FloatArray, deg: FloatArray, r: Float, rho: Float): DoubleArray? {
        val n = deg.size + 1
        val src = FloatArray(2 * n); val dst = FloatArray(2 * n)
        src[0] = 0.5f; src[1] = 0.5f - r
        for ((q, dq) in deg.withIndex()) { val a = dq * PI.toFloat() / 180f; src[2 * q + 2] = 0.5f + r * sin(a); src[2 * q + 3] = 0.5f - r * cos(a) }
        for (q in 0 until n) { dst[2 * q] = g.px(cos(ang[q]), sin(ang[q])); dst[2 * q + 1] = g.py(cos(ang[q]), sin(ang[q])) }
        var h = dlt(src, dst, n, g) ?: return null
        // ticks point at the true centre, not the ellipse's: slide each point along the line from it, onto the ring
        val p = FloatArray(2)
        Fit.apply(h, 0.5f, 0.5f, p)
        val cx = g.ux(p[0], p[1]); val cy = g.uy(p[0], p[1])
        for (q in 0 until n) {
            val rq = if (q == 0) 1f else rho
            val dx = rq * cos(ang[q]) - cx; val dy = rq * sin(ang[q]) - cy
            val a = dx * dx + dy * dy; val b = cx * dx + cy * dy; val c = cx * cx + cy * cy - 1f
            val disc = b * b - a * c
            if (a <= 0f || disc <= 0f) return null
            val tt = (-b + sqrt(disc)) / a
            val ux = cx + tt * dx; val uy = cy + tt * dy
            dst[2 * q] = g.px(ux, uy); dst[2 * q + 1] = g.py(ux, uy)
        }
        h = dlt(src, dst, n, g) ?: return null
        return h
    }

    /** Least-squares homography src → dst over [n] points (image side normalised by [g] for conditioning). */
    private fun dlt(src: FloatArray, dst: FloatArray, n: Int, g: Ell): DoubleArray? {
        val s = g.radius.toDouble(); val ox = g.ex.toDouble(); val oy = g.ey.toDouble()
        val m = Array(8) { DoubleArray(9) }
        val row = DoubleArray(8)
        for (i in 0 until n) {
            val x = src[2 * i].toDouble(); val y = src[2 * i + 1].toDouble()
            val u = (dst[2 * i] - ox) / s; val v = (dst[2 * i + 1] - oy) / s
            for (e in 0 until 2) {
                row.fill(0.0)
                val w: Double
                if (e == 0) { row[0] = x; row[1] = y; row[2] = 1.0; row[6] = -u * x; row[7] = -u * y; w = u }
                else { row[3] = x; row[4] = y; row[5] = 1.0; row[6] = -v * x; row[7] = -v * y; w = v }
                for (p in 0 until 8) { for (q in 0 until 8) m[p][q] += row[p] * row[q]; m[p][8] += row[p] * w }
            }
        }
        val k = Fit.solve(m) ?: return null
        // back to image px: [s 0 ox; 0 s oy; 0 0 1] · Hn
        return doubleArrayOf(s * k[0] + ox * k[6], s * k[1] + ox * k[7], s * k[2] + ox,
            s * k[3] + oy * k[6], s * k[4] + oy * k[7], s * k[5] + oy, k[6], k[7], 1.0)
    }

    /**
     * Frames of a looped, slotted signal (hard cuts every [slotMs]): per frame the units' values, a clock signal and
     * a "global" signal with known expected signs per slot. Fits the loop's phase and gives each slot's mean values.
     */
    private class Clocked(val units: Int, val slotMs: Float, val slots: Int, val clockExp: FloatArray, val globExp: FloatArray) {
        private val cap = 160
        private val ts = LongArray(cap); private val vs = Array(cap) { FloatArray(units) }
        private val cs = FloatArray(cap); private val gs = FloatArray(cap)
        private var n = 0
        private val loopMs = slotMs * slots
        var phase = 0f; var conf = 0f

        fun clear() { n = 0 }
        val size: Int get() = n
        fun span(): Long = if (n < 2) 0L else ts[n - 1] - ts[0]

        fun add(t: Long, v: FloatArray, clock: Float, glob: Float) {
            // keep ~2.3 loops
            var drop = 0
            while (drop < n && t - ts[drop] > (loopMs * 2.3f).toLong()) drop++
            if (drop > 0 || n == cap) {
                val d = max(drop, if (n == cap) 1 else 0)
                for (i in d until n) { ts[i - d] = ts[i]; cs[i - d] = cs[i]; gs[i - d] = gs[i]; val tmp = vs[i - d]; vs[i - d] = vs[i]; vs[i] = tmp }
                n -= d
            }
            ts[n] = t; cs[n] = clock; gs[n] = glob; v.copyInto(vs[n]); n++
        }

        private fun slotOf(t: Long, ph: Float): Int {
            val x = ((t - ph) % loopMs + loopMs) % loopMs
            val o = x % slotMs
            if (o < MARGIN || o > slotMs - MARGIN) return -1
            return (x / slotMs).toInt()
        }

        /** Fits the phase (ms, 5 ms steps); [conf] = best score over the best one at least half a slot away. */
        fun fit() {
            if (n < 4) { conf = 0f; return }
            var mc = 0f; var mg = 0f
            for (i in 0 until n) { mc += cs[i]; mg += gs[i] }
            mc /= n; mg /= n
            var sc = 0f; var sg = 0f
            for (i in 0 until n) { sc += (cs[i] - mc) * (cs[i] - mc); sg += (gs[i] - mg) * (gs[i] - mg) }
            sc = sqrt(sc / n) + 1e-3f; sg = sqrt(sg / n) + 1e-3f
            val steps = (loopMs / 5f).toInt()
            val score = FloatArray(steps)
            for (k in 0 until steps) {
                val ph = k * 5f
                var s = 0f
                for (i in 0 until n) {
                    val sl = slotOf(ts[i], ph)
                    if (sl < 0) continue
                    s += (cs[i] - mc) / sc * clockExp[sl] + (gs[i] - mg) / sg * globExp[sl]
                }
                score[k] = s
            }
            var bk = 0
            for (k in 1 until steps) if (score[k] > score[bk]) bk = k
            var second = 0f
            val far = (slotMs / 2 / 5f).toInt()
            for (k in 0 until steps) {
                val d = min((k - bk + steps) % steps, (bk - k + steps) % steps)
                if (d >= far && score[k] > second) second = score[k]
            }
            // centre of the plateau of best scores
            var lo = bk; var hi = bk
            while (score[(lo - 1 + steps) % steps] >= score[bk] - 1e-3f && (bk - lo) < steps / 4) lo--
            while (score[(hi + 1) % steps] >= score[bk] - 1e-3f && (hi - bk) < steps / 4) hi++
            phase = ((lo + hi) / 2f * 5f + loopMs) % loopMs
            conf = if (second <= 0f) 9f else score[bk] / second
        }

        /** Mean values per slot (with the fitted phase) into [mean] (slots × units); counts into [cnt]. */
        fun means(mean: Array<FloatArray>, cnt: IntArray) {
            for (s in 0 until slots) { mean[s].fill(0f); cnt[s] = 0 }
            for (i in 0 until n) {
                val sl = slotOf(ts[i], phase)
                if (sl < 0) continue
                val m = mean[sl]; val v = vs[i]
                for (u in 0 until units) m[u] += v[u]
                cnt[sl]++
            }
            for (s in 0 until slots) if (cnt[s] > 0) for (u in 0 until units) mean[s][u] /= cnt[s]
        }

        companion object { const val MARGIN = 48f }
    }

    // ------------------------------------------------------------------ reader

    override fun reader(): SchemeReader = Reader()

    private val CLOCK_EXP = FloatArray(SLOTS) { s -> if (s < 2) 0f else if ((s - 2) % 2 == 0) 1f else -1f }
    private val GLOB_EXP = FloatArray(SLOTS) { s -> if (s == 0) 1f else if (s == 1) -1f else 0f }

    private class Reader : SchemeReader {
        override var seen = false
            private set
        private val finder = Finder()
        private val ring = Ring()
        private val cands = ArrayList<FloatArray>()
        private var lock: Ell? = null
        private var light = true
        private var lostAt = 0L
        private val tick = FloatArray(TICKS.size + 1)
        private val p = FloatArray(2)
        private val v = FloatArray(LAMPS)
        private val clk = Clocked(LAMPS, SLOT * 1000f, SLOTS, CLOCK_EXP, GLOB_EXP)
        private val mean = Array(SLOTS) { FloatArray(LAMPS) }
        private val cnt = IntArray(SLOTS)
        private var lastKey = 0L
        private var chi = 0f

        override fun feed(f: SigilFrame): Read? {
            seen = false
            val h = locate(f)
            if (h == null) {
                if (f.tMs - lostAt > 1500) { clk.clear(); lock = null; chi = 0f }
                return null
            }
            lostAt = f.tMs
            seen = true
            val scale = lock!!.radius / BEZEL
            val rr = max(1f, BULB * scale * 0.55f)
            for (k in 0 until LAMPS) {
                Fit.apply(h, LX[k], LY[k], p)
                val s = f.disc(p[0], p[1], rr)
                v[k] = if (light) s else -s
            }
            // bulb 4 always lit, bulb 12 always dark: which way round the homography is
            chi += (v[4] - v[12]).coerceIn(-60f, 60f)
            var g = 0f
            for (k in DATA_A) g += v[k]
            for (k in DATA_B) g += v[k]
            clk.add(f.tMs, v, v[0] - v[8], g / 12f)
            return decode()
        }

        private fun locate(f: SigilFrame): DoubleArray? {
            lock?.let { g ->
                val e = ring.refine(f, g, light, 0.9f, 1.1f, BEZEL_W / BEZEL, 10f)
                if (e != null) { val h = verify(f, e, light); if (h != null) { lock = e; return h } }
                lock = null
            }
            finder.run(f, cands)
            for (c in cands) {
                val lt = c[5] > 0.5f
                val g0 = Ell(c[0], c[1], c[2], c[3], c[4])
                val e1 = ring.refine(f, g0, lt, 0.8f, 1.2f, BEZEL_W / BEZEL, 10f) ?: continue
                val e2 = ring.refine(f, e1, lt, 0.92f, 1.08f, BEZEL_W / BEZEL, 10f) ?: continue
                val h = verify(f, e2, lt) ?: continue
                lock = e2; light = lt
                return h
            }
            return null
        }

        private fun verify(f: SigilFrame, e: Ell, lt: Boolean): DoubleArray? {
            if (e.round < 0.45f || e.radius < 30f) return null
            if (ring.ticks(f, e, lt, RHO_TICK, TICKS, 10f, tick) <= 0f) return null
            return tickHomography(e, tick, TICKS, BEZEL, RHO_TICK)
        }

        private fun decode(): Read? {
            if (clk.span() < SLOT * 1000 * (SLOTS - 0.5f)) return null
            clk.fit()
            if (clk.conf < 1.3f) return null
            clk.means(mean, cnt)
            for (s in 0 until SLOTS) if (cnt[s] == 0) return null
            if (abs(chi) < 40f) return null
            val mirrored = chi < 0
            var key = 0L
            for (s in 0 until SLOTS) key = key * 31 + cnt[s]
            key = key * 31 + clk.phase.toLong()
            key = key * 2 + (if (mirrored) 1 else 0)
            if (key == lastKey) return null
            lastKey = key
            val on = mean[0]; val off = mean[1]
            val cs = FloatArray(LAMPS) { on[it] - off[it] }
            val cmed = run { val c = FloatArray(12); var j = 0; for (k in DATA_A) c[j++] = cs[k]; for (k in DATA_B) c[j++] = cs[k]; c.sort(); c[6] }
            if (cmed < 3f) return null
            val bits = BooleanArray(84); val sure = FloatArray(84)
            for (i in 0 until 7) {
                val m = mean[2 + i]
                for (b in 0 until 12) {
                    val lamp0 = if (b < 6) DATA_A[b] else DATA_B[b - 6]
                    val lamp = if (mirrored) (LAMPS - lamp0) % LAMPS else lamp0
                    val c = cs[lamp]
                    val x = if (c > 0.25f * cmed) (m[lamp] - off[lamp]) / c else 0.5f
                    val idx = 12 * i + b
                    bits[idx] = (x > 0.5f) != MASK[idx]
                    sure[idx] = min(1f, abs(x - 0.5f) * 2f) * min(1f, cnt[2 + i] / 2f + 0.25f)
                }
            }
            trace?.invoke("decode phase=${clk.phase} conf=${clk.conf} chi=$chi cnt=${cnt.joinToString()} cmed=$cmed sure=${sure.sorted().take(6).joinToString()}")
            return SigilPayload.decodeBits(bits, sure)
        }
    }
}
