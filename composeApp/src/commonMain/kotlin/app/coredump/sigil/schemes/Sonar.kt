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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ABYSS: a sonar scope in the deep. A bright bezel with compass ticks (a long one at north), range rings, a hub, and a
 * sweep beam turning clockwise once every [PERIOD] seconds with a glowing trail. Behind the beam the blips light up
 * (a ping), glow steadily for a while, then fade out before the beam comes round again. Plankton drifts in the glass,
 * bubbles rise in the corners.
 *
 * Data: 14 sectors (one 6-bit symbol each) × 6 range cells; a cell holds a blip for a 1 bit (bits XOR a scheme mask).
 * Reader: finds the bezel (ring), its 4 ticks (homography, north = the long one), the beam (its angle, and from the side
 * its trail is on: clockwise or mirrored), fits the beam's phase over frames, then reads every cell twice per turn:
 * shortly after the beam passed (blip lit, if any) and just before it comes back (always dark) – the difference,
 * split by Otsu over all 84 cells, is the bit.
 */
object Sonar : SigilScheme {
    override val name = "sonar"

    /** Debug hook (lab only). */
    internal var trace: ((String) -> Unit)? = null

    // ------------------------------------------------------------------ layout (unit square, centre 0.5 / 0.5)
    private const val BEZEL = 0.415f
    private const val BEZEL_W = 0.018f
    private const val SECT = 14
    private val RANGE = floatArrayOf(0.115f, 0.164f, 0.213f, 0.262f, 0.311f, 0.36f)
    private const val ZIG = 0.17f
    private const val BLIP = 0.0145f
    private const val HUB = 0.03f
    private const val BEAM_END = 0.38f
    private const val TRAIL = 40f
    /** Seconds per turn of the beam. */
    private const val PERIOD = 3.0f
    private const val RHO_TICK = 1.053f  // the compass ticks (outside the bezel) cross this circle (ring radii)
    private val TICKS = floatArrayOf(90f, 180f, 270f)
    private const val GAP = 5f           // half the north gap in the bezel, degrees

    // read windows (beam age in degrees)
    private const val ON_A = 50f
    private const val ON_B = 140f
    private const val OFF_A = 285f
    private const val OFF_B = 350f

    private val MASK: BooleanArray = run {
        var x = 0x50A2B33FL
        BooleanArray(SigilPayload.BITS) { x = x * 6364136223846793005L + 1442695040888963407L; ((x ushr 41) and 1L) == 1L }
    }

    /** Cell (sector s, range j) angle: degrees clockwise from north. */
    private fun cellDeg(s: Int, j: Int) = (s + 0.5f + (if (j % 2 == 0) -ZIG else ZIG)) * 360f / SECT
    private val CELL_DEG = FloatArray(84) { cellDeg(it / 6, it % 6) }
    private val CELL_X = FloatArray(84) { 0.5f + RANGE[it % 6] * sin(rad(CELL_DEG[it])) }
    private val CELL_Y = FloatArray(84) { 0.5f - RANGE[it % 6] * cos(rad(CELL_DEG[it])) }

    private fun rad(d: Float) = d * (PI.toFloat() / 180f)
    private fun wrap(d: Float): Float { val m = d % 360f; return if (m < 0) m + 360f else m }
    private fun hash(i: Int): Float { var h = i * 374761393 + 668265263; h = (h xor (h ushr 13)) * 1274126177; h = h xor (h ushr 16); return (h and 0xFFFFFF) / 16777216f }

    // ------------------------------------------------------------------ drawing

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val beam = wrap(t / PERIOD * 360f)
        val tint = Colors.hueShift(ink.alt, 22f * sin(t * 0.45f))
        val tint2 = Colors.hueShift(ink.alt, -30f + 18f * sin(t * 0.31f + 1f))
        val core = Colors.hueShift(ink.main, 10f * sin(t * 0.6f))

