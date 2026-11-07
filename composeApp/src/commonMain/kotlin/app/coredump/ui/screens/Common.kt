package app.coredump.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.ui.C
import app.coredump.ui.Divider
import app.coredump.ui.DesktopTitleBar
import app.coredump.ui.box
import app.coredump.ui.LocalDisplay
import app.coredump.ui.LocalLook
import app.coredump.ui.Style
import app.coredump.ui.TLink
import app.coredump.ui.onColor
import app.coredump.ui.ransomFonts
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.rememberHaptic
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Top bar: back arrow, title, handle. */
@Composable
fun Header(model: AppModel, title: String, onBack: (() -> Unit)? = { model.back() }) {
    val haptic = rememberHaptic()
    val style = Th.style
    if (style == Style.DESKTOP) {
        DesktopTitleBar(title, onClose = onBack, extra = {
            TText(model.settings.handle.take(10), Modifier.padding(end = 6.dp), size = 10.sp, color = onColor(C.hot).copy(alpha = 0.8f), maxLines = 1)
        })
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TText(
                    when (style) { Style.TERMINAL, Style.PIXEL -> "◂ "; Style.CHAT -> "‹"; else -> "←" },
                    Modifier.clickable(remember { MutableInteractionSource() }, null) { haptic(false); onBack() }
                        .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                    size = when (style) { Style.CLEAN, Style.NEON, Style.ABYSS, Style.METRO -> 20.sp; Style.CHAT -> 28.sp; else -> 16.sp },
                    weight = FontWeight.Bold, color = if (style == Style.CHAT) C.hot else C.amber,
                )
            }
            val plain = style == Style.CLEAN || style == Style.CHAT
            TText(
                if (style == Style.PAPER || style == Style.PIXEL || style == Style.METRO || style == Style.ORACLE || style == Style.ABYSS) title.uppercase() else title,
                Modifier.weight(1f),
                size = if (plain) 17.sp else 13.sp,
                weight = FontWeight.Bold,
                spacing = if (plain) 0.sp else if (style == Style.ABYSS) 3.sp else 2.sp,
                maxLines = 1,
            )
            TText(if (style == Style.TERMINAL) "uid=${model.settings.handle.take(10)}" else model.settings.handle.take(10),
                size = 11.sp, color = C.dim, maxLines = 1)
        }
        if (style == Style.METRO) Box(Modifier.fillMaxWidth().height(3.dp).background(C.hot))
        else if (style != Style.CLEAN && style != Style.CHAT) Divider(Modifier.padding(horizontal = 16.dp))
        else if (style == Style.CHAT) Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    }
}

@Composable
fun Frame(model: AppModel, title: String, onBack: (() -> Unit)? = { model.back() }, content: @Composable ColumnScope.() -> Unit) {
    if (Th.style == Style.DESKTOP) {
        // every screen is a maximised window on the desktop
        Column(Modifier.fillMaxSize().padding(6.dp).box(Style.DESKTOP, C.line, C.panel).padding(3.dp)) {
            Header(model, title, onBack)
            Spacer(Modifier.height(4.dp))
            content()
        }
        return
    }
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
        Style.COMIC -> RansomText("CORE DUMP", size * 0.8f, seed = 11)
        Style.ABYSS -> TText("CORE DUMP", size = size * 0.7f, weight = FontWeight.SemiBold, spacing = 9.sp, color = C.amber, glow = 1.6f)
        Style.METRO -> SplitFlap("CORE DUMP", size * 0.62f)
        Style.JACKPOT -> BasicText(
            "CORE DUMP",
            style = TextStyle(
                brush = Brush.verticalGradient(listOf(C.white, C.hot, lerp(C.hot, Color.Black, 0.25f))),
                fontFamily = LocalDisplay.current, fontWeight = FontWeight.Bold, fontSize = size * 0.9f, letterSpacing = 2.sp,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 4f), 6f),
            ),
        )
        Style.CHAT -> Row(verticalAlignment = Alignment.CenterVertically) {
            TText("Core Dump", size = size * 0.8f, weight = FontWeight.Bold, color = C.amber)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(9.dp).background(Color(0xFF34C759), CircleShape))
        }
        Style.DESKTOP -> BasicText("Core Dump", style = TextStyle(color = C.amber, fontFamily = LocalDisplay.current, fontSize = size * 0.75f))
        Style.ORACLE -> TText("Core Dump", size = size * 0.95f, weight = FontWeight.Bold, color = C.hot, spacing = 1.5.sp)
    }
}

