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
 * JACKPOT: a stack of seven poker chips, seen a little from above, inside a gold marquee of chasing bulbs.
 *
 * Every chip edge carries a ring of 13 inserts: one tall "marker" insert (the whole edge height) and 12 data inserts
 * (half height; present = 1), i.e. two payload symbols per chip. The chips spin (each at its own steady pace, all the
 * same way round), so the inserts scroll across the front of the stack and every one comes round within a turn.
 *
 * Reader: the marquee band (a rounded square) gives a 4-corner homography; the stack's shape gives "up". Each frame
 * it samples the front ±60° of every chip edge (spot row and the two margins), finds marker inserts (dark margins),
 * learns each chip's spin phase from them (the spin rates are fixed) and the mirror from the direction they move,
 * and accumulates soft votes per insert over the last few seconds.
 */
object ChipStack : SigilScheme {
    override val name = "chips"

    // ------------------------------------------------------------------ layout (unit square, y down)
    internal const val FO = 0.42f      // marquee outer edge, half-size
    internal const val FB = 0.04f      // marquee band thickness
    internal const val FRO = 0.065f     // outer corner radius
    internal const val CX = 0.5f
    internal const val R = 0.26f        // chip radius
    internal const val RY = 0.075f      // half-height of a chip face as seen
    internal const val YF = 0.225f      // centre of the top face
    internal const val HB = 0.074f      // chip thickness
    internal const val N = 7
    internal const val SLOTS = 13       // marker + 12 data inserts
    internal const val GAP = 0.008f     // crevice between chips
    internal const val SPOT_H = 0.032f
    internal const val MT = (HB - GAP - SPOT_H) / 2f   // margin above / below a data insert
    internal const val BRX = 0.30f      // turntable radius
    internal const val BRY = BRX * RY / R
    internal const val BY = YF + N * HB + 0.006f       // turntable top centre
    internal const val BT = 0.012f      // turntable thickness
    internal const val SLOT_DEG = 360f / SLOTS
    internal const val SPOT_DEG = 0.6f * SLOT_DEG
    /** Seconds per turn of each chip (top to bottom); all spin the same way (front moves to +x). */
    internal val TURN = floatArrayOf(2.35f, 2.6f, 2.45f, 2.75f, 2.3f, 2.65f, 2.5f)
    private val PHASE = floatArrayOf(17f, 131f, 250f, 63f, 199f, 310f, 95f)

