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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * QUEST: a pixel-art summoning circle that slowly turns: a double ring with tick marks, 14 runes (5 × 5 pixel glyphs)
 * between the rings, a ▶ menu cursor in the 15th place marking where the spell starts, a crystal in the middle,
 * sparkles, and the runes lighting up one after another like a spell charging.
 *
 * Each rune is one payload symbol, drawn as one of 64 glyphs chosen for distance (any two differ in ≥ 8 of the 25
 * pixels, ≥ 5 even when one is shifted by a pixel, ≥ 8 from the cursor), after a scheme-specific symbol mask.
 *
 * Reader: the inner ring (always fully in view) is found as a ring, an ellipse fit to its inside edge gives a first
 * guess, then a homography is fitted to three ring edges (two concentric circles fix the perspective). The ticks on
 * the outer ring give the turn (15-fold); the cursor's slot gives the start and the way it points the reading
 * direction (and undoes a mirrored camera); every rune is classified by correlation with the 64 glyphs (with a small
 * alignment search), scores are summed over recent frames, and unsure runes become erasures.
 */
object RuneCircle : SigilScheme {
    override val name = "runes"

    internal const val RO0 = 0.418f       // outer ring, inside edge
    internal const val RO1 = 0.442f       // outer ring, outside edge
    internal const val RI0 = 0.232f       // inner ring, inside edge
    internal const val RI1 = 0.254f       // inner ring, outside edge
    internal const val RC = 0.336f        // rune centres
    internal const val CELL = 0.0232f     // rune pixel
    internal const val SLOTS = 15
    internal const val SLOT_DEG = 360f / SLOTS
    internal const val TURN = 7f          // degrees per second (clockwise)
    internal const val TICK_L = 0.02f
    internal const val TICK_W = 0.012f

    /** The 64 rune glyphs: bit 24 - (5 r + c) is pixel (row r from the outside, column c along the reading direction). */
    internal val GLYPHS = intArrayOf(
        0x0FA38C1, 0x074421F, 0x08FA83F, 0x1494D46, 0x011959C, 0x1D9C631, 0x10633E7, 0x1C38BE8, 0x1F6692C, 0x114DE52, 0x0A56AF0,
        0x14FB482, 0x095394A, 0x1251D31, 0x118E5A7, 0x0C2599F, 0x1395B80, 0x02224FD, 0x1F18887, 0x11F7151, 0x1D3321C, 0x1C41445,
        0x01F87E1, 0x0233F44, 0x0C5278F, 0x1FA4E54, 0x0741BE8, 0x1CE0CA9, 0x0518C7F, 0x0455310, 0x07F4400, 0x1D170E0, 0x125785F,
        0x0027B79, 0x10F98CD, 0x10856DF, 0x1FE1194, 0x0D7DD21, 0x087E75C, 0x12BEA52, 0x1C4F9C9, 0x1C827E4, 0x072A627, 0x108710C,
        0x0739952, 0x0B6A58A, 0x0464867, 0x04CE89C, 0x1F095F1, 0x082FC84, 0x05B3DB0, 0x0226396, 0x018ACE8, 0x19ADC41, 0x0C67A52,
        0x09534A5, 0x055DCC4, 0x0656D6B, 0x1E1398C, 0x0115737, 0x10C5CE0, 0x00FE307, 0x0C535F8, 0x04255E4,
    )

    /** The ▶ cursor, pointing along the reading direction. */
    internal const val CURSOR = 0b11000_11100_11111_11100_11000

    internal val MASK: IntArray = run {
        var s = 0x3E5A91C7
        IntArray(SigilPayload.SYMBOLS) {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            (s ushr 7) and 63
        }
    }

    /** Turn of the circle (degrees, clockwise) at time t: slot k's centre is at turn + k · 24°. */
    internal fun turn(t: Float): Float = (TURN * t + 11f) % 360f

    // ------------------------------------------------------------------ colours: four shades and a glow

    private class Pal(val ink: Ink) {
        val dark = Colors.light(ink.plate) < 0.5f
        val s1 = Colors.mix(ink.plate, ink.main, 0.55f)                        // mid shade (decoration only)
        val s3 = Colors.mix(ink.plate, if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.28f)   // away from the marks
        val glow = Colors.mix(ink.plate, if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.6f)
    }

    private var palInk: Ink? = null
    private var palVal: Pal? = null
    private fun pal(ink: Ink): Pal {
        val p = palVal
        if (p != null && palInk === ink) return p
        return Pal(ink).also { palInk = ink; palVal = it }
    }

