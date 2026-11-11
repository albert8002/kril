package app.coredump.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Difficulty
import app.coredump.core.PromptPicker
import app.coredump.core.Spam
import app.coredump.core.Tier
import app.coredump.core.Topics
import app.coredump.ui.Toggle
import app.coredump.ui.C
import app.coredump.ui.Choice
import app.coredump.ui.KV
import app.coredump.ui.Panel
import app.coredump.ui.Stepper
import app.coredump.ui.Style
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
            Panel(Th.say("MODE", "Mode")) {
                Toggle(Th.say(".reversed()", "Reversed mode · .reversed()"), s.reversed, { v -> model.update { it.copy(reversed = v) } },
                    "think like everyone else: the most common answer scores most, the rarest least")
                if (forHost) {
                    Toggle(Th.say("speed penalty", "Speed penalty"), s.speedPenalty, { v -> model.update { it.copy(speedPenalty = v) } },
                        "1v1: once your opponent has answered, you lose points every second until you answer or skip")
                }
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
                    "hyper-specific lists: sub-genres, rare species, specialist subjects… (people lists have their own settings below)")
            }
            Spacer(Modifier.height(16.dp))
            Panel(Th.say("PEOPLE LISTS", "People questions")) {
                PeopleControls(model)
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
                    " · key-mashing −${Spam.PENALTY}" + (if (s.reversed) " · .reversed(): common answers score high" else ""),
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

// ------------------------------------------------------------------ people lists

/** Positions of the "common ↔ niche" slider ([app.coredump.data.Settings.peopleNiche]) and what each lets in. */
private val nicheSteps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
private val nicheNames = listOf("best-known only", "+ narrower jobs", "+ big countries", "+ all countries", "anything goes")

/** Positions of the "max share" slider ([app.coredump.data.Settings.peopleMax], percent). */
private val shareSteps = listOf(0, 10, 20, 30, 50, 75, 100)

/**
 * People lists ("Name a famous French actor"): on/off, how specific they may get, and the most of a run they may
 * take. Used on the run setup and in the settings.
 */
@Composable
internal fun PeopleControls(model: AppModel) {
    val s = model.settings
    Toggle(Th.say("people lists", "People questions"), s.peopleLists, { v -> model.update { it.copy(peopleLists = v) } },
        "\"name a famous …\" lists about real people: actors, athletes, singers…")
    if (s.peopleLists) {
        val ni = nicheSteps.indices.minByOrNull { kotlin.math.abs(nicheSteps[it] - s.peopleNiche) } ?: 1
        Spacer(Modifier.height(8.dp))
        KV(Th.say("common <-> niche", "Common ↔ niche"), nicheNames[ni])
        StepSlider(nicheSteps.size, ni, { i -> model.update { it.copy(peopleNiche = nicheSteps[i]) } }, "common", "niche")
        TText("common: only the big global lists (\"a famous actor\"). further right, lists by country and narrower jobs " +
            "(\"a famous Belgian cyclist\") can come up, more often the further you go.", size = 10.sp, color = C.dim)
        Spacer(Modifier.height(12.dp))
        val si = shareSteps.indices.minByOrNull { kotlin.math.abs(shareSteps[it] - s.peopleMax) } ?: 2
        val cap = PromptPicker.peopleCap(s.gameConfig())
        KV(Th.say("max share of people prompts", "Max share of people questions"), "${shareSteps[si]}% · up to $cap of ${s.rounds}")
        StepSlider(shareSteps.size, si, { i -> model.update { it.copy(peopleMax = shareSteps[i]) } }, "0%", "100%")
        TText("a ceiling, not a quota: runs hold fewer whenever there are other questions, and only go over it when the " +
            "chosen categories have nothing else left.", size = 10.sp, color = C.dim)
    }
}

/** Step of a [StepSlider] under a pointer at [x] (px) on a track [width] px wide with [pad] px ends. */
private fun stepAt(x: Float, width: Int, pad: Float, n: Int): Int {
    val f = ((x - pad) / (width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (f * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)
}

/**
 * A slider with [steps] fixed positions (tap or drag along the track), [index] selected; [left] / [right] label the
 * ends. Square knob on the terminal, handheld and desktop looks, round elsewhere.
 */
@Composable
internal fun StepSlider(steps: Int, index: Int, onIndex: (Int) -> Unit, left: String, right: String) {
    val haptic = rememberHaptic()
    val n = steps.coerceAtLeast(2)
    val cur by rememberUpdatedState(index.coerceIn(0, n - 1))
    val pick by rememberUpdatedState(onIndex)
    val on = C.hot
    val off = C.line
    val face = C.bg
    val square = Th.style == Style.TERMINAL || Th.style == Style.PIXEL || Th.style == Style.DESKTOP
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(40.dp)
                .pointerInput(n) {
                    detectTapGestures { pos ->
                        val i = stepAt(pos.x, size.width, 12.dp.toPx(), n)
                        if (i != cur) {
                            haptic(false)
                            pick(i)
                        }
                    }
                }
                .pointerInput(n) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val i = stepAt(change.position.x, size.width, 12.dp.toPx(), n)
                        if (i != cur) {
                            haptic(false)
                            pick(i)
                        }
                    }
                },
        ) {
            val pad = 12.dp.toPx()
            val y = size.height / 2f
            val w = size.width - 2 * pad
            val fx = pad + w * cur / (n - 1).toFloat()
            val line = 3.dp.toPx()
            drawLine(off, Offset(pad, y), Offset(pad + w, y), strokeWidth = line, cap = StrokeCap.Round)
            drawLine(on, Offset(pad, y), Offset(fx, y), strokeWidth = line, cap = StrokeCap.Round)
            for (i in 0 until n) {
                val x = pad + w * i / (n - 1).toFloat()
                drawCircle(if (i <= cur) on else off, 3.dp.toPx(), Offset(x, y))
            }
            val r = 9.dp.toPx()
            if (square) {
                drawRect(face, Offset(fx - r, y - r), Size(2 * r, 2 * r))
                drawRect(on, Offset(fx - r, y - r), Size(2 * r, 2 * r), style = Stroke(2.dp.toPx()))
            } else {
                drawCircle(face, r, Offset(fx, y))
                drawCircle(on, r, Offset(fx, y), style = Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TText(left, Modifier.weight(1f), size = 10.sp, color = C.dim)
            TText(right, Modifier.weight(1f), size = 10.sp, color = C.dim, align = TextAlign.End)
        }
    }
}
