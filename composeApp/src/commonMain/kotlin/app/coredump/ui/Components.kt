package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

private const val GLYPHS = "▓▒░#%&$@<>/\\|01ABCDEF*+=?"
private const val ASCII_GLYPHS = "#%&$@<>/\\|01ABCDEF*+=?"

@Composable
fun rememberHaptic(): (Boolean) -> Unit {
    val h = LocalHapticFeedback.current
    val on = LocalLook.current.haptics
    return remember(h, on) {
        { strong: Boolean ->
            if (on) runCatching { h.performHapticFeedback(if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove) }
        }
    }
}

// ------------------------------------------------------------------ shapes

/**
 * Draws a themed box behind the content: fill + border, shaped by [style].
 * PIXEL: thick border with notched corners. NEON: rounded with a soft glow. Others: rounded / square stroke.
 */
fun Modifier.box(style: Style, stroke: Color, fill: Color, strokeWidth: Dp = 1.dp, glow: Boolean = false, pill: Boolean = false): Modifier =
    drawBehind { drawBox(style, stroke, fill, strokeWidth.toPx(), glow, pill) }

fun DrawScope.drawBox(style: Style, stroke: Color, fill: Color, sw: Float, glow: Boolean = false, pill: Boolean = false) {
    val w = size.width
    val h = size.height
    if (style == Style.PIXEL) {
        val b = max(3f, sw * 3f)
        if (fill.alpha > 0f) drawRect(fill, Offset(b, b), Size(w - 2 * b, h - 2 * b))
        if (stroke.alpha > 0f) {
            drawRect(stroke, Offset(b, 0f), Size(w - 2 * b, b))
            drawRect(stroke, Offset(b, h - b), Size(w - 2 * b, b))
            drawRect(stroke, Offset(0f, b), Size(b, h - 2 * b))
            drawRect(stroke, Offset(w - b, b), Size(b, h - 2 * b))
        }
        return
    }
    if (style == Style.COMIC) {
        // slanted panel; [glow] = hard drop shadow in the accent colour
        val k = minOf(h * 0.22f, 12.dp.toPx())
        fun slab(dx: Float, dy: Float) = Path().apply {
            moveTo(k + dx, dy); lineTo(w + dx, dy); lineTo(w - k + dx, h + dy); lineTo(dx, h + dy); close()
        }
        if (glow) { val o = 4.dp.toPx(); drawPath(slab(o, o), C.hot) }
        val body = slab(0f, 0f)
        if (fill.alpha > 0f) drawPath(body, fill)
        if (stroke.alpha > 0f && sw > 0f) drawPath(body, stroke, style = Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Miter))
        return
    }
    if (style == Style.DESKTOP) {
        // bevelled face; [glow] = sunken (fields, pressed buttons). stroke = text colour: default-button frame;
        // any other accent: thin inner highlight.
        val px = max(1f, 1.dp.toPx())
        val light = Th.pal.white
        val shade = Th.pal.line
        val deep = lerp(shade, Color.Black, 0.65f)
        val frame = stroke.alpha > 0f && stroke == Th.pal.amber
        val o = if (frame) px else 0f
        if (fill.alpha > 0f) drawRect(fill)
        if (frame) drawRect(stroke, style = Stroke(px * 2f))
        fun edge(tl: Color, br: Color, i: Float) {
            drawRect(tl, Offset(i, i), Size(w - 2 * i, px)); drawRect(tl, Offset(i, i), Size(px, h - 2 * i))
            drawRect(br, Offset(i, h - i - px), Size(w - 2 * i, px)); drawRect(br, Offset(w - i - px, i), Size(px, h - 2 * i))
        }
        if (glow) { edge(shade, light, o); edge(deep, Th.pal.ghost, o + px) } else { edge(light, deep, o); edge(Th.pal.ghost, shade, o + px) }
        if (!frame && stroke.alpha > 0f && stroke != shade && stroke != Color.Transparent) {
            drawRect(stroke, Offset(o + 3 * px, o + 3 * px), Size(w - 2 * o - 6 * px, h - 2 * o - 6 * px), style = Stroke(px))
        }
        return
    }
    if (style == Style.ORACLE) {
        // chamfered card frame with a fine second rule inside; [glow] = soft halo
        val k = minOf(h * 0.22f, 9.dp.toPx())
        fun oct(i: Float) = Path().apply {
            moveTo(k + i, i); lineTo(w - k - i, i); lineTo(w - i, k + i); lineTo(w - i, h - k - i)
            lineTo(w - k - i, h - i); lineTo(k + i, h - i); lineTo(i, h - k - i); lineTo(i, k + i); close()
        }
        if (glow && stroke.alpha > 0f) drawPath(oct(0f), stroke.copy(alpha = 0.12f), style = Stroke(sw * 7))
        if (fill.alpha > 0f) drawPath(oct(0f), fill)
        if (stroke.alpha > 0f && sw > 0f) {
            drawPath(oct(sw / 2), stroke, style = Stroke(sw))
            if (h > 26.dp.toPx()) drawPath(oct(sw + 3.dp.toPx()), stroke.copy(alpha = 0.4f), style = Stroke(sw * 0.6f))
        }
        return
    }
    val r = if (pill) h / 2f else style.corner.toPx()
    if (fill.alpha > 0f) drawRoundRect(fill, cornerRadius = CornerRadius(r))
    if (glow && (style == Style.NEON || style == Style.ABYSS) && stroke.alpha > 0f) {
        val soft = if (style == Style.ABYSS) 0.6f else 1f
        drawRoundRect(stroke.copy(alpha = 0.10f * soft), Offset(-sw * 2, -sw * 2), Size(w + sw * 4, h + sw * 4),
            CornerRadius(r + sw * 2), style = Stroke(sw * 6))
        drawRoundRect(stroke.copy(alpha = 0.22f * soft), cornerRadius = CornerRadius(r), style = Stroke(sw * 3))
    }
    if (style == Style.JACKPOT && stroke.alpha > 0f && sw > 0f) {
        // double gold rim; [glow] = a row of marquee bulbs on the rim
        val i = sw + 2.5.dp.toPx()
        if (w > 2 * i && h > 2 * i) drawRoundRect(stroke.copy(alpha = 0.4f), Offset(i, i), Size(w - 2 * i, h - 2 * i), CornerRadius(max(0f, r - i)), style = Stroke(sw * 0.7f))
        if (glow) {
            val step = 16.dp.toPx()
            var x = r
            while (x < w - r) {
                drawCircle(Th.pal.white.copy(alpha = 0.75f), 1.8.dp.toPx(), Offset(x, 0f))
                drawCircle(Th.pal.white.copy(alpha = 0.75f), 1.8.dp.toPx(), Offset(x, h))
                x += step
            }
        }
    }
    if (stroke.alpha > 0f && sw > 0f) {
        drawRoundRect(stroke, Offset(sw / 2, sw / 2), Size(w - sw, h - sw), CornerRadius(max(0f, r - sw / 2)), style = Stroke(sw))
    }
}

