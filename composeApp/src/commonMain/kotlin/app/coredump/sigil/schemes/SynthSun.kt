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
 * ARCADE: attract mode of an outrun coin-op. A big neon sun over a scrolling perspective grid, mountain ranges on both
 * sides, twinkling stars, a blinking INSERT COIN bar. The sun's cut-out slats drift downward as in the classic picture
 * (thicker as they sink) and its colours cycle.
 *
 * Data: every slat is one 6-bit symbol: across the sun it has six cells, each thin (0) or thick (1), blended into a
 * heat-shimmer ripple. The slats loop: 14 symbols and one missing slat (sync), 3 slats a second (a 5 s loop).
 *
 * Reader: the sun is the biggest bright (or dark) mark found with a large threshold window; an ellipse is fitted to
 * its hull and refined on the luminance edge; "up" is where the solid cap's mass lies; the slat angle and phase come
 * from the sharpest comb fit of the profile along the sun's axis. Each visible slat's cells are read as the mean
 * darkness over one slat pitch (blur-proof), divided by the sun-to-sky contrast and the known taper. Slats are named
 * across frames from the timestamps (the drift is a fixed speed), the missing slat names the loop's start, votes add
 * up; the mirror (cell order) is decided by trying both orders.
 */
object SynthSun : SigilScheme {
    override val name = "sun"

    // ------------------------------------------------------------------ layout
    internal const val CX = 0.5f
    internal const val CY = 0.4f
    internal const val R = 0.31f
    internal const val SLOTS = 15
    internal const val SPEED = 3f            // slats per second
    internal const val PV = 0.135f           // slat pitch, in sun radii
    internal const val VZ0 = -0.08f          // where slats appear (sun coordinates, v down, radius 1)
    internal const val VREAD = 0.76f         // slats are read above this
    internal const val THIN = 0.24f
    internal const val THICK = 0.62f
    internal const val CELL = 0.18f          // cell width (sun radii); six cells centred on the axis
    internal const val HORIZON = 0.745f

    internal fun taper(v: Float) = 0.8f + 0.35f * ((v - VZ0) / (1f - VZ0)).coerceIn(0f, 1f)
    internal fun cellU(c: Int) = (c - 2.5f) * CELL

    internal val MASK = BooleanArray(SigilPayload.BITS).also { m ->
        var s = 0x51D_0C0DEL
        for (i in m.indices) { s = (s * 6364136223846793005L + 1442695040888963407L); m[i] = (s ushr 61) and 1L == 1L }
    }

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val plate = ink.plate; val main = ink.main
        val hue = t * 24f

        // ---- stars (twinkling), sky haze
        for (k in 0 until 26) {
            val sx = ((k * 0.6180339f + 0.05f) % 1f)
            val sy = ((k * 0.3819660f + 0.02f) % 1f) * 0.62f + 0.02f
            val dx = sx - CX; val dy = sy - CY
            if (dx * dx + dy * dy < (R + 0.06f) * (R + 0.06f)) continue
            val tw = 0.5f + 0.5f * sin(t * (1.3f + (k % 5) * 0.4f) + k * 2.1f)
            out.circle(sx, sy, 0.0025f + 0.003f * tw, Colors.mix(plate, Colors.hueShift(ink.alt, k * 37f), 0.12f + 0.25f * tw))
        }
        out.glow(CX, CY, R * 1.55f, Colors.alpha(Colors.hueShift(ink.alt, hue), 0.1f))

        // ---- mountains on both sides (behind; dark with neon ridges)
        mountains(out, ink, t, left = true)
        mountains(out, ink, t, left = false)

