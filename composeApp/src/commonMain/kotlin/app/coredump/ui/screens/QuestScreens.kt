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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.data.History
import app.coredump.game.AttemptStatus
import app.coredump.game.MatchSession
import app.coredump.game.MatchState
import app.coredump.game.Phase
import app.coredump.platform.shareText
import app.coredump.ui.C
import app.coredump.ui.RankBadge
import app.coredump.ui.TierBadge
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.Spinner
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.Typewriter
import app.coredump.ui.box
import app.coredump.ui.group
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import kotlinx.coroutines.delay
import kotlin.math.ceil

// QUEST: a handheld RPG. Command window with a ▶ cursor, a status window with LV / EXP, each prompt is a
// boss turn ("GARBAGE COLLECTOR" with an HP bar), answers are attacks with damage numbers, results level you up.

/** Boss HP per turn (points). Beating the boss needs an average of 45 points per prompt. */
const val BOSS_HP_PER_TURN = 45

/** Segmented bar, [cells] blocks, [fraction] 0..1 of them filled. */
@Composable
fun PixelBar(fraction: Float, modifier: Modifier = Modifier, cells: Int = 20, color: Color = C.amber) {
    val track = C.ghost
    val edge = C.line
    Canvas(modifier.height(12.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (cells - 1)) / cells
        val n = ceil(fraction.coerceIn(0f, 1f) * cells).toInt()
        for (i in 0 until cells) {
            val x = i * (w + gap)
            drawRect(if (i < n) color else track, Offset(x, 0f), Size(w, size.height))
            if (i >= n) drawRect(edge.copy(alpha = 0.4f), Offset(x, size.height - 2f), Size(w, 2f))
        }
    }
}

private val BOSS = listOf(
    "...######...",
    "..########..",
    ".##..##..##.",
    ".##..##..##.",
    "############",
    "############",
    "##.######.##",
    "###......###",
    ".##########.",
    "..#.#..#.#..",
)

/** The boss, drawn from [BOSS] in whole pixels; bobs in two frames, shakes in steps on a hit. */
@Composable
private fun BossSprite(hits: Int, critical: Boolean, modifier: Modifier = Modifier) {
    val motion = !LocalLook.current.reduceMotion
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(motion) { if (motion) while (true) { delay(520); frame = 1 - frame } }
    var shake by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(hits) {
        if (hits == 0 || !motion) return@LaunchedEffect
        for (s in listOf(3, -3, 2, -2, 1, 0)) { shake = s; flash = critical && s != 0 && s % 2 != 0; delay(60) }
        flash = false
    }
    val ink = if (flash) C.ghost else C.amber
    Canvas(modifier.size(72.dp, 60.dp)) {
        val px = minOf(size.width / 12f, size.height / 11f)
        val ox = (size.width - px * 12) / 2f + shake * px
        val oy = frame * px
        for ((y, row) in BOSS.withIndex()) for ((x, ch) in row.withIndex()) {
            if (ch == '#') drawRect(ink, Offset(ox + x * px, oy + y * px), Size(px, px))
        }
    }
}

// ======================================================================= menu

private fun questLabel(key: String, fallback: String) = when (key) {
    "daily" -> "DAILY QUEST"
    "solo" -> "ADVENTURE"
    "duel" -> "DUEL"
    "party" -> "ARENA"
    "stats" -> "RECORDS"
    "browse" -> "BESTIARY"
    "theme" -> "APPEARANCE"
    "settings" -> "CONFIG"
    "manual" -> "GUIDE"
    else -> fallback
}

private val TIPS = listOf(
    "Obvious answers are weak attacks. Rare ones are critical hits.",
    "A miss costs nothing but time. Keep attacking!",
    "The daily quest is the same for every hero today.",
    "Tap a turn in the battle log to see stronger attacks.",
    "Discard a question after a turn and it never returns.",
)

