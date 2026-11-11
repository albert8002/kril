package app.coredump.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.game.MeltPhase
import app.coredump.game.MeltPlayer
import app.coredump.game.MeltSession
import app.coredump.game.MeltState
import app.coredump.game.Meltdown
import app.coredump.game.PartyGuest
import app.coredump.net.JoinTicket
import app.coredump.platform.rememberQrScanLauncher
import app.coredump.ui.C
import app.coredump.ui.Choice
import app.coredump.ui.Cue
import app.coredump.ui.LocalLook
import app.coredump.ui.ModalCard
import app.coredump.ui.Panel
import app.coredump.ui.Sfx
import app.coredump.ui.Spinner
import app.coredump.ui.Style
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.TermInput
import app.coredump.ui.Th
import app.coredump.ui.TierBadge
import app.coredump.ui.box
import app.coredump.ui.rememberClock
import app.coredump.ui.rememberHaptic
import app.coredump.ui.starburst
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * MELTDOWN screens: the setup (play bots, host a party, join one) and the arena (the core in the middle, the players
 * in a ring round it, the answers said so far, the input for whoever holds the core).
 */

private enum class MeltTab(val label: String) { BOTS("BOTS"), HOST("HOST PARTY"), JOIN("JOIN PARTY") }

private val skill = listOf("EASY", "NORMAL", "HARD")

/** What the core is called in each look. */
private fun coreWord(): String = when (Th.style) {
    Style.TERMINAL -> "the kernel"
    Style.NEON -> "the bomb"
    Style.PAPER -> "the hot story"
    Style.CLEAN -> "the hot potato"
    Style.PIXEL -> "the cursed orb"
    Style.COMIC -> "the bomb"
    Style.ABYSS -> "the reactor"
    Style.METRO -> "the hot seat"
    Style.JACKPOT -> "the hot dice"
    Style.CHAT -> "the hot take"
    Style.DESKTOP -> "the overheating CPU"
    Style.ORACLE -> "the burning star"
}

