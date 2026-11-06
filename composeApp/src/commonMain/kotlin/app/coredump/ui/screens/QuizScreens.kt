package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.dailyNumber
import app.coredump.data.History
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.Platform
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RollingNumber
import app.coredump.ui.Spinner
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
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// QUIZ SHOW: a bright mobile quiz app. Greeting + streak, a hero card for the daily run, chunky 3D tiles,
// progress dots and a circular countdown ring, result cards that slide up, a score ring with 0–3 stars.

/** Chunky tile with a darker base; pressing pushes the face down onto it. */
@Composable
private fun Tile3D(accent: Color, modifier: Modifier = Modifier, depth: Dp = 5.dp, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val down by animateDpAsState(if (pressed) depth else 0.dp, tween(70))
    val face = lerp(C.panel, accent, 0.13f)
    val base = lerp(C.panel, accent, 0.45f)
    Box(modifier.clickable(src, null) { haptic(true); onClick() }) {
        Box(Modifier.matchParentSize().padding(top = depth).box(Th.style, Color.Transparent, base))
        Column(Modifier.fillMaxWidth().padding(bottom = depth).offset(y = down).box(Th.style, accent.copy(alpha = 0.35f), face)
            .padding(16.dp), content = content)
    }
}

@Composable
private fun IconDot(emoji: String, accent: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).box(Th.style, Color.Transparent, accent.copy(alpha = 0.18f), pill = true), contentAlignment = Alignment.Center) {
        TText(emoji, size = (size.value * 0.48f).sp, align = TextAlign.Center)
    }
}

// ======================================================================= menu