        // ---- the sun: horizontal strips with a hue gradient (same lightness), then the slats cut out
        val strips = 14
        for (i in 0 until strips) {
            val v0 = -1f + 2f * i / strips; val v1 = -1f + 2f * (i + 1) / strips
            val c = lumaShift(main, hue + 70f * (i / (strips - 1f)))
            out.poly(chord(v0, v1 + 0.004f), c)
        }
        val shift = SPEED * t
        val n0 = floor(shift - (1.02f - VZ0) / PV).toInt() - 1
        val n1 = floor(shift).toInt() + 1
        for (n in n0..n1) {
            val v = VZ0 + (shift - n) * PV
            if (v < VZ0 - 0.0001f || v > 1.02f) continue
            val k = ((n % SLOTS) + SLOTS) % SLOTS
            if (k == 0) continue   // the sync: no slat
            val j = k - 1
            slat(out, v, plate) { c ->
                val b = 6 * j + c
                if (bits[b] != MASK[b]) THICK else THIN
            }
        }
        // a slat fading in at the top edge of the zone (emerging), drawn thin over the cap
        val fr = shift - floor(shift)
        val nNext = floor(shift).toInt() + 1
        if (((nNext % SLOTS) + SLOTS) % SLOTS != 0) {
            val vv = VZ0 - (1f - fr) * PV
            if (vv > VZ0 - 0.4f * PV) slat(out, vv, Colors.mix(plate, main, 0.55f), -0.02f) { 0.18f * (1f - (VZ0 - vv) / (0.4f * PV)) }
        }

        // ---- horizon and the scrolling grid floor
        out.rect(0.5f, (HORIZON + 1f) / 2, 1f, 1f - HORIZON, plate)
        val gridC = Colors.hueShift(Colors.mix(plate, ink.alt, 0.32f), -hue * 0.5f)
        out.line(0f, HORIZON, 1f, HORIZON, 0.004f, Colors.mix(plate, ink.alt, 0.45f), round = false)
        val vpX = 0.5f; val vpY = HORIZON - 0.06f
        for (k in -9..9) {
            val xb = 0.5f + k * 0.14f
            // from the horizon down to the bottom, converging on the vanishing point
            val fx = (HORIZON - vpY) / (1f - vpY)
            out.line(vpX + (xb - vpX) * fx, HORIZON, xb, 1f, 0.003f, gridC, round = false)
        }
        val scroll = (t * 0.9f) % 1f
        for (k in 0 until 7) {
            val z = (k + 1f - scroll)            // depth in rows
            val y = HORIZON + (1f - HORIZON) * (1f / (1f + 0.0f)) * (1.6f / (z + 0.6f)) * 0.5f
            if (y > 1f || y < HORIZON) continue
            out.line(0f, y, 1f, y, 0.0025f + 0.002f * (y - HORIZON) / (1f - HORIZON), gridC, round = false)
        }

