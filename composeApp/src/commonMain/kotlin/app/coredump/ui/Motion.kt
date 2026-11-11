package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Small motion shared by every look:
 *  - press(): how a button or chip reacts to a finger, one feel per look (stamp, jelly, pop, slide …)
 *  - entrance(): how a panel arrives the first time it is shown, one way per look
 *  - breathe(): a slow pulse on main buttons in the softer looks
 *  - IdleAmbience(): faint background motion for the looks without an animated backdrop
 * and ADHD-fy (per look, settings → appearance): rainbow frames, bobbing, rainbow titles, colour blobs and sparks
 * behind the content, sparkle bursts under every tap, a shimmering screen edge.
 */

/** ADHD-fy state of the active look: [on], and a running clock in seconds ([t]: read it in draw / layer blocks only). */
@Stable
class Hyper(val on: Boolean, private val clock: State<Float>) {
    val t: Float get() = clock.value
}

val LocalHyper = staticCompositionLocalOf { Hyper(false, mutableFloatStateOf(0f)) }

/**
 * Seconds since [running] turned on, ticking while it is on (frozen otherwise): every frame, or [fps] times a second
 * for ambient motion (between ticks no frames are asked for, so a phone at 60/120 Hz is not kept redrawing).
 * Read it inside draw / graphicsLayer blocks, so a tick only redraws.
 */
@Composable
fun rememberClock(running: Boolean, fps: Int = 0): State<Float> {
    val s = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, fps) {
        if (!running) return@LaunchedEffect
        val start = withFrameMillis { it }
        if (fps <= 0) {
            while (true) withFrameMillis { s.floatValue = (it - start) / 1000f }
        } else {
            val step = (1000L / fps).coerceAtLeast(8L)
            while (true) {
                kotlinx.coroutines.delay(step)
                withFrameMillis { s.floatValue = (it - start) / 1000f }
            }
        }
    }
    return s
}

/** A colour on the colour wheel; [h] in degrees (any value, wraps). */
fun hue(h: Float, s: Float = 0.78f, v: Float = 1f, a: Float = 1f): Color {
    val d = ((h % 360f) + 360f) % 360f
    return Color.hsv(if (d >= 360f) 0f else d, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), a.coerceIn(0f, 1f))
}

/** [n] + 1 colours around the wheel starting at [shift] degrees (first = last, for sweeps). */
fun rainbow(shift: Float, n: Int = 6, s: Float = 0.8f, v: Float = 1f): List<Color> = List(n + 1) { hue(shift + it * 360f / n, s, v) }

// ------------------------------------------------------------------ press

/**
 * How a pressed button / chip moves, one feel per look; it springs back past rest (a small bounce).
 * PIXEL and DESKTOP keep their own stepped / bevelled press only.
 */
@Composable
fun Modifier.press(pressed: Boolean): Modifier {
    if (LocalLook.current.reduceMotion) return this
    val style = Th.style
    val hyper = LocalHyper.current.on
    if (!hyper && (style == Style.PIXEL || style == Style.DESKTOP)) return this
    val target = if (pressed) 1f else 0f
    val p by animateFloatAsState(
        target,
        if (pressed) tween<Float>(70) else spring<Float>(dampingRatio = if (hyper) 0.25f else 0.4f, stiffness = if (style == Style.ABYSS) 260f else 520f),
    )
    val k = if (hyper) 1.9f else 1f
    return graphicsLayer {
        when (style) {
            Style.TERMINAL -> { translationX = p * 1.5.dp.toPx() * k }
            Style.PAPER -> { rotationZ = -0.9f * p * k; val s = 1f - 0.02f * p * k; scaleX = s; scaleY = s }
            Style.NEON -> { val s = 1f - 0.05f * p * k; scaleX = s; scaleY = s }
            Style.CLEAN -> { val s = 1f - 0.06f * p * k; scaleX = s; scaleY = s }
            Style.COMIC -> { val s = 1f - 0.03f * p * k; scaleX = s; scaleY = s }
            Style.ABYSS -> { scaleX = 1f + 0.04f * p * k; scaleY = 1f - 0.06f * p * k }   // jelly
            Style.METRO -> { translationX = 3.dp.toPx() * p * k }
            Style.JACKPOT -> { val s = 1f - 0.02f * p * k; scaleX = s; scaleY = s }
            Style.CHAT -> { val s = 1f - 0.08f * p * k; scaleX = s; scaleY = s }   // bubble pop
            Style.ORACLE -> { val s = 1f - 0.03f * p * k; scaleX = s; scaleY = s }
            Style.PIXEL, Style.DESKTOP -> { val s = 1f - 0.04f * p; scaleX = s; scaleY = s }   // ADHD-fy only
        }
        if (hyper) { rotationZ += 2.5f * p }
    }
}

// ------------------------------------------------------------------ entrance