@Composable
fun QuestMenu(model: AppModel) {
    val items = menuItems(model)
    val haptic = rememberHaptic()
    var sel by remember { mutableIntStateOf(0) }
    val blink = rememberBlink(380)
    val total = History.runs.sumOf { it.points }
    val (lv, into, need) = Exp.of(total)
    var tip by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(7000); tip = (tip + 1) % TIPS.size } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AppTitle(40.sp) }
        TText("~ A QUEST FOR RARE ANSWERS ~", Modifier.fillMaxWidth(), size = 11.sp, color = C.dim, align = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Panel("STATUS") {
            Row {
                TText(model.settings.handle.uppercase().take(12), Modifier.weight(1f), size = 16.sp)
                TText("LV ${lv.toString().padStart(2, '0')}", size = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TText("EXP ", size = 12.sp, color = C.dim)
                PixelBar(into / need.toFloat(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row {
                TText("NEXT LV ${group(need - into)}", Modifier.weight(1f), size = 11.sp, color = C.dim)
                TText("BATTLES ${History.games} · BEST ${History.best}", size = 11.sp, color = C.dim)
            }
        }
        CrashBanner(model)
        Spacer(Modifier.height(14.dp))
        Panel("COMMAND") {
            items.forEachIndexed { i, it ->
                Row(
                    Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {
                        sel = i; haptic(true)
                        it.action()
                    }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TText(if (i == sel && blink) "▶" else " ", Modifier.width(24.dp), size = 16.sp)
                    TText(questLabel(it.key, it.label), Modifier.weight(1f), size = 16.sp)
                    it.badge?.let { b -> TText(if (b == "NEW") "!" else "✓", Modifier.box(Th.style, C.amber, C.amber).padding(horizontal = 8.dp, vertical = 2.dp),
                        size = 11.sp, color = C.bg) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        DialogueBox {
            key(tip) { Typewriter(TIPS[tip], size = 13.sp, weight = FontWeight.Normal, charMs = 24, cursor = false) }
        }
        Spacer(Modifier.height(14.dp))
        Panel("SUPPLIES") { LibraryFooter(model, sources = false) }
        Spacer(Modifier.height(28.dp))
    }
}

/** RPG text window with a blinking ▼ in the corner. */
@Composable
fun DialogueBox(modifier: Modifier = Modifier, more: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val blink = rememberBlink(420)
    Box(modifier.fillMaxWidth().box(Th.style, C.amber, C.panel).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), content = content)
        if (more) TText(if (blink) "▼" else " ", Modifier.align(Alignment.BottomEnd), size = 11.sp)
    }
}

// ======================================================================= play

@Composable
fun QuestPlay(model: AppModel, session: MatchSession, st: MatchState, a: AnswerState) {
    val hpMax = (st.total * BOSS_HP_PER_TURN).coerceAtLeast(1)
    val hp = (hpMax - st.myTotal).coerceAtLeast(0)
    val hits = st.rounds.count { it.me.points > 0 }
    val lastCrit = (st.rounds.lastOrNull()?.me?.tier?.heat ?: 0) >= 5
    val name = model.settings.handle.uppercase().take(10)

    Column(Modifier.fillMaxSize()) {
        PlayHeader(model, session, st, title = if (st.daily != null) "DAILY QUEST · TURN ${st.round + 1}/${st.total}" else "BATTLE · TURN ${st.round + 1}/${st.total}")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
            // boss window
            Row(Modifier.fillMaxWidth().box(Th.style, C.amber, C.panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    BossSprite(hits, lastCrit)
                    val r = st.reveal
                    if (st.phase == Phase.REVEAL && r != null && r.me.points > 0) DamageNumber(r.me.points, r.index, Modifier.align(Alignment.TopCenter))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    TText(if (st.daily != null) "DAILY BOSS" else "GARBAGE COLLECTOR", size = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TText("HP ", size = 11.sp, color = C.dim)
                        PixelBar(hp / hpMax.toFloat(), Modifier.weight(1f), cells = 16)
                    }
                    TText("${group(hp)}/${group(hpMax)}", Modifier.fillMaxWidth(), size = 11.sp, color = C.dim, align = TextAlign.End)
                    if (session.isMulti) TText("RIVAL ${st.oppName?.uppercase()?.take(8) ?: "?"}: ${st.oppTotal} DMG", size = 10.sp, color = C.dim, maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))
            TimerBar(st.startedAt, st.seconds, isRunning(st))
            Spacer(Modifier.height(10.dp))
            when (st.phase) {
                Phase.WAITING -> DialogueBox(more = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); TText("  ${st.message ?: "Waiting for the rival…"}", size = 13.sp) }
                }
                Phase.TYPING, Phase.LOCKED -> {
                    DialogueBox {
                        TText("${st.topic.uppercase()} · ${group(st.listSize)} IN THE BESTIARY", size = 10.sp, color = C.dim)
                        Spacer(Modifier.height(6.dp))
                        key(st.round) { Typewriter(st.prompt, size = 18.sp, charMs = 22, cursor = false) }
                        val misses = st.attempts.filter { it.status == AttemptStatus.REJECTED }
                        if (misses.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            TText("$name used ${misses.last().text.uppercase()}! It missed.", size = 12.sp, color = C.dim)
                        }
                        if (st.verifying) Row(verticalAlignment = Alignment.CenterVertically) { Spinner(C.dim, 11.sp); TText("  Checking the scrolls…", size = 11.sp, color = C.dim) }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (st.phase == Phase.LOCKED) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); TText("  Waiting for the rival's move…", size = 13.sp, color = C.dim) }
                    } else if (!st.paused) {
                        TText("ATTACK WITH:", size = 12.sp, color = C.dim)
                        Spacer(Modifier.height(4.dp))
                        AnswerField(a, st, Modifier.fillMaxWidth().shakeX(a))
                        Spacer(Modifier.height(8.dp))
                        SuggestionChip(st, a)
                        Spacer(Modifier.height(10.dp))
                        TButton("ATTACK", { a.send() }, Modifier.fillMaxWidth(), enabled = a.text.isNotBlank(), primary = true, textSize = 16.sp)
                    }
                }
                Phase.REVEAL -> st.reveal?.let { v ->
                    val used = when (v.me.tier) { Tier.TIMEOUT -> "$name hesitated…"; else -> "$name used ${(v.me.canonical ?: v.me.input).uppercase()}!" }
                    DialogueBox {
                        key(v.index) {
                            Typewriter(used + "\n" + tierLine(v.me.tier) + if (v.me.points > 0) "  -${v.me.points} HP" else "",
                                size = 15.sp, color = tierColor(v.me.tier), charMs = 20, cursor = false)
                        }
                        Spacer(Modifier.height(8.dp))
                        TierBadge(v.me.tier, 12.sp)
                    }
                    RevealExtras(v, session.isMulti)
                }
                Phase.ERROR -> DialogueBox(more = false) { TText(st.message ?: "unknown fault", size = 14.sp, color = C.blood) }
                else -> Unit
            }
        }
        PlayActions(model, session, st, Modifier.padding(16.dp))
    }
}

/** "-60" rising in whole steps above the boss. */
@Composable
private fun DamageNumber(points: Int, round: Int, modifier: Modifier) {
    var step by remember(round) { mutableIntStateOf(0) }
    LaunchedEffect(round) { delay(300); for (i in 1..8) { step = i; delay(70) } }
    if (step in 1..7) TText("-$points", modifier.offset(y = (-4 * step).dp), size = 16.sp, color = C.amber, weight = FontWeight.Bold)
}

// ======================================================================= summary

@Composable
fun QuestSummary(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val t = totalsOf(st)
    val hpMax = (t.count * BOSS_HP_PER_TURN).coerceAtLeast(1)
    val won = t.my >= hpMax
    val total = History.runs.sumOf { it.points }
    val before = (total - t.my).coerceAtLeast(0)
    val e = remember { Animatable(before.toFloat()) }
    LaunchedEffect(total) { delay(700); e.animateTo(total.toFloat(), tween(1800, easing = LinearEasing)) }
    val (lvNow, into, need) = Exp.of(e.value.toInt())
    val lvBefore = Exp.of(before).first
    val blink = rememberBlink(300)
    val name = st.myName.uppercase().take(10)

    Frame(model, "BATTLE RESULT", onBack = { model.home() }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(14.dp))
            DialogueBox {
                Typewriter(
                    if (won) "VICTORY!\nThe GARBAGE COLLECTOR was defeated!"
                    else "THE BUG ESCAPED...\nIt fled with ${hpMax - t.my} HP left.",
                    size = 17.sp, charMs = 30, cursor = false,
                )
            }
            val opp = st.oppName
            if (session.isMulti && opp != null) {
                Spacer(Modifier.height(10.dp))
                val o = st.oppTotal
                TText(when { t.my > o -> "$name WINS THE DUEL!"; t.my < o -> "${opp.uppercase()} WINS THE DUEL."; else -> "THE DUEL IS A DRAW." } + "  ${t.my}-$o",
                    size = 14.sp, color = if (t.my >= o) C.amber else C.blood)
            }
            Spacer(Modifier.height(12.dp))
            Panel("EXP") {
                TText("$name gained ${group(t.my)} EXP!", size = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TText("LV ${lvNow.toString().padStart(2, '0')} ", size = 14.sp)
                    PixelBar(into / need.toFloat(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                if (lvNow > lvBefore) TText(if (blink) "LEVEL UP!  LV $lvBefore → $lvNow" else "", Modifier.height(18.dp), size = 14.sp)
                else TText("NEXT LV IN ${group(need - into)} EXP", size = 11.sp, color = C.dim)
                Spacer(Modifier.height(4.dp))
                Spacer(Modifier.height(6.dp))
                TText("TITLE EARNED", size = 11.sp, color = C.dim)
                Spacer(Modifier.height(4.dp))
                RankBadge(t.rank, size = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
            Panel("BATTLE LOG") {
                for (r in st.rounds) QuestLogRow(model, r)
            }
            Spacer(Modifier.height(10.dp))
            TLink("SEND RESULTS", { shareText(shareCard(st, t.rank)) })
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("FIGHT AGAIN", { model.replay(session) }, Modifier.fillMaxWidth(), primary = true)
                Spacer(Modifier.height(10.dp))
            }
            TButton("RETURN TO TOWN", { model.home() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QuestLogRow(model: AppModel, r: RoundView) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open }.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TText(if (open) "▼" else "▶", Modifier.width(18.dp), size = 11.sp)
            TText("T${r.index + 1} ", size = 12.sp, color = C.dim)
            TText(answerText(r.me.tier, r.me.canonical, r.me.input).uppercase(), Modifier.weight(1f), size = 13.sp, maxLines = 1)
            TText(when { r.me.tier == Tier.SKIPPED -> "FLED"; r.me.tier.isValid -> "-${r.me.tier.points}"; else -> "MISS" } +
                if (r.me.penalty > 0) " (−${r.me.penalty})" else "", size = 13.sp)
        }
        TText(r.prompt, Modifier.padding(start = 18.dp), size = 10.sp, color = C.dim, maxLines = 1)
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { RoundDetails(model, r) }
    }
}
