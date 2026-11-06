package app.coredump.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.Screen
import app.coredump.ui.C
import app.coredump.ui.Cursor
import app.coredump.ui.TText
import app.coredump.ui.asciiBar
import app.coredump.ui.Style
import app.coredump.ui.Th
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BootScreen(model: AppModel) {
    val lines = remember { mutableStateListOf<String>() }
    var progress by remember { mutableStateOf(0f) }
    var ready by remember { mutableStateOf(false) }
    val power = remember { Animatable(0f) }
    val fx = model.fx

    LaunchedEffect(Unit) {
        launch { power.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
        val logs = Channel<String>(Channel.UNLIMITED)
        val printer = launch {
            for (l in logs) {
                lines += l
                if (lines.size > 60) lines.removeAt(0)
                delay(28)
            }
        }
        delay(450)
        for (l in listOf(
            "CORE_DUMP BIOS 1.1.0  (c) 1987-2026 /dev/null systems",
            "cpu0: amber phosphor P3 @ 60Hz ............ [ OK ]",
            "memtest: 640K ought to be enough ............ [ OK ]",
            "warning: something is living in the heap",
            "mounting /packs ...",
        )) { logs.send(l); progress += 0.08f; delay(70) }

        model.bank.boot(model.settings) { logs.trySend(it); progress = (progress + 0.04f).coerceAtMost(0.9f) }

        logs.send("[ .. ] new lists are collected in the background")
        logs.send("[ OK ] ranking tables loaded")
        logs.send("")
        logs.send("login: ${model.settings.handle.lowercase()}")
        logs.send("last login: never. or so they told you.")
        progress = 1f
        delay(500)
        logs.close()
        printer.join()
        ready = true
        fx.glitch()
        delay(1400)
        if (model.screen == Screen.Boot) model.go(Screen.Menu, push = false)
    }

    if (!Th.terminal) {
        // Splash for the non-terminal themes: title, progress, last status line.
        Box(
            Modifier.fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null) {
                    if (ready && model.screen == Screen.Boot) model.go(Screen.Menu, push = false)
                }
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                AppTitle(38.sp)
                Spacer(Modifier.height(18.dp))
                val track = C.ghost
                val fill = C.hot
                Canvas(Modifier.fillMaxWidth().height(if (Th.style == Style.PIXEL) 12.dp else 6.dp)) {
                    if (Th.style == Style.PIXEL) {
                        val n = 20
                        val gap = 3.dp.toPx()
                        val w = (size.width - gap * (n - 1)) / n
                        for (i in 0 until n) drawRect(if (i < (progress * n).toInt()) fill else track, Offset(i * (w + gap), 0f), Size(w, size.height))
                    } else {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(track, cornerRadius = r)
                        drawRoundRect(fill, size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = r)
                    }
                }
                Spacer(Modifier.height(10.dp))
                val last = lines.lastOrNull { it.isNotBlank() }.orEmpty()
                    .removePrefix("[ OK ] ").removePrefix("[ -- ] ").removePrefix("[ .. ] ").removePrefix("[FAIL] ")
                TText(if (ready) "Tap to start" else last, size = 12.sp, color = if (ready) C.amber else C.dim, maxLines = 1,
                    weight = if (ready) FontWeight.Bold else FontWeight.Normal)
            }
        }
        return
    }

    Box(
        Modifier.fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, null) {
                if (ready && model.screen == Screen.Boot) model.go(Screen.Menu, push = false)
            }
            .graphicsLayer {
                val p = power.value
                scaleY = 0.004f + 0.996f * p
                scaleX = (p * 3f).coerceAtMost(1f)
                alpha = (0.4f + p).coerceAtMost(1f)
            }
            .drawBehind { drawRect(C.white.copy(alpha = (1f - power.value) * 0.9f)) }
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Column(Modifier.weight(1f)) {
                for (l in lines.takeLast(34)) {
                    val c = when {
                        l.startsWith("[FAIL]") || l.startsWith("warning") -> C.blood
                        l.startsWith("[ OK ]") -> C.amber
                        else -> C.dim
                    }
                    TText(l, size = 11.sp, color = c, maxLines = 1)
                }
                Row { if (!ready) Cursor(11.sp) }
            }
            TText("${asciiBar(progress, 24)} ${(progress * 100).toInt()}%", size = 12.sp, color = C.hot)
            Spacer(Modifier.padding(4.dp))
            if (ready) {
                Row {
                    TText("press any key", size = 13.sp, weight = FontWeight.Bold)
                    Cursor(13.sp)
                }
            }
            Spacer(Modifier.fillMaxWidth().padding(6.dp))
        }
    }
}
