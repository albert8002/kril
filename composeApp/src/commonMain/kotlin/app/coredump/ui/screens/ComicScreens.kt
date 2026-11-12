package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.TierBadge
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
import app.coredump.ui.onColor
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay

// COMIC: punk collage. Ransom-note titles, slanted menu bars that slam in with overshoot, tilted panels,
// NOPE! / JACKPOT!! starbursts, a slash wipe on every verdict, a giant slanted score.

@Composable
private fun Display(text: String, size: TextUnit, color: Color = C.amber, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    BasicText(text, modifier, style = TextStyle(color = color, fontFamily = LocalDisplay.current, fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic, fontSize = size, textAlign = align))
}

/** Strip of tape with a caption. */
@Composable
private fun Tape(text: String, tilt: Float = 2f, fill: Color = C.white) {
    TText(text, Modifier.graphicsLayer { rotationZ = tilt }.background(fill).padding(horizontal = 10.dp, vertical = 5.dp),
        size = 12.sp, color = onColor(fill), weight = FontWeight.Bold, spacing = 1.sp, glow = 0f)
}

// ======================================================================= menu

private fun comicLabel(key: String, fallback: String) = when (key) {
    "daily" -> "DAILY HIT"
    "solo" -> "SOLO"
    "duel" -> "1V1 BRAWL"
    "party" -> "FREE-FOR-ALL"
    "friends" -> "THE GANG"
    "stats" -> "SCORECARD"
    "browse" -> "THE LISTS"
    "theme" -> "RESTYLE"
    "settings" -> "SETTINGS"
    "manual" -> "HOW IT WORKS"
    else -> fallback
}

@Composable
fun ComicMenu(model: AppModel) {
    val items = menuItems(model)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(26.dp))
        Box(Modifier.graphicsLayer { rotationZ = -3f }) { RansomText("CORE DUMP", 38.sp, seed = 11) }
        Spacer(Modifier.height(10.dp))
        Tape("NAME THE ANSWER NOBODY ELSE WILL")
        CrashBanner(model)
        Spacer(Modifier.height(22.dp))
        items.forEachIndexed { i, it ->
            ComicBar(i, comicLabel(it.key, it.label), it.hint, it.badge, primary = i == 0) { it.action() }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(10.dp))
        Panel("STASH") { LibraryFooter(model, sources = false) }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ComicBar(index: Int, label: String, hint: String, badge: String?, primary: Boolean, onClick: () -> Unit) {
    val motion = !LocalLook.current.reduceMotion
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val slide = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { delay(70L * index + 80L); slide.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) }
    val push by animateDpAsState(if (pressed) 4.dp else 0.dp, tween(60))
    val tilt = if (index % 2 == 0) -1.2f else 1.2f
    val fill = if (primary) C.hot else C.panel
    val ink = if (primary) onColor(C.hot) else C.amber
    val spin = rememberInfiniteTransition()
    val badgeTurn by spin.animateFloat(-8f, 8f, infiniteRepeatable(tween(1400), RepeatMode.Reverse))
    Box(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationX = (1f - slide.value) * -size.width; rotationZ = tilt + if (pressed) -tilt else 0f }
            .clickable(src, null) { haptic(true); onClick() },
    ) {
        Box(Modifier.matchParentSize().offset(4.dp, 4.dp).box(Th.style, Color.Transparent, if (primary) C.white else C.hot))
        Row(
            Modifier.fillMaxWidth().offset(push, push).box(Th.style, C.line, fill, 2.dp).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Display((index + 1).toString().padStart(2, '0'), 28.sp, if (primary) ink else C.hot)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                TText(label, size = 18.sp, color = ink, weight = FontWeight.Bold, spacing = 1.sp, glow = 0f)
                TText(hint, size = 11.sp, color = if (primary) ink.copy(alpha = 0.85f) else C.dim, maxLines = 1)
            }
        }
        if (badge != null) {
            Starburst(Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-12).dp).size(58.dp, 40.dp).graphicsLayer { rotationZ = badgeTurn },
                fill = C.white, stroke = C.bg, spikes = 12) {
                TText(if (badge == "NEW") "NEW!" else badge, size = 11.sp, color = onColor(C.white), weight = FontWeight.Bold, glow = 0f)
            }
        }
    }
}

// ======================================================================= play

