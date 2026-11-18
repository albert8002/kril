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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DESKTOP: a retro disk-defragmenter window. A bevelled grey window with a title bar, a sunken cluster map (12 × 8
 * blocks: one fixed "system" row on top, then 7 rows = 84 data blocks, used = bit 1, free = bit 0), a progress bar,
 * buttons, a taskbar with a clock. The read/write head hops from block to block; blocks it passes flash "reading" /
 * "writing" and settle from fragmented colours to the optimised blue (hue only: lightness never changes), the head's
 * block lifts a little, the progress bar and percentage run, the pointer idles over the buttons.
 *
 * Reader: its own half-resolution adaptive threshold (with a noise margin) and labelling; the cluster map's thick
 * frame is a ring component → farthest-point quad → edge lines refined at full resolution → homography. The frame's
 * long side and the title bar above it give "up", the system row (a pattern unlike its mirror) gives the flip.
 * Blocks are read by a 5-point median against ink / paper planes fitted to the blocks themselves.
 */
object Defrag : SigilScheme {
    override val name = "defrag"

    /** Diagnostics hook for the bench (null in the app). */
    var debug: ((String) -> Unit)? = null
    private inline fun dbg(msg: () -> String) { debug?.let { it(msg()) } }

    // ---------------------------------------------------------------- layout (unit square)
    private const val PX0 = 0.15f; private const val PX1 = 0.85f      // map frame, outer edge
    private const val PY0 = 0.255f; private const val PY1 = 0.735f
    private const val RING = 0.018f                                      // frame thickness
    private const val GAP = 0.013f                                       // paper between frame and blocks
    private const val COLS = 12; private const val ROWS = 8
    private const val CX0 = PX0 + RING + GAP; private const val CY0 = PY0 + RING + GAP
    private const val PW = (PX1 - PX0 - 2 * (RING + GAP)) / COLS
    private const val PH = (PY1 - PY0 - 2 * (RING + GAP)) / ROWS
    private const val TB0 = 0.105f; private const val TB1 = 0.19f       // title bar
    private const val TBX0 = 0.075f; private const val TBX1 = 0.925f

    /** The fixed "system" row (unlike its mirror image in every place). */
    private val TIMING = intArrayOf(1, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0)

    /** Scheme-specific whitening of the 84 bits (another look's reader reading these frames gets garbage). */
    private val MASK = BooleanArray(SigilPayload.BITS).also {
        var s = 0x5DEECE6
        for (i in it.indices) { s = s * 1103515245 + 12345; it[i] = (s ushr 16) and 1 == 1 }
    }

    private fun cellX(c: Int) = CX0 + (c + 0.5f) * PW
    private fun cellY(r: Int) = CY0 + (r + 0.5f) * PH

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

    private val FRAG_HUES = floatArrayOf(0f, 330f, 175f, 280f, 25f)

    private fun hash(i: Int): Float { var x = i * 0x27d4eb2d + 0x165667b1; x = x xor (x ushr 15); x *= 0x2c1b3c6d; x = x xor (x ushr 12); return (x and 0xFFFF) / 65535f }

    private fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun easeOut(x: Float): Float { val c = 1f - x.coerceIn(0f, 1f); return 1f - c * c * c }
    /** Ease-out with a small overshoot (a window or balloon popping open). */
    private fun popOut(x: Float): Float { val c = x.coerceIn(0f, 1f) - 1f; return 1f + c * c * (2.4f * c + 1.4f) }

    /** 3 × 5 pixel font, rows top to bottom, 3 bits each. */
    private val FONT: Map<Char, Int> = mapOf(
        'A' to 0b010_101_111_101_101, 'C' to 0b011_100_100_100_011, 'D' to 0b110_101_101_101_110, 'E' to 0b111_100_110_100_111,
        'F' to 0b111_100_110_100_100, 'G' to 0b011_100_101_101_011, 'R' to 0b110_101_110_101_101, 'S' to 0b011_100_010_001_110,
        'T' to 0b111_010_010_010_010, 'O' to 0b010_101_101_101_010, 'P' to 0b110_101_110_100_100, 'U' to 0b101_101_101_101_111,
        'I' to 0b111_010_010_010_111, 'V' to 0b101_101_101_101_010, 'L' to 0b100_100_100_100_111, 'M' to 0b101_111_111_101_101,
        'N' to 0b110_101_101_101_101, 'K' to 0b101_101_110_101_101, 'Z' to 0b111_001_010_100_111, 'W' to 0b101_101_111_111_101,
        'Y' to 0b101_101_010_010_010, 'H' to 0b101_101_111_101_101, 'B' to 0b110_101_110_101_110,
        '0' to 0b111_101_101_101_111, '1' to 0b010_110_010_010_111, '2' to 0b110_001_010_100_111, '3' to 0b110_001_010_001_110,
        '4' to 0b101_101_111_001_001, '5' to 0b111_100_110_001_110, '6' to 0b011_100_111_101_111, '7' to 0b111_001_010_010_010,
        '8' to 0b111_101_111_101_111, '9' to 0b111_101_111_001_110, '%' to 0b101_001_010_100_101, ':' to 0b000_010_000_010_000,
        '.' to 0b000_000_000_000_010, '_' to 0b000_000_000_000_111, '!' to 0b010_010_010_000_010,
    )

    /** Each glyph as horizontal runs (row, first column, length): one rect per run. */
    private val RUNS: Map<Char, IntArray> = FONT.mapValues { (_, g) ->
        val r = ArrayList<Int>()
        for (row in 0 until 5) {
            var col = 0
            while (col < 3) {
                if ((g shr (14 - row * 3 - col)) and 1 == 1) {
                    val c0 = col
                    while (col < 3 && (g shr (14 - row * 3 - col)) and 1 == 1) col++
                    r.add(row); r.add(c0); r.add(col - c0)
                } else col++
            }
        }
        r.toIntArray()
    }

