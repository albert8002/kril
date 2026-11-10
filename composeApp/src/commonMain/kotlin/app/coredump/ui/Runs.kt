package app.coredump.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.core.Tier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The self-test slows runs down so its screenshots catch one in flight (1 = normal speed). */
object RunProbe {
    var slow = 1f
}

/** Time on the arrival screen before the run fades out, and the fade (ms). */
private const val HOLD = 800f
private const val FADE = 320f

/** Everything one frame of a run needs. value: points now (fractional while moving). */
private class Frame(
    val e: Float, val travel: Float, val u: Float, val v: Float, val value: Float,
    val from: Int, val gain: Int, val max: Int, val arrive: Float, val sec: Float,
)

/** Colours and fonts of the active look, read once per composition. */
private class Ink(
    val bg: Color, val panel: Color, val amber: Color, val hot: Color, val line: Color, val dim: Color, val white: Color,
    val tier: Color, val font: FontFamily, val display: FontFamily, val hyper: Hyper,
)

/** Words of each look's run: what is happening, the counter, the marks passed, where you end up. */
private object Words {
    private val layers = listOf("user space", "shell", "libc", "syscalls", "vfs", "drivers", "scheduler", "ring 0", "kernel core")
    private val booms = listOf("POW!", "ZAP!", "WHAM!", "BOOM!", "KRAK!", "ZOOM!", "BAM!", "WHOOSH!", "SPLAT!", "KAPOW!")
    val stations = listOf(
        "Core Square", "Cache Lane", "Byte Bridge", "Stack Park", "Heap Hill", "Kernel Gate", "Loop Street", "Pixel Quay",
        "Root Junction", "Signal Row", "Patch Cross", "Archive Hall", "Index Bay", "Buffer Wharf", "Thread Mill", "Vector Place",
    )
    private val arcana = listOf(
        "The Fool", "The Magician", "The High Priestess", "The Empress", "The Emperor", "The Hierophant", "The Lovers",
        "The Chariot", "Strength", "The Hermit", "Wheel of Fortune", "Justice", "The Hanged Man", "Death", "Temperance",
        "The Devil", "The Tower", "The Star", "The Moon", "The Sun", "Judgement", "The World",
    )

    /** Points between two marks on the track (0 = no marks). */
    fun every(s: Style): Int = when (s) {
        Style.ABYSS -> 25
        Style.JACKPOT, Style.DESKTOP -> 0
        else -> 10
    }

    fun going(s: Style): String = when (s) {
        Style.TERMINAL -> "descending to the kernel"
        Style.PAPER -> "STOP THE PRESSES"
        Style.NEON -> "BOOST"
        Style.CLEAN -> "Climbing the ladder"
        Style.PIXEL -> "QUEST!"
        Style.COMIC -> "ZOOOOM!"
        Style.ABYSS -> "DIVING"
        Style.METRO -> "NEXT STOP"
        Style.JACKPOT -> "SPINNING"
        Style.CHAT -> "typing…"
        Style.DESKTOP -> "Copying points…"
        Style.ORACLE -> "THE CARDS ARE TURNING"
    }

    fun counter(s: Style, v: Float): String {
        val n = v.toInt()
        return when (s) {
            Style.ABYSS -> "${group(n * 2)} m"
            Style.NEON -> n.toString().padStart(6, '0')
            Style.PIXEL -> "XP $n"
            Style.TERMINAL, Style.PAPER, Style.METRO, Style.DESKTOP -> "${group(n)} pts"
            else -> group(n)
        }
    }

    fun gain(s: Style, g: Int): String = when (s) {
        Style.ABYSS -> "+${g * 2} m deeper"
        Style.NEON -> "+$g BONUS"
        Style.PIXEL -> "+$g XP"
        Style.JACKPOT -> "PAYOUT +$g"
        Style.TERMINAL -> "↓ +$g"
        else -> "+$g"
    }

    fun mark(s: Style, k: Int, max: Int): String = when (s) {
        Style.TERMINAL -> "0x" + ((k * 10f / max.coerceAtLeast(1)).coerceIn(0f, 1f) * 0xFFFFFFFFL).toLong().toString(16).uppercase().padStart(8, '0')
        Style.ABYSS -> "${k * 50} m"
        Style.PAPER -> "EDITION No. ${k + 1}"
        Style.NEON -> "${k * 10}"
        Style.CLEAN -> "${k * 10}"
        Style.COMIC -> booms[k % booms.size]
        Style.METRO -> stations[k % stations.size]
        Style.ORACLE -> arcana[k % arcana.size]
        else -> ""
    }

