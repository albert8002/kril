package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.data.History
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.DesktopTitleBar
import app.coredump.ui.Divider
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RankBadge
import app.coredump.ui.RollingNumber
import app.coredump.ui.Spinner
import app.coredump.ui.Style
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.TierBadge
import app.coredump.ui.box
import app.coredump.ui.group
import app.coredump.ui.onColor
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlin.math.ceil
import kotlin.math.max

// DESKTOP: a generic 90s desktop. Icons on a coloured desktop, a taskbar with a menu button, every screen a
// bevelled window that "zooms" open with dotted outlines, questions as dialog boxes with status icons, a
// block progress bar for the timer, and the results as a report window with a list view.

private fun deskLabel(it: Item): String = when (it.key) {
    "daily" -> dailyLabel(it.label, "Daily")
    "solo" -> "Solo"
    "duel" -> "1v1"
    "party" -> "Meltdown"
    "friends" -> "Friends"
    "stats" -> "Stats"
    "browse" -> "Lists"
    "theme" -> "Theme"
    "settings" -> "Settings"
    "manual" -> "Help"
    else -> it.label
}

/** Text field background: white, or black in dark palettes. */
private fun fieldColor(): Color = if (Th.pal.dark) Color.Black else Color.White

/**
 * A window: bevelled frame, title bar, content. Opens with dotted outlines growing from the centre.
 * fill = take all the height the parent gives (for full-screen windows).
 */