    private fun rad(d: Float) = d * PI.toFloat() / 180f

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val p = pal(ink)
        val tr = turn(t)
        // the circle's aura (lighter / darker than the plate, away from the marks)
        out.glow(0.5f, 0.5f, 0.5f, Colors.alpha(p.s3, 0.8f))
        // dashed rune-dust ring outside, turning the other way (decoration)
        for (i in 0 until 48) {
            val a = rad(i * 7.5f - tr * 1.6f)
            val r = 0.472f
            out.rect(0.5f + r * cos(a), 0.5f + r * sin(a), 0.012f, 0.012f, Colors.alpha(p.s1, 0.55f), i * 7.5f - tr * 1.6f)
        }
        // spell charge: runes light up one after another, then the whole circle flashes
        val cyc = (t % 4.2f)
        val lit = (cyc / 0.22f).toInt()
        val flash = if (cyc > 3.4f) sin((cyc - 3.4f) / 0.8f * PI.toFloat()) else 0f
        if (flash > 0f) out.glow(0.5f, 0.5f, 0.5f, Colors.alpha(p.glow, 0.6f * flash))
        // rings and ticks
        out.circle(0.5f, 0.5f, (RO0 + RO1) / 2, ink.main, RO1 - RO0)
        out.circle(0.5f, 0.5f, (RI0 + RI1) / 2, ink.main, RI1 - RI0)
        for (k in 0 until SLOTS) {
            val a = rad(tr + (k + 0.5f) * SLOT_DEG)
            val ca = cos(a); val sa = sin(a)
            val w = TICK_W / RO0
            out.poly(floatArrayOf(
                0.5f + (RO0 + 0.003f) * cos(a - w), 0.5f + (RO0 + 0.003f) * sin(a - w),
                0.5f + (RO0 - TICK_L) * ca, 0.5f + (RO0 - TICK_L) * sa,
                0.5f + (RO0 + 0.003f) * cos(a + w), 0.5f + (RO0 + 0.003f) * sin(a + w),
            ), ink.main)
        }
        // runes
        for (k in 0 until SLOTS) {
            val ang = tr + k * SLOT_DEG
            val a = rad(ang)
            val cx = 0.5f + RC * cos(a); val cy = 0.5f + RC * sin(a)
            val tx = -sin(a); val ty = cos(a)      // clockwise
            val nx = cos(a); val ny = sin(a)       // outwards
            if (k == 0) {
                // the menu cursor: a steady halo, and it bobs along the reading direction (two-step, like a handheld)
                val bob = 0.25f * CELL * (if (sin(t * 7f) > 0f) 1f else -1f)
                out.glow(cx, cy, 0.09f, Colors.alpha(p.glow, 0.75f + 0.25f * sin(t * 3f)))
                pixels(out, CURSOR, cx + tx * bob, cy + ty * bob, tx, ty, nx, ny, ang, ink.main)
                continue
            }
            val idx = k - 1
            var sym = 0
            for (b in 0 until 6) if (bits[6 * idx + b]) sym = sym or (1 shl (5 - b))
            val g = GLYPHS[sym xor MASK[idx]]
            val on = if (lit == idx) 1f else if (lit == idx + 1) 0.45f else 0f
            if (on > 0f) out.glow(cx, cy, 0.085f, Colors.alpha(p.glow, 0.85f * on))
            pixels(out, g, cx, cy, tx, ty, nx, ny, ang, ink.main)
        }
        // the crystal: a turning pixel gem in four shades, with orbiting sparkles
        crystal(out, t, p, ink)
        for (i in 0 until 5) {
            val a = rad(-tr * 3f + i * 72f)
            val r = 0.155f + 0.012f * sin(t * 2f + i)
            val tw = sin(t * 3.1f + i * 1.7f)
            if (tw < 0f) continue
            sparkle(out, 0.5f + r * cos(a), 0.5f + r * sin(a), 0.006f, Colors.alpha(p.s1, tw))
        }
        // corner sparkles and a level-up tag (outside the circle; decoration)
        for (c in 0 until 4) {
            val tw = sin(t * 1.4f + c * 1.6f)
            if (tw < 0.1f) continue
            val x = if (c % 2 == 0) 0.075f else 0.925f; val y = if (c < 2) 0.075f else 0.925f
            sparkle(out, x, y, 0.008f * (0.6f + tw), Colors.alpha(p.s1, tw))
        }
    }

    private fun pixels(out: Scene, g: Int, cx: Float, cy: Float, tx: Float, ty: Float, nx: Float, ny: Float, ang: Float, col: Int) {
        for (r in 0 until 5) for (c in 0 until 5) {
            if ((g shr (24 - (5 * r + c))) and 1 == 0) continue
            val u = (c - 2) * CELL; val v = (2 - r) * CELL
            out.rect(cx + tx * u + nx * v, cy + ty * u + ny * v, CELL * 1.02f, CELL * 1.02f, col, ang)
        }
    }

    private fun sparkle(out: Scene, x: Float, y: Float, s: Float, col: Int) {
        out.rect(x, y, s, s, col)
        out.rect(x - s, y, s, s, col); out.rect(x + s, y, s, s, col)
        out.rect(x, y - s, s, s, col); out.rect(x, y + s, s, s, col)
    }

    /** A pixel-art gem: a tall faceted crystal of squares in four shades, a sweeping glint, a soft pulse. */
    private fun crystal(out: Scene, t: Float, p: Pal, ink: Ink) {
        val px = 0.019f
        val bob = 0.006f * sin(t * 1.8f)
        val pulse = 0.5f + 0.5f * sin(t * 2.4f)
        out.glow(0.5f, 0.5f + bob, 0.16f, Colors.alpha(p.glow, 0.55f + 0.4f * pulse))
        // a floating save-crystal: pointed ends, a tall six-sided body (half-widths in pixels, top to bottom)
        val half = intArrayOf(0, 1, 2, 2, 2, 2, 2, 2, 2, 2, 1, 0)
        val y0 = 0.5f + bob - (half.size - 1) / 2f * px
        out.oval(0.5f, 0.5f + 0.13f, 0.045f * (1.1f - 0.4f * bob / 0.006f * 0.1f), 0.012f, Colors.alpha(p.s1, 0.45f))
        val glint = ((t * 0.8f) % 2.2f) - 0.2f      // a glint runs down the crystal now and then
        val midTone = Colors.mix(ink.plate, ink.main, 0.3f)
        for ((ri, hw) in half.withIndex()) {
            val y = y0 + ri * px
            for (cc in -hw..hw) {
                val x = 0.5f + cc * px
                val edge = abs(cc) == hw && hw > 0 || ri == 0 || ri == half.size - 1
                val col = when {
                    edge -> ink.main
                    abs(ri / (half.size - 1f) - glint) < 0.09f -> p.s3
                    cc < 0 -> p.s1
                    cc == 0 -> p.s3
                    else -> midTone
                }
                out.rect(x, y, px * 1.03f, px * 1.03f, col)
            }
        }
        val gt = sin(t * 1.3f)
        if (gt > 0.4f) sparkle(out, 0.5f + px * 2.5f, y0 + px * 1.5f, 0.005f, Colors.alpha(p.s3, (gt - 0.4f) / 0.6f))
    }

    override fun reader(): SchemeReader = RuneReader()
}