    /** Where the run ends: kernel layer, ocean zone, station, level … */
    fun place(s: Style, v: Float, max: Int): String {
        val n = v.toInt()
        val frac = (v / max.coerceAtLeast(1)).coerceIn(0f, 1f)
        return when (s) {
            Style.TERMINAL -> "reached: " + layers[(frac * (layers.size - 1)).toInt().coerceIn(0, layers.size - 1)]
            Style.ABYSS -> when { n * 2 < 200 -> "SUNLIGHT ZONE"; n * 2 < 1000 -> "TWILIGHT ZONE"; n * 2 < 4000 -> "MIDNIGHT ZONE"; else -> "ABYSSAL ZONE" }
            Style.PAPER -> "${group(n * 1000)} copies sold"
            Style.NEON -> "STAGE ${n / 100 + 1}"
            Style.CLEAN -> "Locked in!"
            Style.PIXEL -> "LV ${n / 100 + 1}"
            Style.COMIC -> "KA-CHING!"
            Style.METRO -> "Arriving: " + stations[(n / 10) % stations.size]
            Style.JACKPOT -> "WINNER"
            Style.CHAT -> "Delivered · Read"
            Style.DESKTOP -> "Copy complete"
            Style.ORACLE -> arcana[(n / 10) % arcana.size]
        }
    }

    fun layer(v: Float, max: Int): String {
        val frac = (v / max.coerceAtLeast(1)).coerceIn(0f, 1f)
        return layers[(frac * (layers.size - 1)).toInt().coerceIn(0, layers.size - 1)]
    }
}

/**
 * The score run: when points are scored, the screen travels — speeds up, counts the points, slows down and stops at
 * the new total — in the look's own world: the terminal descends through memory toward the kernel, the deep sea
 * dives (metres), the gazette runs its presses, the arcade boosts down a neon road, the quiz show climbs its ladder,
 * the quest hero runs for XP, the comic zooms past sound effects, the metro passes stations, the jackpot spins its
 * reels, the chat flies up the thread, the desktop copies files, the oracle rushes through the stars.
 * Tap to skip. [onArrive] fires once when it stops (or is skipped). Shows once per [key].
 */
@Composable
fun ScoreRun(from: Int, gain: Int, max: Int, tier: Tier, key: Any, onArrive: () -> Unit, modifier: Modifier = Modifier) {
    val style = Th.style
    val travel = Travel.durationMs(gain) * RunProbe.slow
    val clock = remember(key) { mutableFloatStateOf(0f) }
    var done by remember(key) { mutableStateOf(false) }
    var skip by remember(key) { mutableStateOf(false) }
    val arrive by rememberUpdatedState(onArrive)
    LaunchedEffect(key) {
        val start = withFrameMillis { it }
        var bonus = 0f
        var landed = false
        while (true) {
            val now = withFrameMillis { it }
            var e = (now - start).toFloat() + bonus
            if (skip) {
                skip = false
                val jump = when { e < travel -> travel - e; e < travel + HOLD -> travel + HOLD - e; else -> 0f }
                bonus += jump; e += jump
            }
            clock.floatValue = e
            if (!landed && e >= travel) { landed = true; arrive() }
            if (e >= travel + HOLD + FADE) break
        }
        done = true
    }
    if (done) return

    val tm = rememberTextMeasurer(cacheSize = 64)
    val ink = Ink(C.bg, C.panel, C.amber, C.hot, C.line, C.dim, C.white, tierColor(tier), LocalMono.current, LocalDisplay.current, LocalHyper.current)
    val seeds = remember(style) { val r = Random(style.ordinal * 31 + 11); List(90) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()) } }
    val tierName = tier.label
    Box(
        modifier.fillMaxSize()
            .pointerInput(key) { detectTapGestures { skip = true } }
            .graphicsLayer {
                val e = clock.floatValue
                alpha = min((e / 160f).coerceIn(0f, 1f), 1f - ((e - travel - HOLD) / FADE).coerceIn(0f, 1f))
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val e = clock.floatValue
            val tt = (e / travel).coerceIn(0f, 1f)
            val u = Travel.pos(tt)
            val f = Frame(e, travel, u, Travel.speed(tt), from + gain * u, from, gain, max.coerceAtLeast(100),
                ((e - travel) / 300f).coerceIn(0f, 1f), e / 1000f)
            drawRect(ink.bg)
            world(style, f, ink, seeds, tm)
            overlay(style, f, ink, tm, tierName)
        }
    }
}

// ================================================================== drawing helpers

private fun st(ink: Ink, size: TextUnit, color: Color, bold: Boolean = true, display: Boolean = false, spacing: TextUnit = 0.sp) =
    TextStyle(color = color, fontSize = size, fontFamily = if (display) ink.display else ink.font,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, letterSpacing = spacing)

/** Text centred (by [ax], [ay]) on (x, y). */
private fun DrawScope.say(tm: TextMeasurer, s: String, x: Float, y: Float, style: TextStyle, alpha: Float = 1f, ax: Float = 0.5f, ay: Float = 0.5f) {
    if (alpha <= 0.01f || s.isEmpty()) return
    val r = tm.measure(s, style)
    drawText(r, topLeft = Offset(x - r.size.width * ax, y - r.size.height * ay), alpha = alpha.coerceIn(0f, 1f))
}

/** Streak colour: the look's colour, or a running rainbow in ADHD-fy. */
private fun tint(ink: Ink, base: Color, i: Int, a: Float): Color =
    if (ink.hyper.on) hue(i * 37f + ink.hyper.t * 160f, 0.8f, 1f, a) else base.copy(alpha = a.coerceIn(0f, 1f))