        // ---- attract mode: INSERT COIN blinking, a credit counter
        if ((t * 1.6f) % 2f < 1.3f) {
            val c = Colors.mix(plate, Colors.hueShift(ink.alt, 180f + hue), 0.6f)
            var x = 0.32f
            for (w in floatArrayOf(0.02f, 0.035f, 0.03f, 0.035f, 0.03f, 0.02f, 0f, 0.035f, 0.035f, 0.02f, 0.035f)) {
                if (w == 0f) { x += 0.03f; continue }
                out.rect(x + w / 2, 0.955f, w, 0.022f, c, corner = 0.003f)
                x += w + 0.012f
            }
        }
    }

    /** [c] turned [deg] round the colour wheel, kept at the camera luma (Rec.601 Y') of [c]. */
    internal fun lumaShift(c: Int, deg: Float): Int {
        fun y(q: Int) = 0.299f * Colors.r(q) + 0.587f * Colors.g(q) + 0.114f * Colors.b(q)
        val want = y(c)
        var out = Colors.hueShift(c, deg)
        repeat(8) {
            val have = y(out)
            if (abs(have - want) < 1.5f) return out
            out = if (have < want) Colors.mix(out, 0xFFFFFFFF.toInt(), min(1f, (want - have) / max(1f, 255f - have)))
            else Colors.mix(out, 0xFF000000.toInt(), min(1f, (have - want) / max(1f, have)))
        }
        return out
    }

    /** The disc between sun heights v0..v1 (sun coordinates) as a polygon in the unit square. */
    private fun chord(v0: Float, v1: Float): FloatArray {
        val n = 10
        val pts = FloatArray(4 * (n + 1))
        for (i in 0..n) {
            val v = v0 + (v1 - v0) * i / n
            val hw = sqrt(max(0f, 1f - v * v))
            pts[2 * i] = CX + hw * R; pts[2 * i + 1] = CY + v * R
            pts[4 * (n + 1) - 2 - 2 * i] = CX - hw * R; pts[4 * (n + 1) - 1 - 2 * i] = CY + v * R
        }
        return pts
    }

    /** One slat at height [v]: six cells of thickness duty(cell), blended, neutral outside the cells. */
    private fun slat(out: Scene, v: Float, color: Int, overhang: Float = 0.05f, duty: (Int) -> Float) {
        val hw = sqrt(max(0f, 1f - v * v)) + overhang
        val tp = taper(v) * PV
        val m = 33
        val pts = FloatArray(4 * m)
        val d = FloatArray(6) { duty(it) }
        val neutral = (THIN + THICK) / 2
        for (i in 0 until m) {
            val u = -hw + 2 * hw * i / (m - 1)
            // thickness at u: cell plateaus with cosine blends between neighbours
            val fc = u / CELL + 3f
            val c0 = floor(fc - 0.5f).toInt()
            val fr = fc - 0.5f - c0
            val a = if (c0 < 0 || c0 > 5) neutral else d[c0]
            val b = if (c0 + 1 < 0 || c0 + 1 > 5) neutral else d[c0 + 1]
            val s = ((fr - 0.22f) / 0.56f).coerceIn(0f, 1f)
            val w = (a + (b - a) * (0.5f - 0.5f * cos(s * PI.toFloat()))) * tp
            pts[2 * i] = CX + u * R; pts[2 * i + 1] = CY + (v - w / 2) * R
            pts[4 * m - 2 - 2 * i] = CX + u * R; pts[4 * m - 1 - 2 * i] = CY + (v + w / 2) * R
        }
        out.poly(pts, color)
    }

    private fun mountains(out: Scene, ink: Ink, t: Float, left: Boolean) {
        val plate = ink.plate
        val fill = Colors.mix(plate, ink.main, 0.06f)
        val ridge = Colors.hueShift(Colors.mix(plate, ink.alt, 0.5f), 200f + 15f * sin(t * 0.5f))
        val xs = if (left) floatArrayOf(0f, 0.04f, 0.09f, 0.12f, 0.155f) else floatArrayOf(1f, 0.95f, 0.91f, 0.875f, 0.845f)
        val ys = floatArrayOf(0.6f, 0.53f, 0.62f, 0.6f, HORIZON)
        val pts = FloatArray(2 * (xs.size + 1))
        for (i in xs.indices) { pts[2 * i] = xs[i]; pts[2 * i + 1] = ys[i] }
        pts[2 * xs.size] = xs[0]; pts[2 * xs.size + 1] = HORIZON
        out.poly(pts, fill)
        for (i in 0 until xs.size - 1) out.line(xs[i], ys[i], xs[i + 1], ys[i + 1], 0.003f, ridge)
    }

    override fun reader(): SchemeReader = SunReader()
}

// ====================================================================== reader


private class SunReader : SchemeReader {
    override var seen = false
        private set
    private var pol = true
    // votes per slat label (mod SLOTS) × cell, in the image's cell order; sync evidence per label
    private val acc = FloatArray(SynthSun.SLOTS * 6)
    private val seenL = FloatArray(SynthSun.SLOTS)
    private val syncEv = FloatArray(SynthSun.SLOTS)
    private var phC = 0f; private var phS = 0f   // circular mean of the fractional slat phase
    private var lastOk = Long.MIN_VALUE / 2