@Composable
private fun DesktopWindow(title: String, modifier: Modifier = Modifier, fill: Boolean = false, onClose: (() -> Unit)? = null,
                          extra: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val motion = !LocalLook.current.reduceMotion
    val open = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { open.animateTo(1f, tween(240, easing = LinearEasing)) }
    val ink = C.amber
    Box(modifier) {
        Column(
            (if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).graphicsLayer { alpha = if (open.value < 0.65f) 0f else 1f }
                .box(Style.DESKTOP, C.line, C.panel).padding(3.dp),
        ) {
            DesktopTitleBar(title, onClose = onClose, extra = extra)
            content()
        }
        if (open.value < 1f) {
            Canvas(Modifier.matchParentSize()) {
                val q = open.value
                for (i in 0 until 3) {
                    val f = (q * 1.3f - i * 0.15f).coerceIn(0.05f, 1f)
                    val w = size.width * f
                    val h = size.height * f
                    drawRect(ink, Offset((size.width - w) / 2f, (size.height - h) / 2f), Size(w, h),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
                }
            }
        }
    }
}

/** Little square button in a title bar. */
@Composable
private fun TitleButton(glyph: String, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Box(Modifier.padding(start = 3.dp).size(24.dp, 21.dp).box(Style.DESKTOP, C.line, C.panel)
        .clickable(remember { MutableInteractionSource() }, null) { haptic(false); onClick() }, contentAlignment = Alignment.Center) {
        TText(glyph, size = 13.sp, color = C.amber, weight = FontWeight.Bold)
    }
}

/** Simple pixel-style icons drawn with shapes. */
@Composable
private fun DeskIcon(key: String, modifier: Modifier) {
    val face = C.panel
    val shade = C.line
    val screen = C.hot
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val px = max(1f, w / 32f)
        fun bevel(l: Float, t: Float, r: Float, b: Float, fill: Color) {
            drawRect(fill, Offset(l, t), Size(r - l, b - t))
            drawRect(Color.White, Offset(l, t), Size(r - l, px)); drawRect(Color.White, Offset(l, t), Size(px, b - t))
            drawRect(Color.Black, Offset(l, b - px), Size(r - l, px)); drawRect(Color.Black, Offset(r - px, t), Size(px, b - t))
        }
        fun monitor(l: Float, t: Float, s: Float) {
            bevel(l, t, l + s, t + s * 0.72f, face)
            drawRect(screen, Offset(l + s * 0.1f, t + s * 0.1f), Size(s * 0.8f, s * 0.5f))
            drawRect(shade, Offset(l + s * 0.4f, t + s * 0.72f), Size(s * 0.2f, s * 0.1f))
            bevel(l + s * 0.2f, t + s * 0.82f, l + s * 0.8f, t + s * 0.94f, face)
        }
        when (key) {
            "daily" -> {
                bevel(w * 0.12f, h * 0.14f, w * 0.88f, h * 0.9f, Color.White)
                drawRect(Color(0xFFC62828), Offset(w * 0.12f + px, h * 0.14f + px), Size(w * 0.76f - 2 * px, h * 0.18f))
                for (r in 0..2) for (c in 0..3) drawRect(Color(0xFF555555), Offset(w * (0.2f + c * 0.16f), h * (0.42f + r * 0.15f)), Size(w * 0.09f, h * 0.08f))
            }
            "solo" -> monitor(w * 0.06f, h * 0.06f, w * 0.88f)
            "party" -> {
                val tri = Path().apply { moveTo(w * 0.5f, h * 0.08f); lineTo(w * 0.94f, h * 0.88f); lineTo(w * 0.06f, h * 0.88f); close() }
                drawPath(tri, Color(0xFFF2C230))
                drawPath(tri, Color.Black, style = Stroke(px))
                drawRect(Color.Black, Offset(w * 0.46f, h * 0.34f), Size(w * 0.08f, h * 0.3f))
                drawRect(Color.Black, Offset(w * 0.46f, h * 0.7f), Size(w * 0.08f, h * 0.08f))
            }
            "duel" -> {
                monitor(0f, 0f, w * 0.6f)
                monitor(w * 0.4f, h * 0.36f, w * 0.6f)
            }
            "stats" -> {
                bevel(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.9f, Color.White)
                val base = h * 0.82f
                listOf(0.35f to Color(0xFFD32F2F), 0.55f to Color(0xFF388E3C), 0.25f to Color(0xFF1E5BD8)).forEachIndexed { i, (f, c) ->
                    drawRect(c, Offset(w * (0.22f + i * 0.2f), base - h * f), Size(w * 0.14f, h * f))
                }
            }
            "browse" -> {
                val y = Color(0xFFF2C94C)
                drawRect(y, Offset(w * 0.08f, h * 0.2f), Size(w * 0.36f, h * 0.14f))
                drawRect(y, Offset(w * 0.08f, h * 0.3f), Size(w * 0.84f, h * 0.54f))
                drawRect(Color.Black, Offset(w * 0.08f, h * 0.3f), Size(w * 0.84f, h * 0.54f), style = Stroke(px))
                drawRect(Color(0xFFFFE89A), Offset(w * 0.08f + px, h * 0.34f), Size(w * 0.84f - 2 * px, px * 2))
            }
            "theme" -> {
                drawOval(Color(0xFFE8D9B5), Offset(w * 0.08f, h * 0.18f), Size(w * 0.84f, h * 0.66f))
                drawOval(Color.Black, Offset(w * 0.08f, h * 0.18f), Size(w * 0.84f, h * 0.66f), style = Stroke(px))
                listOf(Color(0xFFD32F2F), Color(0xFF1E5BD8), Color(0xFF388E3C), Color(0xFFF2C94C)).forEachIndexed { i, c ->
                    drawCircle(c, w * 0.08f, Offset(w * (0.28f + i * 0.15f), h * (if (i % 2 == 0) 0.4f else 0.6f)))
                }
            }
            "settings" -> {
                val c = Offset(w / 2f, h / 2f)
                val r = w * 0.28f
                for (i in 0 until 8) rotate(i * 45f, c) { drawRect(shade, Offset(c.x - w * 0.07f, c.y - r * 1.45f), Size(w * 0.14f, r * 0.6f)) }
                drawCircle(face, r, c)
                drawCircle(Color.Black, r, c, style = Stroke(px))
                drawCircle(shade, r * 0.38f, c)
            }
            "manual" -> {
                val p = Path().apply {
                    moveTo(w * 0.2f, h * 0.08f); lineTo(w * 0.64f, h * 0.08f); lineTo(w * 0.8f, h * 0.24f); lineTo(w * 0.8f, h * 0.92f)
                    lineTo(w * 0.2f, h * 0.92f); close()
                }
                drawPath(p, Color.White)
                drawPath(p, Color.Black, style = Stroke(px))
                for (i in 0..4) drawRect(shade, Offset(w * 0.3f, h * (0.34f + i * 0.11f)), Size(w * 0.4f, px * 1.5f))
            }
            "disc" -> {
                val c = Offset(w / 2f, h / 2f)
                drawCircle(Brush.linearGradient(listOf(Color(0xFFE0E0E0), Color(0xFF9EA7B0), Color(0xFFF5F5F5)), Offset.Zero, Offset(w, h)), w * 0.46f, c)
                drawCircle(Color.Black, w * 0.46f, c, style = Stroke(px))
                drawCircle(face, w * 0.12f, c)
                drawCircle(Color.Black, w * 0.12f, c, style = Stroke(px))
            }
            else -> monitor(w * 0.06f, h * 0.06f, w * 0.88f)
        }
    }
}

