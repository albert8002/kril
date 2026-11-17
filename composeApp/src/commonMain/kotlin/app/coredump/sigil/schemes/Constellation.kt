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
 * ORACLE: a tarot reading's patch of night sky. A gold-ringed sky disc on midnight, a faint milky way and background
 * stars, the moon, four bright guide stars, and a constellation of 28 stars joined by gold lines that draw themselves
 * in; candles flicker in the corners, the sky turns slowly, stars twinkle, now and then a shooting star.
 *
 * Code: the star catalogue has 28 fixed "houses"; each house has 8 places on a small circle and its star shines in
 * exactly one of them (3 bits, brightest place wins: no threshold). Two houses = one 6-bit symbol.
 *
 * Reader: own half-resolution threshold + labelling → the gold ring (big hollow round component) → ellipse fit at full
 * resolution → stars inside, ranked by integrated light → moon + 4 guide stars matched to the catalogue (any turn,
 * either flip) → 4-point homography (absorbs the sky's rotation and the tilt) → each house's 8 places compared.
 */
object Constellation : SigilScheme {
    override val name = "constellation"

    /** Diagnostics hook for the bench (null in the app). */
    var debug: ((String) -> Unit)? = null
    private inline fun dbg(msg: () -> String) { debug?.let { it(msg()) } }

    // ---------------------------------------------------------------- the catalogue (sky frame, centre 0, unit = square side)
    private const val RR = 0.415f        // ring band centre radius
    private const val RT = 0.02f         // ring thickness
    private const val SKY = 0.405f       // sky disc radius
    private const val RHO = 0.036f       // radius of a house's circle of places
    private const val MOON_R = 0.047f

    private val REG = floatArrayOf(-0.1550f, -0.0260f, -0.3202f, -0.0558f, -0.0802f, -0.1124f, -0.1574f, -0.2844f, -0.0373f, -0.2137f, -0.0412f, -0.3224f, 0.0778f, -0.3156f, 0.0363f, -0.0830f, 0.1861f, -0.2665f, 0.1573f, -0.1353f, 0.2713f, -0.1790f, 0.2315f, -0.0499f, 0.1108f, 0.0032f, 0.3235f, 0.0307f, 0.2072f, 0.0687f, 0.2824f, 0.1609f, 0.1418f, 0.1632f, 0.1978f, 0.2579f, 0.0865f, 0.3133f, 0.0309f, 0.2123f, -0.0447f, 0.3219f, -0.0434f, 0.1148f, -0.0941f, 0.2084f, -0.1751f, 0.2738f, -0.1526f, 0.0864f, -0.2975f, 0.1309f, -0.0297f, 0.0081f, -0.2609f, 0.0302f)
    private val ROT = floatArrayOf(11f, 24f, 17f, 27f, 28f, 3f, 1f, 38f, 12f, 11f, 45f, 21f, 38f, 21f, 29f, 7f, 29f, 39f, 24f, 33f, 30f, 3f, 34f, 27f, 14f, 1f, 39f, 21f)
    private const val HOUSES = 28
    private val ANCH = floatArrayOf(0.0575f, 0.0996f, 0.0650f, -0.1785f, -0.2068f, 0.1736f, 0.3332f, -0.0893f)
    private const val ANCH_R = 0.024f
    private val MOON = floatArrayOf(-0.2212f, -0.1549f)
    /** The crescent's dark disc: offset (towards the sky's middle-ish) and size. */
    private const val CARVE_DX = 0.021f; private const val CARVE_DY = 0.017f; private const val CARVE_R = 0.041f

    /** Place k of house h (sky frame). */
    private fun placeX(h: Int, k: Int) = REG[2 * h] + RHO * cos((ROT[h] + 45f * k) * PI.toFloat() / 180f)
    private fun placeY(h: Int, k: Int) = REG[2 * h + 1] + RHO * sin((ROT[h] + 45f * k) * PI.toFloat() / 180f)

    /** Scheme-specific whitening of the 84 bits. */
    private val MASK = BooleanArray(SigilPayload.BITS).also {
        var s = 0x0C0FFEE
        for (i in it.indices) { s = s * 1103515245 + 12345; it[i] = (s ushr 16) and 1 == 1 }
    }

    /** The centroid of the moon's lit part (sky frame), for matching. */
    private val MOON_LIT: FloatArray by lazy {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in -30..30) for (j in -30..30) {
            val x = i / 30f * MOON_R; val y = j / 30f * MOON_R
            if (x * x + y * y > MOON_R * MOON_R) continue
            val dx = x - CARVE_DX; val dy = y - CARVE_DY
            if (dx * dx + dy * dy < CARVE_R * CARVE_R) continue
            sx += x; sy += y; n++
        }
        floatArrayOf(MOON[0] + sx / n, MOON[1] + sy / n)
    }

    /** Constellation lines: a minimum spanning tree over the houses and guide stars (indices: houses, then 28 + anchor). */
    private val EDGES: IntArray by lazy {
        val n = HOUSES + 4
        val px = FloatArray(n) { if (it < HOUSES) REG[2 * it] else ANCH[2 * (it - HOUSES)] }
        val py = FloatArray(n) { if (it < HOUSES) REG[2 * it + 1] else ANCH[2 * (it - HOUSES) + 1] }
        val inT = BooleanArray(n); inT[HOUSES] = true
        val out = ArrayList<Int>()
        repeat(n - 1) {
            var bi = -1; var bj = -1; var bd = Float.MAX_VALUE
            for (i in 0 until n) if (inT[i]) for (j in 0 until n) if (!inT[j]) {
                val d = (px[i] - px[j]) * (px[i] - px[j]) + (py[i] - py[j]) * (py[i] - py[j])
                if (d < bd) { bd = d; bi = i; bj = j }
            }
            if (bd < 0.17f * 0.17f) { out.add(bi); out.add(bj) }
            inT[bj] = true
        }
        out.toIntArray()
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

    private fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

    /** A four- (or more-) pointed star polygon. */
    private fun sparkle(out: Scene, x: Float, y: Float, r: Float, inner: Float, points: Int, turn: Float, color: Int) {
        val pts = FloatArray(points * 4)
        for (i in 0 until points * 2) {
            val a = turn + i * PI.toFloat() / points
            val rr = if (i % 2 == 0) r else inner
            pts[2 * i] = x + rr * cos(a); pts[2 * i + 1] = y + rr * sin(a)
        }
        out.poly(pts, color)
    }

    private fun candle(out: Scene, x: Float, base: Float, h: Float, t: Float, seed: Int, wax: Int, waxShade: Int, flame: Int, core: Int, glowC: Int) {
        val w = 0.046f
        val top = base - h
        out.rect(x, base - h / 2, w, h, wax, corner = 0.006f)
        out.rect(x + w * 0.28f, base - h / 2, w * 0.3f, h, waxShade, corner = 0.004f)
        // a drip
        out.oval(x - w * 0.3f, top + 0.012f, 0.006f, 0.014f, wax)
        out.line(x, top, x, top - 0.012f, 0.0035f, Colors.mix(waxShade, BLACK, 0.4f))
        val fl = 1f + 0.12f * sin(t * 9.1f + seed) + 0.07f * sin(t * 23.7f + seed * 2)
        val sway = 0.004f * sin(t * 5.3f + seed)
        out.glow(x, top - 0.03f, 0.11f * fl, Colors.alpha(glowC, 0.22f))
        val fh = 0.05f * fl
        val pts = floatArrayOf(x + sway * 2.5f, top - 0.014f - fh, x + 0.009f + sway, top - 0.03f, x + 0.012f, top - 0.019f,
            x + 0.007f, top - 0.01f, x, top - 0.008f, x - 0.007f, top - 0.01f, x - 0.012f, top - 0.019f, x - 0.009f + sway, top - 0.03f)
        out.poly(pts, flame)
        out.oval(x + sway, top - 0.022f, 0.005f, 0.011f * fl, core)
    }

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val p = ink.plate; val main = ink.main
        val dark = ink.dark
        // midnight blue on dark looks, warm parchment (an old star atlas) on light ones; plate lightness either way
        val sky = if (dark) tint(p, 228f, 0.5f) else tint(p, 40f, 0.2f)
        val gold = tint(main, 44f, 0.58f)
        val starC = tint(main, 48f, 0.16f)
        val lineC = Colors.mix(sky, gold, 0.27f)
        val faintStar = Colors.mix(sky, starC, 0.2f)
        val cx = 0.5f; val cy = 0.5f
        val phi = -t * 2.2f * PI.toFloat() / 180f            // the sky turns slowly
        val cp = cos(phi); val sp = sin(phi)
        fun sx(x: Float, y: Float) = cx + x * cp - y * sp
        fun sy(x: Float, y: Float) = cy + x * sp + y * cp

        // the table: candle light pools, corner ornaments
        val warm = tint(Colors.mix(p, main, 0.35f), 36f, 0.6f)
        out.glow(0.08f, 0.9f, 0.32f, Colors.alpha(warm, 0.22f + 0.04f * sin(t * 7f)))
        out.glow(0.92f, 0.9f, 0.32f, Colors.alpha(warm, 0.22f + 0.04f * sin(t * 6.3f + 1f)))
        val orn = Colors.mix(p, gold, 0.55f)
        for ((k, ox) in floatArrayOf(0.075f, 0.925f).withIndex()) {
            val tw = 0.8f + 0.2f * sin(t * 1.7f + k * 2f)
            sparkle(out, ox, 0.075f, 0.034f * tw, 0.008f, 4, PI.toFloat() / 4 * k, orn)
            sparkle(out, ox + (if (k == 0) 0.06f else -0.06f), 0.05f, 0.014f, 0.004f, 4, 0f, Colors.mix(p, gold, 0.35f))
            out.circle(ox + (if (k == 0) 0.035f else -0.035f), 0.13f, 0.005f, Colors.mix(p, gold, 0.35f))
        }

        // sky disc and the milky way
        out.circle(cx, cy, SKY, sky)
        out.glow(cx, cy, SKY * 1.05f, Colors.alpha(Colors.mix(sky, tint(p, 260f, 0.5f), 0.5f), 0.35f))
        for (k in 0 until 9) {
            val u = (k - 4) / 4f * 0.32f
            val bx = u; val by = -0.55f * u + 0.03f * sin(k * 1.9f)
            out.glow(sx(bx, by), sy(bx, by), 0.09f + 0.02f * hash(k), Colors.alpha(Colors.mix(sky, starC, 0.16f), 0.5f))
        }
        // background stars (faint, twinkling)
        for (i in 0 until 90) {
            val r = 0.39f * sqrt(hash(i * 3 + 1)); val a = 2 * PI.toFloat() * hash(i * 3 + 2)
            val x = r * cos(a); val y = r * sin(a)
            val tw = 0.55f + 0.45f * sin(t * (1.3f + 2.5f * hash(i)) + 6.28f * hash(i * 5))
            out.circle(sx(x, y), sy(x, y), 0.0024f + 0.0028f * hash(i * 7), Colors.alpha(faintStar, 0.35f + 0.65f * tw))
        }

        // where each house's star shines
        val place = IntArray(HOUSES) { h ->
            var v = 0
            for (b in 0 until 3) { val i = 3 * h + b; v = v * 2 + (if (bits[i] != MASK[i]) 1 else 0) }
            v
        }
        fun starX(i: Int) = if (i < HOUSES) placeX(i, place[i]) else ANCH[2 * (i - HOUSES)]
        fun starY(i: Int) = if (i < HOUSES) placeY(i, place[i]) else ANCH[2 * (i - HOUSES) + 1]
        fun starR(i: Int) = if (i < HOUSES) 0.0115f + 0.0035f * hash(i * 13 + 5) else ANCH_R

        // constellation lines draw themselves in, hold, fade
        val cyc = 15f
        val ct = t % cyc
        val fade = if (ct > 12.5f) 1f - ease((ct - 12.5f) / 1.6f) else 1f
        val ne = EDGES.size / 2
        for (e in 0 until ne) {
            val a = EDGES[2 * e]; val b = EDGES[2 * e + 1]
            val grow = ease((ct - 0.3f * e) / 0.7f) * fade
            if (grow <= 0f) continue
            var x0 = starX(a); var y0 = starY(a); var x1 = starX(b); var y1 = starY(b)
            val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val c0 = (starR(a) + 0.014f) / len; val c1 = (starR(b) + 0.014f) / len
            if (c0 + c1 >= 0.95f) continue
            val ax = x0 + (x1 - x0) * c0; val ay = y0 + (y1 - y0) * c0
            val bx = x1 - (x1 - x0) * c1; val by = y1 - (y1 - y0) * c1
            val ex = ax + (bx - ax) * grow; val ey = ay + (by - ay) * grow
            out.line(sx(ax, ay), sy(ax, ay), sx(ex, ey), sy(ex, ey), 0.0034f, lineC)
            x0 = ex; y0 = ey
            if (grow < 1f) out.circle(sx(x0, y0), sy(x0, y0), 0.004f, Colors.mix(sky, gold, 0.3f))
        }

        // the moon
        val mx = sx(MOON[0], MOON[1]); val my = sy(MOON[0], MOON[1])
        out.glow(mx, my, MOON_R * 1.55f, Colors.alpha(starC, 0.22f))
        out.circle(mx, my, MOON_R, starC)
        out.circle(mx + 0.013f * cp - 0.009f * sp, my + 0.013f * sp + 0.009f * cp, 0.007f, Colors.mix(starC, sky, 0.12f))
        out.circle(mx - 0.019f * cp + 0.005f * sp, my - 0.019f * sp - 0.005f * cp, 0.005f, Colors.mix(starC, sky, 0.1f))
        val kx = sx(MOON[0] + CARVE_DX, MOON[1] + CARVE_DY); val ky = sy(MOON[0] + CARVE_DX, MOON[1] + CARVE_DY)
        out.circle(kx, ky, CARVE_R, sky)

        // the constellation's stars
        for (h in 0 until HOUSES) {
            val x = sx(placeX(h, place[h]), placeY(h, place[h])); val y = sy(placeX(h, place[h]), placeY(h, place[h]))
            val r = starR(h)
            val tw = sin(t * (2.1f + 1.7f * hash(h)) + 6.28f * hash(h * 3))
            out.glow(x, y, r * 2.6f, Colors.alpha(starC, 0.28f + 0.1f * tw))
            out.circle(x, y, r, starC)
            if (hash(h * 11) > 0.55f) sparkle(out, x, y, r * (1.9f + 0.35f * tw), r * 0.35f, 4, phi + 0.4f, starC)
        }
        // guide stars: bigger, with rays
        for (a in 0 until 4) {
            val x = sx(ANCH[2 * a], ANCH[2 * a + 1]); val y = sy(ANCH[2 * a], ANCH[2 * a + 1])
            val tw = sin(t * 2.7f + a * 1.3f)
            out.glow(x, y, ANCH_R * 3.2f, Colors.alpha(gold, 0.3f + 0.08f * tw))
            sparkle(out, x, y, ANCH_R * (2.1f + 0.25f * tw), ANCH_R * 0.42f, 4, phi, starC)
            out.circle(x, y, ANCH_R, starC)
        }

        // now and then a shooting star
        val sc = 8.5f
        val k = floor(t / sc).toInt()
        val st = t - k * sc - 2f
        if (st in 0f..0.75f) {
            val a0 = 6.28f * hash(k * 17 + 3)
            val hx0 = 0.3f * cos(a0); val hy0 = 0.3f * sin(a0)
            val dir = a0 + PI.toFloat() + (hash(k * 5) - 0.5f) * 1.2f
            val u = ease(st / 0.75f)
            val hx = hx0 + 0.52f * u * cos(dir); val hy = hy0 + 0.52f * u * sin(dir)
            val al = sin(u * PI.toFloat())
            for (s in 0 until 6) {
                val f0 = s / 6f; val f1 = (s + 1) / 6f
                val tl = 0.16f * min(1f, u * 3f)
                out.line(cx + hx - tl * f0 * cos(dir), cy + hy - tl * f0 * sin(dir), cx + hx - tl * f1 * cos(dir), cy + hy - tl * f1 * sin(dir),
                    0.004f * (1f - f0), Colors.alpha(starC, al * (1f - f0)))
            }
        }

        // the gold ring, ticks outside it
        out.circle(cx, cy, RR, gold, RT)
        out.circle(cx, cy, RR + RT / 2 + 0.012f, Colors.mix(p, gold, 0.3f), 0.003f)
        for (i in 0 until 48) {
            val a = i * PI.toFloat() / 24 + phi * 0.25f
            val l = if (i % 4 == 0) 0.022f else 0.01f
            val r0 = RR + RT / 2 + 0.018f
            out.line(cx + r0 * cos(a), cy + r0 * sin(a), cx + (r0 + l) * cos(a), cy + (r0 + l) * sin(a), 0.003f, Colors.mix(p, gold, 0.4f))
        }

        // candles
        val wax = tint(Colors.mix(p, main, 0.78f), 40f, 0.22f)
        val waxShade = Colors.mix(wax, BLACK, 0.18f)
        val flame = tint(main, 40f, 0.85f)
        val flameCore = if (dark) WHITE else tint(main, 45f, 0.4f)
        candle(out, 0.072f, 1.0f, 0.14f, t, 1, wax, waxShade, flame, flameCore, warm)
        candle(out, 0.13f, 1.0f, 0.085f, t, 3, wax, waxShade, flame, flameCore, warm)
        candle(out, 0.928f, 1.0f, 0.12f, t, 2, wax, waxShade, flame, flameCore, warm)
    }

    // ---------------------------------------------------------------- reading

    override fun reader(): SchemeReader = SkyReader()

    /** An ellipse: centre ([x0], [y0]) and the 2 × 2 map [l] (row-major) from the unit circle, full-res pixels. */
    private class Ellipse(val x0: Float, val y0: Float, val l: FloatArray) {
        val det = l[0] * l[3] - l[1] * l[2]
        /** Sky-frame point (ring radius RR) → image (affine, before the homography). */
        fun toImage(u: Float, v: Float, out: FloatArray) { val a = u / RR; val b = v / RR; out[0] = x0 + l[0] * a + l[1] * b; out[1] = y0 + l[2] * a + l[3] * b }
        fun toSky(x: Float, y: Float, out: FloatArray) {
            val dx = x - x0; val dy = y - y0
            out[0] = (l[3] * dx - l[1] * dy) / det * RR; out[1] = (-l[2] * dx + l[0] * dy) / det * RR
        }
        /** Pixels per sky unit (mean). */
        val scale get() = sqrt(abs(det)) / RR
    }

    private class Cand(val x: Float, val y: Float, val u: Float, val v: Float, val light: Float)

    private class SkyReader : SchemeReader {
        override var seen = false
            private set
        private val tmp = FloatArray(2)
        private var got: Read? = null

        override fun feed(f: SigilFrame): Read? {
            seen = false
            if (f.w < 64 || f.h < 64) return null
            val half = Half(f)
            val cls = half.classify(10, 24)
            half.label(cls)
            val minSide = min(half.w, half.h) * 0.2f
            val rings = half.comps.filter { c ->
                val bw = c[4] - c[2] + 1; val bh = c[5] - c[3] + 1
                val big = max(bw, bh)
                val fill = c[1].toFloat() / (bw * bh)
                big >= minSide && fill <= 0.32f && min(bw, bh) >= big * 0.45f && c[1] >= 40
            }.sortedByDescending { c ->
                val bw = c[4] - c[2] + 1; val bh = c[5] - c[3] + 1
                (bw + bh) * (1f - c[1].toFloat() / (bw * bh))
            }.take(4)
            if (rings.isEmpty()) dbg { "no ring candidates" }
            for (c in rings) {
                val ell = fitRing(f, half, c) ?: run { dbg { "ring fit fail" }; null } ?: continue
                if (!readSky(f, half, c, ell)) continue
                seen = true
                return got
            }
            return null
        }

        /**
         * The ring: a first conic through the component's runs (half resolution, robust), then the band's centre along
         * 72 rays at full resolution, searched only near that first ellipse → final conic.
         */
        private fun fitRing(f: SigilFrame, half: Half, c: IntArray): Ellipse? {
            val dark = c[0] == 1
            val cx = c[6].toFloat() / c[1] * 2f + 0.5f; val cy = c[7].toFloat() / c[1] * 2f + 0.5f
            val r0 = ((c[4] - c[2] + 1) + (c[5] - c[3] + 1)) / 2f
            if (r0 < 20f) return null
            dbg { "ring cand c=${cx.toInt()},${cy.toInt()} r0=${r0.toInt()} area=${c[1]}" }
            val runs = half.runsOf(c[8])
            if (runs.size < 20) return null
            val step = max(1, runs.size / 400)
            var m = 0
            val xs0 = FloatArray(runs.size / step + 1); val ys0 = FloatArray(runs.size / step + 1)
            var i = 0
            while (i < runs.size) {
                val r = runs[i]
                xs0[m] = (half.rx0[r] + half.rx1[r]) + 0.5f; ys0[m] = half.ry[r] * 2f + 0.5f; m++
                i += step
            }
            var e = robustConic(xs0, ys0, m, cx, cy, r0) ?: return null
            // a ring's runs hug one ellipse; filled or ragged shapes do not
            var spread = 0f
            for (q in 0 until m) { e.toSky(xs0[q], ys0[q], tmp); spread += abs(sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1]) / RR - 1f) }
            if (spread / m > 0.06f) { dbg { "not a ring ${(spread / m * 100).toInt()}" }; return null }
            // full resolution: the band's centre near the first ellipse
            val n = 48
            val xs = FloatArray(n); val ys = FloatArray(n); m = 0
            val pv = FloatArray(160)
            val det = e.det
            for (k in 0 until n) {
                val a = k * 2 * PI.toFloat() / n
                val dx = cos(a); val dy = sin(a)
                // ellipse radius along (dx, dy): |L⁻¹ d| = 1/t
                val ux = (e.l[3] * dx - e.l[1] * dy) / det; val uy = (-e.l[2] * dx + e.l[0] * dy) / det
                val t = 1f / sqrt(ux * ux + uy * uy)
                val lo = t * 0.86f; val hi = t * 1.14f
                val st = max(0.7f, (hi - lo) / 159f)
                var ns = 0
                var rr = lo
                while (rr <= hi && ns < 160) {
                    val x = e.x0 + dx * rr; val y = e.y0 + dy * rr
                    if (x < 1 || y < 1 || x > f.w - 2 || y > f.h - 2) break
                    pv[ns++] = if (dark) -f.at(x, y) else f.at(x, y)
                    rr += st
                }
                if (ns < 10 || lo + (ns - 1) * st < hi - st) continue
                var pk = 0
                for (q in 1 until ns) if (pv[q] > pv[pk]) pk = q
                if (pk < 2 || pk > ns - 3) continue
                var inB = pv[pk]; for (q in 0 until pk) inB = min(inB, pv[q])
                var outB = pv[pk]; for (q in pk + 1 until ns) outB = min(outB, pv[q])
                if (pv[pk] - inB < 12f || pv[pk] - outB < 12f) continue
                val tIn = (pv[pk] + inB) / 2; val tOut = (pv[pk] + outB) / 2
                var a1 = pk; while (a1 > 0 && pv[a1 - 1] > tIn) a1--
                var b1 = pk; while (b1 < ns - 1 && pv[b1 + 1] > tOut) b1++
                if (a1 == 0 || b1 == ns - 1) continue
                val rIn = lo + st * (a1 - 1 + (tIn - pv[a1 - 1]) / max(1e-3f, pv[a1] - pv[a1 - 1]))
                val rOut = lo + st * (b1 + (pv[b1] - tOut) / max(1e-3f, pv[b1] - pv[b1 + 1]))
                val r = (rIn + rOut) / 2
                xs[m] = e.x0 + dx * r; ys[m] = e.y0 + dy * r; m++
            }
            if (m < 14) { dbg { "ring points $m" }; return null }
            e = robustConic(xs, ys, m, e.x0, e.y0, r0) ?: return null
            val ax = sqrt(e.l[0] * e.l[0] + e.l[2] * e.l[2]); val bx = sqrt(e.l[1] * e.l[1] + e.l[3] * e.l[3])
            if (min(ax, bx) < max(ax, bx) * 0.5f) return null
            return e
        }

        /** A conic through the points, refitted once without the worst ones. */
        private fun robustConic(xs: FloatArray, ys: FloatArray, m: Int, cx: Float, cy: Float, r0: Float): Ellipse? {
            var e = conic(xs, ys, m, cx, cy, r0) ?: return null
            val res = FloatArray(m)
            for (i in 0 until m) { e.toSky(xs[i], ys[i], tmp); res[i] = abs(sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1]) / RR - 1f) }
            val sorted = res.copyOf(); sorted.sort()
            val cut = max(0.015f, sorted[m / 2] * 3f)
            var k = 0
            for (i in 0 until m) if (res[i] <= cut) { xs[k] = xs[i]; ys[k] = ys[i]; k++ }
            if (k < 12) return null
            e = conic(xs, ys, k, cx, cy, r0) ?: return null
            return e
        }

        /** Least-squares conic a·x² + b·xy + c·y² + d·x + e·y = 1 (normalised coordinates) → ellipse. */
        private fun conic(xs: FloatArray, ys: FloatArray, n: Int, cx: Float, cy: Float, s: Float): Ellipse? {
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
            // eigen of [[p, q], [q, r]]
            val tr = p + r; val dd = sqrt(max(0.0, (p - r) * (p - r) / 4 + q * q))
            val l1 = tr / 2 + dd; val l2 = tr / 2 - dd
            if (l1 <= 0 || l2 <= 0) return null
            val th = 0.5 * atan2(2 * q, p - r)
            val v1x = cos(th); val v1y = sin(th)          // eigenvector of l1
            val s1 = 1 / sqrt(l1); val s2 = 1 / sqrt(l2)
            // L = V · diag(s1, s2), V = [v1, v2], v2 = v1 turned +90° (proper rotation)
            val l = floatArrayOf((v1x * s1 * s).toFloat(), (-v1y * s2 * s).toFloat(), (v1y * s1 * s).toFloat(), (v1x * s2 * s).toFloat())
            return Ellipse((cx + x0 * s).toFloat(), (cy + y0 * s).toFloat(), l)
        }

        private fun inFrame(f: SigilFrame, x: Float, y: Float) = x >= 1 && y >= 1 && x <= f.w - 2 && y <= f.h - 2

        /** Sky the light of a star at (x, y): re-centred twice, integrated over a window, against the local sky. */
        private fun measure(f: SigilFrame, x0: Float, y0: Float, wr: Float, sign: Float, out: FloatArray): Float {
            var x = x0; var y = y0
            // local sky: lower half of a ring of samples
            val ring = FloatArray(16)
            for (i in 0 until 16) { val a = i * PI.toFloat() / 8; ring[i] = sign * f.at(x + 1.8f * wr * cos(a), y + 1.8f * wr * sin(a)) }
            ring.sort()
            val sky = (ring[2] + ring[3] + ring[4] + ring[5]) / 4
            var tot = 0f
            val st = max(0.75f, wr / 4f)
            repeat(2) {
                var sx = 0f; var sy = 0f; tot = 0f
                var oy = -wr
                while (oy <= wr) {
                    var ox = -wr
                    while (ox <= wr) {
                        if (ox * ox + oy * oy <= wr * wr) {
                            val v = sign * f.at(x + ox, y + oy) - sky
                            if (v > 0) { sx += v * ox; sy += v * oy; tot += v }
                        }
                        ox += st
                    }
                    oy += st
                }
                if (tot <= 0f) return 0f
                x += sx / tot; y += sy / tot
            }
            out[0] = x; out[1] = y
            return tot * st * st
        }

        /** Bright (ink-side) spots inside the sky: local maxima of a star-sized box mean against its surroundings. */
        private fun peaks(half: Half, e: Ellipse, sign: Float, s: Float): ArrayList<Cand> {
            val w = half.w; val iw = w + 1; val ii = half.ii
            val a = max(1, (0.02f * s / 2f + 0.5f).toInt())   // a guide star's size: small stars answer weakly
            val b = 3 * a
            val rx = 0.4f * s / 2f
            val cx = (e.x0 - 0.5f) / 2f; val cy = (e.y0 - 0.5f) / 2f
            val x0 = max(b, (cx - rx * 1.4f).toInt()); val x1 = min(w - b - 1, (cx + rx * 1.4f).toInt())
            val y0 = max(b, (cy - rx * 1.4f).toInt()); val y1 = min(half.h - b - 1, (cy + rx * 1.4f).toInt())
            if (x1 <= x0 || y1 <= y0) return ArrayList()
            val gw = x1 - x0 + 1; val gh = y1 - y0 + 1
            val resp = FloatArray(gw * gh)
            val na = ((2 * a + 1) * (2 * a + 1)).toFloat(); val nb = ((2 * b + 1) * (2 * b + 1)).toFloat()
            // inside the sky: (p − c)ᵀ Q (p − c) ≤ (0.385 / RR)², Q = L⁻ᵀ L⁻¹ (half-resolution pixels)
            val il = floatArrayOf(e.l[3] / e.det, -e.l[1] / e.det, -e.l[2] / e.det, e.l[0] / e.det)
            val q11 = 4f * (il[0] * il[0] + il[2] * il[2]); val q12 = 4f * (il[0] * il[1] + il[2] * il[3]); val q22 = 4f * (il[1] * il[1] + il[3] * il[3])
            val lim = (0.385f / RR) * (0.385f / RR)
            for (y in y0..y1) {
                val dy = y - cy
                // q11·dx² + 2·q12·dy·dx + q22·dy² − lim ≤ 0
                val bq = 2 * q12 * dy; val cq = q22 * dy * dy - lim
                val disc = bq * bq - 4 * q11 * cq
                if (disc <= 0f) continue
                val sq = sqrt(disc)
                val xa = max(x0, (cx + (-bq - sq) / (2 * q11)).toInt() + 1); val xb = min(x1, (cx + (-bq + sq) / (2 * q11)).toInt())
                val ra = (y + a + 1) * iw; val rb = (y - a) * iw; val rc = (y + b + 1) * iw; val rd = (y - b) * iw
                for (x in xa..xb) {
                    val sa = ii[ra + x + a + 1] - ii[rb + x + a + 1] - ii[ra + x - a] + ii[rb + x - a]
                    val sb = ii[rc + x + b + 1] - ii[rd + x + b + 1] - ii[rc + x - b] + ii[rd + x - b]
                    resp[(y - y0) * gw + x - x0] = sign * (sa / na - sb / nb) / 4f
                }
            }
            // local maxima above a floor, best first (a short list: only the brightest few matter)
            var floor = 6f
            for (v in resp) if (v * 0.3f > floor) floor = v * 0.3f
            val out = ArrayList<Cand>()
            for (y in 1 until gh - 1) {
                val row = y * gw
                for (x in 1 until gw - 1) {
                    val r = resp[row + x]
                    if (r < floor) continue
                    if (resp[row + x - 1] > r || resp[row + x + 1] > r || resp[row - gw + x] > r || resp[row + gw + x] > r ||
                        resp[row - gw + x - 1] > r || resp[row - gw + x + 1] > r || resp[row + gw + x - 1] > r || resp[row + gw + x + 1] > r) continue
                    out.add(Cand((x + x0) * 2f + 0.5f, (y + y0) * 2f + 0.5f, 0f, 0f, r))
                }
            }
            out.sortByDescending { it.light }
            // non-maximum suppression at a star's size
            val keep = ArrayList<Cand>()
            val sep = 0.03f * s
            for (c in out) {
                if (keep.any { (it.x - c.x) * (it.x - c.x) + (it.y - c.y) * (it.y - c.y) < sep * sep }) continue
                keep.add(c)
                if (keep.size >= 10) break
            }
            return keep
        }

        private fun readSky(f: SigilFrame, half: Half, ring: IntArray, e: Ellipse): Boolean {
            got = null
            val sign = if (ring[0] == 1) -1f else 1f
            val s = e.scale
            if (s < 60f) return false
            // star candidates: local maxima of a small box filter (half resolution) inside the sky
            val cand = peaks(half, e, sign, s)
            if (cand.size < 5) { dbg { "stars ${cand.size}" }; return false }
            val q = FloatArray(2)
            // the brightest few, measured properly
            val top = ArrayList<Cand>()
            val wr = 0.026f * s
            for (c in cand) {
                if (!inFrame(f, c.x, c.y)) continue
                if (measure(f, c.x, c.y, wr, sign, q) <= 0f) continue
                val l = c.light
                // several peaks can settle on one star: keep the first
                val dd = 0.012f * s
                if (top.any { (it.x - q[0]) * (it.x - q[0]) + (it.y - q[1]) * (it.y - q[1]) < dd * dd }) continue
                e.toSky(q[0], q[1], tmp)
                top.add(Cand(q[0], q[1], tmp[0], tmp[1], l))
            }
            top.sortByDescending { it.light }
            val pick = top.take(7)
            dbg { "top " + top.take(9).joinToString(" ") { "(${(it.u * 1000).toInt()},${(it.v * 1000).toInt()}:${it.light.toInt()})" } }
            if (pick.size < 5) return false
            // moon + 4 guide stars against the catalogue: any turn, either flip
            val dx = floatArrayOf(MOON_LIT[0], ANCH[0], ANCH[2], ANCH[4], ANCH[6])
            val dy = floatArrayOf(MOON_LIT[1], ANCH[1], ANCH[3], ANCH[5], ANCH[7])
            val wt = floatArrayOf(0.4f, 1f, 1f, 1f, 1f)
            var best = Float.MAX_VALUE; var bestSel: IntArray? = null; var bestMir = 1f
            val sel = IntArray(5); val used = BooleanArray(pick.size)
            val wsum = wt.sum()
            fun score(mir: Float) {
                // rotation + shift (the ellipse's middle is off the sky's middle under perspective)
                var mdx = 0f; var mdy = 0f; var mcx = 0f; var mcy = 0f
                for (j in 0 until 5) { val c = pick[sel[j]]; mdx += wt[j] * dx[j]; mdy += wt[j] * dy[j] * mir; mcx += wt[j] * c.u; mcy += wt[j] * c.v }
                mdx /= wsum; mdy /= wsum; mcx /= wsum; mcy /= wsum
                var sd = 0f; var sc = 0f
                for (j in 0 until 5) {
                    val px = dx[j] - mdx; val py = dy[j] * mir - mdy; val c = pick[sel[j]]
                    val qx = c.u - mcx; val qy = c.v - mcy
                    sd += wt[j] * (px * qx + py * qy); sc += wt[j] * (px * qy - py * qx)
                }
                val phi = atan2(sc, sd); val cp = cos(phi); val sp = sin(phi)
                var err = 0f
                for (j in 0 until 5) {
                    val px = dx[j] - mdx; val py = dy[j] * mir - mdy; val c = pick[sel[j]]
                    val ex = px * cp - py * sp - (c.u - mcx); val ey = px * sp + py * cp - (c.v - mcy)
                    err += wt[j] * (ex * ex + ey * ey)
                    if (err >= best) return
                }
                best = err; bestSel = sel.copyOf(); bestMir = mir
            }
            // distances survive turns and flips: prune assignments whose pairwise distances disagree
            fun rec(j: Int) {
                if (j == 5) { score(1f); score(-1f); return }
                for (i in pick.indices) {
                    if (used[i]) continue
                    var fits = true
                    for (q in 0 until j) {
                        val c = pick[sel[q]]; val d = pick[i]
                        val dc = sqrt((c.u - d.u) * (c.u - d.u) + (c.v - d.v) * (c.v - d.v))
                        val dd = sqrt((dx[q] - dx[j]) * (dx[q] - dx[j]) + (dy[q] - dy[j]) * (dy[q] - dy[j]))
                        if (abs(dc - dd) > 0.07f + 0.15f * dd) { fits = false; break }
                    }
                    if (!fits) continue
                    used[i] = true; sel[j] = i; rec(j + 1); used[i] = false
                }
            }
            rec(0)
            val bs = bestSel ?: return false
            val rms = sqrt(best / 4.4f)
            if (rms > 0.06f) { dbg { "match rms ${(rms * 1000).toInt()}" }; return false }
            // homography from the guide stars
            val src = FloatArray(8) { ANCH[it] }
            val dst = FloatArray(8)
            for (j in 0 until 4) { dst[2 * j] = pick[bs[j + 1]].x; dst[2 * j + 1] = pick[bs[j + 1]].y }
            val h = Fit.homography(src, dst) ?: return false
            // the moon must sit where the homography puts it
            Fit.apply(h, MOON_LIT[0], MOON_LIT[1], tmp)
            val mm = pick[bs[0]]
            if (hypot(tmp[0] - mm.x, tmp[1] - mm.y) > 0.05f * s) { dbg { "moon off" }; return false }
            // contrast reference: the guide stars' peaks against their surroundings
            var cref = 0f
            for (j in 0 until 4) {
                val c = pick[bs[j + 1]]
                val pk = sign * f.disc(c.x, c.y, 0.012f * s)
                var sk = 0f
                for (i in 0 until 8) { val a = i * PI.toFloat() / 4; sk += sign * f.at(c.x + 0.05f * s * cos(a), c.y + 0.05f * s * sin(a)) }
                cref += pk - sk / 8
            }
            cref /= 4
            if (cref < 12f) return false
            // read the houses: the brightest place wins
            val bitsOut = BooleanArray(SigilPayload.BITS); val sure = FloatArray(SigilPayload.BITS)
            val v = FloatArray(8)
            var clear = 0
            for (hh in 0 until HOUSES) {
                Fit.apply(h, REG[2 * hh], REG[2 * hh + 1], tmp)
                val mx = tmp[0]; val my = tmp[1]
                Fit.apply(h, REG[2 * hh] + 0.01f, REG[2 * hh + 1], tmp)
                val sl = hypot(tmp[0] - mx, tmp[1] - my) / 0.01f
                val sr = max(0.8f, 0.3f * 0.0276f * sl)
                val sky = if (inFrame(f, mx, my)) sign * cross5(f, mx, my, 0.4f * RHO * sl) else 0f
                var bk = 0
                for (k in 0 until 8) {
                    Fit.apply(h, placeX(hh, k), placeY(hh, k), tmp)
                    v[k] = if (inFrame(f, tmp[0], tmp[1])) sign * cross5(f, tmp[0], tmp[1], sr) - sky else -999f
                    if (v[k] > v[bk]) bk = k
                }
                var s2 = -999f
                for (k in 0 until 8) if (k != bk && v[k] > s2) s2 = v[k]
                val conf = if (v[bk] < 0.2f * cref) 0f else ((v[bk] - s2) / cref * 2f).coerceIn(0f, 2f)
                if (v[bk] > 0.35f * cref && conf > 0.3f) clear++
                for (b in 0 until 3) {
                    val i = 3 * hh + b
                    bitsOut[i] = ((bk shr (2 - b)) and 1 == 1) != MASK[i]
                    sure[i] = conf
                }
            }
            if (clear < 18) { dbg { "houses clear $clear" }; return false }
            got = SigilPayload.decodeBits(bitsOut, sure)
            if (got == null) dbg { "decode fail weak=${(0 until HOUSES).count { sure[3 * it] < 0.3f }}" }
            return true
        }

        private fun hypot(a: Float, b: Float) = sqrt(a * a + b * b)

        /** Mean of a 5-point cross of radius [r] (half on the diagonals' scale). */
        private fun cross5(f: SigilFrame, x: Float, y: Float, r: Float): Float {
            val d = r * 0.6f
            return (2 * f.at(x, y) + f.at(x + d, y + d) + f.at(x - d, y + d) + f.at(x + d, y - d) + f.at(x - d, y - d)) / 6f
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