    override fun feed(f: SigilFrame): Read? {
        seen = false
        val half = SHalf(f)
        var g: SGeo? = null
        for (k in 0 until 2) {
            val p = if (k == 0) pol else !pol
            g = sLocate(f, p, half)
            if (g != null) { pol = p; break }
        }
        if (g == null) {
            if (f.tMs - lastOk > 2500) reset()
            return null
        }
        seen = true
        lastOk = f.tMs
        val obs = sRead(f, g) ?: return null
        // name the slats: S = speed·t − (v − VZ0)/PV is an integer plus a constant per session
        val tt = SynthSun.SPEED * (f.tMs % 600_000L) / 1000.0
        for (o in obs) {
            val s = tt - (o.v - SynthSun.VZ0) / SynthSun.PV
            val a = (s - floor(s)) * 2 * PI
            phC += cos(a).toFloat(); phS += sin(a).toFloat()
        }
        val frac = (atan2(phS, phC) / (2 * PI)).let { if (it < 0) it + 1 else it }
        for (i in acc.indices) acc[i] *= 0.96f
        for (i in syncEv.indices) { syncEv[i] *= 0.96f; seenL[i] *= 0.96f }
        for (o in obs) {
            val s = tt - (o.v - SynthSun.VZ0) / SynthSun.PV - frac
            val off = s - s.roundToInt()
            if (abs(off) > 0.3) continue
            val lab = (((s.roundToInt() % SynthSun.SLOTS) + SynthSun.SLOTS) % SynthSun.SLOTS)
            seenL[lab] += 1f
            if (o.missing) syncEv[lab] += 1f else {
                syncEv[lab] -= 1f
                for (c in 0 until 6) acc[lab * 6 + c] += o.cells[c]
            }
        }
        // the loop's start: the label most often seen without a slat
        var ls = -1; var best = 0.8f; var second = 0f
        for (l in 0 until SynthSun.SLOTS) { if (syncEv[l] > best) { second = best; best = syncEv[l]; ls = l } else if (syncEv[l] > second) second = syncEv[l] }
        if (ls < 0) return null
        for (j in 0 until 14) if (seenL[(ls + 1 + j) % SynthSun.SLOTS] < 0.5f) return null
        for (mir in 0 until 2) {
            val bits = BooleanArray(SigilPayload.BITS); val sure = FloatArray(SigilPayload.BITS)
            for (j in 0 until 14) {
                val lab = (ls + 1 + j) % SynthSun.SLOTS
                for (c in 0 until 6) {
                    val a = acc[lab * 6 + (if (mir == 0) c else 5 - c)]
                    val b = 6 * j + c
                    bits[b] = (a > 0f) != SynthSun.MASK[b]; sure[b] = abs(a)
                }
            }
            val r = SigilPayload.decodeBits(bits, sure)
            if (r != null) return r
        }
        return null
    }

    private fun reset() {
        acc.fill(0f); seenL.fill(0f); syncEv.fill(0f); phC = 0f; phS = 0f
    }
}

/** The sun in the camera: image point = (cx, cy) + A·(u, v), sun coordinates (radius 1, v down). */
internal class SGeo(val cx: Float, val cy: Float, val a: FloatArray, val sgn: Float) {
    fun x(u: Float, v: Float) = cx + a[0] * u + a[1] * v
    fun y(u: Float, v: Float) = cy + a[2] * u + a[3] * v
}

internal class SObs(val v: Float, val missing: Boolean, val cells: FloatArray)

internal fun sLocate(f: SigilFrame, sunLight: Boolean, half: SHalf = SHalf(f)): SGeo? {
    // the sun is lighter than the sky on dark plates: "marks" of the light polarity
    val mk = half.marks(!sunLight, 81, 12f, keepRuns = true)
    val sgn = if (sunLight) 1f else -1f    // + : sun brighter
    val order = (0 until mk.n).filter { max(mk.bw(it), mk.bh(it)) > 60 && mk.area[it] > 2500 }.sortedByDescending { mk.area[it] }
    for (id in order.take(6)) {
        val g = sFit(f, mk, id, sgn) ?: continue
        return g
    }
    return null
}

