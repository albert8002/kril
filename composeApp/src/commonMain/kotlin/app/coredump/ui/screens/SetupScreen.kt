package app.coredump.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Difficulty
import app.coredump.ui.C
import app.coredump.ui.Choice
import app.coredump.ui.Panel
import app.coredump.ui.Stepper
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.group
import app.coredump.ui.rememberHaptic

private val secondOptions = listOf(10, 15, 25, 40, 60, 90, 0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(model: AppModel, forHost: Boolean) {
    val s = model.settings
    val bank by model.bank.state.collectAsState()
    val topics = remember(bank.prompts) { model.bank.topics() }
    val haptic = rememberHaptic()
    val pool = remember(bank.prompts, s.topics) {
        model.bank.all().count { s.topics.isEmpty() || it.topic in s.topics }
    }

    Frame(model, if (forHost) "HOST PARAMETERS" else "SOLO DIVE") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel("PARAMETERS") {
                Stepper("prompts per run", s.rounds, { v -> model.update { it.copy(rounds = v) } }, 1..50)
                Spacer(Modifier.height(10.dp))
                TText("time per prompt", size = 13.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                Choice(secondOptions, s.seconds, { fmtSeconds(it) }) { v -> model.update { it.copy(seconds = v) } }
                Spacer(Modifier.height(14.dp))
                TText("difficulty", size = 13.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                Choice(Difficulty.entries, s.difficulty, { it.label }) { v -> model.update { it.copy(difficulty = v) } }
                Spacer(Modifier.height(6.dp))
                TText("» ${s.difficulty.blurb}", size = 11.sp, color = if (s.difficulty == Difficulty.NIGHTMARE) C.blood else C.dim)
            }
            Spacer(Modifier.height(16.dp))
            Panel("TOPICS  (${if (s.topics.isEmpty()) "ALL" else s.topics.size.toString()})") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TopicChip("#ALL", bank.prompts, s.topics.isEmpty()) {
                        haptic(false); model.update { it.copy(topics = emptySet()) }
                    }
                    for ((t, n) in topics) {
                        TopicChip("#$t", n, t in s.topics) {
                            haptic(false)
                            model.update { st ->
                                val next = if (t in st.topics) st.topics - t else st.topics + t
                                st.copy(topics = next)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TText("${group(pool)} prompts in pool", size = 11.sp, color = if (pool < s.rounds) C.blood else C.dim)
            }
            Spacer(Modifier.height(16.dp))
            TText(
                "scoring: HELLO_WORLD 10 · COPYPASTA 15 · LEGACY 30 · MEMORY_LEAK 60 · HEISENBUG 85 · ZERO_DAY 100",
                size = 10.sp, color = C.line,
            )
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (forHost) TButton("SAVE + RETURN", { model.back() }, Modifier.fillMaxWidth(), primary = true)
            else TButton("EXECUTE", { model.startSolo() }, Modifier.fillMaxWidth(), primary = true, textSize = 17.sp)
        }
    }
}

@Composable
private fun TopicChip(label: String, count: Int, on: Boolean, onClick: () -> Unit) {
    TText(
        "$label ${count}",
        Modifier.background(if (on) C.amber else Color.Transparent)
            .border(1.dp, if (on) C.amber else C.line)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        size = 11.sp, color = if (on) C.bg else C.amber, weight = FontWeight.Bold, glow = if (on) 0f else 1f,
    )
}
