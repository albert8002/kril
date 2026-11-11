package app.coredump.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * ADHD-fy, one flavour per look (settings → appearance, or the button on every look's menu).
 *
 * Every decoration follows the element's real outline (slanted comic slabs, stepped pixel boxes, chamfered tarot
 * cards, pills, bevels …), and everything moves in step, so rows stay lined up:
 *  TERMINAL  rainbow outline with RGB glitches; rainbow code rain; bit bursts under taps
 *  GAZETTE   highlighter marks and ink stamps; paper confetti and ink splats; corner tape
 *  ARCADE    neon tube glow that changes colour; laser sweeps and a pulsing grid
 *  QUIZ SHOW colour halo and bouncing confetti; balloons rising
 *  QUEST     8-bit blocks chasing round each box; twinkling pixels and a rainbow snake
 *  COMIC     coloured offset slab and a burst; action bursts popping, colour halftone
 *  ABYSS     bioluminescent edges and bubbles; jellyfish drifting up
 *  METRO     line-colour bars and a little train on each panel; metro lines drawing across
 *  JACKPOT   marquee bulbs chasing round every box; coins falling, light rays
 *  CHAT      gradient outlines and hearts floating up; emoji-like shapes rising
 *  DESKTOP   marching-ants selection; windows flying out like a screensaver
 *  ORACLE    stars orbiting each card along its chamfered frame, both rules lit; shooting stars, a turning zodiac ring
 *
 * Cost: outlines, paths and bulb spots are made once per size (drawWithCache), and each decorated element keeps its
 * content in a layer of its own, so the ticking decoration never re-records what it decorates.
 */

/** Theme colours read once per composition for drawing. */
private class HyInk(val amber: Color, val hot: Color, val line: Color, val bg: Color, val panel: Color) {
    val light: Boolean = bg.luminance() > 0.5f
}

@Composable
private fun hyInk() = HyInk(C.amber, C.hot, C.line, C.bg, C.panel)

/** Transit line colours (METRO). */
private val lines = listOf(Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF8E24AA), Color(0xFFFB8C00))

/** Ink colours (GAZETTE). */
private val inks = listOf(Color(0xFFD32F2F), Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFF6A1B9A), Color(0xFFEF6C00))

/** The tarot gold (ORACLE). */
private val GOLD = Color(0xFFE9C46A)

/**
 * Element outline exactly as the look draws it ([drawBox]) on a [w] × [h] element, [inset] px in: slanted (COMIC),
 * chamfered (ORACLE: the same corner cut at every inset, like the box's two rules), square, pill or rounded.
 */
private fun outline(style: Style, w: Float, h: Float, dp: Float, pill: Boolean, r: Float, inset: Float = 0f): Path {
    val p = Path()
    val i = inset
    when (style) {
        Style.COMIC -> {
            val k = min(h * 0.22f, 12 * dp)
            p.moveTo(k + i, i); p.lineTo(w - i, i); p.lineTo(w - k - i, h - i); p.lineTo(i, h - i); p.close()
        }
        Style.ORACLE -> {
            val k = min(h * 0.22f, 9 * dp)
            p.moveTo(k + i, i); p.lineTo(w - k - i, i); p.lineTo(w - i, k + i); p.lineTo(w - i, h - k - i)
            p.lineTo(w - k - i, h - i); p.lineTo(k + i, h - i); p.lineTo(i, h - k - i); p.lineTo(i, k + i); p.close()
        }
        Style.TERMINAL, Style.DESKTOP, Style.PAPER, Style.PIXEL -> p.addRect(Rect(i, i, w - i, h - i))
        else -> {
            val rr = max(0f, (if (pill) h / 2f else r) - i)
            p.addRoundRect(RoundRect(i, i, w - i, h - i, rr, rr))
        }
    }
    return p
}

/** Points every [gap] px or so round [path] (closed), as x, y pairs: where marquee bulbs sit. */
private fun bulbSpots(path: Path, gap: Float): FloatArray {
    val pm = PathMeasure()
    pm.setPath(path, true)
    val len = pm.length
    if (len <= 0f) return FloatArray(0)
    val n = max(4, (len / gap).toInt())
    val a = FloatArray(n * 2)
    for (i in 0 until n) {
        val p = pm.getPosition(i * len / n)
        a[2 * i] = p.x
        a[2 * i + 1] = p.y
    }
    return a
}

/**
 * What one decorated element draws with, made once per size: its outline where the box draws its rule (ORACLE: both
 * rules), the bulb spots (JACKPOT), the measure to walk the outline (ORACLE stars), the highlighter swipe (GAZETTE).
 */
private class FrameKit(style: Style, val w: Float, val h: Float, dp: Float, val r: Float, pill: Boolean, val stroke: Float) {
    /** The outline on the box's (outer) rule: the stroke is centred [stroke] / 2 in. */
    val rule: Path? = when (style) {
        Style.TERMINAL, Style.NEON, Style.ABYSS, Style.ORACLE -> outline(style, w, h, dp, pill, r, stroke / 2f)
        Style.CHAT -> outline(style, w, h, dp, pill, r, dp)
        Style.DESKTOP, Style.JACKPOT -> outline(style, w, h, dp, pill, r, 2 * dp)
        Style.COMIC, Style.METRO -> outline(style, w, h, dp, pill, r)
        else -> null
    }
    /** ORACLE: the card's fine inner rule (drawn by the box on cards taller than 26 dp). */
    val inner: Path? = if (style == Style.ORACLE && h > 26 * dp) outline(style, w, h, dp, pill, r, stroke + 3 * dp) else null
    val measure: PathMeasure? = if (style == Style.ORACLE && rule != null) PathMeasure().apply { setPath(rule, true) } else null
    val ruleLength: Float = measure?.length ?: 0f
    val bulbs: FloatArray? = if (style == Style.JACKPOT && rule != null) bulbSpots(rule, 14 * dp) else null
    val swipe: Path? = if (style == Style.PAPER && h <= 90 * dp) Path().apply {
        moveTo(-4 * dp, h * 0.55f); lineTo(w + 6 * dp, h * 0.5f)
        lineTo(w + 2 * dp, h * 0.98f); lineTo(-6 * dp, h * 1.02f); close()
    } else null
}