// ------------------------------------------------------------------ text effects

/** Blinking block cursor (TERMINAL / PIXEL only). */
@Composable
fun Cursor(size: TextUnit = 14.sp, color: Color = C.amber) {
    if (Th.style != Style.TERMINAL && Th.style != Style.PIXEL) return
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = { if (it < 0.5f) 0f else 1f }), RepeatMode.Restart))
    TText("█", Modifier.graphicsLayer { alpha = a }, size = size, color = color)
}

/** Types text out char by char (TERMINAL / PIXEL / NEON); fades in elsewhere. */
@Composable
fun Typewriter(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 20.sp,
    color: Color = C.amber,
    charMs: Long = 18,
    weight: FontWeight = FontWeight.Bold,
    cursor: Boolean = true,
    onDone: () -> Unit = {},
) {
    val style = Th.style
    val reduce = LocalLook.current.reduceMotion
    val typing = !reduce && style != Style.PAPER && style != Style.CLEAN
    var shown by remember(text, typing) { mutableIntStateOf(if (typing) 0 else text.length) }
    val fade = remember(text) { Animatable(if (typing || reduce) 1f else 0f) }
    LaunchedEffect(text, typing) {
        if (typing) {
            for (i in 0..text.length) { shown = i; delay(charMs + if (text.getOrNull(i) == ' ') 12 else 0) }
        } else {
            shown = text.length
            fade.animateTo(1f, tween(380))
        }
        onDone()
    }
    val block = style == Style.TERMINAL || style == Style.PIXEL
    Row(modifier.graphicsLayer { alpha = fade.value; translationY = (1f - fade.value) * 10f }) {
        TText(text.take(shown) + if (shown < text.length && block) "▌" else "", Modifier.weight(1f, fill = false),
            size = size, color = color, weight = weight)
        if (cursor && shown >= text.length) Cursor(size, color)
    }
}

/** Scrambles glyphs then resolves left to right (TERMINAL / NEON / PIXEL); rises in elsewhere. */
@Composable
fun DecodeText(
    target: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 22.sp,
    color: Color = C.amber,
    durationMs: Int = 650,
    weight: FontWeight = FontWeight.Bold,
    glow: Float = 1.2f,
) {
    val style = Th.style
    val motion = !LocalLook.current.reduceMotion
    val scrambles = motion && (style == Style.TERMINAL || style == Style.NEON || style == Style.PIXEL)
    val glyphs = if (style == Style.PIXEL) ASCII_GLYPHS else GLYPHS
    var shown by remember(target, scrambles) { mutableStateOf(if (scrambles) scramble(target, 0f, glyphs) else target) }
    val rise = remember(target) { Animatable(if (!motion || scrambles) 1f else 0f) }
    LaunchedEffect(target, scrambles) {
        if (!scrambles) { shown = target; rise.animateTo(1f, tween(420, easing = FastOutSlowInEasing)); return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            val p = ((withFrameMillis { it } - start) / durationMs.toFloat()).coerceIn(0f, 1f)
            shown = scramble(target, p, glyphs)
            if (p >= 1f) break
        }
    }
    TText(shown, modifier.graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 14f },
        size = size, color = color, weight = weight, glow = glow)
}

private fun scramble(t: String, p: Float, glyphs: String): String {
    val done = (t.length * p).toInt()
    val sb = StringBuilder(t.length)
    for (i in t.indices) {
        val c = t[i]
        sb.append(if (i < done || c == ' ') c else glyphs[Random.nextInt(glyphs.length)])
    }
    return sb.toString()
}

/** Counts up to [value] with an odometer feel. */
@Composable
fun RollingNumber(
    value: Int,
    modifier: Modifier = Modifier,
    size: TextUnit = 18.sp,
    color: Color = C.amber,
    suffix: String = "",
    durationMs: Int = 900,
    weight: FontWeight = FontWeight.Bold,
) {
    val v by animateIntAsState(value, tween(durationMs, easing = FastOutSlowInEasing))
    TText(group(v) + suffix, modifier, size = size, color = color, weight = weight)
}

