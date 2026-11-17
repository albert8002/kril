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
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * COMIC: a punk-collage "KAPOW!". A jagged impact starburst with a bold outline, a flash-yellow core and Ben-Day dots,
 * on a slanted panel with halftone corners, impact lines and ransom-note words. The burst throbs and wobbles and
 * punches through three shapes in turn (ZAP! POW! BAM!), each landing with a pop.
 *
 * Code: 32 spike places round the burst. Every shape has a wide notch (two missing spikes) as the start, one more
 * missing spike that says which shape it is (and, being on one side, which way round the burst reads), and 29 spikes
 * that are long (1) or short (0): 28 bits + one always-long check spike. Three shapes = 84 bits; ~0.8 s each.
 *
 * Reader: own half-resolution threshold + labelling → the core (a round paper-coloured hole ringed by thick ink) →
 * ellipse fit at full resolution → affine "burst frame" → spike phase from the ring of spike roots → the gaps (start,
 * shape, flip) → each spike's ink between the short and long tip radii. Shapes are kept (soft) until all three are in.
 */
object Kapow : SigilScheme {
    override val name = "kapow"

    /** Diagnostics hook for the bench (null in the app). */
    var debug: ((String) -> Unit)? = null
    private inline fun dbg(msg: () -> String) { debug?.let { it(msg()) } }

    // ---------------------------------------------------------------- geometry (burst frame: centre 0, unit = square side)
    private const val SLOTS = 32
    private const val PITCH = 360f / SLOTS
    private const val RC = 0.115f        // core (paper) radius
    private const val RV = 0.205f        // spike roots
    private const val SHORT = 0.275f     // short tip
    private const val LONG = 0.41f       // long tip
    private const val OUTLINE = 0.016f
    private const val SHAPES = 3
    private const val HOLD = 0.8f        // seconds per shape
    private const val PER = 28           // data spikes per shape

    /** The shape-flag gap of shape [s]. */
    private fun flagSlot(s: Int) = 9 + 8 * s

    /** Data slots of shape [s] (design order), then the always-long check slot last. */
    private val DATA: Array<IntArray> = Array(SHAPES) { s -> (2 until SLOTS).filter { it != flagSlot(s) }.toIntArray() }

    /** Scheme-specific whitening of the 84 bits. */
    private val MASK = BooleanArray(SigilPayload.BITS).also {
        var x = 0x4B4F5721
        for (i in it.indices) { x = x * 1103515245 + 12345; it[i] = (x ushr 16) and 1 == 1 }
    }

    /** Tip radius of every slot for shape [s] (0 = no spike). */
    private fun tips(bits: BooleanArray, s: Int): FloatArray {
        val r = FloatArray(SLOTS)
        val d = DATA[s]
        for ((j, k) in d.withIndex()) {
            val one = if (j < PER) { val i = s * PER + j; bits[i] != MASK[i] } else true
            r[k] = if (one) LONG else SHORT
        }
        return r
    }

    // ---------------------------------------------------------------- drawing

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** A colour of hue [hue] (saturation [sat]) with the lightness of [base]. */
    private fun tint(base: Int, hue: Float, sat: Float): Int {
        val want = Colors.light(base)
        val top = Colors.hsv(hue, sat, 1f)
        val out = if (Colors.light(top) >= want) {
            var lo = 0f; var hi = 1f
            repeat(14) { val m = (lo + hi) / 2; if (Colors.light(Colors.hsv(hue, sat, m)) < want) lo = m else hi = m }
            Colors.hsv(hue, sat, (lo + hi) / 2)
        } else {
            var lo = 0f; var hi = 1f
            repeat(14) { val m = (lo + hi) / 2; if (Colors.light(Colors.mix(top, WHITE, m)) < want) lo = m else hi = m }
            Colors.mix(top, WHITE, (lo + hi) / 2)
        }
        return Colors.alpha(out, Colors.a(base) / 255f)
    }

    private fun hash(i: Int): Float { var x = i * 0x27d4eb2d + 0x165667b1; x = x xor (x ushr 15); x *= 0x2c1b3c6d; x = x xor (x ushr 12); return (x and 0xFFFF) / 65535f }

    private const val DEG = PI.toFloat() / 180f