@Composable
fun ComicPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Tape("PANEL ${st.round + 1}/${st.total}", tilt = -3f, fill = C.hot)
                Spacer(Modifier.weight(1f))
                Starburst(Modifier.size(84.dp, 50.dp), fill = C.white, stroke = C.bg) {
                    RollingNumber(st.myTotal, size = 15.sp, color = onColor(C.white))
                }
                Spacer(Modifier.width(8.dp))
                TText(if (st.canPause && st.phase == Phase.TYPING && !st.paused) "‖" else "✕",
                    Modifier.clickable(remember { MutableInteractionSource() }, null) {
                        if (st.canPause && st.phase == Phase.TYPING && !st.paused) session.pause() else { session.pause(); model.confirmQuit = true }
                    }.padding(10.dp), size = 20.sp, weight = FontWeight.Bold)
            }
            if (session.isMulti) {
                TText("VS ${st.oppName?.uppercase() ?: "?"} · ${st.oppTotal}" + if (st.oppLocked && st.phase != Phase.REVEAL) " · LOCKED IN" else "",
                    Modifier.padding(horizontal = 18.dp), size = 12.sp, color = C.dim, weight = FontWeight.Bold)
            }
            if (st.seconds > 0) StripeTimer(left / (st.seconds * 1000f), left < 5000)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
                if (st.phase == Phase.WAITING) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); TText("  ${st.message ?: "HOLD ON…"}", size = 13.sp, color = C.dim) }
                } else if (st.prompt.isNotEmpty()) {
                    key(st.round) {
                        val drop = remember { Animatable(0f) }
                        LaunchedEffect(Unit) { drop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 320f)) }
                        Column(Modifier.fillMaxWidth().graphicsLayer {
                            rotationZ = -1.5f + (1f - drop.value) * 8f; scaleX = 0.8f + 0.2f * drop.value; scaleY = 0.8f + 0.2f * drop.value
                            alpha = drop.value.coerceIn(0f, 1f)
                        }.box(Th.style, C.line, C.panel, 2.dp, glow = true).padding(horizontal = 24.dp, vertical = 18.dp)) {
                            TText("#${st.topic.uppercase()} · ${group(st.listSize)} ANSWERS", size = 10.sp, color = C.hot, weight = FontWeight.Bold, spacing = 1.5.sp)
                            Spacer(Modifier.height(8.dp))
                            TText(st.prompt.uppercase(), size = 21.sp, weight = FontWeight.Bold, color = C.amber)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                when (st.phase) {
                    Phase.TYPING -> if (!st.paused) {
                        AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a), size = 19.sp)
                        Spacer(Modifier.height(10.dp))
                        SuggestionChip(st, a)
                        Spacer(Modifier.height(8.dp))
                        AttemptChips(st)
                        Spacer(Modifier.height(14.dp))
                        TButton("GO!", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true, textSize = 20.sp)
                        Spacer(Modifier.height(10.dp))
                        Tape("RARE = BIG POINTS · MISSES ARE FREE", tilt = 1f)
                    }
                    Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Spinner(C.hot); TText("  LOCKED IN. WAITING ON THE OTHER GUY…", size = 13.sp, color = C.dim)
                    }
                    Phase.REVEAL -> st.reveal?.let { v ->
                        ComicVerdict(v)
                        RevealExtras(v, session.isMulti)
                    }
                    Phase.ERROR -> Panel("BUSTED", color = C.blood, titleColor = C.blood) { TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood) }
                    else -> Unit
                }
            }
            PlayActions(model, session, st, Modifier.padding(16.dp))
        }
        if (st.phase == Phase.TYPING) Nope(st.missTick)
        st.reveal?.let { if (st.phase == Phase.REVEAL) SlashWipe(it.index) }
    }
}

/** Diagonal-striped countdown bar. */
@Composable
private fun StripeTimer(f: Float, danger: Boolean) {
    val c = if (danger) C.blood else C.hot
    val stripe = C.bg.copy(alpha = 0.25f)
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(16.dp).box(Th.style, C.line, C.ghost, 2.dp)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().box(Th.style, Color.Transparent, c).clipToBounds().drawBehind {
            val step = 10.dp.toPx()
            var x = -size.height
            while (x < size.width) {
                val p = Path().apply {
                    moveTo(x, size.height); lineTo(x + size.height, 0f); lineTo(x + size.height + step / 2, 0f); lineTo(x + step / 2, size.height); close()
                }
                drawPath(p, stripe)
                x += step
            }
        })
    }
}

/** "NOPE!" starburst that pops near the top on each wrong answer. */
@Composable
private fun BoxScope.Nope(tick: Int) {
    if (tick == 0 || LocalLook.current.reduceMotion) return
    val p = remember(tick) { Animatable(0f) }
    val fade = remember(tick) { Animatable(1f) }
    LaunchedEffect(tick) { p.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f)) }
    LaunchedEffect(tick) { delay(650); fade.animateTo(0f, tween(250)) }
    if (fade.value <= 0f) return
    Starburst(Modifier.align(Alignment.Center).size(170.dp, 110.dp).graphicsLayer {
        scaleX = p.value; scaleY = p.value; rotationZ = -12f; alpha = fade.value
    }, fill = C.blood, stroke = C.line) {
        Display("NOPE!", 30.sp, onColor(C.blood))
    }
}

/** A hot diagonal band sweeping across the screen once per verdict. */
@Composable
private fun SlashWipe(round: Int) {
    if (LocalLook.current.reduceMotion) return
    val p = remember(round) { Animatable(0f) }
    LaunchedEffect(round) { p.animateTo(1f, tween(460, easing = FastOutSlowInEasing)) }
    if (p.value >= 1f) return
    val c = C.hot
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val band = w * 0.45f
        val x = -band - h * 0.4f + (w + band + h * 0.8f) * p.value
        val path = Path().apply {
            moveTo(x, h); lineTo(x + h * 0.4f, 0f); lineTo(x + h * 0.4f + band, 0f); lineTo(x + band, h); close()
        }
        drawPath(path, c.copy(alpha = 0.85f * (1f - p.value * 0.6f)))
    }
}

