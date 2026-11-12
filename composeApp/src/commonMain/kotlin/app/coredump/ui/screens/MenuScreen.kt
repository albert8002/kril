package app.coredump.ui.screens

import androidx.compose.foundation.layout.Box
import app.coredump.ui.Cue
import app.coredump.ui.Sfx
import app.coredump.ui.hyperText
import app.coredump.ui.hyperFrame
import app.coredump.ui.press
import app.coredump.ui.breathe
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.Screen
import app.coredump.core.SourceKind
import app.coredump.dailyNumber
import app.coredump.data.History
import app.coredump.data.SyncState
import app.coredump.platform.Platform
import app.coredump.ui.C
import app.coredump.ui.Cursor
import app.coredump.ui.DecodeText
import app.coredump.ui.Divider
import app.coredump.ui.Spinner
import app.coredump.ui.Style
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.Typewriter
import app.coredump.ui.box
import app.coredump.ui.group
import app.coredump.ui.onColor
import app.coredump.ui.rememberHaptic
import kotlinx.coroutines.delay

private val whispers = listOf(
    "do not trust the cache. it remembers you.",
    "pid 666 has been running since before boot.",
    "the garbage collector is coming. be obscure.",
    "someone else is reading this heap.",
    "every obvious answer feeds it.",
    "there is no undo in /dev/null.",
    "kill -9 does not work on it.",
)

/** One main-menu destination. [key] lets each archetype pick its own words and icon. */
class Item(val key: String, val label: String, val cmd: String, val hint: String, val badge: String?, val action: () -> Unit)

fun menuItems(model: AppModel): List<Item> {
    val today = dailyNumber(Platform.epochMillis())
    val playedToday = History.playedDaily(today)
    val style = Th.style
    return listOf(
        Item("daily", if (playedToday != null) "DAILY #$today ✓" else "DAILY RUN #$today", "cron @daily",
            if (playedToday != null) "Played today · ${playedToday.points} pts" else "Same 7 prompts for everyone today",
            if (playedToday != null) "${playedToday.points}" else "NEW") { model.startDaily() },
        Item("solo", "RUN SOLO", "./run --solo", "Pick topics, time and difficulty", null) { model.go(Screen.Setup(forHost = false)) },
        Item("duel", "LINK 1v1", "./run --peer", "Friends, online or Wi-Fi · match history", null) { model.go(Screen.Lobby) },
        Item("party", "MELTDOWN", "./run --royale", "Battle royale · 2–8 players · bots or friends", "NEW") { model.go(Screen.Meltdown) },
        Item("friends", "FRIENDS", "./friends",
            model.settings.requests.size.let { if (it > 0) "$it friend request${if (it > 1) "s" else ""} waiting" else "Codes, requests, ratings and streaks" },
            model.settings.requests.size.let { if (it > 0) "$it" else null }) { model.go(Screen.Friends) },
        Item("stats", "STATS", "cat /proc/self", "Runs, tiers, streaks", null) { model.go(Screen.Stats) },
        Item("browse", "BROWSE LISTS", "ls -la /var/lists", "Every list and its answers", null) { model.go(Screen.Browse) },
        Item("theme", "THEME", "chtheme", "${style.label.lowercase().replaceFirstChar { it.uppercase() }} · ${Th.pal.name.lowercase()}", null) { model.go(Screen.Appearance) },
        Item("settings", "SETTINGS", "vim ~/.coredumprc", "Sources, defaults, crash reports", null) { model.go(Screen.Config) },
        Item("manual", "MANUAL", "man coredump", "How scoring works", null) { model.go(Screen.Manual) },
    )
}

@Composable
fun MenuScreen(model: AppModel) {
    Box(Modifier.fillMaxSize()) {
        when (Th.style) {
            Style.TERMINAL -> TerminalMenu(model)
            Style.NEON -> ArcadeMenu(model)
            Style.PAPER -> GazetteMenu(model)
            Style.CLEAN -> QuizMenu(model)
            Style.PIXEL -> QuestMenu(model)
            Style.COMIC -> ComicMenu(model)
            Style.ABYSS -> AbyssMenu(model)
            Style.METRO -> MetroMenu(model)
            Style.JACKPOT -> JackpotMenu(model)
            Style.CHAT -> ChatMenu(model)
            Style.DESKTOP -> DesktopMenu(model)
            Style.ORACLE -> OracleMenu(model)
        }
        HyperButton(model, Modifier.align(Alignment.BottomEnd)
            .padding(end = 14.dp, bottom = if (Th.style == Style.DESKTOP) 52.dp else 14.dp))
    }
}

/**
 * ADHD-fy switch for the active look (every look's menu has one, bottom right): more motion, more colour,
 * flourishes everywhere. Each look remembers its own setting.
 */
