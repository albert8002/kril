package app.coredump.ui.screens

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

private class Item(val label: String, val cmd: String, val hint: String, val badge: String?, val action: () -> Unit)

@Composable
fun MenuScreen(model: AppModel) {
    val bank by model.bank.state.collectAsState()
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
        val crash = model.lastCrash
        if (crash != null) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().box(style, C.blood, C.bloodDim.copy(alpha = 0.35f)).padding(12.dp)) {
                TText(Th.say("kernel panic last session. report saved.", "The app crashed last time. A report was saved."),
                    size = 12.sp, color = C.blood, weight = FontWeight.Bold)
                TLink(Th.say("view report in config", "View report in Settings"), { model.go(Screen.Config) }, color = C.blood)
            }
        }
        Spacer(Modifier.height(22.dp))

        val today = dailyNumber(Platform.epochMillis())
        val playedToday = History.playedDaily(today)
        val items = listOf(
            Item(if (playedToday != null) "DAILY #$today ✓" else "DAILY RUN #$today", "cron @daily",
                if (playedToday != null) "Played today · ${playedToday.points} pts" else "Same 7 prompts for everyone today",
                if (playedToday != null) "${playedToday.points}" else "NEW") { model.startDaily() },
            Item("RUN SOLO", "./run --solo", "Pick topics, time and difficulty", null) { model.go(Screen.Setup(forHost = false)) },
            Item("LINK 1v1", "./run --peer", "Play a friend online or on Wi-Fi", null) { model.go(Screen.Lobby) },
            Item("STATS", "cat /proc/self", "Runs, tiers, streaks", null) { model.go(Screen.Stats) },
            Item("BROWSE LISTS", "ls -la /var/lists", "Every list and its answers", null) { model.go(Screen.Browse) },
            Item("THEME", "chtheme", "${style.label.lowercase().replaceFirstChar { it.uppercase() }} · ${Th.pal.name.lowercase()}", null) { model.go(Screen.Appearance) },
            Item("SETTINGS", "vim ~/.coredumprc", "Sources, defaults, crash reports", null) { model.go(Screen.Config) },
            Item("MANUAL", "man coredump", "How scoring works", null) { model.go(Screen.Manual) },
        )
        items.forEachIndexed { i, it -> MenuItem(i, it) { haptic(true); it.action() } }

        Spacer(Modifier.height(22.dp))
        Divider()
        Spacer(Modifier.height(10.dp))
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
        if (!col.running) {
            Spacer(Modifier.height(8.dp))
            TButton(Th.say("FETCH MORE LISTS", "Fetch more lists"), { model.bank.requestMore() }, Modifier.fillMaxWidth(), textSize = 12.sp)
        }
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

    when (style) {
        Style.TERMINAL -> Row(
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
        Style.PAPER -> Column(Modifier.fillMaxWidth().then(anim).clickable(src, null, onClick = onClick)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                TText("${index + 1}.", Modifier.width(30.dp), size = 15.sp, color = C.dim, weight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    TText(item.label.lowercase().replaceFirstChar { it.uppercase() }, size = 19.sp,
                        color = if (pressed) C.hot else C.amber, weight = FontWeight.Bold)
                    TText(item.hint, size = 12.sp, color = C.dim)
                }
                item.badge?.let { TText(it, size = 11.sp, color = C.hot, weight = FontWeight.Bold) }
            }
            Box1px()
        }
        Style.NEON -> Row(
            Modifier.fillMaxWidth().then(anim).padding(vertical = 5.dp)
                .box(style, if (pressed) C.hot else C.amber.copy(alpha = 0.55f), if (pressed) C.hot.copy(alpha = 0.18f) else C.panel.copy(alpha = 0.55f),
                    strokeWidth = 1.5.dp, glow = pressed || index == 0)
                .clickable(src, null, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                TText(item.label, size = 16.sp, color = if (index == 0) C.hot else C.amber, weight = FontWeight.Bold, spacing = 1.5.sp)
                TText(item.hint, size = 11.sp, color = C.dim)
            }
            TText("›", size = 22.sp, color = C.hot, weight = FontWeight.Bold)
        }
        Style.CLEAN -> Row(
            Modifier.fillMaxWidth().then(anim).padding(vertical = 5.dp)
                .box(style, C.line.copy(alpha = 0.6f), if (pressed) C.ghost else C.panel)
                .clickable(src, null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dot = if (index == 0) C.hot else C.ghost
            TText(
                "${index + 1}", Modifier.width(30.dp).box(style, Color.Transparent, dot, pill = true).padding(vertical = 4.dp),
                size = 12.sp, color = if (index == 0) onColor(dot) else C.dim, weight = FontWeight.SemiBold,
                align = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                TText(item.label.lowercase().replaceFirstChar { it.uppercase() }.replace("1v1", "1v1"), size = 16.sp,
                    color = C.amber, weight = FontWeight.SemiBold)
                TText(item.hint, size = 12.sp, color = C.dim)
            }
            item.badge?.let {
                TText(it, Modifier.box(style, Color.Transparent, C.hot, pill = true).padding(horizontal = 8.dp, vertical = 3.dp),
                    size = 10.sp, color = onColor(C.hot), weight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
            }
            TText("›", size = 20.sp, color = C.dim)
        }
        Style.PIXEL -> Row(
            Modifier.fillMaxWidth().then(anim)
                .clickable(src, null, onClick = onClick)
                .padding(vertical = 11.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TText(if (pressed) "▶" else " ", Modifier.width(22.dp), size = 16.sp, color = C.amber)
            TText(item.label, Modifier.weight(1f), size = 17.sp, color = C.amber)
            item.badge?.let { TText(it, Modifier.background(C.amber).padding(horizontal = 4.dp, vertical = 2.dp), size = 11.sp, color = C.bg) }
        }
    }
}

@Composable
private fun Box1px() {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
}