    /** Glyph strokes (polylines in a 1 × 1 box, y down), NaN separates strokes. */
    private val GLYPHS: Map<Char, FloatArray> = run {
        val n = Float.NaN
        mapOf(
            'Z' to floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            'A' to floatArrayOf(0f, 1f, 0.5f, 0f, 1f, 1f, n, n, 0.22f, 0.64f, 0.78f, 0.64f),
            'P' to floatArrayOf(0f, 1f, 0f, 0f, 0.75f, 0f, 1f, 0.2f, 1f, 0.36f, 0.75f, 0.56f, 0f, 0.56f),
            'O' to floatArrayOf(0.2f, 0f, 0.8f, 0f, 1f, 0.22f, 1f, 0.78f, 0.8f, 1f, 0.2f, 1f, 0f, 0.78f, 0f, 0.22f, 0.2f, 0f),
            'W' to floatArrayOf(0f, 0f, 0.22f, 1f, 0.5f, 0.38f, 0.78f, 1f, 1f, 0f),
            'B' to floatArrayOf(0f, 0f, 0f, 1f, 0.78f, 1f, 1f, 0.8f, 1f, 0.68f, 0.78f, 0.5f, 0f, 0.5f, n, n, 0f, 0f, 0.72f, 0f, 0.92f, 0.16f, 0.92f, 0.32f, 0.72f, 0.5f),
            'M' to floatArrayOf(0f, 1f, 0f, 0f, 0.5f, 0.55f, 1f, 0f, 1f, 1f),
            '!' to floatArrayOf(0.5f, 0f, 0.5f, 0.62f, n, n, 0.5f, 0.9f, 0.5f, 0.93f),
        )
    }

    /** One ransom-note letter: a turned paper scrap with the glyph on it. */
    private fun letter(out: Scene, ch: Char, x: Float, y: Float, size: Float, turn: Float, scrap: Int, inkC: Int, edge: Int) {
        val g = GLYPHS[ch] ?: return
        val w = size * 0.86f; val h = size
        out.rect(x + size * 0.06f, y + size * 0.07f, w, h, Colors.alpha(BLACK, 0.25f), turn)
        out.rect(x, y, w, h, scrap, turn)
        out.rect(x, y, w, h, edge, turn, stroke = size * 0.05f)
        val c = cos(turn * DEG); val s = sin(turn * DEG)
        val gw = w * 0.56f; val gh = h * 0.62f
        var px = Float.NaN; var py = Float.NaN
        var i = 0
        while (i < g.size) {
            val gx = g[i]; val gy = g[i + 1]
            if (gx.isNaN()) { px = Float.NaN; i += 2; continue }
            val lx = (gx - 0.5f) * gw; val ly = (gy - 0.5f) * gh
            val qx = x + lx * c - ly * s; val qy = y + lx * s + ly * c
            if (!px.isNaN()) out.line(px, py, qx, qy, size * 0.13f, inkC)
            px = qx; py = qy
            i += 2
        }
    }

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val p = ink.plate; val main = ink.main; val alt = ink.alt
        val dark = ink.dark
        // the pop colour: three quarters of the way from the plate to the ink (ink side), hue from the look
        val fillBase = Colors.mix(p, alt, 0.76f)
        val fill = tint(fillBase, if (dark) 46f else 356f, 0.95f)
        val core = tint(Colors.mix(p, main, 0.1f), if (dark) 350f else 50f, 0.9f)
        val flash = if (dark) Colors.mix(p, BLACK, 0.4f) else Colors.mix(p, WHITE, 0.9f)
        val dotC = tint(Colors.mix(p, main, 0.2f), 192f, 0.75f)
        val dotC2 = tint(Colors.mix(p, main, 0.16f), 330f, 0.6f)

        val shape = (floor(t / HOLD).toInt() % SHAPES + SHAPES) % SHAPES
        val tau = t - floor(t / HOLD) * HOLD          // time since this shape landed
        val tip = tips(bits, shape)