/** Everything bobs together (same phase for all elements), so neighbours stay lined up. */
private fun GraphicsLayerScope.hyMotion(style: Style, t: Float, bob: Float) {
    when (style) {
        Style.CLEAN, Style.CHAT -> { val s = 1f + 0.012f * bob * sin(t * 3.2f); scaleX = s; scaleY = s }
        Style.COMIC -> { rotationZ = 0.8f * bob * sin(t * 2.4f) }
        Style.ABYSS, Style.ORACLE -> { translationY = 1.6.dp.toPx() * bob * sin(t * 1.8f) }
        Style.PIXEL -> { translationY = if (((t * 2f).toInt() % 2) == 0) 0f else -2.dp.toPx() * bob }
        else -> Unit
    }
}

/**
 * ADHD-fy decoration of a button, chip or panel box, in the look's own flavour. [corner] / [pill]: the element's
 * shape, [stroke]: the width of the box's rule (the decoration is drawn on it), so it sits exactly on the box.
 * No-op when ADHD-fy is off.
 */
@Composable
fun Modifier.hyperFrame(seed: Int = 0, corner: Dp = Th.style.corner, pill: Boolean = false, bob: Float = 1f, stroke: Dp = 1.dp): Modifier {
    val h = LocalHyper.current
    if (!h.on) return this
    val style = Th.style
    val ink = hyInk()
    val s = (seed and 0xFFFF) / 65535f
    return this
        .graphicsLayer { hyMotion(style, h.t, bob) }
        .drawWithCache {
            val kit = FrameKit(style, size.width, size.height, 1.dp.toPx(), corner.toPx(), pill, stroke.toPx())
            onDrawWithContent {
                val t = h.t
                decoBehind(style, t, s, pill, ink, kit)
                drawContent()
                decoFront(style, t, s, ink, kit)
            }
        }
        // the element in a layer of its own: the decoration redraws every tick, the content is not re-recorded
        .graphicsLayer { }
}

private fun DrawScope.decoBehind(style: Style, t: Float, s: Float, pill: Boolean, ink: HyInk, kit: FrameKit) {
    val w = size.width
    val h = size.height
    when (style) {
        Style.PAPER -> {
            val c = inks[((t * 0.5f + s * 5f).toInt()) % inks.size]
            val swipe = kit.swipe
            if (swipe == null) {
                // a marker stroke down the margin of a big block
                drawRect(c.copy(alpha = 0.32f), Offset(-8.dp.toPx(), 0f), Size(5.dp.toPx(), h))
            } else {
                // a highlighter swipe under a button
                drawPath(swipe, c.copy(alpha = 0.28f))
            }
        }
        Style.CLEAN -> {
            val g = 4.dp.toPx()
            val rr = (if (pill) h / 2f else kit.r) + g
            drawRoundRect(Brush.linearGradient(rainbow(t * 60f + s * 120f, 5, 0.6f), Offset(-g, -g), Offset(w + g, h + g)),
                Offset(-g, -g), Size(w + 2 * g, h + 2 * g), CornerRadius(rr), alpha = 0.32f)
        }
        Style.COMIC -> {
            val slab = kit.rule ?: return
            val o = 6.dp.toPx()
            translate(o, o) { drawPath(slab, hue(t * 70f + s * 200f, 0.85f, 1f)) }
        }
        else -> Unit
    }
}