// ------------------------------------------------------------------ buttons

/** Themed button. [color] tints secondary / danger buttons; primary buttons use the accent. */
@Composable
fun TButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    color: Color = C.amber,
    textSize: TextUnit = 15.sp,
) {
    val style = Th.style
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val fill by animateFloatAsState(if (pressed) 1f else if (primary && style == Style.TERMINAL) 0.12f else 0f, tween(if (pressed) 90 else 260))
    val custom = color != C.amber
    val accent = when {
        !enabled -> C.line
        custom -> color
        primary && style != Style.TERMINAL && style != Style.PIXEL && style != Style.PAPER && style != Style.DESKTOP -> C.hot
        else -> C.amber
    }
    val click = Modifier.clickable(interactionSource = src, indication = null, enabled = enabled) { haptic(primary); onClick() }

    when (style) {
        Style.TERMINAL -> {
            val textColor = lerp(accent, C.bg, fill.coerceIn(0f, 1f))
            Box(
                modifier.drawBehind {
                    drawRect(accent.copy(alpha = if (primary) 0.9f else 0.55f), style = Stroke(1.dp.toPx()))
                    if (fill > 0f) drawRect(accent, size = Size(size.width * fill, size.height))
                }.then(click).heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                TText(if (pressed) "[$label]" else "[ $label ]", size = textSize, color = textColor, weight = FontWeight.Bold,
                    glow = if (fill > 0.5f) 0f else 1f, align = TextAlign.Center)
            }
        }
        Style.PIXEL -> {
            val shadow = 4.dp
            val down = pressed || !enabled
            val o = if (down) shadow else 0.dp
            val face = if (primary) accent else C.bg
            val ink = if (primary) C.bg else accent
            val shade = C.amber
            Box(modifier.then(click), contentAlignment = Alignment.Center) {
                if (!down) Box(Modifier.matchParentSize().offset(shadow, shadow).box(style, shade, shade))
                Box(Modifier.matchParentSize().offset(o, o).box(style, accent, face))
                Box(Modifier.offset(o, o).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center) {
                    TText((if (primary) "▶ " else "") + label.uppercase(), size = textSize, color = ink, align = TextAlign.Center)
                }
            }
        }
        Style.COMIC -> {
            // slanted slab on a hard shadow; pressing pushes it into the shadow, with a small tilt
            val shadow = 5.dp
            val o by animateDpAsState(if (pressed) shadow else 0.dp, tween(70))
            val tilt by animateFloatAsState(if (pressed) -1.5f else 0f, tween(90))
            val face = when { !enabled -> C.ghost; primary -> accent; else -> C.panel }
            val ink = when { !enabled -> C.dim; primary -> onColor(accent); else -> accent }
            Box(modifier.graphicsLayer { rotationZ = tilt }.then(click), contentAlignment = Alignment.Center) {
                if (enabled) Box(Modifier.matchParentSize().offset(shadow, shadow).box(style, Color.Transparent, if (primary) C.white else C.hot))
                Box(Modifier.matchParentSize().offset(o, o).box(style, if (custom) color else C.line, face, 2.dp))
                Box(Modifier.offset(o, o).heightIn(min = 50.dp).padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    TText(label.uppercase(), size = textSize, color = ink, weight = FontWeight.Bold, spacing = 1.sp, glow = 0f, align = TextAlign.Center)
                }
            }
        }
        Style.JACKPOT -> {
            // domed casino push button on a dark base; it sinks into the base when pressed
            val depth = 4.dp
            val o by animateDpAsState(if (pressed) depth else 0.dp, tween(60))
            val face = when { !enabled -> C.ghost; primary -> accent; custom -> color; else -> C.panel }
            val ink = when { !enabled -> C.dim; primary || custom -> onColor(face); else -> C.amber }
            val rim = C.line
            Box(modifier.then(click), contentAlignment = Alignment.Center) {
                Box(Modifier.matchParentSize().offset(y = depth).drawBehind {
                    drawRoundRect(lerp(face, Color.Black, 0.5f), cornerRadius = CornerRadius(style.corner.toPx()))
                })
                Box(Modifier.matchParentSize().offset(y = o).drawBehind {
                    val r = CornerRadius(style.corner.toPx())
                    drawRoundRect(Brush.verticalGradient(listOf(lerp(face, Color.White, 0.25f), face, lerp(face, Color.Black, 0.12f))), cornerRadius = r)
                    drawRoundRect(rim, cornerRadius = r, style = Stroke(1.5.dp.toPx()))
                    drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(6.dp.toPx(), 3.dp.toPx()),
                        Size(size.width - 12.dp.toPx(), size.height * 0.32f), CornerRadius(size.height * 0.16f))
                })
                Box(Modifier.offset(y = o).heightIn(min = 50.dp).padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    TText(label.uppercase(), size = textSize, color = ink, weight = FontWeight.Bold, spacing = 1.sp, align = TextAlign.Center)
                }
            }
        }
        Style.DESKTOP -> {
            // bevelled push button: sinks (inverted bevel, label nudged 1 dp) while pressed; primary = default button frame
            val down = pressed && enabled
            val ink = if (!enabled) C.line else if (custom) color else C.amber
            val nudge = if (down) 1.dp else 0.dp
            Box(
                modifier.box(style, if (primary && enabled) C.amber else C.line, C.panel, glow = down)
                    .then(click).heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TText(label, Modifier.offset(nudge, nudge), size = textSize * 0.95f, color = ink,
                    weight = if (primary) FontWeight.Bold else FontWeight.Normal, align = TextAlign.Center)
            }
        }
        else -> {
            val filled = primary || pressed
            // QUIZ SHOW / CHAT / METRO: secondary buttons sit on a panel fill
            val soft = style == Style.CLEAN || style == Style.CHAT || style == Style.METRO
            val bgFill = when {
                filled -> accent
                soft -> C.panel
                else -> Color.Transparent
            }
            val ink = if (filled) onColor(accent) else accent
            val pill = style == Style.NEON || style == Style.ABYSS || style == Style.CHAT
            val upper = style == Style.PAPER || style == Style.METRO || style == Style.ORACLE
            val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120))
            Box(
                modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                    .box(style, when { !soft || filled -> accent; style == Style.CHAT -> Color.Transparent; else -> C.line }, bgFill,
                        strokeWidth = if (style == Style.PAPER) 1.5.dp else if (style == Style.ORACLE) 1.2.dp else 1.dp,
                        glow = style == Style.NEON || style == Style.ABYSS, pill = pill)
                    .then(click).heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                TText(
                    (if (upper) label.uppercase() else label) + if (style == Style.METRO && primary) "  →" else "",
                    size = textSize * (if (style == Style.PAPER || style == Style.ORACLE) 0.9f else 1f), color = ink,
                    weight = if (style == Style.CLEAN || style == Style.CHAT) FontWeight.SemiBold else FontWeight.Bold,
                    spacing = when (style) { Style.PAPER -> 1.5.sp; Style.ORACLE -> 2.sp; Style.NEON -> 1.sp; Style.METRO, Style.ABYSS -> 0.5.sp; else -> 0.sp },
                    glow = if (filled) 0f else 1f, align = TextAlign.Center,
                )
            }
        }
    }
}