/** Marks of the track near the current value: (mark index, offset in points from the current value). */
private fun marks(s: Style, f: Frame, span: Float): List<Pair<Int, Float>> {
    val every = Words.every(s)
    if (every <= 0) return emptyList()
    val lo = floor((f.value - span) / every).toInt().coerceAtLeast(0)
    val hi = floor((f.value + span) / every).toInt() + 1
    return (lo..hi).map { it to (it * every - f.value) }
}

// ================================================================== the worlds

private fun DrawScope.world(s: Style, f: Frame, ink: Ink, seeds: List<FloatArray>, tm: TextMeasurer) {
    val w = size.width
    val h = size.height
    val ppp = h * 0.055f                       // px per point of travel
    val scroll = f.value * ppp
    val more = if (ink.hyper.on) 2 else 1
    when (s) {
        Style.TERMINAL -> {
            // memory streaming past: columns of bits rushing up as we go down; address lines at each mark
            val cols = 22
            for (c in 0 until cols) {
                val sd = seeds[c]
                val x = w * (c + 0.5f) / cols
                val gap = (40f + 50f * sd[0]).dp.toPx()
                val len = (4f + 70f * f.v * (0.5f + sd[1])).dp.toPx()
                var y = -((scroll * (0.6f + sd[2]) + sd[3] * gap) % gap)
                while (y < h) {
                    drawLine(tint(ink, ink.amber, c, 0.18f + 0.3f * sd[1]), Offset(x, y), Offset(x, y + len), strokeWidth = 2.dp.toPx())
                    y += gap
                }
            }
            for ((k, d) in marks(s, f, h / ppp)) {
                val y = h * 0.42f + d * ppp
                drawLine(ink.amber.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                say(tm, Words.mark(s, k, f.max), 14.dp.toPx(), y - 10.dp.toPx(), st(ink, 11.sp, ink.dim, bold = false), 0.9f, ax = 0f)
            }
            // the layer we are in, at the edge
            say(tm, "[ ${Words.layer(f.value, f.max)} ]", w - 14.dp.toPx(), h * 0.74f, st(ink, 12.sp, ink.hot), 0.9f, ax = 1f)
        }
        Style.ABYSS -> {
            // light fades with depth; marine snow rising past; depth lines
            val deep = (f.value * 2f / 1400f).coerceIn(0f, 1f)
            drawRect(Brush.verticalGradient(listOf(lerp(Color(0xFF0E4A6B), Color(0xFF02060C), deep), lerp(Color(0xFF052238), Color.Black, deep))))
            for (i in 0 until 50 * more) {
                val sd = seeds[i % seeds.size]
                val x = w * ((sd[0] + i * 0.013f) % 1f)
                val y = h - ((sd[1] * h + scroll * (0.5f + sd[2])) % h)
                val len = (2f + 46f * f.v * sd[2]).dp.toPx()
                drawLine(tint(ink, Color.White, i, 0.25f + 0.35f * sd[3]), Offset(x, y), Offset(x, y + len), strokeWidth = (1f + sd[3] * 1.5f).dp.toPx(), cap = StrokeCap.Round)
            }
            for (i in 0 until 5) {
                val sd = seeds[60 + i]
                val y = h - ((sd[1] * h + scroll * 0.35f) % h)
                val x = w * sd[0] + sin(f.sec * 1.5f + i) * 12.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(ink.hot.copy(alpha = 0.35f), Color.Transparent), Offset(x, y), 26.dp.toPx()), 26.dp.toPx(), Offset(x, y))
            }
            for ((k, d) in marks(s, f, h / ppp)) {
                val y = h * 0.42f + d * ppp
                drawLine(Color.White.copy(alpha = 0.22f), Offset(w * 0.08f, y), Offset(w * 0.2f, y), strokeWidth = 1.dp.toPx())
                say(tm, Words.mark(s, k, f.max), w * 0.22f, y, st(ink, 11.sp, Color.White.copy(alpha = 0.6f), bold = false), 0.9f, ax = 0f)
            }
        }
        Style.PAPER -> {
            // the press: three columns of type running up, rollers top and bottom, an edition banner at each mark
            val colW = w * 0.26f
            for (c in 0 until 3) {
                val x0 = w * 0.08f + c * (colW + w * 0.04f)
                val gap = 9.dp.toPx()
                var y = -((scroll * 1.2f) % gap)
                var n = c * 7
                while (y < h) {
                    val lw = colW * (0.6f + 0.4f * seeds[n % seeds.size][0])
                    drawRect(ink.amber.copy(alpha = 0.18f), Offset(x0, y), Size(lw, 3.dp.toPx()))
                    if (f.v > 0.2f) drawRect(ink.amber.copy(alpha = 0.06f * f.v), Offset(x0, y), Size(lw, (3f + 20f * f.v).dp.toPx()))
                    y += gap; n++
                }
            }
            for (r in 0 until 2) {
                val cy = if (r == 0) 30.dp.toPx() else h - 30.dp.toPx()
                drawRect(ink.amber.copy(alpha = 0.85f), Offset(0f, cy - 14.dp.toPx()), Size(w, 28.dp.toPx()))
                for (spoke in 0 until 10) {
                    val x = ((spoke * w / 10f + scroll * 1.2f) % w)
                    drawLine(ink.bg.copy(alpha = 0.6f), Offset(x, cy - 14.dp.toPx()), Offset(x, cy + 14.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            }
            for ((k, d) in marks(s, f, h / ppp)) {
                val y = h * 0.42f + d * ppp * 1.2f
                drawRect(ink.amber, Offset(0f, y - 13.dp.toPx()), Size(w, 26.dp.toPx()))
                say(tm, Words.mark(s, k, f.max), w / 2f, y, st(ink, 13.sp, ink.bg, spacing = 3.sp), 1f)
            }
        }
        Style.NEON -> {
            // night drive: sun on the horizon, a grid floor rushing at you, posts flying by
            val hz = h * 0.36f
            drawRect(Brush.verticalGradient(listOf(ink.bg, lerp(ink.bg, ink.hot, 0.35f)), 0f, hz), size = Size(w, hz))
            val sunR = w * 0.22f
            drawCircle(Brush.verticalGradient(listOf(ink.amber, ink.hot), hz - sunR, hz), sunR, Offset(w / 2f, hz))
            for (i in 0 until 5) {
                val y = hz - sunR * (0.12f + i * 0.17f)
                drawRect(ink.bg, Offset(w / 2f - sunR, y), Size(sunR * 2f, (2f + i).dp.toPx()))
            }
            drawRect(ink.bg, Offset(0f, hz), Size(w, h - hz))
            val phase = (scroll / ppp * 0.6f) % 1f
            for (i in 0 until 14) {
                val z = ((i + 1f - phase) / 14f)
                val y = hz + (h - hz) * z * z
                drawLine(tint(ink, ink.hot, i, 0.25f + 0.6f * z), Offset(0f, y), Offset(w, y), strokeWidth = (1f + 2f * z).dp.toPx())
            }
            for (i in -7..7) {
                drawLine(tint(ink, ink.amber, i + 7, 0.45f), Offset(w / 2f + i * w * 0.02f, hz), Offset(w / 2f + i * w * 0.32f, h), strokeWidth = 1.5.dp.toPx())
            }
            for ((k, d) in marks(s, f, 30f)) {
                if (d < -1f) continue
                val z = (1f - d / 30f).coerceIn(0f, 1f)
                val y = hz + (h - hz) * z * z
                val x = w / 2f + w * 0.42f * z * z * (if (k % 2 == 0) 1f else -1f)
                drawLine(ink.amber.copy(alpha = z), Offset(x, y), Offset(x, y - 60.dp.toPx() * z * z), strokeWidth = (2f + 4f * z).dp.toPx())
            }
            say(tm, "${(f.v * 299).toInt()} KM/H", w / 2f, h * 0.9f, st(ink, 14.sp, ink.amber, spacing = 2.sp), 0.9f)
        }
        Style.CLEAN -> {
            // the prize ladder sliding down as we climb; spotlights swing faster with speed
            for (b in 0 until 2) {
                val ang = (if (b == 0) -1f else 1f) * (20f + 25f * sin(f.sec * (1.2f + 3f * f.v) + b * 2f))
                rotate(ang, Offset(if (b == 0) 0f else w, h)) {
                    val path = Path().apply {
                        moveTo(if (b == 0) 0f else w, h)
                        lineTo(w / 2f - w * 0.25f, -h * 0.1f)
                        lineTo(w / 2f + w * 0.25f, -h * 0.1f)
                        close()
                    }
                    drawPath(path, Brush.verticalGradient(listOf(ink.hot.copy(alpha = 0.10f), Color.Transparent), h, 0f))
                }
            }
            for ((k, d) in marks(s, f, h / ppp)) {
                if (k == 0) continue
                val y = h * 0.42f - d * ppp
                val near = (1f - abs(d) / 10f).coerceIn(0f, 1f)
                val pw = w * (0.5f + 0.08f * near)
                val ph = 40.dp.toPx()
                drawRoundRect(lerp(ink.panel, ink.hot, near * 0.8f), Offset(w / 2f - pw / 2f, y - ph / 2f), Size(pw, ph), CornerRadius(ph / 2f))
                say(tm, Words.mark(s, k, f.max), w / 2f, y, st(ink, 15.sp, if (near > 0.5f) onColor(ink.hot) else ink.dim), 0.35f + 0.65f * near)
            }
        }
        Style.PIXEL -> {
            // side-scroller: clouds, a running hero, ground and coins, an XP bar that levels up
            val g = 4.dp.toPx()
            fun snap(x: Float) = (x / g).toInt() * g
            for (i in 0 until 4) {
                val sd = seeds[i]
                val x = snap(w - ((sd[0] * w + scroll * 0.2f) % (w + 120.dp.toPx())))
                val y = snap(h * (0.12f + 0.15f * sd[1]))
                drawRect(ink.amber.copy(alpha = 0.18f), Offset(x, y), Size(snap(60.dp.toPx()), snap(16.dp.toPx())))
            }
            val ground = snap(h * 0.72f)
            drawRect(ink.amber.copy(alpha = 0.55f), Offset(0f, ground), Size(w, g))
            val tile = 24.dp.toPx()
            var x = -snap(scroll % tile)
            while (x < w) { drawRect(ink.amber.copy(alpha = 0.25f), Offset(x, ground + g * 2), Size(g * 2, g * 2)); x += tile }
            for ((_, d) in marks(s, f, w / ppp)) {
                val cx = snap(w * 0.3f + d * ppp)
                val bob = if (((f.sec * 4f).toInt() % 2) == 0) 0f else g
                drawRect(tint(ink, ink.hot, 3, 1f), Offset(cx - g, ground - g * 8 - bob), Size(g * 2, g * 3))
            }
            // hero (blocky): legs swap with distance
            val hx = snap(w * 0.3f)
            val step = ((scroll / (g * 6)).toInt() % 2 == 0)
            val c = ink.amber
            drawRect(c, Offset(hx - g * 2, ground - g * 10), Size(g * 4, g * 4))      // head
            drawRect(c, Offset(hx - g * 2, ground - g * 6), Size(g * 4, g * 3))       // body
            drawRect(c, Offset(hx - g * (if (step) 2 else 1), ground - g * 3), Size(g, g * 3))
            drawRect(c, Offset(hx + g * (if (step) 1 else 0), ground - g * 3), Size(g, g * 3))
            if (f.v > 0.4f) for (i in 1..3) drawRect(c.copy(alpha = 0.3f), Offset(hx - g * (4 + i * 3), ground - g * (4 + i)), Size(g * 2, g))
            // XP bar
            val frac = (f.value % 100f) / 100f
            drawRect(ink.amber, Offset(w * 0.1f, h * 0.08f), Size(w * 0.8f, g * 4), style = Stroke(g / 2))
            drawRect(tint(ink, ink.hot, 1, 1f), Offset(w * 0.1f + g, h * 0.08f + g), Size(snap((w * 0.8f - g * 2) * frac), g * 2))
        }
        Style.COMIC -> {
            // halftone, radial speed lines, sound effects whooshing past
            val dot = 14.dp.toPx()
            var yy = 0f
            while (yy < h) { var xx = if ((yy / dot).toInt() % 2 == 0) 0f else dot / 2; while (xx < w) { drawCircle(ink.line.copy(alpha = 0.08f), 2.dp.toPx(), Offset(xx, yy)); xx += dot }; yy += dot }
            val o = Offset(w / 2f, h * 0.42f)
            for (i in 0 until 40 * more) {
                val sd = seeds[i % seeds.size]
                val ang = (i / (40f * more) + sd[0] * 0.02f) * 2f * PI.toFloat()
                val r0 = w * (0.3f + 0.25f * ((sd[1] + f.sec * (0.5f + 2.5f * f.v)) % 1f))
                val r1 = r0 + w * 0.25f * f.v * (0.4f + sd[2])
                drawLine(tint(ink, ink.line, i, 0.5f + 0.4f * f.v), Offset(o.x + cos(ang) * r0, o.y + sin(ang) * r0), Offset(o.x + cos(ang) * r1, o.y + sin(ang) * r1),
                    strokeWidth = (2f + 3f * sd[3]).dp.toPx())
            }
            for ((k, d) in marks(s, f, w / ppp)) {
                val x = w / 2f + d * ppp * 1.4f
                val y = h * (if (k % 2 == 0) 0.72f else 0.18f)
                burst(Offset(x, y), 44.dp.toPx(), if (k % 2 == 0) ink.hot else ink.amber, k)
                say(tm, Words.mark(s, k, f.max), x, y, st(ink, 18.sp, onColor(if (k % 2 == 0) ink.hot else ink.amber), display = true), 1f)
            }
        }
        Style.METRO -> {
            // window of a moving train: tunnel lights streaking, platform signs sliding past, rails, the route line
            drawRect(Color.Black.copy(alpha = 0.55f), Offset(0f, h * 0.2f), Size(w, h * 0.5f))
            for (i in 0 until 8) {
                val sd = seeds[i]
                val x = w - ((sd[0] * w + scroll * 2f) % (w * 1.4f))
                val len = (8f + 160f * f.v).dp.toPx()
                drawLine(tint(ink, ink.amber, i, 0.6f), Offset(x, h * (0.25f + 0.05f * (i % 3))), Offset(x + len, h * (0.25f + 0.05f * (i % 3))), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            for ((k, d) in marks(s, f, w / ppp)) {
                val x = w / 2f + d * ppp * 1.6f
                val sw = 170.dp.toPx()
                drawRoundRect(ink.hot, Offset(x - sw / 2f, h * 0.55f), Size(sw, 34.dp.toPx()), CornerRadius(4.dp.toPx()))
                say(tm, Words.mark(s, k, f.max), x, h * 0.55f + 17.dp.toPx(), st(ink, 13.sp, onColor(ink.hot)), 1f)
            }
            val ry = h * 0.68f
            drawLine(ink.line, Offset(0f, ry), Offset(w, ry), strokeWidth = 3.dp.toPx())
            var sx = -((scroll * 2f) % (22.dp.toPx()))
            while (sx < w) { drawLine(ink.line.copy(alpha = 0.6f), Offset(sx, ry + 4.dp.toPx()), Offset(sx - 8.dp.toPx(), ry + 14.dp.toPx()), strokeWidth = 3.dp.toPx()); sx += 22.dp.toPx() }
            // route: stations as dots, the train moving along
            val y0 = h * 0.08f
            drawLine(ink.hot, Offset(w * 0.08f, y0), Offset(w * 0.92f, y0), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            val first = (f.from / 10)
            for (i in 0..5) {
                val dot = Offset(w * (0.08f + 0.168f * i), y0)
                drawCircle(ink.bg, 6.dp.toPx(), dot)
                drawCircle(ink.hot, 6.dp.toPx(), dot, style = Stroke(2.dp.toPx()))
            }
            val tx = w * (0.08f + 0.168f * ((f.value / 10f - first).coerceIn(0f, 5f)))
            drawCircle(ink.amber, 9.dp.toPx(), Offset(tx, y0))
        }
        Style.JACKPOT -> {
            // chasing bulbs round the screen, faster with speed
            val n = 36
            val lit = (scroll / 30.dp.toPx()).toInt()
            for (i in 0 until n) {
                val p = i / n.toFloat()
                val pt = perimeter(p, w * 0.06f, h * 0.06f, w * 0.88f, h * 0.88f)
                val on = (i + lit) % 3 == 0
                drawCircle(if (on) tint(ink, ink.amber, i, 1f) else ink.amber.copy(alpha = 0.18f), (if (on) 6f else 4f).dp.toPx(), pt)
            }
            reels(f, ink, tm)
        }
        Style.CHAT -> {
            // the thread flying up: bubbles left and right, hearts floating
            val gap = 64.dp.toPx()
            var y = h - ((scroll * 1.1f) % gap) - gap
            var i = 0
            while (y > -gap) {
                val sd = seeds[i % seeds.size]
                val bw = w * (0.3f + 0.35f * sd[0])
                val mine = (i + (scroll * 1.1f / gap).toInt()) % 2 == 0
                val x = if (mine) w - 16.dp.toPx() - bw else 16.dp.toPx()
                drawRoundRect(if (mine) ink.hot.copy(alpha = 0.35f) else ink.line.copy(alpha = 0.45f), Offset(x, y), Size(bw, 40.dp.toPx()), CornerRadius(18.dp.toPx()))
                y -= gap; i++
            }
            for (k in 0 until 8 * more) {
                val sd = seeds[30 + k]
                val hy = h - ((sd[1] * h + scroll * (0.4f + sd[2] * 0.6f)) % h)
                heart(Offset(w * sd[0], hy), (6f + 6f * sd[3]).dp.toPx(), tint(ink, ink.hot, k, 0.5f + 0.4f * f.v))
            }
        }
        Style.DESKTOP -> desktopCopy(f, ink, tm)
        Style.ORACLE -> {
            // warp through the stars; sigil rings turning; arcana passing
            val o = Offset(w / 2f, h * 0.42f)
            val reach = max(w, h) * 0.75f
            for (i in 0 until 80 * more) {
                val sd = seeds[i % seeds.size]
                val ang = (sd[0] + i * 0.0137f) * 2f * PI.toFloat()
                val z = ((sd[1] + f.value * 0.02f * (0.6f + sd[2])) % 1f)
                val r = reach * z * z
                val len = reach * 0.08f * f.v * z + 1.dp.toPx()
                drawLine(tint(ink, ink.white, i, 0.25f + 0.7f * z), Offset(o.x + cos(ang) * r, o.y + sin(ang) * r),
                    Offset(o.x + cos(ang) * (r + len), o.y + sin(ang) * (r + len)), strokeWidth = (1f + 1.5f * z).dp.toPx(), cap = StrokeCap.Round)
            }
            for (ring in 0 until 2) {
                rotate(f.sec * (20f + 200f * f.v) * (if (ring == 0) 1f else -0.6f), o) {
                    drawCircle(ink.hot.copy(alpha = 0.35f), w * (0.36f + 0.08f * ring), o, style = Stroke(1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))))
                }
            }
            for ((k, d) in marks(s, f, 6f)) {
                val z = (1f - abs(d) / 6f).coerceIn(0f, 1f)
                say(tm, Words.mark(s, k, f.max), w / 2f, h * 0.74f + d * 8.dp.toPx(), st(ink, 13.sp, ink.hot, spacing = 2.sp), z * 0.8f)
            }
        }
    }
}

/** Counter, words and the arrival on top of the world. */
private fun DrawScope.overlay(s: Style, f: Frame, ink: Ink, tm: TextMeasurer, tierName: String) {
    val w = size.width
    val h = size.height
    val cy = if (s == Style.DESKTOP) h * 0.24f else h * 0.42f
    val hy = ink.hyper
    // what is happening
    say(tm, Words.going(s), w / 2f, h * 0.15f, st(ink, 13.sp, ink.dim, spacing = 3.sp), 1f - f.arrive * 0.6f)
    if (s != Style.JACKPOT) {
        // a soft band behind the number, then the number (pops on arrival, wobbles in ADHD-fy)
        drawRoundRect(ink.bg.copy(alpha = 0.72f), Offset(w * 0.1f, cy - 44.dp.toPx()), Size(w * 0.8f, 88.dp.toPx()), CornerRadius(16.dp.toPx()))
        val pop = 1f + 0.22f * sin(f.arrive * PI.toFloat()) + (if (hy.on) 0.04f * sin(f.sec * 9f) else 0f)
        val color = if (hy.on) hue(hy.t * 150f, 0.75f, 1f) else lerp(ink.amber, ink.tier, f.u)
        rotate(if (hy.on) 4f * sin(f.sec * 5f) else 0f, Offset(w / 2f, cy)) {
            scale(pop, Offset(w / 2f, cy)) {
                say(tm, Words.counter(s, f.value), w / 2f, cy, st(ink, if (s == Style.NEON) 44.sp else 52.sp, color, display = true), 1f)
            }
        }
    }
    // the gain, then where we are and the tier on arrival
    say(tm, Words.gain(s, f.gain), w / 2f, cy + 58.dp.toPx(), st(ink, 18.sp, ink.tier), (f.e / 300f).coerceIn(0f, 1f))
    if (f.arrive > 0f) {
        val a = f.arrive
        say(tm, Words.place(s, f.from + f.gain.toFloat(), f.max), w / 2f, cy + 96.dp.toPx() + 10.dp.toPx() * (1f - a), st(ink, 16.sp, ink.white), a)
        say(tm, tierName, w / 2f, cy + 128.dp.toPx() + 10.dp.toPx() * (1f - a), st(ink, 14.sp, ink.tier, spacing = 2.sp), a)
        // a ring going out from the number
        drawCircle(tint(ink, ink.tier, 0, (1f - a) * 0.8f), 40.dp.toPx() + w * 0.5f * a, Offset(w / 2f, cy), style = Stroke((4f * (1f - a) + 0.5f).dp.toPx()))
        if (hy.on) for (i in 0 until 12) {
            val ang = i / 12f * 2f * PI.toFloat()
            val d = w * 0.42f * a
            sparkle(Offset(w / 2f + cos(ang) * d, cy + sin(ang) * d), 9.dp.toPx() * (1f - a * 0.5f), hue(i * 30f + hy.t * 200f, 0.8f, 1f, 1f - a), a * 360f)
        }
    } else {
        say(tm, "tap to skip", w / 2f, h * 0.95f, st(ink, 11.sp, ink.dim, bold = false), 0.6f)
    }
}

// ================================================================== pieces

/** Comic starburst behind a sound effect. */
private fun DrawScope.burst(c: Offset, r: Float, color: Color, seed: Int) {
    val rnd = Random(seed)
    val path = Path()
    val n = 14
    for (i in 0 until n * 2) {
        val rr = if (i % 2 == 0) r * (1f + 0.15f * rnd.nextFloat()) else r * 0.62f
        val ang = i * PI.toFloat() / n
        val x = c.x + cos(ang) * rr * 1.5f
        val y = c.y + sin(ang) * rr
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
    drawPath(path, Color.Black, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.heart(c: Offset, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + s * 0.9f)
        cubicTo(c.x - s * 1.4f, c.y - s * 0.1f, c.x - s * 0.6f, c.y - s * 1.1f, c.x, c.y - s * 0.35f)
        cubicTo(c.x + s * 0.6f, c.y - s * 1.1f, c.x + s * 1.4f, c.y - s * 0.1f, c.x, c.y + s * 0.9f)
        close()
    }
    drawPath(p, color)
}

/** Point at fraction [p] (0..1) round the rectangle (x, y, w, h), clockwise from the top left. */
private fun perimeter(p: Float, x: Float, y: Float, w: Float, h: Float): Offset {
    val total = 2f * (w + h)
    var d = (p % 1f) * total
    if (d < w) return Offset(x + d, y)
    d -= w
    if (d < h) return Offset(x + w, y + d)
    d -= h
    if (d < w) return Offset(x + w - d, y + h)
    d -= w
    return Offset(x, y + h - d)
}

/** JACKPOT: the total as slot reels — every digit rolls like an odometer and blurs with speed. */
private fun DrawScope.reels(f: Frame, ink: Ink, tm: TextMeasurer) {
    val w = size.width
    val h = size.height
    val digits = 4
    val rw = w * 0.17f
    val rh = rw * 1.5f
    val x0 = w / 2f - rw * digits / 2f - 4.dp.toPx() * (digits - 1) / 2f
    val cy = h * 0.42f
    val popScale = 1f + 0.12f * sin(f.arrive * PI.toFloat())
    scale(popScale, Offset(w / 2f, cy)) {
        for (i in 0 until digits) {
            val place = digits - 1 - i
            var div = 1f
            repeat(place) { div *= 10f }
            val roll = (f.value / div) % 10f          // this reel's position, 0..10
            val x = x0 + i * (rw + 4.dp.toPx())
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFDDDDDD), Color.White, Color(0xFFDDDDDD)), cy - rh / 2f, cy + rh / 2f),
                Offset(x, cy - rh / 2f), Size(rw, rh), CornerRadius(8.dp.toPx()))
            val d0 = floor(roll).toInt()
            val frac = roll - d0
            val style = st(ink, 40.sp, Color(0xFF1A1A1A), display = true)
            val blur = (f.v * (if (place == 0) 1f else 0.5f)).coerceIn(0f, 1f)
            clipRect(x, cy - rh / 2f, x + rw, cy + rh / 2f) {
                for (k in -1..1) {
                    val digit = ((d0 + k) % 10 + 10) % 10
                    val y = cy + (k - frac) * rh * 0.8f
                    say(tm, digit.toString(), x + rw / 2f, y, style, 1f - blur * 0.5f)
                    if (blur > 0.15f) say(tm, digit.toString(), x + rw / 2f, y - rh * 0.12f * blur, style, blur * 0.35f)
                }
            }
            drawRoundRect(ink.amber, Offset(x, cy - rh / 2f), Size(rw, rh), CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()))
        }
        drawLine(ink.hot.copy(alpha = 0.7f), Offset(x0 - 10.dp.toPx(), cy), Offset(x0 + digits * (rw + 4.dp.toPx()) + 6.dp.toPx(), cy), strokeWidth = 2.dp.toPx())
    }
}