@Composable
fun MeltdownScreen(model: AppModel) {
    var tab by remember { mutableStateOf(if (model.partyGuest != null) MeltTab.JOIN else if (model.partyHost != null) MeltTab.HOST else MeltTab.BOTS) }
    Frame(model, Th.say("./run --royale", "Meltdown")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            MeltRules(model)
            Spacer(Modifier.height(14.dp))
            Choice(MeltTab.entries, tab, { it.label }) { tab = it }
            Spacer(Modifier.height(14.dp))
            when (tab) {
                MeltTab.BOTS -> { BotsPane(model) }
                MeltTab.HOST -> { PartyHostPane(model) }
                MeltTab.JOIN -> { PartyJoinPane(model) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MeltRules(model: AppModel) {
    Panel(Th.say("man meltdown", "How Meltdown works")) {
        val w = coreWord()
        for (line in listOf(
            "2–8 players sit round $w. whoever holds it names an answer nobody said yet this round, and it passes on.",
            "every turn has a countdown (the host sets the seconds). still holding $w at 0: it blows, you lose a heart.",
            "type ahead while you wait: press enter and it goes out the moment it's your turn.",
            "not on the list? the host checks it online. a miss shows \"did you mean\".",
            "rare answers fill your meter. full meter: +1 heart (3 at most). no hearts left: out. last one standing wins.",
        )) {
            Row(Modifier.padding(vertical = 2.dp)) {
                TText("•", Modifier.width(14.dp), size = 12.sp, color = C.hot)
                TText(line, size = 12.sp, color = C.amber)
            }
        }
        val s = model.settings
        if (s.meltGames > 0) {
            Spacer(Modifier.height(6.dp))
            TText("${s.meltGames} games · ${s.meltWins} wins", size = 11.sp, color = C.dim)
        }
    }
}

/** Seconds per turn, topics and bot skill (for bots games and the party host). */
@Composable
private fun MeltRulesSetup(model: AppModel, bots: Boolean) {
    val c = model.meltConfig
    TText("seconds per turn", size = 12.sp, color = C.dim)
    Spacer(Modifier.height(6.dp))
    Choice(Meltdown.TURN_CHOICES, c.turnSec, { "${it}s" }) { model.meltConfig = c.copy(turnSec = it) }
    Spacer(Modifier.height(12.dp))
    TText("topics" + if (c.topics.isEmpty()) " · all" else " · ${c.topics.size} chosen", size = 12.sp, color = C.dim)
    Spacer(Modifier.height(6.dp))
    val keys = app.coredump.core.Topics.all.map { it.key }
    for (row in keys.chunked(3)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            for (k in row) {
                val on = k in c.topics
                app.coredump.ui.ChipBox(app.coredump.core.Topics.label(k), on, Modifier.weight(1f).padding(horizontal = 3.dp), size = 11.sp) {
                    model.meltConfig = c.copy(topics = if (on) c.topics - k else c.topics + k)
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (c.topics.isNotEmpty()) TLink("all topics", { model.meltConfig = c.copy(topics = emptySet()) })
    if (bots) {
        Spacer(Modifier.height(12.dp))
        TText("bot skill", size = 12.sp, color = C.dim)
        Spacer(Modifier.height(6.dp))
        Choice(listOf(0, 1, 2), c.botLevel, { skill[it] }) { model.meltConfig = c.copy(botLevel = it) }
    }
}

@Composable
private fun BotsPane(model: AppModel) {
    var bots by remember { mutableIntStateOf(model.lastMelt.first) }
    TText("rivals", size = 12.sp, color = C.dim)
    Spacer(Modifier.height(6.dp))
    Choice((1..Meltdown.MAX_PLAYERS - 1).toList(), bots, { "$it" }) { bots = it }
    Spacer(Modifier.height(12.dp))
    MeltRulesSetup(model, bots = true)
    Spacer(Modifier.height(18.dp))
    TButton(Th.say("IGNITE", "Start"), { model.startMeltBots(bots) }, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
    Spacer(Modifier.height(6.dp))
    TText("works offline. answers not on a list are checked online when there is internet.", size = 10.sp, color = C.line)
}

/** A player's rating and streak, short ("1034 · 3★"), or "" when unknown. */
internal fun ratingLine(elo: Int, streak: Int): String = buildString {
    if (elo > 0) append(elo)
    if (streak > 1) { if (isNotEmpty()) append(" · "); append("$streak★") }
}

@Composable
private fun PartyHostPane(model: AppModel) {
    val host = remember { model.openPartyHost() }
    val lobby by host.lobby.collectAsState()
    var bots by remember { mutableIntStateOf(0) }
    val s = model.settings
    // guests see the rules while they wait
    LaunchedEffect(model.meltConfig) { host.setConfig(model.meltConfig) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!lobby.open) { Spinner(C.hot); TText("  ", size = 12.sp) }
        TText("status: ${lobby.status}", size = 12.sp, color = if (lobby.error != null) C.blood else C.amber)
    }
    lobby.error?.let { TText("  $it", size = 11.sp, color = C.blood) }
    Spacer(Modifier.height(12.dp))
    val uri = JoinTicket("", 0, "", s.handle, host.room, s.roomBroker).toUri().replace("coredump://join?", "coredump://party?")
    TText(Th.say("let them scan this sigil:", "Friends scan this code to join:"), size = 12.sp, color = C.dim)
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrSigil(uri, Modifier.widthIn(max = 230.dp).fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    TText("or type your code:", size = 11.sp, color = C.dim)
    TText(JoinTicket.pretty(host.room), size = 26.sp, weight = FontWeight.Bold, color = C.hot, spacing = 3.sp)
    TText("same code as your 1v1 code. it stays the same when you leave the app.", size = 10.sp, color = C.dim)
    Spacer(Modifier.height(8.dp))
    TButton("Share code", {
        app.coredump.platform.shareText("Join my CORE DUMP Meltdown party! Code: ${JoinTicket.pretty(host.room)}\n$uri")
    }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    Panel(Th.say("SEATED", "Players") + " (${lobby.players.size}/${Meltdown.MAX_PLAYERS})") {
        lobby.players.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                TText("${i + 1}.", Modifier.width(24.dp), size = 12.sp, color = C.dim)
                Column(Modifier.weight(1f)) {
                    TText(p.name, size = 14.sp, weight = FontWeight.Bold)
                    val r = ratingLine(p.elo, p.streak) + if (p.wins > 0) " · ${p.wins} meltdown wins" else ""
                    if (r.isNotEmpty()) TText(r, size = 10.sp, color = C.dim)
                }
                TText(if (i == 0) "host (you)" else "joined", size = 11.sp, color = if (i == 0) C.hot else C.dim)
            }
        }
        if (lobby.players.size < 2) TText("waiting for friends… or add bots below.", size = 11.sp, color = C.dim)
    }
    if (s.friends.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Panel(Th.say("INVITE", "Invite friends")) {
            for (f in s.friends.sortedBy { it.name.lowercase() }) {
                val seated = lobby.players.any { it.name.equals(f.name, ignoreCase = true) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TText(f.name, Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold)
                    if (seated) TText("seated", size = 11.sp, color = C.hot)
                    else TLink("invite", { model.inviteFriend(f, "party") }, color = C.hot)
                }
            }
            InviteStatus(model, "party")
            TText("they get a pop-up in the app (it must be open) and sit down with one tap.", size = 10.sp, color = C.dim)
        }
    }
    Spacer(Modifier.height(12.dp))
    MeltRulesSetup(model, bots = bots > 0)
    Spacer(Modifier.height(12.dp))
    val maxBots = (Meltdown.MAX_PLAYERS - lobby.players.size).coerceAtLeast(0)
    TText("add bots", size = 12.sp, color = C.dim)
    Spacer(Modifier.height(6.dp))
    Choice((0..maxBots).toList(), bots.coerceAtMost(maxBots), { "$it" }) { bots = it }
    Spacer(Modifier.height(16.dp))
    val total = lobby.players.size + bots.coerceAtMost(maxBots)
    TButton(Th.say("IGNITE", "Start Meltdown") + " · $total players", { model.startParty(bots.coerceAtMost(maxBots)) },
        Modifier.fillMaxWidth(), primary = true, enabled = total >= 2, textSize = 16.sp)
}

@Composable
private fun PartyJoinPane(model: AppModel) {
    var manual by remember { mutableStateOf("") }
    val scan = rememberQrScanLauncher { raw -> if (raw != null) model.joinParty(raw) }
    TButton(Th.say("SCAN SIGIL", "Scan QR code"), { scan() }, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
    Spacer(Modifier.height(14.dp))
    TText("or type the host's code:", size = 12.sp, color = C.dim)
    Spacer(Modifier.height(6.dp))
    TermInput(manual, { manual = it.uppercase().take(12) }, Modifier.fillMaxWidth(), prompt = "join", placeholder = "K7Q2-M9XA",
        size = 16.sp, onSubmit = { model.joinParty(manual) })
    Spacer(Modifier.height(8.dp))
    TButton("JOIN", { model.joinParty(manual) }, Modifier.fillMaxWidth(), enabled = manual.isNotBlank())
    val g = model.partyGuest
    if (g != null) {
        val lb by g.lobby.collectAsState()
        val gs by g.state.collectAsState()
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!lb.open && lb.error == null) { Spinner(C.hot); TText("  ", size = 12.sp) }
            TText("status: ${lb.status}", size = 12.sp, color = if (lb.error != null) C.blood else C.amber)
        }
        lb.error?.let { TText("  $it", size = 11.sp, color = C.blood) }
        if (lb.players.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Panel(Th.say("SEATED", "At the table") + " (${lb.players.size})") {
                for (p in lb.players) {
                    val r = ratingLine(p.elo, p.streak)
                    TText(p.name + (if (p.id == g.myId) "  (you)" else "") + (if (r.isNotEmpty()) "  ·  $r" else ""), size = 13.sp,
                        weight = if (p.id == g.myId) FontWeight.Bold else FontWeight.Normal, color = if (p.id == g.myId) C.hot else C.amber)
                }
                Spacer(Modifier.height(4.dp))
                TText("rules: ${gs.turnMs / 1000}s per turn · " +
                    (if (gs.topics.isEmpty()) "all topics" else gs.topics.joinToString(" ") { "#" + app.coredump.core.Topics.label(it) }),
                    size = 11.sp, color = C.amber)
                TText("the game starts when the host presses start.", size = 11.sp, color = C.dim)
            }
        }
    }
    val friends = model.settings.friends
    if (friends.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Panel(Th.say("FRIENDS", "Join a friend's party")) {
            for (f in friends.sortedBy { it.name.lowercase() }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TText(f.name, Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold)
                    TLink("join", { model.joinParty(f.code, f.name) }, color = C.hot)
                }
            }
            TText("they must be on their HOST PARTY screen.", size = 10.sp, color = C.dim)
        }
    }
}

// ============================================================================ ARENA

@Composable
fun MeltScreen(model: AppModel, session: MeltSession) {
    val st by session.state.collectAsState()
    val myId = session.myId
    val me = st.player(myId)
    val alive = me != null && !me.out
    val myTurn = st.phase == MeltPhase.TURN && st.holder == myId && alive
    val haptic = rememberHaptic()
    val fx = model.fx
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    /** Typed ahead and sent with enter while waiting: goes out when my turn starts. */
    var queued by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    val nudge = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }

    // my own attempts: clear on a hit, say why on a miss (with "did you mean")
    LaunchedEffect(session) {
        session.verdicts.collect { v ->
            when (v.kind) {
                "ok" -> { input = ""; note = ""; suggestions = emptyList() }
                "check" -> { note = "checking “${v.text}” online…"; suggestions = emptyList() }
                "miss" -> {
                    note = "“${v.text}” is not on the list" + if (v.suggestions.isNotEmpty()) " · did you mean:" else ""
                    suggestions = v.suggestions
                    nudge.snapTo(1f); nudge.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 900f))
                }
                "dup" -> { note = "already said this round"; suggestions = emptyList(); nudge.snapTo(1f); nudge.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 900f)) }
                else -> { note = "not your turn yet" }
            }
            Unit
        }
    }
    // my turn: a nudge, and whatever I typed ahead goes out at once
    LaunchedEffect(myTurn) {
        if (myTurn) {
            haptic(true)
            Sfx.play(Cue.CONFIRM)
            val q = queued.trim()
            if (q.isNotEmpty()) { queued = ""; Sfx.play(Cue.SUBMIT); session.submit(q) }
        } else {
            note = ""
            suggestions = emptyList()
        }
        Unit
    }
    // sounds and screen effects for every event
    LaunchedEffect(st.evn) {
        if (st.evn == 0) return@LaunchedEffect
        val mine = st.eventBy == myId
        when (st.eventKind) {
            "ok" -> { val t = st.eventTier; if (t != null) Sfx.hit(t) else Sfx.play(Cue.CHECK) }
            "heart" -> { Sfx.play(Cue.RANK); fx.flash(C.hot.copy(alpha = 0.35f)) }
            "miss" -> { if (mine) { Sfx.play(Cue.MISS); haptic(false) } }
            "dup" -> { if (mine) { Sfx.play(Cue.DISCARD); haptic(false) } }
            "blast" -> {
                Sfx.play(Cue.TIMEOUT)
                fx.flash(C.blood)
                fx.shake(if (mine) 1.6f else 0.8f)
                haptic(true)
                if (mine) { delay(130); haptic(true); delay(130); haptic(true) }
            }
            "out" -> { Sfx.play(Cue.SKIP) }
            "win" -> { Sfx.play(Cue.RANK); if (st.winner == myId) fx.flash(C.hot.copy(alpha = 0.5f)) }
            else -> {}
        }
        Unit
    }
    // a tick each second of the countdown; the last three louder (and a buzz when it's mine)
    val secs = st.secondsLeft
    LaunchedEffect(secs, st.holder, st.phase) {
        if (st.phase != MeltPhase.TURN) return@LaunchedEffect
        Sfx.play(Cue.TICK, if (secs <= 3) 2 else 0)
        if (secs <= 3 && myTurn) haptic(false)
    }
    LaunchedEffect(st.phase, st.countdown) { if (st.phase == MeltPhase.COUNTDOWN) Sfx.play(Cue.KEY) }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (myTurn) {
            Sfx.play(Cue.SUBMIT)
            session.submit(t)
        } else if (alive && st.phase != MeltPhase.OVER) {
            // typed ahead: it goes out the moment the core reaches me
            queued = t
            input = ""
            Sfx.play(Cue.KEY)
        }
    }

    val lost = (session as? PartyGuest)?.lobby?.collectAsState()?.value?.error

    Box(Modifier.fillMaxSize()) {
        Frame(model, Th.say("meltdown --round ${st.round}", if (st.round > 0) "Meltdown · round ${st.round}" else "Meltdown")) {
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (st.phase == MeltPhase.OVER) MeltResults(st, myId, Modifier.fillMaxSize())
                    else CoreRing(st, myId, Modifier.fillMaxSize())
                    if (st.phase == MeltPhase.COUNTDOWN) Countdown(st.countdown, Modifier.align(Alignment.Center))
                    // seconds left, on the core
                    if (st.phase == MeltPhase.TURN) {
                        TText("$secs", Modifier.align(Alignment.Center), size = 34.sp, weight = FontWeight.Bold,
                            color = if (secs <= 3) C.white else C.bg, align = TextAlign.Center)
                    }
                }
                if (st.phase != MeltPhase.OVER) {
                    EventLine(st)
                    Spacer(Modifier.height(4.dp))
                    UsedRow(st)
                    Spacer(Modifier.height(8.dp))
                    // the category, right above where the answer is typed
                    if (st.prompt.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().box(Th.style, if (myTurn) C.hot else C.line, C.panel).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TText("#${app.coredump.core.Topics.label(st.topic)}", Modifier.weight(1f), size = 10.sp, color = C.dim)
                                TText("${st.size} on the list · ${st.used.size} said", size = 10.sp, color = C.dim)
                            }
                            TText(st.prompt, size = 18.sp, weight = FontWeight.Bold, color = C.amber, maxLines = 3)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                when {
                    lost != null && st.phase != MeltPhase.OVER -> {
                        TText(lost, size = 13.sp, color = C.blood, weight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        TButton(Th.say("EXIT", "Leave"), { model.leaveMelt(session) }, Modifier.fillMaxWidth(), color = C.blood)
                    }
                    st.phase == MeltPhase.OVER -> {
                        Row(Modifier.fillMaxWidth()) {
                            if (!session.online) {
                                TButton(Th.say("REBOOT", "Again"), { model.meltAgain(session) }, Modifier.weight(1f), primary = true)
                                Spacer(Modifier.width(10.dp))
                            }
                            TButton(Th.say("EXIT", "Done"), { model.leaveMelt(session) }, Modifier.weight(1f), primary = session.online)
                        }
                    }
                    me != null && me.out -> {
                        TText("you're out${if (me.place > 0) " · place ${me.place}" else ""}. watching the rest…", size = 13.sp, color = C.dim)
                    }
                    else -> {
                        // the answer field is always there: type ahead while you wait
                        if (myTurn) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                        Row(Modifier.fillMaxWidth().graphicsLayer { translationX = nudge.value * 18f * sin(nudge.value * 20f) },
                            verticalAlignment = Alignment.CenterVertically) {
                            TermInput(input, { input = it.take(80) }, Modifier.weight(1f), prompt = "›",
                                placeholder = if (myTurn) "name one, fast" else "type ahead…",
                                size = 17.sp, onSubmit = { send(input) }, fieldModifier = Modifier.focusRequester(focus))
                            if (input.isNotEmpty() || queued.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                // one tap clears what was typed (and what was queued)
                                TButton("×", { input = ""; queued = ""; suggestions = emptyList() }, color = C.dim)
                            }
                            Spacer(Modifier.width(6.dp))
                            TButton(if (myTurn) Th.say("PASS", "Go") else Th.say("QUEUE", "Ready"), { send(input) },
                                enabled = input.isNotBlank(), primary = myTurn)
                        }
                        if (suggestions.isNotEmpty() && myTurn) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                                for (sg in suggestions) {
                                    app.coredump.ui.ChipBox(sg, false, Modifier.padding(end = 6.dp), size = 12.sp) { suggestions = emptyList(); send(sg) }
                                }
                            }
                        }
                        val holder = st.player(st.holder)
                        val next = nextAlive(st, st.holder)
                        TText(
                            when {
                                note.isNotEmpty() -> note
                                myTurn -> "you hold ${coreWord()}! ${secs}s — answer to pass it on."
                                queued.isNotEmpty() -> "ready: “$queued” goes out when it's your turn"
                                st.phase == MeltPhase.TURN -> "${holder?.name ?: "?"} holds ${coreWord()}" + if (next == myId) " · you're next" else ""
                                else -> " "
                            },
                            size = 12.sp,
                            color = when { note.isNotEmpty() && !note.startsWith("checking") -> C.blood; myTurn || next == myId || queued.isNotEmpty() -> C.hot; else -> C.amber },
                            weight = FontWeight.Bold, maxLines = 2,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (model.confirmLeaveMelt) MeltConfirm(session.online && session !is PartyGuest, { model.confirmLeaveMelt = false }) { model.leaveMelt(session) }
    }
}

private fun nextAlive(st: MeltState, id: String): String? {
    val ps = st.players
    val i = ps.indexOfFirst { it.id == id }
    if (i < 0) return null
    for (k in 1..ps.size) {
        val p = ps[(i + k).mod(ps.size)]
        if (!p.out && p.id != id) return p.id
    }
    return null
}

@Composable
private fun Countdown(n: Int, modifier: Modifier) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(n) { pop.snapTo(0f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
    TText("$n", modifier.graphicsLayer { val s = 0.4f + 0.6f * pop.value; scaleX = s; scaleY = s; alpha = pop.value.coerceIn(0f, 1f) },
        size = 96.sp, weight = FontWeight.Bold, color = C.hot, align = TextAlign.Center)
}

@Composable
private fun EventLine(st: MeltState) {
    val color = when (st.eventKind) {
        "blast", "out" -> C.blood
        "heart", "win" -> C.hot
        "miss", "dup" -> C.dim
        else -> st.eventTier?.let { tierColor(it) } ?: C.amber
    }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(st.evn) { if (st.evn > 0) { pop.snapTo(1.18f); pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) } }
    Row(Modifier.fillMaxWidth().graphicsLayer { scaleX = pop.value; scaleY = pop.value }, verticalAlignment = Alignment.CenterVertically) {
        TText(st.event.ifEmpty { " " }, Modifier.weight(1f), size = 15.sp, weight = FontWeight.Bold, color = color, maxLines = 2)
        val t = st.eventTier
        if (t != null && (st.eventKind == "ok" || st.eventKind == "heart")) { TierBadge(t, 11.sp, animate = false) }
    }
}

/** Answers said this round, newest first, in their tier colours. */
@Composable
private fun UsedRow(st: MeltState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if (st.used.isEmpty()) TText("nothing said yet", size = 11.sp, color = C.line)
        for (u in st.used.take(30)) {
            val c = tierColor(u.tier)
            TText(u.name, Modifier.padding(end = 6.dp).box(Th.style, c.copy(alpha = 0.7f), c.copy(alpha = 0.12f), pill = Th.style == Style.CHAT || Th.style == Style.CLEAN)
                .padding(horizontal = 8.dp, vertical = 4.dp), size = 11.sp, color = c, maxLines = 1)
        }
    }
}

/**
 * The core in the middle (swells, glows, pulses faster and cracks as it heats), the players on an ellipse round it,
 * a beam to whoever holds it, and the blast.
 */
@Composable
private fun CoreRing(st: MeltState, myId: String, modifier: Modifier) {
    val still = LocalLook.current.reduceMotion
    val clock = rememberClock(!still)
    val heat by animateFloatAsState(st.heat, tween(420))
    val boom = remember { Animatable(0f) }
    LaunchedEffect(st.evn) { if (st.eventKind == "blast") { boom.snapTo(1f); boom.animateTo(0f, tween(1500)) } }
    val pass = remember { Animatable(1f) }
    LaunchedEffect(st.holder) { pass.snapTo(0f); pass.animateTo(1f, tween(380)) }
    // chip centres, written by the layout, read when drawing (the layout runs first)
    val centres = remember { FloatArray(Meltdown.MAX_PLAYERS * 4) }
    val holderIndex = st.players.indexOfFirst { it.id == st.holder }
    val active = st.phase == MeltPhase.TURN || st.phase == MeltPhase.BLAST
    val style = Th.style
    val cold = C.amber
    val hot = C.blood
    val white = C.white
    val lineC = C.line
    val bg = C.bg
    val round = st.round

    Layout(
        content = {
            Canvas(Modifier) {
                val t = clock.value
                val c = Offset(size.width / 2f, size.height / 2f)
                val base = min(size.width, size.height) * 0.16f
                val freq = 0.7f + 3.6f * heat
                val pulse = if (still) 0f else sin(t * freq * 2f * PI.toFloat()) * (0.03f + 0.05f * heat)
                val r = base * (0.82f + 0.5f * heat) * (1f + pulse)
                val core = lerp(cold, hot, heat.coerceIn(0f, 1f))
                // orbit
                drawOval(lineC.copy(alpha = 0.45f), Offset(size.width * 0.16f, size.height * 0.14f),
                    Size(size.width * 0.68f, size.height * 0.72f),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 9f), if (still) 0f else -t * 18f)))
                // beam to the holder
                if (active && holderIndex in 0 until st.players.size) {
                    val target = Offset(centres[holderIndex * 4], centres[holderIndex * 4 + 1])
                    val len = (target - c).getDistance().coerceAtLeast(1f)
                    val dir = (target - c) / len
                    // stop at the edge of the holder's box, not under its text
                    val hw = centres[holderIndex * 4 + 2]
                    val hh = centres[holderIndex * 4 + 3]
                    val back = min(if (kotlin.math.abs(dir.x) > 0.001f) hw / kotlin.math.abs(dir.x) else Float.MAX_VALUE,
                        if (kotlin.math.abs(dir.y) > 0.001f) hh / kotlin.math.abs(dir.y) else Float.MAX_VALUE)
                    val from = c + dir * r
                    val stop = target - dir * back.coerceAtMost(len - r)
                    val end = from + (stop - from) * pass.value
                    drawLine(core.copy(alpha = 0.35f), from, end, 7.dp.toPx(), StrokeCap.Round)
                    drawLine(core, from, end, 2.dp.toPx(), StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), if (still) 0f else -t * 60f))
                    if (!still) {
                        for (k in 0 until 3) {
                            val f = ((t * (0.8f + heat) + k / 3f) % 1f)
                            drawCircle(white.copy(alpha = 0.8f * (1f - f)), 2.5.dp.toPx(), from + (end - from) * f)
                        }
                    }
                }
                // glow
                drawCircle(core.copy(alpha = 0.10f + 0.12f * heat), r * 2.0f, c)
                drawCircle(core.copy(alpha = 0.18f + 0.15f * heat), r * 1.45f, c)
                // the core, each look its own way
                when (style) {
                    Style.PIXEL, Style.DESKTOP -> {
                        val cell = (r / 4f).coerceAtLeast(2f)
                        var y = -r
                        while (y <= r) {
                            var x = -r
                            while (x <= r) {
                                if (x * x + y * y <= r * r) {
                                    val k = (x * x + y * y) / (r * r)
                                    drawRect(lerp(white, core, k.coerceIn(0f, 1f) * 0.5f + 0.5f), Offset(c.x + x - cell / 2f, c.y + y - cell / 2f), Size(cell, cell))
                                }
                                x += cell
                            }
                            y += cell
                        }
                    }
                    Style.COMIC, Style.JACKPOT -> {
                        starburst(c, r * 1.25f, core)
                        drawCircle(Brush.radialGradient(listOf(white, core), c, r), r * 0.85f, c)
                    }
                    Style.ORACLE -> {
                        drawCircle(Brush.radialGradient(listOf(white, core, core.copy(alpha = 0f)), c, r * 1.3f), r * 1.3f, c)
                        val spikes = 8
                        val star = Path()
                        for (i in 0 until spikes * 2) {
                            val a = i * PI.toFloat() / spikes + (if (still) 0f else t * 0.4f)
                            val rr = if (i % 2 == 0) r * 1.15f else r * 0.45f
                            val p = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
                            if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
                        }
                        star.close()
                        drawPath(star, white.copy(alpha = 0.85f))
                    }
                    else -> {
                        drawCircle(Brush.radialGradient(listOf(white, core, core.copy(alpha = 0.75f)), c, r), r, c)
                        drawCircle(white.copy(alpha = 0.5f), r, c, style = Stroke(1.5.dp.toPx()))
                    }
                }
                // cracks once it runs hot
                if (heat > 0.45f) {
                    val n = ((heat - 0.45f) * 18f).toInt().coerceIn(1, 10)
                    val rnd = Random(round * 31 + 7)
                    for (i in 0 until n) {
                        val a = rnd.nextFloat() * 2f * PI.toFloat()
                        var p = c
                        val crack = Path().apply { moveTo(p.x, p.y) }
                        val steps = 4
                        for (k in 1..steps) {
                            val d = r * 0.95f * k / steps
                            val j = (rnd.nextFloat() - 0.5f) * 0.5f
                            p = Offset(c.x + cos(a + j) * d, c.y + sin(a + j) * d)
                            crack.lineTo(p.x, p.y)
                        }
                        drawPath(crack, bg.copy(alpha = 0.7f), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                // the blast
                val b = boom.value
                if (b > 0f) {
                    val grow = 1f - b
                    drawCircle(white.copy(alpha = b * 0.9f), r * (1f + grow * 1.6f), c)
                    drawCircle(hot.copy(alpha = b), r + grow * size.maxDimension * 0.7f, c, style = Stroke((10f * b + 1f).dp.toPx()))
                    drawCircle(cold.copy(alpha = b * 0.7f), r + grow * size.maxDimension * 0.45f, c, style = Stroke((5f * b + 1f).dp.toPx()))
                    if (style == Style.COMIC || style == Style.JACKPOT) starburst(c, r * (1.2f + grow * 2f), hot.copy(alpha = b))
                }
            }
            for (p in st.players) {
                PlayerChip(p, holder = active && p.id == st.holder, me = p.id == myId, winner = st.winner == p.id)
            }
        },
        modifier = modifier,
    ) { ms, cons ->
        val w = cons.maxWidth
        val h = if (cons.maxHeight == Constraints.Infinity) w else cons.maxHeight
        val canvas = ms[0].measure(Constraints.fixed(w, h))
        val cnt = ms.size - 1
        val cw = (w * (if (cnt > 6) 0.25f else 0.28f)).roundToInt()
        val chips = ms.drop(1).map { it.measure(Constraints(minWidth = cw, maxWidth = cw, maxHeight = h)) }
        layout(w, h) {
            canvas.place(0, 0)
            val offset = if (cnt >= 6) PI / cnt else 0.0
            chips.forEachIndexed { i, pl ->
                val a = -PI / 2 + offset + 2 * PI * i / cnt.coerceAtLeast(1)
                val rx = w / 2f - pl.width / 2f - 2f
                val ry = h / 2f - pl.height / 2f - 2f
                val cx = w / 2f + cos(a).toFloat() * rx
                val cy = h / 2f + sin(a).toFloat() * ry
                if (i < Meltdown.MAX_PLAYERS) {
                    centres[i * 4] = cx; centres[i * 4 + 1] = cy
                    centres[i * 4 + 2] = pl.width / 2f; centres[i * 4 + 3] = pl.height / 2f
                }
                pl.place((cx - pl.width / 2f).roundToInt(), (cy - pl.height / 2f).roundToInt())
            }
        }
    }
}