// ====================================================================== reader


private class RuneReader : SchemeReader {
    private var seenNow = false
    override val seen: Boolean get() = seenNow

    private var hPrev: DoubleArray? = null
    private var pol = 0
    private var lastLock = -1e9
    private val small = RSmall()
    private val hist = ArrayList<Pair<Double, Array<FloatArray>>>()

    override fun feed(f: SigilFrame): Read? {
        val t = f.tMs / 1000.0
        if (t - lastLock > 1.5) hist.clear()
        val h = locate(f, t)
        seenNow = h != null
        if (h == null) return null
        lastLock = t
        val sc = read(f, h)
        if (sc == null) return null
        hist.add(t to sc)
        while (hist.isNotEmpty() && t - hist[0].first > 1.5) hist.removeAt(0)
        decode(sc)?.let { return it }
        if (hist.size < 2) return null
        val sum = Array(14) { FloatArray(64) }
        for ((_, s) in hist) for (k in 0 until 14) for (g in 0 until 64) sum[k][g] += s[k][g]
        return decode(sum)
    }

    private fun decode(sc: Array<FloatArray>): Read? {
        val sym = IntArray(14)
        val margin = FloatArray(14)
        for (k in 0 until 14) {
            var b = 0; var s2 = -1e9f
            for (g in 1 until 64) if (sc[k][g] > sc[k][b]) b = g
            for (g in 0 until 64) if (g != b && sc[k][g] > s2) s2 = sc[k][g]
            val top = sc[k][b]
            margin[k] = (top - s2) / max(1e-3f, abs(top))
            sym[k] = b xor RuneCircle.MASK[k]
        }
        val order = (0 until 14).sortedBy { margin[it] }
        SigilPayload.decode(sym)?.let { return it }
        for (n in intArrayOf(2, 4)) SigilPayload.decode(sym, order.take(n))?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ geometry

    private fun locate(f: SigilFrame, t: Double): DoubleArray? {
        val prev = hPrev
        if (prev != null && t - lastLock < 0.6) {
            val h = refine(f, prev, pol, false)
            if (h != null) { hPrev = h; return h }
        }
        hPrev = null
        val sm = small
        sm.load(f)
        val cls = sm.classes(12, 9)
        val lab = sm.lab
        for (want in intArrayOf(-1, 1)) {
            val comps = sm.label(cls, want.toByte(), lab, if (want == 1) 1 else 1_000_000)
            val rings = comps.filter { c ->
                val bw = c.x1 - c.x0 + 1; val bh = c.y1 - c.y0 + 1
                val big = max(bw, bh)
                big in 24..240 && min(bw, bh) > big * 0.55f && c.area >= 50 && c.area < 0.45f * bw * bh &&
                    c.x0 > 0 && c.y0 > 0 && c.x1 < sm.w - 1 && c.y1 < sm.h - 1 &&
                    abs(c.cx - (c.x0 + c.x1) / 2f) < 0.15f * big && abs(c.cy - (c.y0 + c.y1) / 2f) < 0.15f * big &&
                    lab[c.cy.toInt() * sm.w + c.cx.toInt()] != c.id
            }.sortedByDescending { it.area }.take(4).let { l ->
                l.sortedBy { c -> if (l.any { o -> o !== c && o.area > c.area && abs(o.cx - c.cx) + abs(o.cy - c.cy) < 0.15f * (o.x1 - o.x0) }) 0 else 1 }
            }
            for (c in rings) {
                var h0 = innerEllipse(sm, lab, c) ?: continue
                var fitted = false
                // the outer ring (about 1.9 times as far out) fixes the perspective at once
                val two = bothRings(sm, cls, want, lab, c, h0)
                if (two != null) { h0 = two; fitted = true }
                val h = refine(f, h0, want, !fitted)
                if (h == null) continue
                hPrev = h; pol = want
                return h
            }
        }
        return null
    }

    /** First guess: an ellipse through the ring component's inside edge (rays from its centre), as an affine map. */
    private fun innerEllipse(sm: RSmall, lab: IntArray, c: RSmall.Comp): DoubleArray? {
        val nr = 72
        val xs = DoubleArray(nr); val ys = DoubleArray(nr)
        var n = 0
        val big = max(c.x1 - c.x0, c.y1 - c.y0).toDouble()
        for (i in 0 until nr) {
            val ang = 2 * PI * i / nr
            val dx = cos(ang); val dy = sin(ang)
            var r = 1.0
            while (r < big) {
                val x = (c.cx + dx * r).toInt(); val y = (c.cy + dy * r).toInt()
                if (x < 0 || y < 0 || x >= sm.w || y >= sm.h) break
                if (lab[y * sm.w + x] == c.id) { xs[n] = 2 * (c.cx + dx * (r - 0.5)) + 1; ys[n] = 2 * (c.cy + dy * (r - 0.5)) + 1; n++; break }
                r += 0.5
            }
        }
        if (n < nr * 2 / 3) return null
        // conic A x² + B xy + C y² + D x + E y = 1 in centred coordinates
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += xs[i]; my += ys[i] }
        mx /= n; my /= n
        val m = Array(5) { DoubleArray(6) }
        for (i in 0 until n) {
            val x = xs[i] - mx; val y = ys[i] - my
            val row = doubleArrayOf(x * x, x * y, y * y, x, y)
            for (p in 0 until 5) { for (q in 0 until 5) m[p][q] += row[p] * row[q]; m[p][5] += row[p] }
        }
        val s = Fit.solve(m) ?: return null
        val a = s[0]; val b = s[1]; val cc = s[2]; val d = s[3]; val e = s[4]
        val den = 4 * a * cc - b * b
        if (den <= 0) return null
        val x0 = (b * e - 2 * cc * d) / den; val y0 = (b * d - 2 * a * e) / den
        val k = 1 + (a * x0 * x0 + b * x0 * y0 + cc * y0 * y0)   // value at the centre shifts the level
        val theta = 0.5 * atan2(b, a - cc)
        val ct = cos(theta); val st = sin(theta)
        val ap = a * ct * ct + b * ct * st + cc * st * st
        val cp = a * st * st - b * ct * st + cc * ct * ct
        if (ap <= 0 || cp <= 0 || k <= 0) return null
        val ra = sqrt(k / ap); val rb = sqrt(k / cp)
        // model (0.5 + RI0 cos φ, 0.5 + RI0 sin φ) → centre + R(θ) diag(ra, rb) (cos φ, sin φ)
        val sa = ra / RuneCircle.RI0; val sb = rb / RuneCircle.RI0
        val cx = mx + x0; val cy = my + y0
        val h = doubleArrayOf(ct * sa, -st * sb, 0.0, st * sa, ct * sb, 0.0, 0.0, 0.0, 1.0)
        h[2] = cx - 0.5 * (h[0] + h[1]); h[5] = cy - 0.5 * (h[3] + h[4])
        return h
    }