private fun sFit(f: SigilFrame, mk: SMarks, id: Int, sgn: Float): SGeo? {
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
    if (xs.size < 30) return null
    val hull = sHull(xs, ys)
    val hn = hull.size / 2
    val c0 = Fit.circle(FloatArray(hn) { hull[2 * it] }, FloatArray(hn) { hull[2 * it + 1] }) ?: return null
    if (c0[2] < 25f || c0[2] > 400f) return null
    // cheap check: the hull must be round (a disc or a disc's cap), not a box
    var hs = 0f
    for (i in 0 until hn) { val d = sqrt((hull[2 * i] - c0[0]) * (hull[2 * i] - c0[0]) + (hull[2 * i + 1] - c0[1]) * (hull[2 * i + 1] - c0[1])) - c0[2]; hs += d * d }
    if (sqrt(hs / hn) > 0.13f * c0[2]) return null
    // start from the circle, then edge points on the luminance → ellipse (twice, narrowing the search)
    var e = floatArrayOf(c0[0], c0[1], c0[2], 0f, 0f, c0[2])
    for (round in 0 until 3) {
        val rx = ArrayList<Float>(); val ry = ArrayList<Float>()
        val n = 48
        val win = if (round == 0) 0.25f else 0.1f
        for (i in 0 until n) {
            val th = 2 * PI.toFloat() * i / n
            val dx = e[2] * cos(th) + e[3] * sin(th); val dy = e[4] * cos(th) + e[5] * sin(th)
            val px = e[0] + dx; val py = e[1] + dy
            val l = sqrt(dx * dx + dy * dy)
            if (l < 10f) return null
            val ux = dx / l; val uy = dy / l
            val inner = sgn * f.at(e[0] + dx * (0.9f - win), e[1] + dy * (0.9f - win))
            val outer = sgn * f.at(e[0] + dx * (1.1f + win), e[1] + dy * (1.1f + win))
            if (inner - outer < 20f) continue
            val hv = (inner + outer) / 2
            // the outermost sun→sky crossing in the window
            var hit = Float.NaN
            var d = (1f + win) * l
            var prev = sgn * f.at(e[0] + ux * d, e[1] + uy * d)
            while (d > (1f - win) * l) {
                val dn = d - 0.5f
                val v = sgn * f.at(e[0] + ux * dn, e[1] + uy * dn)
                if (prev < hv && v >= hv) { hit = dn + 0.5f * (hv - v) / (prev - v); break }
                prev = v; d = dn
            }
            if (hit.isNaN()) continue
            rx.add(e[0] + ux * hit); ry.add(e[1] + uy * hit)
        }
        if (rx.size < (if (round == 2) 26 else 16)) return null
        e = sEllipse(sHullOrAll(rx, ry)) ?: return null
        if (round == 2) {
            // the points must sit on the ellipse (a light screen corner or a box would not): drop the worst fifth
            // (slat ends, glare), refit, check the spread
            fun resid(e: FloatArray): FloatArray? {
                val det0 = e[2] * e[5] - e[3] * e[4]
                if (abs(det0) < 1f) return null
                return FloatArray(rx.size) { i ->
                    val dx = rx[i] - e[0]; val dy = ry[i] - e[1]
                    val ux = (e[5] * dx - e[3] * dy) / det0; val uy = (-e[4] * dx + e[2] * dy) / det0
                    abs(sqrt(ux * ux + uy * uy) - 1f)
                }
            }
            val r0 = resid(e) ?: return null
            val lim = r0.copyOf().also { it.sort() }[(rx.size * 0.65f).toInt()]
            val kx = ArrayList<Float>(); val ky = ArrayList<Float>()
            for (i in rx.indices) if (r0[i] <= lim) { kx.add(rx[i]); ky.add(ry[i]) }
            e = sEllipse(sHullOrAll(kx, ky)) ?: return null
            val r1 = resid(e) ?: return null
            var se = 0f; var m = 0
            for (i in rx.indices) if (r0[i] <= lim) { se += r1[i] * r1[i]; m++ }
            val rms = sqrt(se / m)
            if (rms > 0.016f) return null
        }
    }
    val cx = e[0]; val cy = e[1]
    // L maps the unit circle onto the ellipse: columns (e2, e4) and (e3, e5)
    val l00 = e[2]; val l01 = e[3]; val l10 = e[4]; val l11 = e[5]
    val det = l00 * l11 - l01 * l10
    if (abs(det) < 100f) return null
    val r1 = sqrt(l00 * l00 + l10 * l10); val r2 = sqrt(l01 * l01 + l11 * l11)
    if (min(r1, r2) / max(r1, r2) < 0.6f || max(r1, r2) < 30f) return null
    // "down": away from the lit mass (the solid cap is up, the slats cut the lower half)
    var inS = 0f; var n = 0
    for (k in 0 until 12) { val th = k * PI.toFloat() / 6; inS += sgn * f.at(cx + 0.5f * (l00 * cos(th) + l01 * sin(th)), cy + 0.5f * (l10 * cos(th) + l11 * sin(th))); n++ }
    var outS = 0f
    for (k in 0 until 12) { val th = k * PI.toFloat() / 6; outS += sgn * f.at(cx + 1.2f * (l00 * cos(th) + l01 * sin(th)), cy + 1.2f * (l10 * cos(th) + l11 * sin(th))) }
    val mid = (inS + outS) / 2 / n
    var mxs = 0f; var mys = 0f
    for (ri in 1..4) for (k in 0 until 24) {
        val rr = 0.22f * ri; val th = k * PI.toFloat() / 12
        val ux = rr * cos(th); val uy = rr * sin(th)
        val w = sgn * f.at(cx + l00 * ux + l01 * uy, cy + l10 * ux + l11 * uy) - mid
        mxs += w * ux; mys += w * uy
    }
    val wl = sqrt(mxs * mxs + mys * mys)
    if (wl < 1e-3f) return null
    val wx = -mxs / wl; val wy = -mys / wl
    // refine the turn by the sharpest slat comb
    var bestA: FloatArray? = null; var bestS = -1f
    for (k in -6..6) {
        val ang = k * 2f * PI.toFloat() / 180f
        val c = cos(ang); val s = sin(ang)
        val qx = wx * c - wy * s; val qy = wx * s + wy * c
        // R: (1,0) → (qy, −qx), (0,1) → (qx, qy); A = L·R
        val a = floatArrayOf(l00 * qy - l01 * qx, l00 * qx + l01 * qy, l10 * qy - l11 * qx, l10 * qx + l11 * qy)
        val g = SGeo(cx, cy, a, sgn)
        val sc = sComb(f, g)?.get(1) ?: continue
        if (sc > bestS) { bestS = sc; bestA = a }
    }
    val a = bestA ?: return null
    // the slats must be there: a strong comb against the sun's own contrast
    val con = (inS - outS) / n
    if (con < 20f || bestS < 1.0f * con) return null
    return SGeo(cx, cy, a, sgn)
}

