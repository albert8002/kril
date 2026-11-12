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
import androidx.compose.ui.graphics.Brush
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
import kotlin.math.atan2
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
 * The host's join code as the look's own sigil, each with its own layout of cells (see [Sigil]; any phone reads
 * any look): a memory map with a radar sweep (TERMINAL), a halftone print with crop marks (GAZETTE), a striped neon
 * sun (ARCADE), a honeycomb of buzzer tiles (QUIZ SHOW), a pixel gem (QUEST), a starburst of ink slabs (COMIC),
 * a turning ball of glowing dots (ABYSS), a metro map (METRO), a roulette wheel of chips (JACKPOT), a spiral thread
 * of messages (CHAT), a board of bevelled tiles (DESKTOP) and a patch of twinkling night sky (ORACLE).
 * It turns slowly and its light, colours and decoration move; the cells themselves stay put.
 */
@Composable
fun ThemedSigil(room: String, broker: Int, party: Boolean, modifier: Modifier = Modifier) {
    val bits = remember(room, broker, party) { Sigil.encode(room, broker, party) }
    val style = Th.style
    val plate = C.panel
    val inks = remember(plate, C.amber, C.hot, C.white, C.line) { SigilInks.of(plate, listOf(C.amber, C.hot, C.white, C.line)) }
    val motion = !LocalLook.current.reduceMotion
    val clock = rememberClock(motion, fps = 30)
    val layer = rememberGraphicsLayer()
    DisposableEffect(layer) {
        SigilProbe.layer = layer
        onDispose { if (SigilProbe.layer === layer) SigilProbe.layer = null }
    }
    Spacer(
        modifier.aspectRatio(1f).drawWithCache {
            val g = SigilGeo(min(size.width, size.height), bits, style.ordinal % Sigil.LAYOUTS)
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

/** Two inks with enough lightness against the plate for a cheap camera (else black or white), and soft tints. */
internal class SigilInks(val main: Color, val alt: Color, val soft: Color, val faint: Color) {
    companion object {
        private fun light(c: Color) = sqrt(c.luminance())

        fun of(plate: Color, cands: List<Color>): SigilInks {
            val lp = light(plate)
            val far = Color(if (lp > 0.5f) 0xFF111111 else 0xFFF6F6F6)
            val good = cands.filter { abs(light(it) - lp) >= 0.38f }.sortedByDescending { abs(light(it) - lp) }
            val main = good.firstOrNull() ?: far
            val alt = good.getOrNull(1) ?: main
            return SigilInks(main, alt, lerp(plate, main, 0.22f), lerp(plate, main, 0.11f))
        }
    }
}

/** Geometry in pixels for a drawing [side] wide, cells of [layout]. */
internal class SigilGeo(val side: Float, val bits: BooleanArray, val layout: Int) {
    val u = side / (2 * Sigil.EXTENT)
    val cx = side / 2
    val cy = side / 2
    val center = Offset(cx, cy)
    private val cells = Sigil.layouts[layout]
    val n = Sigil.CELLS

    fun x(i: Int) = cells[2 * i]
    fun y(i: Int) = cells[2 * i + 1]
    fun r(i: Int) = sqrt(x(i) * x(i) + y(i) * y(i))
    fun at(i: Int) = Offset(cx + x(i) * u, cy + y(i) * u)
    fun on(i: Int) = bits[i]
    fun p(r: Float, a: Float) = Offset(cx + r * u * cos(a), cy + r * u * sin(a))
    fun pt(x: Float, y: Float) = Offset(cx + x * u, cy + y * u)

    /** Whether a decoration at [o] (pixels) keeps clear of the code and the emblem: corners only. */
    fun clear(o: Offset, extra: Float = 0f): Boolean {
        val dx = o.x - cx; val dy = o.y - cy
        return sqrt(dx * dx + dy * dy) - extra >= 11.0f * u
    }
}

internal fun DrawScope.drawSigil(style: Style, g: SigilGeo, ink: SigilInks, plate: Color, t: Float, corner: Float) {
    drawRoundRect(plate, Offset.Zero, Size(g.side, g.side), CornerRadius(corner, corner))
    corners(style, g, ink, t)
    val turn = t * 4f   // degrees: one turn in 90 s
    rotate(turn, g.center) {
        when (style) {
            Style.TERMINAL -> terminal(g, ink, t)
            Style.PAPER -> gazette(g, ink, plate, t)
            Style.NEON -> arcade(g, ink, t)
            Style.CLEAN -> quiz(g, ink, t)
            Style.PIXEL -> quest(g, ink, t)
            Style.COMIC -> comic(g, ink, t)
            Style.ABYSS -> abyss(g, ink, t)
            Style.METRO -> metro(g, ink, t)
            Style.JACKPOT -> jackpot(g, ink, t)
            Style.CHAT -> chat(g, ink, t)
            Style.DESKTOP -> desktop(g, ink, t)
            Style.ORACLE -> oracle(g, ink, plate, t)
        }
    }
}

/** Disc, gap, ring at their exact radii (what the camera finds). */
private fun DrawScope.bullseye(g: SigilGeo, c: Color, ring: Color = c) {
    drawCircle(c, Sigil.DISC * g.u, g.center)
    drawCircle(ring, (Sigil.GAP + Sigil.RING) / 2 * g.u, g.center, style = Stroke((Sigil.RING - Sigil.GAP) * g.u))
}

/** The emblem's centre (where the code starts). */
private fun emblemAt(g: SigilGeo) = g.pt(Sigil.EMBLEM, 0f)

private fun star(c: Offset, outer: Float, inner: Float, n: Int, spin: Float): Path = Path().apply {
    for (i in 0 until 2 * n) {
        val r = if (i % 2 == 0) outer else inner
        val a = spin + i * PI.toFloat() / n - PI.toFloat() / 2
        val x = c.x + r * cos(a); val y = c.y + r * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun deg(x: Float, y: Float) = atan2(y, x) * 180f / PI.toFloat()

// ---------------------------------------------------------------------------------------------------- the looks

/** A round memory map: block characters, dots for empty bytes, a radar sweep, a block cursor at the start. */
private fun DrawScope.terminal(g: SigilGeo, ink: SigilInks, t: Float) {
    val sweep = (t * 50f) % 360f
    drawArc(ink.main.copy(alpha = 0.07f), sweep - 36f, 36f, true, Offset(g.cx - 9.1f * g.u, g.cy - 9.1f * g.u), Size(18.2f * g.u, 18.2f * g.u))
    drawCircle(ink.soft, 9.3f * g.u, g.center, style = Stroke(0.05f * g.u))
    bullseye(g, ink.main)
    val s = 0.84f * g.u
    for (i in 0 until g.n) {
        val c = g.at(i)
        if (g.on(i)) {
            // blocks the sweep just passed glow in the other ink for a moment
            var a = (deg(g.x(i), g.y(i)) - sweep) % 360f
            if (a < 0) a += 360f
            drawRect(if (a > 330f) ink.alt else ink.main, Offset(c.x - s / 2, c.y - s / 2), Size(s, s))
        } else drawCircle(ink.soft, 0.09f * g.u, c)
    }
    val e = emblemAt(g)
    drawRect(if ((t * 2).toInt() % 2 == 0) ink.main else ink.alt, Offset(e.x - 0.32f * g.u, e.y - 0.55f * g.u), Size(0.64f * g.u, 1.1f * g.u))
}

/** A halftone print: round ink dots on a turned screen, a printer's border, a fleuron. */
private fun DrawScope.gazette(g: SigilGeo, ink: SigilInks, plate: Color, t: Float) {
    drawCircle(ink.soft, 9.3f * g.u, g.center, style = Stroke(0.06f * g.u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.12f * g.u, 0.24f * g.u))))
    bullseye(g, ink.main)
    // the press breathes a little
    val dot = (0.46f + 0.02f * sin(t * 1.3f)) * g.u
    for (i in 0 until g.n) if (g.on(i)) drawCircle(ink.main, dot, g.at(i))
    val e = emblemAt(g)
    val d = Path().apply {
        moveTo(e.x - 0.6f * g.u, e.y); lineTo(e.x, e.y - 0.42f * g.u); lineTo(e.x + 0.6f * g.u, e.y); lineTo(e.x, e.y + 0.42f * g.u); close()
    }
    drawPath(d, ink.main)
    drawCircle(plate, 0.1f * g.u, e)
}

/** A striped neon sun: rows of glowing tubes, colour climbing from one ink to the other, a star. */
private fun DrawScope.arcade(g: SigilGeo, ink: SigilInks, t: Float) {
    bullseye(g, ink.main, ink.alt)
    val hx = 0.52f * g.u; val hy = 0.27f * g.u
    val glow = 0.12f * g.u
    for (i in 0 until g.n) {
        val c = g.at(i)
        val tl = Offset(c.x - hx, c.y - hy)
        if (g.on(i)) {
            // the gradient of a synthwave sun, scrolling upwards
            val k = 0.5f + 0.5f * sin(g.y(i) * 0.35f + t * 2.2f)
            val col = lerp(ink.main, ink.alt, k)
            drawRoundRect(col.copy(alpha = 0.16f), Offset(tl.x - glow, tl.y - glow), Size(2 * hx + 2 * glow, 2 * hy + 2 * glow),
                CornerRadius(hy + glow, hy + glow))
            drawRoundRect(col, tl, Size(2 * hx, 2 * hy), CornerRadius(hy, hy))
        } else {
            drawRoundRect(ink.faint, tl, Size(2 * hx, 2 * hy), CornerRadius(hy, hy), style = Stroke(0.05f * g.u))
        }
    }
    drawPath(star(emblemAt(g), 0.62f * g.u, 0.27f * g.u, 5, t * 0.8f), ink.alt)
}

/** A honeycomb of buzzer tiles: a light runs round the lit ones; a buzzer at the start. */
private fun DrawScope.quiz(g: SigilGeo, ink: SigilInks, t: Float) {
    bullseye(g, ink.main)
    val r = 0.6f * g.u
    for (i in 0 until g.n) {
        val c = g.at(i)
        val hex = Path().apply {
            for (k in 0 until 6) {
                val a = (k * 60f + 30f) * PI.toFloat() / 180f
                val x = c.x + r * cos(a); val y = c.y + r * sin(a)
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        if (g.on(i)) {
            val lit = ((i + (t * 5f).toInt()) % 9) == 0
            drawPath(hex, if (lit) ink.alt else ink.main)
        } else drawPath(hex, ink.soft, style = Stroke(0.06f * g.u))
    }
    val e = emblemAt(g)
    drawCircle(ink.main, 0.58f * g.u, e)
    drawCircle(ink.alt, 0.24f * g.u, e)
}

/** A pixel gem: square pixels on a diagonal grid, each with a lit corner; sparkles; a pixel heart. */
private fun DrawScope.quest(g: SigilGeo, ink: SigilInks, t: Float) {
    bullseye(g, ink.main)
    val s = 0.8f * g.u
    val q = 0.2f * g.u
    for (i in 0 until g.n) {
        val c = g.at(i)
        if (g.on(i)) {
            drawRect(ink.main, Offset(c.x - s / 2, c.y - s / 2), Size(s, s))
            drawRect(ink.alt, Offset(c.x - s / 2, c.y - s / 2), Size(q, q))
        } else if ((i + (t * 3).toInt()) % 11 == 0) {
            // a sparkle on an empty spot, now and then
            drawRect(ink.soft, Offset(c.x - q, c.y - q / 2), Size(2 * q, q))
            drawRect(ink.soft, Offset(c.x - q / 2, c.y - q), Size(q, 2 * q))
        }
    }
    val e = emblemAt(g)
    val rows = listOf("01010", "11111", "11111", "01110", "00100")
    val p = 0.24f * g.u
    for ((y, row) in rows.withIndex()) for ((x, ch) in row.withIndex()) if (ch == '1')
        drawRect(ink.main, Offset(e.x + (x - 2.5f) * p, e.y + (y - 2.5f) * p), Size(p, p))
}

/** A starburst: slanted ink slabs on spokes, a little shake, a burst at the start. */
private fun DrawScope.comic(g: SigilGeo, ink: SigilInks, t: Float) {
    bullseye(g, ink.main)
    val len = 0.84f * g.u; val wid = 0.7f * g.u; val slant = 0.16f * g.u
    for (i in 0 until g.n) {
        if (!g.on(i)) continue
        val c = g.at(i)
        val shake = if ((i + (t * 4).toInt()) % 13 == 0) 0.04f * g.u else 0f
        rotate(deg(g.x(i), g.y(i)), c) {
            val slab = Path().apply {
                moveTo(c.x - len / 2 + slant + shake, c.y - wid / 2); lineTo(c.x + len / 2 + slant + shake, c.y - wid / 2)
                lineTo(c.x + len / 2 - slant + shake, c.y + wid / 2); lineTo(c.x - len / 2 - slant + shake, c.y + wid / 2); close()
            }
            drawPath(slab, if ((i * 7) % 5 == 0) ink.alt else ink.main)
        }
    }
    drawPath(star(emblemAt(g), 0.66f * g.u, 0.34f * g.u, 9, 0f), ink.main)
}

/**
 * A ball of glowing dots turning in the dark: the dots are shaded like little spheres lit from a light that circles
 * round, bigger at the front of the ball; motes orbit it on a tilted ring, dimmer behind. A jellyfish at the start.
 */
private fun DrawScope.abyss(g: SigilGeo, ink: SigilInks, t: Float) {
    val ballR = 9.2f
    motes(g, ink, t, front = false)
    bullseye(g, ink.main)
    drawCircle(ink.alt, 0.55f * g.u, Offset(g.cx - 0.35f * g.u, g.cy - 0.35f * g.u))
    val la = t * 0.9f
    val lx = cos(la); val ly = sin(la)
    for (i in 0 until g.n) {
        val c = g.at(i)
        if (!g.on(i)) { drawCircle(ink.faint, 0.12f * g.u, c); continue }
        val q = g.r(i) / ballR
        val z = sqrt((1f - q * q).coerceAtLeast(0f))
        val rr = (0.36f + 0.1f * z) * g.u
        val hi = Offset(c.x + lx * rr * 0.4f, c.y + ly * rr * 0.4f)
        drawCircle(Brush.radialGradient(listOf(ink.alt, ink.main), hi, rr * 1.2f), rr, c)
    }
    motes(g, ink, t, front = true)
    // a jellyfish, bobbing
    val e = emblemAt(g)
    val s = 0.62f * g.u
    val b = sin(t * 2f) * s * 0.1f
    drawArc(ink.main, 180f, 180f, true, Offset(e.x - s * 0.75f, e.y - s * 0.55f + b), Size(s * 1.5f, s * 1.3f))
    for (k in -1..1) drawLine(ink.main, Offset(e.x + k * s * 0.42f, e.y + 0.05f * s + b),
        Offset(e.x + k * s * 0.48f + sin(t * 3 + k) * s * 0.15f, e.y + s * 0.62f + b), s * 0.16f, StrokeCap.Round)
}

/** Motes on a tilted ring round the ball (soft: they never read as cells). */
private fun DrawScope.motes(g: SigilGeo, ink: SigilInks, t: Float, front: Boolean) {
    val tilt = 1.1f
    for (k in 0 until 36) {
        val a = k * 2 * PI.toFloat() / 36 + t * 0.35f
        val x = 8.9f * cos(a)
        val y0 = 8.9f * sin(a)
        val y = y0 * cos(tilt)
        val z = y0 * sin(tilt)
        if ((z > 0) != front) continue
        val depth = 0.5f + 0.5f * (z / 8.9f)
        drawCircle(ink.main.copy(alpha = 0.05f + 0.09f * depth), (0.08f + 0.06f * depth) * g.u, g.pt(x, y))
    }
}

/** A metro map: three octagonal lines, a station dot where a cell is lit, a roundel at the start. */
private fun DrawScope.metro(g: SigilGeo, ink: SigilInks, t: Float) {
    for (rv in floatArrayOf(5.45f, 7.0f, 8.6f)) {
        val path = Path()
        for (k in 0..8) {
            val a = (k * 45f + 22.5f) * PI.toFloat() / 180f
            val p = g.p(rv, a)
            if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, ink.soft, style = Stroke(0.22f * g.u))
    }
    bullseye(g, ink.main)
    for (i in 0 until g.n) if (g.on(i)) {
        val c = g.at(i)
        drawCircle(ink.main, 0.46f * g.u, c)
        drawCircle(ink.alt, 0.2f * g.u, c)
    }
    // a train running round the outer line (soft: it is not a station)
    val a = t * 0.3f
    drawLine(ink.soft, g.p(8.6f, a), g.p(8.6f, a + 0.16f), 0.3f * g.u, StrokeCap.Round)
    val e = emblemAt(g)
    drawCircle(ink.main, 0.58f * g.u, e)
    drawRect(ink.alt, Offset(e.x - 0.62f * g.u, e.y - 0.12f * g.u), Size(1.24f * g.u, 0.24f * g.u))
}

/** A roulette wheel: pockets on three rings, gold chips in the lit ones, the ball at the start. */
private fun DrawScope.jackpot(g: SigilGeo, ink: SigilInks, t: Float) {
    drawCircle(ink.soft, 9.3f * g.u, g.center, style = Stroke(0.1f * g.u))
    for (rv in floatArrayOf(6.2f, 7.8f)) drawCircle(ink.faint, rv * g.u, g.center, style = Stroke(0.05f * g.u))
    bullseye(g, ink.main)
    drawCircle(ink.alt, 0.6f * g.u, g.center)
    for (i in 0 until g.n) {
        val c = g.at(i)
        if (g.on(i)) {
            val glint = ((i + (t * 6).toInt()) % 10) == 0
            drawCircle(if (glint) ink.alt else ink.main, 0.55f * g.u, c)
            drawCircle(if (glint) ink.main else ink.alt, 0.36f * g.u, c, style = Stroke(0.08f * g.u))
        } else drawCircle(ink.soft, 0.5f * g.u, c, style = Stroke(0.05f * g.u))
    }
    val e = emblemAt(g)
    drawCircle(ink.main, 0.5f * g.u, e)
    drawCircle(ink.alt, 0.18f * g.u, Offset(e.x - 0.15f * g.u, e.y - 0.15f * g.u))
}

/** A spiral thread of messages from the middle out: bubbles where cells are lit, a wave of colour runs along it. */
private fun DrawScope.chat(g: SigilGeo, ink: SigilInks, t: Float) {
    // the thread, in spiral order (inside out: by radius)
    val order = (0 until g.n).sortedBy { g.r(it) }
    val thread = Path()
    for ((k, i) in order.withIndex()) { val c = g.at(i); if (k == 0) thread.moveTo(c.x, c.y) else thread.lineTo(c.x, c.y) }
    drawPath(thread, ink.faint, style = Stroke(0.06f * g.u))
    bullseye(g, ink.main)
    for ((k, i) in order.withIndex()) {
        val c = g.at(i)
        if (g.on(i)) {
            val w = 0.5f + 0.5f * sin(k * 0.25f - t * 3f)
            val col = lerp(ink.main, ink.alt, w)
            drawCircle(col, 0.48f * g.u, c)
            // the bubble's tail
            val tail = Path().apply {
                moveTo(c.x + 0.15f * g.u, c.y + 0.38f * g.u); lineTo(c.x + 0.5f * g.u, c.y + 0.55f * g.u); lineTo(c.x + 0.36f * g.u, c.y + 0.2f * g.u); close()
            }
            drawPath(tail, col)
        } else drawCircle(ink.soft, (0.1f + 0.04f * sin(t * 6f + k)) * g.u, c)
    }
    val e = emblemAt(g)
    val plane = Path().apply {
        moveTo(e.x - 0.55f * g.u, e.y - 0.5f * g.u); lineTo(e.x + 0.65f * g.u, e.y); lineTo(e.x - 0.55f * g.u, e.y + 0.5f * g.u); lineTo(e.x - 0.3f * g.u, e.y); close()
    }
    drawPath(plane, ink.main)
}

/** A board of bevelled tiles, raised where a cell is lit, an outline where not; a defrag light runs over them. */
private fun DrawScope.desktop(g: SigilGeo, ink: SigilInks, t: Float) {
    bullseye(g, ink.main)
    val s = 0.92f * g.u
    val e = 0.15f * g.u
    val head = ((t * 9f).toInt()) % g.n
    for (i in 0 until g.n) {
        val c = g.at(i)
        val tl = Offset(c.x - s / 2, c.y - s / 2)
        if (g.on(i)) {
            drawRect(if (i == head) ink.alt else ink.main, tl, Size(s, s))
            drawRect(ink.alt, tl, Size(s, e))
            drawRect(ink.alt, tl, Size(e, s))
        } else {
            drawRect(ink.soft, tl, Size(s, s), style = Stroke(0.05f * g.u))
        }
    }
    val p = emblemAt(g)
    val arrow = Path().apply {
        moveTo(p.x - 0.4f * g.u, p.y - 0.62f * g.u); lineTo(p.x - 0.4f * g.u, p.y + 0.5f * g.u); lineTo(p.x - 0.08f * g.u, p.y + 0.2f * g.u)
        lineTo(p.x + 0.16f * g.u, p.y + 0.66f * g.u); lineTo(p.x + 0.34f * g.u, p.y + 0.58f * g.u); lineTo(p.x + 0.1f * g.u, p.y + 0.12f * g.u)
        lineTo(p.x + 0.48f * g.u, p.y + 0.12f * g.u); close()
    }
    drawPath(arrow, ink.main)
}

/**
 * A patch of night sky: twinkling four-point stars where cells are lit, faint dust where not, thin constellation
 * lines between near neighbours, a ringed planet in the middle, the moon at the start.
 */
private fun DrawScope.oracle(g: SigilGeo, ink: SigilInks, plate: Color, t: Float) {
    // constellation lines (soft): each lit star to its nearest lit neighbour, if close
    for (i in 0 until g.n) if (g.on(i)) {
        var best = -1; var bd = 99f
        for (j in 0 until g.n) if (j != i && g.on(j)) {
            val dx = g.x(i) - g.x(j); val dy = g.y(i) - g.y(j)
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = j }
        }
        if (best >= 0 && bd < 2.2f * 2.2f) drawLine(ink.soft, g.at(i), g.at(best), 0.05f * g.u)
    }
    bullseye(g, ink.main)
    drawCircle(ink.alt, 0.5f * g.u, Offset(g.cx + 0.45f * g.u, g.cy - 0.4f * g.u))
    for (i in 0 until g.n) {
        val c = g.at(i)
        if (g.on(i)) {
            val tw = 0.5f + 0.5f * sin(t * 2.3f + i * 1.7f)
            val col = lerp(ink.main, ink.alt, tw * 0.6f)
            drawCircle(col, 0.37f * g.u, c)
            val ray = (0.5f + 0.12f * tw) * g.u
            val w = 0.13f * g.u
            drawPath(Path().apply { moveTo(c.x, c.y - ray); lineTo(c.x + w, c.y); lineTo(c.x, c.y + ray); lineTo(c.x - w, c.y); close() }, col)
            drawPath(Path().apply { moveTo(c.x - ray, c.y); lineTo(c.x, c.y + w); lineTo(c.x + ray, c.y); lineTo(c.x, c.y - w); close() }, col)
        } else {
            drawCircle(ink.soft, (0.06f + 0.04f * (0.5f + 0.5f * sin(t * 1.7f + i))) * g.u, c)
        }
    }
    // the moon
    val e = emblemAt(g)
    drawCircle(ink.main, 0.6f * g.u, e)
    // the bite keeps clear of the middle (that is where the reader looks for the emblem)
    drawCircle(plate, 0.42f * g.u, Offset(e.x + 0.5f * g.u, e.y - 0.15f * g.u))
}

// ---------------------------------------------------------------------------------------------------- corners

/** The look's touches in the plate's corners (never over the code). */
private fun DrawScope.corners(style: Style, g: SigilGeo, ink: SigilInks, t: Float) {
    val k = g.side
    when (style) {
        Style.TERMINAL -> {
            // bytes scrolling in two corners
            for (i in 0 until 24) {
                val col = i % 4; val row = (i / 4 + (t * 2).toInt()) % 6
                for ((ox, oy) in listOf(0f to 0f, k - 4.6f * g.u to k - 3.6f * g.u)) {
                    val o = Offset(ox + (0.5f + col * 1.0f) * g.u, oy + (0.5f + row * 0.5f) * g.u)
                    if (g.clear(o)) drawRect(ink.soft, o, Size(0.6f * g.u, 0.22f * g.u))
                }
            }
        }
        Style.PAPER -> {
            // crop marks
            val l = 1.2f * g.u; val m = 0.35f * g.u
            for ((x, y) in listOf(m to m, k - m to m, m to k - m, k - m to k - m)) {
                val sx = if (x < g.cx) 1f else -1f; val sy = if (y < g.cy) 1f else -1f
                drawLine(ink.main, Offset(x, y + sy * 0.3f * g.u), Offset(x + sx * l, y + sy * 0.3f * g.u), 0.06f * g.u)
                drawLine(ink.main, Offset(x + sx * 0.3f * g.u, y), Offset(x + sx * 0.3f * g.u, y + sy * l), 0.06f * g.u)
            }
        }
        Style.NEON -> {
            // a perspective grid in the lower corners, sliding towards you
            val shift = (t * 0.8f) % 1f
            for (i in 0 until 5) {
                val y = k - (i + shift) * 0.7f * g.u
                for (x0 in listOf(0f, k - 4f * g.u)) {
                    val a = Offset(x0, y); val b = Offset(x0 + 4f * g.u, y)
                    if (g.clear(a) && g.clear(b)) drawLine(ink.soft, a, b, 0.05f * g.u)
                }
            }
        }
        Style.CLEAN, Style.COMIC -> {
            for (i in 0 until 16) {
                val cx = if (i % 2 == 0) k * (0.04f + 0.011f * i) else k * (0.96f - 0.011f * i)
                val cy = if (i % 4 < 2) k * (0.05f + 0.009f * i) else k * (0.95f - 0.009f * i)
                val wob = if (style == Style.CLEAN) sin(t * 2 + i) * 0.15f * g.u else 0f
                val o = Offset(cx + wob, cy)
                if (!g.clear(o, 0.3f * g.u)) continue
                drawCircle(if (i % 3 == 0) ink.alt.copy(alpha = 0.55f) else ink.soft, (0.18f + 0.04f * (i % 3)) * g.u, o)
            }
        }
        Style.ABYSS -> {
            // bubbles rising in the corners
            for (i in 0 until 10) {
                val y = k - ((t * 0.12f + i * 0.1f) % 1f) * k
                val x = if (i % 2 == 0) (0.3f + 0.2f * (i % 3)) * g.u else k - (0.3f + 0.2f * (i % 3)) * g.u
                val o = Offset(x, y)
                if (g.clear(o, 0.2f * g.u)) drawCircle(ink.soft, (0.1f + 0.05f * (i % 3)) * g.u, o, style = Stroke(0.04f * g.u))
            }
        }
        Style.METRO -> {
            // the lines' key in a corner
            for (i in 0 until 3) drawLine(if (i == 1) ink.alt else ink.main, Offset(0.5f * g.u, k - (0.6f + i * 0.45f) * g.u),
                Offset(1.8f * g.u, k - (0.6f + i * 0.45f) * g.u), 0.2f * g.u, StrokeCap.Round)
        }
        Style.JACKPOT -> {
            // chasing bulbs in the corners
            var idx = 0
            for ((x0, y0) in listOf(0f to 0f, k to 0f, 0f to k, k to k)) for (j in 0 until 5) {
                val sx = if (x0 == 0f) 1f else -1f; val sy = if (y0 == 0f) 1f else -1f
                val o = if (j < 3) Offset(x0 + sx * (0.45f + j * 0.75f) * g.u, y0 + sy * 0.45f * g.u)
                else Offset(x0 + sx * 0.45f * g.u, y0 + sy * (0.45f + (j - 2) * 0.75f) * g.u)
                val lit = (idx + (t * 8).toInt()) % 3 == 0
                idx++
                if (g.clear(o, 0.2f * g.u)) drawCircle(if (lit) ink.alt else ink.soft, 0.2f * g.u, o)
            }
        }
        Style.CHAT -> {
            for (i in 0 until 3) {
                val lift = (sin(t * 5f - i * 0.8f) * 0.5f + 0.5f) * 0.25f * g.u
                drawCircle(ink.soft, 0.22f * g.u, Offset(k - (1.9f - i * 0.6f) * g.u, k - 0.6f * g.u - lift))
            }
        }
        Style.DESKTOP -> {
            val inset = 0.3f * g.u
            drawRect(ink.soft, Offset(inset, inset), Size(k - 2 * inset, k - 2 * inset),
                style = Stroke(0.08f * g.u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.3f * g.u, 0.3f * g.u), t * 0.6f * g.u)))
        }
        Style.ORACLE -> {
            for (i in 0 until 18) {
                val x = ((i * 37) % 100) / 100f; val y = ((i * 61 + 13) % 100) / 100f
                val o = Offset(k * (if (x < 0.5f) x * 0.22f else 1 - (1 - x) * 0.22f), k * (if (y < 0.5f) y * 0.22f else 1 - (1 - y) * 0.22f))
                if (!g.clear(o, 0.1f * g.u)) continue
                val tw = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 2 + i * 1.7f))
                drawCircle(ink.alt.copy(alpha = 0.5f * tw), 0.09f * g.u, o)
            }
            // now and then a shooting star across a corner
            val sh = (t / 6f) % 1f
            if (sh < 0.25f) {
                val f = sh / 0.25f
                val a = Offset(k * (0.02f + 0.16f * f), k * (0.12f - 0.08f * f))
                if (g.clear(a)) drawLine(ink.alt.copy(alpha = 0.6f * (1 - f)), a, Offset(a.x - 0.9f * g.u, a.y + 0.45f * g.u), 0.06f * g.u, StrokeCap.Round)
            }
        }
        Style.PIXEL -> {
            if ((t * 1.5f).toInt() % 2 == 0) {
                val q = 0.35f * g.u; val l = 1.6f * g.u; val e = 0.5f * g.u
                for ((x, y) in listOf(e to e, k - e to e, e to k - e, k - e to k - e)) {
                    val sx = if (x < g.cx) 1 else -1; val sy = if (y < g.cy) 1 else -1
                    drawRect(ink.soft, Offset(min(x, x + sx * l), min(y, y + sy * q)), Size(l, q))
                    drawRect(ink.soft, Offset(min(x, x + sx * q), min(y, y + sy * l)), Size(q, l))
                }
            }
        }
    }
}