        // panel: halftone corners, impact lines, slanted border
        val step = 0.045f
        var gy = -1
        while (gy <= 23) {
            var gx = -1
            while (gx <= 23) {
                val x = gx * step + (if (gy % 2 == 0) 0f else step / 2); val y = gy * step * 0.87f
                val dx = x - 0.5f; val dy = y - 0.5f
                val r = sqrt(dx * dx + dy * dy)
                if (r > 0.46f) {
                    val rr = min(0.019f, (r - 0.44f) * 0.08f + 0.004f) * (0.9f + 0.1f * sin(t * 3f + r * 20f))
                    out.circle(x, y, rr, if ((gx + gy) % 5 == 0) dotC2 else dotC)
                }
                gx++
            }
            gy++
        }
        val lines = 20
        for (i in 0 until lines) {
            val a = (i * 360f / lines + 7f * hash(i) + t * 4f) * DEG
            val r0 = 0.47f + 0.05f * ((t * 1.3f + hash(i * 3)) % 1f)
            val r1 = 0.78f
            val w = 0.012f + 0.01f * hash(i * 7)
            val ca = cos(a); val sa = sin(a); val nx = -sa; val ny = ca
            out.poly(floatArrayOf(0.5f + ca * r0, 0.5f + sa * r0, 0.5f + ca * r1 + nx * w, 0.5f + sa * r1 + ny * w, 0.5f + ca * r1 - nx * w, 0.5f + sa * r1 - ny * w), main)
        }
        out.poly(floatArrayOf(0.022f, 0.045f, 0.975f, 0.018f, 0.965f, 0.972f, 0.035f, 0.985f), main, 0.014f)

        // the burst's frame: throb, wobble, squash, and a pop when a new shape lands
        val pop = 0.075f * exp(-tau / 0.07f)
        val sc = 1f + 0.02f * sin(t * 2.1f * 2 * PI.toFloat()) + pop
        val turn = (3.5f * sin(t * 0.7f * 2 * PI.toFloat()) + floatArrayOf(0f, 7f, -6f)[shape] - 14f * exp(-tau / 0.09f)) * DEG
        val sq = 1f + 0.03f * sin(t * 2.1f * 2 * PI.toFloat() + 1.2f)
        val ct = cos(turn); val st = sin(turn)
        val ax = sc * sq; val ay = sc / sq
        fun bx(u: Float, v: Float) = 0.5f + (u * ax) * ct - (v * ay) * st
        fun by(u: Float, v: Float) = 0.5f + (u * ax) * st + (v * ay) * ct

        // burst outline polygon
        val pts = ArrayList<Float>(200)
        fun add(r: Float, deg: Float) { val a = deg * DEG; val u = r * cos(a); val v = r * sin(a); pts.add(bx(u, v)); pts.add(by(u, v)) }
        for (k in 0 until SLOTS) {
            val th = k * PITCH
            val rv = RV * (1f + 0.035f * (hash(k * 5 + 1) - 0.5f))
            add(rv, th - PITCH / 2)
            if (tip[k] > 0f) add(tip[k], th + 1.2f * (hash(k * 9 + 2) - 0.5f))
            else { add(RV, th - PITCH / 4); add(RV, th); add(RV, th + PITCH / 4) }
        }
        val poly = pts.toFloatArray()
        out.poly(poly, fill)
        out.poly(poly, main, OUTLINE)
        // Ben-Day dots on the body ring (ink on ink)
        for (ring in 0 until 2) {
            val r = 0.145f + ring * 0.033f
            val n = 18 + ring * 6
            for (i in 0 until n) {
                val a = (i + 0.5f * ring) * 360f / n
                val u = r * cos(a * DEG); val v = r * sin(a * DEG)
                out.circle(bx(u, v), by(u, v), 0.0085f * sc, main)
            }
        }
        // the core: flash yellow, a turning white star, speed sparks
        out.oval(0.5f, 0.5f, RC * ax, RC * ay, core, turn / DEG)
        val spin = t * 40f
        val star = FloatArray(32)
        for (i in 0 until 16) {
            val r = if (i % 2 == 0) 0.088f else 0.045f
            val a = (spin + i * 22.5f) * DEG
            star[2 * i] = bx(r * cos(a), r * sin(a)); star[2 * i + 1] = by(r * cos(a), r * sin(a))
        }
        out.poly(star, flash)
        out.oval(0.5f, 0.5f, 0.03f * ax, 0.03f * ay, core, turn / DEG)

