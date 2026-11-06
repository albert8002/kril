package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Tier
import app.coredump.data.History
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.DecodeText
import app.coredump.ui.LocalDisplay
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RollingNumber
import app.coredump.ui.Spinner
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.box
import app.coredump.ui.group
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay

// ARCADE: a coin-op cabinet. HUD on top (1UP / HI-SCORE / TIME), attract-mode menu with a blinking
// PRESS START, stage intros (STAGE n → GO!), big rating words with floating points, combo counter,
// GAME OVER with a high-score table and a CONTINUE countdown.

/** On/off blink, [periodMs] per half cycle (always on when motion is reduced). */
@Composable
fun rememberBlink(periodMs: Int = 500): Boolean {
    val motion = !LocalLook.current.reduceMotion
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(motion, periodMs) {
        if (!motion) { on = true; return@LaunchedEffect }
        while (true) { delay(periodMs.toLong()); on = !on }
    }
    return on
}

/** Gradient italic display text (cabinet artwork). */
@Composable
private fun NeonWord(text: String, size: TextUnit, modifier: Modifier = Modifier, colors: List<Color> = listOf(C.hot, C.amber, C.white)) {
    val look = LocalLook.current
    BasicText(
        text, modifier,
        style = TextStyle(
            brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(500f, 120f)),
            fontFamily = LocalDisplay.current, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
            fontSize = size, letterSpacing = 1.sp, textAlign = TextAlign.Center,
            shadow = if (look.glow > 0f) Shadow(C.hot.copy(alpha = 0.75f), Offset.Zero, 20f * look.glow) else null,
        ),
    )
}

@Composable
private fun HudCell(label: String, value: String, modifier: Modifier = Modifier, labelColor: Color = C.hot, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier, horizontalAlignment = align) {
        TText(label, size = 11.sp, color = labelColor, weight = FontWeight.Bold, spacing = 2.sp)
        TText(value, size = 16.sp, color = C.white, weight = FontWeight.Bold, spacing = 1.sp, maxLines = 1)
    }
}

// ======================================================================= menu