/** Slat phase (v of a slat, in [VZ0, VZ0 + PV)) and its comb score, from the profile along the sun's axis. */
private fun sComb(f: SigilFrame, g: SGeo): FloatArray? {
    val n = 96
    val prof = FloatArray(n)
    val v0 = SynthSun.VZ0 - 0.02f; val dv = 0.01f
    for (i in 0 until n) {
        val v = v0 + i * dv
        var s = 0f
        for (c in 0 until 6) { val u = SynthSun.cellU(c); s += f.at(g.x(u, v), g.y(u, v)) }
        prof[i] = -g.sgn * s / 6   // darkness (slat = plate)
    }
    fun pr(v: Float): Float { val x = (v - v0) / dv; val i = x.toInt().coerceIn(0, n - 2); val fr = (x - i).coerceIn(0f, 1f); return prof[i] * (1 - fr) + prof[i + 1] * fr }
    var bestP = 0f; var bestS = -1e9f
    val steps = 27
    for (k in 0 until steps) {
        val ph = SynthSun.VZ0 + k * SynthSun.PV / steps
        var s = 0f
        var v = ph
        while (v < 0.8f) { s += pr(v) - 0.5f * (pr(v - SynthSun.PV / 2) + pr(v + SynthSun.PV / 2)); v += SynthSun.PV }
        if (s > bestS) { bestS = s; bestP = ph }
    }
    return floatArrayOf(bestP, bestS)
}

