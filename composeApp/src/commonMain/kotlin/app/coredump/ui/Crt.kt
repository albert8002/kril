package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Whole-app backdrop + screen effects, per [Style]:
 *  TERMINAL: CRT glass (scanlines, beam, vignette, flicker, surge, glitch tear)
 *  PAPER:    paper grain, column rules, soft vignette
 *  NEON:     dusk gradient + scrolling perspective grid + horizon glow
 *  CLEAN:    flat
 *  PIXEL:    LCD dot matrix
 *  COMIC:    halftone dots + one slashed colour band
 * Shake and flash ([Fx]) work in every style; glitch tears only in TERMINAL.
 */
@Composable
fun ThemedScreen(look: Look, fx: Fx, content: @Composable BoxScope.() -> Unit) {
    val style = Th.style
    val motion = !look.reduceMotion

    var flicker by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(look.flicker, motion, style) {
        if (style != Style.TERMINAL || !look.flicker || !motion) { flicker = 1f; return@LaunchedEffect }
        while (isActive) {
            flicker = 0.975f + Random.nextFloat() * 0.025f
            delay(60L + Random.nextLong(60))
        }
    }

    val surge = remember { Animatable(0f) }
    LaunchedEffect(motion, style) {
        if (!motion || style != Style.TERMINAL) return@LaunchedEffect
        while (isActive) {
            delay(45_000L + Random.nextLong(45_000))
            surge.animateTo(1f, tween(120))
            surge.animateTo(0f, tween(380))
        }
    }

    val loop = rememberInfiniteTransition()
    val beam by loop.animateFloat(-0.2f, 1.2f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart))
    val gridT by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart))

    var glitchSeed by remember { mutableIntStateOf(0) }
    var glitchOn by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(fx.glitchTick) {
        if (fx.glitchTick == 0 || !motion || (style != Style.TERMINAL && style != Style.NEON)) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            val t = withFrameMillis { it } - start
            if (t > 180) break
            glitchOn = 1f
            glitchSeed = Random.nextInt()
        }
        glitchOn = 0f
    }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(fx.shakeTick) {
        if (fx.shakeTick == 0 || !motion) return@LaunchedEffect
        shake.snapTo(1f)
        shake.animateTo(0f, tween(520, easing = LinearEasing))
    }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(fx.flashTick) {
        if (fx.flashTick == 0) return@LaunchedEffect
        launch {
            flash.snapTo(if (motion) (if (style == Style.TERMINAL || style == Style.NEON) 0.85f else 0.45f) else 0.25f)
            flash.animateTo(0f, tween(650))
        }
    }

    val bg = C.bg
    Box(Modifier.fillMaxSize().background(bg)) {
        // ---- backdrop
        when (style) {
            Style.NEON -> NeonBackdrop(if (motion) gridT else 0f)
            Style.PAPER -> PaperBackdrop()
            Style.COMIC -> ComicBackdrop()
            else -> Unit
        }

        // ---- content (+ shake / tear)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = flicker
                val s = shake.value
                if (s > 0f) {
                    val p = fx.shakePower
                    if (style == Style.PIXEL) {
                        // pixel games shake in whole steps
                        val step = 4.dp.toPx()
                        translationX = (sin(s * 38f) * 3f * p).toInt() * step * s
                        translationY = (sin(s * 29f + 1f) * 1.5f * p).toInt() * step * s
                    } else {
                        val k = if (style == Style.CLEAN || style == Style.PAPER) 0.5f else 1f
                        translationX = sin(s * 38f) * 14f * s * p * k
                        translationY = sin(s * 29f + 1f) * 6f * s * p * k
                    }
                }
                if (glitchOn > 0f) {
                    val r = Random(glitchSeed)
                    translationX += (r.nextFloat() - 0.5f) * 18f
                }
            },
            content = content,
        )

        // ---- overlays
        if (glitchOn > 0f) {
            Spacer(Modifier.fillMaxSize().drawBehind {
                val r = Random(glitchSeed)
                repeat(4 + r.nextInt(4)) {
                    val y = r.nextFloat() * size.height
                    val h = 2f + r.nextFloat() * 14f
                    val x = (r.nextFloat() - 0.5f) * 60f
                    drawRect(
                        if (r.nextBoolean()) C.blood.copy(alpha = 0.35f) else C.amber.copy(alpha = 0.25f),
                        topLeft = Offset(x, y), size = Size(size.width, h),
                    )
                }
            })
        }
        when (style) {
            Style.TERMINAL -> CrtGlass(look.scanlines, motion, beam)
            Style.PIXEL -> LcdMatrix()
            else -> Unit
        }
        Spacer(Modifier.fillMaxSize().drawBehind {
            val sv = surge.value
            if (sv > 0f) drawRect(C.hot.copy(alpha = 0.07f * sv))
            val fv = flash.value
            if (fv > 0f) drawRect(fx.flashColor.copy(alpha = fv))
        })
    }
}

@Composable
private fun CrtGlass(scanlines: Boolean, motion: Boolean, beam: Float) {
    val amber = C.amber
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val lines = Path()
            if (scanlines) {
                val step = 3.dp.toPx().coerceAtLeast(3f)
                var y = 0f
                while (y < size.height) {
                    lines.addRect(Rect(0f, y, size.width, y + step / 3f))
                    y += step
                }
            }
            val vignette = Brush.radialGradient(
                0.0f to Color.Transparent,
                0.62f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.72f),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = max(size.width, size.height) * 0.78f,
            )
            onDrawBehind {
                if (scanlines) drawPath(lines, Color.Black.copy(alpha = 0.28f))
                drawRect(vignette)
            }
        }
    )
    if (motion) {
        Spacer(Modifier.fillMaxSize().drawBehind {
            val bh = size.height * 0.16f
            val top = beam * size.height - bh / 2f
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to amber.copy(alpha = 0.045f), 1f to Color.Transparent,
                    startY = top, endY = top + bh),
                topLeft = Offset(0f, top), size = Size(size.width, bh),
            )
        })
    }
}