    /** Scheme-specific whitening of the 84 bits (draw: bits xor mask; read: xor back). */
    internal val MASK: BooleanArray = run {
        var s = 0x2C41F5A3
        BooleanArray(SigilPayload.BITS) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            (s ushr 11) and 1 == 1
        }
    }

    internal fun omega(k: Int) = 360.0 / TURN[k]
    internal fun phase(k: Int, t: Double): Double = PHASE[k] + omega(k) * t

    internal fun wrap(a: Double): Double { var x = a % 360.0; if (x > 180.0) x -= 360.0; if (x <= -180.0) x += 360.0; return x }
    internal fun wrapF(a: Float): Float = wrap(a.toDouble()).toFloat()

    // ------------------------------------------------------------------ colours

    private class Pal(val ink: Ink) {
        val lp = Colors.light(ink.plate)
        val lm = Colors.light(ink.main)
        fun lv(f: Float) = lp + (lm - lp) * f
        val gold = tone(43f, 0.78f, lv(0.74f))
        val goldDeep = tone(33f, 0.85f, lv(0.52f))
        val goldHi = tone(48f, 0.35f, lv(1.0f))
        val bulb = tone(52f, 0.18f, lv(1.0f))
        val body = arrayOf(
            tone(42f, 0.10f, lv(1.0f)), tone(44f, 0.62f, lv(0.84f)), tone(352f, 0.20f, lv(0.97f)), tone(40f, 0.12f, lv(1.0f)),
            tone(160f, 0.16f, lv(0.95f)), tone(44f, 0.62f, lv(0.84f)), tone(42f, 0.10f, lv(1.0f)),
        )
        val spot = arrayOf(
            tone(355f, 0.85f, lp), tone(225f, 0.8f, lp), tone(0f, 0f, lp), tone(355f, 0.85f, lp),
            tone(280f, 0.6f, lp), tone(225f, 0.8f, lp), tone(150f, 0.7f, lp),
        )
        val shade = arrayOf(*Array(N) { Colors.mix(body[it], ink.plate, 0.16f) })
        val crevice = Colors.mix(ink.plate, 0xFF000000.toInt(), if (lp < 0.5f) 0.35f else 0.0f)
    }

    private var palInk: Ink? = null
    private var palVal: Pal? = null
    private fun pal(ink: Ink): Pal {
        val p = palVal
        if (p != null && palInk === ink) return p
        return Pal(ink).also { palInk = ink; palVal = it }
    }

    // ------------------------------------------------------------------ drawing

    private fun smooth(e0: Float, e1: Float, x: Float): Float { val k = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return k * k * (3 - 2 * k) }
    private fun rad(d: Float) = d * (PI.toFloat() / 180f)

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val p = pal(ink)
        val dark = ink.dark
        // felt: a soft pool of light behind the stack
        out.glow(0.5f, 0.47f, 0.58f, Colors.alpha(ink.soft, 0.7f))

        // jackpot coins drifting down behind the stack (faint)
        for (i in 0 until 7) {
            val ph = ((t * 0.075f + i * 0.143f) % 1f)
            val x = 0.13f + ((i * 0.377f) % 1f) * 0.74f + 0.02f * sin(t * 1.3f + i)
            val y = 0.11f + ph * 0.8f
            val spin = cos(t * 2.4f + i * 1.7f)
            val a = smooth(0f, 0.12f, ph) * (1f - smooth(0.85f, 1f, ph))
            out.oval(x, y, 0.022f * max(0.12f, abs(spin)), 0.022f, Colors.alpha(ink.faint, a))
        }

        // marquee band and its chasing bulbs
        val a = FO - FB / 2
        out.rect(0.5f, 0.5f, 2 * a, 2 * a, p.gold, 0f, FRO - FB / 2, FB)
        out.rect(0.5f, 0.5f, 2 * a, 2 * a, Colors.alpha(p.goldHi, 0.55f), 0f, FRO - FB / 2, 0.006f)
        val nb = 48
        val per = squareLen(a, FRO - FB / 2)
        val step = floor(t * 9f).toInt()
        val flash = (t % 6f) > 5.2f
        for (i in 0 until nb) {
            val q = squareAt(a, FRO - FB / 2, per * i / nb)
            val lit = flash && ((t * 8f).toInt() % 2 == 0) || (!flash && (i + step) % 3 == 0)
            if (lit) {
                out.glow(q[0], q[1], 0.021f, Colors.alpha(p.bulb, 0.85f))
                out.circle(q[0], q[1], 0.0105f, p.bulb)
            } else {
                out.circle(q[0], q[1], 0.0095f, p.goldDeep)
                out.circle(q[0] - 0.003f, q[1] - 0.003f, 0.003f, Colors.alpha(p.gold, 0.9f))
            }
        }

        // the gold turntable the stack spins on, and the stack's shadow on it
        val yb = YF + N * HB
        out.glow(CX + 0.02f, BY + BRY, BRX * 1.15f, Colors.alpha(ink.soft, 0.9f))
        run {
            var n = 0
            val bp = FloatArray(4 * 25)
            for (i in 0..24) { val th = rad(-90f + 7.5f * i); bp[n++] = CX + BRX * sin(th); bp[n++] = BY + BRY * cos(th) }
            for (i in 24 downTo 0) { val th = rad(-90f + 7.5f * i); bp[n++] = CX + BRX * sin(th); bp[n++] = BY + BT + BRY * cos(th) }
            out.poly(bp, p.goldDeep)
            out.oval(CX, BY, BRX, BRY, p.gold)
            out.oval(CX, BY, BRX - 0.012f, BRY - 0.0035f, Colors.alpha(p.goldHi, 0.5f), 0f, 0.003f)
            // studs round the turntable rim turn with it
            for (i in 0 until 16) {
                val th = rad(i * 22.5f + t * 40f)
                if (cos(th) < 0.15f) continue
                out.circle(CX + (BRX - 0.022f) * sin(th), BY + (BRY - 0.0064f) * cos(th), 0.0045f, Colors.alpha(p.goldHi, 0.9f))
            }
            out.glow(CX, yb + RY * 0.35f, R * 1.02f, Colors.alpha(p.goldDeep, 0.85f))
        }

        // chips, top to bottom (their front bands do not overlap)
        val pts = FloatArray(4 * 13)
        for (k in 0 until N) {
            val yk = YF + k * HB
            val yk1 = yk + HB
            val bc = p.body[k]
            // band
            var n = 0
            for (i in 0..12) { val th = rad(-90f + 15f * i); pts[n++] = CX + R * sin(th); pts[n++] = yk + GAP + RY * cos(th) }
            for (i in 12 downTo 0) { val th = rad(-90f + 15f * i); pts[n++] = CX + R * sin(th); pts[n++] = yk1 + RY * cos(th) }
            out.poly(pts.copyOf(n), bc)
            // rim light along the top edge, a soft shade along the bottom, cylinder shading near the silhouette
            bandQuad(out, yk + GAP, yk + GAP + 0.0045f, -88f, 88f, Colors.mix(bc, if (dark) 0xFFFFFFFF.toInt() else ink.plate, if (dark) 0.45f else 0.12f))
            bandQuad(out, yk1 - 0.005f, yk1, -90f, 90f, p.shade[k])
            for (side in intArrayOf(-1, 1)) {
                bandQuad(out, yk + GAP, yk1, side * 66f, side * 90f, p.shade[k])
            }
            // a gold glint sweeping round the stack now and then
            val sweep = ((t * 0.22f + k * 0.035f) % 1f) * 260f - 130f
            if (abs(sweep) < 88f) bandQuad(out, yk + GAP, yk1, sweep - 4f, sweep + 4f, Colors.mix(bc, if (dark) 0xFFFFFFFF.toInt() else ink.plate, if (dark) 0.35f else 0.10f))
            // inserts
            val ph = phase(k, t.toDouble()).toFloat()
            for (j in 0 until SLOTS) {
                val c = wrapF(j * SLOT_DEG + ph)
                if (abs(c) > 90f + SPOT_DEG / 2) continue
                val marker = j == 0
                if (!marker) {
                    val bi = 12 * k + j - 1
                    if (!(bits[bi] xor MASK[bi])) continue
                }
                val th0 = max(c - SPOT_DEG / 2, -90f)
                val th1 = min(c + SPOT_DEG / 2, 90f)
                if (th1 - th0 < 0.5f) continue
                val col = Colors.mix(p.spot[k], bc, smooth(62f, 86f, abs(c)))
                if (marker) bandQuad(out, yk + GAP, yk1, th0, th1, col)
                else bandQuad(out, yk + GAP + MT, yk + GAP + MT + SPOT_H, th0, th1, col)
            }
        }

        // the top face: a cream inlay, a gold ring, a diamond, and the inserts peeking round its rim
        val fc = Colors.mix(p.body[0], if (dark) 0xFFFFFFFF.toInt() else p.body[0], 0.25f)
        out.oval(CX, YF, R, RY, fc)
        val ph0 = phase(0, t.toDouble()).toFloat()
        for (j in 0 until SLOTS) {
            val c = j * SLOT_DEG + ph0
            if (j > 0) { val bi = j - 1; if (!(bits[bi] xor MASK[bi])) continue }
            val c0 = rad(c - SPOT_DEG / 2); val c1 = rad(c + SPOT_DEG / 2)
            val r0 = 0.9f; val r1 = 0.985f
            val face = Colors.mix(p.spot[0], fc, 0.35f + 0.25f * smooth(-1f, 1f, -cos(rad(c))))
            out.poly(floatArrayOf(
                CX + R * r0 * sin(c0), YF + RY * r0 * cos(c0), CX + R * r1 * sin(c0), YF + RY * r1 * cos(c0),
                CX + R * r1 * sin(c1), YF + RY * r1 * cos(c1), CX + R * r0 * sin(c1), YF + RY * r0 * cos(c1),
            ), face)
        }
        out.oval(CX, YF, R * 0.72f, RY * 0.72f, p.gold, 0f, 0.007f)
        val dw = 0.05f; val dh = 0.032f
        out.poly(floatArrayOf(CX, YF - dh, CX + dw, YF, CX, YF + dh, CX - dw, YF), p.gold)
        out.poly(floatArrayOf(CX, YF - dh * 0.55f, CX + dw * 0.55f, YF, CX, YF + dh * 0.55f, CX - dw * 0.55f, YF), Colors.alpha(p.goldHi, 0.8f))

        // twinkles on the gold
        for (i in 0 until 4) {
            val tw = sin(t * 2.1f + i * 1.9f)
            if (tw < 0.2f) continue
            val s = 0.024f * (tw - 0.2f) / 0.8f
            val q = squareAt(a, FRO - FB / 2, per * ((i * 0.29f + 0.07f + t * 0.013f) % 1f))
            val col = Colors.alpha(p.goldHi, 0.95f)
            out.line(q[0] - s, q[1], q[0] + s, q[1], 0.004f, col)
            out.line(q[0], q[1] - s, q[0], q[1] + s, 0.004f, col)
        }
        val tw = sin(t * 1.7f)
        if (tw > 0.3f) {
            val s = 0.03f * (tw - 0.3f)
            val col = Colors.alpha(p.goldHi, 0.95f)
            out.line(CX - s, YF, CX + s, YF, 0.004f, col)
            out.line(CX, YF - s * 0.8f, CX, YF + s * 0.8f, 0.004f, col)
        }
    }

    /** A patch of a chip's front band between heights [ya] and [yb] (at the front centre) and angles [t0]..[t1]. */
    private fun bandQuad(out: Scene, ya: Float, yb: Float, t0: Float, t1: Float, col: Int) {
        val lo = min(t0, t1); val hi = max(t0, t1)
        val m = max(2, ((hi - lo) / 9f).toInt() + 1)
        val pts = FloatArray(4 * (m + 1))
        var n = 0
        for (i in 0..m) { val th = rad(lo + (hi - lo) * i / m); pts[n++] = CX + R * sin(th); pts[n++] = ya + RY * cos(th) }
        for (i in m downTo 0) { val th = rad(lo + (hi - lo) * i / m); pts[n++] = CX + R * sin(th); pts[n++] = yb + RY * cos(th) }
        out.poly(pts, col)
    }

    /** Length of a rounded square path (half-size [a], corner radius [rc]). */
    internal fun squareLen(a: Float, rc: Float) = 8 * (a - rc) + 2 * PI.toFloat() * rc

    /** Point at arc length [s] along a rounded square round (0.5, 0.5), clockwise from the top-left end of the top side. */
    internal fun squareAt(a: Float, rc: Float, s0: Float): FloatArray {
        val st = 2 * (a - rc); val ar = PI.toFloat() / 2 * rc
        val per = 4 * (st + ar)
        var s = ((s0 % per) + per) % per
        for (side in 0 until 4) {
            val ang = side * PI.toFloat() / 2   // direction of travel: 0 = +x
            val dx = cos(ang); val dy = sin(ang)
            // start of this side
            val sx = when (side) { 0 -> -a + rc; 1 -> a; 2 -> a - rc; else -> -a }
            val sy = when (side) { 0 -> -a; 1 -> -a + rc; 2 -> a; else -> a - rc }
            if (s < st) return floatArrayOf(0.5f + sx + dx * s, 0.5f + sy + dy * s, dx, dy)
            s -= st
            if (s < ar) {
                // quarter turn round the corner centre
                val ccx = sx + dx * st - dy * rc
                val ccy = sy + dy * st + dx * rc
                val start = ang - PI.toFloat() / 2
                val phi = start + s / rc
                return floatArrayOf(0.5f + ccx + rc * cos(phi), 0.5f + ccy + rc * sin(phi), -sin(phi), cos(phi))
            }
            s -= ar
        }
        return floatArrayOf(0.5f - a + rc, 0.5f - a, 1f, 0f)
    }

    override fun reader(): SchemeReader = ChipReader()
}

