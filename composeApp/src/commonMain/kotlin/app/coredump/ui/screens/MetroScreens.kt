package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.data.History
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.LocalDisplay
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RankBadge
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
import kotlin.random.Random

// METRO: a departure board. Split-flap letters rattle into place, the menu is a list of departures with line
// bullets, each prompt is a station sign on a route line (a dot per prompt, the train glides to the next stop),
// and the results are a ticket plus the route you rode.

private const val FLAPS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

/** Line colours for the menu bullets (any transit map). */
private val LINES = listOf(0xFF0098D4, 0xFF00843D, 0xFFB26300, 0xFF9B0056, 0xFF7156A5, 0xFFE21836, 0xFF00A4A7, 0xFF6A7278).map { Color(it) }

private fun scrambleFlaps(t: String, settled: Int): String = buildString {
    for ((i, c) in t.withIndex()) append(if (i < settled || !c.isLetterOrDigit()) c else FLAPS[Random.nextInt(FLAPS.length)])
}

/**
 * Text on split-flap tiles: each letter rattles through the alphabet, settling left to right ([scramble] = false:
 * just shows the text). Tile size follows [textSize]. Wraps onto more rows when long.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitFlap(text: String, textSize: TextUnit = 18.sp, ink: Color = C.hot, tile: Color = C.panel, modifier: Modifier = Modifier,
              delayMs: Long = 0, scramble: Boolean = true) {
    val motion = scramble && !LocalLook.current.reduceMotion
    val chars = text.uppercase()
    var shown by remember(chars) { mutableStateOf(if (motion) scrambleFlaps(chars, 0) else chars) }
    LaunchedEffect(chars, motion) {
        if (!motion) { shown = chars; return@LaunchedEffect }
        delay(delayMs)
        for (k in 0..(chars.length + 4)) { shown = scrambleFlaps(chars, k - 4); delay(34) }
        shown = chars
    }
    val d = LocalDensity.current
    val w = with(d) { (textSize * 0.74f).toDp() }
    val h = with(d) { (textSize * 1.34f).toDp() }
    val seam = C.bg
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (c in shown) {
            if (c == ' ') Spacer(Modifier.width(w * 0.6f)) else Box(
                Modifier.size(w, h).background(tile, RoundedCornerShape(2.dp)).drawWithContent {
                    drawContent()
                    drawRect(seam.copy(alpha = 0.7f), Offset(0f, this.size.height / 2f - 0.5.dp.toPx()), Size(this.size.width, 1.dp.toPx()))
                },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(c.toString(), style = TextStyle(color = ink, fontFamily = LocalDisplay.current, fontWeight = FontWeight.Bold,
                    fontSize = textSize, textAlign = TextAlign.Center))
            }
        }
    }
}

/** Round line bullet with a number or short code. */
@Composable
private fun LineBullet(label: String, color: Color, size: Int = 28) {
    Box(Modifier.size(size.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        TText(label, size = (size * 0.46f).sp, color = onColor(color), weight = FontWeight.Bold)
    }
}

private fun metroLabel(it: Item): String = when (it.key) {
    "daily" -> dailyLabel(it.label, "DAILY")
    "solo" -> "SOLO"
    "duel" -> "1V1 LINK"
    "stats" -> "STATS"
    "browse" -> "ALL LINES"
    "theme" -> "THEME"
    "settings" -> "SETTINGS"
    "manual" -> "HOW IT WORKS"
    else -> it.label
}

// ======================================================================= menu

@Composable
fun MetroMenu(model: AppModel) {
    val items = menuItems(model)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(30.dp))
        AppTitle(40.sp)
        Spacer(Modifier.height(10.dp))
        TText("Departures · rare answers go further", size = 13.sp, color = C.dim)
        TText(if (History.games == 0) "No journeys yet." else "Best journey ${History.best} pts · ${History.games} rides", size = 11.sp, color = C.dim)
        CrashBanner(model)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().box(Th.style, C.line, C.panel)) {
            Row(Modifier.fillMaxWidth().background(C.hot, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                TText("DEPARTURES", Modifier.weight(1f), size = 12.sp, color = onColor(C.hot), weight = FontWeight.Bold, spacing = 2.sp)
                TText("PLATFORM", size = 10.sp, color = onColor(C.hot), weight = FontWeight.Bold, spacing = 1.sp)
            }
            items.forEachIndexed { i, it ->
                MetroRow(i, it)
                if (i < items.size - 1) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(C.line))
            }
        }
        Spacer(Modifier.height(18.dp))
        Panel("STATION INFO") { LibraryFooter(model, sources = false) }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun MetroRow(index: Int, item: Item) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    Row(
        Modifier.fillMaxWidth().background(if (pressed) C.hot.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(src, null) { haptic(true); item.action() }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBullet("${index + 1}", if (index == 0) C.hot else LINES[index % LINES.size])
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SplitFlap(metroLabel(item), 13.sp, ink = if (index == 0) C.hot else C.amber, tile = C.bg, delayMs = 40L * index)
            Spacer(Modifier.height(4.dp))
            TText(item.hint, size = 11.sp, color = C.dim, maxLines = 1)
        }
        val badge = item.badge
        if (badge != null) {
            TText(badge, Modifier.padding(start = 6.dp).background(C.hot, RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                size = 10.sp, color = onColor(C.hot), weight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        TText("${index + 1}", size = 20.sp, weight = FontWeight.Bold, color = C.hot)
    }
}

// ======================================================================= play

@Composable
fun MetroPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            LineBullet("${st.round + 1}", C.hot)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                TText(when { st.daily != null -> "DAILY #${st.daily}"; session.isMulti -> "1V1 · ${st.oppName ?: "?"}"; else -> "SOLO" },
                    size = 10.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.5.sp, maxLines = 1)
                TText("Stop ${st.round + 1} of ${st.total}", size = 15.sp, weight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                TText("POINTS", size = 9.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.sp)
                RollingNumber(st.myTotal, size = 18.sp, color = C.hot)
            }
            PauseQuit(model, session, st)
        }
        Box(Modifier.padding(horizontal = 16.dp)) { PeerLine(session, st) }
        RouteLine(st, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(30.dp))
        if (st.seconds > 0) DepartsIn(left, st.seconds)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (st.phase == Phase.WAITING) {
                Row(verticalAlignment = Alignment.CenterVertically) { Spinner(C.hot); TText("  ${st.message ?: "Train approaching…"}", size = 13.sp, color = C.dim) }
            } else if (st.prompt.isNotEmpty()) {
                key(st.round) { StationSign(st) }
            }
            Spacer(Modifier.height(16.dp))
            when (st.phase) {
                Phase.TYPING -> if (!st.paused) {
                    AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a), size = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    SuggestionChip(st, a)
                    Spacer(Modifier.height(8.dp))
                    AttemptChips(st)
                    Spacer(Modifier.height(14.dp))
                    TButton("Submit", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true, textSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    TText("Common answers are local trains. Rare ones run express.", size = 10.sp, color = C.dim)
                }
                Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(C.hot); TText("  Waiting on the platform for the other player…", size = 13.sp, color = C.dim)
                }
                Phase.REVEAL -> st.reveal?.let { v ->
                    MetroVerdict(v)
                    RevealExtras(v, session.isMulti)
                }
                Phase.ERROR -> Panel("SERVICE DISRUPTION", color = C.blood, titleColor = C.blood) {
                    TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood)
                }
                else -> Unit
            }
        }
        PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

