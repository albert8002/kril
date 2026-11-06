package app.coredump.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.coredump.core.Tier
import app.coredump.res.Res
import app.coredump.res.dejavu_mono
import app.coredump.res.dejavu_mono_bold
import org.jetbrains.compose.resources.Font

/** Amber phosphor palette. */
object C {
    val bg = Color(0xFF0A0603)
    val panel = Color(0xFF120B04)
    val line = Color(0xFF3A2806)
    val ghost = Color(0xFF241804)
    val dim = Color(0xFF8C6100)
    val amber = Color(0xFFFFB000)
    val hot = Color(0xFFFFD58A)
    val white = Color(0xFFFFF3D6)
    val blood = Color(0xFFFF4122)
    val bloodDim = Color(0xFF5C1408)
}

fun tierColor(t: Tier): Color = when (t) {
    Tier.TIMEOUT, Tier.SEGFAULT -> C.blood
    Tier.HELLO_WORLD, Tier.COPYPASTA -> C.dim
    Tier.LEGACY -> C.amber
    Tier.MEMORY_LEAK -> C.amber
    Tier.HEISENBUG -> C.hot
    Tier.ZERO_DAY -> C.white
}

/** Global screen effects, triggered from anywhere, animated by CrtScreen. */
@Stable
class Fx {
    var glitchTick by mutableIntStateOf(0)
    var shakeTick by mutableIntStateOf(0)
    var flashTick by mutableIntStateOf(0)
    var flashColor by mutableStateOf(C.amber)
    var shakePower by mutableStateOf(1f)

    fun glitch() { glitchTick++ }
    fun shake(power: Float = 1f) { shakePower = power; shakeTick++ }
    fun flash(color: Color) { flashColor = color; flashTick++ }
}

/** Visual options from settings. glow: multiplier 0..1.5. */
data class Look(
    val glow: Float = 1f,
    val scanlines: Boolean = true,
    val flicker: Boolean = true,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
)

val LocalMono = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }
val LocalLook = staticCompositionLocalOf { Look() }
val LocalFx = staticCompositionLocalOf { Fx() }

@Composable
fun monoFamily(): FontFamily = FontFamily(
    Font(Res.font.dejavu_mono, FontWeight.Normal),
    Font(Res.font.dejavu_mono_bold, FontWeight.Bold),
)

@Composable
fun termStyle(
    size: TextUnit = 14.sp,
    color: Color = C.amber,
    weight: FontWeight = FontWeight.Normal,
    glow: Float = 1f,
    align: TextAlign = TextAlign.Start,
    spacing: TextUnit = 0.sp,
    decoration: TextDecoration? = null,
): TextStyle {
    val g = LocalLook.current.glow * glow
    return TextStyle(
        color = color,
        fontSize = size,
        fontFamily = LocalMono.current,
        fontWeight = weight,
        textAlign = align,
        letterSpacing = spacing,
        textDecoration = decoration,
        shadow = if (g > 0.01f) Shadow(color.copy(alpha = 0.6f), Offset.Zero, 12f * g) else null,
    )
}

/** Terminal text with phosphor glow. */
@Composable
fun TText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = C.amber,
    weight: FontWeight = FontWeight.Normal,
    glow: Float = 1f,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
    spacing: TextUnit = 0.sp,
    decoration: TextDecoration? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = termStyle(size, color, weight, glow, align, spacing, decoration),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun TText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = C.amber,
    glow: Float = 1f,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = termStyle(size, color, FontWeight.Normal, glow),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

fun group(n: Int): String {
    val s = n.toString()
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0 && c != '-') sb.append(',')
        sb.append(c)
    }
    return sb.toString()
}
