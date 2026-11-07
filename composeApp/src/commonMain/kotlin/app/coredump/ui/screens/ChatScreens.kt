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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.core.Spam
import app.coredump.core.Tier
import app.coredump.data.History
import app.coredump.game.AttemptStatus
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RankBadge
import app.coredump.ui.RollingNumber
import app.coredump.ui.Style
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.TierBadge
import app.coredump.ui.box
import app.coredump.ui.group
import app.coredump.ui.onColor
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// CHAT: a messenger. The menu is a chat list, a run is a conversation with a quiz bot: it "types", sends the prompt,
// your answers go out as bubbles, misses get a short reply, autocorrect asks with quick-reply buttons, and the tier
// lands as a reaction on your message. The results are a recap thread.

private val AVATARS = listOf(0xFF5B8DEF, 0xFFEF6B5B, 0xFF4CC38A, 0xFFF2A93B, 0xFFA66CF0, 0xFF3CB8C9, 0xFFE0609E, 0xFF8A94A6).map { Color(it) }

private fun chatTitle(it: Item): String = when (it.key) {
    "daily" -> dailyLabel(it.label, "Daily drop")
    "solo" -> "Solo practice"
    "duel" -> "1v1 with a friend"
    "stats" -> "Your stats"
    "browse" -> "All lists"
    "theme" -> "Theme"
    "settings" -> "Settings"
    "manual" -> "How it works"
    else -> it.label
}

private fun chatInitial(key: String): String = when (key) {
    "daily" -> "D"; "solo" -> "S"; "duel" -> "1v1"; "stats" -> "%"; "browse" -> "≡"; "theme" -> "Aa"; "settings" -> "⋯"; "manual" -> "?"; else -> "•"
}

@Composable
private fun Avatar(text: String, color: Color, size: Dp = 48.dp) {
    Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        TText(text, size = (size.value * 0.34f).sp, color = onColor(color), weight = FontWeight.Bold, maxLines = 1)
    }
}

// ======================================================================= menu

@Composable
fun ChatMenu(model: AppModel) {
    val items = menuItems(model)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TText("Chats", size = 32.sp, weight = FontWeight.Bold)
                TText("Core Dump · name the answer nobody else will", size = 12.sp, color = C.dim, maxLines = 1)
            }
            Avatar(model.settings.handle.take(1).uppercase().ifEmpty { "?" }, C.hot, 40.dp)
        }
        TText(if (History.games == 0) "No runs yet" else "Best run ${History.best} pts · ${History.games} runs",
            Modifier.padding(horizontal = 18.dp), size = 11.sp, color = C.dim)
        Box(Modifier.padding(horizontal = 18.dp)) { CrashBanner(model) }
        Spacer(Modifier.height(10.dp))
        items.forEachIndexed { i, it -> ChatRow(i, it) }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.padding(horizontal = 16.dp)) { Panel("Library") { LibraryFooter(model, sources = false) } }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ChatRow(index: Int, item: Item) {
    val motion = !LocalLook.current.reduceMotion
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val enter = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { delay(45L * index + 60L); enter.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    val unread = item.badge == "NEW"
    Column(
        Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value; translationX = (1f - enter.value) * 30.dp.toPx() }
            .background(if (pressed) C.ghost else Color.Transparent).clickable(src, null) { haptic(false); item.action() },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(chatInitial(item.key), AVATARS[index % AVATARS.size])
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TText(chatTitle(item), Modifier.weight(1f), size = 16.sp, weight = if (unread) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                    if (index == 0) TText("today", size = 11.sp, color = if (unread) C.hot else C.dim)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TText(item.hint, Modifier.weight(1f), size = 13.sp, color = if (unread) C.amber else C.dim, maxLines = 1)
                    val badge = item.badge
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).background(C.hot, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center) {
                            TText(if (unread) "1" else badge, size = 11.sp, color = onColor(C.hot), weight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(start = 80.dp).fillMaxWidth().height(1.dp).background(C.line.copy(alpha = 0.6f)))
    }
}

// ======================================================================= bubbles

/** One message bubble. me = right side in the accent colour; the bot's are grey on the left. Pops in once. */
@Composable
private fun Bubble(text: String, me: Boolean, small: Boolean = false, strike: Boolean = false, error: Boolean = false,
                   extra: (@Composable () -> Unit)? = null) {
    val motion = !LocalLook.current.reduceMotion
    val pop = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f)) }
    val fill = when { error -> C.bloodDim; me -> C.hot; else -> C.panel }
    val ink = when { error -> C.blood; me -> onColor(C.hot); else -> C.amber }
    val shape = if (me) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 290.dp).graphicsLayer {
                val s = 0.85f + 0.15f * pop.value
                scaleX = s; scaleY = s; alpha = pop.value.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(if (me) 1f else 0f, 1f)
            }.background(fill, shape).padding(horizontal = 14.dp, vertical = if (small) 7.dp else 10.dp),
        ) {
            TText(text, size = if (small) 13.sp else 16.sp, color = if (strike) ink.copy(alpha = 0.75f) else ink,
                decoration = if (strike) TextDecoration.LineThrough else null)
            if (extra != null) extra()
        }
    }
}