/** A colour of hue [h] (degrees) and saturation [s] whose lightness is [l] (pure white blended in if needed). */
private fun tone(h: Float, s: Float, l: Float): Int {
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


private class ChipSnap(val t: Double, val p: Array<FloatArray?>, val mk: FloatArray)

private class ChipReader : SchemeReader {
    private var seenNow = false
    override val seen: Boolean get() = seenNow

    private var hFrame: DoubleArray? = null      // marquee model → image (unrotated), last good
    private var lastLock = -1e9
    private var pol = 0
    private var rot = -1
    private val small = SmallImg()
    private val hist = ArrayList<ChipSnap>()

    override fun feed(f: SigilFrame): Read? {
        val t = f.tMs / 1000.0
        if (t - lastLock > 2.0) { hist.clear() }
        val lock = locate(f, t)
        seenNow = lock != null
        if (lock == null) return null
        lastLock = t
        val snap = sample(f, lock, t) ?: return null
        hist.add(snap)
        while (hist.isNotEmpty() && t - hist[0].t > 9.0) hist.removeAt(0)
        return estimate()
    }

    // ------------------------------------------------------------------ locating the marquee

    private class Lock(val h: DoubleArray, val pol: Int, val mark: Float, val plate: Float, val scale: Float)

    private fun locate(f: SigilFrame, t: Double): Lock? {
        val prev = hFrame
        if (prev != null && t - lastLock < 0.6) {
            var h: DoubleArray? = refineSquare(f, prev, pol)
            if (h == null) h = refineSquare(f, prev, pol, true)?.let { refineSquare(f, it, pol) }
            if (h != null) orient(f, h, pol, rot)?.let { hFrame = h; return it }
        }
        hFrame = null
        val sm = small
        sm.load(f)
        val cls = sm.classes(12, 9)
        val lab = sm.lab
        for (want in intArrayOf(1, -1)) {
            val comps = sm.label(cls, want.toByte(), lab, if (want == 1) 1 else 1_000_000)
            // the stack (with its turntable) is the biggest compact blob of chip colour
            val stacks = comps.filter { c ->
                val bw = c.x1 - c.x0 + 1; val bh = c.y1 - c.y0 + 1
                val big = max(bw, bh)
                c.area >= 300 && big in 35..280 && min(bw, bh) > 0.5f * big && c.area > 0.2f * bw * bh
            }.sortedByDescending { it.area }.take(2)
            for (c in stacks) {
                // pixels per unit: the stack with its turntable spans about 0.73 (upright) to 0.95 (turned 45°) units
                val u0 = 2.0 * max(c.x1 - c.x0 + 1, c.y1 - c.y0 + 1) / 0.84
                val h0 = frameFromRays(f, 2.0 * c.cx + 1, 2.0 * c.cy + 1, u0, want)
                if (h0 == null) continue
                var h: DoubleArray? = h0
                for (it in 0 until 3) { h = h?.let { hh -> refineSquare(f, hh, want, it == 0) } }
                if (h == null) continue
                val lk = orient(f, h, want)
                if (lk == null) continue
                hFrame = h; pol = want
                return lk
            }
        }
        return null
    }

    /** Refines a marquee homography by edge search along the band's outer edge; null if it does not hold. */
    private fun refineSquare(f: SigilFrame, h: DoubleArray, pol: Int, wide: Boolean = false): DoubleArray? {
        val nh = fitSquareEdges(f, h, ChipStack.FO.toDouble(), 0.28, pol, if (wide) 0.045 else 0.03, if (wide) 0.05 else 0.017) ?: return null
        // the band must be there: band centre vs just outside / just inside
        val a = ChipStack.FO - ChipStack.FB / 2
        var good = 0; var tot = 0
        val bandV = FloatArray(32); val inV = FloatArray(32)
        val pt = DoubleArray(2)
        for (side in 0 until 4) for (i in 0 until 8) {
            val u = -0.3 + 0.6 * i / 7
            val (nx, ny) = sideNormal(side)
            val bx = 0.5 + sideX(side, u, a.toDouble()); val by = 0.5 + sideY(side, u, a.toDouble())
            val vb = sampleAt(f, nh, bx, by, pt)
            val vo = sampleAt(f, nh, bx + nx * (ChipStack.FB / 2 + 0.012), by + ny * (ChipStack.FB / 2 + 0.012), pt)
            val vi = sampleAt(f, nh, bx - nx * (ChipStack.FB / 2 + 0.022), by - ny * (ChipStack.FB / 2 + 0.022), pt)
            if (vb.isNaN() || vo.isNaN() || vi.isNaN()) continue
            bandV[tot] = vb; inV[tot] = vi
            if (pol * (vb - vo) > 12 && (side == 0 || side == 2 || pol * (vb - vi) > 12)) good++
            tot++
        }
        if (tot < 12 || good < tot * 0.75f) return null
        return nh
    }

    /** Finds which way up the stack is (4 turns) and the reference levels; null if the content does not fit. */
    private fun orient(f: SigilFrame, h: DoubleArray, pol: Int, only: Int = -1): Lock? {
        val pt = DoubleArray(2)
        // band and felt levels
        val a = (ChipStack.FO - ChipStack.FB / 2).toDouble()
        val bandL = FloatArray(16); val feltL = FloatArray(16)
        for (side in 0 until 4) for (i in 0 until 4) {
            val u = -0.25 + 0.5 * i / 3
            val (nx, ny) = sideNormal(side)
            val bx = 0.5 + sideX(side, u, a); val by = 0.5 + sideY(side, u, a)
            bandL[side * 4 + i] = sampleAt(f, h, bx, by, pt)
            // felt: beside the stack whichever way up it is (corners of the inside)
            val cx = 0.5 + 0.33 * (if (side == 0 || side == 3) -1 else 1); val cy = 0.5 + 0.33 * (if (side < 2) -1 else 1)
            feltL[side * 4 + i] = sampleAt(f, h, cx + (i - 1.5) * 0.012, cy, pt)
        }
        val bl = bandL.filter { !it.isNaN() }.sorted(); val fl = feltL.filter { !it.isNaN() }.sorted()
        if (bl.size < 6 || fl.size < 4) return null
        val mark = bl[bl.size / 2]; val plate = fl[fl.size / 2]
        val c = pol * (mark - plate)
        if (c < 14f) return null
        val mid = (mark + plate) / 2
        val sc = FloatArray(4) { -9f }
        for (r in 0 until 4) {
            if (only >= 0 && r != only) continue
            val hr = mul3(h, rot90(r))
            var s = 0f; var wt = 0f
            for (q in PROBES.indices step 4) {
                val v = sampleAt(f, hr, PROBES[q].toDouble(), PROBES[q + 1].toDouble(), pt)
                if (v.isNaN()) continue
                val w = PROBES[q + 3]
                s += w * PROBES[q + 2] * (pol * (v - mid)).coerceIn(-0.6f * c, 0.6f * c); wt += w
            }
            sc[r] = if (wt < 10f) -9f else s / (0.5f * c * wt)
        }
        var best = 0
        for (r in 1 until 4) if (sc[r] > sc[best]) best = r
        var second = -9f
        for (r in 0 until 4) if (r != best) second = max(second, sc[r])
        if (only >= 0) { if (sc[best] < 0.55f) return null }
        else if (sc[best] < 0.55f || sc[best] - second < 0.3f) return null
        rot = best
        val hc = mul3(h, rot90(best))
        // pixels per model unit at the centre
        val p0 = DoubleArray(2); val p1 = DoubleArray(2); val p2 = DoubleArray(2)
        hApply(hc, 0.5, 0.5, p0); hApply(hc, 0.6, 0.5, p1); hApply(hc, 0.5, 0.6, p2)
        val scale = sqrt(abs((p1[0] - p0[0]) * (p2[1] - p0[1]) - (p1[1] - p0[1]) * (p2[0] - p0[0]))).toFloat() * 10f
        return Lock(hc, pol, mark, plate, scale)
    }

    // ------------------------------------------------------------------ sampling the chips

    private fun sample(f: SigilFrame, lk: Lock, t: Double): ChipSnap? {
        val h = lk.h
        val pt = DoubleArray(2)
        val pol = lk.pol
        // felt references beside every chip and above / below the stack → plane S(x, y)
        val ps = PlaneFit()
        for (k in 0 until ChipStack.N) {
            val y = ChipStack.YF + k * ChipStack.HB + ChipStack.HB / 2
            for (side in intArrayOf(-1, 1)) {
                val x = ChipStack.CX + side * (ChipStack.R + 0.065f)
                hApply(h, x.toDouble(), y.toDouble(), pt)
                ps.add(pt[0], pt[1], f.disc(pt[0].toFloat(), pt[1].toFloat(), 0.012f * lk.scale).toDouble())
            }
        }
        for (x in floatArrayOf(0.2f, 0.8f)) { hApply(h, x.toDouble(), 0.165, pt); ps.add(pt[0], pt[1], f.disc(pt[0].toFloat(), pt[1].toFloat(), 0.01f * lk.scale).toDouble()) }
        for (x in floatArrayOf(0.155f, 0.845f)) { hApply(h, x.toDouble(), 0.84, pt); ps.add(pt[0], pt[1], f.disc(pt[0].toFloat(), pt[1].toFloat(), 0.01f * lk.scale).toDouble()) }
        val sPlane = ps.solve() ?: return null

        val g = GRID
        val spot = Array(ChipStack.N) { FloatArray(g) }
        val marg = Array(ChipStack.N) { FloatArray(g) }
        val sx = Array(ChipStack.N) { FloatArray(g) }
        val sy = Array(ChipStack.N) { FloatArray(g) }
        val mx = Array(ChipStack.N) { FloatArray(2 * g) }
        val my = Array(ChipStack.N) { FloatArray(2 * g) }
        val mv = Array(ChipStack.N) { FloatArray(2 * g) }
        val rSpot = max(0.6f, 0.009f * lk.scale)
        val rMarg = max(0.5f, 0.0045f * lk.scale)
        for (k in 0 until ChipStack.N) {
            val yk = ChipStack.YF + k * ChipStack.HB
            for (i in 0 until g) {
                val th = (G0 + GS * i) * PI / 180.0
                val x = ChipStack.CX + ChipStack.R * sin(th)
                val cy = ChipStack.RY * cos(th)
                hApply(h, x, yk + (ChipStack.HB + ChipStack.GAP) / 2.0 + cy, pt)
                sx[k][i] = pt[0].toFloat(); sy[k][i] = pt[1].toFloat()
                spot[k][i] = cross(f, pt[0].toFloat(), pt[1].toFloat(), rSpot)
                hApply(h, x, yk + ChipStack.GAP + ChipStack.MT / 2.0 + cy, pt)
                mx[k][2 * i] = pt[0].toFloat(); my[k][2 * i] = pt[1].toFloat()
                mv[k][2 * i] = cross(f, pt[0].toFloat(), pt[1].toFloat(), rMarg)
                hApply(h, x, yk + ChipStack.HB - ChipStack.MT / 2.0 + cy, pt)
                mx[k][2 * i + 1] = pt[0].toFloat(); my[k][2 * i + 1] = pt[1].toFloat()
                mv[k][2 * i + 1] = cross(f, pt[0].toFloat(), pt[1].toFloat(), rMarg)
            }
        }
        // body plane B from the margins (two passes: the marker and outliers dropped)
        var bPlane: DoubleArray? = null
        val c0 = pol * (lk.mark - lk.plate)
        for (pass in 0 until 2) {
            val pb = PlaneFit()
            for (k in 0 until ChipStack.N) for (i in 0 until 2 * g) {
                val v = mv[k][i].toDouble()
                if (bPlane != null) {
                    val e = planeAt(bPlane, mx[k][i].toDouble(), my[k][i].toDouble())
                    if (pol * (e - v) > 0.3 * c0) continue
                }
                pb.add(mx[k][i].toDouble(), my[k][i].toDouble(), v)
            }
            bPlane = pb.solve() ?: return null
        }
        val bp = bPlane!!
        val p = arrayOfNulls<FloatArray>(ChipStack.N)
        val mk = FloatArray(ChipStack.N) { Float.NaN }
        for (k in 0 until ChipStack.N) {
            val pk = FloatArray(g); val qk = FloatArray(g)
            var okc = true
            for (i in 0 until g) {
                val b = planeAt(bp, sx[k][i].toDouble(), sy[k][i].toDouble())
                val s = planeAt(sPlane, sx[k][i].toDouble(), sy[k][i].toDouble())
                val den = b - s
                if (pol * den < 0.35 * c0) { okc = false; break }
                pk[i] = ((b - spot[k][i]) / den).toFloat()
                val bm0 = planeAt(bp, mx[k][2 * i].toDouble(), my[k][2 * i].toDouble())
                val bm1 = planeAt(bp, mx[k][2 * i + 1].toDouble(), my[k][2 * i + 1].toDouble())
                qk[i] = (((bm0 - mv[k][2 * i]) + (bm1 - mv[k][2 * i + 1])) / 2 / den).toFloat()
            }
            if (!okc) continue
            p[k] = pk
            mk[k] = findMarker(pk, qk)
        }
        return ChipSnap(t, p, mk)
    }

    /** Centre angle (degrees, as seen) of a marker insert in this frame's window, or NaN. */
    private fun findMarker(p: FloatArray, q: FloatArray): Float {
        var best = -1; var bv = 0.62f
        for (i in q.indices) if (q[i] > bv) { bv = q[i]; best = i }
        if (best < 0) return Float.NaN
        var a = best; var b = best
        while (a > 0 && q[a - 1] > 0.42f) a--
        while (b < q.size - 1 && q[b + 1] > 0.42f) b++
        if (a == 0 || b == q.size - 1) return Float.NaN
        val width = (b - a + 1) * GS
        if (width < 7f || width > 34f) return Float.NaN
        for (i in q.indices) if ((i < a - 3 || i > b + 3) && q[i] > 0.6f) return Float.NaN
        var sw = 0f; var st = 0f
        for (i in a..b) { val w = q[i] - 0.42f; sw += w; st += w * (G0 + GS * i) }
        val c = st / sw
        val ci = ((c - G0) / GS).toInt().coerceIn(0, p.size - 1)
        if (p[ci] < 0.55f) return Float.NaN
        return c
    }

    // ------------------------------------------------------------------ putting the frames together

    private fun estimate(): Read? {
        // mirror: the direction the markers move (all chips turn the same way)
        var plus = 0; var minus = 0
        val last = FloatArray(ChipStack.N) { Float.NaN }
        val lastT = DoubleArray(ChipStack.N)
        for (s in hist) for (k in 0 until ChipStack.N) {
            val m = s.mk[k]
            if (m.isNaN()) continue
            if (!last[k].isNaN()) {
                val dt = s.t - lastT[k]
                if (dt > 0.03 && dt < 0.6) {
                    val v = ChipStack.wrap((m - last[k]).toDouble()) / dt
                    val w = ChipStack.omega(k)
                    if (abs(v) > 0.45 * w && abs(v) < 1.7 * w) { if (v > 0) plus++ else minus++ }
                }
            }
            last[k] = m; lastT[k] = s.t
        }
        val sgn = when {
            plus - minus >= 3 && plus >= 4 * minus -> 1
            minus - plus >= 3 && minus >= 4 * plus -> -1
            else -> return null
        }
        // each chip's spin phase from its marker sightings
        val ph = DoubleArray(ChipStack.N) { Double.NaN }
        for (k in 0 until ChipStack.N) {
            val angs = ArrayList<Double>()
            for (s in hist) { val m = s.mk[k]; if (!m.isNaN()) angs.add(ChipStack.wrap(sgn * m - ChipStack.omega(k) * s.t + 0.0) ) }
            if (angs.isEmpty()) continue
            var c = circMean(angs, null, 0.0)
            c = circMean(angs, c, 14.0)
            if (!c.isNaN()) ph[k] = c
        }
        // votes per insert
        val vote = FloatArray(SigilPayload.BITS)
        val wsum = FloatArray(SigilPayload.BITS)
        for (s in hist) for (k in 0 until ChipStack.N) {
            val pk = s.p[k] ?: continue
            if (ph[k].isNaN()) continue
            val base = ph[k] + ChipStack.omega(k) * s.t
            for (j in 1 until ChipStack.SLOTS) {
                val th = sgn * ChipStack.wrap(j * ChipStack.SLOT_DEG + base)
                if (abs(th) > WIN) continue
                val x = ((th - G0) / GS).toFloat()
                val i0 = x.toInt().coerceIn(0, GRID - 2)
                val fr = x - i0
                val pv = pk[i0] * (1 - fr) + pk[i0 + 1] * fr
                val c = cos(th * PI / 180.0).toFloat()
                val w = c * c
                val bi = 12 * k + j - 1
                vote[bi] += w * ((pv - 0.5f) * 2.5f).coerceIn(-1f, 1f)
                wsum[bi] += w
            }
        }
        var seenSym = 0
        for (sy in 0 until SigilPayload.SYMBOLS) {
            var ok = true
            for (b in 0 until 6) if (wsum[6 * sy + b] < 0.8f) ok = false
            if (ok) seenSym++
        }
        if (seenSym < 12) return null
        val bits = BooleanArray(SigilPayload.BITS) { (vote[it] > 0f) xor ChipStack.MASK[it] }
        val sure = FloatArray(SigilPayload.BITS) { if (wsum[it] < 0.8f) 0f else abs(vote[it]) }
        return SigilPayload.decodeBits(bits, sure)
    }

    companion object {
        const val G0 = -60f
        const val GS = 2f
        const val GRID = 61
        const val WIN = 52.0

        /** Orientation probes (content model): x, y, +1 chip / gold or -1 felt, weight. */
        val PROBES: FloatArray = run {
            val l = ArrayList<Float>()
            fun add(x: Float, y: Float, s: Float, w: Float) { l.add(x); l.add(y); l.add(s); l.add(w) }
            add(0.5f - 0.11f, ChipStack.YF, 1f, 1f); add(0.5f + 0.11f, ChipStack.YF, 1f, 1f); add(0.5f, ChipStack.YF - 0.045f, 1f, 1f)
            val th = 68f * PI.toFloat() / 180f
            for (k in 0 until ChipStack.N) for (side in intArrayOf(-1, 1)) {
                val yk = ChipStack.YF + k * ChipStack.HB
                val x = ChipStack.CX + side * ChipStack.R * sin(th)
                add(x, yk + ChipStack.GAP + ChipStack.MT / 2 + ChipStack.RY * cos(th), 1f, 0.5f)
                add(x, yk + ChipStack.HB - ChipStack.MT / 2 + ChipStack.RY * cos(th), 1f, 0.5f)
                add(ChipStack.CX + side * (ChipStack.R + 0.065f), yk + ChipStack.HB / 2, -1f, 0.5f)
            }
            add(0.2f, 0.165f, -1f, 1f); add(0.8f, 0.165f, -1f, 1f)
            // the turntable's sides (and the felt where they would be if the stack were upside down)
            for (side in intArrayOf(-1, 1)) {
                add(0.5f + side * 0.28f, ChipStack.BY + 0.008f, 1f, 3f)
                add(0.5f + side * 0.28f, 1f - ChipStack.BY - 0.008f, -1f, 3f)
            }
            l.toFloatArray()
        }
    }
}

/** Circular mean (degrees) of [a]; with [around], only angles within [tol] of it. NaN if none. */
private fun circMean(a: List<Double>, around: Double?, tol: Double): Double {
    var sx = 0.0; var sy = 0.0; var n = 0
    for (x in a) {
        if (around != null && abs(ChipStack.wrap(x - around)) > tol) continue
        sx += cos(x * PI / 180); sy += sin(x * PI / 180); n++
    }
    if (n == 0) return Double.NaN
    return atan2(sy, sx) * 180 / PI
}

// ====================================================================== geometry helpers (private to this file)

private fun hApply(h: DoubleArray, x: Double, y: Double, out: DoubleArray) {
    val d = h[6] * x + h[7] * y + h[8]
    out[0] = (h[0] * x + h[1] * y + h[2]) / d
    out[1] = (h[3] * x + h[4] * y + h[5]) / d
}

private fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
    val r = DoubleArray(9)
    for (i in 0 until 3) for (j in 0 until 3) { var s = 0.0; for (k in 0 until 3) s += a[3 * i + k] * b[3 * k + j]; r[3 * i + j] = s }
    return r
}

