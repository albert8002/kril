package app.coredump.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.core.hexAddr
import app.coredump.core.Spam
import app.coredump.game.Attempt
import app.coredump.game.AttemptStatus
import app.coredump.game.Confirm
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.game.duelVerdict
import app.coredump.platform.Platform
import app.coredump.ui.C
import app.coredump.ui.Celebrate
import app.coredump.ui.DecodeText
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RewardBurst
import app.coredump.ui.RollingNumber
import app.coredump.ui.Spinner
import app.coredump.ui.Style
import app.coredump.ui.Sfx
import app.coredump.ui.Cue
import app.coredump.ui.LocalFx
import app.coredump.ui.ThemeTouches
import app.coredump.ui.reportField
import androidx.compose.runtime.DisposableEffect
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.TermInput
import app.coredump.ui.Th
import app.coredump.ui.TierBadge
import app.coredump.ui.Typewriter
import app.coredump.ui.box
import app.coredump.ui.group
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

// ======================================================================= shared play kit

/** Text being typed for the current prompt + focus + shake on a miss. One per round. */
@Stable
class AnswerState {
    var text by mutableStateOf("")
    val focus = FocusRequester()
    val shake = Animatable(0f)
    var onSubmit: (String) -> Unit = {}
    /** Answer to an autocorrect question: true = take the suggestion, false = search what was typed. */
    var onConfirm: (Boolean) -> Unit = {}
    /** Sound + theme touch when something is sent. */
    var onSent: () -> Unit = {}

    fun send(v: String = text) {
        val t = v.trim()
        if (t.isEmpty()) return
        onSent()
        onSubmit(t)
        text = ""
    }
}

@Composable
fun rememberAnswer(st: MatchState, onSubmit: (String) -> Unit, onConfirm: (Boolean) -> Unit = {}): AnswerState {
    val a = remember(st.round) { AnswerState() }
    val fx = LocalFx.current
    a.onSubmit = onSubmit
    a.onConfirm = onConfirm
    a.onSent = { fx.sent(); Sfx.play(Cue.SUBMIT) }
    val motion = !LocalLook.current.reduceMotion
    LaunchedEffect(st.round, st.phase) { if (st.phase == Phase.TYPING) { delay(250); runCatching { a.focus.requestFocus() } } }
    LaunchedEffect(st.missTick) {
        if (st.missTick == 0 || !motion) return@LaunchedEffect
        a.shake.snapTo(1f)
        a.shake.animateTo(0f, tween(420))
    }
    return a
}

/** Horizontal shake for the input after a wrong answer. */
fun Modifier.shakeX(a: AnswerState): Modifier = graphicsLayer { translationX = sin(a.shake.value * 30f) * 14f * a.shake.value }

/** The latest "did you mean" choices, without answers already tried. */
fun suggestionsOf(st: MatchState): List<String> =
    st.attempts.lastOrNull { it.suggestions.isNotEmpty() }?.suggestions.orEmpty()
        .filter { s -> st.attempts.none { it.text.equals(s, ignoreCase = true) } }

/** The best "did you mean" choice, unless already tried. */
fun suggestionOf(st: MatchState): String? = suggestionsOf(st).firstOrNull()

/** Epoch ms, ticking every frame while [running]. */
@Composable
fun rememberNow(startedAt: Long, running: Boolean): State<Long> {
    val now = remember { mutableLongStateOf(Platform.epochMillis()) }
    LaunchedEffect(startedAt, running) {
        now.longValue = Platform.epochMillis()
        while (running) { withFrameMillis { }; now.longValue = Platform.epochMillis() }
    }
    return now
}

/** Milliseconds left in the round (0 when there is no limit). */
fun timeLeft(st: MatchState, now: Long): Long =
    if (st.seconds <= 0) 0L else (st.seconds * 1000L - (now - st.startedAt)).coerceIn(0L, st.seconds * 1000L)

fun isRunning(st: MatchState) = (st.phase == Phase.TYPING || st.phase == Phase.LOCKED) && !st.paused

/** Themed answer field bound to [a]. */
@Composable
fun AnswerField(a: AnswerState, st: MatchState, modifier: Modifier = Modifier, size: TextUnit = 18.sp, prompt: String = "$") {
    val fx = LocalFx.current
    // the field is gone (reveal etc.): no more touches around it
    DisposableEffect(Unit) { onDispose { fx.field = androidx.compose.ui.geometry.Rect.Zero } }
    TermInput(
        value = a.text,
        onValueChange = {
            if (it.length <= 60) {
                if (it.length > a.text.length) { fx.key(); Sfx.play(Cue.KEY) }
                a.text = it
            }
        },
        modifier = modifier.reportField(fx),
        prompt = prompt,
        placeholder = if (st.attempts.isEmpty()) Th.say("type an answer…", "Type an answer") else Th.say("try again…", "Try again"),
        onSubmit = { a.send() },
        size = size,
        fieldModifier = Modifier.focusRequester(a.focus),
    )
}

