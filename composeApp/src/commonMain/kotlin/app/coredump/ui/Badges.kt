package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.core.Rank
import app.coredump.core.Tier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ------------------------------------------------------------------ shared drawing

/** Outline of a badge in the active style (pill, rounded, square, slanted). */
private fun DrawScope.badgePath(style: Style, pill: Boolean): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        when (style) {
            Style.COMIC -> {
                val k = min(h * 0.3f, 8.dp.toPx())
                moveTo(k, 0f); lineTo(w, 0f); lineTo(w - k, h); lineTo(0f, h); close()
            }
            Style.PIXEL, Style.TERMINAL -> addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
            else -> addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(if (pill) h / 2f else style.corner.toPx().coerceAtMost(h / 2f))))
        }
    }
}

/** Four-point twinkle star. */
private fun DrawScope.twinkle(c: Offset, r: Float, color: Color) {
    if (r <= 0.3f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r); quadraticTo(c.x, c.y, c.x + r, c.y); quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y); quadraticTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(p, color)
}

/** Shine band sweeping across the shape; [t] 0..1 is its position (outside 0.15..0.85 it is off-shape). */
private fun DrawScope.shine(path: Path, t: Float, alpha: Float) {
    clipPath(path) {
        val w = size.width
        val x = -w * 0.6f + (w * 2.2f) * t
        drawRect(Brush.linearGradient(
            0f to Color.Transparent, 0.45f to Color.White.copy(alpha = 0f), 0.5f to Color.White.copy(alpha = alpha),
            0.55f to Color.White.copy(alpha = 0f), 1f to Color.Transparent,
            start = Offset(x, 0f), end = Offset(x + w * 0.6f, size.height),
        ))
    }
}

private fun mid(colors: List<Color>): Color = colors[colors.size / 2]

// ------------------------------------------------------------------ tier badge

/**
 * Tier of one answer: own colours per tier, pops in, rarer tiers shine, pulse and sparkle.
 * [animate] = false for long lists (tables) where many badges are on screen.
 */