private fun DrawScope.decoFront(style: Style, t: Float, s: Float, ink: HyInk, kit: FrameKit) {
    val w = size.width
    val h = size.height
    val px = 1.dp.toPx()
    when (style) {
        Style.TERMINAL -> {
            val path = kit.rule ?: return
            drawPath(path, Brush.sweepGradient(rainbow(t * 140f + s * 90f), Offset(w / 2f, h / 2f)), style = Stroke(1.5f * px))
            // an RGB glitch now and then
            if (((t * 1.3f + s * 3f) % 3f) < 0.14f) {
                translate(2 * px, 0f) { drawPath(path, Color.Red.copy(alpha = 0.6f), style = Stroke(px)) }
                translate(-2 * px, 0f) { drawPath(path, Color.Cyan.copy(alpha = 0.6f), style = Stroke(px)) }
            }
        }
        Style.PAPER -> {
            // an ink stamp in the corner
            val c = inks[((t * 0.5f + s * 5f).toInt() + 2) % inks.size]
            spark(Offset(w - 2 * px, 2 * px), 7.dp.toPx(), c.copy(alpha = 0.85f), t * 40f + s * 90f)
        }
        Style.NEON -> {
            val path = kit.rule ?: return
            val flick = if (((t * 9f).toInt() + (s * 50).toInt()) % 17 == 0) 0.35f else 1f
            val c = hue(t * 80f + s * 60f, 0.9f, 1f)
            drawPath(path, c.copy(alpha = 0.14f * flick), style = Stroke(9 * px))
            drawPath(path, c.copy(alpha = 0.35f * flick), style = Stroke(4.5f * px))
            drawPath(path, c.copy(alpha = 0.95f * flick), style = Stroke(1.6f * px))
        }
        Style.CLEAN -> {
            for (k in 0 until 4) {
                val cx = if (k % 2 == 0) 0f else w
                val cy = if (k < 2) 0f else h
                val b = abs(sin(t * 4f + k * 1.3f + s * 6f)) * 5.dp.toPx()
                drawCircle(hue(k * 90f + t * 90f, 0.7f, 1f), 3.dp.toPx(), Offset(cx + (if (k % 2 == 0) -b else b), cy + (if (k < 2) -b else b)))
            }
        }
        Style.PIXEL -> {
            // 8-bit blocks chasing round the stepped edge (8 frames a second)
            val b = max(3f, px * 3f)
            val tick = (t * 8f).toInt()
            val cell = Size(b, b)
            var i = 0
            var x = b
            while (x < w - b) {
                drawRect(hue((i + tick) * 30f, 0.9f, 1f), Offset(x, 0f), cell)
                drawRect(hue((i + tick) * 30f + 180f, 0.9f, 1f), Offset(x, h - b), cell)
                x += b * 2; i++
            }
            var y = b
            while (y < h - b) {
                drawRect(hue((i + tick) * 30f + 90f, 0.9f, 1f), Offset(0f, y), cell)
                drawRect(hue((i + tick) * 30f + 270f, 0.9f, 1f), Offset(w - b, y), cell)
                y += b * 2; i++
            }
        }
        Style.COMIC -> {
            val c = Offset(w - 4 * px, 2 * px)
            val k = 1f + 0.18f * abs(sin(t * 5f + s * 4f))
            starburst(c, 9.dp.toPx() * k, hue(t * 70f + s * 200f + 120f, 0.9f, 1f))
        }
        Style.ABYSS -> {
            val path = kit.rule ?: return
            val a = 0.45f + 0.35f * sin(t * 2.2f)
            drawPath(path, Color(0xFF3CF2FF).copy(alpha = a * 0.35f), style = Stroke(6 * px))
            drawPath(path, lerpC(Color(0xFF3CF2FF), Color(0xFFFF4FD8), (sin(t * 0.7f + s * 5f) + 1f) / 2f).copy(alpha = a), style = Stroke(1.5f * px))
            val ring = Stroke(px)
            for (k in 0 until 3) {
                val p = ((t * 0.6f + k * 0.33f + s) % 1f)
                drawCircle(Color.White.copy(alpha = 0.6f * (1f - p)), (2f + k).dp.toPx(), Offset(w * (0.2f + 0.3f * k), -p * 22.dp.toPx()), style = ring)
            }
        }
        Style.METRO -> {
            // the line-colour bar, kept inside the box's rounded corners
            val bar = lines[((t * 0.7f + s * 6f).toInt()) % lines.size]
            val shape = kit.rule
            if (shape != null) clipPath(shape) { drawRect(bar, Offset.Zero, Size(4 * px, h)) } else drawRect(bar, Offset.Zero, Size(4 * px, h))
            val p = (t * 0.25f + s) % 1f
            val tx = 6 * px + (w - 12 * px) * p
            drawCircle(Color.White, 3.5f * px, Offset(tx, h - 3 * px))
            drawCircle(lines[((t * 0.7f + s * 6f).toInt() + 3) % lines.size], 3.5f * px, Offset(tx, h - 3 * px), style = Stroke(1.5f * px))
        }
        Style.JACKPOT -> kit.bulbs?.let { bulbs(it, t, 2.4f * px) }
        Style.CHAT -> {
            val path = kit.rule ?: return
            drawPath(path, Brush.linearGradient(listOf(hue(t * 50f + 320f, 0.75f, 1f), hue(t * 50f + 30f, 0.8f, 1f), hue(t * 50f + 270f, 0.7f, 1f)),
                Offset.Zero, Offset(w, h)), style = Stroke(2 * px))
            val p = ((t + s * 7f) % 2.6f) / 1.3f
            if (p < 1f) heartShape(Offset(w - 10.dp.toPx(), -p * 26.dp.toPx()), 5.dp.toPx() * (0.7f + 0.3f * p), hue(340f + p * 30f, 0.75f, 1f, 1f - p))
        }
        Style.DESKTOP -> {
            val path = kit.rule ?: return
            drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(px))
            drawPath(path, hue(t * 120f, 0.9f, 1f), style = Stroke(px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * px, 3 * px), -t * 24 * px)))
        }
        Style.ORACLE -> {
            // gold light running round the card's outer rule, the fine inner rule shimmering, two stars orbiting
            val path = kit.rule ?: return
            val sw = kit.stroke
            drawPath(path, Brush.sweepGradient(listOf(GOLD, hue(t * 60f, 0.6f, 1f), GOLD), Offset(w / 2f, h / 2f)), style = Stroke(sw))
            kit.inner?.let { drawPath(it, hue(t * 60f + 150f, 0.5f, 1f, 0.45f + 0.2f * sin(t * 2f + s * 6f)), style = Stroke(sw * 0.6f)) }
            val pm = kit.measure
            val len = kit.ruleLength
            if (pm != null && len > 0f) for (k in 0 until 2) {
                val d = ((t * 0.12f + k * 0.5f + s) % 1f) * len
                spark(pm.getPosition(d), 5.dp.toPx(), hue(t * 90f + k * 180f, 0.5f, 1f), t * 120f)
            }
        }
    }
}

/** Marquee bulbs at [spots] (x, y pairs), chasing. */
private fun DrawScope.bulbs(spots: FloatArray, t: Float, r: Float) {
    val n = spots.size / 2
    val tick = (t * 9f).toInt()
    val off = Color(0xFFFFE08A).copy(alpha = 0.35f)
    for (i in 0 until n) {
        val on = (i + tick) % 3 == 0
        drawCircle(if (on) hue(i * 25f + t * 100f, 0.8f, 1f) else off, if (on) r * 1.25f else r, Offset(spots[2 * i], spots[2 * i + 1]))
    }
}