private fun arcadeLabel(key: String, fallback: String) = when (key) {
    "daily" -> "DAILY STAGE"
    "solo" -> "1 PLAYER"
    "duel" -> "VS MODE"
    "stats" -> "RECORDS"
    "browse" -> "STAGES"
    "theme" -> "CABINET"
    "settings" -> "OPTIONS"
    "manual" -> "HOW TO PLAY"
    else -> fallback
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArcadeMenu(model: AppModel) {
    val items = menuItems(model)
    val blink = rememberBlink(520)
    val haptic = rememberHaptic()
    val last = History.runs.firstOrNull()?.points ?: 0
    val motion = !LocalLook.current.reduceMotion
    val pulse = rememberInfiniteTransition()
    val glow by pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            HudCell("1UP", group(last).padStart(6, '0'), Modifier.weight(1f), labelColor = if (blink) C.hot else C.hot.copy(alpha = 0.25f))
            HudCell("HI-SCORE", group(History.best).padStart(6, '0'), Modifier.weight(1f), align = Alignment.CenterHorizontally)
            HudCell("CREDIT", "99", Modifier.weight(1f), align = Alignment.End)
        }
        Spacer(Modifier.height(30.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AppTitle(46.sp) }
        Spacer(Modifier.height(6.dp))
        TText("RARE ANSWERS · HIGH SCORES", Modifier.fillMaxWidth(), size = 12.sp, color = C.amber, align = TextAlign.Center, spacing = 3.sp)
        Spacer(Modifier.height(22.dp))
        TText(if (blink) "PRESS START" else "", Modifier.fillMaxWidth().height(24.dp), size = 18.sp, color = C.white,
            weight = FontWeight.Bold, align = TextAlign.Center, spacing = 4.sp)
        CrashBanner(model)
        Spacer(Modifier.height(16.dp))

        // three big cabinet buttons
        for ((i, item) in items.take(3).withIndex()) {
            val enter = remember { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(Unit) { delay(120L + 90L * i); enter.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f)) }
            Box(Modifier.graphicsLayer { scaleX = 0.6f + 0.4f * enter.value; scaleY = 0.6f + 0.4f * enter.value; alpha = enter.value.coerceIn(0f, 1f) }) {
                TButton(
                    arcadeLabel(item.key, item.label) + (item.badge?.let { b -> if (b == "NEW") "  ★" else "  ✓" } ?: ""),
                    { haptic(true); item.action() },
                    Modifier.fillMaxWidth().graphicsLayer { if (i == 0) { alpha = 0.75f + 0.25f * glow } },
                    primary = i == 0, textSize = 17.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        // small pills, two per row
        for (row in items.drop(3).chunked(2)) {
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { k, it ->
                    if (k > 0) Spacer(Modifier.width(10.dp))
                    TButton(arcadeLabel(it.key, it.label), { haptic(false); it.action() }, Modifier.weight(1f), textSize = 12.sp)
                }
                if (row.size == 1) { Spacer(Modifier.width(10.dp)); Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }

        // high-score marquee
        Spacer(Modifier.height(12.dp))
        val top = History.runs.sortedByDescending { it.points }.take(5)
        val line = if (top.isEmpty()) "NO HIGH SCORES YET  ·  BE THE FIRST  ·  OBVIOUS ANSWERS SCORE LOW  ·  "
        else top.mapIndexed { i, r -> "${i + 1}. ${model.settings.handle.take(3).uppercase()} ${group(r.points)}" }.joinToString("   ★   ") + "   ★   "
        Box(Modifier.fillMaxWidth().box(Th.style, C.line, C.panel.copy(alpha = 0.6f)).clipToBounds().padding(vertical = 10.dp)) {
            TText(line + line, Modifier.basicMarquee(iterations = Int.MAX_VALUE), size = 13.sp, color = C.amber, weight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.height(20.dp))
        LibraryFooter(model, sources = false)
        Spacer(Modifier.height(16.dp))
        TText("© 2026 CORE DUMP  ·  INSERT COIN", Modifier.fillMaxWidth(), size = 10.sp, color = C.dim, align = TextAlign.Center, spacing = 2.sp)
        Spacer(Modifier.height(24.dp))
    }
}

// ======================================================================= play

/** Consecutive valid answers at the end of the run (the combo). */
fun comboOf(st: MatchState): Int {
    var n = 0
    for (r in st.rounds.asReversed()) { if (r.me.tier.isValid) n++ else break }
    return n
}

@Composable
fun ArcadePlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    val blink = rememberBlink(250)

    Column(Modifier.fillMaxSize()) {
        // HUD
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                TText("1UP", size = 11.sp, color = C.hot, weight = FontWeight.Bold, spacing = 2.sp)
                RollingNumber(st.myTotal, size = 18.sp, color = C.white)
                if (session.isMulti) {
                    TText("2UP ${st.oppName?.take(8)?.uppercase() ?: "?"}", size = 10.sp, color = C.dim, spacing = 1.sp)
                    RollingNumber(st.oppTotal, size = 13.sp, color = C.dim)
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                TText(if (st.daily != null) "DAILY" else "STAGE", size = 11.sp, color = C.hot, weight = FontWeight.Bold, spacing = 2.sp)
                TText("${st.round + 1}/${st.total}", size = 18.sp, color = C.white, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                TText("TIME", size = 11.sp, color = C.hot, weight = FontWeight.Bold, spacing = 2.sp)
                val secs = ((left + 999) / 1000).toInt()
                val danger = st.seconds > 0 && secs <= 5 && isRunning(st)
                TText(if (st.seconds <= 0) "∞" else secs.toString().padStart(2, '0'), size = 24.sp,
                    color = if (danger) (if (blink) C.blood else C.white) else C.white, weight = FontWeight.Bold)
            }
        }
        // pause / quit
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TText("◂ EXIT", Modifier.clickable(remember { MutableInteractionSource() }, null) { session.pause(); model.confirmQuit = true }
                .padding(vertical = 6.dp), size = 11.sp, color = C.dim, weight = FontWeight.Bold, spacing = 2.sp)
            Spacer(Modifier.weight(1f))
            if (st.canPause && st.phase == Phase.TYPING && !st.paused) {
                TText("PAUSE ‖", Modifier.clickable(remember { MutableInteractionSource() }, null) { session.pause() }
                    .padding(vertical = 6.dp), size = 11.sp, color = C.dim, weight = FontWeight.Bold, spacing = 2.sp)
            }
        }
        // smooth neon time bar
        if (st.seconds > 0) {
            val f = left / (st.seconds * 1000f)
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(6.dp).box(Th.style, Color.Transparent, C.ghost, pill = true)) {
                Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight()
                    .box(Th.style, Color.Transparent, if (f < 0.2f) C.blood else C.amber, glow = true, pill = true))
            }
        }

        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (st.phase == Phase.WAITING) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); TText("  ${st.message ?: "LINKING…"}", size = 13.sp, color = C.dim) }
                } else if (st.prompt.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().box(Th.style, C.hot, C.panel.copy(alpha = 0.8f), 2.dp, glow = true)
                        .padding(horizontal = 18.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        TText("★ ${st.topic.uppercase()} ★  ${group(st.listSize)} ANSWERS", size = 10.sp, color = C.hot, weight = FontWeight.Bold,
                            spacing = 2.sp, align = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        key(st.round) { DecodeText(st.prompt, size = 21.sp, color = C.white, durationMs = 500) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                when (st.phase) {
                    Phase.TYPING -> if (!st.paused) {
                        AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a))
                        Spacer(Modifier.height(10.dp))
                        SuggestionChip(st, a)
                        Spacer(Modifier.height(8.dp))
                        AttemptChips(st)
                        Spacer(Modifier.height(12.dp))
                        TButton("FIRE!", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true, textSize = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        TText("RARER ANSWER = MORE POINTS · MISSES ARE FREE", Modifier.fillMaxWidth(), size = 10.sp, color = C.dim,
                            align = TextAlign.Center, spacing = 1.sp)
                    }
                    Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner(C.hot); TText("  LOCKED IN · WAITING FOR 2UP", size = 13.sp, color = C.dim)
                    }
                    Phase.REVEAL -> st.reveal?.let { v ->
                        ArcadeRating(v.me.tier, v.me.points, comboOf(st), v.index)
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            TText("YOU: ${v.me.input.ifBlank { "—" }}", size = 11.sp, color = C.dim, maxLines = 1)
                            DecodeText(headline(v.me), size = 26.sp, color = tierColor(v.me.tier), durationMs = 450)
                        }
                        RevealExtras(v, session.isMulti)
                    }
                    Phase.ERROR -> Panel("ERROR", color = C.blood, titleColor = C.blood) { TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood) }
                    else -> Unit
                }
            }
            if (st.phase == Phase.TYPING) StageIntro(st.round, st.daily != null)
        }
        PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