    /**
     * Homography from both rings: the inner ring component's inside edge, and along the same rays the outermost
     * pixel of the marks' class between 1.55 and 2.1 times that distance (the outer ring's outside edge).
     */
    private fun bothRings(sm: RSmall, cls: ByteArray, want: Int, lab: IntArray, c: RSmall.Comp, h0: DoubleArray): DoubleArray? {
        val nr = 72
        val ix = DoubleArray(2 * nr); val iy = DoubleArray(2 * nr); val rr = DoubleArray(2 * nr)
        var n = 0; var nOut = 0
        val big = max(c.x1 - c.x0, c.y1 - c.y0).toDouble()
        for (i in 0 until nr) {
            val ang = 2 * PI * i / nr
            val dx = cos(ang); val dy = sin(ang)
            var r = 1.0; var first = -1.0
            while (r < big) {
                val x = (c.cx + dx * r).toInt(); val y = (c.cy + dy * r).toInt()
                if (x < 0 || y < 0 || x >= sm.w || y >= sm.h) break
                if (lab[y * sm.w + x] == c.id) { first = r; break }
                r += 0.5
            }
            if (first < 0) continue
            ix[n] = 2 * (c.cx + dx * (first - 0.5)) + 1; iy[n] = 2 * (c.cy + dy * (first - 0.5)) + 1; rr[n] = RuneCircle.RI0.toDouble(); n++
            var last = -1.0; var cut = false
            r = 1.55 * first
            while (r <= 2.1 * first) {
                val x = (c.cx + dx * r).toInt(); val y = (c.cy + dy * r).toInt()
                if (x < 0 || y < 0 || x >= sm.w || y >= sm.h) { cut = true; break }
                if (cls[y * sm.w + x].toInt() == want) last = r
                r += 0.5
            }
            if (last > 0 && !cut && last < 2.08 * first) { ix[n] = 2 * (c.cx + dx * (last + 0.5)) + 1; iy[n] = 2 * (c.cy + dy * (last + 0.5)) + 1; rr[n] = RuneCircle.RO1.toDouble(); n++; nOut++ }
        }
        if (nOut < nr / 3 || n - nOut < nr * 2 / 3) return null
        val fr = fitRadial(h0, ix, iy, rr, n, false)
        if (fr == null || fr.second > 0.01) return null
        return fr.first
    }