        // the glass: a faint glow, range rings, cross hairs
        out.glow(0.5f, 0.5f, BEZEL * 0.98f, Colors.alpha(ink.soft, 0.55f))
        for (r in floatArrayOf(0.145f, 0.245f, 0.345f)) out.circle(0.5f, 0.5f, r, Colors.alpha(ink.soft, 0.75f), 0.0026f)
        for (r in floatArrayOf(0.195f, 0.295f)) out.circle(0.5f, 0.5f, r, Colors.alpha(ink.faint, 0.9f), 0.0018f)
        for (k in 0 until 4) {
            val a = rad(45f + 90f * k)
            out.line(0.5f + 0.06f * sin(a), 0.5f - 0.06f * cos(a), 0.5f + 0.40f * sin(a), 0.5f - 0.40f * cos(a), 0.0018f, Colors.alpha(ink.faint, 0.9f))
        }
        // degree ticks just inside the bezel
        for (k in 0 until 36) {
            if (k % 9 == 0) continue
            val a = rad(k * 10f)
            val r0 = if (k % 3 == 0) 0.389f else 0.395f
            out.line(0.5f + r0 * sin(a), 0.5f - r0 * cos(a), 0.5f + 0.401f * sin(a), 0.5f - 0.401f * cos(a), 0.0022f, Colors.alpha(ink.soft, 0.8f), false)
        }

        // plankton drifting in the glass (faint: decoration only)
        for (i in 0 until 26) {
            val a0 = hash(i) * 6.2832f; val r0 = 0.05f + 0.33f * sqrt(hash(i + 99))
            val a = a0 + t * (0.03f + 0.04f * hash(i + 7)); val wob = 0.012f * sin(t * (0.7f + hash(i + 3)) + i)
            val x = 0.5f + (r0 + wob) * cos(a); val y = 0.5f + (r0 + wob) * sin(a) - 0.01f * sin(t * 0.5f + i)
            val tw = 0.5f + 0.5f * sin(t * (1.3f + hash(i + 5) * 2f) + i * 1.7f)
            out.circle(x, y, 0.0028f + 0.0022f * hash(i + 11), Colors.alpha(ink.faint, 0.35f + 0.65f * tw))
        }

        // the trail behind the beam: nested wedges, all starting at the beam (a smooth fade, no seams)
        val slices = 12
        for (k in slices downTo 1) {
            val span = TRAIL * k / slices
            val arcN = 2 + k / 2
            val pts = FloatArray(2 * (arcN + 2))
            pts[0] = 0.5f + HUB * sin(rad(beam - span)); pts[1] = 0.5f - HUB * cos(rad(beam - span))
            for (q in 0 until arcN) { val a = rad(beam - span + span * q / (arcN - 1)); pts[2 + 2 * q] = 0.5f + BEAM_END * sin(a); pts[3 + 2 * q] = 0.5f - BEAM_END * cos(a) }
            pts[2 * arcN + 2] = 0.5f + HUB * sin(rad(beam)); pts[2 * arcN + 3] = 0.5f - HUB * cos(rad(beam))
            out.poly(pts, Colors.alpha(if (k <= 3) tint else tint2, if (k <= 2) 0.16f else 0.085f))
        }

        // blips
        for (c in 0 until 84) {
            if (bits[c] == MASK[c]) continue
            val age = wrap(beam - CELL_DEG[c])
            val x = CELL_X[c]; val y = CELL_Y[c]
            when {
                age < 22f -> {
                    val k = age / 22f
                    out.glow(x, y, BLIP * (3.6f - 1.2f * k), Colors.alpha(tint, 0.9f * (1f - k)))
                    out.circle(x, y, BLIP * (1.2f - 0.2f * k), core)
                    out.circle(x, y, BLIP * (1.4f + 1.6f * k), Colors.alpha(tint, 0.8f * (1f - k)), 0.0022f)
                }
                age < 150f -> {
                    out.glow(x, y, BLIP * 2.2f, Colors.alpha(tint, 0.35f))
                    out.circle(x, y, BLIP, core)
                }
                age < 268f -> {
                    val k = 1f - (age - 150f) / 118f
                    out.glow(x, y, BLIP * 2.2f, Colors.alpha(tint, 0.35f * k))
                    out.circle(x, y, BLIP * (0.75f + 0.25f * k), Colors.alpha(core, k * k))
                }
            }
        }

