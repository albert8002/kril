package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
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
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay
import kotlin.math.max

// ABYSS (inspired by deep-sea rare-answer games): every point takes you 2 m deeper. The menu is a descent line
// with depth markers; play has a depth rail, an air line for the timer and a sonar ping on each verdict;
// the results are a dive log with a depth profile. Motion is slow and soft: things sink into place.

/** Metres of depth per point (unit: m / point). */
private const val M_PER_POINT = 2

/** Ocean zone for a depth in metres. */
private fun zoneOf(m: Int): String = when {
    m < 200 -> "SUNLIGHT ZONE"
    m < 1000 -> "TWILIGHT ZONE"
    m < 4000 -> "MIDNIGHT ZONE"
    else -> "ABYSSAL ZONE"
}

private fun metres(points: Int): String {
    val m = points * M_PER_POINT
    return when { m > 0 -> "+$m m"; m < 0 -> "−${-m} m"; else -> "±0 m" }
}

private fun abyssLabel(it: Item): String = when (it.key) {
    "daily" -> dailyLabel(it.label, "TODAY'S DESCENT")
    "solo" -> "FREE DIVE"
    "duel" -> "BUDDY DIVE"
    "stats" -> "DIVE LOG"
    "browse" -> "SPECIMENS"
    "theme" -> "WATER COLOUR"
    "settings" -> "SETTINGS"
    "manual" -> "HOW DEPTH WORKS"
    else -> it.label
}

// ======================================================================= menu

@Composable
fun AbyssMenu(model: AppModel) {
    val items = menuItems(model)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(44.dp))
        AppTitle(40.sp)
        Spacer(Modifier.height(8.dp))
        TText("The rarer the answer, the deeper you sink.", size = 13.sp, color = C.dim)
        Spacer(Modifier.height(4.dp))
        TText(if (History.games == 0) "No dives yet." else "Deepest dive ${group(History.best * M_PER_POINT)} m · ${History.games} dives",
            size = 11.sp, color = C.dim)
        CrashBanner(model)
        Spacer(Modifier.height(26.dp))
        items.forEachIndexed { i, it -> DepthMarker(i, items.size, it) }
        Spacer(Modifier.height(20.dp))
        Panel("ON BOARD") { LibraryFooter(model, sources = false) }
        Spacer(Modifier.height(36.dp))
    }
}

/** One menu entry hanging on the descent line; the first one glows and pings. */
@Composable
private fun DepthMarker(index: Int, count: Int, item: Item) {
    val motion = !LocalLook.current.reduceMotion
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val enter = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { delay(90L * index + 120L); enter.animateTo(1f, tween(640, easing = FastOutSlowInEasing)) }
    val loop = rememberInfiniteTransition()
    val ping by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)))
    val primary = index == 0
    val hot = C.hot
    val line = C.line
    val bg = C.bg
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f); translationY = (1f - enter.value) * -18.dp.toPx() }
            .drawBehind {
                val x = 11.dp.toPx()
                val cy = size.height / 2f
                drawLine(line, Offset(x, if (index == 0) cy else 0f), Offset(x, if (index == count - 1) cy else size.height), 1.dp.toPx())
                if (pressed) drawRoundRect(hot.copy(alpha = 0.09f), Offset(26.dp.toPx(), 2.dp.toPx()),
                    Size(size.width - 26.dp.toPx(), size.height - 4.dp.toPx()), CornerRadius(14.dp.toPx()))
                if (primary) {
                    val q = if (motion) ping else 0f
                    drawCircle(hot.copy(alpha = 0.35f * (1f - q)), 5.dp.toPx() + 10.dp.toPx() * q, Offset(x, cy), style = Stroke(1.dp.toPx()))
                    drawCircle(hot, 5.dp.toPx(), Offset(x, cy))
                } else {
                    drawCircle(bg, 4.dp.toPx(), Offset(x, cy))
                    drawCircle(hot.copy(alpha = 0.7f), 4.dp.toPx(), Offset(x, cy), style = Stroke(1.dp.toPx()))
                }
            }
            .clickable(src, null) { haptic(true); item.action() }
            .padding(start = 38.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TText(abyssLabel(item), size = 16.sp, color = if (primary) hot else C.amber, weight = FontWeight.SemiBold, spacing = 1.5.sp)
            TText(item.hint, size = 11.sp, color = C.dim, maxLines = 1)
        }
        val badge = item.badge
        if (badge != null) {
            TText(badge, Modifier.padding(end = 8.dp).box(Style.ABYSS, hot, Color.Transparent, pill = true).padding(horizontal = 8.dp, vertical = 3.dp),
                size = 10.sp, color = hot, weight = FontWeight.SemiBold)
        }
        TText("${(index + 1) * 200} m", size = 10.sp, color = C.dim, spacing = 1.sp)
    }
}