    /** Edge search on three ring edges, homography fit with radial residuals; null if the rings do not hold. */
    private fun refine(f: SigilFrame, h0: DoubleArray, pol: Int, first: Boolean): DoubleArray? {
        var h = h0
        val na = 40
        val maxPts = 3 * na
        val ix = DoubleArray(maxPts); val iy = DoubleArray(maxPts); val rr = DoubleArray(maxPts)
        val pa = DoubleArray(2); val pb = DoubleArray(2)
        val prof = FloatArray(48)
        val rounds = if (first) 3 else 1
        var rms = 0.0
        for (round in 0 until rounds) {
            var n = 0
            for ((ei, edge) in EDGES.withIndex()) {
                if (first && round == 0 && ei != 0) continue
                val r = edge[0]; val ringOut = edge[1]   // +1: the ring lies outside this edge
                val reach = if (first && round == 0) 0.025 else if (first && round == 1) 0.016 else 0.011
                for (i in 0 until na) {
                    val ang = 2 * PI * (i + 0.5 * ei / 3.0) / na
                    val ux = cos(ang); val uy = sin(ang)
                    val x = 0.5 + r * ux; val y = 0.5 + r * uy
                    // from the ring across its edge
                    rApply(h, x + ringOut * ux * reach, y + ringOut * uy * reach, pa)
                    rApply(h, x - ringOut * ux * reach, y - ringOut * uy * reach, pb)
                    if (!rInside(f, pa[0], pa[1]) || !rInside(f, pb[0], pb[1])) continue
                    val len = sqrt((pb[0] - pa[0]) * (pb[0] - pa[0]) + (pb[1] - pa[1]) * (pb[1] - pa[1]))
                    val m = (len / 0.7).toInt().coerceIn(8, 48)
                    for (k in 0 until m) { val q = k / (m - 1.0); prof[k] = f.at((pa[0] + (pb[0] - pa[0]) * q).toFloat(), (pa[1] + (pb[1] - pa[1]) * q).toFloat()) }
                    var bi = -1; var bv = 0f
                    for (k in 1 until m - 1) { val g = -pol * (prof[k + 1] - prof[k - 1]); if (g > bv) { bv = g; bi = k } }
                    if (bi < 0 || bv < 8f) continue
                    var off = 0.0
                    if (bi in 2 until m - 2) {
                        val e0 = -pol * (prof[bi] - prof[bi - 2]); val e2 = -pol * (prof[bi + 2] - prof[bi])
                        val den = e0 - 2 * bv + e2
                        if (den < 0) off = (0.5 * (e0 - e2) / den).coerceIn(-0.5, 0.5)
                    }
                    val q = (bi + off) / (m - 1.0)
                    ix[n] = pa[0] + (pb[0] - pa[0]) * q; iy[n] = pa[1] + (pb[1] - pa[1]) * q; rr[n] = r
                    n++
                }
            }
            if (n < (if (first && round == 0) 20 else 40)) return null
            val res = fitRadial(h, ix, iy, rr, n, first && round == 0) ?: return null
            h = res.first; rms = res.second
        }
        val p0 = DoubleArray(2); val p1 = DoubleArray(2)
        rApply(h, 0.5, 0.5, p0); rApply(h, 0.6, 0.5, p1)
        val scale = sqrt((p1[0] - p0[0]) * (p1[0] - p0[0]) + (p1[1] - p0[1]) * (p1[1] - p0[1])) * 10
        if (rms * scale > 1.5) return null
        // both rings stand out on both sides (relative to their own contrast)
        val pt = DoubleArray(2)
        val diffs = FloatArray(48)
        var tot = 0
        for (i in 0 until 24) {
            val ang = 2 * PI * (i + 0.25) / 24
            val ux = cos(ang); val uy = sin(ang)
            for (k in 0 until 2) {
                val mid = if (k == 0) (RuneCircle.RI0 + RuneCircle.RI1) / 2.0 else (RuneCircle.RO0 + RuneCircle.RO1) / 2.0
                val inn = if (k == 0) RuneCircle.RI0 - 0.026 else RuneCircle.RO0 - 0.012
                val outr = if (k == 0) RuneCircle.RI1 + 0.012 else RuneCircle.RO1 + 0.017
                val vr = rSample(f, h, 0.5 + mid * ux, 0.5 + mid * uy, pt, 0.8f)
                val vi = rSample(f, h, 0.5 + inn * ux, 0.5 + inn * uy, pt, 0.8f)
                val vo = rSample(f, h, 0.5 + outr * ux, 0.5 + outr * uy, pt, 0.8f)
                if (vr.isNaN() || vi.isNaN() || vo.isNaN()) continue
                diffs[tot++] = pol * min(vr - vi, vr - vo)
            }
        }
        if (tot < 24) return null
        val sd = diffs.copyOf(tot).sorted()
        val med = sd[tot / 2]
        val good = sd.count { it > 0.4f * med }
        if (med < 12f || good < 0.8 * tot) return null
        return h
    }