        // ransom-note word for this shape, popping in at its corner
        val word = arrayOf("ZAP!", "POW!", "BAM!")[shape]
        val corner = floatArrayOf(225f, 45f, 315f)[shape]
        val grow = min(1f, tau / 0.12f)
        val wsize = 0.088f * (0.6f + 0.4f * grow + 0.18f * sin(min(1f, tau / 0.25f) * PI.toFloat()))
        val scraps = intArrayOf(main, fill, if (dark) Colors.mix(p, WHITE, 0.15f) else WHITE, core)
        val inks = intArrayOf(if (dark) p else WHITE, main, main, main)
        val dxw = cos(corner * DEG); val dyw = sin(corner * DEG)
        for ((i, ch) in word.withIndex()) {
            val along = (i - 1.5f) * 0.078f * (if (-dyw < 0f) -1f else 1f)
            val r = 0.5f + 0.01f * sin(t * 6f + i)
            val x = 0.5f + dxw * r - dyw * along; val y = 0.5f + dyw * r + dxw * along
            val tw = (hash(i * 11 + shape * 3) - 0.5f) * 36f + 4f * sin(t * 5f + i)
            val k = (i + shape) % 4
            letter(out, ch, x, y, wsize, tw, scraps[k], inks[k], main)
        }
    }

    // ---------------------------------------------------------------- reading

    override fun reader(): SchemeReader = BurstReader()

    /** Burst frame → image: centre ([x0], [y0]) + [l] (2 × 2, row-major) · (u, v) / RC. */
    private class Aff(val x0: Float, val y0: Float, val l: FloatArray) {
        val det = l[0] * l[3] - l[1] * l[2]
        fun map(u: Float, v: Float, out: FloatArray) { val a = u / RC; val b = v / RC; out[0] = x0 + l[0] * a + l[1] * b; out[1] = y0 + l[2] * a + l[3] * b }
        /** Pixels per burst unit (mean). */
        val scale get() = sqrt(abs(det)) / RC
    }

    private class BurstReader : SchemeReader {
        override var seen = false
            private set
        private val tmp = FloatArray(2)
        private val soft = Array(SHAPES) { FloatArray(PER) }
        private val heardAt = LongArray(SHAPES) { Long.MIN_VALUE / 4 }
        private val weight = FloatArray(SHAPES)
        private var trackX = 0f; private var trackY = 0f; private var trackR = 0f; private var trackLight = false; private var tracking = false

        override fun feed(f: SigilFrame): Read? {
            seen = false
            if (f.w < 64 || f.h < 64) return null
            var aff: Aff? = null
            var coreLight = false
            if (tracking) {
                aff = fitCore(f, trackX, trackY, trackR, trackLight)
                coreLight = trackLight
                if (aff == null) tracking = false
            }
            if (aff == null) {
                val half = Half(f)
                val cls = half.classify(16, 24)
                half.label(cls)
                val hmin = 7; val hmax = (min(half.w, half.h) * 0.36f).toInt()
                val cores = half.comps.filter { c ->
                    val bw = c[4] - c[2] + 1; val bh = c[5] - c[3] + 1
                    val big = max(bw, bh)
                    big in hmin..hmax && min(bw, bh) >= big * 0.5f && c[1] >= 0.5f * bw * bh && c[1] >= 30
                }.sortedByDescending { it[1] }.take(6)
                if (cores.isEmpty()) dbg { "no cores" }
                for (c in cores) {
                    val light = c[0] == 2
                    val cx = c[6].toFloat() / c[1] * 2f + 0.5f; val cy = c[7].toFloat() / c[1] * 2f + 0.5f
                    val r = sqrt(c[1] / PI.toFloat()) * 2f
                    val a = fitCore(f, cx, cy, r, light) ?: continue
                    aff = a; coreLight = light
                    break
                }
            }
            val a = aff ?: return null
            val shape = readBurst(f, a, if (coreLight) -1f else 1f)
            if (shape < -1) { tracking = false; return null }
            seen = true
            tracking = true
            trackX = a.x0; trackY = a.y0; trackR = sqrt(abs(a.det)); trackLight = coreLight
            if (shape < 0) return null
            // all three shapes heard recently: decode
            val now = f.tMs
            for (s in 0 until SHAPES) if (now - heardAt[s] > 6000) return null
            val bitsOut = BooleanArray(SigilPayload.BITS); val sure = FloatArray(SigilPayload.BITS)
            for (s in 0 until SHAPES) for (j in 0 until PER) {
                val i = s * PER + j
                bitsOut[i] = (soft[s][j] > 0f) != MASK[i]
                sure[i] = abs(soft[s][j])
            }
            val got = SigilPayload.decodeBits(bitsOut, sure)
            if (got == null) dbg { "decode fail" }
            return got
        }

        /**
         * The core's edge along 40 rays from (cx, cy) (paper core → ink body) → least-squares conic → the burst's
         * affine frame; null if it is not a paper disc ringed by ink.
         */
        private fun fitCore(f: SigilFrame, cx: Float, cy: Float, r: Float, light: Boolean): Aff? {
            if (r < 6f) return null
            val sg = if (light) 1f else -1f      // paper-ness
            // core and body levels
            val core = sg * f.disc(cx, cy, r * 0.35f)
            var body = 0f; var nb = 0
            val ring = FloatArray(16)
            for (i in 0 until 16) {
                val a = i * PI.toFloat() / 8
                val x = cx + 1.45f * r * cos(a); val y = cy + 1.45f * r * sin(a)
                if (x < 1 || y < 1 || x > f.w - 2 || y > f.h - 2) return null
                ring[i] = sg * f.at(x, y)
            }
            ring.sort()
            body = (ring[4] + ring[8] + ring[11]) / 3   // robust middle (dots and fill)
            nb = 16
            if (core - ring[13] < 14f) { dbg { "core weak ${(core - ring[13]).toInt()}" }; return null }
            val thr = (core + body) / 2
            val n = 40
            val xs = FloatArray(n); val ys = FloatArray(n); var m = 0
            for (i in 0 until n) {
                val a = i * 2 * PI.toFloat() / n
                val dx = cos(a); val dy = sin(a)
                var prev = sg * f.at(cx + dx * 0.45f * r, cy + dy * 0.45f * r)
                var rr = 0.45f * r
                val st = max(0.5f, r / 40f)
                var hit = -1f
                while (rr < 1.7f * r) {
                    val nr = rr + st
                    val x = cx + dx * nr; val y = cy + dy * nr
                    if (x < 1 || y < 1 || x > f.w - 2 || y > f.h - 2) break
                    val v = sg * f.at(x, y)
                    if (prev >= thr && v < thr) { hit = rr + st * (prev - thr) / max(1e-3f, prev - v); break }
                    prev = v; rr = nr
                }
                if (hit < 0) continue
                xs[m] = cx + dx * hit; ys[m] = cy + dy * hit; m++
            }
            if (m < 28) { dbg { "core points $m" }; return null }
            var e = conic(xs, ys, m, cx, cy, r) ?: return null
            // drop the worst, refit
            val res = FloatArray(m)
            for (i in 0 until m) res[i] = abs(e.norm(xs[i], ys[i]) - 1f)
            val sorted = res.copyOf(); sorted.sort()
            if (sorted[m / 2] > 0.06f) { dbg { "core ragged" }; return null }
            val cut = max(0.03f, sorted[m / 2] * 3f)
            var k = 0
            for (i in 0 until m) if (res[i] <= cut) { xs[k] = xs[i]; ys[k] = ys[i]; k++ }
            if (k < 24) return null
            e = conic(xs, ys, k, cx, cy, r) ?: return null
            val ax = sqrt(e.l[0] * e.l[0] + e.l[2] * e.l[2]); val bx = sqrt(e.l[1] * e.l[1] + e.l[3] * e.l[3])
            if (min(ax, bx) < max(ax, bx) * 0.55f) return null
            return Aff(e.x0, e.y0, e.l)
        }

        private class Ell(val x0: Float, val y0: Float, val l: FloatArray) {
            val det = l[0] * l[3] - l[1] * l[2]
            fun norm(x: Float, y: Float): Float {
                val dx = x - x0; val dy = y - y0
                val u = (l[3] * dx - l[1] * dy) / det; val v = (-l[2] * dx + l[0] * dy) / det
                return sqrt(u * u + v * v)
            }
        }

        /** Least-squares conic a·x² + b·xy + c·y² + d·x + e·y = 1 (normalised) → ellipse (unit circle map). */
        private fun conic(xs: FloatArray, ys: FloatArray, n: Int, cx: Float, cy: Float, s: Float): Ell? {
            val mtx = Array(5) { DoubleArray(6) }
            for (i in 0 until n) {
                val x = ((xs[i] - cx) / s).toDouble(); val y = ((ys[i] - cy) / s).toDouble()
                val row = doubleArrayOf(x * x, x * y, y * y, x, y)
                for (p in 0 until 5) { for (q in 0 until 5) mtx[p][q] += row[p] * row[q]; mtx[p][5] += row[p] }
            }
            val sol = Fit.solve(mtx) ?: return null
            val a = sol[0]; val b = sol[1]; val c = sol[2]; val d = sol[3]; val e = sol[4]
            val det = 4 * a * c - b * b
            if (det <= 0) return null
            val x0 = (-2 * c * d + b * e) / det; val y0 = (b * d - 2 * a * e) / det
            val k = 1 + a * x0 * x0 + b * x0 * y0 + c * y0 * y0
            if (k <= 0) return null
            val p = a / k; val q = b / 2 / k; val r = c / k
            val tr = p + r; val dd = sqrt(max(0.0, (p - r) * (p - r) / 4 + q * q))
            val l1 = tr / 2 + dd; val l2 = tr / 2 - dd
            if (l1 <= 0 || l2 <= 0) return null
            val th = 0.5 * atan2(2 * q, p - r)
            val v1x = cos(th); val v1y = sin(th)
            val s1 = 1 / sqrt(l1); val s2 = 1 / sqrt(l2)
            val l = floatArrayOf((v1x * s1 * s).toFloat(), (-v1y * s2 * s).toFloat(), (v1y * s1 * s).toFloat(), (v1x * s2 * s).toFloat())
            return Ell((cx + x0 * s).toFloat(), (cy + y0 * s).toFloat(), l)
        }

        private fun inFrame(f: SigilFrame, x: Float, y: Float) = x >= 1 && y >= 1 && x <= f.w - 2 && y <= f.h - 2

        /**
         * Reads one shape: spike phase, gaps, flip, shape number, long / short spikes. Returns the shape (0..2) after
         * storing its soft bits, -1 if the burst is here but this frame is not readable, -2 if it is not a burst.
         */
        private fun readBurst(f: SigilFrame, a: Aff, sign: Float): Int {
            if (a.scale < 80f) return -2
            // levels: ink from the body ring, paper from the core (refined from the start notch below)
            val v = FloatArray(24)
            for (i in 0 until 24) { val t = i * 15f * DEG; a.map(0.165f * cos(t), 0.165f * sin(t), tmp); v[i] = if (inFrame(f, tmp[0], tmp[1])) sign * f.at(tmp[0], tmp[1]) else Float.NaN }
            val okv = v.filter { !it.isNaN() }.sorted()
            if (okv.size < 16) return -2
            val inkL = okv[okv.size / 2]
            a.map(0f, 0f, tmp)
            var paperL = sign * f.disc(tmp[0], tmp[1], 0.05f * a.scale)
            if (inkL - paperL < 14f) { dbg { "burst contrast" }; return -2 }
            fun inkAt(u: Float, w: Float): Float {
                a.map(u, w, tmp)
                if (!inFrame(f, tmp[0], tmp[1])) return Float.NaN
                return (sign * f.at(tmp[0], tmp[1]) - paperL) / (inkL - paperL)
            }
            // spike phase from the ring of roots
            val nP = 128
            var cs = 0f; var sn = 0f
            val prof = FloatArray(nP)
            for (i in 0 until nP) {
                val t = i * 2 * PI.toFloat() / nP
                val x = inkAt(0.355f * cos(t), 0.355f * sin(t))   // only long spikes reach here: a clean comb
                prof[i] = if (x.isNaN()) 0.5f else x
                cs += prof[i] * cos(SLOTS * t); sn += prof[i] * sin(SLOTS * t)
            }
            val amp = sqrt(cs * cs + sn * sn) / nP
            dbg { "aff c=${a.x0.toInt()},${a.y0.toInt()} scale=${a.scale.toInt()} ink=${inkL.toInt()} paper=${paperL.toInt()} prof=" + prof.take(24).joinToString(",") { (it * 10).toInt().toString() } }
            if (amp < 0.06f) { dbg { "no spikes amp=${(amp * 100).toInt()}" }; return -2 }
            val th0 = atan2(sn, cs) / SLOTS
            // per place: root fill, and ink between the short and long tips
            val base = FloatArray(SLOTS); val zone = FloatArray(SLOTS)
            for (j in 0 until SLOTS) {
                val c = th0 + j * PITCH * DEG
                var s = 0f; var n = 0
                for (rr in floatArrayOf(0.226f, 0.24f, 0.254f)) for (da in floatArrayOf(-3f, -1.5f, 0f, 1.5f, 3f)) {
                    val t = c + da * DEG; val x = inkAt(rr * cos(t), rr * sin(t)); if (!x.isNaN()) { s += x; n++ }
                }
                base[j] = if (n > 0) s / n else 0.5f
                s = 0f; n = 0
                for (q in 0 until 6) {
                    val rr = 0.30f + q * 0.016f
                    for (da in floatArrayOf(-4f, -2.7f, -1.3f, 0f, 1.3f, 2.7f, 4f)) {
                        val t = c + da * DEG; val x = inkAt(rr * cos(t), rr * sin(t)); if (!x.isNaN()) { s += x.coerceIn(-0.3f, 1.3f); n++ }
                    }
                }
                zone[j] = if (n > 20) s / n else Float.NaN
            }
            val sb = base.copyOf(); sb.sort()
            val medB = sb[SLOTS / 2]
            if (medB < 0.35f) { dbg { "roots weak" }; return -1 }
            // the start notch: two neighbouring empty places; the shape flag: one more
            var d0 = -1; var best = Float.MAX_VALUE
            for (j in 0 until SLOTS) { val s = base[j] + base[(j + 1) % SLOTS]; if (s < best) { best = s; d0 = j } }
            var g = -1; var gb = Float.MAX_VALUE
            for (j in 0 until SLOTS) if (j != d0 && j != (d0 + 1) % SLOTS && base[j] < gb) { gb = base[j]; g = j }
            val gapT = 0.42f * medB
            if (base[d0] > gapT || base[(d0 + 1) % SLOTS] > gapT || gb > gapT) { dbg { "gaps unclear" }; return -1 }
            var others = Float.MAX_VALUE
            for (j in 0 until SLOTS) if (j != d0 && j != (d0 + 1) % SLOTS && j != g) others = min(others, base[j])
            if (others < 0.6f * medB) { dbg { "extra gap" }; return -1 }
            val off = ((g - d0) % SLOTS + SLOTS) % SLOTS
            val shape: Int; val mir: Boolean
            when (off) {
                9 -> { shape = 0; mir = false }; 17 -> { shape = 1; mir = false }; 25 -> { shape = 2; mir = false }
                24 -> { shape = 0; mir = true }; 16 -> { shape = 1; mir = true }; 8 -> { shape = 2; mir = true }
                else -> { dbg { "flag at $off" }; return -1 }
            }
            // paper from the notch (between the short and long tips)
            val notch = if (mir) zone[d0] else zone[(d0 + 1) % SLOTS]
            fun place(k: Int) = if (!mir) (d0 + k) % SLOTS else ((d0 + 1 - k) % SLOTS + SLOTS) % SLOTS
            val z = FloatArray(PER + 1)
            for ((j, k) in DATA[shape].withIndex()) {
                val zz = zone[place(k)]
                if (zz.isNaN()) { dbg { "zone off frame" }; return -1 }
                z[j] = zz - (if (notch.isNaN()) 0f else notch)
            }
            val ot = Fit.otsu(z)
            val thr = ot[0]
            var hi = 0f; var nh = 0; var lo = 0f; var nl = 0
            for (x in z) if (x > thr) { hi += x; nh++ } else { lo += x; nl++ }
            if (nh == 0 || nl == 0) return -1
            hi /= nh; lo /= nl
            if (hi - lo < 0.12f || ot[1] < 0.6f) { dbg { "spikes unclear" }; return -1 }
            if (z[PER] < thr) { dbg { "check spike short" }; return -1 }
            val now = f.tMs
            val fresh = now - heardAt[shape] < 3000
            val w0 = if (fresh) min(weight[shape], 3f) else 0f
            for (j in 0 until PER) {
                val sv = ((z[j] - thr) / (hi - lo) * 2f).coerceIn(-1.5f, 1.5f)
                soft[shape][j] = (soft[shape][j] * w0 + sv) / (w0 + 1f)
            }
            weight[shape] = w0 + 1f
            heardAt[shape] = now
            dbg { "shape $shape${if (mir) " mirrored" else ""}" }
            return shape
        }
    }

    /**
     * A half-resolution copy of the frame (2 × 2 sums) with an adaptive three-way threshold (darker / lighter than the
     * local mean by more than a noise margin, else neither) and run-based connected components (8-neighbour).
     */
    private class Half(val f: SigilFrame) {
        val w = f.w / 2; val h = f.h / 2
        val v = IntArray(w * h)
        val ii = IntArray((w + 1) * (h + 1))
        init {
            val iw = w + 1
            for (y in 0 until h) {
                val r0 = f.offset + 2 * y * f.stride; val r1 = r0 + f.stride
                var o = y * w
                var row = 0
                for (x in 0 until w) {
                    val xx = 2 * x
                    val s = (f.lum[r0 + xx].toInt() and 255) + (f.lum[r0 + xx + 1].toInt() and 255) +
                        (f.lum[r1 + xx].toInt() and 255) + (f.lum[r1 + xx + 1].toInt() and 255)
                    v[o++] = s
                    row += s
                    ii[(y + 1) * iw + x + 1] = ii[y * iw + x + 1] + row
                }
            }
        }

        /** 1 = darker than the mean of a (2·[rad] + 1)² box by more than [margin] (per 2 × 2 sum), 2 = lighter, 0 = neither. */
        fun classify(rad: Int, margin: Int): ByteArray {
            val out = ByteArray(w * h)
            val iw = w + 1
            for (y in 0 until h) {
                val y0 = max(0, y - rad); val y1 = min(h, y + rad + 1)
                val ra = y1 * iw; val rb = y0 * iw
                for (x in 0 until w) {
                    val x0 = max(0, x - rad); val x1 = min(w, x + rad + 1)
                    val sum = ii[ra + x1] - ii[rb + x1] - ii[ra + x0] + ii[rb + x0]
                    val area = (y1 - y0) * (x1 - x0)
                    val d = v[y * w + x] * area - sum
                    val m = margin * area
                    out[y * w + x] = if (d < -m) 1 else if (d > m) 2 else 0
                }
            }
            return out
        }

        // runs: row, first x, last x, class, union-find parent
        var nRuns = 0
        var ry = IntArray(4096); var rx0 = IntArray(4096); var rx1 = IntArray(4096); var rc = IntArray(4096); var par = IntArray(4096)
        /** Components (roots): [cls, area, x0, y0, x1, y1, sx, sy, root]. */
        val comps = ArrayList<IntArray>()

        private fun find(a: Int): Int { var x = a; while (par[x] != x) { par[x] = par[par[x]]; x = par[x] }; return x }

        fun label(cls: ByteArray) {
            nRuns = 0; comps.clear()
            var prevStart = 0; var prevEnd = 0
            for (y in 0 until h) {
                val rowStart = nRuns
                var x = 0
                val base = y * w
                while (x < w) {
                    val c = cls[base + x].toInt()
                    if (c == 0) { x++; continue }
                    val s = x
                    while (x < w && cls[base + x].toInt() == c) x++
                    if (nRuns == ry.size) grow()
                    ry[nRuns] = y; rx0[nRuns] = s; rx1[nRuns] = x - 1; rc[nRuns] = c; par[nRuns] = nRuns
                    // join the previous row's runs that touch it (8-neighbour)
                    var p = prevStart
                    while (p < prevEnd && rx1[p] < s - 1) p++
                    while (p < prevEnd && rx0[p] <= x) {
                        if (rc[p] == c) { val a = find(p); val b = find(nRuns); if (a != b) { if (a < b) par[b] = a else par[a] = b } }
                        p++
                    }
                    nRuns++
                }
                prevStart = rowStart; prevEnd = nRuns
            }
            val slot = IntArray(nRuns) { -1 }
            for (i in 0 until nRuns) {
                val r = find(i)
                var k = slot[r]
                if (k < 0) { k = comps.size; slot[r] = k; comps.add(intArrayOf(rc[i], 0, w, h, -1, -1, 0, 0, r)) }
                val cp = comps[k]
                val len = rx1[i] - rx0[i] + 1
                cp[1] += len
                if (rx0[i] < cp[2]) cp[2] = rx0[i]; if (ry[i] < cp[3]) cp[3] = ry[i]
                if (rx1[i] > cp[4]) cp[4] = rx1[i]; if (ry[i] > cp[5]) cp[5] = ry[i]
                cp[6] += (rx0[i] + rx1[i]) * len / 2; cp[7] += ry[i] * len
            }
        }

        private fun grow() {
            val n = ry.size * 2
            ry = ry.copyOf(n); rx0 = rx0.copyOf(n); rx1 = rx1.copyOf(n); rc = rc.copyOf(n); par = par.copyOf(n)
        }

        /** The runs of the component whose root is [root] (indices). */
        fun runsOf(root: Int): IntArray {
            var n = 0
            for (i in 0 until nRuns) if (find(i) == root) n++
            val out = IntArray(n); n = 0
            for (i in 0 until nRuns) if (find(i) == root) out[n++] = i
            return out
        }
    }
}
