package app.coredump.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.core.depthFrames
import app.coredump.core.hexAddr
import app.coredump.game.Attempt
import app.coredump.game.AttemptStatus
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.game.duelVerdict
import app.coredump.platform.Platform
import app.coredump.ui.C
import app.coredump.ui.DaemonShaft
import app.coredump.ui.DecodeText
import app.coredump.ui.HexRain
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.RollingNumber
import app.coredump.ui.Spinner
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.TermInput
import app.coredump.ui.TierBadge
import app.coredump.ui.Typewriter
import app.coredump.ui.group
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

@Composable
fun PlayScreen(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val fx = model.fx
    val haptic = rememberHaptic()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(st.phase) {
        if (st.phase == Phase.OVER) { delay(400); model.finished(session) }
    }
    LaunchedEffect(st.myTotal, st.total) {
        model.depthFraction = if (st.total > 0) st.myTotal / (st.total * 100f) else 0f
    }
    // wrong attempt: small glitch + haptic
    LaunchedEffect(st.missTick) {
        if (st.missTick > 0) { fx.glitch(); haptic(false) }
    }
    // screen effects when a reveal lands
    var rain by remember { mutableStateOf(false) }
    LaunchedEffect(st.reveal) {
        val v = st.reveal ?: return@LaunchedEffect
        keyboard?.hide()
        when (v.me.tier) {
            Tier.ZERO_DAY -> { fx.flash(C.white); fx.shake(1.3f); haptic(true); rain = true; delay(1800); rain = false }
            Tier.HEISENBUG -> { fx.flash(C.hot.copy(alpha = 0.6f)); fx.shake(0.45f); haptic(true) }
            Tier.MEMORY_LEAK -> { fx.glitch(); haptic(false) }
            Tier.SEGFAULT, Tier.TIMEOUT -> { fx.flash(C.blood.copy(alpha = 0.55f)); fx.shake(0.8f); fx.glitch(); haptic(true) }
            else -> haptic(false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PlayHeader(model, session, st)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
                    StatusRow(session, st)
                    Spacer(Modifier.height(10.dp))
                    TimerBar(st.startedAt, st.seconds, running = (st.phase == Phase.TYPING || st.phase == Phase.LOCKED) && !st.paused)
                    Spacer(Modifier.height(14.dp))

                    if (st.phase == Phase.WAITING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(); TText("  ${st.message ?: "linking…"}", size = 13.sp, color = C.dim)
                        }
                    } else if (st.prompt.isNotEmpty()) {
                        Panel("QUERY ${hexAddr(st.round * 16 + 0x40)}") {
                            TText("#${st.topic}  ·  ${group(st.listSize)} entries in memory", size = 11.sp, color = C.dim)
                            Spacer(Modifier.height(8.dp))
                            key(st.round) { Typewriter("> ${st.prompt}", size = 21.sp, charMs = 16) }
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    when (st.phase) {
                        Phase.TYPING -> if (!st.paused) AnswerBox(st) { text -> session.submit(text) }
                        Phase.LOCKED -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(C.hot)
                            TText("  answer locked. awaiting peer process…", size = 13.sp, color = C.dim)
                        }
                        Phase.REVEAL -> st.reveal?.let { Reveal(it, session.isMulti, st.attempts.size) }
                        Phase.ERROR -> Panel("KERNEL PANIC", color = C.blood, titleColor = C.blood) {
                            TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood)
                        }
                        else -> Unit
                    }
                }
                DaemonShaft(
                    depthFrames = depthFrames(st.myTotal),
                    modifier = Modifier.width(64.dp).fillMaxHeight(),
                    lastTier = st.rounds.lastOrNull()?.me?.tier,
                    missTick = st.missTick,
                )
            }

            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                when (st.phase) {
                    Phase.REVEAL -> {
                        if (st.canAdvance) {
                            TButton(if (st.isLast) "DUMP CORE ▸" else "NEXT PROMPT ▸", { session.next() },
                                Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
                        } else {
                            val waiting = st.message != null
                            TButton(if (waiting) "WAITING FOR HOST…" else "READY ▸", { session.next() },
                                Modifier.fillMaxWidth(), primary = !waiting, enabled = !waiting)
                        }
                    }
                    Phase.ERROR -> TButton("RETURN TO SHELL", { model.abortRun(session) }, Modifier.fillMaxWidth(), color = C.blood)
                    else -> Unit
                }
            }
        }
        HexRain(Modifier.fillMaxSize(), rain)
        if (st.paused && !model.confirmQuit) PauseOverlay({ session.resume() }, { model.confirmQuit = true })
        if (model.confirmQuit) ConfirmQuit(session.isMulti,
            onKeep = { model.confirmQuit = false; session.resume() },
            onAbort = { model.abortRun(session) })
    }
}