    // ------------------------------------------------------------------ reading

    /** Correlation of every rune with every glyph (14 × 64), or null if the cursor is not found. */
    private fun read(f: SigilFrame, h: DoubleArray): Array<FloatArray>? {
        val pt = DoubleArray(2)
        val p0 = DoubleArray(2); val p1 = DoubleArray(2)
        rApply(h, 0.5, 0.5, p0); rApply(h, 0.6, 0.5, p1)
        val scale = (sqrt((p1[0] - p0[0]) * (p1[0] - p0[0]) + (p1[1] - p0[1]) * (p1[1] - p0[1])) * 10).toFloat()
        // the turn, from the ticks on the outer ring (15th harmonic of the darkness just inside it)
        var re = 0.0; var im = 0.0
        val rt = RuneCircle.RO0 - RuneCircle.TICK_L * 0.45
        for (i in 0 until 360) {
            val a = 2 * PI * i / 360
            val v = rSample(f, h, 0.5 + rt * cos(a), 0.5 + rt * sin(a), pt, 0.6f)
            if (v.isNaN()) continue
            val ink = pol * v
            re += ink * cos(15 * a); im += ink * sin(15 * a)
        }
        // ticks sit between slots: slot centres are half a slot from them
        val tick = atan2(im, re) / 15 * 180 / PI
        val base = tick + RuneCircle.SLOT_DEG / 2
        // sample every slot's 5 × 5 pixels at 3 × 3 small offsets (reading clockwise)
        val cell = RuneCircle.CELL.toDouble()
        val rs = max(0.6f, 0.22f * RuneCircle.CELL * scale)
        val smp = Array(RuneCircle.SLOTS) { Array(9) { FloatArray(25) } }
        for (k in 0 until RuneCircle.SLOTS) {
            val a = (base + k * RuneCircle.SLOT_DEG) * PI / 180
            val cx = 0.5 + RuneCircle.RC * cos(a); val cy = 0.5 + RuneCircle.RC * sin(a)
            val tx = -sin(a); val ty = cos(a); val nx = cos(a); val ny = sin(a)
            for (o in 0 until 9) {
                val du = OFF[o % 3] * cell; val dv = OFF[o / 3] * cell
                val s = smp[k][o]
                for (r in 0 until 5) for (c in 0 until 5) {
                    val u = (c - 2) * cell + du; val v = (2 - r) * cell + dv
                    val x = cx + tx * u + nx * v; val y = cy + ty * u + ny * v
                    val q = rSample(f, h, x, y, pt, rs)
                    s[5 * r + c] = if (q.isNaN()) Float.NaN else pol * q
                }
            }
        }
        // centre and normalise each sample set once (missing samples count as the mean)
        for (k in 0 until RuneCircle.SLOTS) for (o in 0 until 9) normalise(smp[k][o])
        // the cursor: the slot most like ▶ (reading clockwise) or ◀ (a mirrored camera: reading anticlockwise)
        var bestK = -1; var bestD = 0; var bestC = -2f; var secondC = -2f
        for (k in 0 until RuneCircle.SLOTS) {
            for (d in intArrayOf(1, -1)) {
                var cbest = -2f
                for (o in 0 until 9) cbest = max(cbest, corr(smp[k][o], CURSOR_T, d))
                if (cbest > bestC) { if (bestK != k) secondC = max(secondC, bestC); bestC = cbest; bestK = k; bestD = d }
                else if (k != bestK) secondC = max(secondC, cbest)
            }
        }
        if (bestC < 0.55f || bestC - secondC < 0.12f) return null
        val out = Array(14) { FloatArray(64) }
        for (k in 0 until 14) {
            val slot = ((bestK + bestD * (k + 1)) % RuneCircle.SLOTS + RuneCircle.SLOTS) % RuneCircle.SLOTS
            val sc = out[k]
            for (g in 0 until 64) {
                var b = -2f
                for (o in 0 until 9) b = max(b, corr(smp[slot][o], TEMPLATES[g], bestD))
                sc[g] = b
            }
        }
        return out
    }