@Composable
fun TierBadge(tier: Tier, size: TextUnit = 13.sp, animate: Boolean = true) {
    val style = Th.style
    val motion = animate && !LocalLook.current.reduceMotion
    val look = tierLook(tier)
    val colors = if (style == Style.PIXEL) listOf(lerp(C.amber, C.bg, 0.15f), C.amber) else look.gradient
    val ink = if (style == Style.PIXEL) C.bg else onColor(mid(colors))
    val heat = tier.heat
    val pop = remember(tier) { Animatable(if (motion) 0.3f else 1f) }
    LaunchedEffect(tier, motion) { if (motion) pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f)) }
    val loop = rememberInfiniteTransition()
    val sweep by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(if (heat >= 5) 1600 else 2600, easing = LinearEasing)))
    val beat by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val pill = style == Style.CLEAN || style == Style.NEON
    val sparkSeed = remember(tier) { List(8) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    val shadow = Shadow(Color.Black.copy(alpha = if (ink.luminanceDark()) 0f else 0.35f), Offset(0f, 1.5f), 2f)

    Box(
        Modifier
            .graphicsLayer {
                val s = pop.value * (if (motion && heat >= 5) 1f + 0.05f * beat else 1f)
                scaleX = s; scaleY = s
                rotationZ = (1f - pop.value) * -12f
            }
            .drawWithContent {
                val path = badgePath(style, pill)
                // glow for the rarest
                if (heat >= 5 && style != Style.PIXEL) {
                    drawRoundRect(colors.last().copy(alpha = 0.25f + 0.2f * (if (motion) beat else 0.5f)),
                        Offset(-4.dp.toPx(), -4.dp.toPx()), Size(this.size.width + 8.dp.toPx(), this.size.height + 8.dp.toPx()),
                        CornerRadius(this.size.height), style = Stroke(3.dp.toPx()))
                }
                drawPath(path, Brush.linearGradient(colors, start = Offset.Zero, end = Offset(this.size.width, this.size.height)))
                if (style != Style.PIXEL) drawPath(path, Color.White.copy(alpha = 0.18f), style = Stroke(1.dp.toPx()))
                if (motion && heat >= 3) shine(path, sweep, if (heat >= 5) 0.75f else 0.5f)
                drawContent()
                if (motion && heat >= 5) {
                    val n = if (heat >= 6) 8 else 4
                    for (i in 0 until n) {
                        val (a, b, c) = sparkSeed[i]
                        val phase = ((sweep + c) % 1f)
                        val tw = sin(phase * PI).toFloat()
                        val x = this.size.width * (a * 1.2f - 0.1f)
                        val y = if (b > 0.5f) -this.size.height * 0.25f * b else this.size.height * (1f + 0.25f * b)
                        twinkle(Offset(x, y), 4.dp.toPx() * tw, Color.White.copy(alpha = 0.9f * tw))
                    }
                }
            }
            .padding(horizontal = if (style == Style.PIXEL) 8.dp else 9.dp, vertical = if (style == Style.PIXEL) 5.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(
            tier.label,
            style = termStyle(size = size, color = ink, weight = FontWeight.Bold, glow = 0f, spacing = 0.5.sp)
                .copy(shadow = shadow),
            maxLines = 1,
        )
    }
}

private fun Color.luminanceDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

// ------------------------------------------------------------------ rank badge

/** End-of-run title: a shining plaque in the rank's colours; higher ranks pulse and sparkle. */
@Composable
fun RankBadge(rank: Rank, modifier: Modifier = Modifier, size: TextUnit = 24.sp) {
    val style = Th.style
    val motion = !LocalLook.current.reduceMotion
    val colors = if (style == Style.PIXEL) listOf(lerp(C.amber, C.bg, 0.15f), C.amber) else rankGradient(rank)
    val ink = if (style == Style.PIXEL) C.bg else onColor(mid(colors))
    val level = rank.ordinal          // 0..6
    val enter = remember(rank) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(rank, motion) { if (motion) { enter.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f)) } }
    val loop = rememberInfiniteTransition()
    val sweep by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)))
    val beat by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val orbit by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)))
    val pill = style == Style.CLEAN || style == Style.NEON
    Box(
        modifier
            .graphicsLayer {
                val s = (0.4f + 0.6f * enter.value) * (if (motion && level >= 5) 1f + 0.03f * beat else 1f)
                scaleX = s; scaleY = s
                alpha = enter.value.coerceIn(0f, 1f)
                rotationZ = (1f - enter.value) * 8f + if (style == Style.COMIC) -3f else 0f
            }
            .drawWithContent {
                val path = badgePath(style, pill)
                if (level >= 4 && style != Style.PIXEL) {
                    drawRoundRect(colors.last().copy(alpha = 0.18f + 0.22f * (if (motion) beat else 0.5f)),
                        Offset(-6.dp.toPx(), -6.dp.toPx()), Size(this.size.width + 12.dp.toPx(), this.size.height + 12.dp.toPx()),
                        CornerRadius(if (pill) this.size.height else 10.dp.toPx()), style = Stroke(5.dp.toPx()))
                }
                drawPath(path, Brush.linearGradient(colors, start = Offset.Zero, end = Offset(this.size.width, this.size.height)))
                drawPath(path, Color.White.copy(alpha = 0.25f), style = Stroke(1.5.dp.toPx()))
                if (motion && level >= 1) shine(path, sweep, 0.35f + 0.08f * level)
                drawContent()
                if (motion && level >= 4) {
                    val n = 3 + level
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    for (i in 0 until n) {
                        val a = (orbit + i.toFloat() / n) * 2 * PI
                        val rx = this.size.width * 0.58f
                        val ry = this.size.height * 0.95f
                        val tw = (0.5f + 0.5f * sin(a * 3 + i)).toFloat()
                        twinkle(Offset(cx + (cos(a) * rx).toFloat(), cy + (sin(a) * ry).toFloat()), 5.dp.toPx() * tw,
                            lerp(Color.White, colors.last(), 0.3f).copy(alpha = 0.9f * tw))
                    }
                }
            }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(
            rank.title,
            style = TextStyle(
                color = ink, fontSize = size, fontWeight = FontWeight.Bold, fontFamily = LocalDisplay.current,
                letterSpacing = 1.sp, textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(alpha = if (ink.luminanceDark()) 0f else 0.4f), Offset(0f, 2f), 3f),
            ),
            maxLines = 2,
        )
    }
}

