package app.coredump.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
    val r = if (pill) h / 2f else style.corner.toPx()
    if (fill.alpha > 0f) drawRoundRect(fill, cornerRadius = CornerRadius(r))
    if (glow && style == Style.NEON && stroke.alpha > 0f) {
        drawRoundRect(stroke.copy(alpha = 0.10f), Offset(-sw * 2, -sw * 2), Size(w + sw * 4, h + sw * 4),
            CornerRadius(r + sw * 2), style = Stroke(sw * 6))
        drawRoundRect(stroke.copy(alpha = 0.22f), cornerRadius = CornerRadius(r), style = Stroke(sw * 3))
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
        primary && (style == Style.CLEAN || style == Style.NEON) -> C.hot
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
        else -> {
            val filled = primary || pressed
            val bgFill = when {
                style == Style.CLEAN && !filled -> C.panel
                filled -> accent
                else -> Color.Transparent
            }
            val ink = if (filled) onColor(accent) else accent
            val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120))
            Box(
                modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                    .box(style, if (style == Style.CLEAN && !filled) C.line else accent, bgFill,
                        strokeWidth = if (style == Style.PAPER) 1.5.dp else 1.dp, glow = style == Style.NEON, pill = style == Style.NEON)
                    .then(click).heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                TText(
                    if (style == Style.PAPER) label.uppercase() else label,
                    size = textSize * (if (style == Style.PAPER) 0.9f else 1f), color = ink,
                    weight = if (style == Style.CLEAN) FontWeight.SemiBold else FontWeight.Bold,
                    spacing = if (style == Style.PAPER) 1.5.sp else if (style == Style.NEON) 1.sp else 0.sp,
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
    val prefix = when (Th.style) { Style.TERMINAL -> "› "; Style.PIXEL -> "> "; else -> "" }
    val suffix = when (Th.style) { Style.CLEAN, Style.NEON, Style.PAPER -> "  →"; else -> "" }
    TText("$prefix$label$suffix", modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        haptic(false); onClick()
    }.padding(vertical = 6.dp), size = size, color = color,
        decoration = if (Th.style == Style.PAPER) androidx.compose.ui.text.style.TextDecoration.Underline else null)
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
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = C.line) {
    val style = Th.style
    Canvas(modifier.fillMaxWidth().height(if (style == Style.PIXEL) 3.dp else 1.dp)) {
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
    val accent = when (style) { Style.CLEAN, Style.NEON -> C.hot; else -> C.amber }
    val ink = if (on) (if (style == Style.TERMINAL) C.bg else onColor(accent)) else C.amber
    TText(
        label,
        modifier
            .box(style, if (on) accent else C.line, if (on) accent else Color.Transparent, glow = on && style == Style.NEON,
                pill = style == Style.NEON || style == Style.CLEAN)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = if (style == Style.PIXEL) 14.dp else 12.dp, vertical = 11.dp),
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
            drawRect(C_line(), Offset(0f, size.height - 1.5.dp.toPx()), Size(size.width, 1.5.dp.toPx()))
        }.padding(horizontal = 2.dp, vertical = 12.dp)
        Style.PIXEL -> Modifier.box(style, C.amber, C.panel).padding(horizontal = 14.dp, vertical = 14.dp)
        Style.NEON -> Modifier.box(style, C.amber.copy(alpha = 0.7f), C.panel.copy(alpha = 0.8f), 1.5.dp, glow = true)
            .padding(horizontal = 16.dp, vertical = 12.dp)
        Style.CLEAN -> Modifier.box(style, C.line, C.panel).padding(horizontal = 14.dp, vertical = 13.dp)
        Style.TERMINAL -> Modifier.box(style, C.line, C.panel).padding(horizontal = 12.dp, vertical = 12.dp)
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
                    weight = if (style == Style.CLEAN) FontWeight.Normal else FontWeight.Bold),
                cursorBrush = SolidColor(if (style == Style.CLEAN || style == Style.NEON) C.hot else C.amber),
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

/** Tier badge, styled per theme. */
@Composable
fun TierBadge(tier: app.coredump.core.Tier, size: TextUnit = 13.sp) {
    val style = Th.style
    val c = tierColor(tier)
    val t = rememberInfiniteTransition()
    val pulse by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    val a = if (tier.heat >= 5 && !LocalLook.current.reduceMotion) pulse else 1f
    when (style) {
        Style.PAPER -> TText(
            " ${tier.label} ",
            Modifier.box(style, c, Color.Transparent, 1.dp).padding(horizontal = 6.dp, vertical = 3.dp),
            size = size, color = c, weight = FontWeight.Bold, glow = 0f, spacing = 1.sp,
        )
        else -> TText(
            " ${tier.label} ",
            Modifier.box(style, Color.Transparent, c.copy(alpha = a), pill = style == Style.CLEAN || style == Style.NEON)
                .padding(horizontal = if (style == Style.PIXEL) 8.dp else 6.dp, vertical = if (style == Style.PIXEL) 5.dp else 3.dp),
            size = size, color = if (style == Style.TERMINAL) C.bg else onColor(c), weight = FontWeight.Bold, glow = 0f,
        )
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
