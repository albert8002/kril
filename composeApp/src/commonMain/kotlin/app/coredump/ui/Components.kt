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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

private const val GLYPHS = "▓▒░#%&$@<>/\\|01ABCDEF*+=?"

@Composable
fun rememberHaptic(): (Boolean) -> Unit {
    val h = LocalHapticFeedback.current
    val on = LocalLook.current.haptics
    return remember(h, on) {
        { strong: Boolean ->
            if (on) h.performHapticFeedback(if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        }
    }
}

/** Blinking block cursor. */
@Composable
fun Cursor(size: TextUnit = 14.sp, color: Color = C.amber) {
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = { if (it < 0.5f) 0f else 1f }), RepeatMode.Restart))
    TText("█", Modifier.graphicsLayer { alpha = a }, size = size, color = color)
}

/** Types text out char by char, then keeps a blinking cursor. */
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
    val motion = !LocalLook.current.reduceMotion
    var shown by remember(text) { mutableIntStateOf(if (motion) 0 else text.length) }
    LaunchedEffect(text) {
        if (motion) {
            for (i in 0..text.length) { shown = i; delay(charMs + if (text.getOrNull(i) == ' ') 12 else 0) }
        }
        onDone()
    }
    Row(modifier) {
        TText(text.take(shown) + if (shown < text.length) "▌" else "", Modifier.weight(1f, fill = false), size = size, color = color, weight = weight)
        if (cursor && shown >= text.length) Cursor(size, color)
    }
}

/** Scrambles glyphs then resolves left to right into [target]. */
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
    val motion = !LocalLook.current.reduceMotion
    var shown by remember(target) { mutableStateOf(if (motion) scramble(target, 0f) else target) }
    LaunchedEffect(target) {
        if (!motion) { shown = target; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            val p = ((withFrameMillis { it } - start) / durationMs.toFloat()).coerceIn(0f, 1f)
            shown = scramble(target, p)
            if (p >= 1f) break
        }
    }
    TText(shown, modifier, size = size, color = color, weight = weight, glow = glow)
}

private fun scramble(t: String, p: Float): String {
    val done = (t.length * p).toInt()
    val sb = StringBuilder(t.length)
    for (i in t.indices) {
        val c = t[i]
        sb.append(if (i < done || c == ' ') c else GLYPHS[Random.nextInt(GLYPHS.length)])
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

/** Bracket button: a fill wipes in on press, label inverts. */
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
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val fill by animateFloatAsState(if (pressed) 1f else if (primary) 0.12f else 0f, tween(if (pressed) 90 else 260))
    val c = if (enabled) color else C.line
    val textColor = lerp(c, C.bg, fill.coerceIn(0f, 1f))
    Box(
        modifier
            .drawBehind {
                drawRect(c.copy(alpha = if (primary) 0.9f else 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                if (fill > 0f) drawRect(c, size = Size(size.width * fill, size.height))
            }
            .clickable(interactionSource = src, indication = null, enabled = enabled) {
                haptic(primary)
                onClick()
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        TText(
            if (pressed) "[$label]" else "[ $label ]",
            size = textSize, color = textColor, weight = FontWeight.Bold,
            glow = if (fill > 0.5f) 0f else 1f,
        )
    }
}

/** Plain text link-style action: "> label". */
@Composable
fun TLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.dim, size: TextUnit = 13.sp) {
    val haptic = rememberHaptic()
    TText("› $label", modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        haptic(false); onClick()
    }.padding(vertical = 6.dp), size = size, color = color)
}

/** Framed panel with a title notch: ┌─ TITLE ───┐ */
@Composable
fun Panel(
    title: String?,
    modifier: Modifier = Modifier,
    color: Color = C.line,
    titleColor: Color = C.dim,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.padding(top = if (title != null) 8.dp else 0.dp)) {
        Column(
            Modifier.fillMaxWidth().border(1.dp, color).background(C.panel.copy(alpha = 0.55f)).padding(14.dp),
            content = content,
        )
        if (title != null) {
            TText(
                " $title ", Modifier.offset(x = 12.dp, y = (-8).dp).background(C.bg),
                size = 11.sp, color = titleColor, spacing = 1.sp,
            )
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, color: Color = C.line) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        var x = 0f
        val dash = 6.dp.toPx(); val gap = 4.dp.toPx()
        while (x < size.width) {
            drawRect(color, Offset(x, 0f), Size(dash.coerceAtMost(size.width - x), size.height))
            x += dash + gap
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
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(90); i = (i + 1) % 4 } }
    TText("|/-\\"[i].toString(), size = size, color = color)
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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TText(label, Modifier.weight(1f), size = 13.sp, color = C.dim)
        val src = remember { MutableInteractionSource() }
        TText(" ‹ ", Modifier.clickable(src, null) {
            haptic(false); onChange((value - step).coerceIn(range.first, range.last))
        }.padding(12.dp), size = 18.sp, weight = FontWeight.Bold)
        TText(format(value), Modifier.width(64.dp), size = 18.sp, weight = FontWeight.Bold, color = C.hot,
            align = androidx.compose.ui.text.style.TextAlign.Center)
        TText(" › ", Modifier.clickable(remember { MutableInteractionSource() }, null) {
            haptic(false); onChange((value + step).coerceIn(range.first, range.last))
        }.padding(12.dp), size = 18.sp, weight = FontWeight.Bold)
    }
}