/** Ransom-note letters: each glyph cut out in its own font, fill, size and tilt; they drop in one by one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RansomText(text: String, size: TextUnit = 30.sp, seed: Int = 3, modifier: Modifier = Modifier) {
    val fonts = ransomFonts()
    val motion = !LocalLook.current.reduceMotion
    val glyphs = remember(text, seed) {
        val r = Random(seed)
        text.map { c -> RGlyph(c, r.nextInt(fonts.size), (r.nextFloat() - 0.5f) * 14f, 0.86f + r.nextFloat() * 0.28f, r.nextInt(4)) }
    }
    FlowRow(modifier, verticalArrangement = Arrangement.Center) {
        glyphs.forEachIndexed { i, g ->
            key(i) {
                if (g.c == ' ') {
                    Spacer(Modifier.width((size.value * 0.4f).dp))
                } else {
                    val drop = remember(text) { Animatable(if (motion) 0f else 1f) }
                    LaunchedEffect(text) { delay(45L * i); drop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f)) }
                    val fill = when (g.fill) { 0 -> C.hot; 1 -> C.white; 2 -> C.line; else -> C.panel }
                    val ink = if (g.fill == 3) C.amber else onColor(fill)
                    BasicText(
                        g.c.toString(),
                        Modifier.padding(horizontal = 1.dp, vertical = 2.dp)
                            .graphicsLayer { rotationZ = g.rot; scaleX = drop.value; scaleY = drop.value; alpha = drop.value.coerceIn(0f, 1f) }
                            .background(fill).padding(horizontal = 4.dp),
                        style = TextStyle(
                            color = ink, fontFamily = fonts[g.font], fontWeight = FontWeight.Bold, fontSize = size * g.scale,
                            fontStyle = if (g.font == 0) FontStyle.Italic else FontStyle.Normal,
                        ),
                    )
                }
            }
        }
    }
}

private class RGlyph(val c: Char, val font: Int, val rot: Float, val scale: Float, val fill: Int)

/** Comic starburst behind [content]; [spikes] points, inner radius = 72 % of the outer one. */
@Composable
fun Starburst(
    modifier: Modifier = Modifier,
    fill: Color = C.white,
    stroke: Color = C.bg,
    spikes: Int = 14,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.drawBehind {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val ro = minOf(size.width, size.height) / 2f
        val path = Path()
        for (i in 0 until spikes * 2) {
            val a = PI * i / spikes - PI / 2
            val r = if (i % 2 == 0) ro else ro * 0.72f
            // stretch horizontally so a word fits inside
            val x = cx + (cos(a) * r * (size.width / (2f * ro))).toFloat()
            val y = cy + (sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, fill)
        drawPath(path, stroke, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Miter))
    }, contentAlignment = Alignment.Center, content = content)
}

/** "Never ask this again" for one prompt id (hidden when the id is unknown, e.g. a guest in 1v1). */
@Composable
fun DiscardLink(model: AppModel, id: String?, modifier: Modifier = Modifier) {
    if (id == null) return
    if (model.isDiscarded(id)) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            TText(Th.say("✗ rm'd. never again.", "✓ Discarded — won't come up again"), size = 12.sp, color = C.dim)
            Spacer(Modifier.width(12.dp))
            TLink(Th.say("undo", "Undo"), { model.undoDiscard(id) }, color = C.hot, size = 12.sp)
        }
    } else {
        TLink(Th.say("rm this question (never ask again)", "Discard this question"), { app.coredump.ui.Sfx.play(app.coredump.ui.Cue.DISCARD); model.discard(id) }, modifier, color = C.dim, size = 12.sp)
    }
}

/** Experience for QUEST: every point ever scored is 1 EXP. Level L → L+1 needs 300 + 100·L EXP. */
object Exp {
    fun need(level: Int): Int = 300 + 100 * level

    /** For a lifetime total (EXP): level (≥ 1), EXP gained inside that level, EXP the level needs. */
    fun of(total: Int): Triple<Int, Int, Int> {
        var level = 1
        var left = total.coerceAtLeast(0)
        while (left >= need(level)) { left -= need(level); level++ }
        return Triple(level, left, need(level))
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
