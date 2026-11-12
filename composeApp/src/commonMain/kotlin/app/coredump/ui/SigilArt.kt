package app.coredump.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import app.coredump.net.ShortCode
import app.coredump.net.Sigil
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The self-test's handle on sigils: it asks for one to be shown over everything ([show]) and reads back the pixels
 * the last sigil drew ([layer]) to decode them.
 */
object SigilProbe {
    /** Shown full-screen by the app root while set (self-test only). */
    var show by mutableStateOf<ShortCode.Decoded?>(null)
    /** While true, sigils also record what they draw into [layer]. */
    var armed = false
    var layer: GraphicsLayer? = null
}

/**
 * The host's join code as the look's own sigil (see [Sigil] for the geometry every look keeps, so any phone reads
 * any look): a round sound wave of bars round a bullseye, drawn as square bars (TERMINAL), ink (GAZETTE), neon tubes
 * (ARCADE), rounded bars (QUIZ SHOW, CHAT), pixel blocks (QUEST), bold slabs (COMIC), bubbles (ABYSS), route lines
 * (METRO), bulbs (JACKPOT), bevelled blocks (DESKTOP), balls of star dust (ORACLE). It turns slowly; the colours and
 * the decoration round it move, the code itself never changes shape.
 */
@Composable
fun ThemedSigil(room: String, broker: Int, party: Boolean, modifier: Modifier = Modifier) {
    val heights = remember(room, broker, party) { Sigil.encode(room, broker, party) }
    val style = Th.style
    val plate = C.panel
    val inks = remember(plate, C.amber, C.hot, C.white, C.line) { SigilInks.of(plate, C.amber, C.hot, C.white, C.line) }
    val motion = !LocalLook.current.reduceMotion
    val clock = rememberClock(motion, fps = 30)
    val layer = rememberGraphicsLayer()
    DisposableEffect(layer) {
        SigilProbe.layer = layer
        onDispose { if (SigilProbe.layer === layer) SigilProbe.layer = null }
    }
    Spacer(
        modifier.aspectRatio(1f).drawWithCache {
            val g = SigilGeo(min(size.width, size.height), heights)
            val corner = min(style.corner.toPx(), g.side * 0.08f)
            onDrawBehind {
                val t = clock.value
                if (SigilProbe.armed) {
                    layer.record { drawSigil(style, g, inks, plate, t, corner) }
                    drawLayer(layer)
                } else {
                    drawSigil(style, g, inks, plate, t, corner)
                }
            }
        },
    )
}

/** Two inks with enough lightness against the plate for a cheap camera (else black or white), and a soft tint. */
internal class SigilInks(val main: Color, val alt: Color, val soft: Color) {
    companion object {
        private fun light(c: Color) = sqrt(c.luminance())

        fun of(plate: Color, vararg cands: Color): SigilInks {
            val lp = light(plate)
            val far = Color(if (lp > 0.5f) 0xFF111111 else 0xFFF6F6F6)
            val good = cands.filter { abs(light(it) - lp) >= 0.38f }.sortedByDescending { abs(light(it) - lp) }
            val main = good.firstOrNull() ?: far
            val alt = good.getOrNull(1) ?: main
            return SigilInks(main, alt, lerp(plate, main, 0.22f))
        }
    }
}

/** Geometry in pixels for a drawing [side] wide. */
internal class SigilGeo(val side: Float, val heights: IntArray) {
    /** Whether a decoration at [o] keeps clear of the code (corners and the outer margin only). */
    fun clear(o: Offset, extra: Float = 0f): Boolean {
        val dx = o.x - cx; val dy = o.y - cy
        return sqrt(dx * dx + dy * dy) - extra >= 10.2f * u
    }

    val u = side / (2 * Sigil.EXTENT)
    val cx = side / 2
    val cy = side / 2
    val cell = (2 * PI / Sigil.ANGLES).toFloat()
    /** Angular half-width a mark may take (a hair under the limit). */
    val half = cell * Sigil.DUTY / 2 * 0.94f

    fun p(r: Float, a: Float) = Offset(cx + r * u * cos(a), cy + r * u * sin(a))

    /** Whether ring [ring] (0 = timing, 1..3 = data) is drawn at angle [k]. */
    fun filled(ring: Int, k: Int): Boolean = k != 0 && (ring == 0 || heights[k] >= ring)

