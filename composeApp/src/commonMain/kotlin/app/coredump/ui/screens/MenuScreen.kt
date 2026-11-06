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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.Screen
import app.coredump.core.SourceKind
import app.coredump.core.depthFrames
import app.coredump.core.hexAddr
import app.coredump.data.History
import app.coredump.data.SyncState
import app.coredump.ui.C
import app.coredump.ui.Cursor
import app.coredump.ui.DecodeText
import app.coredump.ui.Divider
import app.coredump.ui.Spinner
import app.coredump.ui.TText
import app.coredump.ui.Typewriter
import app.coredump.ui.group
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

@Composable
fun MenuScreen(model: AppModel) {
    val bank by model.bank.state.collectAsState()
    val haptic = rememberHaptic()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        TText("/usr/bin/", size = 12.sp, color = C.dim)
        GlitchTitle("CORE_DUMP", 42.sp)
        Typewriter("the obvious get garbage-collected.", size = 13.sp, color = C.dim, weight = FontWeight.Normal, charMs = 26)
        Spacer(Modifier.height(10.dp))
        val best = History.best
        TText(
            if (History.games == 0) "no dumps on record. go deep."
            else "deepest dump: ${group(depthFrames(best))} frames ${hexAddr(depthFrames(best))} · ${History.games} runs",
            size = 11.sp, color = C.dim,
        )
        Spacer(Modifier.height(26.dp))

        val items = listOf(
            Triple("RUN SOLO", "./dive --solo") { model.go(Screen.Setup(forHost = false)) },
            Triple("LINK 1v1", "./dive --peer --lan") { model.go(Screen.Lobby) },
            Triple("BROWSE DUMPS", "ls -la /var/lists") { model.go(Screen.Browse) },
            Triple("CONFIG", "vim ~/.coredumprc") { model.go(Screen.Config) },
            Triple("MANUAL", "man coredump") { model.go(Screen.Manual) },
        )
        items.forEachIndexed { i, (label, cmd, action) ->
            MenuItem(i, label, cmd) { haptic(true); action() }
        }

        Spacer(Modifier.height(26.dp))
        Divider()
        Spacer(Modifier.height(10.dp))
        TText("${group(bank.prompts)} prompts · ${group(bank.answers)} answers hashed", size = 12.sp, color = C.amber)
        Spacer(Modifier.height(6.dp))
        for (k in SourceKind.entries) {
            val st = bank.sources.getValue(k)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                TText(k.tag.padEnd(10), size = 11.sp, color = C.dim)
                when (st.state) {
                    SyncState.SYNCING -> { Spinner(C.amber, 11.sp); TText(" ${st.done}/${st.total}", size = 11.sp, color = C.amber) }
                    SyncState.OK -> TText("✓ ${st.count}", size = 11.sp, color = C.amber)
                    SyncState.ERROR -> TText("✗ offline · ${st.count} cached", size = 11.sp, color = C.blood)
                    SyncState.OFF -> TText("off", size = 11.sp, color = C.line)
                    SyncState.IDLE -> TText("· ${st.count}", size = 11.sp, color = C.dim)
                }
                if (st.detail.isNotEmpty() && st.state != SyncState.SYNCING) TText("  ${st.detail}", size = 10.sp, color = C.line)
            }
        }
        Spacer(Modifier.height(18.dp))
        var w by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(7000); w = (w + 1) % whispers.size } }
        DecodeText(whispers[w], size = 11.sp, color = C.bloodDim, weight = FontWeight.Normal, glow = 0.6f, durationMs = 900)
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun MenuItem(index: Int, label: String, cmd: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(80L * index + 120); enter.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = enter.value; translationX = (1f - enter.value) * -40f }
            .background(if (pressed) C.amber else C.bg.copy(alpha = 0f))
            .clickable(src, null, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (pressed) C.bg else C.amber
        TText(if (pressed) "▶" else ">", Modifier.width(20.dp), size = 16.sp, color = fg, weight = FontWeight.Bold)
        TText("[${index + 1}] ", size = 13.sp, color = if (pressed) C.bg else C.dim)
        TText(label, Modifier.weight(1f), size = 17.sp, color = fg, weight = FontWeight.Bold, glow = if (pressed) 0f else 1f)
        TText(cmd, size = 10.sp, color = if (pressed) C.bg else C.line, maxLines = 1)
        if (pressed) Cursor(12.sp, C.bg)
    }
}