    private fun text(out: Scene, s: String, x: Float, y: Float, px: Float, color: Int, count: Int = s.length) {
        var cx = x
        for ((k, ch) in s.withIndex()) {
            if (k >= count) break
            val g = RUNS[ch]
            if (g != null) {
                var i = 0
                while (i < g.size) {
                    val row = g[i]; val c0 = g[i + 1]; val len = g[i + 2]
                    out.rect(cx + (c0 + len / 2f) * px, y + (row + 0.5f) * px, px * len + px * 0.02f, px * 1.02f, color)
                    i += 3
                }
            }
            cx += 4 * px
        }
    }

    /** A Win9x-style raised (or sunken) box: face, light top-left edge, dark bottom-right edge. */
    private fun bevel(out: Scene, x0: Float, y0: Float, x1: Float, y1: Float, face: Int, hi: Int, lo: Int, lw: Float, sunken: Boolean = false) {
        if (face != 0) out.rect((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0, face)
        val a = if (sunken) lo else hi; val b = if (sunken) hi else lo
        out.rect((x0 + x1) / 2, y0 + lw / 2, x1 - x0, lw, a)
        out.rect(x0 + lw / 2, (y0 + y1) / 2, lw, y1 - y0, a)
        out.rect((x0 + x1) / 2, y1 - lw / 2, x1 - x0, lw, b)
        out.rect(x1 - lw / 2, (y0 + y1) / 2, lw, y1 - y0, b)
    }

    /** The look's colours for one ink (worked out once per palette, not per frame). */
    private class Look(ink: Ink) {
        val p = ink.plate; val main = ink.main; val alt = ink.alt
        val dark = ink.dark
        // decoration: strong only AWAY from the ink, weak toward it
        val hi = if (dark) Colors.mix(p, WHITE, 0.22f) else Colors.mix(p, WHITE, 0.75f)
        val lo = if (dark) Colors.mix(p, BLACK, 0.55f) else Colors.mix(p, BLACK, 0.3f)
        val paper = if (dark) Colors.mix(p, BLACK, 0.5f) else Colors.mix(p, WHITE, 0.62f)
        val desk = tint(Colors.mix(p, main, if (dark) 0.18f else 0.42f), 182f, 0.62f)
        val deskHi = Colors.alpha(hi, 0.05f)
        val edge = Colors.mix(lo, BLACK, 0.3f)
        val outline = Colors.mix(paper, main, 0.16f)
        val logo = IntArray(4) { tint(alt, floatArrayOf(4f, 120f, 215f, 50f)[it], 0.85f) }
        val ledOn = tint(paper, 120f, 0.9f); val ledOff = Colors.mix(paper, main, 0.3f)
        val trayOn = tint(paper, 45f, 0.9f)
        val diskBody = Colors.mix(paper, main, 0.55f)
        val titleInk = if (dark) Colors.mix(alt, BLACK, 0.85f) else paper
        val titleHues = IntArray(72) { Colors.hueShift(alt, it * 5f) }
        val frag = IntArray(FRAG_HUES.size) { tint(alt, FRAG_HUES[it], 0.8f) }
        val readC = tint(alt, 120f, 0.85f)
        val writeC = tint(alt, 0f, 0.9f)
        val hatch = tint(alt, 45f, 0.9f)
        val bevHi = Colors.alpha(Colors.mix(alt, WHITE, 0.5f), 0.55f)
        val bevLo = Colors.alpha(Colors.mix(alt, BLACK, 0.45f), 0.55f)
        val lift = Colors.alpha(lo, 0.55f)
        val cursor = tint(alt, 55f, 1f)
        val tip = if (dark) Colors.mix(p, tint(p, 55f, 0.6f), 0.6f) else tint(Colors.mix(p, WHITE, 0.85f), 56f, 0.35f)
        val shadow = Colors.alpha(BLACK, 0.1f)
        val scan = Colors.alpha(BLACK, if (dark) 0.12f else 0.045f)
        val roll = Colors.alpha(WHITE, if (dark) 0.025f else 0.05f)
        val glare = Colors.alpha(WHITE, if (dark) 0.06f else 0.1f)
        val vignette = Colors.alpha(BLACK, if (dark) 0.5f else 0.28f)
        val arrowFill = if (dark) main else WHITE
        val arrowEdge = if (dark) p else BLACK
    }

    private var lookKey = 0L
    private var lookCache: Look? = null
    private fun look(ink: Ink): Look {
        val k = (ink.plate.toLong() shl 32) xor (ink.main.toLong() and 0xFFFFFFFFL) xor (ink.alt.toLong() * 31)
        val c = lookCache
        if (c != null && k == lookKey) return c
        val n = Look(ink); lookCache = n; lookKey = k
        return n
    }

    /** The classic arrow pointer at (x, y); an hourglass while [busy] (0..1, its sand turning). */
    private fun pointer(out: Scene, lk: Look, x: Float, y: Float, busy: Float) {
        if (busy > 0.01f) {
            val h = 0.04f; val w = 0.026f
            val turn = 180f * ease(busy)
            val c = kotlin.math.cos(turn * kotlin.math.PI.toFloat() / 180f); val s = kotlin.math.sin(turn * kotlin.math.PI.toFloat() / 180f)
            fun px(u: Float, v: Float) = x + u * c - v * s
            fun py(u: Float, v: Float) = y + u * s + v * c
            val pts = floatArrayOf(px(-w / 2, -h / 2), py(-w / 2, -h / 2), px(w / 2, -h / 2), py(w / 2, -h / 2), px(w * 0.08f, 0f), py(w * 0.08f, 0f),
                px(w / 2, h / 2), py(w / 2, h / 2), px(-w / 2, h / 2), py(-w / 2, h / 2), px(-w * 0.08f, 0f), py(-w * 0.08f, 0f))
            out.poly(pts, lk.arrowFill)
            out.poly(pts, lk.arrowEdge, 0.003f)
            out.circle(px(0f, h * 0.3f), py(0f, h * 0.3f), w * 0.18f, lk.hatch)
            return
        }
        val arrow = floatArrayOf(0f, 0f, 0f, 0.05f, 0.012f, 0.039f, 0.021f, 0.058f, 0.029f, 0.054f, 0.02f, 0.036f, 0.036f, 0.036f)
        val pts = FloatArray(arrow.size) { if (it % 2 == 0) x + arrow[it] else y + arrow[it] }
        out.poly(pts, lk.arrowFill)
        out.poly(pts, lk.arrowEdge, 0.003f)
    }

    override fun draw(bits: BooleanArray, t: Float, ink: Ink, out: Scene) {
        val lk = look(ink)
        val p = lk.p; val main = lk.main; val alt = lk.alt
        val lw = 0.006f

        // ---- desktop wallpaper (slow diagonal sheen) and the window's soft drop shadow
        out.rect(0.5f, 0.5f, 1f, 1f, lk.desk)
        for (k in 0 until 6) {
            val x = ((k * 0.21f + t * 0.011f) % 1.26f) - 0.13f
            out.line(x, -0.02f, x - 0.3f, 1.02f, 0.03f + 0.012f * hash(k), lk.deskHi)
        }
        for (k in 1..3) out.rect(0.5f + 0.006f * k, 0.495f + 0.008f * k, 0.89f, 0.82f, lk.shadow, corner = 0.004f * k)

        // ---- taskbar: Start (a glint runs across now and then), tray lights, a clock with a blinking colon
        bevel(out, -0.01f, 0.932f, 1.01f, 1.01f, p, lk.hi, lk.lo, lw * 0.8f)
        bevel(out, 0.035f, 0.945f, 0.17f, 0.99f, p, lk.hi, lk.lo, lw * 0.7f)
        val logo = 0.0075f
        for (q in 0 until 4) out.rect(0.052f + (q % 2) * logo * 1.25f, 0.958f + (q / 2) * logo * 1.25f, logo, logo, lk.logo[q], -8f + 3f * sin(t * 0.8f))
        text(out, "START", 0.072f, 0.957f, 0.0045f, main)
        val gk = floor(t / 5.5f).toInt(); val ga = (t - gk * 5.5f - 1.5f * hash(gk)) / 0.6f
        if (ga in 0f..1f) out.rect(0.04f + 0.125f * ease(ga), 0.9675f, 0.012f, 0.04f, Colors.alpha(WHITE, 0.45f * sin(ga * 3.1416f)), 20f)
        bevel(out, 0.74f, 0.945f, 0.965f, 0.99f, 0, lk.lo, lk.hi, lw * 0.5f)
        for (q in 0 until 2) {
            val on = sin(t * (2.3f + 1.7f * q) + q) > 0.2f
            out.rect(0.755f + q * 0.016f, 0.9675f, 0.009f, 0.009f, if (on) lk.trayOn else lk.ledOff)
        }
        val secs = 9 * 3600 + 41 * 60 + t.toInt()
        val colon = if ((t % 1f) < 0.5f) ":" else " "
        text(out, "${(secs / 3600) % 24}$colon${((secs / 60) % 60).toString().padStart(2, '0')}", 0.86f, 0.957f, 0.0045f, main)

        // ---- the window
        bevel(out, 0.055f, 0.085f, 0.945f, 0.905f, p, lk.hi, lk.lo, lw)
        out.rect(0.5f, 0.905f - lw * 0.4f, 0.89f, lw * 0.8f, lk.edge)
        out.rect(0.945f - lw * 0.4f, 0.495f, lw * 0.8f, 0.82f, lk.edge)
        // title bar: alt with a travelling hue gradient (lightness kept)
        val segs = 10
        for (k in 0 until segs) {
            val x0 = TBX0 + (TBX1 - TBX0) * k / segs; val x1 = TBX0 + (TBX1 - TBX0) * (k + 1) / segs
            val hue = 28f * k / segs + 10f * sin(t * 0.7f + k * 0.35f) + 6f * sin(t * 0.23f)
            out.rect((x0 + x1) / 2, (TB0 + TB1) / 2, x1 - x0 + 0.002f, TB1 - TB0, lk.titleHues[((hue / 5f).toInt() % 72 + 72) % 72])
        }
        // disk icon with a busy light
        bevel(out, 0.086f, 0.118f, 0.144f, 0.177f, lk.paper, lk.hi, lk.lo, 0.004f)
        out.rect(0.115f, 0.128f, 0.03f, 0.014f, lk.diskBody)
        out.rect(0.124f + 0.004f * sin(t * 3f), 0.128f, 0.007f, 0.01f, lk.paper)
        val busy = sin(t * 17f) + sin(t * 5.3f) > 0.3f
        out.rect(0.134f, 0.168f, 0.008f, 0.005f, if (busy) lk.ledOn else lk.ledOff)
        text(out, "DEFRAG", 0.158f, 0.12f, 0.0105f, lk.titleInk)
        val bw = 0.052f
        for ((k, bx) in floatArrayOf(0.742f, 0.798f, 0.862f).withIndex()) {
            bevel(out, bx, 0.12f, bx + bw, 0.175f, p, lk.hi, lk.lo, 0.004f)
            val gx = bx + bw / 2; val gy = 0.1475f
            when (k) {
                0 -> out.rect(gx, gy + 0.012f, 0.02f, 0.006f, main)
                1 -> { out.rect(gx, gy, 0.024f, 0.022f, main, stroke = 0.004f); out.rect(gx, gy - 0.009f, 0.024f, 0.004f, main) }
                else -> { out.line(gx - 0.011f, gy - 0.011f, gx + 0.011f, gy + 0.011f, 0.006f, main, false); out.line(gx - 0.011f, gy + 0.011f, gx + 0.011f, gy - 0.011f, 0.006f, main, false) }
            }
        }

        // ---- the cluster map: frame, paper, blocks
        out.rect((PX0 + PX1) / 2, (PY0 + PY1) / 2, PX1 - PX0, PY1 - PY0, main)
        out.rect((PX0 + PX1) / 2 + 0.003f, PY1 + 0.004f, PX1 - PX0, 0.005f, lk.hi)
        out.rect(PX1 + 0.004f, (PY0 + PY1) / 2 + 0.003f, 0.005f, PY1 - PY0, lk.hi)
        out.rect((PX0 + PX1) / 2, (PY0 + PY1) / 2, PX1 - PX0 - 2 * RING, PY1 - PY0 - 2 * RING, lk.paper)

        val n = COLS * ROWS
        val rate = 4.2f                                   // blocks per second
        val cyc = n + 18                                  // a short rest after each pass
        val pos = t * rate
        val pass = floor(pos / cyc).toInt()
        val inPass = pos - pass * cyc
        val headI = min(n - 1, inPass.toInt())
        val hop = ease((inPass - inPass.toInt()) / 0.3f)  // the head eases to the next block
        val resting = inPass >= n

        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val i = r * COLS + c
            val on = if (r == 0) TIMING[c] == 1 else bits[(r - 1) * COLS + c] != MASK[(r - 1) * COLS + c]
            val cx = cellX(c); val cy = cellY(r)
            val bwid = PW * 0.8f; val bht = PH * 0.8f
            if (!on) {
                out.rect(cx, cy, bwid, bht, lk.outline, stroke = 0.0025f)
                continue
            }
            // state colour: fragmented (scattered hues) until the head has passed, then the optimised colour
            val done = resting || i < headI
            val since = if (resting) 99f else (inPass - i) / rate
            val col = when {
                r == 0 -> alt
                !resting && i == headI -> lk.readC
                !done -> lk.frag[(hash(i * 7 + pass * 131) * FRAG_HUES.size).toInt().coerceIn(0, FRAG_HUES.size - 1)]
                since < 1.2f -> Colors.mix(lk.writeC, alt, ease(since / 1.2f))
                else -> alt
            }
            val lift = if (!resting && i == headI) 0.055f * PW * (1f - abs(hop - 0.5f) * 2f) else 0f
            if (lift > 0f) out.rect(cx + lift, cy + lift, bwid, bht, lk.lift)
            out.rect(cx - lift, cy - lift, bwid, bht, col)
            // a bevel inside the block (outside the sampled middle)
            val e = bwid * 0.09f
            out.rect(cx - lift, cy - lift - bht / 2 + e / 2, bwid, e, lk.bevHi)
            out.rect(cx - lift + bwid / 2 - e / 2, cy - lift, e, bht, lk.bevLo)
            if (r == 0) out.line(cx - bwid * 0.3f, cy + bht * 0.3f, cx + bwid * 0.3f, cy - bht * 0.3f, bwid * 0.12f, lk.hatch, false)
        }
        if (!resting) {
            // the read/write head: a bracket in the gutters, easing from block to block, shedding a few pixel sparks
            val prev = max(0, headI - 1)
            val k = if (headI == 0) 1f else hop
            val hx = cellX(prev % COLS) + (cellX(headI % COLS) - cellX(prev % COLS)) * k
            val hy = cellY(prev / COLS) + (cellY(headI / COLS) - cellY(prev / COLS)) * k
            val s = 0.5f * PW; val a = 0.38f * PW; val th = 0.0042f
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                out.line(hx + sx * s, hy + sy * s, hx + sx * (s - a * 0.45f), hy + sy * s, th, lk.cursor, false)
                out.line(hx + sx * s, hy + sy * s, hx + sx * s, hy + sy * (s - a * 0.45f), th, lk.cursor, false)
            }
            val ph = inPass - inPass.toInt()
            for (q in 0 until 4) {
                val a2 = 6.283f * hash(headI * 7 + q)
                val d = 0.48f * PW + 0.03f * easeOut(ph)
                out.rect(hx + d * kotlin.math.cos(a2), hy + d * kotlin.math.sin(a2), 0.0045f, 0.0045f, Colors.alpha(lk.cursor, 1f - ph))
            }
        }