    /** Where the bar at angle [k] ends (outer edge of its last filled ring). */
    fun tip(k: Int): Float = if (heights[k] <= 0) Sigil.TIMING + Sigil.TIMING_HALF else Sigil.DATA[heights[k] - 1] + Sigil.DATA_HALF

    fun ringR(ring: Int) = if (ring == 0) Sigil.TIMING else Sigil.DATA[ring - 1]
    fun ringHalf(ring: Int) = if (ring == 0) Sigil.TIMING_HALF else Sigil.DATA_HALF

    /** Largest round mark that fits the cell of [ring] (radius, modules). */
    fun dotR(ring: Int) = min(ringHalf(ring), ringR(ring) * half) * 0.97f

    /** Annular sector from [r0] to [r1] (modules) round angle [a]. */
    fun sector(r0: Float, r1: Float, a: Float, h: Float = half): Path = Path().apply {
        val n = 5
        val s = p(r0, a - h); moveTo(s.x, s.y)
        for (i in 1..n) { val q = p(r0, a - h + 2 * h * i / n); lineTo(q.x, q.y) }
        for (i in 0..n) { val q = p(r1, a + h - 2 * h * i / n); lineTo(q.x, q.y) }
        close()
    }

    /** The same sector with round ends (the ends bulge to exactly [r0] and [r1]). */
    fun roundSector(r0: Float, r1: Float, a: Float): Path = Path().apply {
        val rr = min(r0 * half, (r1 - r0) / 2)
        val p1 = p(r0 + rr, a - half); val p2 = p(r1 - rr, a - half)
        val p3 = p(r1 - rr, a + half); val p4 = p(r0 + rr, a + half)
        val q1 = p(r1 + rr * 0.95f, a); val q2 = p(r0 - rr * 0.95f, a)
        moveTo(p1.x, p1.y); lineTo(p2.x, p2.y)
        quadraticTo(q1.x, q1.y, p3.x, p3.y)
        lineTo(p4.x, p4.y)
        quadraticTo(q2.x, q2.y, p1.x, p1.y)
        close()
    }
}

private val RINGS = 0..3

internal fun DrawScope.drawSigil(style: Style, g: SigilGeo, ink: SigilInks, plate: Color, t: Float, corner: Float) {
    // plate (and the look's frame round it: everything here stays outside the code's circle)
    drawRoundRect(plate, Offset.Zero, Size(g.side, g.side), CornerRadius(corner, corner))
    frame(style, g, ink, t, corner)
    val turn = t * 4f   // degrees: one turn in 90 s
    rotate(turn, Offset(g.cx, g.cy)) {
        // bullseye: disc, gap, ring (exact radii: this is what the camera finds)
        drawCircle(ink.main, Sigil.DISC * g.u, Offset(g.cx, g.cy))
        drawCircle(ink.main, (Sigil.GAP + Sigil.RING) / 2 * g.u, Offset(g.cx, g.cy), style = Stroke((Sigil.RING - Sigil.GAP) * g.u))
        marks(style, g, ink, plate, t)
        emblem(style, g, ink, plate, t)
    }
}