    /** Centres [s] and scales it to unit length in place (missing samples become 0); all-NaN or flat → zeros. */
    private fun normalise(s: FloatArray) {
        var mean = 0f; var n = 0
        for (v in s) if (!v.isNaN()) { mean += v; n++ }
        if (n < 20) { s.fill(0f); return }
        mean /= n
        var ss = 0f
        for (i in s.indices) { s[i] = if (s[i].isNaN()) 0f else s[i] - mean; ss += s[i] * s[i] }
        if (ss <= 1e-6f) { s.fill(0f); return }
        val k = 1f / sqrt(ss)
        for (i in s.indices) s[i] *= k
    }

    /** Correlation of normalised samples with a centred, normalised template (columns reversed when [dir] is -1). */
    private fun corr(s: FloatArray, t: FloatArray, dir: Int): Float {
        var dot = 0f
        if (dir > 0) for (i in 0 until 25) dot += s[i] * t[i]
        else for (r in 0 until 5) { val b = 5 * r; dot += s[b] * t[b + 4] + s[b + 1] * t[b + 3] + s[b + 2] * t[b + 2] + s[b + 3] * t[b + 1] + s[b + 4] * t[b] }
        return dot
    }

    companion object {
        /** Edges used for the fit: radius, +1 if the ring lies outside it. */
        val EDGES = arrayOf(
            doubleArrayOf(RuneCircle.RI0.toDouble(), 1.0),
            doubleArrayOf(RuneCircle.RI1.toDouble(), -1.0),
            doubleArrayOf(RuneCircle.RO1.toDouble(), -1.0),
        )
        val OFF = doubleArrayOf(-0.4, 0.0, 0.4)
        fun template(g: Int): FloatArray {
            val v = FloatArray(25) { if ((g shr (24 - it)) and 1 == 1) 1f else 0f }
            val m = v.average().toFloat()
            var ss = 0f
            for (i in v.indices) { v[i] -= m; ss += v[i] * v[i] }
            val nn = sqrt(ss)
            for (i in v.indices) v[i] /= nn
            return v
        }
        val TEMPLATES: Array<FloatArray> = Array(64) { template(RuneCircle.GLYPHS[it]) }
        val CURSOR_T: FloatArray = template(RuneCircle.CURSOR)
    }
}

// ====================================================================== geometry helpers (private to this file)

private fun rApply(h: DoubleArray, x: Double, y: Double, out: DoubleArray) {
    val d = h[6] * x + h[7] * y + h[8]
    out[0] = (h[0] * x + h[1] * y + h[2]) / d
    out[1] = (h[3] * x + h[4] * y + h[5]) / d
}

private fun rInside(f: SigilFrame, x: Double, y: Double, m: Double = 2.0) = x >= m && y >= m && x <= f.w - 1 - m && y <= f.h - 1 - m

private fun rSample(f: SigilFrame, h: DoubleArray, x: Double, y: Double, pt: DoubleArray, r: Float): Float {
    rApply(h, x, y, pt)
    if (!rInside(f, pt[0], pt[1], 2.0 + r)) return Float.NaN
    val px = pt[0].toFloat(); val py = pt[1].toFloat()
    if (r < 0.6f) return f.at(px, py)
    return (2 * f.at(px, py) + f.at(px - r, py) + f.at(px + r, py) + f.at(px, py - r) + f.at(px, py + r)) / 6f
}

