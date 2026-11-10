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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
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
import kotlin.math.ceil
import kotlin.random.Random

// JACKPOT: a slot machine. A marquee with chasing bulbs, menu entries as little machines whose reel spins once,
// credits on an LED counter, a row of bulbs as the timer, three reels that spin and stop on the verdict, and a
// cash-out ticket with a barcode at the end.

// Plain geometric symbols only: card suits turn into colour emoji on some phones.
private val SYM = listOf("7", "★", "◆", "●", "▲", "BAR", "✦", "■")

/** The three reel symbols a tier lands on (three of a kind = rare). */
private fun reelsFor(t: Tier): List<String> = when (t) {
    Tier.ZERO_DAY -> listOf("7", "7", "7")
    Tier.HEISENBUG -> listOf("★", "★", "★")
    Tier.MEMORY_LEAK -> listOf("◆", "◆", "◆")
    Tier.LEGACY -> listOf("BAR", "BAR", "BAR")
    Tier.COPYPASTA -> listOf("●", "●", "▲")
    Tier.HELLO_WORLD -> listOf("▲", "●", "▲")
    else -> listOf("7", "●", "✗")
}

private fun symbolColor(s: String): Color = when (s) {
    "7" -> Color(0xFFE53935)
    "★" -> Color(0xFFFFC93C)
    "◆" -> Color(0xFF4FC3F7)
    "●" -> Color(0xFFFF5A7A)
    "▲" -> Color(0xFF66D17A)
    "✦" -> Color(0xFFB79CFF)
    "✗" -> C.blood
    else -> C.amber
}

private fun jackpotLabel(it: Item): String = when (it.key) {
    "daily" -> dailyLabel(it.label, "DAILY SPIN")
    "solo" -> "SOLO TABLE"
    "duel" -> "HEAD TO HEAD"
    "party" -> "MELTDOWN TABLE"
    "stats" -> "WINNINGS"
    "browse" -> "PAY TABLES"
    "theme" -> "DECOR"
    "settings" -> "SETTINGS"
    "manual" -> "HOW TO PLAY"
    else -> it.label
}

private fun symbolFor(key: String): String = when (key) {
    "daily" -> "7"; "solo" -> "◆"; "duel" -> "▲"; "party" -> "☢"; "stats" -> "★"; "browse" -> "BAR"; "theme" -> "✦"; "settings" -> "■"; else -> "●"
}

/** Frame with a ring of bulbs chasing around it. */
@Composable
private fun Marquee(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val motion = !LocalLook.current.reduceMotion
    val loop = rememberInfiniteTransition()
    val chase by loop.animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing)))
    val gold = C.line
    val bulb = C.white
    val panel = C.panel
    Box(
        modifier.drawBehind {
            val r = 14.dp.toPx()
            drawRoundRect(panel, cornerRadius = CornerRadius(r))
            drawRoundRect(gold, cornerRadius = CornerRadius(r), style = Stroke(2.dp.toPx()))
            val step = 15.dp.toPx()
            val inset = 8.dp.toPx()
            val w = size.width - 2 * inset
            val h = size.height - 2 * inset
            val per = 2 * (w + h)
            val n = (per / step).toInt().coerceAtLeast(4)
            val phase = if (motion) chase.toInt() else 0
            for (i in 0 until n) {
                val d = i * per / n
                val p = when {
                    d < w -> Offset(inset + d, inset)
                    d < w + h -> Offset(inset + w, inset + d - w)
                    d < 2 * w + h -> Offset(inset + w - (d - w - h), inset + h)
                    else -> Offset(inset, inset + h - (d - 2 * w - h))
                }
                val on = (i + phase) % 3 == 0
                if (on) drawCircle(bulb.copy(alpha = 0.22f), 4.5.dp.toPx(), p)
                drawCircle(if (on) bulb else gold.copy(alpha = 0.45f), 2.dp.toPx(), p)
            }
        }.padding(26.dp),
        contentAlignment = Alignment.Center, content = content,
    )
}