private fun entranceSpec(style: Style, hyper: Boolean): AnimationSpec<Float> = when {
    hyper -> spring<Float>(dampingRatio = 0.38f, stiffness = 300f)
    else -> when (style) {
        Style.CLEAN -> spring(dampingRatio = 0.72f, stiffness = 320f)
        Style.COMIC -> spring(dampingRatio = 0.5f, stiffness = 420f)
        Style.JACKPOT -> spring(dampingRatio = 0.45f, stiffness = 360f)
        Style.CHAT -> spring(dampingRatio = 0.6f, stiffness = 420f)
        Style.ABYSS -> tween(700, easing = FastOutSlowInEasing)
        Style.ORACLE -> tween(650, easing = FastOutSlowInEasing)
        Style.METRO -> tween(380, easing = FastOutSlowInEasing)
        Style.PAPER -> tween(320, easing = FastOutSlowInEasing)
        Style.DESKTOP -> tween(180, easing = LinearEasing)
        Style.PIXEL -> tween(240, easing = LinearEasing)
        else -> tween(280, easing = LinearEasing)
    }
}

/** 0..1 in [n] equal steps (old screens and handhelds move in jumps). */
private fun stepped(p: Float, n: Int): Float = (p * n).toInt().coerceAtMost(n) / n.toFloat()

/** How a panel arrives the first time it is composed, one way per look (once; not with reduced motion). */
@Composable
fun Modifier.entrance(): Modifier {
    if (LocalLook.current.reduceMotion) return this
    val style = Th.style
    val hyper = LocalHyper.current.on
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, entranceSpec(style, hyper)) }
    return graphicsLayer {
        val p = a.value
        val q = 1f - p
        val fade = p.coerceIn(0f, 1f)
        when (style) {
            Style.TERMINAL -> { alpha = stepped(fade, 4); scaleY = 0.96f + 0.04f * fade }   // power-on flicker
            Style.PAPER -> { translationY = -8.dp.toPx() * q; alpha = fade }
            Style.NEON -> { alpha = if (p < 0.35f) (if ((p * 20f).toInt() % 2 == 0) 0.25f else 0.9f) else fade; val s = 1.02f - 0.02f * fade; scaleX = s; scaleY = s }
            Style.CLEAN -> { translationY = 14.dp.toPx() * q; alpha = fade }
            Style.PIXEL -> { alpha = stepped(fade, 4); translationY = stepped(q.coerceIn(0f, 1f), 4) * 8.dp.toPx() }
            Style.COMIC -> { rotationZ = -4f * q; val s = 0.9f + 0.1f * p; scaleX = s; scaleY = s; alpha = (p * 3f).coerceIn(0f, 1f) }
            Style.ABYSS -> { translationY = 24.dp.toPx() * q; alpha = fade }
            Style.METRO -> { translationX = 40.dp.toPx() * q; alpha = fade }
            Style.JACKPOT -> { translationY = -30.dp.toPx() * q; alpha = (p * 4f).coerceIn(0f, 1f) }
            Style.CHAT -> { transformOrigin = TransformOrigin(0f, 1f); val s = 0.85f + 0.15f * p; scaleX = s; scaleY = s; alpha = (p * 3f).coerceIn(0f, 1f) }
            Style.DESKTOP -> { val s = 0.9f + 0.1f * stepped(fade, 3); scaleX = s; scaleY = s }   // window zoom
            Style.ORACLE -> { translationY = 6.dp.toPx() * q; alpha = fade }
        }
        if (hyper) { rotationZ += 6f * q; val s = 1f + 0.08f * q; scaleX *= s; scaleY *= s }
    }
}

// ------------------------------------------------------------------ breathe

/** Slow pulse on a main button (softer looks only, or any look in ADHD-fy). [seed] staggers neighbours. */
@Composable
fun Modifier.breathe(on: Boolean, seed: Int = 0): Modifier {
    if (!on || LocalLook.current.reduceMotion) return this
    val style = Th.style
    val hyper = LocalHyper.current
    val soft = style == Style.CLEAN || style == Style.NEON || style == Style.ABYSS || style == Style.ORACLE || style == Style.CHAT || style == Style.JACKPOT
    if (!soft && !hyper.on) return this
    val clock = rememberClock(true)
    val amp = if (hyper.on) 0.035f else 0.014f
    val period = if (style == Style.ABYSS || style == Style.ORACLE) 3.6f else 2.4f
    return graphicsLayer {
        val s = 1f + amp * sin((clock.value / period + seed * 0.17f) * 2f * PI.toFloat())
        scaleX = s; scaleY = s
    }
}

// ------------------------------------------------------------------ ADHD-fy pieces

/** A finger came down at [at] (root px) at clock time [born] (s). */
class Tap(val at: Offset, val born: Float, val seed: Int)

/** Watches every finger-down on the screen (never consumes it) for ADHD-fy sparkles. */
fun Modifier.watchTaps(on: Boolean, now: () -> Float, taps: SnapshotStateList<Tap>): Modifier = if (!on) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            for (c in ev.changes) {
                if (c.pressed && !c.previousPressed) {
                    val t = now()
                    taps.removeAll { t - it.born > 1f }
                    if (taps.size > 12) taps.removeAt(0)
                    taps.add(Tap(c.position, t, Random.nextInt()))
                }
            }
        }
    }
}