@Composable
private fun PlayerChip(p: MeltPlayer, holder: Boolean, me: Boolean, winner: Boolean) {
    val loop = rememberInfiniteTransition()
    val beat by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse))
    val still = LocalLook.current.reduceMotion
    val stroke = when { winner -> C.hot; holder -> C.blood; me -> C.hot; else -> C.line }
    // opaque, so the beam and the orbit never show through the text
    val fill = when { holder -> C.blood.copy(alpha = 0.18f); me -> C.hot.copy(alpha = 0.1f); else -> C.panel }.compositeOver(C.bg.copy(alpha = 1f))
    val pill = Th.style == Style.CHAT || Th.style == Style.CLEAN || Th.style == Style.NEON || Th.style == Style.ABYSS
    Column(
        Modifier.graphicsLayer {
            val s = if (holder && !still) 1f + 0.06f * beat else 1f
            scaleX = s; scaleY = s
            alpha = if (p.out) 0.42f else 1f
        }.box(Th.style, stroke, fill, strokeWidth = if (holder || winner) 2.dp else 1.dp, pill = pill)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TText(p.name, size = 11.sp, weight = FontWeight.Bold, color = if (me) C.hot else C.amber, maxLines = 1, align = TextAlign.Center)
        val rating = ratingLine(p.elo, p.streak)
        if (rating.isNotEmpty()) TText(rating, size = 8.sp, color = C.dim, maxLines = 1)
        val full = p.hearts.coerceAtLeast(0)
        TText("♥".repeat(full) + "♡".repeat((Meltdown.START_HEARTS - full).coerceAtLeast(0)), size = 11.sp,
            color = if (p.out) C.dim else C.blood, maxLines = 1)
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp).background(C.line.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth((p.charge.toFloat() / Meltdown.CHARGE).coerceIn(0f, 1f)).fillMaxHeight().background(C.hot))
        }
        TText(
            when {
                winner -> "WINNER"
                p.out -> if (p.place > 0) "out · #${p.place}" else "out"
                me -> "you"
                p.bot -> "bot"
                else -> " "
            },
            size = 9.sp, color = if (winner) C.hot else C.dim, maxLines = 1,
        )
    }
}