/**
 * Autocorrect question ("Submit X?" with yes / "no, search what I typed") when the last answer only matched with
 * typos; otherwise a "Did you mean X?" chip after a miss.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionChip(st: MatchState, a: AnswerState) {
    val c = st.confirm
    if (c != null) { ConfirmCard(c, a); return }
    val list = suggestionsOf(st)
    if (list.isEmpty()) return
    val pill = Th.style == Style.CLEAN || Th.style == Style.NEON || Th.style == Style.ABYSS || Th.style == Style.CHAT
    if (list.size == 1) {
        val s = list[0]
        TText(
            Th.say("did you mean  $s ?  ↵", "Did you mean $s?"),
            Modifier.box(Th.style, C.hot, C.panel, pill = pill)
                .clickable(remember { MutableInteractionSource() }, null) { a.send(s) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            size = 13.sp, color = C.hot, weight = FontWeight.Bold,
        )
        return
    }
    // several answers contain what was typed: one chip each
    Column {
        TText(Th.say("did you mean:", "Did you mean"), size = 11.sp, color = C.dim)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, s) in list.withIndex()) {
                TText(
                    s,
                    Modifier.box(Th.style, if (i == 0) C.hot else C.line, C.panel, pill = pill)
                        .clickable(remember { MutableInteractionSource() }, null) { a.send(s) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    size = 12.sp, color = if (i == 0) C.hot else C.amber, weight = FontWeight.Bold, maxLines = 2,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ConfirmCard(c: Confirm, a: AnswerState) {
    val pop = remember(c) { Animatable(0.85f) }
    val view = remember { BringIntoViewRequester() }
    LaunchedEffect(c) { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f)) }
    // the keyboard is closed when the question appears; then make sure both buttons are on screen
    LaunchedEffect(c) { delay(350); runCatching { view.bringIntoView() } }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(view).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .box(Th.style, C.hot, C.panel, 1.5.dp, glow = Th.style == Style.NEON || Th.style == Style.COMIC)
            .padding(horizontal = if (Th.style == Style.COMIC) 22.dp else 14.dp, vertical = 12.dp),
    ) {
        TText(Th.say("autocorrect: you typed \"${c.typed}\"", "You typed \u201c${c.typed}\u201d"), size = 11.sp, color = C.dim, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        TText(Th.say("submit \"${c.answer}\"?", "Submit \u201c${c.answer}\u201d?"), size = 16.sp, color = C.hot, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            TButton(Th.say("YES, SUBMIT", "Yes, submit"), { a.onConfirm(true) }, Modifier.weight(1f), primary = true, textSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            TButton(Th.say("NO, SEARCH", "No, search mine"), { a.onConfirm(false) }, Modifier.weight(1f), textSize = 13.sp)
        }
        if (c.others.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            TText(Th.say("or one of:", "Or one of these"), size = 11.sp, color = C.dim)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in c.others) {
                    TText(s, Modifier.box(Th.style, C.line, C.bg, pill = Th.style == Style.CLEAN || Th.style == Style.NEON || Th.style == Style.ABYSS || Th.style == Style.CHAT)
                        .clickable(remember { MutableInteractionSource() }, null) { a.send(s) }
                        .padding(horizontal = 10.dp, vertical = 7.dp), size = 12.sp, color = C.amber, weight = FontWeight.Bold, maxLines = 2)
                }
            }
        }
    }
}

/** Give up on this prompt (worded per archetype). */
@Composable
fun SkipLink(session: MatchSession, st: MatchState, modifier: Modifier = Modifier) {
    if (st.phase != Phase.TYPING || st.paused) return
    val label = when (Th.style) {
        Style.TERMINAL -> "skip (kill -SIGSKIP)"
        Style.NEON -> "SKIP STAGE"
        Style.PAPER -> "Pass on this clue"
        Style.CLEAN -> "Skip question"
        Style.PIXEL -> "RUN AWAY"
        Style.COMIC -> "PASS!"
        Style.ABYSS -> "Let this one drift"
        Style.METRO -> "Skip this stop"
        Style.JACKPOT -> "FOLD"
        Style.CHAT -> "Skip"
        Style.DESKTOP -> "Skip"
        Style.ORACLE -> "Leave it to fate"
    }
    TLink(label, { session.skip() }, modifier, color = C.dim, size = 12.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttemptChips(st: MatchState) {
    if (st.attempts.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in st.attempts) AttemptChip(a)
    }
}

@Composable
fun AttemptChip(a: Attempt) {
    Row(
        Modifier.box(Th.style, if (a.status == AttemptStatus.VERIFYING) C.dim else C.blood.copy(alpha = 0.5f), Color.Transparent,
            pill = Th.style == Style.CLEAN || Th.style == Style.NEON || Th.style == Style.ABYSS || Th.style == Style.CHAT)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (a.status == AttemptStatus.VERIFYING) {
            Spinner(C.dim, 11.sp)
            TText(" checking ${a.text}…", size = 11.sp, color = C.dim, maxLines = 1)
        } else {
            TText("✗ ", size = 11.sp, color = C.blood)
            TText(a.text, size = 11.sp, color = C.blood.copy(alpha = 0.8f), decoration = TextDecoration.LineThrough, maxLines = 1)
            if (a.spam) TText("  −${Spam.PENALTY} mashing", size = 10.sp, color = C.blood, weight = FontWeight.Bold)
        }
    }
}

/** "Never ask this again": discards the current prompt (hidden forever, collected lists deleted). */
@Composable
fun DiscardAction(model: AppModel, session: MatchSession, round: Int, modifier: Modifier = Modifier) =
    DiscardLink(model, session.promptAt(round)?.id, modifier)

/** Short line for a tier, phrased per archetype. */
fun tierLine(t: Tier): String = when (Th.style) {
    Style.PIXEL -> when (t) {
        Tier.SKIPPED -> "You fled the battle!"
        Tier.TIMEOUT -> "You ran out of time!"
        Tier.SEGFAULT -> "The attack missed!"
        Tier.HELLO_WORLD -> "A weak hit..."
        Tier.COPYPASTA -> "A clever hit."
        Tier.LEGACY -> "A solid hit."
        Tier.MEMORY_LEAK -> "A strong hit!"
        Tier.HEISENBUG -> "CRITICAL HIT!"
        Tier.ZERO_DAY -> "A ONE-IN-A-MILLION HIT!!"
    }
    Style.NEON -> when (t) {
        Tier.SKIPPED -> "STAGE SKIPPED"
        Tier.TIMEOUT -> "TIME OVER"
        Tier.SEGFAULT -> "MISS"
        Tier.HELLO_WORLD -> "GOOD"
        Tier.COPYPASTA -> "NICE"
        Tier.LEGACY -> "GREAT"
        Tier.MEMORY_LEAK -> "EXCELLENT"
        Tier.HEISENBUG -> "AMAZING!"
        Tier.ZERO_DAY -> "PERFECT!!"
    }
    Style.COMIC -> when (t) {
        Tier.SKIPPED -> "PASSED!"
        Tier.TIMEOUT -> "TOO SLOW!"
        Tier.SEGFAULT -> "NOPE!"
        Tier.HELLO_WORLD -> "BASIC."
        Tier.COPYPASTA -> "SNEAKY."
        Tier.LEGACY -> "NICE ONE!"
        Tier.MEMORY_LEAK -> "SHARP!"
        Tier.HEISENBUG -> "RARE STEAL!"
        Tier.ZERO_DAY -> "JACKPOT!!"
    }
    Style.PAPER -> when (t) {
        Tier.SKIPPED -> "Passed"
        Tier.TIMEOUT -> "Out of time"
        Tier.SEGFAULT -> "Not on the list"
        Tier.HELLO_WORLD -> "Common answer"
        Tier.COPYPASTA -> "Clever, but common"
        Tier.LEGACY -> "Accepted"
        Tier.MEMORY_LEAK -> "Uncommon find"
        Tier.HEISENBUG -> "Rare find"
        Tier.ZERO_DAY -> "Exclusive!"
    }
    Style.CLEAN -> when (t) {
        Tier.SKIPPED -> "Skipped"
        Tier.TIMEOUT -> "Time's up"
        Tier.SEGFAULT -> "Not on the list"
        Tier.HELLO_WORLD -> "Correct — but very common"
        Tier.COPYPASTA -> "Correct — popular pick"
        Tier.LEGACY -> "Correct!"
        Tier.MEMORY_LEAK -> "Great answer!"
        Tier.HEISENBUG -> "Brilliant — that's rare!"
        Tier.ZERO_DAY -> "Incredible! The rarest pick!"
    }
    Style.ABYSS -> when (t) {
        Tier.SKIPPED -> "You let it drift"
        Tier.TIMEOUT -> "Out of air"
        Tier.SEGFAULT -> "Nothing down here"
        Tier.HELLO_WORLD -> "Surface water"
        Tier.COPYPASTA -> "Shallow reef"
        Tier.LEGACY -> "Twilight zone"
        Tier.MEMORY_LEAK -> "Midnight zone"
        Tier.HEISENBUG -> "The abyss"
        Tier.ZERO_DAY -> "The deepest trench!"
    }
    Style.METRO -> when (t) {
        Tier.SKIPPED -> "Skipped this stop"
        Tier.TIMEOUT -> "Missed the train"
        Tier.SEGFAULT -> "No such station"
        Tier.HELLO_WORLD -> "Local service"
        Tier.COPYPASTA -> "Regional line"
        Tier.LEGACY -> "Express"
        Tier.MEMORY_LEAK -> "Intercity"
        Tier.HEISENBUG -> "Night express"
        Tier.ZERO_DAY -> "Last train out!"
    }
    Style.JACKPOT -> when (t) {
        Tier.SKIPPED -> "FOLDED"
        Tier.TIMEOUT -> "TIME'S UP"
        Tier.SEGFAULT -> "NO WIN"
        Tier.HELLO_WORLD -> "SMALL WIN"
        Tier.COPYPASTA -> "WIN"
        Tier.LEGACY -> "NICE WIN"
        Tier.MEMORY_LEAK -> "BIG WIN!"
        Tier.HEISENBUG -> "MEGA WIN!"
        Tier.ZERO_DAY -> "JACKPOT!!!"
    }
    Style.CHAT -> when (t) {
        Tier.SKIPPED -> "Skipped 👋"
        Tier.TIMEOUT -> "Time's up ⏰"
        Tier.SEGFAULT -> "Not on the list 🙅"
        Tier.HELLO_WORLD -> "Correct, but everyone said that 😅"
        Tier.COPYPASTA -> "Correct, pretty common 👍"
        Tier.LEGACY -> "Nice one 🙌"
        Tier.MEMORY_LEAK -> "Ooh, good pick 🔥"
        Tier.HEISENBUG -> "Wait, how?? 🤯"
        Tier.ZERO_DAY -> "THE rarest answer 👑"
    }
    Style.DESKTOP -> when (t) {
        Tier.SKIPPED -> "Operation cancelled."
        Tier.TIMEOUT -> "The operation timed out."
        Tier.SEGFAULT -> "Answer not found."
        Tier.HELLO_WORLD -> "Answer accepted (very common)."
        Tier.COPYPASTA -> "Answer accepted (common)."
        Tier.LEGACY -> "Answer accepted."
        Tier.MEMORY_LEAK -> "Uncommon answer found!"
        Tier.HEISENBUG -> "Rare answer found!"
        Tier.ZERO_DAY -> "Rarest answer on the list!"
    }
    Style.ORACLE -> when (t) {
        Tier.SKIPPED -> "The card was set aside"
        Tier.TIMEOUT -> "The candle burned out"
        Tier.SEGFAULT -> "The cards do not know it"
        Tier.HELLO_WORLD -> "A common fortune"
        Tier.COPYPASTA -> "A familiar omen"
        Tier.LEGACY -> "A fair omen"
        Tier.MEMORY_LEAK -> "A rare omen"
        Tier.HEISENBUG -> "A prophecy!"
        Tier.ZERO_DAY -> "Written in the stars!"
    }
    Style.TERMINAL -> t.blurb
}

/** Answer shown on a reveal. */
fun headline(v: app.coredump.core.PlayView): String = when (v.tier) {
    Tier.TIMEOUT -> Th.say("PROCESS KILLED", "Time's up")
    Tier.SKIPPED -> Th.say("SKIPPED", "Skipped")
    Tier.SEGFAULT -> Th.say("Segmentation fault", v.input.ifBlank { "—" })
    else -> v.canonical ?: v.input
}

// ======================================================================= screen

@Composable
fun PlayScreen(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val fx = model.fx
    val haptic = rememberHaptic()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(st.phase) {
        if (st.phase == Phase.OVER) { delay(400); model.finished(session) }
    }
    LaunchedEffect(st.missTick) {
        if (st.missTick > 0) {
            fx.glitch(); haptic(false)
            Sfx.play(if (st.attempts.lastOrNull()?.offline == true) Cue.OFFLINE else Cue.MISS)
        }
    }
    // an answer went off to be searched online
    val checking = st.attempts.count { it.status == app.coredump.game.AttemptStatus.VERIFYING }
    LaunchedEffect(checking) { if (checking > 0) Sfx.play(Cue.CHECK) }
    // autocorrect question: close the keyboard so "Yes" / "No, search" are visible
    LaunchedEffect(st.confirm) { if (st.confirm != null) { keyboard?.hide(); haptic(false); Sfx.play(Cue.CONFIRM) } }
    // last five seconds: one tick per second
    LaunchedEffect(st.round, st.phase, st.paused, st.startedAt, st.seconds) {
        if (st.phase != Phase.TYPING || st.paused || st.seconds <= 0) return@LaunchedEffect
        val end = st.startedAt + st.seconds * 1000L
        while (true) {
            val left = end - Platform.epochMillis()
            if (left <= 0) break
            val sec = ((left + 999) / 1000).toInt()
            if (sec <= 5) Sfx.play(Cue.TICK)
            delay((left - (sec - 1) * 1000L).coerceAtLeast(30))
        }
    }
    var rain by remember { mutableStateOf(false) }
    LaunchedEffect(st.reveal) {
        val v = st.reveal ?: return@LaunchedEffect
        keyboard?.hide()
        Sfx.hit(v.me.tier)
        when (v.me.tier) {
            Tier.ZERO_DAY -> { fx.flash(tierColor(Tier.ZERO_DAY)); fx.shake(1.3f); haptic(true); delay(110); haptic(true); delay(110); haptic(true); rain = true; delay(1800); rain = false }
            Tier.HEISENBUG -> { fx.flash(tierColor(Tier.HEISENBUG).copy(alpha = 0.5f)); fx.shake(0.45f); haptic(true); delay(120); haptic(true) }
            Tier.MEMORY_LEAK -> { fx.flash(tierColor(Tier.MEMORY_LEAK).copy(alpha = 0.3f)); fx.glitch(); haptic(true) }
            Tier.SEGFAULT, Tier.TIMEOUT -> { fx.flash(C.blood.copy(alpha = 0.55f)); fx.shake(0.8f); fx.glitch(); haptic(true) }
            Tier.SKIPPED -> haptic(false)
            Tier.LEGACY -> { fx.flash(tierColor(Tier.LEGACY).copy(alpha = 0.18f)); haptic(false) }
            else -> { fx.flash(tierColor(v.me.tier).copy(alpha = 0.15f)); haptic(false) }
        }
    }
    val a = rememberAnswer(st, { session.submit(it) }, { session.confirm(it) })

    Box(Modifier.fillMaxSize()) {
        when (Th.style) {
            Style.TERMINAL -> TerminalPlay(model, session, st, a)
            Style.NEON -> ArcadePlay(model, session, st, a)
            Style.PAPER -> GazettePlay(model, session, st, a)
            Style.CLEAN -> QuizPlay(model, session, st, a)
            Style.PIXEL -> QuestPlay(model, session, st, a)
            Style.COMIC -> ComicPlay(model, session, st, a)
            Style.ABYSS -> AbyssPlay(model, session, st, a)
            Style.METRO -> MetroPlay(model, session, st, a)
            Style.JACKPOT -> JackpotPlay(model, session, st, a)
            Style.CHAT -> ChatPlay(model, session, st, a)
            Style.DESKTOP -> DesktopPlay(model, session, st, a)
            Style.ORACLE -> OraclePlay(model, session, st, a)
        }
        ThemeTouches(fx, Modifier.fillMaxSize())
        Celebrate(Modifier.fillMaxSize(), rain)
        st.reveal?.let { v -> if (st.phase == Phase.REVEAL) RewardBurst(v.me.tier, v.me.points.coerceAtLeast(0), v.index) }
        if (st.paused && !model.confirmQuit) PauseOverlay({ session.resume() }, { model.confirmQuit = true })
        if (model.confirmQuit) ConfirmQuit(session.isMulti,
            onKeep = { model.confirmQuit = false; session.resume() },
            onAbort = { model.abortRun(session) })
    }
}

/** Bottom action for the reveal / error phases (same logic everywhere, themed buttons). */
@Composable
fun PlayActions(model: AppModel, session: MatchSession, st: MatchState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        when (st.phase) {
            Phase.REVEAL -> {
                st.reveal?.let { DiscardAction(model, session, it.index, Modifier.padding(bottom = 8.dp)) }
                if (st.canAdvance) {
                    TButton(
                        if (st.isLast) Th.say("DUMP CORE ▸", when (Th.style) {
                            Style.NEON -> "FINISH"; Style.PIXEL -> "END BATTLE"; Style.ABYSS -> "Surface"; Style.METRO -> "End of the line"
                            Style.JACKPOT -> "CASH OUT"; Style.DESKTOP -> "Finish"; Style.ORACLE -> "Read the cards"; else -> "See results"
                        })
                        else Th.say("NEXT PROMPT ▸", when (Th.style) {
                            Style.NEON -> "NEXT STAGE"; Style.PIXEL -> "NEXT TURN"; Style.COMIC -> "NEXT!"; Style.ABYSS -> "Descend"
                            Style.METRO -> "Next stop"; Style.JACKPOT -> "SPIN AGAIN"; Style.CHAT -> "Next question"; Style.DESKTOP -> "Next >"
                            Style.ORACLE -> "Draw the next card"; else -> "Next prompt"
                        }),
                        { session.next() }, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp,
                    )
                } else {
                    val waiting = st.message != null
                    TButton(if (waiting) Th.say("WAITING FOR HOST…", "Waiting for host…") else Th.say("READY ▸", "Ready"), { session.next() },
                        Modifier.fillMaxWidth(), primary = !waiting, enabled = !waiting)
                }
            }
            Phase.ERROR -> TButton(Th.say("RETURN TO SHELL", "Back to menu"), { model.abortRun(session) }, Modifier.fillMaxWidth(), color = C.blood)
            Phase.TYPING -> SkipLink(session, st, Modifier.align(Alignment.CenterHorizontally))
            else -> Unit
        }
    }
}