        // ---- status: progress bar (segmented, the newest segment pulsing), percentage, a typed status word, buttons
        val frac = if (resting) 1f else (inPass / n).coerceIn(0f, 1f)
        bevel(out, 0.15f, 0.785f, 0.60f, 0.822f, lk.paper, lk.hi, lk.lo, 0.004f, sunken = true)
        val segW = 0.021f
        val segF = (0.44f / segW) * frac
        val nSeg = segF.toInt()
        for (k in 0 until nSeg) out.rect(0.158f + k * segW + segW * 0.42f, 0.8035f, segW * 0.8f, 0.024f, alt)
        if (nSeg < 21) out.rect(0.158f + nSeg * segW + segW * 0.42f, 0.8035f, segW * 0.8f, 0.024f, Colors.alpha(alt, ease(segF - nSeg) * (0.6f + 0.4f * sin(t * 12f))))
        text(out, "${(frac * 100).toInt()}%", 0.615f, 0.789f, 0.0062f, main)
        val word = if (resting) "DONE" else if (hop < 0.999f) "MOVE" else if (((pos * 2f).toInt() and 1) == 0) "READ" else "WRITE"
        val typed = min(word.length, ((inPass - inPass.toInt()) * 18f).toInt() + 1)
        text(out, word, 0.152f, 0.85f, 0.0052f, main, typed)
        if ((t * 2.5f).toInt() % 2 == 0) out.rect(0.152f + typed * 4 * 0.0052f + 0.004f, 0.863f, 0.004f, 0.026f, main)
        for ((k, label) in listOf("STOP", "PAUSE").withIndex()) {
            val x0 = 0.585f + k * 0.135f
            bevel(out, x0, 0.842f, x0 + 0.125f, 0.886f, p, lk.hi, lk.lo, 0.004f)
            text(out, label, x0 + 0.0625f - label.length * 0.0105f, 0.856f, 0.0053f, main)
        }

