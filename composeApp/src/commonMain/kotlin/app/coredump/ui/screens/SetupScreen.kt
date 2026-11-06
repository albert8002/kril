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
import app.coredump.core.Spam
import app.coredump.core.Tier
import app.coredump.core.Topics
import app.coredump.ui.Toggle
import app.coredump.ui.C
import app.coredump.ui.Choice
import app.coredump.ui.Panel
import app.coredump.ui.Stepper
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.group
import app.coredump.ui.rememberHaptic
import app.coredump.ui.ChipBox
import app.coredump.ui.Th

private val secondOptions = listOf(10, 15, 25, 40, 60, 90, 0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(model: AppModel, forHost: Boolean) {
    val s = model.settings
    val bank by model.bank.state.collectAsState()
    val counts = remember(bank.prompts, s.niche) {
        model.bank.all().filter { s.niche || !it.niche }.groupingBy { it.topic }.eachCount()
    }
    val topics = Topics.all.filter { (counts[it.key] ?: 0) > 0 }
    val haptic = rememberHaptic()
    val pool = remember(bank.prompts, s.topics, s.niche) {
        model.bank.all().count { (s.topics.isEmpty() || it.topic in s.topics) && (s.niche || !it.niche) }
    }

    Frame(model, if (forHost) Th.say("HOST PARAMETERS", "Match settings") else Th.say("SOLO RUN", "Solo run")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel(Th.say("PARAMETERS", "Settings")) {
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
            Panel(Th.say("CATEGORIES", "Categories") + "  (${if (s.topics.isEmpty()) "ALL" else s.topics.size.toString()})") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TopicChip(Th.say("#ALL", "All"), counts.values.sum(), s.topics.isEmpty()) {
                        haptic(false); model.update { it.copy(topics = emptySet()) }
                    }
                    for (t in topics) {
                        TopicChip(Th.say("#" + t.key.replace(" & ", "&").replace(' ', '_'), t.icon + " " + t.label), counts[t.key] ?: 0, t.key in s.topics) {
                            haptic(false)
                            model.update { st ->
                                val next = if (t.key in st.topics) st.topics - t.key else st.topics + t.key
                                st.copy(topics = next)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TText("${group(pool)} prompts in pool", size = 11.sp, color = if (pool < s.rounds) C.blood else C.dim)
                Spacer(Modifier.height(6.dp))
                Toggle(Th.say("include niche lists", "Include niche lists"), s.niche, { v -> model.update { it.copy(niche = v) } },
                    "hyper-specific lists: players from one country, sub-genres, rare species…")
            }
            Spacer(Modifier.height(16.dp))
            Panel(Th.say("REPEATS", "Repeats")) {
                TText(Th.say("hide prompts already seen for", "Don't repeat a question for"), size = 13.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                Choice(listOf(0, 1, 2, 3, 7), s.cooldownDays, { if (it == 0) "off" else "$it day" + if (it == 1) "" else "s" }) { v ->
                    model.update { it.copy(cooldownDays = v) }
                }
            }
            Spacer(Modifier.height(16.dp))
            TText(
                "scoring: " + Tier.validDescending.reversed().joinToString(" · ") { "${it.label} ${it.points}" } +
                    " · key-mashing −${Spam.PENALTY}",
                size = 10.sp, color = C.line,
            )
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (forHost) TButton(Th.say("SAVE + RETURN", "Save"), { model.back() }, Modifier.fillMaxWidth(), primary = true)
            else TButton(Th.say("EXECUTE", "Start"), { model.startSolo() }, Modifier.fillMaxWidth(), primary = true, textSize = 17.sp)
        }
    }
}

@Composable
private fun TopicChip(label: String, count: Int, on: Boolean, onClick: () -> Unit) =
    ChipBox("$label $count", on, size = 11.sp, onClick = onClick)