/** ADHD-fy text: the look's way of colouring titles and numbers. */
@Composable
fun Modifier.hyperText(seed: Int = 0): Modifier {
    val h = LocalHyper.current
    if (!h.on) return this
    val style = Th.style
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val t = h.t
            val brush: Brush = when (style) {
                Style.PAPER -> Brush.linearGradient(listOf(inks[((t * 0.8f).toInt() + seed) .mod(inks.size)], inks[((t * 0.8f).toInt() + seed) .mod(inks.size)]))
                Style.PIXEL -> { val c = hue(((t * 8f).toInt() % 8) * 45f, 0.9f, 1f); Brush.linearGradient(listOf(c, c)) }
                Style.NEON -> { val c = hue(t * 80f + seed * 13f, 0.9f, 1f); Brush.linearGradient(listOf(c, c)) }
                Style.COMIC -> Brush.linearGradient(listOf(hue(t * 60f, 0.9f, 1f), hue(t * 60f + 150f, 0.9f, 1f)), Offset.Zero, Offset(size.width, 0f))
                Style.METRO -> { val c = lines[((t * 0.7f).toInt() + seed).mod(lines.size)]; Brush.linearGradient(listOf(c, c)) }
                else -> Brush.linearGradient(rainbow(t * 110f + seed * 31f, 5), Offset.Zero, Offset(size.width.coerceAtLeast(1f), size.height * 0.4f))
            }
            drawRect(brush, alpha = 0.85f, blendMode = BlendMode.SrcAtop)
        }
}

// ================================================================== behind the content

/** What the background party reuses every frame (made once per size): metro lines, halftone rows, a scratch path. */
private class BackKit(look: Style, w: Float, hh: Float, dp: Float) {
    val metro: List<Path>? = if (look != Style.METRO) null else List(4) { k ->
        val y0 = hh * (0.18f + 0.2f * k)
        Path().apply {
            moveTo(-20 * dp, y0)
            lineTo(w * 0.3f, y0)
            lineTo(w * 0.45f, y0 + (if (k % 2 == 0) 60f else -60f) * dp)
            lineTo(w + 20 * dp, y0 + (if (k % 2 == 0) 60f else -60f) * dp)
        }
    }
    /** COMIC: the halftone, row by row (x, y pairs; one draw call a row, each row its own colour). */
    val dotRows: List<FloatArray>
    val dotY: FloatArray
    init {
        val rows = ArrayList<FloatArray>()
        val ys = ArrayList<Float>()
        if (look == Style.COMIC) {
            val dot = 18 * dp
            var yy = 0f
            var row = 0
            while (yy < hh) {
                val x0 = if (row % 2 == 0) 0f else dot / 2f
                var n = 0
                var xx = x0
                while (xx < w) { n++; xx += dot }
                val a = FloatArray(n * 2)
                xx = x0
                var i = 0
                while (xx < w) { a[i++] = xx; a[i++] = yy; xx += dot }
                rows += a
                ys += yy
                yy += dot; row++
            }
        }
        dotRows = rows
        dotY = FloatArray(ys.size) { ys[it] }
    }
    val dotInk: Paint = Paint().apply {
        this.style = PaintingStyle.Stroke
        strokeWidth = 6 * dp
        strokeCap = StrokeCap.Round
        isAntiAlias = true
    }
    /** ABYSS tentacles: one path, rebuilt for each. */
    val scratch = Path()
}

/** ADHD-fy, behind the content: the look's own background party. */
@Composable
fun HyperBack(h: Hyper, modifier: Modifier = Modifier) {
    if (!h.on) return
    val style = Th.style
    val ink = hyInk()
    val seeds = remember(style) { val r = Random(style.ordinal * 97 + 5); List(40) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()) } }
    // its own layer: the party redraws every tick without re-recording the backdrop beside it
    Spacer(modifier.fillMaxSize().graphicsLayer { }.drawWithCache {
        val kit = BackKit(style, size.width, size.height, 1.dp.toPx())
        onDrawBehind { party(style, h.t, ink, seeds, kit) }
    })
}

