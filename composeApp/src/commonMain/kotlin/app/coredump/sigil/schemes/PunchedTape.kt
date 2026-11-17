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
    internal const val BX0 = 0.09f
    internal const val BX1 = 0.91f
    internal const val BY0 = 0.29f
    internal const val BY1 = 0.71f
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

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val plate = ink.plate; val main = ink.main
        val soft = ink.soft; val faint = ink.faint
        val stampInk = Colors.hueShift(ink.alt, 6f * sin(t * 0.4f))

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
        // dateline (fine print) and edition ticker
        out.rect(0.17f, 0.152f, 0.24f, 0.007f, soft)
        out.rect(0.83f, 0.152f, 0.24f, 0.007f, soft)
        val tick = ((t * 1.1f) % 1f)
        out.circle(0.5f, 0.152f, 0.006f + 0.002f * sin(tick * 2 * PI.toFloat()), main)

        // ---- headline (two bold bars of "words")
        headline(out, 0.065f, 0.192f, floatArrayOf(0.13f, 0.09f, 0.16f, 0.07f, 0.12f, 0.17f), 0.03f, main)
        headline(out, 0.065f, 0.243f, floatArrayOf(0.1f, 0.15f, 0.08f, 0.2f, 0.11f), 0.018f, Colors.mix(plate, main, 0.55f))

        // ---- the box with the tape
        drawBox(bits, t, ink, out)

        // ---- body text columns
        for (col in 0 until 3) {
            val cx0 = 0.045f + col * 0.31f
            for (ln in 0 until 8) {
                val y = 0.76f + ln * 0.022f
                if (y > 0.965f) break
                var len = 0.27f - (if (ln == 7) 0.11f else 0f) - ((col * 7 + ln * 3) % 5) * 0.006f
                if (col == 2 && ln in 1..6) len -= 0.12f   // room for the stamp
                if (col == 0 && ln in 3..7) len -= 0.14f   // room for the curl
                if (len > 0.02f) out.rect(cx0 + len / 2, y, len, 0.008f, faint)
            }
        }

        // ---- a curl of cut tape lying on the page (bottom left)
        curl(out, 0.135f, 0.9f, t, ink)

        // ---- the rubber stamp: TELEGRAM, slammed down now and then
        stamp(out, 0.82f, 0.875f, t, stampInk, plate)

        // ---- ink speckles
        for (k in 0 until 18) {
            val sx = ((k * 0.6180339f + 0.13f) % 1f) * 0.9f + 0.05f
            val sy = ((k * 0.4142135f + 0.71f) % 1f) * 0.95f + 0.02f
            if (sy in 0.29f..0.77f) continue
            out.circle(sx, sy, 0.0018f + 0.0012f * ((k * 7) % 3), Colors.mix(plate, main, 0.18f))
        }
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
        out.rect((ix0 + ix1) / 2, (ty0 + ty1) / 2, ix1 - ix0, ty1 - ty0, Colors.hueShift(Colors.mix(plate, ink.alt, 0.035f), 0f))
        out.line(ix0, ty0, ix1, ty0, 0.002f, Colors.mix(plate, main, 0.2f), round = false)
        out.line(ix0, ty1, ix1, ty1, 0.002f, Colors.mix(plate, main, 0.2f), round = false)
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
        if (flash > 0f) out.glow(BX0 + RULE_L + 0.004f, rowY(2), 0.05f, Colors.alpha(Colors.mix(plate, ink.alt, 0.5f), 0.35f * flash))
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
        out.line(x - 0.035f, y - 0.025f, x - 0.018f, y - 0.023f, 0.0015f, c)
        out.line(x + 0.018f, y - 0.023f, x + 0.035f, y - 0.026f, 0.0015f, c)
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
        val n = 44
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
            if (i % 3 == 0 && sin(p0[2]) > 0.3f) {
                out.circle(p0[0], p0[1] - w0 * k0 * 0.45f, 0.0035f * (1f - 0.4f * f0), hole)
                if (i % 6 == 0) out.circle(p0[0], p0[1] + w0 * k0 * 0.4f, 0.0042f * (1f - 0.4f * f0), hole)
            }
        }
    }

    /** The TELEGRAM rubber stamp: a double frame of broken ink with letter blocks, thumped down every few seconds. */
    private fun stamp(out: Scene, cx: Float, cy: Float, t: Float, c: Int, plate: Int) {
        val per = 5.5f
        val ph = ((t % per) + per) % per
        val drop = if (ph < 0.18f) 1.6f - 0.6f * (ph / 0.18f) else 1f
        val fade = if (ph < 0.18f) ph / 0.18f else 1f
        val col = Colors.alpha(c, 0.82f * fade)
        val rot = -11f
        val w = 0.25f * drop; val h = 0.105f * drop
        val ca = cos(rot * PI.toFloat() / 180f); val sa = sin(rot * PI.toFloat() / 180f)
        fun px(u: Float, v: Float) = cx + u * ca - v * sa
        fun py(u: Float, v: Float) = cy + u * sa + v * ca
        out.rect(cx, cy, w, h, col, rot = rot, corner = 0.008f, stroke = 0.007f * drop)
        out.rect(cx, cy, w - 0.022f * drop, h - 0.022f * drop, col, rot = rot, corner = 0.005f, stroke = 0.0025f * drop)
        // letters T E L E G R A M as blocks
        val n = 8
        for (k in 0 until n) {
            val u = (-0.5f + (k + 0.5f) / n) * (w - 0.05f * drop)
            val lw = 0.018f * drop; val lh = 0.04f * drop
            out.rect(px(u, 0f), py(u, 0f), lw, lh, col, rot = rot)
            out.rect(px(u + 0.003f * drop, ((k * 5) % 3 - 1) * 0.009f * drop), py(u + 0.003f * drop, ((k * 5) % 3 - 1) * 0.009f * drop), lw * 0.45f, lh * 0.18f, Colors.alpha(plate, fade), rot = rot)
        }
        // ink wear: plate nicks on the frame
        for (k in 0 until 6) {
            val u = (-0.45f + k * 0.18f) * w; val v = (if (k % 2 == 0) -0.5f else 0.5f) * h
            out.circle(px(u, v), py(u, v), 0.004f * drop, Colors.alpha(plate, 0.9f * fade))
        }
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