/** Content model → marquee model: a turn by r quarter turns (clockwise) round the centre. */
private fun rot90(r: Int): DoubleArray = when (r and 3) {
    0 -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    1 -> doubleArrayOf(0.0, -1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    2 -> doubleArrayOf(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0, 0.0, 0.0, 1.0)
    else -> doubleArrayOf(0.0, 1.0, 0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 1.0)
}

private fun sampleAt(f: SigilFrame, h: DoubleArray, x: Double, y: Double, pt: DoubleArray): Float {
    hApply(h, x, y, pt)
    if (!inside(f, pt[0], pt[1])) return Float.NaN
    return cross(f, pt[0].toFloat(), pt[1].toFloat(), 1.2f)
}

/** Mean of a 5-tap cross of half-size [r] pixels (centre counted twice). */
private fun cross(f: SigilFrame, x: Float, y: Float, r: Float): Float {
    if (r < 0.6f) return f.at(x, y)
    return (2 * f.at(x, y) + f.at(x - r, y) + f.at(x + r, y) + f.at(x, y - r) + f.at(x, y + r)) / 6f
}

private fun inside(f: SigilFrame, x: Double, y: Double, m: Double = 2.0) = x >= m && y >= m && x <= f.w - 1 - m && y <= f.h - 1 - m

/** Side 0 top, 1 right, 2 bottom, 3 left (clockwise); u runs clockwise along it. */
private fun sideX(side: Int, u: Double, a: Double) = when (side) { 0 -> u; 1 -> a; 2 -> -u; else -> -a }
private fun sideY(side: Int, u: Double, a: Double) = when (side) { 0 -> -a; 1 -> u; 2 -> a; else -> -u }
private fun sideNormal(side: Int): Pair<Double, Double> = when (side) { 0 -> 0.0 to -1.0; 1 -> 1.0 to 0.0; 2 -> 0.0 to 1.0; else -> -1.0 to 0.0 }

/** Least-squares plane v = a + b x + c y. */
private class PlaneFit {
    private val m = Array(3) { DoubleArray(4) }
    private var n = 0
    fun add(x: Double, y: Double, v: Double) {
        val r = doubleArrayOf(1.0, x, y)
        for (i in 0 until 3) { for (j in 0 until 3) m[i][j] += r[i] * r[j]; m[i][3] += r[i] * v }
        n++
    }
    fun solve(): DoubleArray? = if (n < 3) null else Fit.solve(Array(3) { m[it].copyOf() })
}

private fun planeAt(p: DoubleArray, x: Double, y: Double) = p[0] + p[1] * x + p[2] * y

/**
 * Edge search along the four straight sides of a rounded square's outer edge (half-size [a] round the model centre,
 * straight part used: |u| ≤ [span]), from [din] inside to [dout] outside; lines fitted per side, corners intersected.
 * [pol] +1: the band is lighter than what lies outside it.
 */
private fun fitSquareEdges(f: SigilFrame, h: DoubleArray, a: Double, span: Double, pol: Int, din: Double, dout: Double): DoubleArray? {
    val lines = Array(4) { DoubleArray(3) }
    val px = DoubleArray(12); val py = DoubleArray(12); val pw = DoubleArray(12)
    val pa = DoubleArray(2); val pb = DoubleArray(2)
    val prof = FloatArray(48)
    for (side in 0 until 4) {
        val (nx, ny) = sideNormal(side)
        var n = 0
        for (i in 0 until 12) {
            val u = -span + 2 * span * i / 11
            val x = 0.5 + sideX(side, u, a); val y = 0.5 + sideY(side, u, a)
            hApply(h, x - nx * din, y - ny * din, pa)
            hApply(h, x + nx * dout, y + ny * dout, pb)
            if (!inside(f, pa[0], pa[1]) || !inside(f, pb[0], pb[1])) continue
            val len = sqrt((pb[0] - pa[0]) * (pb[0] - pa[0]) + (pb[1] - pa[1]) * (pb[1] - pa[1]))
            val m = (len / 0.8).toInt().coerceIn(10, 48)
            for (k in 0 until m) {
                val q = k / (m - 1.0)
                prof[k] = f.at((pa[0] + (pb[0] - pa[0]) * q).toFloat(), (pa[1] + (pb[1] - pa[1]) * q).toFloat())
            }
            var bi = -1; var bv = 0f
            for (k in 1 until m - 1) { val e = -pol * (prof[k + 1] - prof[k - 1]); if (e > bv) { bv = e; bi = k } }
            if (bi < 0 || bv < 6f) continue
            var off = 0.0
            if (bi in 2 until m - 2) {
                val e0 = -pol * (prof[bi] - prof[bi - 2]); val e2 = -pol * (prof[bi + 2] - prof[bi])
                val den = e0 - 2 * bv + e2
                if (den < 0) off = (0.5 * (e0 - e2) / den).coerceIn(-0.5, 0.5)
            }
            val q = (bi + off) / (m - 1.0)
            px[n] = pa[0] + (pb[0] - pa[0]) * q; py[n] = pa[1] + (pb[1] - pa[1]) * q; pw[n] = bv.toDouble()
            n++
        }
        if (n < 5) return null
        val ln = fitLine(px, py, pw, n) ?: return null
        lines[side] = ln
    }
    val img = FloatArray(8)
    for (c in 0 until 4) {
        // corner between side c-1 and c: TL (3,0), TR (0,1), BR (1,2), BL (2,3)
        val l1 = lines[(c + 3) % 4]; val l2 = lines[c]
        val d = l1[0] * l2[1] - l1[1] * l2[0]
        if (abs(d) < 1e-6) return null
        img[2 * c] = ((l1[1] * l2[2] - l2[1] * l1[2]) / d).toFloat()
        img[2 * c + 1] = ((l2[0] * l1[2] - l1[0] * l2[2]) / d).toFloat()
    }
    val mod = floatArrayOf((0.5 - a).toFloat(), (0.5 - a).toFloat(), (0.5 + a).toFloat(), (0.5 - a).toFloat(),
        (0.5 + a).toFloat(), (0.5 + a).toFloat(), (0.5 - a).toFloat(), (0.5 + a).toFloat())
    return Fit.homography(mod, img)
}

/** Weighted total-least-squares line a x + b y + c = 0 (a² + b² = 1), one round of outlier removal. */
private fun fitLine(x: DoubleArray, y: DoubleArray, w: DoubleArray, n: Int): DoubleArray? {
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
        var rms = 0.0; var k = 0
        for (i in 0 until n) { val d = a * x[i] + b * y[i] + line[2]; rms += d * d; k++ }
        rms = sqrt(rms / k)
        var kept = 0
        for (i in 0 until n) { val d = abs(a * x[i] + b * y[i] + line[2]); use[i] = d <= max(1.2, 2.2 * rms); if (use[i]) kept++ }
        if (kept < min(5, n)) return null
    }
    return line
}