/** The bot is typing: three dots that bounce in turn. */
@Composable
private fun BotTyping(label: String? = null) {
    val t = rememberInfiniteTransition()
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
    val dot = C.dim
    val motion = !LocalLook.current.reduceMotion
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.background(C.panel, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Canvas(Modifier.size(34.dp, 10.dp)) {
                for (i in 0 until 3) {
                    val ph = (((if (motion) p else 0f) - i * 0.16f) % 1f + 1f) % 1f
                    val q = (ph * 2f).coerceAtMost(1f)
                    val lift = sin(q * PI.toFloat())
                    drawCircle(dot.copy(alpha = 0.4f + 0.6f * lift), 3.dp.toPx(),
                        Offset(5.dp.toPx() + i * 12.dp.toPx(), size.height / 2f + 1.dp.toPx() - lift * 2.dp.toPx()))
                }
            }
        }
        if (label != null) TText("  $label", size = 11.sp, color = C.dim)
    }
}

/** Small centred date-style chip. */
@Composable
private fun DayChip(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        TText(text, Modifier.background(C.ghost, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            size = 11.sp, color = C.dim, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Quick-reply buttons under a bot question (right-aligned, like suggested replies). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickReplies(options: List<Pair<String, () -> Unit>>) {
    val haptic = rememberHaptic()
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, act) in options) {
            TText(label, Modifier.box(Style.CHAT, C.hot, Color.Transparent, 1.5.dp, pill = true)
                .clickable(remember { MutableInteractionSource() }, null) { haptic(true); act() }
                .padding(horizontal = 16.dp, vertical = 9.dp), size = 14.sp, color = C.hot, weight = FontWeight.SemiBold)
        }
    }
}

// ======================================================================= play