@Composable
private fun MeltResults(st: MeltState, myId: String, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        val w = st.player(st.winner)
        TText(
            when {
                w == null -> "nobody survived"
                w.id == myId -> Th.say("you survived the meltdown.", "You win!")
                else -> "${w.name} wins"
            },
            Modifier.fillMaxWidth(), size = 28.sp, weight = FontWeight.Bold, color = C.hot, align = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Panel(Th.say("FINAL STANDINGS", "Standings")) {
            for (p in st.players.sortedBy { if (it.place == 0) 99 else it.place }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    TText(if (p.place > 0) "#${p.place}" else "–", Modifier.width(34.dp), size = 14.sp, weight = FontWeight.Bold,
                        color = if (p.place == 1) C.hot else C.dim)
                    TText(p.name + if (p.id == myId) "  (you)" else "", Modifier.weight(1f), size = 14.sp,
                        weight = if (p.id == myId) FontWeight.Bold else FontWeight.Normal, color = if (p.id == myId) C.hot else C.amber)
                    TText("${p.answers} answers", size = 11.sp, color = C.dim)
                }
            }
        }
    }
}

@Composable
private fun MeltConfirm(hosting: Boolean, onKeep: () -> Unit, onLeave: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
        .clickable(remember { MutableInteractionSource() }, null) { }, contentAlignment = Alignment.Center) {
        ModalCard(Th.say("CONFIRM", "Leave Meltdown?"), C.blood, Modifier.padding(28.dp)) {
            TText(Th.say("abandon the core?", "Leave this game?"), size = 18.sp, weight = FontWeight.Bold, color = C.blood)
            Spacer(Modifier.height(6.dp))
            TText(if (hosting) "you are the host: the party ends for everyone." else "you will be out of this game.", size = 12.sp, color = C.dim)
            Spacer(Modifier.height(18.dp))
            TButton(Th.say("STAY", "Keep playing"), onKeep, Modifier.fillMaxWidth(), primary = true)
            Spacer(Modifier.height(10.dp))
            TButton(Th.say("ABORT", "Leave"), onLeave, Modifier.fillMaxWidth(), color = C.blood)
        }
    }
}