// ======================================================================= play

@Composable
fun AbyssPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    val motion = !LocalLook.current.reduceMotion
    val depth = st.myTotal * M_PER_POINT
    val maxDepth = (st.total.coerceAtLeast(1) * Tier.ZERO_DAY.points * M_PER_POINT)
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            DepthRail(depth, maxDepth, st, Modifier.fillMaxHeight().width(40.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TText(if (st.daily != null) "DESCENT #${st.daily}" else "DIVE", size = 10.sp, color = C.dim, spacing = 3.sp)
                    TText("  ${st.round + 1}/${st.total}", size = 12.sp, weight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    RollingNumber(depth, size = 18.sp, color = C.hot, suffix = " m")
                    PauseQuit(model, session, st)
                }
                Box(Modifier.padding(start = 4.dp, end = 16.dp)) { PeerLine(session, st) }
                if (st.seconds > 0) AirLine(left, st.seconds)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 4.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                    if (st.phase == Phase.WAITING) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Spinner(C.hot); TText("  ${st.message ?: "Descending…"}", size = 13.sp, color = C.dim) }
                    } else if (st.prompt.isNotEmpty()) {
                        key(st.round) {
                            val sink = remember { Animatable(if (motion) 0f else 1f) }
                            LaunchedEffect(Unit) { sink.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
                            Column(
                                Modifier.fillMaxWidth().graphicsLayer { alpha = sink.value; translationY = (1f - sink.value) * -20.dp.toPx() }
                                    .box(Th.style, C.line, C.panel.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 16.dp),
                            ) {
                                TText("${st.topic.uppercase()} · ${group(st.listSize)} KNOWN ANSWERS", size = 9.sp, color = C.dim, spacing = 2.sp)
                                Spacer(Modifier.height(10.dp))
                                TText(st.prompt, size = 21.sp, weight = FontWeight.SemiBold, color = C.amber)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    when (st.phase) {
                        Phase.TYPING -> if (!st.paused) {
                            AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a), size = 18.sp)
                            Spacer(Modifier.height(10.dp))
                            SuggestionChip(st, a)
                            Spacer(Modifier.height(8.dp))
                            AttemptChips(st)
                            Spacer(Modifier.height(14.dp))
                            TButton("Dive", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true, textSize = 16.sp)
                            Spacer(Modifier.height(10.dp))
                            TText("Misses only cost air. Rare answers sink you deeper.", size = 10.sp, color = C.dim)
                        }
                        Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(C.hot); TText("  Holding depth… waiting for your buddy.", size = 13.sp, color = C.dim)
                        }
                        Phase.REVEAL -> st.reveal?.let { v ->
                            AbyssVerdict(v)
                            RevealExtras(v, session.isMulti)
                        }
                        Phase.ERROR -> Panel("SIGNAL LOST", color = C.blood, titleColor = C.blood) {
                            TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood)
                        }
                        else -> Unit
                    }
                }
                PlayActions(model, session, st, Modifier.padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 14.dp))
            }
        }
        if (st.phase == Phase.REVEAL) st.reveal?.let { SonarPing(it.index, tierColor(it.me.tier)) }
    }
}