@Composable
private fun ComicVerdict(v: RoundView) {
    val me = v.me
    val pop = remember(v.index) { Animatable(0f) }
    LaunchedEffect(v.index) { delay(200); pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 380f)) }
    val good = me.tier.isValid
    val burst = when { !good -> C.blood; me.tier.heat >= 5 -> C.white; else -> C.hot }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Starburst(Modifier.size(260.dp, 120.dp).graphicsLayer {
            scaleX = pop.value; scaleY = pop.value; rotationZ = -6f - (1f - pop.value) * 20f
        }, fill = burst, stroke = C.line, spikes = 16) {
            Display(tierLine(me.tier), if (me.tier.heat >= 5) 30.sp else 26.sp, onColor(burst), align = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().graphicsLayer { rotationZ = 1.2f }.box(Th.style, C.line, C.panel, 2.dp).padding(horizontal = 24.dp, vertical = 14.dp)) {
            TText(if (me.tier == Tier.SEGFAULT) "YOU SAID: ${me.input.uppercase()}" else "YOUR ANSWER", size = 10.sp, color = C.dim, weight = FontWeight.Bold, spacing = 1.sp)
            Display(headline(me).uppercase(), 26.sp, tierColor(me.tier))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierBadge(me.tier, 12.sp)
                Spacer(Modifier.weight(1f))
                Display(signed(me.points), 26.sp, C.hot)
            }
        }
    }
}

// ======================================================================= summary

@Composable
fun ComicSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    val slam = remember { Animatable(0f) }
    LaunchedEffect(Unit) { slam.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }

    Frame(model, "THE DAMAGE", onBack = { model.home() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.graphicsLayer { rotationZ = -4f; scaleX = 0.5f + 0.5f * slam.value; scaleY = 0.5f + 0.5f * slam.value; alpha = slam.value.coerceIn(0f, 1f) },
                verticalAlignment = Alignment.Bottom) {
                Display(group(t.my), 72.sp, C.hot)
                Spacer(Modifier.width(8.dp))
                Tape("PTS", tilt = -6f)
            }
            Spacer(Modifier.height(10.dp))
            app.coredump.ui.CustomIcon(app.coredump.ui.customKey(t.rank), 48.dp)
            RansomText(t.rank.title, 26.sp, seed = t.rank.ordinal + 5)
            Spacer(Modifier.height(10.dp))
            Tape(t.rank.line.uppercase(), tilt = 1.5f)
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                Spacer(Modifier.height(14.dp))
                val o = st.oppTotal
                Display(when { t.my > o -> "YOU WIN! ${t.my}–$o"; t.my < o -> "${opp.uppercase()} WINS ${o}–${t.my}"; else -> "DRAW! ${t.my}–$o" },
                    24.sp, if (t.my >= o) C.hot else C.blood)
            }
            Spacer(Modifier.height(18.dp))
            Panel("TIERS") {
                for (tier in Tier.entries.reversed()) {
                    val n = st.rounds.count { it.me.tier == tier }
                    if (n == 0) continue
                    key(tier) {
                        val grow = remember { Animatable(0f) }
                        LaunchedEffect(Unit) { delay(300L + 90L * tier.ordinal); grow.animateTo(n / t.count.coerceAtLeast(1).toFloat(), spring(dampingRatio = 0.6f, stiffness = 200f)) }
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            TText(tier.label, Modifier.width(130.dp), size = 10.sp, color = tierColor(tier), weight = FontWeight.Bold)
                            Box(Modifier.weight(1f).height(18.dp)) {
                                Box(Modifier.fillMaxWidth(grow.value.coerceIn(0.04f, 1f)).fillMaxHeight().box(Th.style, Color.Transparent, tierColor(tier)))
                            }
                            TText("  $n", size = 12.sp, weight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            for (r in st.rounds) ComicRoundPanel(model, r)
            Spacer(Modifier.height(8.dp))
            TLink("SHARE THE DAMAGE", { shareText(shareCard(st, t.rank)) }, color = C.hot)
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("AGAIN!", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(12.dp))
            }
            TButton("HOME", { model.home() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ComicRoundPanel(model: AppModel, r: RoundView) {
    var open by remember { mutableStateOf(false) }
    val tilt = if (r.index % 2 == 0) -0.8f else 0.8f
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).graphicsLayer { rotationZ = tilt }
            .box(Th.style, if (open) C.hot else C.line, C.panel, 2.dp)
            .clickable(remember { MutableInteractionSource() }, null) { open = !open }
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Display("${r.index + 1}", 22.sp, C.hot)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                TText(r.prompt.uppercase(), size = 10.sp, color = C.dim, maxLines = 2, weight = FontWeight.Bold)
                TText(answerText(r.me.tier, r.me.canonical, r.me.input).uppercase(), size = 15.sp, color = tierColor(r.me.tier), weight = FontWeight.Bold, maxLines = 1)
            }
            Display(signed(r.me.points), 20.sp, if (r.me.points < 0) C.blood else C.amber)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