@Composable
fun QuizMenu(model: AppModel) {
    val items = menuItems(model).associateBy { it.key }
    val today = dailyNumber(Platform.epochMillis())
    val played = History.playedDaily(today)
    val streakDays = streak(History.runs)
    val handle = model.settings.handle
    val accents = listOf(C.hot, C.white, C.blood, lerp(C.hot, C.white, 0.5f))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).box(Th.style, Color.Transparent, C.hot, pill = true), contentAlignment = Alignment.Center) {
                TText(handle.take(1).uppercase(), size = 20.sp, color = onColor(C.hot), weight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                TText("Hi, $handle 👋", size = 21.sp, weight = FontWeight.Bold, maxLines = 1)
                TText("Ready for today's quiz?", size = 13.sp, color = C.dim)
            }
            TText("🔥 $streakDays", Modifier.box(Th.style, C.line, C.panel, pill = true).padding(horizontal = 12.dp, vertical = 7.dp),
                size = 14.sp, weight = FontWeight.Bold)
        }
        CrashBanner(model)
        Spacer(Modifier.height(18.dp))

        // hero: daily run
        val heroIn = remember { Animatable(0f) }
        LaunchedEffect(Unit) { heroIn.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 260f)) }
        Column(Modifier.fillMaxWidth().graphicsLayer { translationY = (1f - heroIn.value) * 60f; alpha = heroIn.value.coerceIn(0f, 1f) }
            .box(Th.style, Color.Transparent, C.hot).padding(20.dp)) {
            val ink = onColor(C.hot)
            TText("DAILY RUN #$today", size = 11.sp, color = ink.copy(alpha = 0.8f), weight = FontWeight.Bold, spacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            TText(if (played != null) "You scored ${played.points} pts today ✓" else "7 prompts · the same for everyone",
                size = 20.sp, color = ink, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            TText(if (streakDays > 0) "Keep your $streakDays-day streak going" else "Start a streak today", size = 13.sp, color = ink.copy(alpha = 0.85f))
            Spacer(Modifier.height(14.dp))
            Tile3D(ink, Modifier.fillMaxWidth(), depth = 4.dp, onClick = { items["daily"]?.action?.invoke() }) {
                TText(if (played != null) "Play again" else "Play now", Modifier.fillMaxWidth(), size = 16.sp, color = ink,
                    weight = FontWeight.Bold, align = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(22.dp))
        TText("Play", size = 17.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        val grid = listOf(
            Triple("solo", "🎯", "Solo"), Triple("duel", "⚔️", "1v1"),
            Triple("stats", "📊", "Stats"), Triple("browse", "📚", "Lists"),
        )
        for (row in grid.chunked(2)) {
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { k, (key, emoji, label) ->
                    if (k > 0) Spacer(Modifier.width(12.dp))
                    val idx = grid.indexOfFirst { it.first == key }
                    val accent = accents[idx % accents.size]
                    val pop = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { delay(80L * idx + 120L); pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 380f)) }
                    Box(Modifier.weight(1f).graphicsLayer { scaleX = 0.7f + 0.3f * pop.value; scaleY = 0.7f + 0.3f * pop.value; alpha = pop.value.coerceIn(0f, 1f) }) {
                        Tile3D(accent, Modifier.fillMaxWidth(), onClick = { items[key]?.action?.invoke() }) {
                            IconDot(emoji, accent)
                            Spacer(Modifier.height(12.dp))
                            TText(label, size = 16.sp, weight = FontWeight.Bold)
                            TText(items[key]?.hint ?: "", size = 11.sp, color = C.dim, maxLines = 2)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(10.dp))
        TText("More", size = 17.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        for ((key, emoji, label) in listOf(Triple("theme", "🎨", "Theme"), Triple("settings", "⚙️", "Settings"), Triple("manual", "❓", "How to play"))) {
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).box(Th.style, C.line.copy(alpha = 0.7f), if (pressed) C.ghost else C.panel)
                .clickable(src, null) { items[key]?.action?.invoke() }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconDot(emoji, C.hot, 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    TText(label, size = 15.sp, weight = FontWeight.SemiBold)
                    TText(items[key]?.hint ?: "", size = 11.sp, color = C.dim, maxLines = 1)
                }
                TText("›", size = 22.sp, color = C.dim)
            }
        }
        Spacer(Modifier.height(16.dp))
        Panel("Library") { LibraryFooter(model, sources = false) }
        Spacer(Modifier.height(28.dp))
    }
}

// ======================================================================= play

@Composable
fun QuizPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TText("✕", Modifier.size(36.dp).box(Th.style, C.line, C.panel, pill = true)
                .clickable(remember { MutableInteractionSource() }, null) { session.pause(); model.confirmQuit = true }.padding(top = 7.dp),
                size = 16.sp, color = C.dim, align = TextAlign.Center)
            Spacer(Modifier.width(12.dp))
            ProgressDots(st, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Row(Modifier.box(Th.style, Color.Transparent, C.hot.copy(alpha = 0.14f), pill = true).padding(horizontal = 12.dp, vertical = 7.dp)) {
                RollingNumber(st.myTotal, size = 14.sp, color = C.hot, suffix = " pts")
            }
        }
        if (session.isMulti) {
            TText("vs ${st.oppName ?: "?"} · ${st.oppTotal} pts" + if (st.oppLocked && st.phase != Phase.REVEAL) " · answered" else "",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), size = 12.sp, color = C.dim, align = TextAlign.Center)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            CountdownRing(st, left, now)
            Spacer(Modifier.height(14.dp))
            if (st.phase == Phase.WAITING) {
                Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); TText("  ${st.message ?: "Connecting…"}", size = 13.sp, color = C.dim) }
            } else if (st.prompt.isNotEmpty()) {
                QuestionCard(st, a)
            }
            Spacer(Modifier.height(16.dp))
            when (st.phase) {
                Phase.TYPING -> if (!st.paused) {
                    AnswerField(a, st, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SuggestionChip(st, a)
                    Spacer(Modifier.height(8.dp))
                    AttemptChips(st)
                    Spacer(Modifier.height(12.dp))
                    TButton("Submit", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true, textSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    TText("Rare answers score more · wrong answers are free", size = 12.sp, color = C.dim, align = TextAlign.Center)
                }
                Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(C.hot); TText("  Answer locked · waiting for your opponent", size = 13.sp, color = C.dim)
                }
                Phase.REVEAL -> st.reveal?.let { v ->
                    ResultCard(v)
                    Column(Modifier.fillMaxWidth()) {
                        RevealExtras(v, session.isMulti)
                        Spacer(Modifier.height(10.dp))
                        DiscardAction(model, session, v.index)
                    }
                }
                Phase.ERROR -> Panel("Something went wrong", color = C.blood, titleColor = C.blood) { TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood) }
                else -> Unit
            }
        }
        PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

@Composable
private fun ProgressDots(st: MatchState, modifier: Modifier) {
    val t = rememberInfiniteTransition()
    val beat by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(650), RepeatMode.Reverse))
    val done = st.rounds.map { tierColor(it.me.tier) }
    val ghost = C.ghost
    val hot = C.hot
    val n = st.total.coerceAtLeast(1)
    Canvas(modifier.height(16.dp)) {
        val gap = 6.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(4f)
        for (i in 0 until n) {
            val x = i * (w + gap)
            val h = if (i == st.round && i >= done.size) size.height * 0.7f * beat else size.height * 0.5f
            val c = when { i < done.size -> done[i]; i == st.round -> hot; else -> ghost }
            drawRoundRect(c, Offset(x, (size.height - h) / 2f), Size(w, h), CornerRadius(h / 2f))
        }
    }
}