/**
 * First guess of the marquee homography: rays from a point inside (the stack), a bar detector for the gold band
 * (lighter / darker than the felt on both sides, about [ChipStack.FB] wide), the band's outer edge per ray, its
 * fourfold harmonic for the turn, a line per side. Rays that leave the image are skipped.
 */
private fun frameFromRays(f: SigilFrame, cx: Double, cy: Double, u0: Double, pol: Int): DoubleArray? {
    val nr = 72
    val dist = DoubleArray(nr) { Double.NaN }
    val prof = FloatArray(800)
    val pre = DoubleArray(801)
    val resp = DoubleArray(800)
    val r0 = 0.24 * u0; val r1 = 0.64 * u0
    for (i in 0 until nr) {
        val ang = 2 * PI * i / nr
        val dx = cos(ang); val dy = sin(ang)
        var n = 0
        var r = r0
        while (r <= r1 && n < prof.size) {
            val x = cx + dx * r; val y = cy + dy * r
            if (x < 1 || y < 1 || x > f.w - 2 || y > f.h - 2) break
            prof[n++] = f.at(x.toFloat(), y.toFloat())
            r += 1.0
        }
        pre[0] = 0.0
        for (k in 0 until n) pre[k + 1] = pre[k] + prof[k]
        // bar response per outer-edge position (best over a few widths); the marquee is the outermost strong bar
        resp.fill(0.0)
        var best = 0.0
        for (wf in doubleArrayOf(0.75, 1.0, 1.35)) {
            val w = max(2, (wf * ChipStack.FB * u0).toInt())
            val m = max(2, (0.45 * ChipStack.FB * u0).toInt())
            val mi = max(2, (0.3 * ChipStack.FB * u0).toInt())
            for (e in (w + mi) until n - 2) {
                val mo = min(m, n - e)
                val band = (pre[e] - pre[e - w]) / w
                val outer = (pre[e + mo] - pre[e]) / mo
                val inner = (pre[e - w] - pre[e - w - mi]) / mi
                val rr = min(pol * (band - outer), pol * (band - inner))
                if (rr > resp[e]) resp[e] = rr
                if (rr > best) best = rr
            }
        }
        if (best < 18.0) continue
        val thr = max(18.0, 0.6 * best)
        for (e in n - 2 downTo 1) {
            if (resp[e] >= thr && resp[e] >= resp[e - 1] && resp[e] >= resp[e + 1]) { dist[i] = r0 + e; break }
        }
    }
    // d(φ) ≈ a0 + a1 cos φ + b1 sin φ + a4 cos 4φ + b4 sin 4φ over the rays that found the band (outliers dropped)
    var sol: DoubleArray? = null
    for (pass in 0 until 3) {
        val m = Array(5) { DoubleArray(6) }
        var valid = 0
        for (i in 0 until nr) {
            if (dist[i].isNaN()) continue
            val ang = 2 * PI * i / nr
            val row = doubleArrayOf(1.0, cos(ang), sin(ang), cos(4 * ang), sin(4 * ang))
            val prev = sol
            if (prev != null) {
                val fit = prev[0] + prev[1] * row[1] + prev[2] * row[2] + prev[3] * row[3] + prev[4] * row[4]
                if (abs(dist[i] - fit) > (if (pass == 1) 0.07 else 0.035) * u0) continue
            }
            for (p in 0 until 5) { for (q in 0 until 5) m[p][q] += row[p] * row[q]; m[p][5] += row[p] * dist[i] }
            valid++
        }
        if (valid < nr / 3) return null
        sol = Fit.solve(m) ?: return null
    }
    sol!!
    val corner = atan2(sol[4], sol[3]) / 4
    // keep rays whose distance agrees with the fit
    val lines = Array(4) { DoubleArray(3) }
    val px = DoubleArray(nr); val py = DoubleArray(nr); val pw = DoubleArray(nr)
    for (sd in 0 until 4) {
        val nrm = corner + PI / 4 + sd * PI / 2
        var n = 0
        for (i in 0 until nr) {
            if (dist[i].isNaN()) continue
            val ang = 2 * PI * i / nr
            var da = (ang - nrm) % (2 * PI); if (da > PI) da -= 2 * PI; if (da < -PI) da += 2 * PI
            if (abs(da) > 32 * PI / 180) continue
            val fit = sol[0] + sol[1] * cos(ang) + sol[2] * sin(ang) + sol[3] * cos(4 * ang) + sol[4] * sin(4 * ang)
            if (abs(dist[i] - fit) > 0.035 * u0) continue
            px[n] = cx + cos(ang) * dist[i]; py[n] = cy + sin(ang) * dist[i]; pw[n] = 1.0; n++
        }
        if (n < 3) return null
        lines[sd] = fitLine(px, py, pw, n) ?: return null
    }
    val img = FloatArray(8)
    for (k in 0 until 4) {
        val l1 = lines[(k + 3) % 4]; val l2 = lines[k]
        val dd = l1[0] * l2[1] - l1[1] * l2[0]
        if (abs(dd) < 1e-6) return null
        img[2 * k] = ((l1[1] * l2[2] - l2[1] * l1[2]) / dd).toFloat()
        img[2 * k + 1] = ((l2[0] * l1[2] - l1[0] * l2[2]) / dd).toFloat()
    }
    val a = ChipStack.FO
    val mod = floatArrayOf(0.5f - a, 0.5f - a, 0.5f + a, 0.5f - a, 0.5f + a, 0.5f + a, 0.5f - a, 0.5f + a)
    return Fit.homography(mod, img)
}

/** A half-size copy of a frame with an integral image, a contrast-gated threshold and component labelling (buffers reused). */
private class SmallImg {
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