/** The run as a route: a station per prompt (answered ones in their tier colour), the train glides between them. */
@Composable
private fun RouteLine(st: MatchState, modifier: Modifier) {
    val n = st.total.coerceAtLeast(1)
    val pos by animateFloatAsState(st.round.toFloat(), tween(700, easing = FastOutSlowInEasing))
    val hot = C.hot
    val panel = C.panel
    val line = C.line
    val done = st.rounds.map { tierColor(it.me.tier) }
    val motion = !LocalLook.current.reduceMotion
    val loop = rememberInfiniteTransition()
    val ping by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)))
    val round = st.round
    Canvas(modifier) {
        val y = size.height / 2f
        val pad = 8.dp.toPx()
        val w = size.width - 2 * pad
        fun x(i: Float) = pad + if (n == 1) w / 2f else w * i / (n - 1)
        drawLine(line, Offset(pad, y), Offset(pad + w, y), 6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(hot, Offset(pad, y), Offset(x(pos), y), 6.dp.toPx(), cap = StrokeCap.Round)
        for (i in 0 until n) {
            val c = Offset(x(i.toFloat()), y)
            drawCircle(panel, 6.dp.toPx(), c)
            drawCircle(if (i < done.size) done[i] else if (i <= round) hot else line, 6.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
            if (i < done.size) drawCircle(done[i], 3.dp.toPx(), c)
        }
        if (motion) {
            val cur = Offset(x(pos), y)
            drawCircle(hot.copy(alpha = 0.4f * (1f - ping)), 6.dp.toPx() + 8.dp.toPx() * ping, cur, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** "Departs in 23 s" on flap tiles plus a thin bar. */
@Composable
private fun DepartsIn(leftMs: Long, seconds: Int) {
    val s = ((leftMs + 999) / 1000).toInt()
    val low = leftMs in 1..5000
    val f = (leftMs / (seconds * 1000f)).coerceIn(0f, 1f)
    val c = if (low) C.blood else C.hot
    val track = C.ghost
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        TText("Departs in ", size = 11.sp, color = C.dim)
        SplitFlap(s.toString().padStart(2, '0'), 12.sp, ink = c, tile = C.panel, scramble = false)
        TText(" s", size = 11.sp, color = C.dim)
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.weight(1f).height(4.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2f))
            if (f > 0f) drawRoundRect(c, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(size.height / 2f))
        }
    }
}

/** Prompt on a station sign that slides in like a train pulling in. */
@Composable
private fun StationSign(st: MatchState) {
    val motion = !LocalLook.current.reduceMotion
    val slide = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { slide.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    Column(
        Modifier.fillMaxWidth().graphicsLayer { translationX = (1f - slide.value) * size.width * 0.3f; alpha = slide.value }
            .box(Th.style, C.line, C.panel),
    ) {
        Row(Modifier.fillMaxWidth().background(C.hot, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TText("STOP ${st.round + 1} · ${st.topic.uppercase()}", Modifier.weight(1f), size = 11.sp, color = onColor(C.hot), weight = FontWeight.Bold,
                spacing = 1.sp, maxLines = 1)
            TText("${group(st.listSize)} ANSWERS", size = 10.sp, color = onColor(C.hot).copy(alpha = 0.85f), weight = FontWeight.Bold)
        }
        TText(st.prompt, Modifier.padding(horizontal = 16.dp, vertical = 16.dp), size = 21.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun MetroVerdict(v: RoundView) {
    val me = v.me
    val c = tierColor(me.tier)
    Column(Modifier.fillMaxWidth().box(Th.style, C.line, C.panel).padding(16.dp)) {
        TText(tierLine(me.tier).uppercase(), size = 10.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        key(v.index) { SplitFlap(headline(me), 16.sp, ink = c, tile = C.bg) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierBadge(me.tier, 11.sp)
            Spacer(Modifier.weight(1f))
            TText("${signed(me.points)} pts", size = 20.sp, weight = FontWeight.Bold, color = C.hot)
        }
    }
}

// ======================================================================= summary

@Composable
fun MetroSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    Frame(model, "Journey complete", onBack = { model.home() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            Ticket(st, t)
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                Spacer(Modifier.height(12.dp))
                val o = st.oppTotal
                TText(when { t.my > o -> "You arrive first · ${t.my}–$o"; t.my < o -> "$opp arrives first · $o–${t.my}"; else -> "Same train · ${t.my}–$o" },
                    size = 15.sp, weight = FontWeight.Bold, color = if (t.my >= o) C.hot else C.blood)
            }
            Spacer(Modifier.height(18.dp))
            TText("ROUTE · tap a stop for rarer answers", size = 11.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            st.rounds.forEachIndexed { i, r -> RouteStop(model, r, first = i == 0, last = i == st.rounds.size - 1) }
            Spacer(Modifier.height(6.dp))
            TLink("Share journey", { shareText(shareCard(st, t.rank)) }, color = C.hot)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("Ride again", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(12.dp))
            }
            TButton("Exit", { model.home() }, Modifier.weight(1f))
        }
    }
}

/** Ticket with side notches and a perforated stub. */
@Composable
private fun Ticket(st: MatchState, t: RunTotals) {
    val bg = C.bg
    val panel = C.panel
    val line = C.line
    val motion = !LocalLook.current.reduceMotion
    val stamp = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { delay(500); stamp.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
    Column(
        Modifier.fillMaxWidth().drawBehind {
            val r = 10.dp.toPx()
            val y = size.height - 56.dp.toPx()
            drawRoundRect(panel, cornerRadius = CornerRadius(8.dp.toPx()))
            drawRoundRect(line, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawCircle(bg, r, Offset(0f, y))
            drawCircle(bg, r, Offset(size.width, y))
            drawLine(line, Offset(r + 4.dp.toPx(), y), Offset(size.width - r - 4.dp.toPx(), y), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        }.padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TText("FROM", size = 9.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.sp)
                TText("Stop 1", size = 15.sp, weight = FontWeight.Bold)
            }
            TText("→", size = 20.sp, color = C.hot, weight = FontWeight.Bold)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                TText("TO", size = 9.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.sp)
                TText("Stop ${t.count}", size = 15.sp, weight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        TText("FARE", size = 9.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            RollingNumber(t.my, size = 44.sp, color = C.hot, durationMs = 1800)
            TText(" / ${t.maxPts} pts", Modifier.padding(bottom = 8.dp), size = 12.sp, color = C.dim)
        }
        TText("Average ${oneDecimal(t.avg)} per stop", size = 11.sp, color = C.dim)
        Spacer(Modifier.height(14.dp))
        TText("CLASS", size = 9.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.graphicsLayer { val s = 1.4f - 0.4f * stamp.value; scaleX = s; scaleY = s; alpha = stamp.value.coerceIn(0f, 1f) }) {
            RankBadge(t.rank, size = 18.sp)
        }
        Spacer(Modifier.height(6.dp))
        TText(t.rank.line, size = 11.sp, color = C.dim)
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TText(if (st.daily != null) "DAILY #${st.daily}" else "SINGLE RIDE", Modifier.weight(1f), size = 11.sp, color = C.dim,
                weight = FontWeight.Bold, spacing = 1.5.sp)
            TText("▮▯▮▮▯▮▯▯▮▮▮▯▮▯▮", size = 12.sp, color = C.dim)
        }
    }
}

@Composable
private fun RouteStop(model: AppModel, r: RoundView, first: Boolean, last: Boolean) {
    var open by remember { mutableStateOf(false) }
    val c = tierColor(r.me.tier)
    val hot = C.hot
    val panel = C.panel
    Column(
        Modifier.fillMaxWidth().drawBehind {
            val x = 14.dp.toPx()
            val y = 20.dp.toPx()
            drawLine(hot, Offset(x, if (first) y else 0f), Offset(x, if (last) y else size.height), 5.dp.toPx())
            drawCircle(panel, 6.dp.toPx(), Offset(x, y))
            drawCircle(c, 6.dp.toPx(), Offset(x, y), style = Stroke(2.5.dp.toPx()))
        }.clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(start = 38.dp, top = 8.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TText(r.prompt, size = 11.sp, color = C.dim, maxLines = 2)
                TText(answerText(r.me.tier, r.me.canonical, r.me.input), size = 15.sp, color = c, weight = FontWeight.Bold, maxLines = 1)
            }
            TText(signed(r.me.points), size = 13.sp, weight = FontWeight.Bold)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