private fun rMul3(a: DoubleArray, b: DoubleArray): DoubleArray {
    val r = DoubleArray(9)
    for (i in 0 until 3) for (j in 0 until 3) { var s = 0.0; for (k in 0 until 3) s += a[3 * i + k] * b[3 * k + j]; r[3 * i + j] = s }
    return r
}

private fun rInv3(m: DoubleArray): DoubleArray? {
    val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
    val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
    val det = a * A + b * B + c * C
    if (abs(det) < 1e-12) return null
    return doubleArrayOf(A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
        B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
        C / det, -(a * h - b * g) / det, (a * e - b * d) / det)
}

/**
 * Homography (model → image) such that image points map back to model distance [rr] from the centre (0.5, 0.5):
 * Levenberg–Marquardt on the image → model map (normalised image coordinates); [affine]: perspective terms held at
 * zero (first round, one circle only). Returns it and the RMS residual (model units), gross outliers dropped once.
 */
private fun fitRadial(h0: DoubleArray, ix: DoubleArray, iy: DoubleArray, rr: DoubleArray, n: Int, affine: Boolean): Pair<DoubleArray, Double>? {
    var mx = 0.0; var my = 0.0
    for (k in 0 until n) { mx += ix[k]; my += iy[k] }
    mx /= n; my /= n
    var sc = 0.0
    for (k in 0 until n) sc += abs(ix[k] - mx) + abs(iy[k] - my)
    sc = max(1.0, sc / (2 * n))
    val tn = doubleArrayOf(1 / sc, 0.0, -mx / sc, 0.0, 1 / sc, -my / sc, 0.0, 0.0, 1.0)
    val tInv = doubleArrayOf(sc, 0.0, mx, 0.0, sc, my, 0.0, 0.0, 1.0)
    var g = rInv3(rMul3(tn, h0)) ?: return null
    val s0 = g[8]; g = DoubleArray(9) { g[it] / s0 }
    val nx = DoubleArray(n) { (ix[it] - mx) / sc }; val ny = DoubleArray(n) { (iy[it] - my) / sc }
    val use = BooleanArray(n) { true }
    val np = if (affine) 6 else 8
    fun res(gg: DoubleArray, k: Int): Double {
        val x = nx[k]; val y = ny[k]
        val d = gg[6] * x + gg[7] * y + gg[8]
        val X = (gg[0] * x + gg[1] * y + gg[2]) / d - 0.5; val Y = (gg[3] * x + gg[4] * y + gg[5]) / d - 0.5
        return sqrt(X * X + Y * Y) - rr[k]
    }
    var rms = 0.0
    val jac = DoubleArray(8)
    for (pass in 0 until 2) {
        for (iter in 0 until 6) {
            val jtj = Array(np) { DoubleArray(np + 1) }
            val jr = DoubleArray(np)
            for (k in 0 until n) {
                if (!use[k]) continue
                val x = nx[k]; val y = ny[k]
                val d = g[6] * x + g[7] * y + g[8]
                val xm = (g[0] * x + g[1] * y + g[2]) / d; val ym = (g[3] * x + g[4] * y + g[5]) / d
                val X = xm - 0.5; val Y = ym - 0.5
                val rho = max(1e-9, sqrt(X * X + Y * Y))
                val r = rho - rr[k]
                val ax = X / rho; val ay = Y / rho
                jac[0] = ax * x / d; jac[1] = ax * y / d; jac[2] = ax / d
                jac[3] = ay * x / d; jac[4] = ay * y / d; jac[5] = ay / d
                jac[6] = -(ax * xm + ay * ym) * x / d; jac[7] = -(ax * xm + ay * ym) * y / d
                for (p in 0 until np) { jr[p] += jac[p] * r; for (q in 0 until np) jtj[p][q] += jac[p] * jac[q] }
            }
            var tr = 0.0
            for (p in 0 until np) tr += jtj[p][p]
            for (p in 0 until np) { jtj[p][p] += 1e-4 * tr / np + 1e-12; jtj[p][np] = -jr[p] }
            val step = Fit.solve(jtj) ?: return null
            for (p in 0 until np) g[p] += step[p]
        }
        var ss = 0.0; var cnt = 0
        for (k in 0 until n) if (use[k]) { val r = res(g, k); ss += r * r; cnt++ }
        rms = sqrt(ss / max(1, cnt))
        if (pass == 1) break
        var kept = 0
        for (k in 0 until n) { use[k] = abs(res(g, k)) < max(0.004, 3 * rms); if (use[k]) kept++ }
        if (kept < 16) return null
    }
    val hn = rInv3(g) ?: return null
    return rMul3(tInv, hn) to rms
}

/** A half-size copy of a frame with an integral image, a contrast-gated threshold and component labelling (buffers reused). */
private class RSmall {
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