/** The bars / dots / blocks of the timing and data rings. */
private fun DrawScope.marks(style: Style, g: SigilGeo, ink: SigilInks, plate: Color, t: Float) {
    val r0 = Sigil.TIMING - Sigil.TIMING_HALF
    fun wave(k: Int, speed: Float = 1.6f) = lerp(ink.main, ink.alt, 0.5f + 0.5f * sin(t * speed - k * 0.45f))
    when (style) {
        Style.TERMINAL, Style.PAPER, Style.COMIC -> for (k in 1 until Sigil.ANGLES) {
            drawPath(g.sector(r0, g.tip(k), k * g.cell), ink.main)
        }
        Style.NEON -> for (k in 1 until Sigil.ANGLES) {
            val c = wave(k)
            val path = g.sector(r0, g.tip(k), k * g.cell)
            drawPath(path, c.copy(alpha = 0.12f), style = Stroke(0.28f * g.u))
            drawPath(path, c)
        }
        Style.CLEAN, Style.CHAT -> for (k in 1 until Sigil.ANGLES) {
            drawPath(g.roundSector(r0, g.tip(k), k * g.cell), if (style == Style.CHAT) wave(k, 2.2f) else ink.main)
        }
        Style.METRO -> for (k in 1 until Sigil.ANGLES) {
            // each ring its own line colour, rounded like a route map
            for (ring in RINGS) if (g.filled(ring, k)) {
                val c = if (ring % 2 == 0) ink.main else ink.alt
                drawPath(g.roundSector(g.ringR(ring) - g.ringHalf(ring), g.ringR(ring) + g.ringHalf(ring), k * g.cell), c)
            }
        }
        Style.PIXEL, Style.DESKTOP -> for (k in 1 until Sigil.ANGLES) for (ring in RINGS) if (g.filled(ring, k)) {
            cellBox(g, ring, k) { c, len, wid ->
                val tl = Offset(c.x - len / 2, c.y - wid / 2)
                drawRect(ink.main, tl, Size(len, wid))
                if (style == Style.DESKTOP) {
                    // bevel: a lit edge along two sides, inside the block
                    val e = wid * 0.16f
                    drawRect(ink.alt, tl, Size(len, e))
                    drawRect(ink.alt, tl, Size(e, wid))
                } else {
                    // pixel art: the four corner pixels knocked out
                    val q = min(len, wid) * 0.18f
                    for ((x, y) in listOf(tl.x to tl.y, tl.x + len - q to tl.y, tl.x to tl.y + wid - q, tl.x + len - q to tl.y + wid - q))
                        drawRect(plate, Offset(x, y), Size(q, q))
                }
            }
        }
        Style.ABYSS, Style.JACKPOT, Style.ORACLE -> for (k in 1 until Sigil.ANGLES) for (ring in RINGS) {
            val on = g.filled(ring, k)
            if (!on && (k == 0 || style != Style.JACKPOT)) continue
            cellBox(g, ring, k) { c, len, wid ->
                val tl = Offset(c.x - len / 2, c.y - wid / 2)
                val cr = CornerRadius(wid / 2, wid / 2)
                when {
                    !on -> drawRoundRect(ink.soft, tl, Size(len, wid), cr, style = Stroke(0.06f * g.u))
                    style == Style.ABYSS -> {
                        // a bubble rising outwards, with a glint (in the other ink: still a mark to the camera)
                        drawRoundRect(ink.main, tl, Size(len, wid), cr)
                        drawCircle(ink.alt, wid * 0.16f, Offset(c.x + len * 0.22f, c.y - wid * 0.18f))
                    }
                    style == Style.JACKPOT -> {
                        // a light tube with its bulb; one in seven blinks to the other colour, chasing round
                        val lit = ((k + ring + (t * 6).toInt()) % 7) != 0
                        drawRoundRect(if (lit) ink.main else ink.alt, tl, Size(len, wid), cr)
                        drawCircle(if (lit) ink.alt else ink.main, wid * 0.32f, c)
                    }
                    else -> {
                        // a streak of star dust with specks circling inside it
                        drawRoundRect(ink.main, tl, Size(len, wid), cr)
                        for (i in 0 until 3) {
                            val ang = t * (0.9f + 0.2f * (k % 3)) + i * 2.1f + k
                            drawCircle(ink.alt, wid * 0.13f, Offset(c.x + cos(ang) * len * 0.3f, c.y + sin(ang) * wid * 0.28f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws in the box of one cell ([ring], angle [k]), turned so x runs outwards: [block] gets the centre, the radial
 * length and the width (pixels), the largest box the geometry allows.
 */
private inline fun DrawScope.cellBox(g: SigilGeo, ring: Int, k: Int, block: DrawScope.(Offset, Float, Float) -> Unit) {
    val a = k * g.cell
    val c = g.p(g.ringR(ring), a)
    val len = 2 * g.ringHalf(ring) * 0.97f * g.u
    val wid = 2 * g.ringR(ring) * g.half * 0.97f * g.u
    rotate(a * 180f / PI.toFloat(), c) { block(c, len, wid) }
}

/** The look's mark at the key (where the code starts), outside the data. */
private fun DrawScope.emblem(style: Style, g: SigilGeo, ink: SigilInks, plate: Color, t: Float) {
    val c = g.p(Sigil.EMBLEM, 0f)
    val s = 0.62f * g.u
    when (style) {
        Style.TERMINAL -> if ((t * 2).toInt() % 2 == 0 || t == 0f) drawRect(ink.main, Offset(c.x - s * 0.45f, c.y - s * 0.75f), Size(s * 0.9f, s * 1.5f))
        Style.PAPER -> {
            val d = Path().apply { moveTo(c.x - s, c.y); lineTo(c.x, c.y - s * 0.6f); lineTo(c.x + s, c.y); lineTo(c.x, c.y + s * 0.6f); close() }
            drawPath(d, ink.main)
        }
        Style.NEON -> drawPath(star(c, s, s * 0.45f, 5, t * 0.6f), ink.alt)
        Style.CLEAN -> { drawCircle(ink.main, s * 0.85f, c, style = Stroke(s * 0.25f)); drawCircle(ink.main, s * 0.3f, c) }
        Style.PIXEL -> {
            // a pixel heart
            val rows = listOf("01010", "11111", "11111", "01110", "00100")
            val q = s * 0.36f
            for ((y, row) in rows.withIndex()) for ((x, ch) in row.withIndex()) if (ch == '1')
                drawRect(ink.main, Offset(c.x + (x - 2.5f) * q, c.y + (y - 2.5f) * q), Size(q, q))
        }
        Style.COMIC -> drawPath(star(c, s * 1.05f, s * 0.6f, 9, 0f), ink.main)
        Style.ABYSS -> {
            // a small jellyfish, bobbing
            val b = sin(t * 2f) * s * 0.12f
            drawArc(ink.main, 180f, 180f, true, Offset(c.x - s * 0.7f, c.y - s * 0.6f + b), Size(s * 1.4f, s * 1.2f))
            for (i in -1..1) drawLine(ink.main, Offset(c.x + i * s * 0.4f, c.y + b), Offset(c.x + i * s * 0.45f + sin(t * 3 + i) * s * 0.15f, c.y + s * 0.7f + b), s * 0.14f, StrokeCap.Round)
        }
        Style.METRO -> { drawCircle(ink.main, s * 0.8f, c, style = Stroke(s * 0.28f)); drawRect(ink.alt, Offset(c.x - s, c.y - s * 0.16f), Size(s * 2, s * 0.32f)) }
        Style.JACKPOT -> { drawCircle(ink.main, s * 0.9f, c); drawCircle(plate, s * 0.62f, c, style = Stroke(s * 0.12f)) }
        Style.CHAT -> {
            val tri = Path().apply { moveTo(c.x - s * 0.5f, c.y - s * 0.7f); lineTo(c.x + s * 0.75f, c.y); lineTo(c.x - s * 0.5f, c.y + s * 0.7f); close() }
            drawPath(tri, ink.main)
        }
        Style.DESKTOP -> {
            val arrow = Path().apply {
                moveTo(c.x - s * 0.5f, c.y - s * 0.8f); lineTo(c.x - s * 0.5f, c.y + s * 0.6f); lineTo(c.x - s * 0.1f, c.y + s * 0.25f)
                lineTo(c.x + s * 0.2f, c.y + s * 0.85f); lineTo(c.x + s * 0.42f, c.y + s * 0.75f); lineTo(c.x + s * 0.12f, c.y + s * 0.15f)
                lineTo(c.x + s * 0.6f, c.y + s * 0.15f); close()
            }
            drawPath(arrow, ink.main)
        }
        Style.ORACLE -> {
            // a crescent moon
            drawCircle(ink.main, s * 0.8f, c)
            drawCircle(plate, s * 0.68f, Offset(c.x + s * 0.32f, c.y - s * 0.12f))
        }
    }
}

/** Star polygon with [n] points round [c]. */
private fun star(c: Offset, outer: Float, inner: Float, n: Int, spin: Float): Path = Path().apply {
    for (i in 0 until 2 * n) {
        val r = if (i % 2 == 0) outer else inner
        val a = spin + i * PI.toFloat() / n - PI.toFloat() / 2
        val x = c.x + r * cos(a); val y = c.y + r * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The look's decoration round the code (beyond [Sigil.OUTER_BG] + a margin, or in the plate's corners). */
private fun DrawScope.frame(style: Style, g: SigilGeo, ink: SigilInks, t: Float, corner: Float) {
    val ring = 10.75f
    val center = Offset(g.cx, g.cy)
    when (style) {
        Style.TERMINAL -> {
            // a dial of ticks, turning the other way
            rotate(-t * 6f, center) {
                for (i in 0 until 72) if (i % 3 != 0) {
                    val a = i * 2 * PI.toFloat() / 72
                    drawLine(ink.soft, g.p(ring - 0.25f, a), g.p(ring + 0.25f, a), 0.12f * g.u)
                }
            }
        }
        Style.PAPER -> drawCircle(ink.soft, ring * g.u, center, style = Stroke(0.1f * g.u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.15f * g.u, 0.35f * g.u))))
        Style.NEON -> rotate(t * 20f, center) {
            drawCircle(lerp(ink.main, ink.alt, 0.5f + 0.5f * sin(t)).copy(alpha = 0.45f), ring * g.u, center,
                style = Stroke(0.14f * g.u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.2f * g.u, 0.8f * g.u))))
        }
        Style.CLEAN, Style.COMIC -> {
            // confetti or halftone in the corners
            val k = g.side
            for (i in 0 until 14) {
                val cx = if (i % 2 == 0) k * (0.05f + 0.012f * i) else k * (0.95f - 0.012f * i)
                val cy = if (i % 4 < 2) k * (0.06f + 0.01f * i) else k * (0.94f - 0.01f * i)
                val wob = if (style == Style.CLEAN) sin(t * 2 + i) * 0.15f * g.u else 0f
                if (!g.clear(Offset(cx + wob, cy), 0.3f * g.u)) continue
                drawCircle(if (i % 3 == 0) ink.alt.copy(alpha = 0.5f) else ink.soft, (0.18f + 0.04f * (i % 3)) * g.u, Offset(cx + wob, cy))
            }
        }
        Style.ABYSS -> {
            // a sonar ping sweeping out, and drifting plankton outside the code
            val ping = (t / 3.5f) % 1f
            drawCircle(ink.main.copy(alpha = 0.10f * (1 - ping)), (1f + ping * 10f) * g.u, center, style = Stroke(0.12f * g.u))
            for (i in 0 until 18) {
                val a = i * 0.9f + t * 0.05f * (1 + i % 3)
                val r = ring + 0.25f * sin(t * 0.7f + i)
                drawCircle(ink.soft, (0.07f + 0.03f * (i % 3)) * g.u, g.p(r, a))
            }
        }
        Style.METRO -> {
            // the circle line, with a train going round
            drawCircle(ink.soft, ring * g.u, center, style = Stroke(0.22f * g.u))
            val a = t * 0.35f
            drawLine(ink.alt, g.p(ring, a), g.p(ring, a + 0.12f), 0.32f * g.u, StrokeCap.Round)
        }
        Style.JACKPOT -> {
            // marquee bulbs chasing round
            val n = 40
            for (i in 0 until n) {
                val on = (i + (t * 8).toInt()) % 4 == 0
                drawCircle(if (on) ink.alt else ink.soft, 0.16f * g.u, g.p(ring, i * 2 * PI.toFloat() / n))
            }
        }
        Style.CHAT -> {
            // typing dots
            for (i in 0 until 3) {
                val lift = (sin(t * 5f - i * 0.8f) * 0.5f + 0.5f) * 0.25f * g.u
                drawCircle(ink.soft, 0.22f * g.u, Offset(g.side * 0.82f + i * 0.6f * g.u, g.side * 0.93f - lift))
            }
        }
        Style.DESKTOP -> {
            // marching ants round the plate
            val inset = 0.35f * g.u
            drawRect(ink.soft, Offset(inset, inset), Size(g.side - 2 * inset, g.side - 2 * inset),
                style = Stroke(0.08f * g.u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.3f * g.u, 0.3f * g.u), t * 0.6f * g.u)))
        }
        Style.ORACLE -> {
            // a few twinkling stars in the corners
            for (i in 0 until 16) {
                val x = ((i * 37) % 100) / 100f; val y = ((i * 61 + 13) % 100) / 100f
                val px = g.side * (if (x < 0.5f) x * 0.22f else 1 - (1 - x) * 0.22f)
                val py = g.side * (if (y < 0.5f) y * 0.22f else 1 - (1 - y) * 0.22f)
                val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 2 + i * 1.7f))
                if (!g.clear(Offset(px, py), 0.1f * g.u)) continue
                drawCircle(ink.alt.copy(alpha = 0.5f * tw), 0.09f * g.u, Offset(px, py))
            }
        }
        Style.PIXEL -> {
            // corner brackets, blinking
            if ((t * 1.5f).toInt() % 2 == 0) {
                val q = 0.35f * g.u; val l = 1.6f * g.u; val e = 0.5f * g.u
                for ((x, y) in listOf(e to e, g.side - e to e, e to g.side - e, g.side - e to g.side - e)) {
                    val sx = if (x < g.cx) 1 else -1; val sy = if (y < g.cy) 1 else -1
                    drawRect(ink.soft, Offset(min(x, x + sx * l), min(y, y + sy * q)), Size(l, q))
                    drawRect(ink.soft, Offset(min(x, x + sx * q), min(y, y + sy * l)), Size(q, l))
                }
            }
        }
    }
}