/** "STAGE n" → "GO!" over the field when a prompt starts (does not block input). */
@Composable
private fun StageIntro(round: Int, daily: Boolean) {
    if (LocalLook.current.reduceMotion) return
    val p = remember(round) { Animatable(0f) }
    LaunchedEffect(round) { p.animateTo(1f, tween(1100, easing = LinearEasing)) }
    val v = p.value
    if (v >= 1f) return
    val go = v > 0.55f
    val local = if (go) (v - 0.55f) / 0.45f else v / 0.55f
    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        NeonWord(
            if (go) "GO!" else if (daily) "DAILY ${round + 1}" else "STAGE ${round + 1}",
            if (go) 56.sp else 40.sp,
            Modifier.graphicsLayer {
                val s = if (go) 0.6f + local * 0.9f else 1.6f - 0.6f * minOf(1f, local * 2f)
                scaleX = s; scaleY = s
                alpha = if (go) 1f - local else minOf(1f, local * 3f)
            },
        )
    }
}

/** EXCELLENT! / MISS with a spring, "+60" floating up, combo under it. */
@Composable
private fun ArcadeRating(tier: Tier, points: Int, combo: Int, round: Int) {
    val pop = remember(round) { Animatable(0f) }
    val rise = remember(round) { Animatable(0f) }
    LaunchedEffect(round) {
        pop.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = 380f))
    }
    LaunchedEffect(round) { delay(250); rise.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxWidth().height(116.dp), contentAlignment = Alignment.Center) {
        val good = tier.isValid
        NeonWord(
            tierLine(tier), if (tier.heat >= 5) 46.sp else 38.sp,
            Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; rotationZ = (1f - pop.value) * -10f },
            colors = if (good) listOf(C.hot, C.amber, C.white) else listOf(C.blood, C.blood, C.hot),
        )
        if (points > 0) {
            TText("+$points", Modifier.align(Alignment.TopEnd).padding(end = 18.dp)
                .graphicsLayer { translationY = 40f - rise.value * 90f; alpha = 1f - rise.value * 0.85f },
                size = 22.sp, color = C.white, weight = FontWeight.Bold)
        }
        if (combo >= 2 && good) {
            TText("COMBO ×$combo", Modifier.align(Alignment.BottomCenter).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                size = 13.sp, color = C.amber, weight = FontWeight.Bold, spacing = 3.sp)
        }
    }
}

// ======================================================================= summary