        // ---- the mouse: idles over STOP, now and then flicks to the percentage and back, sometimes busy
        val mk = floor(t / 3.1f).toInt(); val ma = t - mk * 3.1f - 1.2f * hash(mk * 3 + 1)
        val flick = if (hash(mk * 5 + 2) < 0.6f && ma in 0f..1.1f) (if (ma < 0.35f) easeOut(ma / 0.35f) else if (ma < 0.75f) 1f else 1f - ease((ma - 0.75f) / 0.35f)) else 0f
        val bk = floor(t / 4.3f).toInt(); val ba = t - bk * 4.3f - 2f * hash(bk * 11)
        val busyT = if (hash(bk * 13) < 0.5f && ba in 0f..0.9f) ba / 0.9f else 0f
        val mx = 0.66f + 0.012f * sin(t * 0.9f) + 0.006f * sin(t * 2.3f) + (0.64f - 0.66f) * flick
        val my = 0.868f + 0.008f * sin(t * 1.3f + 1f) + (0.785f - 0.868f) * flick
        pointer(out, lk, mx, my, busyT)

        // ---- a balloon from the tray now and then
        val tk = floor(t / 7.3f).toInt(); val ta = t - tk * 7.3f - 2.5f * hash(tk * 7 + 3)
        if (ta in 0f..2.6f) {
            val sOpen = popOut(ta / 0.3f) * (1f - ease((ta - 2.2f) / 0.4f))
            if (sOpen > 0.02f) {
                val bx = 0.80f; val by = 0.885f
                val w = 0.27f * sOpen; val h = 0.06f * sOpen
                out.rect(bx + 0.004f, by + 0.004f - h / 2, w, h, lk.shadow, corner = 0.012f * sOpen)
                out.rect(bx, by - h / 2, w, h, lk.tip, corner = 0.012f * sOpen)
                out.rect(bx, by - h / 2, w, h, main, corner = 0.012f * sOpen, stroke = 0.0025f)
                out.poly(floatArrayOf(bx + 0.06f * sOpen, by - 0.002f, bx + 0.09f * sOpen, by - 0.002f, bx + 0.1f * sOpen, by + 0.035f * sOpen), lk.tip)
                if (sOpen > 0.8f) text(out, if (hash(tk) < 0.5f) "DRIVE C: OK!" else "DEFRAG RUNNING", bx - w / 2 + 0.014f, by - h / 2 - 0.009f, 0.0042f, main)
            }
        }