/** Plain text link-style action. */
@Composable
fun TLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.dim, size: TextUnit = 13.sp) {
    val haptic = rememberHaptic()
    val prefix = when (Th.style) {
        Style.TERMINAL -> "› "; Style.PIXEL -> "> "; Style.COMIC -> "✦ "; Style.METRO -> "→ "; Style.JACKPOT -> "♦ "; Style.ORACLE -> "✧ "
        else -> ""
    }
    val suffix = when (Th.style) { Style.CLEAN, Style.NEON, Style.PAPER, Style.ABYSS -> "  →"; else -> "" }
    TText("$prefix$label$suffix", modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        haptic(false); onClick()
    }.padding(vertical = 6.dp), size = size, color = color,
        decoration = if (Th.style == Style.PAPER || Th.style == Style.DESKTOP) androidx.compose.ui.text.style.TextDecoration.Underline else null)
}

// ------------------------------------------------------------------ containers

/** Themed panel with a title. */
@Composable
fun Panel(
    title: String?,
    modifier: Modifier = Modifier,
    color: Color = C.line,
    titleColor: Color = C.dim,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (Th.style) {
        Style.TERMINAL -> Box(modifier.padding(top = if (title != null) 8.dp else 0.dp)) {
            Column(
                Modifier.fillMaxWidth().box(Style.TERMINAL, color, C.panel.copy(alpha = 0.55f)).padding(14.dp),
                content = content,
            )
            if (title != null) {
                TText(" $title ", Modifier.offset(x = 12.dp, y = (-8).dp).background(C.bg), size = 11.sp, color = titleColor, spacing = 1.sp)
            }
        }
        Style.PIXEL -> Box(modifier.padding(top = if (title != null) 10.dp else 0.dp)) {
            Column(Modifier.fillMaxWidth().box(Style.PIXEL, if (color == C.line) C.amber else color, C.panel).padding(16.dp), content = content)
            if (title != null) {
                TText(" ${title.uppercase()} ", Modifier.offset(x = 14.dp, y = (-10).dp).background(if (color == C.line) C.amber else color)
                    .padding(horizontal = 2.dp, vertical = 2.dp), size = 11.sp, color = C.bg)
            }
        }
        Style.PAPER -> Column(modifier.fillMaxWidth()) {
            val ruleColor = if (color == C.line) C.amber else color
            Box(Modifier.fillMaxWidth().height(2.dp).background(ruleColor))
            if (title != null) {
                Spacer(Modifier.height(5.dp))
                TText(title.uppercase(), size = 11.sp, color = if (titleColor == C.dim) C.amber else titleColor,
                    weight = FontWeight.Bold, spacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(ruleColor.copy(alpha = 0.6f)))
            }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), content = content)
        }
        Style.NEON -> Column(
            modifier.fillMaxWidth().box(Style.NEON, if (color == C.line) C.amber.copy(alpha = 0.55f) else color, C.panel.copy(alpha = 0.72f),
                strokeWidth = 1.5.dp, glow = true).padding(16.dp),
        ) {
            if (title != null) {
                TText(title.uppercase(), size = 11.sp, color = if (titleColor == C.dim) C.hot else titleColor, weight = FontWeight.Bold, spacing = 2.sp)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
        Style.CLEAN -> Column(
            modifier.fillMaxWidth().box(Style.CLEAN, if (color == C.line) C.line.copy(alpha = 0.7f) else color, C.panel).padding(16.dp),
        ) {
            if (title != null) {
                TText(title.lowercase().replaceFirstChar { it.uppercase() }, size = 12.sp,
                    color = if (titleColor == C.dim) C.dim else titleColor, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
        Style.COMIC -> Box(modifier.padding(top = if (title != null) 12.dp else 0.dp)) {
            Column(
                Modifier.fillMaxWidth().box(Style.COMIC, if (color == C.line) C.line else color, C.panel, 2.dp)
                    .padding(start = 20.dp, end = 20.dp, top = if (title != null) 20.dp else 16.dp, bottom = 16.dp),
                content = content,
            )
            if (title != null) {
                val tag = if (color == C.line) C.hot else color
                TText(title.uppercase(), Modifier.offset(x = 18.dp, y = (-10).dp).graphicsLayer { rotationZ = -3f }
                    .background(tag).padding(horizontal = 8.dp, vertical = 3.dp),
                    size = 11.sp, color = onColor(tag), weight = FontWeight.Bold, spacing = 1.5.sp, glow = 0f)
            }
        }
        Style.ABYSS -> Column(
            modifier.fillMaxWidth().box(Style.ABYSS, if (color == C.line) C.line else color, C.panel.copy(alpha = 0.62f)).padding(16.dp),
        ) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).background(if (color == C.line) C.hot else color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    TText(title.uppercase(), size = 10.sp, color = titleColor, weight = FontWeight.SemiBold, spacing = 3.sp, glow = 0f)
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
        Style.METRO -> Column(modifier.fillMaxWidth().box(Style.METRO, C.line, C.panel)) {
            if (title != null) {
                val strip = if (color == C.line) C.hot else color
                TText(title.uppercase(), Modifier.fillMaxWidth().background(strip, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp), size = 11.sp, color = onColor(strip), weight = FontWeight.Bold, spacing = 1.sp)
            }
            Column(Modifier.fillMaxWidth().padding(14.dp), content = content)
        }
        Style.JACKPOT -> Box(modifier.padding(top = if (title != null) 12.dp else 0.dp)) {
            Column(
                Modifier.fillMaxWidth().box(Style.JACKPOT, if (color == C.line) C.line else color, C.panel, 1.5.dp)
                    .padding(start = 16.dp, end = 16.dp, top = if (title != null) 22.dp else 16.dp, bottom = 16.dp),
                content = content,
            )
            if (title != null) {
                TText(title.uppercase(), Modifier.align(Alignment.TopCenter).offset(y = (-10).dp)
                    .box(Style.JACKPOT, if (color == C.line) C.line else color, C.bg, 1.5.dp).padding(horizontal = 14.dp, vertical = 4.dp),
                    size = 11.sp, color = if (titleColor == C.dim) C.hot else titleColor, weight = FontWeight.Bold, spacing = 2.sp)
            }
        }
        Style.CHAT -> Column(modifier.fillMaxWidth()) {
            if (title != null) {
                TText(title.uppercase(), Modifier.padding(start = 14.dp, bottom = 6.dp), size = 11.sp, color = titleColor,
                    weight = FontWeight.SemiBold, spacing = 0.5.sp)
            }
            Column(Modifier.fillMaxWidth().box(Style.CHAT, if (color == C.line) Color.Transparent else color, C.panel).padding(16.dp), content = content)
        }
        Style.DESKTOP -> Column(modifier.fillMaxWidth().box(Style.DESKTOP, C.line, C.panel).padding(3.dp)) {
            if (title != null) {
                DesktopTitleBar(title, color = if (color == C.line) C.hot else color)
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), content = content)
        }
        Style.ORACLE -> Box(modifier.padding(top = if (title != null) 10.dp else 0.dp)) {
            Column(
                Modifier.fillMaxWidth().box(Style.ORACLE, if (color == C.line) C.line else color, C.panel, 1.dp)
                    .padding(start = 18.dp, end = 18.dp, top = if (title != null) 20.dp else 16.dp, bottom = 16.dp),
                content = content,
            )
            if (title != null) {
                TText("✦ ${title.uppercase()} ✦", Modifier.align(Alignment.TopCenter).offset(y = (-9).dp).background(C.bg).padding(horizontal = 8.dp),
                    size = 11.sp, color = if (titleColor == C.dim) C.hot else titleColor, weight = FontWeight.Bold, spacing = 2.5.sp)
            }
        }
    }
}

/** DESKTOP window title bar: gradient strip, bold title, optional close / extra buttons at the right. */
@Composable
fun DesktopTitleBar(title: String, color: Color = C.hot, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, extra: (@Composable () -> Unit)? = null) {
    val ink = onColor(color)
    Row(
        modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(color, lerp(color, C.white, 0.42f))))
            .padding(start = 6.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.text.BasicText(title, Modifier.weight(1f), maxLines = 1,
            style = TextStyle(color = ink, fontFamily = LocalDisplay.current, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        if (extra != null) extra()
        if (onClose != null) {
            val haptic = rememberHaptic()
            Box(Modifier.padding(start = 3.dp).size(24.dp, 21.dp).box(Style.DESKTOP, C.line, C.panel)
                .clickable(remember { MutableInteractionSource() }, null) { haptic(false); onClose() }, contentAlignment = Alignment.Center) {
                TText("×", size = 15.sp, color = C.amber, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = C.line) {
    val style = Th.style
    Canvas(modifier.fillMaxWidth().height(when (style) { Style.PIXEL -> 3.dp; Style.DESKTOP -> 2.dp; Style.ORACLE -> 7.dp; else -> 1.dp })) {
        when (style) {
            Style.TERMINAL -> {
                var x = 0f
                val dash = 6.dp.toPx(); val gap = 4.dp.toPx()
                while (x < size.width) {
                    drawRect(color, Offset(x, 0f), Size(dash.coerceAtMost(size.width - x), size.height))
                    x += dash + gap
                }
            }
            Style.PIXEL -> {
                var x = 0f
                val s = size.height
                while (x < size.width) { drawRect(color, Offset(x, 0f), Size(s, s)); x += s * 2 }
            }
            Style.NEON -> drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to color.copy(alpha = 1f), 1f to Color.Transparent))
            Style.ABYSS -> drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to color, 1f to Color.Transparent))
            Style.DESKTOP -> {
                val px = 1.dp.toPx()
                drawRect(Th.pal.line, Offset.Zero, Size(size.width, px))
                drawRect(Th.pal.white, Offset(0f, px), Size(size.width, px))
            }
            Style.ORACLE -> {
                val y = size.height / 2f
                val c = size.width / 2f
                val d = size.height / 2f
                drawLine(color, Offset(0f, y), Offset(c - d * 2.2f, y), 1.dp.toPx())
                drawLine(color, Offset(c + d * 2.2f, y), Offset(size.width, y), 1.dp.toPx())
                drawPath(Path().apply { moveTo(c, y - d); lineTo(c + d, y); lineTo(c, y + d); lineTo(c - d, y); close() }, color)
            }
            else -> drawRect(color.copy(alpha = if (style == Style.CLEAN) 0.8f else 1f))
        }
    }
}

/** ASCII progress: [#######.........] */
fun asciiBar(fraction: Float, width: Int = 18): String {
    val f = (fraction.coerceIn(0f, 1f) * width).roundToInt()
    return "[" + "#".repeat(f) + ".".repeat(width - f) + "]"
}

@Composable
fun Spinner(color: Color = C.amber, size: TextUnit = 14.sp) {
    val style = Th.style
    if (style == Style.TERMINAL || style == Style.PIXEL) {
        var i by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(90); i = (i + 1) % 4 } }
        TText("|/-\\"[i].toString(), size = size, color = color)
        return
    }
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))
    Canvas(Modifier.size((size.value * 1.1f).dp)) {
        val sw = 2.dp.toPx()
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(sw))
        drawArc(color, a, 100f, false, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Value stepper: ‹ 07 › */
@Composable
fun Stepper(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    step: Int = 1,
    format: (Int) -> String = { it.toString().padStart(2, '0') },
) {
    val haptic = rememberHaptic()
    val pixel = Th.style == Style.PIXEL
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TText(label, Modifier.weight(1f), size = 13.sp, color = C.dim)
        TText(if (pixel) " < " else " ‹ ", Modifier.clickable(remember { MutableInteractionSource() }, null) {
            haptic(false); onChange((value - step).coerceIn(range.first, range.last))
        }.padding(12.dp), size = 18.sp, weight = FontWeight.Bold)
        TText(format(value), Modifier.width(64.dp), size = 18.sp, weight = FontWeight.Bold, color = C.hot, align = TextAlign.Center)
        TText(if (pixel) " > " else " › ", Modifier.clickable(remember { MutableInteractionSource() }, null) {
            haptic(false); onChange((value + step).coerceIn(range.first, range.last))
        }.padding(12.dp), size = 18.sp, weight = FontWeight.Bold)
    }
}