/** Opponent line under the score in 1v1. */
@Composable
fun PeerLine(session: MatchSession, st: MatchState) {
    if (!session.isMulti) return
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        TText(Th.say("PEER ", "") + "${st.oppName ?: "?"} ", size = 11.sp, color = C.dim, maxLines = 1)
        TText(
            when {
                st.phase == Phase.REVEAL -> ""
                st.oppLocked -> Th.say("▣ LOCKED IN", "locked in")
                st.phase == Phase.TYPING || st.phase == Phase.LOCKED -> Th.say("▢ thinking…", "thinking…")
                else -> ""
            }, size = 11.sp, color = if (st.oppLocked) C.hot else C.dim,
        )
        Spacer(Modifier.weight(1f))
        RollingNumber(st.oppTotal, size = 12.sp, color = C.dim, suffix = " pts", weight = FontWeight.Normal)
    }
}

/** Opponent result on a 1v1 reveal + "would have scored more" list (shared content, themed panels). */
@Composable
fun RevealExtras(v: RoundView, multi: Boolean) {
    if (v.me.penalty > 0) {
        Spacer(Modifier.height(8.dp))
        TText(Th.say("-${v.me.penalty} pts: key-mashing detected", "−${v.me.penalty} points for key-mashing"),
            size = 12.sp, color = C.blood, weight = FontWeight.Bold)
    }
    val opp = v.opp
    if (multi && opp != null) {
        Spacer(Modifier.height(12.dp))
        Panel(Th.say("PEER ${opp.player.uppercase()}", opp.player)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TText(
                    when (opp.tier) {
                        Tier.TIMEOUT -> Th.say("<timeout>", "time's up")
                        Tier.SKIPPED -> Th.say("<skipped>", "skipped")
                        Tier.SEGFAULT -> "${opp.input} ✗"
                        else -> opp.canonical ?: opp.input
                    },
                    Modifier.weight(1f), size = 16.sp, color = tierColor(opp.tier), weight = FontWeight.Bold, maxLines = 1,
                )
                TierBadge(opp.tier, 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            TText(duelVerdict(v), size = 12.sp, color = if (v.me.points >= opp.points) C.hot else C.blood, weight = FontWeight.Bold)
        }
    }
    val better = v.betterThan(v.me.points).take(3)
    if (better.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Panel(Th.say("RARER IN MEMORY", when (Th.style) {
            Style.PIXEL -> "Stronger attacks"; Style.NEON -> "Bonus answers"; Style.ABYSS -> "Deeper down"; Style.JACKPOT -> "Bigger payouts"
            Style.ORACLE -> "Rarer omens"; else -> "Would have scored more"
        })) {
            for (g in better) {
                Row(Modifier.padding(vertical = 2.dp)) {
                    TText(g.tier.label, Modifier.width(150.dp), size = 11.sp, color = tierColor(g.tier), weight = FontWeight.Bold)
                    TText(g.names.take(3).joinToString(" · "), size = 11.sp, color = C.amber, maxLines = 2)
                }
            }
        }
    }
    AnswerList(v)
}

/** Names shown per tier before "show all" (count). */
private const val FOLD = 30

/** Bumped by the self-test to scroll the reveal down to the answer list (for a screenshot). */
object RevealProbe {
    var ping by mutableIntStateOf(0)
}

/**
 * Every answer on the list after a prompt, by tier (most common first, most familiar first inside a tier).
 * The answers given this round are marked in their tier colour. Long tiers fold to [FOLD] names.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnswerList(v: RoundView) {
    // a 1v1 guest gets the full list from the host a moment after the result (older hosts: only the top few)
    val full = v.all.isNotEmpty()
    val groups = if (full) v.all else v.ladder.reversed()
    if (groups.isEmpty()) return
    val total = if (full) groups.sumOf { it.names.size } else v.listSize
    val given = listOfNotNull(v.me.canonical, v.opp?.canonical).map { app.coredump.core.Norm.key(it) }.toSet()
    Spacer(Modifier.height(12.dp))
    val view = remember { BringIntoViewRequester() }
    // only pings made while this reveal is on screen
    val since = remember { RevealProbe.ping }
    LaunchedEffect(RevealProbe.ping) { if (RevealProbe.ping > since) runCatching { view.bringIntoView() } }
    Panel(Th.say("FULL LIST · ${group(total)} ANSWERS", "All ${group(total)} answers"), Modifier.bringIntoViewRequester(view)) {
        if (!full) {
            TText(Th.say("top answers only (full list not received)", "Only the top answers (the full list did not arrive)"), size = 10.sp, color = C.dim)
            Spacer(Modifier.height(6.dp))
        }
        for ((gi, g) in groups.withIndex()) key(v.index, g.tier) {
            var open by remember { mutableStateOf(false) }
            val shown = if (open) g.names else g.names.take(FOLD)
            if (gi > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierBadge(g.tier, 10.sp, animate = false)
                Spacer(Modifier.width(8.dp))
                TText("${g.names.size} · +${g.tier.points}", size = 10.sp, color = C.dim)
            }
            Spacer(Modifier.height(4.dp))
            val mark = tierColor(g.tier)
            TText(
                buildAnnotatedString {
                    shown.forEachIndexed { i, n ->
                        if (i > 0) append("  ·  ")
                        if (app.coredump.core.Norm.key(n) in given) {
                            withStyle(SpanStyle(color = mark, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(n) }
                        } else append(n)
                    }
                },
                size = 12.sp, color = C.amber,
            )
            if (g.names.size > FOLD) {
                Spacer(Modifier.height(2.dp))
                TLink(if (open) Th.say("fold", "Show fewer") else Th.say("+${g.names.size - FOLD} more", "Show all ${g.names.size}"),
                    { open = !open }, size = 11.sp, color = C.hot)
            }
        }
    }
}

// ======================================================================= TERMINAL (CRT shell)

@Composable
private fun TerminalPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    Column(Modifier.fillMaxSize()) {
        PlayHeader(model, session, st)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TText(Th.say("PID ", "Prompt "), size = 12.sp, color = C.dim)
                TText("${(st.round + 1).toString().padStart(2, '0')}/${st.total.toString().padStart(2, '0')}", size = 14.sp, weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TText("SCORE ", size = 12.sp, color = C.dim)
                RollingNumber(st.myTotal, size = 15.sp, color = C.hot, suffix = " pts")
            }
            PeerLine(session, st)
            st.via?.let { TText("via $it", size = 9.sp, color = C.line, maxLines = 1) }
            Spacer(Modifier.height(10.dp))
            TimerBar(st.startedAt, st.seconds, isRunning(st))
            Spacer(Modifier.height(14.dp))
            if (st.phase == Phase.WAITING) {
                Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); TText("  ${st.message ?: "linking…"}", size = 13.sp, color = C.dim) }
            } else if (st.prompt.isNotEmpty()) {
                Panel("QUERY ${hexAddr(st.round * 16 + 0x40)}") {
                    TText("#${st.topic}  ·  ${group(st.listSize)} entries in memory", size = 11.sp, color = C.dim)
                    Spacer(Modifier.height(8.dp))
                    key(st.round) { Typewriter("> ${st.prompt}", size = 21.sp, charMs = 16) }
                }
            }
            Spacer(Modifier.height(16.dp))
            when (st.phase) {
                Phase.TYPING -> if (!st.paused) {
                    Column {
                        AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a))
                        Spacer(Modifier.height(8.dp))
                        SuggestionChip(st, a)
                        Spacer(Modifier.height(8.dp))
                        AttemptChips(st)
                        Spacer(Modifier.height(10.dp))
                        TButton("EXECUTE", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true)
                        Spacer(Modifier.height(8.dp))
                        TText(
                            if (st.attempts.isEmpty()) "rarer = more points. wrong answers cost nothing but time."
                            else "${st.attempts.count { it.status == AttemptStatus.REJECTED }} rejected · keep going until the clock dies.",
                            size = 10.sp, color = C.line,
                        )
                    }
                }
                Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(C.hot); TText("  answer locked. awaiting peer process…", size = 13.sp, color = C.dim)
                }
                Phase.REVEAL -> st.reveal?.let { v ->
                    TerminalReveal(v, st.attempts.size)
                    RevealExtras(v, session.isMulti)
                }
                Phase.ERROR -> Panel("KERNEL PANIC", color = C.blood, titleColor = C.blood) {
                    TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood)
                }
                else -> Unit
            }
        }
        PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

@Composable
fun PlayHeader(model: AppModel, session: MatchSession, st: MatchState, title: String? = null) {
    val t = title ?: when {
        st.daily != null -> Th.say("DAILY RUN #${st.daily}", "Daily run #${st.daily}")
        session.isMulti -> Th.say("1v1 :: ${st.myName} vs ${st.oppName ?: "?"}", "${st.myName} vs ${st.oppName ?: "?"}")
        else -> Th.say("RUN :: ${st.myName}", "Solo run")
    }
    Box {
        Header(model, t) { session.pause(); model.confirmQuit = true }
        if (st.canPause && st.phase == Phase.TYPING && !st.paused) {
            TText(if (Th.terminal) "[ ‖ ]" else "‖", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 2.dp)
                .background(C.bg)
                .clickable(remember { MutableInteractionSource() }, null) { session.pause() }
                .padding(horizontal = 8.dp, vertical = 8.dp), size = 14.sp, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TerminalReveal(v: RoundView, misses: Int) {
    val me = v.me
    val c = tierColor(me.tier)
    val pop = remember(v.index) { Animatable(1.8f) }
    LaunchedEffect(v.index) { delay(450); pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)) }
    Panel(if (me.tier.isValid) "STDOUT" else "STDERR", color = if (me.tier.isValid) C.line else C.bloodDim,
        titleColor = if (me.tier.isValid) C.dim else C.blood) {
        TText("$ ${me.input.ifBlank { "<null>" }}" + if (misses > 0) "   ($misses miss${if (misses == 1) "" else "es"} before)" else "",
            size = 12.sp, color = C.dim, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        DecodeText(headline(me), size = 26.sp, color = c)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierBadge(me.tier)
            Spacer(Modifier.width(12.dp))
            TText(signed(me.points), Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = (2.2f - pop.value).coerceIn(0f, 1f) },
                size = 22.sp, color = c, weight = FontWeight.Bold, glow = 1.6f)
            Spacer(Modifier.weight(1f))
            TText(me.tier.addr, size = 11.sp, color = C.line)
        }
        Spacer(Modifier.height(8.dp))
        key(v.index) { Typewriter(me.tier.blurb, size = 12.sp, color = C.dim, weight = FontWeight.Normal, charMs = 14, cursor = false) }
    }
}

/** Countdown: segmented bar (terminal / pixel) or smooth bar; heartbeat in the last 5 s. */
@Composable
fun TimerBar(startedAt: Long, seconds: Int, running: Boolean) {
    if (seconds <= 0) {
        TText(Th.say("∞  no deadline. the machine is patient.", "∞  No time limit"), size = 11.sp, color = C.dim)
        return
    }
    val now by rememberNow(startedAt, running)
    val totalMs = seconds * 1000L
    val leftMs = (totalMs - (now - startedAt)).coerceIn(0L, totalMs)
    val f = leftMs / totalMs.toFloat()
    val danger = running && leftMs in 1..5000
    val color = lerp(C.blood, C.amber, (f * 3f).coerceIn(0f, 1f))
    val beat = if (danger && !LocalLook.current.reduceMotion) abs(sin(now / 1000.0 * PI * 1.8)).toFloat() else 0f
    val segmented = Th.style == Style.TERMINAL || Th.style == Style.PIXEL
    val track = C.ghost
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(if (segmented) 14.dp else 10.dp).graphicsLayer { scaleY = 1f + beat * 0.35f }) {
            if (!segmented) {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = r)
                if (f > 0f) drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
                return@Canvas
            }
            val n = 32
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val filled = ceil(f * n).toInt()
            for (i in 0 until n) {
                val x = i * (w + gap)
                val on = i < filled
                val edge = i == filled - 1
                drawRect(if (on) color.copy(alpha = if (edge) 0.55f + 0.45f * (1f - beat) else 1f) else track, Offset(x, 0f), Size(w, size.height))
            }
        }
        val tenths = (leftMs + 99) / 100
        TText("  ${tenths / 10}.${tenths % 10}s", Modifier.width(72.dp).offset(x = if (danger) ((now / 40) % 3 - 1).toInt().dp else 0.dp),
            size = 14.sp, color = color, weight = FontWeight.Bold)
    }
}