/** The slats in view: their heights (sun coordinates) and cells (soft: + = thick). */
private fun sRead(f: SigilFrame, g: SGeo): List<SObs>? {
    val ph = sComb(f, g)?.get(0) ?: return null
    // references: the cap (sun) as a plane over the image (glare is a ramp), the sky just outside the top
    var sunSum = 0f; var skySum = 0f
    val capU = floatArrayOf(-0.5f, -0.25f, 0f, 0.25f, 0.5f, -0.35f, 0f, 0.35f)
    val capV = floatArrayOf(-0.55f, -0.65f, -0.7f, -0.65f, -0.55f, -0.35f, -0.4f, -0.35f)
    for (i in capU.indices) sunSum += f.at(g.x(capU[i], capV[i]), g.y(capU[i], capV[i]))
    val sun = sunSum / capU.size
    for (k in 0 until 7) { val th = -PI.toFloat() / 2 + (k - 3) * 0.32f; skySum += f.at(g.x(1.12f * cos(th), 1.12f * sin(th)), g.y(1.12f * cos(th), 1.12f * sin(th))) }
    val sky = skySum / 7
    val con = g.sgn * (sun - sky)
    if (con < 20f) return null
    // sun brightness near a point, corrected for the glare ramp: plane through cap samples
    val pxs = FloatArray(capU.size) { g.x(capU[it], capV[it]) }; val pys = FloatArray(capU.size) { g.y(capU[it], capV[it]) }
    val pl = FloatArray(capU.size) { f.at(pxs[it], pys[it]) }
    val plane = sPlane(pxs, pys, pl) ?: floatArrayOf(sun, 0f, 0f)
    val out = ArrayList<SObs>()
    var v = ph
    val ratios = ArrayList<Float>()
    val raw = ArrayList<Pair<Float, FloatArray>>()
    while (v <= SynthSun.VREAD) {
        if (v >= SynthSun.VZ0 + 0.45f * SynthSun.PV) {
            val cells = FloatArray(6)
            val tp = SynthSun.taper(v)
            for (c in 0 until 6) {
                val u = SynthSun.cellU(c)
                var s = 0f; var m = 0
                var vv = v - SynthSun.PV / 2 + 0.005f
                while (vv < v + SynthSun.PV / 2) {
                    for (du in floatArrayOf(-0.035f, 0f, 0.035f)) {
                        val x = g.x(u + du, vv); val y = g.y(u + du, vv)
                        val ref = plane[0] + plane[1] * x + plane[2] * y
                        s += g.sgn * (ref - f.at(x, y)); m++
                    }
                    vv += 0.01f
                }
                cells[c] = s / m / con / tp   // duty / taper
            }
            raw.add(v to cells)
            for (c in cells) ratios.add(c)
        }
        v += SynthSun.PV
    }
    if (raw.size < 3) return null
    // threshold between thin and thick: nominal, nudged by this frame's two clusters
    val nominal = (SynthSun.THIN + SynthSun.THICK) / 2
    val arr = FloatArray(ratios.size) { ratios[it] }
    val ot = Fit.otsu(arr)
    val thr = if (ot[1] > 0.6f && abs(ot[0] - nominal) < 0.15f) ot[0] else nominal
    val step = (SynthSun.THICK - SynthSun.THIN) / 2
    for ((vv, cells) in raw) {
        var mean = 0f; for (c in cells) mean += c; mean /= 6
        val missing = mean < 0.5f * SynthSun.THIN
        out.add(SObs(vv, missing, FloatArray(6) { ((cells[it] - thr) / step).coerceIn(-1f, 1f) }))
    }
    return out
}