/** One selectable chip, styled per theme. */
@Composable
fun ChipBox(label: String, on: Boolean, modifier: Modifier = Modifier, size: TextUnit = 12.sp, onClick: () -> Unit) {
    val style = Th.style
    val accent = when (style) { Style.TERMINAL, Style.PAPER, Style.PIXEL -> C.amber; else -> C.hot }
    val ink = if (on) (if (style == Style.TERMINAL) C.bg else onColor(accent)) else C.amber
    TText(
        label,
        modifier
            .box(style, if (on) accent else C.line, if (on) accent else (if (style == Style.DESKTOP || style == Style.CHAT) C.panel else Color.Transparent),
                glow = on && (style == Style.NEON || style == Style.ABYSS || style == Style.DESKTOP),
                pill = style == Style.NEON || style == Style.CLEAN || style == Style.ABYSS || style == Style.CHAT)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = if (style == Style.PIXEL) 14.dp else if (style == Style.COMIC) 16.dp else 12.dp, vertical = 11.dp),
        size = size, color = if (style == Style.PIXEL && on) C.bg else ink,
        weight = if (style == Style.CLEAN) FontWeight.SemiBold else FontWeight.Bold, glow = if (on) 0f else 1f,
    )
}

/** Segmented choice; selected option is filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val haptic = rememberHaptic()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) ChipBox(label(o), o == selected) { haptic(false); onSelect(o) }
    }
}

@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    val haptic = rememberHaptic()
    val style = Th.style
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { haptic(false); onChange(!on) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (style) {
            Style.TERMINAL -> TText(if (on) "[x]" else "[ ]", size = 14.sp, color = if (on) C.hot else C.dim, weight = FontWeight.Bold)
            Style.PIXEL -> TText(if (on) "▣" else "▢", size = 16.sp, color = C.amber)
            Style.DESKTOP -> {
                val field = if (Th.pal.dark) Color.Black else Color.White
                Box(Modifier.size(18.dp).box(style, C.line, field, glow = true), contentAlignment = Alignment.Center) {
                    if (on) TText("✓", size = 13.sp, color = onColor(field), weight = FontWeight.Bold)
                }
            }
            Style.ORACLE -> {
                val ink = C.hot
                Canvas(Modifier.size(18.dp)) {
                    val c = center
                    val r = size.minDimension / 2f
                    val outer = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }
                    drawPath(outer, ink, style = Stroke(1.2.dp.toPx()))
                    if (on) {
                        val q = r * 0.5f
                        drawPath(Path().apply { moveTo(c.x, c.y - q); lineTo(c.x + q, c.y); lineTo(c.x, c.y + q); lineTo(c.x - q, c.y); close() }, ink)
                    }
                }
            }
            Style.PAPER -> {
                val ink = C.amber
                Canvas(Modifier.size(18.dp)) {
                    drawRect(ink, style = Stroke(1.5.dp.toPx()))
                    if (on) {
                        val p = 4.dp.toPx()
                        drawLine(ink, Offset(p, p), Offset(size.width - p, size.height - p), 2.dp.toPx())
                        drawLine(ink, Offset(size.width - p, p), Offset(p, size.height - p), 2.dp.toPx())
                    }
                }
            }
            else -> {
                val k by animateFloatAsState(if (on) 1f else 0f, tween(180))
                val track = lerp(C.line, C.hot, k)
                val knob = if (style == Style.NEON) lerp(C.dim, C.bg, k) else Color.White
                val glow = style == Style.NEON && on
                Canvas(Modifier.width(42.dp).height(24.dp)) {
                    val r = size.height / 2f
                    if (glow) drawRoundRect(track.copy(alpha = 0.25f), Offset(-3f, -3f), Size(size.width + 6f, size.height + 6f), CornerRadius(r + 3f))
                    drawRoundRect(track, cornerRadius = CornerRadius(r))
                    drawCircle(knob, r - 3.dp.toPx(), Offset(r + (size.width - 2 * r) * k, r))
                }
            }
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            TText(label, size = 13.sp, color = if (on || style != Style.TERMINAL) C.amber else C.dim)
            if (hint != null) TText(hint, size = 10.sp, color = C.dim.copy(alpha = 0.85f))
        }
    }
}

/** Text input styled per theme ("$ " prompt in TERMINAL, underline in PAPER, rounded field in CLEAN / NEON…). */
@Composable
fun TermInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = "$",
    placeholder: String = "",
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    size: TextUnit = 18.sp,
    singleLine: Boolean = true,
    fieldModifier: Modifier = Modifier,
) {
    val style = Th.style
    val shown = when (style) {
        Style.TERMINAL -> "$prompt "
        Style.PIXEL -> if (prompt == "$") "> " else "$prompt> "
        Style.NEON -> if (prompt == "$") "› " else "$prompt  "
        else -> if (prompt == "$") "" else "$prompt  "
    }
    val frame = when (style) {
        Style.PAPER -> Modifier.drawBehind {
            val box = this.size
            drawRect(C_line(), Offset(0f, box.height - 1.5.dp.toPx()), Size(box.width, 1.5.dp.toPx()))
        }.padding(horizontal = 2.dp, vertical = 12.dp)
        Style.PIXEL -> Modifier.box(style, C.amber, C.panel).padding(horizontal = 14.dp, vertical = 14.dp)
        Style.NEON -> Modifier.box(style, C.amber.copy(alpha = 0.7f), C.panel.copy(alpha = 0.8f), 1.5.dp, glow = true)
            .padding(horizontal = 16.dp, vertical = 12.dp)
        Style.CLEAN -> Modifier.box(style, C.line, C.panel).padding(horizontal = 14.dp, vertical = 13.dp)
        Style.TERMINAL -> Modifier.box(style, C.line, C.panel).padding(horizontal = 12.dp, vertical = 12.dp)
        Style.COMIC -> Modifier.box(style, C.line, C.panel, 2.dp).padding(horizontal = 22.dp, vertical = 14.dp)
        Style.ABYSS -> Modifier.box(style, C.hot.copy(alpha = 0.55f), C.panel.copy(alpha = 0.8f), 1.dp, glow = true, pill = true)
            .padding(horizontal = 20.dp, vertical = 13.dp)
        Style.METRO -> Modifier.box(style, C.amber.copy(alpha = 0.45f), C.panel, 1.5.dp).padding(horizontal = 14.dp, vertical = 13.dp)
        Style.JACKPOT -> Modifier.box(style, C.line, lerp(C.panel, Color.Black, 0.3f), 1.5.dp).padding(horizontal = 16.dp, vertical = 13.dp)
        Style.CHAT -> Modifier.box(style, C.line, C.panel, 1.dp, pill = true).padding(horizontal = 18.dp, vertical = 12.dp)
        Style.DESKTOP -> Modifier.box(style, C.line, if (Th.pal.dark) Color.Black else Color.White, glow = true).padding(horizontal = 10.dp, vertical = 10.dp)
        Style.ORACLE -> Modifier.drawBehind {
            val box = this.size
            val y = box.height - 1.dp.toPx()
            val d = 3.dp.toPx()
            val ink = C_hot()
            drawLine(ink, Offset(d * 2, y), Offset(box.width - d * 2, y), 1.dp.toPx())
            for (x in listOf(d, box.width - d)) drawPath(Path().apply { moveTo(x, y - d); lineTo(x + d, y); lineTo(x, y + d); lineTo(x - d, y); close() }, ink)
        }.padding(horizontal = 6.dp, vertical = 12.dp)
    }
    val ink = C.amber
    Row(modifier.then(frame), verticalAlignment = Alignment.CenterVertically) {
        if (shown.isNotBlank()) TText(shown, size = if (style == Style.TERMINAL || style == Style.PIXEL) size else size * 0.75f,
            color = C.dim, weight = FontWeight.Bold)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) TText(placeholder, size = size, color = if (style == Style.TERMINAL) C.line else C.dim.copy(alpha = 0.7f), glow = 0f, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = termStyle(size = size, color = if (style == Style.TERMINAL) C.hot else ink,
                    weight = if (style == Style.CLEAN || style == Style.CHAT || style == Style.DESKTOP) FontWeight.Normal else FontWeight.Bold),
                cursorBrush = SolidColor(when (style) { Style.TERMINAL, Style.PAPER, Style.PIXEL, Style.DESKTOP -> C.amber; else -> C.hot }),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }, onGo = { onSubmit() }, onSend = { onSubmit() }),
                modifier = fieldModifier.fillMaxWidth(),
            )
        }
    }
}

