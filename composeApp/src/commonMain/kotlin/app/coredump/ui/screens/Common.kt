package app.coredump.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.ui.C
import app.coredump.ui.Divider
import app.coredump.ui.LocalDisplay
import app.coredump.ui.LocalLook
import app.coredump.ui.Style
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.rememberHaptic
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Top bar: back arrow, title, handle. */
@Composable
fun Header(model: AppModel, title: String, onBack: (() -> Unit)? = { model.back() }) {
    val haptic = rememberHaptic()
    val style = Th.style
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TText(
                    when (style) { Style.TERMINAL, Style.PIXEL -> "◂ "; else -> "←" },
                    Modifier.clickable(remember { MutableInteractionSource() }, null) { haptic(false); onBack() }
                        .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                    size = if (style == Style.CLEAN || style == Style.NEON) 20.sp else 16.sp, weight = FontWeight.Bold,
                )
            }
            TText(
                if (style == Style.PAPER || style == Style.PIXEL) title.uppercase() else title,
                Modifier.weight(1f),
                size = if (style == Style.CLEAN) 17.sp else 13.sp,
                weight = FontWeight.Bold,
                spacing = if (style == Style.CLEAN) 0.sp else 2.sp,
                maxLines = 1,
            )
            TText(if (style == Style.TERMINAL) "uid=${model.settings.handle.take(10)}" else model.settings.handle.take(10),
                size = 11.sp, color = C.dim, maxLines = 1)
        }
        if (style != Style.CLEAN) Divider(Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
fun Frame(model: AppModel, title: String, onBack: (() -> Unit)? = { model.back() }, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(model, title, onBack)
        content()
    }
}

/** The app name, drawn the way each theme would draw a masthead. */
@Composable
fun AppTitle(size: TextUnit = 40.sp) {
    when (Th.style) {
        Style.TERMINAL -> GlitchTitle("CORE_DUMP", size)
        Style.PAPER -> Column {
            Box(Modifier.fillMaxWidth().height(3.dp).background(C.amber))
            Spacer(Modifier.height(2.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.amber))
            TText("The Core Dump", Modifier.padding(vertical = 6.dp), size = size * 1.05f, weight = FontWeight.Bold, color = C.amber)
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.amber))
        }
        Style.NEON -> {
            val t = rememberInfiniteTransition()
            val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse))
            val look = LocalLook.current
            BasicText(
                "CORE DUMP",
                style = TextStyle(
                    brush = Brush.linearGradient(
                        listOf(C.hot, C.amber, C.white),
                        start = Offset(0f + shift * 200f, 0f), end = Offset(600f + shift * 200f, 120f),
                    ),
                    fontFamily = LocalDisplay.current, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                    fontSize = size, letterSpacing = 1.sp,
                    shadow = if (look.glow > 0f) Shadow(C.hot.copy(alpha = 0.8f), Offset.Zero, 22f * look.glow) else null,
                ),
            )
        }
        Style.CLEAN -> TText("Core Dump", size = size * 0.85f, weight = FontWeight.Bold, color = C.amber)
        Style.PIXEL -> TText("CORE DUMP", size = size * 0.8f, weight = FontWeight.Bold, color = C.amber)
    }
}

/** Big title with chromatic glitch bursts every few seconds (terminal). */
@Composable
fun GlitchTitle(text: String, size: TextUnit = 40.sp) {
    val motion = !LocalLook.current.reduceMotion
    var burst by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf(0) }
    LaunchedEffect(motion) {
        if (!motion) return@LaunchedEffect
        while (true) {
            delay(2200L + Random.nextLong(3800))
            repeat(3 + Random.nextInt(4)) { burst = true; seed = Random.nextInt(); delay(45) }
            burst = false
        }
    }
    Box {
        if (burst) {
            val r = Random(seed)
            TText(text, Modifier.offset(x = (-2 - r.nextInt(4)).dp, y = 1.dp), size = size, color = C.blood.copy(alpha = 0.7f), weight = FontWeight.Bold, glow = 0f)
            TText(text, Modifier.offset(x = (2 + r.nextInt(4)).dp, y = (-1).dp), size = size, color = C.hot.copy(alpha = 0.45f), weight = FontWeight.Bold, glow = 0f)
        }
        TText(if (burst) corrupt(text, seed) else text, size = size, weight = FontWeight.Bold, glow = 1.6f, color = C.amber)
    }
}

private fun corrupt(t: String, seed: Int): String {
    val r = Random(seed)
    return t.map { c -> if (c != ' ' && r.nextFloat() < 0.18f) "▓▒░#%&@$"[r.nextInt(8)] else c }.joinToString("")
}

fun fmtSeconds(s: Int): String = if (s == 0) "∞" else "${s}s"