/** Least-squares plane z = a + b x + c y. */
private fun sPlane(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray? {
    val m = Array(3) { DoubleArray(4) }
    for (i in x.indices) {
        val r = doubleArrayOf(1.0, x[i].toDouble(), y[i].toDouble())
        for (p in 0 until 3) { for (q in 0 until 3) m[p][q] += r[p] * r[q]; m[p][3] += r[p] * z[i] }
    }
    val s = Fit.solve(m) ?: return null
    return floatArrayOf(s[0].toFloat(), s[1].toFloat(), s[2].toFloat())
}

private fun sHullOrAll(xs: List<Float>, ys: List<Float>): FloatArray = FloatArray(2 * xs.size) { if (it % 2 == 0) xs[it / 2] else ys[it / 2] }

/** Convex hull (x0, y0, x1, y1 …) of points already sorted by (y, x): monotone chain. */
private fun sHull(xs: List<Float>, ys: List<Float>): FloatArray {
    val n = xs.size
    val h = IntArray(2 * n + 2); var k = 0
    fun cross(a: Int, b: Int, c: Int) = (xs[b] - xs[a]) * (ys[c] - ys[a]) - (ys[b] - ys[a]) * (xs[c] - xs[a])
    for (i in 0 until n) { while (k >= 2 && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
    val lo = k + 1
    for (i in n - 2 downTo 0) { while (k >= lo && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
    k--
    return FloatArray(2 * max(k, 0)) { j -> if (j % 2 == 0) xs[h[j / 2]] else ys[h[j / 2]] }
}

/**
 * Ellipse through points (x0, y0, x1, y1 …), least squares on the conic: [cx, cy, l00, l01, l10, l11] where
 * (cx, cy) + L·(cos θ, sin θ) runs round it; null if the points do not make an ellipse.
 */
private fun sEllipse(p: FloatArray): FloatArray? {
    val n = p.size / 2
    if (n < 6) return null
    var mx = 0.0; var my = 0.0
    for (i in 0 until n) { mx += p[2 * i]; my += p[2 * i + 1] }
    mx /= n; my /= n
    var sd = 0.0
    for (i in 0 until n) sd += sqrt((p[2 * i] - mx) * (p[2 * i] - mx) + (p[2 * i + 1] - my) * (p[2 * i + 1] - my))
    sd /= n
    if (sd < 1e-3) return null
    // a(x² − y²) + b xy + d x + e y + f = −y²   (c = 1 − a)
    val m = Array(5) { DoubleArray(6) }
    for (i in 0 until n) {
        val x = (p[2 * i] - mx) / sd; val y = (p[2 * i + 1] - my) / sd
        val r = doubleArrayOf(x * x - y * y, x * y, x, y, 1.0)
        val b = -y * y
        for (a in 0 until 5) { for (c in 0 until 5) m[a][c] += r[a] * r[c]; m[a][5] += r[a] * b }
    }
    val s = Fit.solve(m) ?: return null
    val A = s[0]; val B = s[1]; val C = 1 - s[0]; val D = s[2]; val E = s[3]; val F = s[4]
    val den = 4 * A * C - B * B
    if (den <= 1e-9) return null
    val x0 = (B * E - 2 * C * D) / den; val y0 = (B * D - 2 * A * E) / den
    val f0 = A * x0 * x0 + B * x0 * y0 + C * y0 * y0 + D * x0 + E * y0 + F
    // eigen of [[A, B/2], [B/2, C]]
    val tr = A + C; val dt = A * C - B * B / 4
    val disc = sqrt(max(0.0, tr * tr / 4 - dt))
    val l1 = tr / 2 + disc; val l2 = tr / 2 - disc
    if (l1 <= 0 || l2 <= 0 || f0 >= 0) return null
    val ra = sqrt(-f0 / l1); val rb = sqrt(-f0 / l2)
    // eigenvector of l1
    var vx: Double; var vy: Double
    if (abs(B) > 1e-12) { vx = B / 2; vy = l1 - A } else if (A >= C) { vx = 1.0; vy = 0.0 } else { vx = 0.0; vy = 1.0 }
    val vl = sqrt(vx * vx + vy * vy); vx /= vl; vy /= vl
    // L columns: ra·v1, rb·v2 (v2 ⟂ v1), back to pixels
    return floatArrayOf(
        (x0 * sd + mx).toFloat(), (y0 * sd + my).toFloat(),
        (ra * vx * sd).toFloat(), (-rb * vy * sd).toFloat(),
        (ra * vy * sd).toFloat(), (rb * vx * sd).toFloat(),
    )
}

// ---------------------------------------------------------------------- fast marks (connected components)

internal class SMarks(cap: Int) {
    var n = 0
    var cx = FloatArray(cap); var cy = FloatArray(cap); var area = IntArray(cap)
    var x0 = IntArray(cap); var y0 = IntArray(cap); var x1 = IntArray(cap); var y1 = IntArray(cap)
    fun bw(i: Int) = x1[i] - x0[i] + 1
    fun bh(i: Int) = y1[i] - y0[i] + 1
    var nRuns = 0
    var runY = IntArray(0); var runS = IntArray(0); var runE = IntArray(0); var runId = IntArray(0)
}

/** The frame at half resolution; [marks] = pixels darker (lighter) than their window's mean by a margin, connected. */
internal class SHalf(val f: SigilFrame) {
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

    fun marks(darkMarks: Boolean, win: Int, delta: Float, maxN: Int = 3000, keepRuns: Boolean = false): SMarks {
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
        val m = SMarks(min(maxN, nr) + 1)
        val sx = DoubleArray(m.cx.size); val sy = DoubleArray(m.cx.size)
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
            if (a < m.x0[k]) m.x0[k] = a; if (b > m.x1[k]) m.x1[k] = b
            if (y < m.y0[k]) m.y0[k] = y; if (y > m.y1[k]) m.y1[k] = y
        }
        for (k in 0 until m.n) {
            val a = m.area[k]
            m.cx[k] = (sx[k] / a).toFloat() * 2f + 0.5f; m.cy[k] = (sy[k] / a).toFloat() * 2f + 0.5f
            m.area[k] = a * 4
            m.x0[k] *= 2; m.y0[k] *= 2; m.x1[k] = m.x1[k] * 2 + 1; m.y1[k] = m.y1[k] * 2 + 1
        }
        if (keepRuns) { m.nRuns = nr; m.runY = runY; m.runS = runS; m.runE = runE; m.runId = runId }
        return m
    }
}