/** Remaining time as a thin air line ("O₂"), red in the last 5 s. */
@Composable
private fun AirLine(leftMs: Long, seconds: Int) {
    val f = (leftMs / (seconds * 1000f)).coerceIn(0f, 1f)
    val low = leftMs in 1..5000
    val c = if (low) C.blood else C.hot
    val track = C.ghost
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TText("O₂", size = 10.sp, color = C.dim, weight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(4.dp)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = r)
            if (f > 0f) drawRoundRect(c, size = Size(size.width * f, size.height), cornerRadius = r)
        }
        TText("  ${(leftMs + 999) / 1000}s", Modifier.width(44.dp), size = 11.sp, color = c)
    }
}

/** Vertical depth gauge: ticks, a dot per answered prompt, the diver's current depth sinking smoothly. */
@Composable
private fun DepthRail(depth: Int, maxDepth: Int, st: MatchState, modifier: Modifier) {
    val f by animateFloatAsState((depth / maxDepth.toFloat()).coerceIn(0f, 1f), tween(1200, easing = FastOutSlowInEasing))
    val line = C.line
    val hot = C.hot
    val marks = remember(st.rounds.size) {
        var sum = 0
        st.rounds.map { sum += it.me.points.coerceAtLeast(0) * M_PER_POINT; sum / maxDepth.toFloat() }
    }
    val colors = st.rounds.map { tierColor(it.me.tier) }
    Canvas(modifier.padding(vertical = 18.dp)) {
        val x = size.width / 2f + 4.dp.toPx()
        drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        for (i in 0..10) {
            val y = size.height * i / 10f
            drawLine(line, Offset(x - (if (i % 5 == 0) 6.dp else 3.dp).toPx(), y), Offset(x, y), 1.dp.toPx())
        }
        marks.forEachIndexed { i, m -> if (i < colors.size) drawCircle(colors[i], 2.5.dp.toPx(), Offset(x, size.height * m.coerceIn(0f, 1f))) }
        val y = size.height * f
        drawCircle(hot.copy(alpha = 0.18f), 9.dp.toPx(), Offset(x, y))
        drawCircle(hot, 4.dp.toPx(), Offset(x, y))
    }
}

@Composable
private fun AbyssVerdict(v: RoundView) {
    val me = v.me
    val c = tierColor(me.tier)
    val motion = !LocalLook.current.reduceMotion
    val rise = remember(v.index) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(v.index) { delay(250); rise.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    Column(
        Modifier.fillMaxWidth().graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * -14.dp.toPx() }
            .box(Th.style, c.copy(alpha = 0.6f), C.panel.copy(alpha = 0.6f), glow = me.tier.heat >= 4).padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        TText(tierLine(me.tier).uppercase(), size = 10.sp, color = c, spacing = 3.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        TText(headline(me), size = 26.sp, color = c, weight = FontWeight.SemiBold, glow = 1.2f)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierBadge(me.tier, 11.sp)
            Spacer(Modifier.weight(1f))
            TText(metres(me.points), size = 18.sp, color = C.hot, weight = FontWeight.SemiBold)
        }
    }
}

/** Three soft sonar rings spreading once from the verdict. */
@Composable
private fun SonarPing(round: Int, color: Color) {
    if (LocalLook.current.reduceMotion) return
    val p = remember(round) { Animatable(0f) }
    LaunchedEffect(round) { p.animateTo(1f, tween(1700, easing = LinearEasing)) }
    if (p.value >= 1f) return
    Canvas(Modifier.fillMaxSize()) {
        val o = Offset(size.width * 0.55f, size.height * 0.45f)
        val reach = max(size.width, size.height) * 0.7f
        for (i in 0 until 3) {
            val q = (p.value - i * 0.18f).coerceIn(0f, 1f)
            if (q <= 0f || q >= 1f) continue
            drawCircle(color.copy(alpha = 0.3f * (1f - q)), reach * FastOutSlowInEasing.transform(q), o, style = Stroke(1.5.dp.toPx()))
        }
    }
}