private fun DrawScope.party(style: Style, t: Float, ink: HyInk, seeds: List<FloatArray>, kit: BackKit) {
    val w = size.width
    val hh = size.height
    val dp = 1.dp.toPx()
    val a = if (ink.light) 1.3f else 1f      // light screens need stronger colour
    when (style) {
        Style.TERMINAL -> {
            val drop = Size(3 * dp, 10 * dp)
            for (c in 0 until 16) {
                val sd = seeds[c]
                val x = w * (c + 0.5f) / 16f
                val head = ((t * (0.12f + 0.18f * sd[0]) + sd[1]) % 1.3f) * hh
                for (k in 0 until 9) drawRect(hue(c * 22f + t * 60f, 0.9f, 1f, (0.32f - k * 0.033f) * a), Offset(x, head - k * 16 * dp), drop)
            }
            val g = floor(t / 4f).toInt()
            if ((t % 4f) < 0.16f) {
                val y = Random(g).nextFloat() * hh
                drawRect(Color.Red.copy(alpha = 0.18f), Offset(0f, y), Size(w, 6 * dp))
                drawRect(Color.Cyan.copy(alpha = 0.18f), Offset(0f, y + 7 * dp), Size(w, 4 * dp))
            }
        }
        Style.PAPER -> {
            val bit = Size(10 * dp, 6 * dp)
            for (i in 0 until 22) {
                val sd = seeds[i]
                val x = w * sd[0] + sin(t * 1.4f + i) * 14 * dp
                val y = ((sd[1] + t * (0.04f + 0.05f * sd[2])) % 1.1f) * hh - 0.05f * hh
                rotate(t * (60f + 90f * sd[3]) + i * 30f, Offset(x, y)) {
                    drawRect(inks[i % inks.size].copy(alpha = 0.5f), Offset(x - 5 * dp, y - 3 * dp), bit)
                }
            }
            for (k in 0 until 3) {
                val cyc = floor((t + k * 1.7f) / 5f).toInt()
                val p = ((t + k * 1.7f) % 5f) / 5f
                val r = Random(cyc * 3 + k)
                val c = Offset(w * r.nextFloat(), hh * r.nextFloat())
                val col = inks[(cyc + k).mod(inks.size)].copy(alpha = 0.16f * (1f - p))
                for (b in 0 until 5) drawCircle(col, (14f + 18f * r.nextFloat()) * dp * min(1f, p * 6f), Offset(c.x + (r.nextFloat() - 0.5f) * 40 * dp, c.y + (r.nextFloat() - 0.5f) * 40 * dp))
            }
        }
        Style.NEON -> {
            for (b in 0 until 2) {
                val o = Offset(if (b == 0) 0f else w, hh)
                val ang = (if (b == 0) -60f else -120f) + 35f * sin(t * (0.9f + b * 0.3f))
                val rad = ang * PI.toFloat() / 180f
                val end = Offset(o.x + cos(rad) * hh * 1.4f, o.y + sin(rad) * hh * 1.4f)
                val c = hue(t * 70f + b * 160f, 0.9f, 1f)
                drawLine(c.copy(alpha = 0.12f), o, end, strokeWidth = 14 * dp)
                drawLine(c.copy(alpha = 0.55f), o, end, strokeWidth = 2 * dp)
            }
            val top = hh * 0.78f
            val ph = (t * 0.5f) % 1f
            for (i in 0 until 8) {
                val z = (i + ph) / 8f
                val y = top + (hh - top) * z * z
                drawLine(hue(t * 50f + i * 20f, 0.9f, 1f, 0.35f * z), Offset(0f, y), Offset(w, y), strokeWidth = 1.5f * dp)
            }
        }
        Style.CLEAN -> {
            val balloon = Size(26 * dp, 32 * dp)
            for (i in 0 until 8) {
                val sd = seeds[i]
                val x = w * sd[0] + sin(t * 0.9f + i) * 18 * dp
                val y = hh * 1.1f - ((sd[1] + t * (0.035f + 0.03f * sd[2])) % 1.25f) * hh
                val c = hue(i * 45f + t * 20f, 0.55f, 1f, 0.5f)
                drawOval(c, Offset(x - 13 * dp, y - 16 * dp), balloon)
                drawLine(c, Offset(x, y + 16 * dp), Offset(x + sin(t * 2f + i) * 5 * dp, y + 44 * dp), strokeWidth = dp)
            }
            val bit = Size(6 * dp, 3 * dp)
            for (i in 8 until 30) {
                val sd = seeds[i]
                val y = ((sd[1] + t * 0.07f * (0.5f + sd[2])) % 1f) * hh
                rotate(t * 120f + i * 20f, Offset(w * sd[0], y)) {
                    drawRect(hue(i * 33f, 0.75f, 1f, 0.6f), Offset(w * sd[0] - 3 * dp, y - 1.5f * dp), bit)
                }
            }
        }
        Style.PIXEL -> {
            val g = 4 * dp
            val tick = (t * 6f).toInt()
            val cell = Size(g, g)
            for (i in 0 until 30) {
                val sd = seeds[i]
                if ((tick + i) % 5 == 0) continue
                drawRect(hue(i * 40f + tick * 15f, 0.9f, 1f, 0.55f), Offset((sd[0] * w / g).toInt() * g, (sd[1] * hh / g).toInt() * g), cell)
            }
            // rainbow snake across the screen
            val seg = Size(g * 2, g * 2)
            for (k in 0 until 24) {
                val x = ((tick * g + k * g * 2) % (w + 100 * dp)) - 50 * dp
                val y = hh * 0.62f + (sin((x / w) * 6f + t) * 30 * dp / g).toInt() * g
                drawRect(hue(k * 15f + tick * 20f, 0.9f, 1f, 0.5f), Offset((x / g).toInt() * g, y), seg)
            }
        }
        Style.COMIC -> {
            // colour halftone: each row one colour, drawn as round points in one call
            val canvas = drawContext.canvas
            for (row in kit.dotRows.indices) {
                kit.dotInk.color = hue(kit.dotY[row] / hh * 300f + t * 40f, 0.8f, 1f, 0.12f * a)
                canvas.drawRawPoints(PointMode.Points, kit.dotRows[row], kit.dotInk)
            }
            for (k in 0 until 3) {
                val cyc = floor((t + k) / 3f).toInt()
                val p = ((t + k) % 3f) / 3f
                val r = Random(cyc * 7 + k)
                val c = Offset(w * (0.15f + 0.7f * r.nextFloat()), hh * (0.15f + 0.7f * r.nextFloat()))
                val sc = if (p < 0.15f) p / 0.15f else 1f
                starburst(c, 34 * dp * sc, hue(r.nextFloat() * 360f, 0.9f, 1f, (1f - p) * 0.55f))
            }
        }
        Style.ABYSS -> {
            val tentacle = Stroke(1.2f * dp, cap = StrokeCap.Round)
            val path = kit.scratch
            for (i in 0 until 5) {
                val sd = seeds[i]
                val x = w * sd[0] + sin(t * 0.6f + i) * 20 * dp
                val y = hh * 1.15f - ((sd[1] + t * (0.03f + 0.02f * sd[2])) % 1.3f) * hh
                val c = hue(i * 70f + t * 30f, 0.7f, 1f)
                val pul = 1f + 0.12f * sin(t * 3f + i)
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.35f), Color.Transparent), Offset(x, y), 40 * dp), 40 * dp, Offset(x, y))
                drawArc(c.copy(alpha = 0.6f), 180f, 180f, true, Offset(x - 16 * dp * pul, y - 14 * dp), Size(32 * dp * pul, 28 * dp))
                val legs = c.copy(alpha = 0.45f)
                for (k in 0 until 4) {
                    val tx = x + (-10f + k * 6.6f) * dp
                    path.reset()
                    path.moveTo(tx, y)
                    for (j in 1..5) path.lineTo(tx + sin(t * 3f + j + k) * 3 * dp, y + j * 7 * dp)
                    drawPath(path, legs, style = tentacle)
                }
            }
            for (i in 5 until 40) {
                val sd = seeds[i]
                drawCircle(hue(i * 20f + t * 50f, 0.7f, 1f, 0.35f + 0.3f * sin(t * 2f + i)), (1f + 2f * sd[2]) * dp,
                    Offset(w * sd[0], ((sd[1] - t * 0.01f * (1f + sd[3])) % 1f + 1f) % 1f * hh))
            }
        }
        Style.METRO -> {
            val stop = Color.White.copy(alpha = 0.7f)
            kit.metro?.forEachIndexed { k, path ->
                val y0 = hh * (0.18f + 0.2f * k)
                drawPath(path, lines[k].copy(alpha = 0.3f * a), style = Stroke(7 * dp, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(40 * dp, 14 * dp), -t * 60 * dp * (1 + k % 2))))
                for (st in 0 until 3) drawCircle(stop, 4 * dp, Offset(w * (0.12f + 0.12f * st), y0))
            }
        }
        Style.JACKPOT -> {
            val ray = Color(0xFFFFE08A).copy(alpha = 0.06f)
            rotate(t * 12f, Offset(w / 2f, -hh * 0.1f)) {
                for (i in 0 until 12) {
                    val ang = i * 30f * PI.toFloat() / 180f
                    val end = Offset(w / 2f + cos(ang) * hh * 1.5f, -hh * 0.1f + sin(ang) * hh * 1.5f)
                    drawLine(ray, Offset(w / 2f, -hh * 0.1f), end, strokeWidth = 40 * dp)
                }
            }
            for (i in 0 until 18) {
                val sd = seeds[i]
                val y = ((sd[1] + t * (0.08f + 0.08f * sd[2])) % 1.1f) * hh - 0.05f * hh
                val x = w * sd[0]
                val sx = abs(cos(t * 3f + i))
                val c = if (i % 4 == 0) hue(t * 80f + i * 30f, 0.8f, 1f) else Color(0xFFFFC93C)
                drawOval(c.copy(alpha = 0.7f), Offset(x - 8 * dp * sx, y - 8 * dp), Size(16 * dp * sx + 1f, 16 * dp))
            }
        }
        Style.CHAT -> {
            val eye = 1.4f * dp
            val smile = Stroke(1.4f * dp)
            for (i in 0 until 16) {
                val sd = seeds[i]
                val x = w * sd[0] + sin(t + i) * 12 * dp
                val y = hh * 1.05f - ((sd[1] + t * (0.04f + 0.04f * sd[2])) % 1.15f) * hh
                val c = hue(i * 47f + t * 30f, 0.7f, 1f, 0.55f)
                when (i % 3) {
                    0 -> heartShape(Offset(x, y), 9 * dp, c)
                    1 -> spark(Offset(x, y), 9 * dp, c, t * 60f)
                    else -> {
                        drawCircle(c, 10 * dp, Offset(x, y))
                        drawCircle(ink.bg, eye, Offset(x - 3.5f * dp, y - 2 * dp))
                        drawCircle(ink.bg, eye, Offset(x + 3.5f * dp, y - 2 * dp))
                        drawArc(ink.bg, 20f, 140f, false, Offset(x - 5 * dp, y - 3 * dp), Size(10 * dp, 8 * dp), style = smile)
                    }
                }
            }
        }
        Style.DESKTOP -> {
            val o = Offset(w / 2f, hh / 2f)
            for (i in 0 until 14) {
                val sd = seeds[i]
                val z = ((sd[0] + t * 0.12f * (0.6f + sd[1])) % 1f)
                val ang = sd[2] * 2f * PI.toFloat()
                val d = z * z * max(w, hh) * 0.75f
                val c = Offset(o.x + cos(ang) * d, o.y + sin(ang) * d)
                val sz = (10f + 50f * z * z) * dp
                drawRect(Color.White.copy(alpha = 0.55f * z), Offset(c.x - sz / 2f, c.y - sz * 0.35f), Size(sz, sz * 0.7f))
                drawRect(hue(i * 50f + t * 40f, 0.8f, 0.9f, 0.8f * z), Offset(c.x - sz / 2f, c.y - sz * 0.35f), Size(sz, sz * 0.14f))
            }
        }
        Style.ORACLE -> {
            for (i in 0 until 34) {
                val sd = seeds[i]
                val tw = (sin(t * (1.5f + sd[2] * 2f) + i) + 1f) / 2f
                spark(Offset(w * sd[0], hh * sd[1]), (2f + 4f * tw) * dp, hue(i * 40f + t * 20f, 0.5f, 1f, 0.3f + 0.5f * tw), 0f)
            }
            val cyc = floor(t / 3f).toInt()
            val p = (t % 3f) / 0.8f
            if (p < 1f) {
                val r = Random(cyc)
                val a0 = Offset(w * r.nextFloat(), hh * 0.4f * r.nextFloat())
                val head = Offset(a0.x + p * w * 0.45f, a0.y + p * hh * 0.25f)
                drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White), a0, head), a0, head, strokeWidth = 2 * dp, cap = StrokeCap.Round)
            }
            rotate(t * 6f, Offset(w / 2f, hh / 2f)) {
                drawCircle(GOLD.copy(alpha = 0.15f), w * 0.42f, Offset(w / 2f, hh / 2f), style = Stroke(dp))
                for (k in 0 until 12) {
                    val ang = k * 30f * PI.toFloat() / 180f
                    drawCircle(hue(k * 30f, 0.5f, 1f, 0.35f), 3 * dp, Offset(w / 2f + cos(ang) * w * 0.42f, hh / 2f + sin(ang) * w * 0.42f))
                }
            }
        }
    }
}