        // ---- the CRT: scan lines, a slow rolling refresh band, vignette and a breathing glass glare
        var sy = (t * 0.004f) % 0.024f
        while (sy < 1f) { out.rect(0.5f, sy, 1f, 0.0035f, lk.scan); sy += 0.024f }
        val roll = ((t / 7f) % 1f) * 1.3f - 0.15f
        out.rect(0.5f, roll, 1f, 0.09f, lk.roll)
        out.rect(0.5f, roll, 1f, 0.03f, lk.roll)
        for ((qx, qy) in arrayOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) out.glow(qx, qy, 0.32f, lk.vignette)
        out.glow(0.22f + 0.02f * sin(t * 0.21f), 0.18f, 0.36f, Colors.alpha(lk.glare, (0.75f + 0.25f * sin(t * 0.37f)) * Colors.a(lk.glare) / 255f))
    }

    // ---------------------------------------------------------------- reading

    override fun reader(): SchemeReader = DefragReader()

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

    private class DefragReader : SchemeReader {
        override var seen = false
            private set

        private val tmp = FloatArray(2)
        /** The code from the last [readQuad] that found the map (null: it did not decode). */
        private var got: Read? = null

        override fun feed(f: SigilFrame): Read? {
            seen = false
            if (f.w < 64 || f.h < 64) return null
            val half = Half(f)
            val cls = half.classify(12, 30)
            half.label(cls)
            // frame candidates: big, hollow, low fill
            val minSide = min(half.w, half.h) * 0.18f
            val cands = half.comps.filter { c ->
                val bw = c[4] - c[2] + 1; val bh = c[5] - c[3] + 1
                val big = max(bw, bh)
                val fill = c[1].toFloat() / (bw * bh)
                big >= minSide && bw < half.w * 0.98f && bh < half.h * 0.98f && fill in 0.02f..0.45f && c[1] >= 60 &&
                    min(bw, bh) > big * 0.3f
            }.sortedByDescending { c ->
                val bw = c[4] - c[2] + 1; val bh = c[5] - c[3] + 1
                sqrt((bw * bw + bh * bh).toFloat()) * (1f - c[1].toFloat() / (bw * bh))
            }.take(6)
            if (debug != null) for (c in cands) dbg { "cand cls=${c[0]} area=${c[1]} box=${c[2]},${c[3]}-${c[4]},${c[5]}" }
            if (cands.isEmpty()) dbg { "no candidates" }
            for (c in cands) {
                val quad = quadOf(half, c) ?: run { dbg { "quadOf fail" }; null }
                if (quad == null) continue
                val dark = c[0] == 1
                val refined = refine(f, quad, dark) ?: run { dbg { "refine fail" }; null }
                if (refined == null) continue
                dbg { "quad " + refined.joinToString(",") { it.toInt().toString() } + " from " + quad.joinToString(",") { it.toInt().toString() } }
                val okq = readQuad(f, refined, dark)
                if (!okq) continue
                seen = true
                return got
            }
            return null
        }

        /** Four corners (full-resolution pixels, in turning order) of a hollow component: farthest-point method. */
        private fun quadOf(half: Half, c: IntArray): FloatArray? {
            val runs = half.runsOf(c[8])
            val cx = c[6].toFloat() / c[1]; val cy = c[7].toFloat() / c[1]
            // the hole: the middle must be mostly free of the component
            val bw = c[4] - c[2] + 1; val bh = c[5] - c[3] + 1
            val rr = max(2, min(bw, bh) / 8)
            var own = 0
            for (i in runs) {
                val y = half.ry[i]
                if (y < cy - rr || y > cy + rr) continue
                val a = max(half.rx0[i].toFloat(), cx - rr); val b = min(half.rx1[i].toFloat(), cx + rr)
                if (b >= a) own += (b - a + 1).toInt()
            }
            if (own > (2 * rr + 1) * (2 * rr + 1) * 0.7f) { dbg { "no hole" }; return null }
            // extremes over run end points (the farthest point of a run from anything is one of its ends)
            var bx = 0f; var by = 0f; var bd = -1f
            for (i in runs) for (x in intArrayOf(half.rx0[i], half.rx1[i])) {
                val y = half.ry[i]
                val d = (x - cx) * (x - cx) + (y - cy) * (y - cy); if (d > bd) { bd = d; bx = x.toFloat(); by = y.toFloat() }
            }
            var ox = 0f; var oy = 0f; bd = -1f
            for (i in runs) for (x in intArrayOf(half.rx0[i], half.rx1[i])) {
                val y = half.ry[i]
                val d = (x - bx) * (x - bx) + (y - by) * (y - by); if (d > bd) { bd = d; ox = x.toFloat(); oy = y.toFloat() }
            }
            val dl = sqrt(bd)
            if (dl < 20f) { dbg { "small" }; return null }
            val nx = -(oy - by) / dl; val ny = (ox - bx) / dl
            var px = 0f; var py = 0f; var pd = 0f; var qx = 0f; var qy = 0f; var qd = 0f
            for (i in runs) for (x in intArrayOf(half.rx0[i], half.rx1[i])) {
                val y = half.ry[i]
                val d = (x - bx) * nx + (y - by) * ny
                if (d > pd) { pd = d; px = x.toFloat(); py = y.toFloat() }
                if (d < qd) { qd = d; qx = x.toFloat(); qy = y.toFloat() }
            }
            if (pd < dl * 0.15f || -qd < dl * 0.15f) { dbg { "flat" }; return null }
            val pts = floatArrayOf(bx, by, px, py, ox, oy, qx, qy)
            for (i in pts.indices) pts[i] = pts[i] * 2f + 0.5f
            return order(pts)
        }

        /** The quad's corners sorted by angle round their middle (clockwise on screen, y down). */
        private fun order(q: FloatArray): FloatArray {
            val mx = (q[0] + q[2] + q[4] + q[6]) / 4; val my = (q[1] + q[3] + q[5] + q[7]) / 4
            val idx = (0 until 4).sortedBy { atan2(q[2 * it + 1] - my, q[2 * it] - mx) }
            return FloatArray(8) { q[2 * idx[it / 2] + it % 2] }
        }

        /** Edge lines fitted to the frame's outer boundary at full resolution (twice), intersected. */
        private fun refine(f: SigilFrame, quad: FloatArray, dark: Boolean): FloatArray? {
            var q = quad
            repeat(2) { pass ->
                val mx = (q[0] + q[2] + q[4] + q[6]) / 4; val my = (q[1] + q[3] + q[5] + q[7]) / 4
                val lines = FloatArray(12)
                var longest = 0f
                for (e in 0 until 4) { val a = e; val b = (e + 1) % 4; longest = max(longest, hypot(q[2 * b] - q[2 * a], q[2 * b + 1] - q[2 * a + 1])) }
                val reach = (longest * (if (pass == 0) 0.04f else 0.025f)).coerceIn(4f, 16f)
                for (e in 0 until 4) {
                    val ax = q[2 * e]; val ay = q[2 * e + 1]; val bx = q[2 * ((e + 1) % 4)]; val by = q[2 * ((e + 1) % 4) + 1]
                    val len = hypot(bx - ax, by - ay)
                    if (len < 10f) return null
                    var nx = -(by - ay) / len; var ny = (bx - ax) / len
                    // outward
                    if (((ax + bx) / 2 - mx) * nx + ((ay + by) / 2 - my) * ny < 0) { nx = -nx; ny = -ny }
                    val xs = FloatArray(16); val ys = FloatArray(16); var n = 0
                    for (k in 0 until 16) {
                        val tt = 0.1f + 0.8f * k / 15f
                        val px = ax + (bx - ax) * tt; val py = ay + (by - ay) * tt
                        val s = edgeAlong(f, px, py, nx, ny, reach, dark)
                        if (s.isNaN()) continue
                        xs[n] = px + nx * s; ys[n] = py + ny * s; n++
                    }
                    if (n < 6) return null
                    val line = fitLine(xs, ys, n) ?: return null
                    lines[3 * e] = line[0]; lines[3 * e + 1] = line[1]; lines[3 * e + 2] = line[2]
                }
                val out = FloatArray(8)
                for (k in 0 until 4) {
                    val a = (k + 3) % 4; val b = k   // corner k joins edge k-1 and edge k
                    val p = cross(lines, a, b) ?: return null
                    out[2 * k] = p[0]; out[2 * k + 1] = p[1]
                }
                q = out
            }
            // sanity: corners inside a generous frame, convex
            for (k in 0 until 4) if (q[2 * k] < -f.w * 0.3f || q[2 * k] > f.w * 1.3f || q[2 * k + 1] < -f.h * 0.3f || q[2 * k + 1] > f.h * 1.3f) return null
            return q
        }

        private fun hypot(a: Float, b: Float) = sqrt(a * a + b * b)

        /** Line a·x + b·y = c (a² + b² = 1) through points (total least squares, one outlier pass). */
        private fun fitLine(xs: FloatArray, ys: FloatArray, n0: Int): FloatArray? {
            var n = n0
            var res: FloatArray? = null
            repeat(2) { pass ->
                if (n < 4) return res
                var mx = 0.0; var my = 0.0
                for (i in 0 until n) { mx += xs[i]; my += ys[i] }
                mx /= n; my /= n
                var sxx = 0.0; var syy = 0.0; var sxy = 0.0
                for (i in 0 until n) { val dx = xs[i] - mx; val dy = ys[i] - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
                // normal = eigenvector of the smaller eigenvalue
                val th = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
                val dxl = kotlin.math.cos(th); val dyl = kotlin.math.sin(th)
                val a = -dyl; val b = dxl
                val c = a * mx + b * my
                res = floatArrayOf(a.toFloat(), b.toFloat(), c.toFloat())
                if (pass == 0) {
                    var m = 0
                    for (i in 0 until n) if (abs(a * xs[i] + b * ys[i] - c) < 1.6) { xs[m] = xs[i]; ys[m] = ys[i]; m++ }
                    n = m
                }
            }
            return res
        }

        private fun cross(l: FloatArray, i: Int, j: Int): FloatArray? {
            val a1 = l[3 * i]; val b1 = l[3 * i + 1]; val c1 = l[3 * i + 2]
            val a2 = l[3 * j]; val b2 = l[3 * j + 1]; val c2 = l[3 * j + 2]
            val d = a1 * b2 - a2 * b1
            if (abs(d) < 0.2f) return null   // nearly parallel neighbours: not a quad
            return floatArrayOf((c1 * b2 - c2 * b1) / d, (a1 * c2 - a2 * c1) / d)
        }

        /** Distance along n (from (bx, by)) of the outer edge of the ink, searching from [reach] outside inward. */
        private fun edgeAlong(f: SigilFrame, bx: Float, by: Float, nx: Float, ny: Float, reach: Float, dark: Boolean): Float {
            val step = 0.5f
            val m = (2 * reach / step).toInt() + 1
            val pv = FloatArray(m)
            var ext = if (dark) 999f else -999f
            for (i in 0 until m) {
                val s = reach - i * step
                val x = bx + nx * s; val y = by + ny * s
                if (x < 1 || y < 1 || x > f.w - 2 || y > f.h - 2) return Float.NaN
                pv[i] = f.at(x, y)
                ext = if (dark) min(ext, pv[i]) else max(ext, pv[i])
            }
            val o = (pv[0] + pv[1] + pv[2]) / 3
            if (abs(o - ext) < 14f) return Float.NaN
            val thr = (o + ext) / 2
            for (i in 1 until m) {
                val inside = if (dark) pv[i] < thr else pv[i] > thr
                if (inside) {
                    val a = pv[i - 1]; val b = pv[i]
                    val fr = if (a == b) 0f else ((a - thr) / (a - b)).coerceIn(0f, 1f)
                    return reach - (i - 1 + fr) * step
                }
            }
            return Float.NaN
        }

        private fun map(h: DoubleArray, x: Float, y: Float): FloatArray { Fit.apply(h, x, y, tmp); return tmp }

        /** Median of a 5-point cross round a block's middle (design coordinates). */
        private fun cell(f: SigilFrame, h: DoubleArray, x: Float, y: Float): Float {
            val dx = PW * 0.24f; val dy = PH * 0.24f
            val v = FloatArray(5)
            var k = 0
            for ((ox, oy) in arrayOf(0f to 0f, dx to 0f, -dx to 0f, 0f to dy, 0f to -dy)) {
                map(h, x + ox, y + oy); v[k++] = f.at(tmp[0], tmp[1])
            }
            v.sort()
            return v[2]
        }

        /**
         * Reads the map in the quad: picks the orientation (system row + title bar), thresholds the blocks against
         * ink / paper planes, decodes into [got]. False = this quad is not the map.
         */
        private fun readQuad(f: SigilFrame, q: FloatArray, dark: Boolean): Boolean {
            got = null
            val e01 = hypot(q[2] - q[0], q[3] - q[1]) + hypot(q[6] - q[4], q[7] - q[5])
            val e12 = hypot(q[4] - q[2], q[5] - q[3]) + hypot(q[0] - q[6], q[1] - q[7])
            val ratio = max(e01, e12) / max(1f, min(e01, e12))
            if (ratio < 1.15f || ratio > 2.2f) { dbg { "aspect" }; return false }
            val design = floatArrayOf(PX0, PY0, PX1, PY0, PX1, PY1, PX0, PY1)
            // hypotheses: which image corner is the top-left, and the turning sense
            val hyps = ArrayList<DoubleArray>()
            for (mir in 0..1) for (k in 0 until 4) {
                val idx = IntArray(4) { j -> if (mir == 0) (k + j) % 4 else (k - j + 8) % 4 }
                // top edge (TL→TR) must be a long side unless the shape is nearly square
                val topLen = hypot(q[2 * idx[1]] - q[2 * idx[0]], q[2 * idx[1] + 1] - q[2 * idx[0] + 1])
                val sideLen = hypot(q[2 * idx[3]] - q[2 * idx[0]], q[2 * idx[3] + 1] - q[2 * idx[0] + 1])
                if (topLen < sideLen) continue
                val dst = FloatArray(8) { q[2 * idx[it / 2] + it % 2] }
                Fit.homography(design, dst)?.let { hyps.add(it) }
            }
            if (hyps.isEmpty()) return false
            // all 96 blocks in the first hypothesis' frame: they set the ink / paper planes (image coordinates)
            val h0 = hyps[0]
            val n = COLS * ROWS
            val vals = FloatArray(n); val ix = FloatArray(n); val iy = FloatArray(n)
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                val i = r * COLS + c
                vals[i] = cell(f, h0, cellX(c), cellY(r))
                map(h0, cellX(c), cellY(r)); ix[i] = tmp[0]; iy[i] = tmp[1]
            }
            val split = Fit.otsu(vals)
            if (split[1] < 0.55f) { dbg { "otsu weak ${(split[1] * 100).toInt()}" }; return false }
            // the frame says which side is ink
            var fs = 0f
            for (k in 0 until 24) {
                val tt = (k / 4 + 0.5f) / 6f
                val x: Float; val y: Float
                when (k % 4) {
                    0 -> { x = PX0 + (PX1 - PX0) * tt; y = PY0 + RING / 2 }
                    1 -> { x = PX0 + (PX1 - PX0) * tt; y = PY1 - RING / 2 }
                    2 -> { x = PX0 + RING / 2; y = PY0 + (PY1 - PY0) * tt }
                    else -> { x = PX1 - RING / 2; y = PY0 + (PY1 - PY0) * tt }
                }
                map(h0, x, y); fs += f.at(tmp[0], tmp[1])
            }
            val inkIsLow = fs / 24 < split[0]
            if (inkIsLow != dark) { dbg { "polarity mismatch" }; return false }
            // the frame: every stretch of it ink, with paper just inside and just outside
            var good = 0
            for (k in 0 until 28) {
                val tt = (k / 4 + 0.5f) / 7f
                val ring = FloatArray(2); val gap = FloatArray(2); val out = FloatArray(2)
                for ((dst, ins) in listOf(ring to RING / 2, gap to RING + GAP * 0.45f, out to -RING * 0.9f)) {
                    when (k % 4) {
                        0 -> { dst[0] = PX0 + (PX1 - PX0) * tt; dst[1] = PY0 + ins }
                        1 -> { dst[0] = PX0 + (PX1 - PX0) * tt; dst[1] = PY1 - ins }
                        2 -> { dst[0] = PX0 + ins; dst[1] = PY0 + (PY1 - PY0) * tt }
                        else -> { dst[0] = PX1 - ins; dst[1] = PY0 + (PY1 - PY0) * tt }
                    }
                }
                map(h0, ring[0], ring[1]); val vr = f.at(tmp[0], tmp[1])
                map(h0, gap[0], gap[1]); val vg = f.at(tmp[0], tmp[1])
                map(h0, out[0], out[1])
                val vo = if (tmp[0] < 0 || tmp[1] < 0 || tmp[0] >= f.w || tmp[1] >= f.h) vg else f.at(tmp[0], tmp[1])
                val sg = if (inkIsLow) 1f else -1f
                if ((vg - vr) * sg > 12f && (vo - vr) * sg > 12f) good++
            }
            if (good < 20) { dbg { "frame check $good" }; return false }
            var thrA = doubleArrayOf(split[0].toDouble(), 0.0, 0.0)
            var conA = doubleArrayOf(if (inkIsLow) 60.0 else -60.0, 0.0, 0.0)
            val ox = f.w / 2f; val oy = f.h / 2f
            for (i in 0 until n) { ix[i] -= ox; iy[i] -= oy }
            repeat(2) {
                val inkP = plane(ix, iy, vals, n) { i -> (vals[i] < thr(thrA, ix[i], iy[i])) == inkIsLow } ?: return false
                val papP = plane(ix, iy, vals, n) { i -> (vals[i] < thr(thrA, ix[i], iy[i])) != inkIsLow } ?: return false
                thrA = DoubleArray(3) { (inkP[it] + papP[it]) / 2 }
                conA = DoubleArray(3) { papP[it] - inkP[it] }
            }
            /** Signed ink-ness of design point (x, y) under h: > 0 = used, about ±1 for clean blocks. */
            fun inkness(h: DoubleArray, x: Float, y: Float, median: Boolean): Float {
                val v: Float
                if (median) v = cell(f, h, x, y) else {
                    map(h, x, y)
                    if (tmp[0] < 0 || tmp[1] < 0 || tmp[0] >= f.w || tmp[1] >= f.h) return 0f
                    v = f.at(tmp[0], tmp[1])
                }
                map(h, x, y)
                val px = tmp[0] - ox; val py = tmp[1] - oy
                val con = thr(conA, px, py)
                if (abs(con) < 8f) return 0f
                return ((thr(thrA, px, py) - v) / con * 2f).coerceIn(-1.5f, 1.5f)
            }
            // which hypothesis: the system row on top, the title bar above it
            var best = -1; var bestScore = -1e9f
            for ((hi, h) in hyps.withIndex()) {
                var sc = 0f
                for (c in 0 until COLS) { val v = inkness(h, cellX(c), cellY(0), true); sc += if (TIMING[c] == 1) v else -v }
                var tb = 0f
                for (k in 0 until 6) tb += inkness(h, 0.46f + 0.048f * k, 0.13f, false)
                sc += tb * 0.8f
                dbg { "hyp $hi timing ${(sc - tb * 0.8f).toInt()} title ${tb.toInt()}" }
                if (sc > bestScore) { bestScore = sc; best = hi }
            }
            if (best < 0 || bestScore < 6f) { dbg { "orientation score ${bestScore.toInt()}" }; return false }
            val hb = hyps[best]
            var bad = 0
            for (c in 0 until COLS) if ((inkness(hb, cellX(c), cellY(0), true) > 0) != (TIMING[c] == 1)) bad++
            if (bad > 2) { dbg { "timing bad=$bad" }; return true }
            val bitsOut = BooleanArray(SigilPayload.BITS); val sure = FloatArray(SigilPayload.BITS)
            for (r in 1 until ROWS) for (c in 0 until COLS) {
                val v = inkness(hb, cellX(c), cellY(r), true)
                val b = (r - 1) * COLS + c
                bitsOut[b] = (v > 0) != MASK[b]
                sure[b] = abs(v)
            }
            got = SigilPayload.decodeBits(bitsOut, sure)
            if (got == null) dbg { "decode fail, weak=${sure.count { it < 0.3f }}" }
            return true
        }

        private fun thr(p: DoubleArray, x: Float, y: Float) = (p[0] + p[1] * x + p[2] * y).toFloat()

        /** Least-squares plane v = a + b·x + c·y over the blocks [take] picks. */
        private inline fun plane(xs: FloatArray, ys: FloatArray, vs: FloatArray, n: Int, take: (Int) -> Boolean): DoubleArray? {
            val m = Array(3) { DoubleArray(4) }
            var cnt = 0
            for (i in 0 until n) {
                if (!take(i)) continue
                cnt++
                val row = doubleArrayOf(1.0, xs[i].toDouble(), ys[i].toDouble())
                for (a in 0 until 3) { for (b in 0 until 3) m[a][b] += row[a] * row[b]; m[a][3] += row[a] * vs[i] }
            }
            if (cnt < 8) return null
            // a little ridge keeps it stable when the blocks lie close to a line
            m[1][1] += 1e-2 * cnt; m[2][2] += 1e-2 * cnt
            return Fit.solve(m)
        }
    }

}