/** Paper grain (fixed seed, cached) + faint column rules. */
@Composable
private fun PaperBackdrop() {
    val ink = C.amber
    val line = C.line
    val dark = Th.pal.dark
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val r = Random(7)
        val dots = Path()
        val n = (size.width * size.height / 900f).toInt().coerceAtMost(6000)
        repeat(n) {
            val x = r.nextFloat() * size.width
            val y = r.nextFloat() * size.height
            val s = 0.6f + r.nextFloat() * 1.2f
            dots.addRect(Rect(x, y, x + s, y + s))
        }
        val vignette = Brush.radialGradient(
            0f to Color.Transparent, 0.7f to Color.Transparent,
            1f to (if (dark) Color.Black.copy(alpha = 0.35f) else Color(0xFF6B5A3A).copy(alpha = 0.10f)),
            center = Offset(size.width / 2f, size.height / 2f), radius = max(size.width, size.height) * 0.75f,
        )
        onDrawBehind {
            drawPath(dots, ink.copy(alpha = if (dark) 0.05f else 0.06f))
            val gx = size.width / 3f
            for (i in 1..2) drawRect(line.copy(alpha = 0.10f), Offset(gx * i, 0f), Size(1f, size.height))
            drawRect(vignette)
        }
    })
}

/** Synthwave dusk: vertical gradient, horizon glow, perspective grid scrolling toward the viewer. */
@Composable
private fun NeonBackdrop(t: Float) {
    val bg = C.bg
    val line = C.line
    val hot = C.hot
    val accent = C.amber
    Spacer(Modifier.fillMaxSize().drawBehind {
        val h = size.height
        val w = size.width
        val horizon = h * 0.68f
        drawRect(Brush.verticalGradient(0f to bg, 0.62f to lerp(bg, line, 0.55f), 0.68f to lerp(bg, hot, 0.25f), 1f to bg))
        // horizon glow
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.5f to hot.copy(alpha = 0.35f), 1f to Color.Transparent,
                startY = horizon - 40f, endY = horizon + 40f),
            topLeft = Offset(0f, horizon - 40f), size = Size(w, 80f),
        )
        // grid: horizontal lines spaced by perspective, scrolling
        val gridC = hot.copy(alpha = 0.32f)
        val rows = 14
        for (i in 0 until rows) {
            val z = ((i + t) / rows)
            val y = horizon + (h - horizon) * z * z
            drawRect(gridC.copy(alpha = 0.10f + 0.32f * z), Offset(0f, y), Size(w, 1.5f))
        }
        // vertical lines converging to the vanishing point
        val vp = Offset(w / 2f, horizon)
        val cols = 18
        for (i in -cols..cols) {
            val xb = w / 2f + i * (w / 7f)
            drawLine(gridC, vp, Offset(xb, h), strokeWidth = 1.2f)
        }
        // a faint striped sun, clipped at the horizon
        clipRect(bottom = horizon) {
            val r = w * 0.22f
            val c = Offset(w / 2f, horizon - r * 0.35f)
            drawCircle(Brush.verticalGradient(0f to accent.copy(alpha = 0.35f), 1f to hot.copy(alpha = 0.30f),
                startY = c.y - r, endY = c.y + r), r, c)
            var y = c.y
            var gap = 3f
            while (y < c.y + r) {
                drawRect(bg.copy(alpha = 0.9f), Offset(c.x - r, y), Size(r * 2, gap))
                y += 10f + gap * 1.4f
                gap += 1.5f
            }
        }
        drawRect(bg, Offset(0f, horizon), Size(w, 2f))
    })
}

/** Punk print: halftone dots fading from the top-right corner + a diagonal colour band near the bottom. */
@Composable
private fun ComicBackdrop() {
    val dot = C.hot
    val band = C.white
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val step = 14.dp.toPx()
        val reach = hypot(size.width, size.height * 0.6f)
        val dots = Path()
        var y = 0f
        var row = 0
        while (y < size.height * 0.6f) {
            var x = if (row % 2 == 0) 0f else step / 2f
            while (x < size.width) {
                val d = hypot(size.width - x, y) / reach
                val r = (1f - d * 1.25f).coerceIn(0f, 1f) * step * 0.42f
                if (r > 0.7f) dots.addOval(Rect(Offset(x, y), r))
                x += step
            }
            y += step * 0.87f
            row++
        }
        val h = size.height
        val w = size.width
        val slash = Path().apply {
            moveTo(0f, h * 0.80f); lineTo(w, h * 0.70f); lineTo(w, h * 0.76f); lineTo(0f, h * 0.86f); close()
        }
        onDrawBehind {
            drawPath(dots, dot.copy(alpha = 0.18f))
            drawPath(slash, band.copy(alpha = 0.07f))
        }
    })
}

/** Handheld LCD: faint pixel grid over everything. */
@Composable
private fun LcdMatrix() {
    val grid = C.line.copy(alpha = 0.07f)
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val step = 3.dp.toPx()
        val p = Path()
        var x = 0f
        while (x < size.width) { p.addRect(Rect(x, 0f, x + 1f, size.height)); x += step }
        var y = 0f
        while (y < size.height) { p.addRect(Rect(0f, y, size.width, y + 1f)); y += step }
        onDrawBehind { drawPath(p, grid) }
    })
}

/** 0..1 ping-pong used by "breathing" elements. */
fun breathe(t: Float): Float = ((sin(t * 2 * PI) + 1) / 2).toFloat()
