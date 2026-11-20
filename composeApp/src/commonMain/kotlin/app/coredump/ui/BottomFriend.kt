package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/**
 * Easter egg: scroll the theme screen all the way down and a little friend — Dumpy, the app's core — peeks up from
 * the bottom edge with a line in the look's own voice. Tap it: it hops, says something else (and, the third time,
 * gives away another secret), and every tap after sends a heart. It sinks away again when you scroll back up.
 */
@Composable
fun BottomFriend(scroll: ScrollState, modifier: Modifier = Modifier) {
    val atEnd = scroll.maxValue > 0 && scroll.value >= scroll.maxValue - 6
    val rise = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    var taps by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    LaunchedEffect(atEnd) {
        if (atEnd) { delay(500); Sfx.play(Cue.FETCHED); rise.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f)) }
        else rise.animateTo(0f, tween(220))
    }
    if (rise.value <= 0.01f && !atEnd) return
    val lines = remember(Th.style) { friendLines(Th.style) }
    val line = lines[taps.coerceAtMost(lines.lastIndex)]
    Column(
        modifier.graphicsLayer {
            translationY = (1f - rise.value) * 220f - hop.value * 60f
            alpha = rise.value.coerceIn(0f, 1f)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TText(line, Modifier.widthIn(max = 260.dp).box(Th.style, C.hot, C.panel).padding(horizontal = 12.dp, vertical = 8.dp),
            size = 12.sp, color = C.amber, align = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Dumpy(taps, Modifier.size(84.dp).clickable(remember { MutableInteractionSource() }, null) {
            taps++
            haptic(true)
            Sfx.play(Cue.HIT)
            scope.launch { hop.snapTo(0f); hop.animateTo(1f, tween(140)); hop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
            if (taps >= lines.size) Eggs.play(Eggs.Kind.LOVE)
        })
    }
}

/** What Dumpy says, by look: a hello, then a secret, then another, then only love. */
private fun friendLines(style: Style): List<String> {
    val hello = when (style) {
        Style.TERMINAL -> "> EOF reached. nothing down here but me. hi."
        Style.PAPER -> "You've read to the very last line. Thank you, dear reader."
        Style.NEON -> "PLAYER 2 HAS ENTERED THE BOTTOM"
        Style.CLEAN -> "You made it all the way down! 🎉"
        Style.PIXEL -> "YOU FOUND THE SECRET LEVEL!"
        Style.COMIC -> "MEANWHILE, AT THE BOTTOM OF THE PAGE…"
        Style.ABYSS -> "you've reached the sea floor. it's quiet down here."
        Style.METRO -> "END OF THE LINE. all change, please."
        Style.JACKPOT -> "the house always scrolls last."
        Style.CHAT -> "seen ✓✓ … you scrolled all the way down? hi 👋"
        Style.DESKTOP -> "Scroll complete. 0 errors. 1 friend found."
        Style.ORACLE -> "the cards foretold you'd scroll this far."
    }
    return listOf(
        hello,
        "psst: tap the logo on the menu five times.",
        "and in the terminal look… hold the logo. hold it.",
        "one more: answer \"core dump\" when asked to name a game.",
        "♥",
    )
}

/** Dumpy: a small glowing core with eyes that blink and follow, a smile, and arms that wave. */
@Composable
private fun Dumpy(taps: Int, modifier: Modifier) {
    val motion = !LocalLook.current.reduceMotion
    val t by rememberClock(motion, fps = 30)
    val body = C.hot
    val glow = C.amber
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c = Offset(w / 2, h * 0.58f + sin(t * 2.4f) * h * 0.02f)
        val r = w * 0.32f
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), glow.copy(alpha = 0f)), c, r * 1.6f), r * 1.6f, c)
        // waving arms
        val wave = sin(t * 6f) * 0.5f
        drawLine(body, Offset(c.x - r * 0.9f, c.y), Offset(c.x - r * 1.45f, c.y - r * (0.7f + wave)), r * 0.16f, StrokeCap.Round)
        drawLine(body, Offset(c.x + r * 0.9f, c.y), Offset(c.x + r * 1.4f, c.y + r * 0.3f), r * 0.16f, StrokeCap.Round)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), body, body.copy(alpha = 0.85f)), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.4f), r, c)
        drawCircle(Color.Black.copy(alpha = 0.25f), r, c, style = Stroke(r * 0.06f))
        // eyes: blink every few seconds, look up at the text
        val blink = ((t % 3.7f) < 0.12f)
        for (sx in listOf(-0.35f, 0.35f)) {
            val e = Offset(c.x + r * sx, c.y - r * 0.15f)
            if (blink) drawLine(Color.Black, Offset(e.x - r * 0.16f, e.y), Offset(e.x + r * 0.16f, e.y), r * 0.08f, StrokeCap.Round)
            else {
                drawOval(Color.White, Offset(e.x - r * 0.17f, e.y - r * 0.22f), Size(r * 0.34f, r * 0.44f))
                drawCircle(Color.Black, r * 0.1f, Offset(e.x + sin(t * 0.9f) * r * 0.04f, e.y - r * 0.08f))
            }
        }
        // smile, wider with every tap
        val wide = (0.25f + 0.07f * taps).coerceAtMost(0.5f)
        val m = Path().apply {
            moveTo(c.x - r * wide, c.y + r * 0.25f)
            quadraticTo(c.x, c.y + r * (0.45f + wide * 0.6f), c.x + r * wide, c.y + r * 0.25f)
        }
        drawPath(m, Color.Black, style = Stroke(r * 0.08f, cap = StrokeCap.Round))
        // blush once you've been nice to it
        if (taps > 0) for (sx in listOf(-0.62f, 0.62f)) drawCircle(Color(0xFFFF7A9A).copy(alpha = 0.45f), r * 0.12f, Offset(c.x + r * sx, c.y + r * 0.18f))
        // a tiny floating heart now and then
        val q = (t * 0.45f) % 1f
        if (taps > 0 && q < 0.6f) {
            val hc = Offset(c.x + r * 1.2f, c.y - r * 1.2f - q * r)
            val s = r * 0.18f
            val heart = Path().apply {
                moveTo(hc.x, hc.y + s * 0.9f)
                cubicTo(hc.x - s * 1.4f, hc.y - s * 0.1f, hc.x - s * 0.7f, hc.y - s * 1.2f, hc.x, hc.y - s * 0.4f)
                cubicTo(hc.x + s * 0.7f, hc.y - s * 1.2f, hc.x + s * 1.4f, hc.y - s * 0.1f, hc.x, hc.y + s * 0.9f)
                close()
            }
            drawPath(heart, Color(0xFFFF4D6D).copy(alpha = 1f - abs(q - 0.3f) / 0.3f * 0.8f))
        }
    }
}