/** Segmented choice; selected option is inverted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val haptic = rememberHaptic()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) {
            val on = o == selected
            TText(
                label(o),
                Modifier
                    .background(if (on) C.amber else Color.Transparent)
                    .border(1.dp, if (on) C.amber else C.line)
                    .clickable(remember { MutableInteractionSource() }, null) { haptic(false); onSelect(o) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                size = 12.sp, color = if (on) C.bg else C.amber, weight = FontWeight.Bold, glow = if (on) 0f else 1f,
            )
        }
    }
}

@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    val haptic = rememberHaptic()
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { haptic(false); onChange(!on) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TText(if (on) "[x]" else "[ ]", size = 14.sp, color = if (on) C.hot else C.dim, weight = FontWeight.Bold)
        Column(Modifier.padding(start = 10.dp)) {
            TText(label, size = 13.sp, color = if (on) C.amber else C.dim)
            if (hint != null) TText(hint, size = 10.sp, color = C.dim.copy(alpha = 0.8f))
        }
    }
}

/** Terminal input line: "$ " prompt, block cursor, no autocorrect. */
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
    Row(
        modifier.border(1.dp, C.line).background(C.panel).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TText("$prompt ", size = size, color = C.dim, weight = FontWeight.Bold)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) TText(placeholder, size = size, color = C.line, glow = 0f, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = termStyle(size = size, color = C.hot, weight = FontWeight.Bold),
                cursorBrush = SolidColor(C.amber),
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

/** Falling hex columns, used for the ZERO_DAY moment. */
@Composable
fun HexRain(modifier: Modifier, active: Boolean, color: Color = C.hot) {
    if (!active || LocalLook.current.reduceMotion) return
    val measurer = rememberTextMeasurer()
    val mono = LocalMono.current
    val t = rememberInfiniteTransition()
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)))
    val cols = remember { List(18) { Random.nextFloat() to Random.nextFloat() } }
    Canvas(modifier) {
        val style = TextStyle(fontFamily = mono, fontSize = 12.sp, color = color.copy(alpha = 0.55f))
        cols.forEachIndexed { i, (speed, phase) ->
            val x = size.width * (i + 0.5f) / cols.size
            val y = ((p * (0.6f + speed) + phase) % 1f) * size.height
            for (k in 0 until 6) {
                val ch = GLYPHS[(i * 7 + k * 3 + (p * 40).toInt()) % GLYPHS.length].toString()
                drawText(measurer, ch, Offset(x, y - k * 16f), style.copy(color = color.copy(alpha = 0.55f * (1f - k / 6f))))
            }
        }
    }
}

/** Tier badge: [ HEISENBUG 0x0055 ] */
@Composable
fun TierBadge(tier: app.coredump.core.Tier, size: TextUnit = 13.sp) {
    val c = tierColor(tier)
    val t = rememberInfiniteTransition()
    val pulse by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    TText(
        " ${tier.label} ",
        Modifier.background(c.copy(alpha = if (tier.heat >= 5) pulse else 1f)).padding(horizontal = 6.dp, vertical = 3.dp),
        size = size, color = C.bg, weight = FontWeight.Bold, glow = 0f,
    )
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