@Composable
fun ArcadeSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    val blink = rememberBlink(400)
    val haptic = rememberHaptic()
    val cleared = t.count > 0 && t.valid == t.count
    // runs are stored newest first; this run is the newest one when its points match
    val current = History.runs.firstOrNull()?.takeIf { it.points == t.my }
    val table = History.runs.sortedByDescending { it.points }.take(10)
    val newHigh = current != null && table.firstOrNull() === current

    var count by remember { mutableIntStateOf(9) }
    LaunchedEffect(Unit) { while (count > 0) { delay(1000); count-- } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                NeonWord(if (cleared) "ALL CLEAR!" else "GAME OVER", 46.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                RollingNumber(t.my, size = 38.sp, color = C.white, durationMs = 1800)
                TText("  PTS", Modifier.padding(bottom = 6.dp), size = 14.sp, color = C.hot, weight = FontWeight.Bold)
            }
            TText("RANK  ${t.rank.title}  ·  AVG ${oneDecimal(t.avg)}", Modifier.fillMaxWidth(), size = 12.sp, color = C.amber,
                align = TextAlign.Center, spacing = 2.sp)
            if (newHigh) {
                Spacer(Modifier.height(8.dp))
                TText(if (blink) "★ NEW HIGH SCORE ★" else "", Modifier.fillMaxWidth().height(22.dp), size = 16.sp, color = C.white,
                    weight = FontWeight.Bold, align = TextAlign.Center, spacing = 2.sp)
            }
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                Spacer(Modifier.height(10.dp))
                val o = st.oppTotal
                TText(
                    when { t.my > o -> "1UP WINS!"; t.my < o -> "2UP ${opp.uppercase()} WINS"; else -> "DRAW GAME" } + "   ${t.my} – $o",
                    Modifier.fillMaxWidth(), size = 16.sp, color = if (t.my >= o) C.hot else C.blood, weight = FontWeight.Bold, align = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(18.dp))
            Panel("HIGH SCORES") {
                Row {
                    TText("RANK", Modifier.width(52.dp), size = 10.sp, color = C.hot, spacing = 1.sp)
                    TText("SCORE", Modifier.weight(1f), size = 10.sp, color = C.hot, spacing = 1.sp)
                    TText("NAME", Modifier.width(52.dp), size = 10.sp, color = C.hot, spacing = 1.sp)
                    TText("MODE", Modifier.width(56.dp), size = 10.sp, color = C.hot, spacing = 1.sp, align = TextAlign.End)
                }
                Spacer(Modifier.height(6.dp))
                if (table.isEmpty()) TText("—", size = 12.sp, color = C.dim)
                table.forEachIndexed { i, r ->
                    val me = r === current
                    val c = if (me) (if (blink) C.white else C.hot) else if (i == 0) C.amber else C.dim
                    Row(Modifier.padding(vertical = 2.dp)) {
                        TText(ordinal(i + 1), Modifier.width(52.dp), size = 13.sp, color = c, weight = FontWeight.Bold)
                        TText(group(r.points).padStart(6, ' '), Modifier.weight(1f), size = 13.sp, color = c, weight = FontWeight.Bold)
                        TText(model.settings.handle.take(3).uppercase(), Modifier.width(52.dp), size = 13.sp, color = c, weight = FontWeight.Bold)
                        TText(r.mode.uppercase(), Modifier.width(56.dp), size = 11.sp, color = c, align = TextAlign.End)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TText("STAGE RESULTS · TAP FOR BONUS ANSWERS", size = 10.sp, color = C.dim, spacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            for (r in st.rounds) ArcadeStageRow(model, r)
            Spacer(Modifier.height(12.dp))
            TButton("SHARE SCORE", { shareText(shareCard(st, t.rank)) }, Modifier.fillMaxWidth(), textSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton(if (count > 0) "CONTINUE?  $count" else "PLAY AGAIN", { haptic(true); model.replay(session) },
                    Modifier.fillMaxWidth(), primary = true, textSize = 18.sp)
                Spacer(Modifier.height(10.dp))
            }
            TButton("EXIT TO TITLE", { model.home() }, Modifier.fillMaxWidth(), textSize = 13.sp)
        }
    }
}

private fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "TH"
    n % 10 == 1 -> "ST"
    n % 10 == 2 -> "ND"
    n % 10 == 3 -> "RD"
    else -> "TH"
}

@Composable
private fun ArcadeStageRow(model: AppModel, r: app.coredump.core.RoundView) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).box(Th.style, if (open) C.hot else C.line, C.panel.copy(alpha = 0.55f))
        .clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TText("ST${r.index + 1}", Modifier.width(40.dp), size = 11.sp, color = C.hot, weight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                TText(r.prompt, size = 11.sp, color = C.dim, maxLines = 1)
                TText(answerText(r.me.tier, r.me.canonical, r.me.input), size = 14.sp, color = tierColor(r.me.tier), weight = FontWeight.Bold, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                TText(tierLine(r.me.tier), size = 10.sp, color = tierColor(r.me.tier), weight = FontWeight.Bold)
                TText("+${r.me.points}", size = 14.sp, color = C.white, weight = FontWeight.Bold)
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