// ================================================================== on top

/** ADHD-fy, on top: the look's screen edge, and its own burst wherever a finger lands. */
@Composable
fun HyperFront(h: Hyper, taps: List<Tap>, modifier: Modifier = Modifier) {
    if (!h.on) return
    val style = Th.style
    // its own layer, like [HyperBack]
    Spacer(modifier.fillMaxSize().graphicsLayer { }.drawWithCache {
        val dp = 1.dp.toPx()
        // JACKPOT: bulbs round the screen edge, placed once per size
        val spots = if (style == Style.JACKPOT) bulbSpots(Path().apply { addRect(Rect(5 * dp, 5 * dp, size.width - 5 * dp, size.height - 5 * dp)) }, 26 * dp) else null
        onDrawBehind { front(style, h.t, taps, spots) }
    })
}

private fun DrawScope.front(style: Style, t: Float, taps: List<Tap>, edgeBulbs: FloatArray?) {
    val w = size.width
    val hh = size.height
    val dp = 1.dp.toPx()
    // edge
    when (style) {
        Style.TERMINAL -> drawRect(Brush.sweepGradient(rainbow(t * 90f), Offset(w / 2f, hh / 2f)), Offset(1.5f * dp, 1.5f * dp), Size(w - 3 * dp, hh - 3 * dp),
            style = Stroke(3 * dp), alpha = 0.6f)
        Style.NEON -> {
            val c = hue(t * 60f, 0.9f, 1f)
            drawRect(c.copy(alpha = 0.12f), Offset.Zero, Size(w, hh), style = Stroke(16 * dp))
            drawRect(c.copy(alpha = 0.7f), Offset(dp, dp), Size(w - 2 * dp, hh - 2 * dp), style = Stroke(2 * dp))
        }
        Style.PIXEL -> {
            val b = 6 * dp
            val cell = Size(b, b)
            val tick = (t * 8f).toInt()
            var x = 0f; var i = 0
            while (x < w) { drawRect(hue((i + tick) * 25f, 0.9f, 1f, 0.8f), Offset(x, 0f), cell); drawRect(hue((i + tick) * 25f + 180f, 0.9f, 1f, 0.8f), Offset(x, hh - b), cell); x += b * 2; i++ }
        }
        Style.JACKPOT -> edgeBulbs?.let { bulbs(it, t, 4 * dp) }
        Style.COMIC -> {
            drawRect(hue(t * 50f, 0.9f, 1f), Offset(3 * dp, 3 * dp), Size(w - 6 * dp, hh - 6 * dp), style = Stroke(6 * dp))
            drawRect(Color.Black, Offset(dp, dp), Size(w - 2 * dp, hh - 2 * dp), style = Stroke(2 * dp))
        }
        Style.ABYSS, Style.ORACLE -> {
            val c = if (style == Style.ABYSS) lerpC(Color(0xFF3CF2FF), Color(0xFFFF4FD8), (sin(t) + 1f) / 2f) else hue(t * 40f, 0.5f, 1f)
            val e = 40 * dp
            drawRect(Brush.verticalGradient(listOf(c.copy(alpha = 0.22f), Color.Transparent), 0f, e), size = Size(w, e))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = 0.22f)), hh - e, hh), Offset(0f, hh - e), Size(w, e))
        }
        Style.CLEAN, Style.CHAT -> drawRect(Brush.linearGradient(listOf(hue(t * 50f + 320f, 0.7f, 1f), hue(t * 50f + 40f, 0.8f, 1f), hue(t * 50f + 260f, 0.7f, 1f)),
            Offset.Zero, Offset(w, hh)), Offset(1.5f * dp, 1.5f * dp), Size(w - 3 * dp, hh - 3 * dp), style = Stroke(3 * dp), alpha = 0.7f)
        Style.METRO -> drawRect(lines[((t * 0.8f).toInt()) % lines.size], Offset.Zero, Size(w, 4 * dp))
        Style.DESKTOP -> drawRect(hue(t * 120f, 0.9f, 1f), Offset(2 * dp, 2 * dp), Size(w - 4 * dp, hh - 4 * dp),
            style = Stroke(1.5f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * dp, 4 * dp), -t * 30 * dp)))
        Style.PAPER -> {
            val tape = Size(48 * dp, 14 * dp)
            for (k in 0 until 4) {
                val c = Offset(if (k % 2 == 0) 18 * dp else w - 18 * dp, if (k < 2) 40 * dp else hh - 30 * dp)
                rotate(if (k % 3 == 0) -35f else 35f, c) { drawRect(inks[(k + (t * 0.4f).toInt()) % inks.size].copy(alpha = 0.35f), Offset(c.x - 24 * dp, c.y - 7 * dp), tape) }
            }
        }
    }
    // bursts where fingers land
    for (tap in taps) {
        val p = (t - tap.born) / 0.85f
        if (p < 0f || p >= 1f) continue
        val r = Random(tap.seed)
        val ease = 1f - (1f - p) * (1f - p)
        val fade = 1f - p
        when (style) {
            Style.TERMINAL, Style.PIXEL -> {
                val g = if (style == Style.PIXEL) 5 * dp else 3 * dp
                val cell = Size(g, g)
                for (k in 0 until 12) {
                    val ang = r.nextFloat() * 2f * PI.toFloat()
                    val d = (12f + 50f * r.nextFloat()) * dp * ease
                    val x = tap.at.x + cos(ang) * d
                    val y = tap.at.y + sin(ang) * d
                    drawRect(hue(k * 30f + t * 100f, 0.9f, 1f, fade), if (style == Style.PIXEL) Offset((x / g).toInt() * g, (y / g).toInt() * g) else Offset(x, y), cell)
                }
            }
            Style.PAPER -> {
                for (k in 0 until 6) {
                    val d = 28 * dp * r.nextFloat() * ease
                    val ang = r.nextFloat() * 2f * PI.toFloat()
                    drawCircle(inks[k % inks.size].copy(alpha = 0.55f * fade), (4f + 9f * r.nextFloat()) * dp * (0.4f + ease), Offset(tap.at.x + cos(ang) * d, tap.at.y + sin(ang) * d))
                }
            }
            Style.NEON, Style.METRO -> {
                for (k in 0 until 3) {
                    val q = (p - k * 0.12f).coerceIn(0f, 1f)
                    val c = if (style == Style.METRO) lines[(k + tap.seed.mod(lines.size)) % lines.size] else hue(k * 120f + t * 80f, 0.9f, 1f)
                    drawCircle(c.copy(alpha = (1f - q) * 0.9f), (6f + 46f * q) * dp, tap.at, style = Stroke((3f * (1f - q) + 0.8f) * dp))
                }
            }
            Style.CLEAN -> {
                val bit = Size(8 * dp, 4 * dp)
                for (k in 0 until 14) {
                    val ang = r.nextFloat() * 2f * PI.toFloat()
                    val d = (16f + 54f * r.nextFloat()) * dp * ease
                    val c = Offset(tap.at.x + cos(ang) * d, tap.at.y + sin(ang) * d + 30 * dp * p * p)
                    rotate(p * 400f + k * 30f, c) { drawRect(hue(k * 26f, 0.7f, 1f, fade), Offset(c.x - 4 * dp, c.y - 2 * dp), bit) }
                }
            }
            Style.COMIC -> starburst(tap.at, (10f + 34f * ease) * dp, hue(r.nextFloat() * 360f, 0.9f, 1f, fade))
            Style.ABYSS -> {
                val ring = Stroke(1.2f * dp)
                for (k in 0 until 7) {
                    val x = tap.at.x + (r.nextFloat() - 0.5f) * 50 * dp
                    val y = tap.at.y - (20f + 60f * r.nextFloat()) * dp * ease
                    drawCircle(Color.White.copy(alpha = 0.7f * fade), (3f + 5f * r.nextFloat()) * dp, Offset(x, y), style = ring)
                }
            }
            Style.JACKPOT -> {
                for (k in 0 until 8) {
                    val ang = r.nextFloat() * 2f * PI.toFloat()
                    val d = (14f + 48f * r.nextFloat()) * dp * ease
                    val c = Offset(tap.at.x + cos(ang) * d, tap.at.y + sin(ang) * d + 40 * dp * p * p)
                    val sx = abs(cos(t * 8f + k))
                    drawOval(Color(0xFFFFC93C).copy(alpha = fade), Offset(c.x - 6 * dp * sx, c.y - 6 * dp), Size(12 * dp * sx + 1f, 12 * dp))
                }
            }
            Style.CHAT -> {
                for (k in 0 until 6) {
                    val x = tap.at.x + (r.nextFloat() - 0.5f) * 60 * dp
                    val y = tap.at.y - (10f + 70f * r.nextFloat()) * dp * ease
                    heartShape(Offset(x, y), (5f + 4f * r.nextFloat()) * dp, hue(330f + k * 12f, 0.75f, 1f, fade))
                }
            }
            Style.DESKTOP -> {
                val icon = Size(14 * dp, 10 * dp)
                for (k in 0 until 6) {
                    val ang = k / 6f * 2f * PI.toFloat()
                    val d = 44 * dp * ease
                    val c = Offset(tap.at.x + cos(ang) * d, tap.at.y + sin(ang) * d)
                    drawRect(Color.White.copy(alpha = fade), Offset(c.x - 7 * dp, c.y - 5 * dp), icon)
                    drawRect(hue(k * 60f, 0.8f, 0.9f, fade), Offset(c.x - 7 * dp, c.y - 5 * dp), Size(14 * dp, 3 * dp))
                }
            }
            Style.ORACLE -> {
                for (k in 0 until 8) {
                    val ang = (k / 8f) * 2f * PI.toFloat() + p
                    val d = 40 * dp * ease
                    spark(Offset(tap.at.x + cos(ang) * d, tap.at.y + sin(ang) * d), (4f + 4f * (1f - p)) * dp, hue(40f + k * 40f, 0.5f, 1f, fade), p * 300f)
                }
            }
        }
    }
}