@Composable
fun HyperButton(model: AppModel, modifier: Modifier = Modifier) {
    val style = Th.style
    val on = style.name in model.settings.hyper
    val haptic = rememberHaptic()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val pill = style == Style.NEON || style == Style.CLEAN || style == Style.ABYSS || style == Style.CHAT
    TText(
        if (on) "ADHD-FY: ON" else "ADHD-FY",
        modifier
            .breathe(true, 3)
            .press(pressed)
            .hyperFrame(5, pill = pill)
            .box(style, if (on) C.hot else C.line, if (on) C.hot.copy(alpha = 0.18f) else C.bg.copy(alpha = 0.85f), pill = pill)
            .clickable(src, null) {
                haptic(true)
                Sfx.play(if (on) Cue.SKIP else Cue.CONFIRM)
                model.update { it.copy(hyper = if (on) it.hyper - style.name else it.hyper + style.name) }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .hyperText(9),
        size = 11.sp, color = if (on) C.hot else C.dim, weight = FontWeight.Bold, spacing = 1.sp,
    )
}

/** Crash notice from the previous session (any archetype). */
@Composable
fun CrashBanner(model: AppModel) {
    if (model.lastCrash == null) return
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().box(Th.style, C.blood, C.bloodDim.copy(alpha = 0.35f)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        TText(Th.say("kernel panic last session. report saved.", "The app crashed last time. A report was saved."),
            size = 12.sp, color = C.blood, weight = FontWeight.Bold)
        TLink(Th.say("view report in config", "View report in Settings"), { model.go(Screen.Config) }, color = C.blood)
    }
}

/** List counts, collection progress, "fetch more", source rows. */
@Composable
fun LibraryFooter(model: AppModel, sources: Boolean = true) {
    val bank by model.bank.state.collectAsState()
    TText(
        Th.say("${group(bank.prompts)} prompts · ${group(bank.answers)} answers hashed", "${group(bank.prompts)} lists · ${group(bank.answers)} answers"),
        size = 12.sp, color = C.amber,
    )
    Spacer(Modifier.height(6.dp))
    val col = bank.collect
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (col.running) {
            Spinner(C.amber, 12.sp)
            TText("  ${Th.say("collecting", "Collecting new lists")} ${col.done}/${col.total}", size = 11.sp, color = C.amber)
        } else {
            TText(
                (if (col.library > 0) "+${col.library} ${Th.say("collected", "collected lists")}" else Th.say("no lists collected yet", "No collected lists yet")) +
                    (if (col.message.isNotEmpty()) " · ${col.message}" else ""),
                Modifier.weight(1f), size = 11.sp, color = C.dim, maxLines = 2,
            )
        }
    }
    if (col.running) FetchStatusLine(model)
    Spacer(Modifier.height(10.dp))
    TButton(if (col.running) Th.say("FETCH STATUS", "Fetch status") else Th.say("FETCH MORE LISTS", "Fetch more lists"),
        { model.go(Screen.Fetch) }, Modifier.fillMaxWidth(), textSize = 12.sp)
    // room to scroll the end of the menu above the floating ADHD-fy button
    Spacer(Modifier.height(44.dp))
    if (!sources) return
    Spacer(Modifier.height(10.dp))
    for (k in SourceKind.entries) {
        val st = bank.sources.getValue(k)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
            TText(k.tag, Modifier.width(86.dp), size = 11.sp, color = C.dim)
            when (st.state) {
                SyncState.SYNCING -> { Spinner(C.amber, 11.sp); TText(" ${st.done}/${st.total}", size = 11.sp, color = C.amber) }
                SyncState.OK -> TText("✓ ${st.count}", size = 11.sp, color = C.amber)
                SyncState.ERROR -> TText("✗ ${st.count} kept", size = 11.sp, color = C.blood)
                SyncState.OFF -> TText("off", size = 11.sp, color = C.line)
                SyncState.IDLE -> TText("· ${st.count}", size = 11.sp, color = C.dim)
            }
            if (st.detail.isNotEmpty()) TText("  ${st.detail}", size = 10.sp,
                color = if (st.state == SyncState.ERROR) C.blood else C.line, maxLines = 1)
        }
    }
}

// ======================================================================= TERMINAL

@Composable
private fun TerminalMenu(model: AppModel) {
    val haptic = rememberHaptic()
    val style = Th.style

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(if (style == Style.CLEAN) 36.dp else 28.dp))
        if (style == Style.TERMINAL) TText("/usr/bin/", size = 12.sp, color = C.dim)
        AppTitle(40.sp)
        Spacer(Modifier.height(4.dp))
        Typewriter(
            Th.say("the obvious get garbage-collected.", "Name the rare answer. The obvious ones barely score."),
            size = 13.sp, color = C.dim, weight = FontWeight.Normal, charMs = 26,
        )
        Spacer(Modifier.height(10.dp))
        TText(
            if (History.games == 0) Th.say("no dumps on record.", "No runs yet.")
            else Th.say("best run: ${History.best} pts · ${History.games} runs", "Best run ${History.best} pts · ${History.games} runs played"),
            size = 11.sp, color = C.dim,
        )
        CrashBanner(model)
        Spacer(Modifier.height(22.dp))
        menuItems(model).forEachIndexed { i, it -> MenuItem(i, it) { haptic(true); it.action() } }

        Spacer(Modifier.height(22.dp))
        Divider()
        Spacer(Modifier.height(10.dp))
        LibraryFooter(model)
        Spacer(Modifier.height(18.dp))
        if (style == Style.TERMINAL) {
            var w by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(7000); w = (w + 1) % whispers.size } }
            DecodeText(whispers[w], size = 11.sp, color = C.bloodDim, weight = FontWeight.Normal, glow = 0.6f, durationMs = 900)
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun MenuItem(index: Int, item: Item, onClick: () -> Unit) {
    val style = Th.style
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(60L * index + 100); enter.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    val anim = Modifier.graphicsLayer { alpha = enter.value; translationX = (1f - enter.value) * -40f }

    Row(
        Modifier.fillMaxWidth().then(anim)
            .background(if (pressed) C.amber else Color.Transparent)
            .clickable(src, null, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (pressed) C.bg else C.amber
        TText(if (pressed) "▶" else ">", Modifier.width(20.dp), size = 16.sp, color = fg, weight = FontWeight.Bold)
        TText("[${index + 1}] ", size = 13.sp, color = if (pressed) C.bg else C.dim)
        TText(item.label, Modifier.weight(1f), size = 17.sp, color = fg, weight = FontWeight.Bold, glow = if (pressed) 0f else 1f)
        TText(item.cmd, size = 10.sp, color = if (pressed) C.bg else C.line, maxLines = 1)
        if (pressed) Cursor(12.sp, C.bg)
    }
}