private fun C_line(): Color = C.amber.copy(alpha = 0.8f)
private fun C_hot(): Color = C.hot

// ------------------------------------------------------------------ celebration

/** Moment of the rarest answer: hex rain (TERMINAL / NEON / PIXEL) or confetti (PAPER / CLEAN). */
@Composable
fun Celebrate(modifier: Modifier, active: Boolean) {
    if (!active || LocalLook.current.reduceMotion) return
    val style = Th.style
    val measurer = rememberTextMeasurer()
    val font = LocalMono.current
    val t = rememberInfiniteTransition()
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)))
    val cols = remember { List(18) { Random.nextFloat() to Random.nextFloat() } }
    val colors = listOf(C.hot, C.amber, C.white, C.blood)
    val glyphs = if (style == Style.PIXEL) ASCII_GLYPHS else GLYPHS
    val rain = style == Style.TERMINAL || style == Style.NEON || style == Style.PIXEL
    val color = C.hot
    Canvas(modifier) {
        if (style == Style.ABYSS || style == Style.JACKPOT || style == Style.ORACLE) {
            // bubbles rising / coins falling / stars drifting down
            cols.forEachIndexed { i, (speed, phase) ->
                for (k in 0 until 2) {
                    val x = size.width * ((i + k * 0.5f + phase) % cols.size) / cols.size
                    val f = (p * (0.5f + speed) + phase + k * 0.47f) % 1f
                    val r = (3f + 4f * speed).dp.toPx()
                    when (style) {
                        Style.ABYSS -> drawCircle(color.copy(alpha = 0.5f), r, Offset(x + sin((f * 4f + phase) * 6.28f) * 8f, size.height * (1f - f)),
                            style = Stroke(1.dp.toPx()))
                        Style.JACKPOT -> {
                            val sq = kotlin.math.abs(kotlin.math.cos((f * 3f + phase) * 6.28f)).coerceAtLeast(0.15f)
                            drawOval(Color(0xFFFFD45A).copy(alpha = 0.9f), Offset(x - r * sq, size.height * f - r), Size(2 * r * sq, 2 * r))
                        }
                        else -> drawCircle(colors[(i + k) % 3].copy(alpha = 0.7f * (1f - f)), r * 0.35f, Offset(x, size.height * f))
                    }
                }
            }
            return@Canvas
        }
        if (rain) {
            val st = TextStyle(fontFamily = font, fontSize = 12.sp, color = color.copy(alpha = 0.55f))
            cols.forEachIndexed { i, (speed, phase) ->
                val x = size.width * (i + 0.5f) / cols.size
                val y = ((p * (0.6f + speed) + phase) % 1f) * size.height
                for (k in 0 until 6) {
                    val ch = glyphs[(i * 7 + k * 3 + (p * 40).toInt()) % glyphs.length].toString()
                    drawText(measurer, ch, Offset(x, y - k * 16f), st.copy(color = color.copy(alpha = 0.55f * (1f - k / 6f))))
                }
            }
        } else {
            cols.forEachIndexed { i, (speed, phase) ->
                for (k in 0 until 3) {
                    val x = size.width * ((i + k * 0.33f + phase) % cols.size) / cols.size
                    val y = ((p * (0.5f + speed) + phase + k * 0.31f) % 1f) * size.height
                    val c = colors[(i + k) % colors.size]
                    val w = 6.dp.toPx(); val h = 10.dp.toPx()
                    drawRect(c.copy(alpha = 0.85f), Offset(x + (speed - 0.5f) * 40f * k, y), Size(if ((i + k) % 2 == 0) w else h, if ((i + k) % 2 == 0) h else w))
                }
            }
        }
    }
}

@Composable
fun Gap(h: Dp) = Box(Modifier.height(h))

/** Small "LABEL ..... value" row. */
@Composable
fun KV(k: String, v: String, color: Color = C.amber) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        TText(k, Modifier.weight(1f), size = 12.sp, color = C.dim, maxLines = 1)
        TText(v, size = 12.sp, color = color, maxLines = 1)
    }
}

/** Horizontal offset used by "jitter" text in the timer's last seconds. */
fun jitter(on: Boolean): IntOffset = if (on) IntOffset(Random.nextInt(-2, 3), Random.nextInt(-1, 2)) else IntOffset.Zero