/** Dialog status icon: information, warning or error. */
@Composable
private fun StatusIcon(kind: String, modifier: Modifier = Modifier) {
    val fill = when (kind) { "error" -> Color(0xFFD32F2F); "warning" -> Color(0xFFF2C230); else -> Color(0xFF1E5BD8) }
    val glyph = when (kind) { "error" -> "×"; "warning" -> "!"; "info" -> "i"; else -> "?" }
    Box(modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            if (kind == "warning") {
                val p = Path().apply { moveTo(size.width / 2f, 1f); lineTo(size.width - 1f, size.height - 1f); lineTo(1f, size.height - 1f); close() }
                drawPath(p, fill)
                drawPath(p, Color.Black, style = Stroke(1.dp.toPx()))
            } else {
                drawCircle(fill)
                drawCircle(Color.Black.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))
            }
        }
        TText(glyph, Modifier.offset(y = if (kind == "warning") 4.dp else 0.dp), size = 18.sp,
            color = if (kind == "warning") Color.Black else Color.White, weight = FontWeight.Bold)
    }
}

/** Block progress bar in a sunken field. */
@Composable
private fun BlockProgress(f: Float, danger: Boolean) {
    val c = if (danger) C.blood else C.hot
    Box(Modifier.fillMaxWidth().height(22.dp).box(Style.DESKTOP, C.line, fieldColor(), glow = true).padding(4.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val bw = 9.dp.toPx()
            val gap = 2.dp.toPx()
            val n = ((size.width + gap) / (bw + gap)).toInt()
            val lit = ceil(f.coerceIn(0f, 1f) * n).toInt()
            for (i in 0 until lit) drawRect(c, Offset(i * (bw + gap), 0f), Size(bw, size.height))
        }
    }
}

// ======================================================================= menu

@Composable
fun DesktopMenu(model: AppModel) {
    val items = menuItems(model)
    var menuOpen by remember { mutableStateOf(false) }
    val bank by model.bank.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                DesktopWindow("Welcome") {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        DeskIcon("disc", Modifier.size(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            AppTitle(30.sp)
                            TText("Name the rare answer. Common answers barely score.", size = 12.sp)
                            TText(if (History.games == 0) "No runs yet." else "Best run: ${History.best} points · ${History.games} runs",
                                size = 11.sp, color = C.dim)
                        }
                    }
                }
                CrashBanner(model)
                Spacer(Modifier.height(18.dp))
                for (row in items.chunked(4)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (it in row) DesktopIconButton(it, Modifier.weight(1f))
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(8.dp))
                Panel("Library") { LibraryFooter(model, sources = false) }
                Spacer(Modifier.height(12.dp))
            }
            Taskbar(bank.prompts, menuOpen) { menuOpen = !menuOpen }
        }
        if (menuOpen) MenuPanel(items, Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 46.dp)) { menuOpen = false }
    }
}

