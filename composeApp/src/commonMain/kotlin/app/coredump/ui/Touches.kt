package app.coredump.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.coredump.platform.Platform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Reports the answer field's position to [Fx] so the theme touches know where to draw. */
fun Modifier.reportField(fx: Fx): Modifier = onGloballyPositioned { c ->
    val r = c.boundsInRoot()
    if (r != fx.field) fx.field = r
}

/** at: the field's bounds when the touch started (root px), so it can finish after the field is gone. */
private class Touch(val send: Boolean, val born: Long, val a: Float, val b: Float, val c: Float, val at: Rect)

/** Life of a touch (ms) per theme; send effects last a little longer. */
private fun lifeOf(style: Style, send: Boolean): Long = when (style) {
    Style.ABYSS, Style.ORACLE -> if (send) 1400 else 1100
    Style.METRO, Style.CHAT, Style.DESKTOP -> if (send) 700 else 0
    Style.CLEAN -> if (send) 650 else 0
    else -> if (send) 700 else 420
}

/**
 * Small theme-shaped motions around the answer field: a bubble rises from the field in the deep sea, embers drift up
 * at the tarot table, ink specks in the gazette, phosphor sparks on the terminal, pixel sparks, comic tick-lines,
 * glints on the casino rim. Sending an answer gets its own move: a sonar ring, a train along the field, a bubble
 * flying up into the chat, a window shrinking into the field, coins hopping, a stamp ring, a spotlight sweep…
 * Drawn above the screen, never touched by input; nothing at all with reduce-motion on.
 */