@Composable
private fun CountdownRing(st: MatchState, left: Long, now: Long) {
    val f = if (st.seconds <= 0) 1f else left / (st.seconds * 1000f)
    val danger = st.seconds > 0 && left < 5000 && isRunning(st)
    val pulse = if (danger && !LocalLook.current.reduceMotion) 1f + 0.06f * sin(now / 1000.0 * PI * 4).toFloat() else 1f
    val track = C.ghost
    val arc = if (f < 0.25f) C.blood else C.hot
    Box(Modifier.size(92.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 8.dp.toPx()
            val r = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, Offset(sw / 2, sw / 2), r, style = Stroke(sw))
            drawArc(arc, -90f, 360f * f.coerceIn(0f, 1f), false, Offset(sw / 2, sw / 2), r, style = Stroke(sw, cap = StrokeCap.Round))
        }
        TText(if (st.seconds <= 0) "∞" else "${(left + 999) / 1000}", size = 28.sp, weight = FontWeight.Bold,
            color = if (danger) C.blood else C.amber, align = TextAlign.Center)
    }
}

@Composable
private fun QuestionCard(st: MatchState, a: AnswerState) {
    val enter = remember(st.round) { Animatable(0f) }
    LaunchedEffect(st.round) { enter.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 300f)) }
    val shadow = Color.Black.copy(alpha = if (Th.pal.dark) 0.35f else 0.07f)
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationX = (1f - enter.value) * 120f + sin(a.shake.value * 30f) * 12f * a.shake.value; alpha = enter.value.coerceIn(0f, 1f) }
            .drawBehind { drawRoundRect(shadow, Offset(0f, 6.dp.toPx()), size, CornerRadius(16.dp.toPx())) }
            .box(Th.style, C.line.copy(alpha = 0.6f), C.panel).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TText(st.topic.replaceFirstChar { it.uppercase() }, Modifier.box(Th.style, Color.Transparent, C.hot.copy(alpha = 0.14f), pill = true)
            .padding(horizontal = 10.dp, vertical = 4.dp), size = 11.sp, color = C.hot, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        TText(st.prompt, size = 22.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        TText("Question ${st.round + 1} of ${st.total} · ${group(st.listSize)} answers", size = 12.sp, color = C.dim, align = TextAlign.Center)
    }
}

@Composable
private fun ResultCard(v: RoundView) {
    val me = v.me
    val good = me.tier.isValid
    val c = if (good) C.hot else C.blood
    val up = remember(v.index) { Animatable(0f) }
    val icon = remember(v.index) { Animatable(0f) }
    LaunchedEffect(v.index) { up.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 280f)) }
    LaunchedEffect(v.index) { delay(180); icon.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 420f)) }
    Column(
        Modifier.fillMaxWidth().graphicsLayer { translationY = (1f - up.value) * 80f; alpha = up.value.coerceIn(0f, 1f) }
            .box(Th.style, c.copy(alpha = 0.5f), lerp(C.panel, c, 0.08f), 1.5.dp).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(54.dp).graphicsLayer { scaleX = icon.value; scaleY = icon.value }.box(Th.style, Color.Transparent, c, pill = true),
            contentAlignment = Alignment.Center) {
            TText(if (good) "✓" else "✕", size = 26.sp, color = onColor(c), weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        TText(tierLine(me.tier), size = 15.sp, color = c, weight = FontWeight.Bold, align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        TText(headline(me), size = 24.sp, weight = FontWeight.Bold, align = TextAlign.Center)
        if (!good && me.tier == Tier.SEGFAULT) TText("You answered “${me.input}”", size = 12.sp, color = C.dim)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierBadge(me.tier, 11.sp)
            Spacer(Modifier.width(8.dp))
            TText("+${me.points} pts", Modifier.box(Th.style, Color.Transparent, C.hot.copy(alpha = 0.14f), pill = true)
                .padding(horizontal = 10.dp, vertical = 4.dp), size = 13.sp, color = C.hot, weight = FontWeight.Bold)
        }
    }
}

// ======================================================================= summary

/** 0–3 stars from the average per prompt (same cut-offs as the ON-CALL, DEBUGGER and ROOT ranks). */
fun starsOf(avg: Double): Int = when {
    avg >= 78 -> 3
    avg >= 48 -> 2
    avg >= 25 -> 1
    else -> 0
}

private fun DrawScope.star(center: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val a = -PI / 2 + PI * i / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = center.x + (cos(a) * rr).toFloat()
        val y = center.y + (sin(a) * rr).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}