@Composable
fun ChatPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val now by rememberNow(st.startedAt, isRunning(st))
    val left = timeLeft(st, now)
    val scroll = rememberScrollState()
    // keep the newest message in view
    LaunchedEffect(st.round, st.attempts.size, st.phase, st.confirm, st.reveal) { delay(150); scroll.animateScrollTo(scroll.maxValue) }
    LaunchedEffect(st.round) { delay(900); scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize()) {
        ChatTopBar(model, session, st)
        if (st.seconds > 0) TimeStrip(left, st.seconds)
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (st.phase == Phase.WAITING) {
                BotTyping(st.message)
            } else if (st.prompt.isNotEmpty()) {
                key(st.round) {
                    DayChip("Question ${st.round + 1} of ${st.total} · ${st.topic}")
                    PromptBubble(st)
                }
                for ((i, at) in st.attempts.withIndex()) {
                    key(st.round, i) {
                        Bubble(at.text, me = true, strike = at.status == AttemptStatus.REJECTED)
                        when (at.status) {
                            AttemptStatus.VERIFYING -> BotTyping("checking…")
                            AttemptStatus.REJECTED -> Bubble(if (at.spam) "That looks like key-mashing · −${Spam.PENALTY} pts" else "Not on the list", me = false, small = true)
                        }
                    }
                }
                val c = st.confirm
                if (c != null) {
                    key(c) {
                        Bubble("Did you mean “${c.answer}”?", me = false)
                        QuickReplies(listOf("Yes, submit" to { a.onConfirm(true) }, "No, search mine" to { a.onConfirm(false) }) +
                            c.others.take(3).map { s -> "$s?" to { a.send(s) } })
                    }
                } else if (st.phase == Phase.TYPING) {
                    suggestionsOf(st).take(3).takeIf { it.isNotEmpty() }?.let { l -> QuickReplies(l.map { s -> "$s?" to { a.send(s) } }) }
                }
                when (st.phase) {
                    Phase.LOCKED -> { Bubble("✓ Locked in", me = true); BotTyping("waiting for ${st.oppName ?: "your friend"}") }
                    Phase.REVEAL -> st.reveal?.let { v ->
                        ChatVerdict(v)
                        RevealExtras(v, session.isMulti)
                    }
                    Phase.ERROR -> Bubble(st.message ?: "Something went wrong.", me = false, error = true)
                    else -> Unit
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (st.phase == Phase.TYPING && !st.paused) Composer(session, st, a)
        else PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

@Composable
private fun ChatTopBar(model: AppModel, session: MatchSession, st: MatchState) {
    val typing = st.phase == Phase.WAITING || st.verifying
    val haptic = rememberHaptic()
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TText("‹", Modifier.clickable(remember { MutableInteractionSource() }, null) { haptic(false); session.pause(); model.confirmQuit = true }
                .padding(horizontal = 10.dp, vertical = 2.dp), size = 30.sp, color = C.hot, weight = FontWeight.Bold)
            Avatar("Q", C.hot, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                TText(if (session.isMulti) "Quiz Bot · vs ${st.oppName ?: "?"}" else "Quiz Bot", size = 15.sp, weight = FontWeight.Bold, maxLines = 1)
                TText(when {
                    typing -> "typing…"
                    session.isMulti && st.oppLocked && st.phase != Phase.REVEAL -> "${st.oppName ?: "friend"} locked in"
                    st.daily != null -> "daily drop #${st.daily}"
                    else -> "online"
                }, size = 11.sp, color = if (typing) C.hot else C.dim, maxLines = 1)
            }
            Box(Modifier.background(C.panel, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                RollingNumber(st.myTotal, size = 13.sp, color = C.amber, suffix = " pts")
            }
            if (canPauseNow(st)) {
                TText("‖", Modifier.clickable(remember { MutableInteractionSource() }, null) { haptic(false); session.pause() }.padding(10.dp),
                    size = 16.sp, weight = FontWeight.Bold)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    }
}

/** Thin strip under the top bar showing the time left (red in the last 5 s). */
@Composable
private fun TimeStrip(leftMs: Long, seconds: Int) {
    val f = (leftMs / (seconds * 1000f)).coerceIn(0f, 1f)
    val c = if (leftMs in 1..5000) C.blood else C.hot
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(3.dp)) { drawRect(c, size = Size(size.width * f, size.height)) }
        TText("${(leftMs + 999) / 1000}s ", size = 10.sp, color = c)
    }
}

/** The bot types for a moment, then sends the prompt. */
@Composable
private fun PromptBubble(st: MatchState) {
    val motion = !LocalLook.current.reduceMotion
    var shown by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { delay(700); shown = true }
    if (!shown) BotTyping()
    else Bubble(st.prompt, me = false) {
        Spacer(Modifier.height(4.dp))
        TText("${group(st.listSize)} answers on the list", size = 11.sp, color = C.dim)
    }
}

/** Message box at the bottom: skip, the answer field, a round send button. */
@Composable
private fun Composer(session: MatchSession, st: MatchState, a: AnswerState) {
    val haptic = rememberHaptic()
    Column(Modifier.fillMaxWidth().background(C.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TText("Skip", Modifier.clickable(remember { MutableInteractionSource() }, null) { haptic(false); session.skip() }
                .padding(horizontal = 8.dp, vertical = 10.dp), size = 14.sp, color = C.hot, weight = FontWeight.SemiBold)
            AnswerField(a, st, Modifier.weight(1f).shakeX(a), size = 16.sp)
            Spacer(Modifier.width(8.dp))
            SendButton(a.text.isNotBlank()) { a.send() }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val k by animateFloatAsState(if (enabled) 1f else 0f, tween(160))
    val fill = lerp(C.line, C.hot, k)
    val ink = onColor(C.hot)
    Box(
        Modifier.size(42.dp).graphicsLayer { val s = 0.88f + 0.12f * k; scaleX = s; scaleY = s }.background(fill, CircleShape)
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled) { haptic(true); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val sw = 2.2.dp.toPx()
            val cx = size.width / 2f
            drawLine(ink, Offset(cx, size.height), Offset(cx, 0f), sw, StrokeCap.Round)
            drawLine(ink, Offset(cx, 0f), Offset(cx - size.width * 0.38f, size.height * 0.4f), sw, StrokeCap.Round)
            drawLine(ink, Offset(cx, 0f), Offset(cx + size.width * 0.38f, size.height * 0.4f), sw, StrokeCap.Round)
        }
    }
}

/** Your final message with the tier as a reaction on it, then the bot's reply. */
@Composable
private fun ChatVerdict(v: RoundView) {
    val me = v.me
    val motion = !LocalLook.current.reduceMotion
    when {
        me.tier.isValid || me.tier == Tier.SEGFAULT -> Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalAlignment = Alignment.End) {
            Bubble(me.input.ifBlank { headline(me) }, me = true)
            Box(Modifier.offset(x = (-10).dp, y = (-8).dp)) { TierBadge(me.tier, 11.sp) }
        }
        me.tier == Tier.SKIPPED -> Bubble("Skip", me = true)
        else -> Unit
    }
    var reply by remember(v.index) { mutableStateOf(!motion) }
    LaunchedEffect(v.index) { delay(650); reply = true }
    if (!reply) {
        BotTyping()
    } else {
        Bubble(tierLine(me.tier), me = false) {
            if (me.tier.isValid) {
                Spacer(Modifier.height(4.dp))
                val alias = me.canonical?.takeIf { !it.equals(me.input, ignoreCase = true) }
                TText((if (alias != null) "$alias · " else "") + "${signed(me.points)} pts", size = 13.sp, color = tierColor(me.tier), weight = FontWeight.Bold)
            } else if (me.points < 0) {
                TText("${signed(me.points)} pts", size = 13.sp, color = C.blood, weight = FontWeight.Bold)
            }
        }
    }
}

// ======================================================================= summary

@Composable
fun ChatSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    Frame(model, "Recap", onBack = { model.home() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(6.dp))
            DayChip(when { st.daily != null -> "Daily drop #${st.daily}"; session.isMulti -> "1v1 with ${st.oppName ?: "a friend"}"; else -> "Solo practice" })
            Bubble("Done! Here's how you did 👇", me = false)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.widthIn(max = 300.dp).background(C.panel, RoundedCornerShape(22.dp, 22.dp, 22.dp, 4.dp)).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingNumber(t.my, size = 46.sp, color = C.hot, durationMs = 1800)
                    TText("points · avg ${oneDecimal(t.avg)}", size = 12.sp, color = C.dim)
                    Spacer(Modifier.height(12.dp))
                    RankBadge(t.rank, size = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    TText(t.rank.line, size = 12.sp, color = C.dim, align = TextAlign.Center)
                }
            }
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                val o = st.oppTotal
                Bubble(when { t.my > o -> "You beat $opp ${t.my}–$o 🎉"; t.my < o -> "$opp won $o–${t.my}. Rematch?"; else -> "A draw with $opp, ${t.my}–$o 🤝" }, me = false)
            }
            DayChip("Your answers · tap one for rarer picks")
            for (r in st.rounds) ChatRecapRow(model, r)
            QuickReplies(listOf("Share result" to { shareText(shareCard(st, t.rank)) }))
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("Play again", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(12.dp))
            }
            TButton("Back to chats", { model.home() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChatRecapRow(model: AppModel, r: RoundView) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }) {
        Bubble(r.prompt, me = false, small = true)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Bubble(answerText(r.me.tier, r.me.canonical, r.me.input), me = true)
            Row(Modifier.offset(x = (-10).dp, y = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
                TierBadge(r.me.tier, 10.sp, animate = false)
                Spacer(Modifier.width(6.dp))
                TText(signed(r.me.points), size = 12.sp, color = C.dim, weight = FontWeight.Bold)
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
