package app.coredump.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.ui.C
import app.coredump.ui.Divider
import app.coredump.ui.LocalLook
import app.coredump.ui.TText
import app.coredump.ui.rememberHaptic
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Status bar: "◂ BACK   TITLE ........ uid=HANDLE" */
@Composable
fun Header(model: AppModel, title: String, onBack: (() -> Unit)? = { model.back() }) {
    val haptic = rememberHaptic()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TText("◂ ", Modifier.clickable(remember { MutableInteractionSource() }, null) { haptic(false); onBack() }
                    .padding(end = 8.dp), size = 16.sp, weight = FontWeight.Bold)
            }
            TText(title, Modifier.weight(1f), size = 13.sp, weight = FontWeight.Bold, spacing = 2.sp, maxLines = 1)
            TText("uid=${model.settings.handle.take(10)}", size = 11.sp, color = C.dim, maxLines = 1)
        }
        Divider(Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
fun Frame(model: AppModel, title: String, onBack: (() -> Unit)? = { model.back() }, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(model, title, onBack)
        content()
    }
}

/** Big title with chromatic glitch bursts every few seconds. */
@Composable
fun GlitchTitle(text: String, size: TextUnit = 40.sp) {
    val motion = !LocalLook.current.reduceMotion
    var burst by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf(0) }
    LaunchedEffect(motion) {
        if (!motion) return@LaunchedEffect
        while (true) {
            delay(2200L + Random.nextLong(3800))
            repeat(3 + Random.nextInt(4)) { burst = true; seed = Random.nextInt(); delay(45) }
            burst = false
        }
    }
    Box {
        if (burst) {
            val r = Random(seed)
            TText(text, Modifier.offset(x = (-2 - r.nextInt(4)).dp, y = 1.dp), size = size, color = C.blood.copy(alpha = 0.7f), weight = FontWeight.Bold, glow = 0f)
            TText(text, Modifier.offset(x = (2 + r.nextInt(4)).dp, y = (-1).dp), size = size, color = C.hot.copy(alpha = 0.45f), weight = FontWeight.Bold, glow = 0f)
        }
        TText(if (burst) corrupt(text, seed) else text, size = size, weight = FontWeight.Bold, glow = 1.6f, color = C.amber)
    }
}

private fun corrupt(t: String, seed: Int): String {
    val r = Random(seed)
    return t.map { c -> if (c != ' ' && r.nextFloat() < 0.18f) "▓▒░#%&@$"[r.nextInt(8)] else c }.joinToString("")
}

fun fmtSeconds(s: Int): String = if (s == 0) "∞" else "${s}s"