@Composable
fun QuizSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    val stars = starsOf(t.avg)
    val fill = remember { Animatable(0f) }
    LaunchedEffect(t.my) { fill.animateTo(if (t.maxPts == 0) 0f else t.my / t.maxPts.toFloat(), tween(1600, easing = FastOutSlowInEasing)) }
    val starPop = remember { List(3) { Animatable(0f) } }
    LaunchedEffect(stars) { for (i in 0 until 3) { delay(if (i == 0) 900 else 220); starPop[i].animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 400f)) } }
    val hot = C.hot
    val ghost = C.ghost
    val gold = C.white

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TText(if (st.daily != null) "Daily run #${st.daily}" else "Results", Modifier.weight(1f), size = 17.sp, weight = FontWeight.Bold)
            TText("✕", Modifier.clickable(remember { MutableInteractionSource() }, null) { model.home() }.padding(8.dp), size = 18.sp, color = C.dim)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 14.dp.toPx()
                    val r = Size(size.width - sw, size.height - sw)
                    drawArc(ghost, 0f, 360f, false, Offset(sw / 2, sw / 2), r, style = Stroke(sw))
                    drawArc(hot, -90f, 360f * fill.value, false, Offset(sw / 2, sw / 2), r, style = Stroke(sw, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingNumber(t.my, size = 38.sp, color = C.amber, durationMs = 1600)
                    TText("of ${t.maxPts} points", size = 12.sp, color = C.dim)
                }
            }
            Spacer(Modifier.height(14.dp))
            Canvas(Modifier.width(150.dp).height(44.dp)) {
                for (i in 0 until 3) {
                    val c = Offset(size.width * (i + 0.5f) / 3f, size.height / 2f)
                    star(c, 19.dp.toPx(), ghost)
                    if (i < stars) star(c, 19.dp.toPx() * starPop[i].value, gold)
                }
            }
            Spacer(Modifier.height(8.dp))
            TText(t.rank.title.lowercase().replaceFirstChar { it.uppercase() }, size = 24.sp, weight = FontWeight.Bold)
            TText(t.rank.line.replaceFirstChar { it.uppercase() }, size = 13.sp, color = C.dim, align = TextAlign.Center)
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                Spacer(Modifier.height(10.dp))
                val o = st.oppTotal
                TText(when { t.my > o -> "You beat $opp! 🎉"; t.my < o -> "$opp wins this one"; else -> "It's a tie" } + "  ·  ${t.my} – $o",
                    size = 15.sp, color = if (t.my >= o) C.hot else C.blood, weight = FontWeight.Bold, align = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                StatChip("✓", "${t.valid}/${t.count}", "correct", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatChip("★", t.best?.label?.lowercase()?.replace('_', ' ') ?: "—", "best tier", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatChip("⌀", oneDecimal(t.avg), "per prompt", Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            for (r in st.rounds) QuizRoundCard(model, r)
            Spacer(Modifier.height(8.dp))
            TLink("Share result", { shareText(shareCard(st, t.rank)) }, color = C.hot)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("Play again", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(10.dp))
            }
            TButton("Home", { model.home() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatChip(icon: String, value: String, label: String, modifier: Modifier) {
    Column(modifier.box(Th.style, C.line.copy(alpha = 0.6f), C.panel).padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        TText(icon, size = 14.sp, color = C.hot)
        TText(value, size = 15.sp, weight = FontWeight.Bold, maxLines = 1)
        TText(label, size = 10.sp, color = C.dim, maxLines = 1)
    }
}

@Composable
private fun QuizRoundCard(model: AppModel, r: RoundView) {
    var open by remember { mutableStateOf(false) }
    val c = tierColor(r.me.tier)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .box(Th.style, C.line.copy(alpha = 0.6f), C.panel)
            .drawBehind { drawRoundRect(c, Offset.Zero, Size(5.dp.toPx(), size.height), CornerRadius(3.dp.toPx())) }
            .clickable(remember { MutableInteractionSource() }, null) { open = !open }
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TText("${r.index + 1}. ${r.prompt}", size = 12.sp, color = C.dim, maxLines = 2)
                TText(answerText(r.me.tier, r.me.canonical, r.me.input), size = 16.sp, weight = FontWeight.Bold, color = if (r.me.tier.isValid) C.amber else C.blood, maxLines = 1)
            }
            TText("+${r.me.points}", size = 15.sp, color = C.hot, weight = FontWeight.Bold)
            TText(if (open) "  ˄" else "  ˅", size = 14.sp, color = C.dim)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