@Composable
fun ThemeTouches(fx: Fx, modifier: Modifier = Modifier) {
    if (LocalLook.current.reduceMotion) return
    val style = Th.style
    val touches = remember { mutableStateListOf<Touch>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val clock = remember { mutableLongStateOf(Platform.epochMillis()) }
    fun spawn(send: Boolean) {
        val at = if (send) fx.sentField else fx.field
        if (lifeOf(style, send) <= 0L || at == Rect.Zero) return
        if (touches.size > 24) touches.removeAt(0)
        touches += Touch(send, Platform.epochMillis(), Random.nextFloat(), Random.nextFloat(), Random.nextFloat(), at)
    }
    LaunchedEffect(fx.keyTick) { if (fx.keyTick > 0) spawn(false) }
    LaunchedEffect(fx.sendTick) { if (fx.sendTick > 0) spawn(true) }
    // One frame loop for the screen's lifetime: it sleeps while nothing is alive and wakes on the next touch.
    // (A loop keyed on "anything alive" could stop for good when the last touch expired and a new one came in the
    // same frame: the new touch then never aged and stayed on screen like a stuck cursor ghost.)
    LaunchedEffect(style) {
        while (true) {
            if (touches.isEmpty()) snapshotFlow { touches.size }.first { it > 0 }
            withFrameMillis { }
            val now = Platform.epochMillis()
            clock.longValue = now
            touches.removeAll { now - it.born > lifeOf(style, it.send) }
        }
    }
    val hot = C.hot
    val amber = C.amber
    val white = C.white
    val line = C.line
    val bg = C.bg
    Canvas(modifier.onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        if (touches.isEmpty()) return@Canvas
        val now = clock.longValue
        for (t in touches) {
            val f = t.at
            val r = Rect(f.left - origin.x, f.top - origin.y, f.right - origin.x, f.bottom - origin.y)
            val life = lifeOf(style, t.send).toFloat()
            val p = ((now - t.born) / life).coerceIn(0f, 1f)
            draw(style, t, p, r, hot, amber, white, line, bg)
        }
    }
}

private fun DrawScope.draw(style: Style, t: Touch, p: Float, r: Rect, hot: Color, amber: Color, white: Color, line: Color, bg: Color) {
    val fade = 1f - p
    val dp = 1.dp.toPx()
    // where along the field a key touch appears: towards the right end (where the text grows)
    val kx = r.left + r.width * (0.35f + 0.6f * t.a)
    when (style) {
        Style.ABYSS -> if (!t.send) {
            val x = kx + sin((p * 3f + t.b) * 2f * PI.toFloat()) * 5 * dp
            val y = r.top - p * 70 * dp
            drawCircle(hot.copy(alpha = 0.55f * fade), (2f + 2.5f * t.c) * dp, Offset(x, y), style = Stroke(dp))
        } else {
            for (i in 0 until 3) {
                val q = (p * 1.3f - i * 0.15f).coerceIn(0f, 1f)
                if (q <= 0f || q >= 1f) continue
                drawCircle(hot.copy(alpha = 0.35f * (1f - q)), r.height * 0.5f + q * r.width * 0.5f, r.center, style = Stroke(1.2f * dp))
            }
        }
        Style.ORACLE -> if (!t.send) {
            val x = kx + sin((p * 2f + t.b) * 2f * PI.toFloat()) * 8 * dp
            val y = r.top - p * 60 * dp
            drawCircle(hot.copy(alpha = 0.7f * fade * (0.6f + 0.4f * sin(p * 12f))), (1.2f + t.c) * dp, Offset(x, y))
        } else {
            for (i in 0 until 6) {
                val ang = i * PI.toFloat() / 3f + p
                val d = (1f - p) * r.width * 0.45f + 6 * dp
                star(Offset(r.center.x + cos(ang) * d, r.center.y + sin(ang) * d * 0.4f), (3f + 2f * fade) * dp, hot.copy(alpha = fade))
            }
        }
        Style.TERMINAL -> if (!t.send) {
            drawRect(hot.copy(alpha = 0.5f * fade * fade), Offset(kx, r.top + r.height * 0.25f), Size(6 * dp, r.height * 0.5f))
        } else {
            val x = r.left + r.width * p
            drawRect(hot.copy(alpha = 0.35f * fade), Offset(x - 14 * dp, r.top), Size(14 * dp, r.height))
        }
        Style.PAPER -> if (!t.send) {
            drawCircle(amber.copy(alpha = 0.45f * fade), (0.8f + 1.4f * t.c) * dp, Offset(kx + (t.b - 0.5f) * 20 * dp, r.bottom + (4 + 10 * t.b) * dp))
        } else {
            val s = 1.5f - 0.5f * min(1f, p * 4f)
            val c = Offset(r.right - 26 * dp, r.center.y)
            drawCircle(hot.copy(alpha = 0.6f * fade), 16 * dp * s, c, style = Stroke(2 * dp))
            drawCircle(hot.copy(alpha = 0.4f * fade), 12 * dp * s, c, style = Stroke(dp))
        }
        Style.NEON -> if (!t.send) {
            val ang = -PI.toFloat() / 2f + (t.b - 0.5f) * 1.6f
            val d = 6 * dp + p * 26 * dp
            val o = Offset(kx, r.top)
            drawLine(hot.copy(alpha = fade), o + Offset(cos(ang), sin(ang)) * d, o + Offset(cos(ang), sin(ang)) * (d + 6 * dp), 1.5f * dp)
        } else {
            val g = 4 * dp + p * 18 * dp
            drawRoundRect(hot.copy(alpha = 0.5f * fade), Offset(r.left - g, r.top - g), Size(r.width + 2 * g, r.height + 2 * g),
                CornerRadius(r.height / 2f + g), style = Stroke(1.5f * dp))
        }
        Style.CLEAN -> if (t.send) {
            val x = r.left - r.width * 0.3f + r.width * 1.6f * p
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color.Transparent, white.copy(alpha = 0.35f * fade), Color.Transparent), startX = x - 40 * dp, endX = x + 40 * dp),
                Offset(x - 40 * dp, r.top), Size(80 * dp, r.height))
        }
        Style.PIXEL -> {
            val g = 3 * dp
            val step = (p * 5f).toInt() / 5f
            if (!t.send) {
                drawRect(amber.copy(alpha = if (p < 0.8f) 1f else 0f), Offset(((kx) / g).toInt() * g, ((r.top - step * 24 * dp) / g).toInt() * g), Size(g, g))
            } else for (i in 0 until 4) {
                val ang = i * PI.toFloat() / 2f + PI.toFloat() / 4f
                val d = step * 28 * dp
                val c = Offset(r.right - 20 * dp, r.center.y)
                drawRect(amber, Offset(((c.x + cos(ang) * d) / g).toInt() * g, ((c.y + sin(ang) * d) / g).toInt() * g), Size(g * 2, g * 2))
            }
        }
        Style.COMIC -> if (!t.send) {
            val o = Offset(kx, r.top - 4 * dp)
            for (i in -1..1) {
                val ang = -PI.toFloat() / 2f + i * 0.5f
                drawLine(line.copy(alpha = fade), o + Offset(cos(ang), sin(ang)) * (4 * dp), o + Offset(cos(ang), sin(ang)) * (4 * dp + 8 * dp * (0.4f + p)),
                    2 * dp)
            }
        } else {
            val c = Offset(r.right - 10 * dp, r.top)
            val s = (8 + 22 * min(1f, p * 3f)) * dp
            val burst = Path().apply {
                for (i in 0 until 16) {
                    val ang = i * PI.toFloat() / 8f
                    val rr = if (i % 2 == 0) s else s * 0.55f
                    val pt = Offset(c.x + cos(ang) * rr, c.y + sin(ang) * rr)
                    if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                }
                close()
            }
            drawPath(burst, hot.copy(alpha = fade))
            drawPath(burst, line.copy(alpha = fade), style = Stroke(2 * dp))
        }
        Style.METRO -> if (t.send) {
            // a little train runs along the bottom edge of the field
            val w = 34 * dp
            val x = r.left - w + (r.width + w) * androidx.compose.animation.core.FastOutSlowInEasing.transform(p)
            val y = r.bottom + 4 * dp
            drawRoundRect(hot.copy(alpha = fade), Offset(x, y), Size(w, 7 * dp), CornerRadius(3 * dp))
            for (i in 0 until 3) drawRect(bg.copy(alpha = fade), Offset(x + 5 * dp + i * 9 * dp, y + 2 * dp), Size(5 * dp, 2.5f * dp))
        }
        Style.JACKPOT -> if (!t.send) {
            star(Offset(kx, if (t.b > 0.5f) r.top else r.bottom), (2f + 4f * sin(p * PI.toFloat())) * dp, white.copy(alpha = fade))
        } else for (i in 0 until 3) {
            val x = r.right - (24 + i * 16) * dp
            val hop = sin((p * 1.15f - i * 0.08f).coerceIn(0f, 1f) * PI.toFloat())
            val y = r.top - hop * 34 * dp
            val sq = kotlin.math.abs(cos((p * 3f + i) * PI.toFloat())).coerceAtLeast(0.2f)
            drawOval(Color(0xFFFFD45A).copy(alpha = fade), Offset(x - 5 * dp * sq, y - 5 * dp), Size(10 * dp * sq, 10 * dp))
        }
        Style.CHAT -> if (t.send) {
            // the message lifts off the composer into the thread
            val w = r.width * 0.45f
            val h = 26 * dp
            val x = r.right - w
            val y = r.top - h - p * 90 * dp
            drawRoundRect(hot.copy(alpha = 0.6f * fade), Offset(x, y), Size(w, h), CornerRadius(13 * dp))
        }
        Style.DESKTOP -> if (t.send) {
            // the answer "minimises" into the field: dotted outlines shrinking onto it
            for (i in 0 until 3) {
                val q = (p * 1.3f - i * 0.12f).coerceIn(0f, 1f)
                if (q <= 0f || q >= 1f) continue
                val g = (1f - q) * 60 * dp
                drawRect(amber.copy(alpha = 0.8f), Offset(r.left - g, r.top - g), Size(r.width + 2 * g, r.height + 2 * g),
                    style = Stroke(dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * dp, 2 * dp))))
            }
        }
    }
}

private fun DrawScope.star(c: Offset, s: Float, color: Color) {
    if (s <= 0.3f) return
    val p = Path().apply {
        moveTo(c.x, c.y - s); quadraticTo(c.x, c.y, c.x + s, c.y); quadraticTo(c.x, c.y, c.x, c.y + s)
        quadraticTo(c.x, c.y, c.x - s, c.y); quadraticTo(c.x, c.y, c.x, c.y - s); close()
    }
    drawPath(p, color)
}