@Composable
fun rememberTaps(): SnapshotStateList<Tap> = remember { mutableStateListOf() }

/** Four-point sparkle star of radius [s], turned by [deg]. */
fun DrawScope.sparkle(c: Offset, s: Float, color: Color, deg: Float = 0f) {
    val a = deg * PI.toFloat() / 180f
    val path = Path()
    for (i in 0 until 8) {
        val r = if (i % 2 == 0) s else s * 0.32f
        val ang = a + i * PI.toFloat() / 4f
        val x = c.x + cos(ang) * r
        val y = c.y + sin(ang) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

internal fun DrawScope.rotateRect(c: Offset, s: Float, color: Color, deg: Float) {
    val a = deg * PI.toFloat() / 180f
    val path = Path()
    for (i in 0 until 4) {
        val ang = a + i * PI.toFloat() / 2f
        val r = if (i % 2 == 0) s * 0.7f else s * 0.35f
        val x = c.x + cos(ang) * r
        val y = c.y + sin(ang) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

// ------------------------------------------------------------------ idle ambience

/**
 * Faint motion behind the content for the looks without an animated backdrop:
 * TERMINAL bits trickling down, QUIZ SHOW soft bokeh, QUEST twinkling pixels, CHAT bubbles rising,
 * DESKTOP a glare sweeping the glass now and then.
 */
@Composable
fun IdleAmbience(style: Style, motion: Boolean, modifier: Modifier = Modifier) {
    if (!motion) return
    if (style != Style.TERMINAL && style != Style.CLEAN && style != Style.PIXEL && style != Style.CHAT && style != Style.DESKTOP) return
    val clock = rememberClock(true, fps = 20)
    val amber = C.amber
    val hot = C.hot
    val seeds = remember(style) { val r = Random(style.ordinal * 7 + 3); List(28) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat()) } }
    Canvas(modifier.fillMaxSize()) {
        val t = clock.value
        val w = size.width
        val h = size.height
        when (style) {
            Style.TERMINAL -> {
                for ((i, s) in seeds.withIndex()) {
                    if (i >= 14) break
                    val x = (s[0] * w / 6.dp.toPx()).toInt() * 6.dp.toPx()
                    val y = ((s[1] + t * (0.015f + 0.03f * s[2])) % 1f) * h
                    drawRect(amber.copy(alpha = 0.05f + 0.06f * s[3]), Offset(x, y), Size(2.dp.toPx(), (4f + 10f * s[2]).dp.toPx()))
                }
            }
            Style.CLEAN -> {
                for ((i, s) in seeds.withIndex()) {
                    if (i >= 9) break
                    val x = w * ((s[0] + 0.02f * sin(t * 0.3f + i)) % 1f)
                    val y = h * (((s[1] - t * 0.008f * (0.5f + s[2])) % 1f + 1f) % 1f)
                    val r = (24f + 50f * s[3]).dp.toPx()
                    drawCircle((if (i % 2 == 0) hot else amber).copy(alpha = 0.035f), r, Offset(x, y))
                }
            }
            Style.PIXEL -> {
                for ((i, s) in seeds.withIndex()) {
                    if (i >= 16) break
                    val g = 4.dp.toPx()
                    val x = (s[0] * w / g).toInt() * g
                    val y = (s[1] * h / g).toInt() * g
                    val on = ((t * (0.6f + s[2]) + s[3] * 4f).toInt() % 4) == 0
                    if (on) drawRect(amber.copy(alpha = 0.22f), Offset(x, y), Size(g, g))
                }
            }
            Style.CHAT -> {
                for ((i, s) in seeds.withIndex()) {
                    if (i >= 10) break
                    val x = w * s[0] + sin(t * 0.8f + i) * 8.dp.toPx()
                    val y = h * (((s[1] - t * 0.02f * (0.4f + s[2])) % 1f + 1f) % 1f)
                    drawCircle(hot.copy(alpha = 0.06f), (6f + 14f * s[3]).dp.toPx(), Offset(x, y))
                }
            }
            Style.DESKTOP -> {
                // a glare across the glass every 14 s
                val p = (t % 14f) / 1.6f
                if (p < 1f) {
                    val x = -w * 0.4f + w * 1.8f * p
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                        Offset(x - w * 0.15f, 0f), Offset(x + w * 0.15f, h * 0.2f)))
                }
            }
            else -> Unit
        }
    }
}

/** Keeps a value in 0..1 with a short, readable name. */
internal fun clamp01(x: Float): Float = min(1f, max(0f, x))

/** |sin| pulse 0..1 at [hz] per second. */
internal fun pulse(t: Float, hz: Float): Float = abs(sin(t * hz * PI.toFloat()))