@Composable
private fun DesktopIconButton(item: Item, modifier: Modifier) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val ink = onColor(C.bg)
    Column(modifier.clickable(src, null) { haptic(true); item.action() }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            DeskIcon(item.key, Modifier.size(40.dp).graphicsLayer { alpha = if (pressed) 0.6f else 1f })
            if (item.badge == "NEW") Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-2).dp).size(10.dp).background(C.blood))
        }
        Spacer(Modifier.height(6.dp))
        TText(deskLabel(item), Modifier.background(if (pressed) C.hot else Color.Transparent).padding(horizontal = 3.dp, vertical = 1.dp),
            size = 11.sp, color = if (pressed) onColor(C.hot) else ink, align = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun Taskbar(lists: Int, open: Boolean, onMenu: () -> Unit) {
    val haptic = rememberHaptic()
    Row(Modifier.fillMaxWidth().box(Style.DESKTOP, C.line, C.panel).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.box(Style.DESKTOP, C.line, C.panel, glow = open).clickable(remember { MutableInteractionSource() }, null) { haptic(false); onMenu() }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            DeskIcon("disc", Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            TText("Menu", size = 13.sp, weight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.box(Style.DESKTOP, C.line, C.panel, glow = true).padding(horizontal = 10.dp, vertical = 6.dp)) {
            TText("${group(lists)} lists", size = 12.sp)
        }
    }
}

/** Pop-up menu above the taskbar: a gradient strip on the left, one row per destination. */
@Composable
private fun MenuPanel(items: List<Item>, modifier: Modifier, onDone: () -> Unit) {
    Row(modifier.width(240.dp).box(Style.DESKTOP, C.line, C.panel).padding(3.dp)) {
        Box(Modifier.width(24.dp).height((items.size * 40).dp).background(Brush.verticalGradient(listOf(lerp(C.hot, C.white, 0.45f), C.hot))))
        Column(Modifier.weight(1f)) { for (it in items) MenuPanelRow(it, onDone) }
    }
}

@Composable
private fun MenuPanelRow(item: Item, onDone: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    Row(Modifier.fillMaxWidth().height(40.dp).background(if (pressed) C.hot else Color.Transparent)
        .clickable(src, null) { haptic(true); onDone(); item.action() }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        DeskIcon(item.key, Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        TText(deskLabel(item), size = 13.sp, color = if (pressed) onColor(C.hot) else C.amber, maxLines = 1)
    }
}

// ======================================================================= play

@Composable
fun DesktopPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    Column(Modifier.fillMaxSize().padding(6.dp)) {
        key(st.round) {
            DesktopWindow(
                if (st.total > 0) "Question ${st.round + 1} of ${st.total}" else "Core Dump", Modifier.weight(1f), fill = true,
                onClose = { session.pause(); model.confirmQuit = true },
                extra = if (canPauseNow(st)) { { TitleButton("_") { session.pause() } } } else null,
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    if (st.phase == Phase.WAITING || st.prompt.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Spinner(C.amber); TText("  ${st.message ?: "Loading…"}", size = 13.sp) }
                    } else {
                        Row(verticalAlignment = Alignment.Top) {
                            StatusIcon("question")
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                TText(st.prompt, size = 18.sp, weight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                TText("Category: ${st.topic} · ${group(st.listSize)} answers", size = 11.sp, color = C.dim)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (st.seconds > 0 && (st.phase == Phase.TYPING || st.phase == Phase.LOCKED)) {
                        TText("Time remaining: ${(left + 999) / 1000} s", size = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        BlockProgress(left / (st.seconds * 1000f), left in 1..5000)
                        Spacer(Modifier.height(12.dp))
                    }
                    when (st.phase) {
                        Phase.TYPING -> if (!st.paused) {
                            TText("Your answer:", size = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a), size = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            SuggestionChip(st, a)
                            Spacer(Modifier.height(8.dp))
                            AttemptChips(st)
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TButton("OK", { a.send() }, Modifier.width(104.dp), enabled = a.text.isNotBlank(), primary = true)
                                Spacer(Modifier.width(8.dp))
                                TButton("Skip", { session.skip() }, Modifier.width(104.dp))
                            }
                        }
                        Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(C.amber); TText("  Please wait. The other player is still answering…", size = 12.sp)
                        }
                        Phase.REVEAL -> st.reveal?.let { v ->
                            DesktopVerdict(v)
                            RevealExtras(v, session.isMulti)
                        }
                        Phase.ERROR -> Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIcon("error"); Spacer(Modifier.width(10.dp)); TText(st.message ?: "An error occurred.", size = 13.sp, color = C.blood)
                        }
                        else -> Unit
                    }
                }
                if (st.phase != Phase.TYPING) PlayActions(model, session, st, Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    TText("Score: ${group(st.myTotal)}", Modifier.weight(1f).box(Style.DESKTOP, C.line, C.panel, glow = true).padding(horizontal = 6.dp, vertical = 3.dp),
                        size = 11.sp, maxLines = 1)
                    Spacer(Modifier.width(3.dp))
                    val side = when {
                        session.isMulti -> "${st.oppName ?: "?"}: ${group(st.oppTotal)}" + (if (st.oppLocked) " ✓" else "")
                        st.daily != null -> "Daily #${st.daily}"
                        else -> "Solo"
                    }
                    TText(side,
                        Modifier.weight(1f).box(Style.DESKTOP, C.line, C.panel, glow = true).padding(horizontal = 6.dp, vertical = 3.dp), size = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DesktopVerdict(v: RoundView) {
    val me = v.me
    val kind = when (me.tier) { Tier.SEGFAULT -> "error"; Tier.SKIPPED, Tier.TIMEOUT -> "warning"; else -> "info" }
    Column(Modifier.fillMaxWidth().box(Style.DESKTOP, C.line, C.panel).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            StatusIcon(kind)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                TText(tierLine(me.tier), size = 13.sp)
                Spacer(Modifier.height(4.dp))
                TText(headline(me), size = 20.sp, weight = FontWeight.Bold, color = tierColor(me.tier))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TierBadge(me.tier, 11.sp)
                    Spacer(Modifier.width(10.dp))
                    TText("${signed(me.points)} points", size = 14.sp, weight = FontWeight.Bold)
                }
            }
        }
    }
}

// ======================================================================= summary

@Composable
fun DesktopSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    Column(Modifier.fillMaxSize().padding(6.dp)) {
        DesktopWindow("Results", Modifier.weight(1f), fill = true, onClose = { model.home() }) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(C.hot, lerp(C.hot, C.white, 0.5f)))).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DeskIcon("stats", Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TText("Run complete", size = 18.sp, weight = FontWeight.Bold, color = onColor(C.hot))
                        TText(when { st.daily != null -> "Daily #${st.daily}"; session.isMulti -> "1v1 vs ${st.oppName ?: "?"}"; else -> "Solo run" },
                            size = 11.sp, color = onColor(C.hot).copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    TText("Score:", Modifier.padding(bottom = 5.dp), size = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    RollingNumber(t.my, size = 30.sp, color = C.amber)
                    TText(" of ${t.maxPts} points", Modifier.padding(bottom = 5.dp), size = 12.sp, color = C.dim)
                }
                TText("Average: ${oneDecimal(t.avg)} per question", size = 12.sp, color = C.dim)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TText("Rank:", size = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    RankBadge(t.rank, size = 16.sp)
                }
                Spacer(Modifier.height(4.dp))
                TText(t.rank.line, size = 12.sp, color = C.dim)
                val opp = st.oppName
                if (session.isMulti && opp != null) {
                    Spacer(Modifier.height(10.dp))
                    val o = st.oppTotal
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(if (t.my >= o) "info" else "warning", Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        TText(when { t.my > o -> "You win, ${t.my} to $o."; t.my < o -> "$opp wins, $o to ${t.my}."; else -> "Draw, ${t.my} to $o." },
                            size = 14.sp, weight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(14.dp))
                DeskTable(model, st.rounds)
                Spacer(Modifier.height(8.dp))
                TLink("Share result", { shareText(shareCard(st, t.rank)) }, color = C.hot)
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.End) {
                if (!session.isMulti) {
                    TButton("Play again", { model.replay(session) }, Modifier.width(136.dp), primary = true)
                    Spacer(Modifier.width(8.dp))
                }
                TButton("Close", { model.home() }, Modifier.width(110.dp))
            }
        }
    }
}

/** List view: bevelled column headers, one row per prompt; tap a row for rarer answers. */
@Composable
private fun DeskTable(model: AppModel, rounds: List<RoundView>) {
    Column(Modifier.fillMaxWidth().box(Style.DESKTOP, C.line, fieldColor(), glow = true).padding(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            HeaderCell("#", Modifier.width(30.dp))
            HeaderCell("Answer", Modifier.weight(1f))
            HeaderCell("Tier", Modifier.width(98.dp))
            HeaderCell("Pts", Modifier.width(44.dp))
        }
        for (r in rounds) DeskRow(model, r)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    TText(text, modifier.box(Style.DESKTOP, C.line, C.panel).padding(horizontal = 6.dp, vertical = 3.dp), size = 11.sp, maxLines = 1)
}

@Composable
private fun DeskRow(model: AppModel, r: RoundView) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(if (open) C.hot.copy(alpha = 0.12f) else Color.Transparent)
        .clickable(remember { MutableInteractionSource() }, null) { open = !open }) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TText("${r.index + 1}", Modifier.width(30.dp).padding(start = 6.dp), size = 12.sp)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                TText(answerText(r.me.tier, r.me.canonical, r.me.input), size = 12.sp, weight = FontWeight.Bold, color = tierColor(r.me.tier), maxLines = 1)
                TText(r.prompt, size = 10.sp, color = C.dim, maxLines = 1)
            }
            TText(r.me.tier.label, Modifier.width(98.dp).padding(start = 6.dp), size = 10.sp, color = tierColor(r.me.tier), maxLines = 2)
            TText(signed(r.me.points), Modifier.width(44.dp).padding(start = 6.dp), size = 12.sp)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