/**
 * One reel window: a strip of symbols scrolls past and stops on [final]. [spinKey] restarts the spin.
 */
@Composable
private fun Reel(final: String, seed: Int, modifier: Modifier = Modifier, spinKey: Any = Unit, delayMs: Long = 0, spinMs: Int = 900,
                 textSize: TextUnit = 22.sp) {
    val motion = !LocalLook.current.reduceMotion
    val strip = remember(spinKey, final) { val r = Random(seed * 31 + final.hashCode()); List(10) { SYM[r.nextInt(SYM.size)] } + final }
    val spin = remember(spinKey, final) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(spinKey, final, motion) { if (motion) { delay(delayMs); spin.animateTo(1f, tween(spinMs, easing = FastOutSlowInEasing)) } }
    var cell by remember { mutableIntStateOf(0) }
    val d = LocalDensity.current
    Box(modifier.onSizeChanged { cell = it.height }.box(Th.style, C.line, lerp(C.panel, Color.Black, 0.35f), 1.5.dp).clipToBounds()) {
        if (cell > 0) {
            val h = with(d) { cell.toDp() }
            Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = -spin.value * (strip.size - 1) * cell }) {
                for (s in strip) {
                    Box(Modifier.fillMaxWidth().height(h), contentAlignment = Alignment.Center) {
                        TText(s, size = if (s.length > 1) textSize * 0.5f else textSize, color = symbolColor(s), weight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ======================================================================= menu

@Composable
fun JackpotMenu(model: AppModel) {
    val items = menuItems(model)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(24.dp))
        Marquee(Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppTitle(38.sp)
                Spacer(Modifier.height(4.dp))
                TText("RARE ANSWERS PAY BIG", size = 11.sp, color = C.dim, weight = FontWeight.Bold, spacing = 3.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        TText(if (History.games == 0) "No spins yet." else "Best payout ${History.best} · ${History.games} sessions",
            Modifier.fillMaxWidth(), size = 11.sp, color = C.dim, align = TextAlign.Center)
        CrashBanner(model)
        Spacer(Modifier.height(16.dp))
        items.forEachIndexed { i, it -> JackpotMachine(i, it); Spacer(Modifier.height(10.dp)) }
        Spacer(Modifier.height(6.dp))
        Panel("CASHIER") { LibraryFooter(model, sources = false) }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun JackpotMachine(index: Int, item: Item) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val primary = index == 0
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(80))
    Row(
        Modifier.fillMaxWidth().graphicsLayer { translationY = press * 2.dp.toPx() }
            .box(Th.style, C.line, if (primary) lerp(C.panel, C.hot, 0.16f) else C.panel, 1.5.dp)
            .clickable(src, null) { haptic(true); item.action() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Reel(symbolFor(item.key), index, Modifier.size(46.dp), delayMs = 90L * index, spinMs = 700 + 60 * index)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            TText(jackpotLabel(item), size = 16.sp, weight = FontWeight.Bold, color = if (primary) C.hot else C.amber, spacing = 1.sp)
            TText(item.hint, size = 11.sp, color = C.dim, maxLines = 1)
        }
        val badge = item.badge
        if (badge != null) {
            TText(badge, Modifier.background(C.hot, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                size = 10.sp, color = onColor(C.hot), weight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        TText("▸", size = 16.sp, color = C.hot)
    }
}

// ======================================================================= play

@Composable
fun JackpotPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.box(Th.style, C.line, Color.Black.copy(alpha = 0.35f), 1.5.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
                TText("CREDITS", size = 9.sp, color = C.dim, weight = FontWeight.Bold, spacing = 2.sp)
                RollingNumber(st.myTotal, size = 20.sp, color = C.hot)
            }
            Spacer(Modifier.weight(1f))
            TText(if (st.daily != null) "DAILY #${st.daily} · " else "", size = 11.sp, color = C.dim, weight = FontWeight.Bold)
            TText("SPIN ${st.round + 1}/${st.total}", size = 13.sp, weight = FontWeight.Bold, spacing = 1.sp)
            PauseQuit(model, session, st)
        }
        Box(Modifier.padding(horizontal = 16.dp)) { PeerLine(session, st) }
        if (st.seconds > 0) BulbTimer(left, st.seconds)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (st.phase == Phase.WAITING) {
                Row(verticalAlignment = Alignment.CenterVertically) { Spinner(C.hot); TText("  ${st.message ?: "Shuffling…"}", size = 13.sp, color = C.dim) }
            } else if (st.prompt.isNotEmpty()) {
                key(st.round) { Payline(st) }
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
                    TButton("SPIN", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true, textSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    TText("Misses are free · rare answers pay up to 100", Modifier.fillMaxWidth(), size = 10.sp, color = C.dim, align = TextAlign.Center)
                }
                Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(C.hot); TText("  Bet placed. Waiting for the other player…", size = 13.sp, color = C.dim)
                }
                Phase.REVEAL -> st.reveal?.let { v ->
                    SlotVerdict(v)
                    RevealExtras(v, session.isMulti)
                }
                Phase.ERROR -> Panel("MACHINE TILT", color = C.blood, titleColor = C.blood) {
                    TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood)
                }
                else -> Unit
            }
        }
        PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

/** Time left as a row of bulbs going dark one by one; the last one blinks in the final 5 s. */
@Composable
private fun BulbTimer(leftMs: Long, seconds: Int) {
    val f = (leftMs / (seconds * 1000f)).coerceIn(0f, 1f)
    val n = 20
    val lit = ceil(f * n).toInt()
    val low = leftMs in 1..5000
    val blink = low && ((leftMs / 250) % 2 == 0L)
    val on = if (low) C.blood else C.white
    val off = C.line.copy(alpha = 0.35f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(12.dp)) {
            val step = size.width / n
            for (i in 0 until n) {
                val c = Offset(step * (i + 0.5f), size.height / 2f)
                val isOn = i < lit && !(blink && i == lit - 1)
                if (isOn) drawCircle(on.copy(alpha = 0.22f), 5.dp.toPx(), c)
                drawCircle(if (isOn) on else off, 2.6.dp.toPx(), c)
            }
        }
        TText("  ${(leftMs + 999) / 1000}s", Modifier.width(48.dp), size = 12.sp, color = if (low) C.blood else C.amber, weight = FontWeight.Bold)
    }
}

/** Prompt on the pay line; drops in and settles like a reel. */
@Composable
private fun Payline(st: MatchState) {
    val motion = !LocalLook.current.reduceMotion
    val drop = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { drop.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 300f)) }
    Column(
        Modifier.fillMaxWidth().graphicsLayer { translationY = (1f - drop.value) * -40.dp.toPx(); alpha = drop.value.coerceIn(0f, 1f) }
            .box(Th.style, C.line, C.panel, 1.5.dp, glow = true).padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        TText("${st.topic.uppercase()} · ${group(st.listSize)} ANSWERS IN PLAY", size = 10.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        TText(st.prompt, size = 21.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun SlotVerdict(v: RoundView) {
    val me = v.me
    val reels = reelsFor(me.tier)
    val win = me.tier.isValid
    val motion = !LocalLook.current.reduceMotion
    val show = remember(v.index) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(v.index) { if (motion) { delay(1350); show.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.box(Th.style, C.line, C.panel, 2.dp, glow = true).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reels.forEachIndexed { i, s ->
                Reel(s, i + v.index * 3, Modifier.size(64.dp, 76.dp), spinKey = v.index, delayMs = i * 200L, spinMs = 650 + i * 260, textSize = 34.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.graphicsLayer { val s = 0.85f + 0.15f * show.value; scaleX = s; scaleY = s; alpha = show.value.coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally) {
            TText(tierLine(me.tier), size = if (me.tier.heat >= 5) 26.sp else 20.sp, color = if (win) C.hot else C.blood,
                weight = FontWeight.Bold, spacing = 2.sp, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            TText(headline(me), size = 20.sp, weight = FontWeight.Bold, color = tierColor(me.tier), align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierBadge(me.tier, 11.sp)
                Spacer(Modifier.width(10.dp))
                TText(if (me.points > 0) "PAYS ${me.points}" else signed(me.points), size = 16.sp, weight = FontWeight.Bold, color = C.hot)
            }
        }
    }
}

// ======================================================================= summary

@Composable
fun JackpotSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    Frame(model, "CASH OUT", onBack = { model.home() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().box(Th.style, C.line, C.panel, 1.5.dp, glow = true).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                TText("CASH-OUT TICKET", size = 11.sp, color = C.dim, weight = FontWeight.Bold, spacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                RollingNumber(t.my, size = 52.sp, color = C.hot, durationMs = 2200)
                TText("CREDITS · ${t.maxPts} POSSIBLE", size = 10.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.sp)
                Spacer(Modifier.height(14.dp))
                RankBadge(t.rank, size = 20.sp)
                Spacer(Modifier.height(6.dp))
                TText(t.rank.line, size = 12.sp, color = C.dim, align = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Barcode(t.my + 7 * t.count, Modifier.fillMaxWidth().height(34.dp))
                Spacer(Modifier.height(4.dp))
                TText(if (st.daily != null) "DAILY SPIN #${st.daily}" else "VALID FOR BRAGGING ONLY", size = 9.sp, color = C.dim, spacing = 2.sp)
            }
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                Spacer(Modifier.height(12.dp))
                val o = st.oppTotal
                TText(when { t.my > o -> "YOU TAKE THE POT · ${t.my}–$o"; t.my < o -> "${opp.uppercase()} TAKES THE POT · $o–${t.my}"; else -> "SPLIT POT · ${t.my}–$o" },
                    Modifier.fillMaxWidth(), size = 16.sp, weight = FontWeight.Bold, color = if (t.my >= o) C.hot else C.blood, align = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Panel("PAY TABLE") { for (r in st.rounds) JackpotRow(model, r) }
            Spacer(Modifier.height(10.dp))
            TLink("Share winnings", { shareText(shareCard(st, t.rank)) }, color = C.hot)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("Play again", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(12.dp))
            }
            TButton("Leave table", { model.home() }, Modifier.weight(1f))
        }
    }
}

/** Decorative barcode, the same for the same [seed]. */
@Composable
private fun Barcode(seed: Int, modifier: Modifier) {
    val ink = C.amber
    val bars = remember(seed) { val r = Random(seed); List(46) { 1 + r.nextInt(3) } }
    Canvas(modifier) {
        val unit = size.width / (bars.sum() * 2f)
        var x = 0f
        for ((i, b) in bars.withIndex()) {
            if (i % 2 == 0) drawRect(ink, Offset(x, 0f), Size(unit * b, size.height))
            x += unit * b * 2f
        }
    }
}

@Composable
private fun JackpotRow(model: AppModel, r: RoundView) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(76.dp), verticalAlignment = Alignment.CenterVertically) {
                for (s in reelsFor(r.me.tier)) TText(s, Modifier.width(24.dp), size = if (s.length > 1) 8.sp else 15.sp, color = symbolColor(s),
                    weight = FontWeight.Bold, align = TextAlign.Center)
            }
            Column(Modifier.weight(1f)) {
                TText(r.prompt, size = 10.sp, color = C.dim, maxLines = 2)
                TText(answerText(r.me.tier, r.me.canonical, r.me.input), size = 14.sp, color = tierColor(r.me.tier), weight = FontWeight.Bold, maxLines = 1)
            }
            TText(signed(r.me.points), size = 13.sp, weight = FontWeight.Bold, color = C.hot)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