// ======================================================================= summary

@Composable
fun AbyssSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    val depth = t.my * M_PER_POINT
    val after = remember(st.rounds.size) { var s = 0; st.rounds.map { s += it.me.points; s * M_PER_POINT } }
    Frame(model, "DIVE LOG", onBack = { model.home() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            TText(if (st.daily != null) "TODAY'S DESCENT #${st.daily}" else if (session.isMulti) "BUDDY DIVE" else "FREE DIVE",
                size = 10.sp, color = C.dim, spacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            RollingNumber(depth, size = 54.sp, color = C.hot, suffix = " m", durationMs = 2200)
            TText(zoneOf(depth), size = 12.sp, color = C.amber, spacing = 4.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            RankBadge(t.rank, size = 20.sp)
            Spacer(Modifier.height(8.dp))
            TText(t.rank.line, size = 12.sp, color = C.dim)
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                Spacer(Modifier.height(12.dp))
                val o = st.oppTotal
                TText(when {
                    t.my > o -> "You went deeper than $opp · ${depth} m vs ${o * M_PER_POINT} m"
                    t.my < o -> "$opp went deeper · ${o * M_PER_POINT} m vs ${depth} m"
                    else -> "Same depth as $opp · ${depth} m"
                }, size = 14.sp, color = if (t.my >= o) C.hot else C.blood, weight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            Panel("DIVE PROFILE") { DiveProfile(st.rounds) }
            Spacer(Modifier.height(10.dp))
            st.rounds.forEachIndexed { i, r -> AbyssLogRow(model, r, after.getOrElse(i) { 0 }) }
            Spacer(Modifier.height(6.dp))
            TLink("Share this dive", { shareText(shareCard(st, t.rank)) }, color = C.hot)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("Dive again", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(12.dp))
            }
            TButton("Surface", { model.home() }, Modifier.weight(1f))
        }
    }
}

/** Depth over the run: one point per prompt, drawn left to right, each answer dot in its tier colour. */
@Composable
private fun DiveProfile(rounds: List<RoundView>) {
    val motion = !LocalLook.current.reduceMotion
    val draw = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    val hot = C.hot
    val line = C.line
    val pts = remember(rounds.size) { var s = 0; listOf(0) + rounds.map { s += it.me.points.coerceAtLeast(0); s } }
    val colors = rounds.map { tierColor(it.me.tier) }
    val top = (rounds.size * Tier.ZERO_DAY.points).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        val n = pts.size - 1
        if (n <= 0) return@Canvas
        fun at(i: Int) = Offset(w * i / n, h * pts[i] / top.toFloat())
        for (k in 1..3) drawLine(line.copy(alpha = 0.6f), Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
        val shown = draw.value * n
        val path = Path().apply { moveTo(0f, 0f) }
        var i = 1
        while (i <= n && i - 1 < shown) {
            val a = at(i - 1)
            val b = at(i)
            val f = (shown - (i - 1)).coerceIn(0f, 1f)
            path.lineTo(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            i++
        }
        drawPath(path, hot, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
        for (j in 1..n) if (j <= shown + 0.001f && j - 1 < colors.size) drawCircle(colors[j - 1], 3.5.dp.toPx(), at(j))
    }
}

@Composable
private fun AbyssLogRow(model: AppModel, r: RoundView, depthAfter: Int) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TText("${group(depthAfter)} m", Modifier.width(76.dp), size = 11.sp, color = C.dim)
            Column(Modifier.weight(1f)) {
                TText(r.prompt, size = 11.sp, color = C.dim, maxLines = 2)
                TText(answerText(r.me.tier, r.me.canonical, r.me.input), size = 15.sp, color = tierColor(r.me.tier), weight = FontWeight.SemiBold, maxLines = 1)
            }
            TText(signed(r.me.points), size = 13.sp, color = C.amber)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
        Spacer(Modifier.height(8.dp))
        Divider()
    }
}