@Composable
fun PauseOverlay(onResume: () -> Unit, onAbort: () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.94f))
        .clickable(remember { MutableInteractionSource() }, null) { }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (Th.terminal) TText("^Z", size = 14.sp, color = C.dim)
            DecodeText(Th.say("PROCESS SUSPENDED", when (Th.style) {
                Style.NEON -> "PAUSE"; Style.PIXEL -> "PAUSED"; Style.COMIC -> "HOLD UP!"; Style.ABYSS -> "Holding depth"; Style.METRO -> "Train held"
                Style.JACKPOT -> "MACHINE PAUSED"; Style.ORACLE -> "The candle waits"; else -> "Paused"
            }), size = 22.sp)
            TText(Th.say("SIGSTOP · the clock is frozen", "The clock is stopped"), size = 12.sp, color = C.dim, align = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            TButton(Th.say("RESUME (fg)", "Resume"), onResume, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            TButton(Th.say("ABORT RUN", "Quit run"), onAbort, Modifier.fillMaxWidth(), color = C.blood)
        }
    }
}

@Composable
fun ConfirmQuit(multi: Boolean, onKeep: () -> Unit, onAbort: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
        .clickable(remember { MutableInteractionSource() }, null) { }, contentAlignment = Alignment.Center) {
        Panel(Th.say("CONFIRM", "Quit?"), Modifier.padding(28.dp).background(C.bg), color = C.blood, titleColor = C.blood) {
            TText(Th.say("kill -9 this run?", "Quit this run?"), size = 18.sp, weight = FontWeight.Bold, color = C.blood)
            Spacer(Modifier.height(6.dp))
            TText(if (multi) Th.say("your peer will be disconnected.", "Your opponent will be disconnected.")
                else Th.say("progress in this run is lost.", "Progress in this run is lost."), size = 12.sp, color = C.dim)
            Spacer(Modifier.height(18.dp))
            TButton(Th.say("KEEP GOING", "Keep playing"), onKeep, Modifier.fillMaxWidth(), primary = true)
            Spacer(Modifier.height(10.dp))
            TButton(Th.say("ABORT", "Quit"), onAbort, Modifier.fillMaxWidth(), color = C.blood)
        }
    }
}
