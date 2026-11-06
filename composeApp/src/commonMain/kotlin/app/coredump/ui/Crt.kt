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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * CRT glass over the whole app:
 *  - scanlines (1px dark every 3px)
 *  - slow refresh beam (9 s loop)
 *  - vignette
 *  - phosphor flicker + rare voltage surge (every 45–90 s)
 *  - triggered glitch tear, screen shake, and flash (via [Fx])
 */
@Composable
fun CrtScreen(look: Look, fx: Fx, content: @Composable BoxScope.() -> Unit) {
    val motion = !look.reduceMotion

    var flicker by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(look.flicker, motion) {
        if (!look.flicker || !motion) { flicker = 1f; return@LaunchedEffect }
        while (isActive) {
            flicker = 0.975f + Random.nextFloat() * 0.025f
            delay(60L + Random.nextLong(60))
        }
    }

    val surge = remember { Animatable(0f) }
    LaunchedEffect(motion) {
        if (!motion) return@LaunchedEffect
        while (isActive) {
            delay(45_000L + Random.nextLong(45_000))
            surge.animateTo(1f, tween(120))
            surge.animateTo(0f, tween(380))
        }
    }

    val beamT = rememberInfiniteTransition()
    val beam by beamT.animateFloat(
        initialValue = -0.2f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
    )

    // glitch: tear offset + bars for ~180 ms
    var glitchSeed by remember { mutableIntStateOf(0) }
    var glitchOn by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(fx.glitchTick) {
        if (fx.glitchTick == 0 || !motion) return@LaunchedEffect
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
            flash.snapTo(if (motion) 0.85f else 0.35f)
            flash.animateTo(0f, tween(650))
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = flicker
                val s = shake.value
                if (s > 0f) {
                    val p = fx.shakePower
                    translationX = sin(s * 38f) * 14f * s * p
                    translationY = sin(s * 29f + 1f) * 6f * s * p
                }
                if (glitchOn > 0f) {
                    val r = Random(glitchSeed)
                    translationX += (r.nextFloat() - 0.5f) * 18f
                }
            },
            content = content,
        )

        // glitch tear bars
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

        // static glass: scanlines + vignette
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val lines = Path()
                if (look.scanlines) {
                    val step = 3.dp.toPx().coerceAtLeast(3f)
                    var y = 0f
                    while (y < size.height) {
                        lines.addRect(androidx.compose.ui.geometry.Rect(0f, y, size.width, y + step / 3f))
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
                    if (look.scanlines) drawPath(lines, Color.Black.copy(alpha = 0.28f))
                    drawRect(vignette)
                }
            }
        )

        // moving beam + surge + flash
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                if (motion) {
                    val bh = size.height * 0.16f
                    val top = beam * size.height - bh / 2f
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to C.amber.copy(alpha = 0.045f),
                            1f to Color.Transparent,
                            startY = top, endY = top + bh,
                        ),
                        topLeft = Offset(0f, top), size = Size(size.width, bh),
                    )
                }
                val sv = surge.value
                if (sv > 0f) drawRect(C.hot.copy(alpha = 0.07f * sv))
                val fv = flash.value
                if (fv > 0f) drawRect(fx.flashColor.copy(alpha = fv))
            }
        )
    }
}

/** 0..1 ping-pong used by "breathing" elements. */
fun breathe(t: Float): Float = ((sin(t * 2 * PI) + 1) / 2).toFloat()