// ------------------------------------------------------------------ reward burst

/**
 * Particle burst for an accepted answer, in the tier's colours: more, faster particles and a shock ring for rarer tiers,
 * plus the points flying up. Plays once per [key].
 */
@Composable
fun RewardBurst(tier: Tier, points: Int, key: Any, modifier: Modifier = Modifier) {
    if (!tier.isValid || LocalLook.current.reduceMotion) return
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(1300, easing = LinearEasing)) }
    val heat = tier.heat
    val colors = if (Th.style == Style.PIXEL) listOf(C.amber, C.dim) else tierLook(tier).gradient + Color.White
    val parts = remember(key) {
        val n = intArrayOf(0, 12, 16, 26, 40, 60, 96)[heat.coerceIn(0, 6)]
        List(n) {
            Particle(
                angle = Random.nextFloat() * 2f * PI.toFloat(),
                speed = 0.25f + Random.nextFloat() * (0.35f + heat * 0.08f),
                size = 3f + Random.nextFloat() * (3f + heat),
                color = Random.nextInt(colors.size),
                kind = Random.nextInt(3),
                spin = Random.nextFloat() * 6f,
            )
        }
    }
    val t = p.value
    if (t >= 1f) return
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val origin = Offset(size.width / 2f, size.height * 0.42f)
            val reach = max(size.width, size.height) * 0.55f
            if (heat >= 4) {
                val r = reach * 0.9f * FastOutSlowInEasing.transform(min(1f, t * 1.6f))
                drawCircle(colors[colors.size - 2].copy(alpha = (1f - t * 1.6f).coerceIn(0f, 1f) * 0.6f), r, origin,
                    style = Stroke((10f - 8f * t).dp.toPx()))
            }
            val ease = FastOutSlowInEasing.transform(t)
            for (q in parts) {
                val d = reach * q.speed * ease
                val x = origin.x + cos(q.angle) * d
                val y = origin.y + sin(q.angle) * d + 260f * t * t        // a little gravity
                val a = (1f - t).coerceIn(0f, 1f)
                val c = colors[q.color].copy(alpha = a)
                val s = q.size.dp.toPx() * (1f - 0.4f * t)
                when (q.kind) {
                    0 -> drawCircle(c, s / 2f, Offset(x, y))
                    1 -> twinkle(Offset(x, y), s, c)
                    else -> {
                        val ang = q.spin * t * 6f
                        val dx = cos(ang) * s
                        val dy = sin(ang) * s * 0.5f
                        drawLine(c, Offset(x - dx, y - dy), Offset(x + dx, y + dy), strokeWidth = s * 0.45f)
                    }
                }
            }
        }
        if (points > 0) {
            TText(
                "+$points", Modifier.align(Alignment.Center).graphicsLayer {
                    val up = FastOutSlowInEasing.transform(min(1f, t * 1.3f))
                    translationY = -size.height * 0.0f - 140f * up
                    val s = 0.6f + 0.9f * min(1f, t * 4f) - 0.3f * t
                    scaleX = s; scaleY = s
                    alpha = (1f - (t - 0.55f).coerceAtLeast(0f) * 2.2f).coerceIn(0f, 1f)
                },
                size = (26 + heat * 4).sp, color = if (Th.style == Style.PIXEL) C.amber else tierColor(tier),
                weight = FontWeight.Bold, glow = 1.5f, align = TextAlign.Center,
            )
        }
    }
}

private class Particle(val angle: Float, val speed: Float, val size: Float, val color: Int, val kind: Int, val spin: Float)