// ================================================================== shapes (each path made once, then moved and scaled)

/** Comic starburst (16 points) of radius 1 round 0,0. */
private val unitBurst: Path by lazy(LazyThreadSafetyMode.NONE) {
    Path().apply {
        for (i in 0 until 32) {
            val rr = if (i % 2 == 0) 1f else 0.55f
            val ang = i * PI.toFloat() / 16f
            if (i == 0) moveTo(cos(ang) * rr, sin(ang) * rr) else lineTo(cos(ang) * rr, sin(ang) * rr)
        }
        close()
    }
}

/** Heart of size 1 round 0,0. */
private val unitHeart: Path by lazy(LazyThreadSafetyMode.NONE) {
    Path().apply {
        moveTo(0f, 0.9f)
        cubicTo(-1.4f, -0.1f, -0.6f, -1.1f, 0f, -0.35f)
        cubicTo(0.6f, -1.1f, 1.4f, -0.1f, 0f, 0.9f)
        close()
    }
}

/** Four-point sparkle star of radius 1 round 0,0 (the same shape as [sparkle]). */
private val unitSpark: Path by lazy(LazyThreadSafetyMode.NONE) {
    Path().apply {
        for (i in 0 until 8) {
            val r = if (i % 2 == 0) 1f else 0.32f
            val ang = i * PI.toFloat() / 4f
            if (i == 0) moveTo(cos(ang) * r, sin(ang) * r) else lineTo(cos(ang) * r, sin(ang) * r)
        }
        close()
    }
}

/** Comic starburst (16 points). */
fun DrawScope.starburst(c: Offset, r: Float, color: Color) {
    if (r <= 0f) return
    val edge = 1.2.dp.toPx() / r
    withTransform({ translate(c.x, c.y); scale(r, r, Offset.Zero) }) {
        drawPath(unitBurst, color)
        drawPath(unitBurst, Color.Black.copy(alpha = color.alpha), style = Stroke(edge))
    }
}

fun DrawScope.heartShape(c: Offset, s: Float, color: Color) {
    if (s <= 0f) return
    withTransform({ translate(c.x, c.y); scale(s, s, Offset.Zero) }) { drawPath(unitHeart, color) }
}

/** [sparkle] without a new path each call: the unit star moved, turned by [deg] and scaled to radius [s]. */
private fun DrawScope.spark(c: Offset, s: Float, color: Color, deg: Float) {
    if (s <= 0f) return
    withTransform({ translate(c.x, c.y); rotate(deg, Offset.Zero); scale(s, s, Offset.Zero) }) { drawPath(unitSpark, color) }
}

private fun lerpC(a: Color, b: Color, f: Float): Color = androidx.compose.ui.graphics.lerp(a, b, f.coerceIn(0f, 1f))