/** DESKTOP: a copy dialog — documents flying folder to folder, a segmented progress bar, a transfer rate. */
private fun DrawScope.desktopCopy(f: Frame, ink: Ink, tm: TextMeasurer) {
    val w = size.width
    val h = size.height
    val left = w * 0.08f
    val top = h * 0.4f
    val ww = w * 0.84f
    val wh = h * 0.3f
    drawRect(ink.panel, Offset(left, top), Size(ww, wh))
    drawRect(Color.White, Offset(left, top), Size(ww, 2.dp.toPx()))
    drawRect(Color.Black.copy(alpha = 0.6f), Offset(left, top + wh - 2.dp.toPx()), Size(ww, 2.dp.toPx()))
    drawRect(Brush.horizontalGradient(listOf(ink.hot, lerp(ink.hot, Color.White, 0.42f)), left, left + ww), Offset(left + 3.dp.toPx(), top + 3.dp.toPx()), Size(ww - 6.dp.toPx(), 24.dp.toPx()))
    say(tm, "Copying…", left + 10.dp.toPx(), top + 15.dp.toPx(), st(ink, 13.sp, onColor(ink.hot)), 1f, ax = 0f)
    // folders and flying pages
    val fy = top + wh * 0.38f
    val fa = Offset(left + ww * 0.15f, fy)
    val fb = Offset(left + ww * 0.85f, fy)
    for (p in listOf(fa, fb)) {
        drawRect(Color(0xFFE8C547), Offset(p.x - 22.dp.toPx(), p.y - 12.dp.toPx()), Size(44.dp.toPx(), 30.dp.toPx()))
        drawRect(Color(0xFFD4A82A), Offset(p.x - 22.dp.toPx(), p.y - 18.dp.toPx()), Size(18.dp.toPx(), 8.dp.toPx()))
    }
    val pages = if (f.v > 0.05f) 1 + (f.v * 4).toInt() else 0
    for (i in 0 until pages) {
        val q = ((f.sec * (0.8f + 1.6f * f.v) + i / pages.toFloat()) % 1f)
        val x = fa.x + (fb.x - fa.x) * q
        val y = fy - sin(q * PI.toFloat()) * wh * 0.3f
        drawRect(tint(ink, Color.White, i, 1f), Offset(x - 8.dp.toPx(), y - 10.dp.toPx()), Size(16.dp.toPx(), 20.dp.toPx()))
        drawRect(Color.Black, Offset(x - 8.dp.toPx(), y - 10.dp.toPx()), Size(16.dp.toPx(), 20.dp.toPx()), style = Stroke(1.dp.toPx()))
    }
    // progress bar in blocks
    val by = top + wh * 0.66f
    val bw = ww - 24.dp.toPx()
    drawRect(Color.White, Offset(left + 12.dp.toPx(), by), Size(bw, 20.dp.toPx()))
    val blocks = (f.u * 24).toInt()
    for (i in 0 until blocks) drawRect(tint(ink, ink.hot, i, 1f), Offset(left + 14.dp.toPx() + i * (bw - 4.dp.toPx()) / 24f, by + 2.dp.toPx()), Size((bw - 4.dp.toPx()) / 24f - 2.dp.toPx(), 16.dp.toPx()))
    val rate = (f.v * 9999).toInt()
    val left2 = ((1f - f.u) * f.travel / 1000f)
    say(tm, "$rate KB/s · ${((left2 * 10).toInt() / 10f)} s left", left + 12.dp.toPx(), by + 36.dp.toPx(), st(ink, 12.sp, ink.amber, bold = false), 1f, ax = 0f)
}