        // the beam itself
        val bx = sin(rad(beam)); val by = -cos(rad(beam))
        out.line(0.5f + HUB * bx, 0.5f + HUB * by, 0.5f + BEAM_END * bx, 0.5f + BEAM_END * by, 0.0065f, Colors.alpha(core, 0.95f))
        out.glow(0.5f + BEAM_END * bx, 0.5f + BEAM_END * by, 0.02f, Colors.alpha(tint, 0.7f))

        // hub
        out.circle(0.5f, 0.5f, HUB, ink.main)
        out.circle(0.5f, 0.5f, HUB * 0.55f, ink.plate)
        out.circle(0.5f, 0.5f, HUB * 0.25f, tint)

        // bezel and compass ticks
        out.arc(0.5f, 0.5f, BEZEL, -90f + GAP, 360f - 2 * GAP, BEZEL_W, ink.main)
        tickN(out, tint)
        for (k in 1..3) {
            val a = rad(90f * k)
            val r = 0.435f
            out.rect(0.5f + r * sin(a), 0.5f - r * cos(a), 0.024f, 0.022f, ink.main, 90f * k)
        }

        // bubbles rising in the corners
        for (i in 0 until 12) {
            val q = i % 4
            val qx = if (q % 2 == 0) 0f else 1f; val qy = if (q < 2) 0f else 1f
            val ph = (t * (0.11f + 0.05f * hash(i + 40)) + hash(i + 20)) % 1f
            val dx = 0.025f + 0.09f * hash(i + 30) + 0.008f * sin(t * 2f + i)
            val x = if (qx == 0f) dx else 1f - dx
            val y0 = if (qy == 0f) 0.135f else 0.995f; val y = y0 - ph * 0.13f
            val r = 0.006f + 0.008f * hash(i + 50)
            val a = sin(ph * PI.toFloat())
            out.circle(x, y, r, Colors.alpha(tint2, 0.65f * a), 0.0022f)
            out.circle(x - r * 0.35f, y - r * 0.35f, r * 0.25f, Colors.alpha(ink.main, 0.5f * a))
        }
    }

    private fun tickN(out: Scene, c: Int) {
        // a small pointer above the north gap
        out.poly(floatArrayOf(0.5f, 0.5f - 0.43f, 0.5f + 0.013f, 0.5f - 0.462f, 0.5f - 0.013f, 0.5f - 0.462f), c)
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

    // ------------------------------------------------------------------ reader

    override fun reader(): SchemeReader = Reader()

    private class Frame(val t: Long, val beam: Float, val chi: Float, val strength: Float, val v: FloatArray)

    private class Reader : SchemeReader {
        override var seen = false
            private set
        private val finder = Finder()
        private val ring = Ring()
        private val cands = ArrayList<FloatArray>()
        private var lock: Ell? = null
        private var light = true
        private var lostAt = 0L
        private val frames = ArrayList<Frame>()
        private val tick = FloatArray(TICKS.size + 1)
        private val p = FloatArray(2)
        private val prof = FloatArray(72)
        private var lastTry = -1

        private fun reset() { frames.clear(); lock = null; lastTry = -1 }

        override fun feed(f: SigilFrame): Read? {
            seen = false
            val h = locate(f)
            if (h == null) {
                if (f.tMs - lostAt > 1500) reset()
                return null
            }
            lostAt = f.tMs
            seen = true
            val g = lock!!
            val scale = g.radius / BEZEL
            // beam in this frame (H0 frame: angles clockwise from north)
            for (i in 0 until 72) {
                val a = rad(i * 5f)
                var s = 0f
                for (r in floatArrayOf(0.145f, 0.195f, 0.245f, 0.295f, 0.345f)) {
                    Fit.apply(h, 0.5f + r * sin(a), 0.5f - r * cos(a), p)
                    s += f.at(p[0], p[1])
                }
                prof[i] = if (light) s else -s
            }
            var bi = 0; var bv = -1e9f; var wi = 0; var wv = 1e9f
            for (i in 0 until 72) {
                var behind = 0f; var ahead = 0f
                for (d in 1..5) { behind += prof[(i - d + 72) % 72]; ahead += prof[(i + d) % 72] }
                val st = (behind - ahead) / 25f
                if (st > bv) { bv = st; bi = i }
                if (st < wv) { wv = st; wi = i }
            }
            val chi = (bv + wv) / max(1f, bv - wv)       // > 0: clockwise as seen (not mirrored)
            val beamDeg = if (chi >= 0) bi * 5f else wi * 5f
            val strength = bv - wv
            // cells, both ways round
            val v = FloatArray(168)
            val rr = max(1f, BLIP * scale * 0.8f)
            for (c in 0 until 84) {
                Fit.apply(h, CELL_X[c], CELL_Y[c], p)
                v[c] = f.disc(p[0], p[1], rr).let { if (light) it else -it }
                Fit.apply(h, 1f - CELL_X[c], CELL_Y[c], p)
                v[84 + c] = f.disc(p[0], p[1], rr).let { if (light) it else -it }
            }
            frames.add(Frame(f.tMs, beamDeg, chi, strength, v))
            while (frames.isNotEmpty() && f.tMs - frames[0].t > (PERIOD * 2300).toLong()) frames.removeAt(0)
            return decode()
        }

        /** Geometry for this frame: world → image homography (mirror not yet known), or null. */
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
            if (frames.size < 8) return null
            // which way round (votes from the trail's side)
            var chiSum = 0f
            for (fr in frames) chiSum += fr.chi.coerceIn(-1f, 1f)
            val mirrored = chiSum < 0
            // the beam's phase: offset = beam(true) - 360 t / T, circular mean of the agreeing ones
            val off = FloatArray(frames.size)
            for ((i, fr) in frames.withIndex()) {
                val b = if (mirrored) wrap(-fr.beam) else fr.beam
                off[i] = wrap(b - 360f * (fr.t % (PERIOD * 1000).toLong()) / (PERIOD * 1000f))
            }
            var bestK = 0; var bestN = -1
            for (k in off.indices) { var n = 0; for (o in off) if (abs(wrap(o - off[k] + 180f) - 180f) < 12f) n++; if (n > bestN) { bestN = n; bestK = k } }
            if (bestN < frames.size * 0.6f) return null
            var sx = 0f; var sy = 0f
            for (o in off) if (abs(wrap(o - off[bestK] + 180f) - 180f) < 12f) { sx += cos(rad(o)); sy += sin(rad(o)) }
            val offset = wrap(atan2(sy, sx) * 180f / PI.toFloat())
            // per cell: lit (just after the beam) minus dark (just before it)
            val on = FloatArray(84); val nOn = IntArray(84); val dark = FloatArray(84); val nDark = IntArray(84)
            val base = if (mirrored) 84 else 0
            for (fr in frames) {
                val beam = wrap(offset + 360f * (fr.t % (PERIOD * 1000).toLong()) / (PERIOD * 1000f))
                for (c in 0 until 84) {
                    val age = wrap(beam - CELL_DEG[c])
                    if (age in ON_A..ON_B) { on[c] += fr.v[base + c]; nOn[c]++ }
                    else if (age in OFF_A..OFF_B) { dark[c] += fr.v[base + c]; nDark[c]++ }
                }
            }
            var have = 0
            for (c in 0 until 84) if (nOn[c] > 0 && nDark[c] > 0) have++
            if (have < 84) return null
            if (have == lastTry) { /* same coverage: retry only when frames change */ }
            val d = FloatArray(84) { on[it] / nOn[it] - dark[it] / nDark[it] }
            val ot = Fit.otsu(d)
            val thr = ot[0]
            var hi = 0f; var nh = 0; var lo = 0f; var nl = 0
            for (x in d) if (x > thr) { hi += x; nh++ } else { lo += x; nl++ }
            if (nh == 0 || nl == 0) return null
            val gap = hi / nh - lo / nl
            trace?.invoke("decode t=${frames.last().t} mirrored=$mirrored offset=$offset otsu=${ot[1]} gap=$gap nh=$nh")
            if (ot[1] < 0.5f || gap < 8f) return null
            val bits = BooleanArray(84) { (d[it] > thr) != MASK[it] }
            val sure = FloatArray(84) { abs(d[it] - thr) / gap * min(1f, (nOn[it] + nDark[it]) / 3f) }
            return SigilPayload.decodeBits(bits, sure)
        }
    }
}