@Composable
private fun PlayHeader(model: AppModel, session: MatchSession, st: MatchState) {
    val title = when {
        st.daily != null -> "DAILY DIVE #${st.daily}"
        session.isMulti -> "1v1 :: ${st.myName} vs ${st.oppName ?: "?"}"
        else -> "DIVE :: ${st.myName}"
    }
    Box {
        Header(model, title) { session.pause(); model.confirmQuit = true }
        if (st.canPause && st.phase == Phase.TYPING && !st.paused) {
            TText("[ ‖ ]", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 2.dp)
                .background(C.bg)
                .clickable(remember { MutableInteractionSource() }, null) { session.pause() }
                .padding(horizontal = 8.dp, vertical = 8.dp), size = 14.sp, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusRow(session: MatchSession, st: MatchState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TText("PID ", size = 12.sp, color = C.dim)
        TText("${(st.round + 1).toString().padStart(2, '0')}/${st.total.toString().padStart(2, '0')}", size = 14.sp, weight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        TText("DEPTH ", size = 12.sp, color = C.dim)
        RollingNumber(depthFrames(st.myTotal), size = 15.sp, color = C.hot, suffix = " fr")
    }
    if (session.isMulti) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            TText("PEER ${st.oppName ?: "?"} ", size = 11.sp, color = C.dim, maxLines = 1)
            TText(
                when {
                    st.phase == Phase.REVEAL -> ""
                    st.oppLocked -> "▣ LOCKED IN"
                    st.phase == Phase.TYPING || st.phase == Phase.LOCKED -> "▢ thinking…"
                    else -> ""
                }, size = 11.sp, color = if (st.oppLocked) C.hot else C.dim,
            )
            Spacer(Modifier.weight(1f))
            RollingNumber(depthFrames(st.oppTotal), size = 12.sp, color = C.dim, suffix = " fr", weight = FontWeight.Normal)
        }
        st.via?.let { TText("via $it", size = 9.sp, color = C.line, maxLines = 1) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerBox(st: MatchState, onSubmit: (String) -> Unit) {
    var text by remember(st.round) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val shake = remember { Animatable(0f) }
    val motion = !LocalLook.current.reduceMotion
    LaunchedEffect(st.round) { delay(250); runCatching { focus.requestFocus() } }
    LaunchedEffect(st.missTick) {
        if (st.missTick == 0 || !motion) return@LaunchedEffect
        shake.snapTo(1f)
        shake.animateTo(0f, tween(420))
    }
    fun send(v: String) {
        val t = v.trim()
        if (t.isEmpty()) return
        onSubmit(t)
        text = ""
    }
    val latestSuggestion = st.attempts.lastOrNull { it.suggestion != null }?.suggestion
        ?.takeIf { s -> st.attempts.none { it.text.equals(s, ignoreCase = true) } }

    Column {
        TermInput(
            value = text,
            onValueChange = { if (it.length <= 60) text = it },
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                translationX = sin(shake.value * 30f) * 14f * shake.value
            }.border(1.dp, lerp(C.line, C.blood, shake.value)),
            placeholder = if (st.attempts.isEmpty()) "type an answer…" else "try again…",
            onSubmit = { send(text) },
            fieldModifier = Modifier.focusRequester(focus),
        )
        if (latestSuggestion != null) {
            Spacer(Modifier.height(8.dp))
            TText(
                "did you mean  $latestSuggestion ?  ↵",
                Modifier.border(1.dp, C.hot).background(C.panel)
                    .clickable(remember { MutableInteractionSource() }, null) { send(latestSuggestion) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                size = 13.sp, color = C.hot, weight = FontWeight.Bold,
            )
        }
        if (st.attempts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in st.attempts) AttemptChip(a)
            }
        }
        Spacer(Modifier.height(10.dp))
        TButton("EXECUTE", { send(text) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank(), primary = true)
        Spacer(Modifier.height(8.dp))
        TText(
            if (st.attempts.isEmpty()) "rarer = deeper. wrong answers cost nothing but time."
            else "${st.attempts.count { it.status == AttemptStatus.REJECTED }} rejected · keep going until the clock dies.",
            size = 10.sp, color = C.line,
        )
    }
}

@Composable
private fun AttemptChip(a: Attempt) {
    Row(
        Modifier.border(1.dp, if (a.status == AttemptStatus.VERIFYING) C.dim else C.bloodDim)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (a.status == AttemptStatus.VERIFYING) {
            Spinner(C.dim, 11.sp)
            TText(" checking ${a.text}", size = 11.sp, color = C.dim, maxLines = 1)
        } else {
            TText("✗ ", size = 11.sp, color = C.blood)
            TText(a.text, size = 11.sp, color = C.blood.copy(alpha = 0.8f), decoration = TextDecoration.LineThrough, maxLines = 1)
        }
    }
}

/** Countdown: segmented bar, drains amber → blood, heartbeat in the last 5 s. seconds = limit (s), 0 = none. */
@Composable
fun TimerBar(startedAt: Long, seconds: Int, running: Boolean) {
    if (seconds <= 0) {
        TText("∞  no deadline. the machine is patient.", size = 11.sp, color = C.dim)
        return
    }
    var now by remember { mutableLongStateOf(Platform.epochMillis()) }
    LaunchedEffect(startedAt, running) {
        now = Platform.epochMillis()
        while (running) { withFrameMillis { }; now = Platform.epochMillis() }
    }
    val totalMs = seconds * 1000L
    val leftMs = (totalMs - (now - startedAt)).coerceIn(0L, totalMs)
    val f = leftMs / totalMs.toFloat()
    val danger = running && leftMs in 1..5000
    val color = lerp(C.blood, C.amber, (f * 3f).coerceIn(0f, 1f))
    val beat = if (danger && !LocalLook.current.reduceMotion) abs(sin(now / 1000.0 * PI * 1.8)).toFloat() else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(14.dp).graphicsLayer { scaleY = 1f + beat * 0.35f }) {
            val n = 32
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val filled = ceil(f * n).toInt()
            for (i in 0 until n) {
                val x = i * (w + gap)
                val on = i < filled
                val edge = i == filled - 1
                drawRect(
                    if (on) color.copy(alpha = if (edge) 0.55f + 0.45f * (1f - beat) else 1f) else C.ghost,
                    Offset(x, 0f), Size(w, size.height),
                )
            }
        }
        val tenths = (leftMs + 99) / 100
        TText(
            "  ${tenths / 10}.${tenths % 10}s",
            Modifier.width(72.dp).offset(x = if (danger) ((now / 40) % 3 - 1).toInt().dp else 0.dp),
            size = 14.sp, color = color, weight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Reveal(v: RoundView, multi: Boolean, misses: Int) {
    val me = v.me
    val c = tierColor(me.tier)
    val pop = remember(v.index) { Animatable(1.8f) }
    LaunchedEffect(v.index) { delay(450); pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)) }

    Panel(if (me.tier.isValid) "STDOUT" else "STDERR", color = if (me.tier.isValid) C.line else C.bloodDim,
        titleColor = if (me.tier.isValid) C.dim else C.blood) {
        TText("$ ${me.input.ifBlank { "<null>" }}" + if (misses > 0) "   ($misses miss${if (misses == 1) "" else "es"} before)" else "",
            size = 12.sp, color = C.dim, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        val headline = when (me.tier) {
            Tier.TIMEOUT -> "PROCESS KILLED"
            Tier.SEGFAULT -> "Segmentation fault"
            else -> me.canonical ?: me.input
        }
        DecodeText(headline, size = 26.sp, color = c)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierBadge(me.tier)
            Spacer(Modifier.width(12.dp))
            TText(
                "+${me.points}",
                Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = (2.2f - pop.value).coerceIn(0f, 1f) },
                size = 22.sp, color = c, weight = FontWeight.Bold, glow = 1.6f,
            )
            Spacer(Modifier.weight(1f))
            TText(me.tier.addr, size = 11.sp, color = C.line)
        }
        Spacer(Modifier.height(8.dp))
        key(v.index) {
            Typewriter(me.tier.blurb, size = 12.sp, color = C.dim, weight = FontWeight.Normal, charMs = 14, cursor = false)
        }
    }

    val opp = v.opp
    if (multi && opp != null) {
        Spacer(Modifier.height(12.dp))
        Panel("PEER ${opp.player.uppercase()}") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TText(
                    when (opp.tier) {
                        Tier.TIMEOUT -> "<timeout>"
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

    val deeper = v.betterThan(me.points).take(3)
    if (deeper.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Panel("DEEPER IN MEMORY") {
            for (g in deeper) {
                Row(Modifier.padding(vertical = 2.dp)) {
                    TText(g.tier.label.padEnd(12), size = 11.sp, color = tierColor(g.tier))
                    TText(g.names.take(3).joinToString(" · "), size = 11.sp, color = C.amber, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun PauseOverlay(onResume: () -> Unit, onAbort: () -> Unit) {
    Box(Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.94f))
        .clickable(remember { MutableInteractionSource() }, null) { }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TText("^Z", size = 14.sp, color = C.dim)
            DecodeText("PROCESS SUSPENDED", size = 22.sp)
            TText("SIGSTOP · the clock is frozen", size = 12.sp, color = C.dim, align = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            TButton("RESUME (fg)", onResume, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            TButton("ABORT RUN", onAbort, Modifier.fillMaxWidth(), color = C.blood)
        }
    }
}

@Composable
private fun ConfirmQuit(multi: Boolean, onKeep: () -> Unit, onAbort: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
        .clickable(remember { MutableInteractionSource() }, null) { }, contentAlignment = Alignment.Center) {
        Panel("CONFIRM", Modifier.padding(28.dp), color = C.blood, titleColor = C.blood) {
            TText("kill -9 this run?", size = 18.sp, weight = FontWeight.Bold, color = C.blood)
            Spacer(Modifier.height(6.dp))
            TText(if (multi) "your peer will be disconnected." else "progress in this dive is lost.", size = 12.sp, color = C.dim)
            Spacer(Modifier.height(18.dp))
            TButton("KEEP DIVING", onKeep, Modifier.fillMaxWidth(), primary = true)
            Spacer(Modifier.height(10.dp))
            TButton("ABORT", onAbort, Modifier.fillMaxWidth(), color = C.blood)
        }
    }
}
