package app.coredump.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.Screen
import app.coredump.core.Judge
import app.coredump.core.Norm
import app.coredump.core.Tier
import app.coredump.ui.C
import app.coredump.ui.Divider
import app.coredump.ui.Panel
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.TermInput
import app.coredump.ui.TierBadge
import app.coredump.ui.group
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor
import app.coredump.ui.ChipBox
import app.coredump.ui.Th
import kotlin.time.TimeSource

@Composable
fun BrowseScreen(model: AppModel) {
    val bank by model.bank.state.collectAsState()
    var q by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf<String?>(null) }
    val topics = remember(bank.prompts) { model.bank.topics() }
    val list = remember(bank.prompts, q, topic) {
        val nq = Norm.key(q)
        model.bank.all()
            .filter { topic == null || it.topic == topic }
            .filter { nq.isEmpty() || Norm.key(it.text).contains(nq) }
            .sortedWith(compareBy<app.coredump.core.Prompt>({ it.topic }, { it.text }))
    }
    val haptic = rememberHaptic()

    Frame(model, Th.say("ls -la /var/lists", "Lists")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            TermInput(q, { q = it }, Modifier.fillMaxWidth(), prompt = Th.say("grep", "$"), placeholder = Th.say("filter prompts", "Search lists"), size = 14.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Chip("#ALL", topic == null) { haptic(false); topic = null } }
                items(topics) { (t, _) -> Chip("#$t", topic == t) { haptic(false); topic = t } }
            }
            Spacer(Modifier.height(6.dp))
            TText("${list.size} lists · ${group(list.sumOf { it.size })} answers", size = 11.sp, color = C.dim)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(list, key = { it.id }) { p ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(remember { MutableInteractionSource() }, null) { haptic(false); model.go(Screen.Dump(p.id)) }
                        .padding(vertical = 9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TText(p.text, Modifier.weight(1f), size = 13.sp, maxLines = 2)
                        TText(group(p.size), size = 12.sp, color = C.hot)
                    }
                    TText("#${p.topic} · ${p.source.tag}" + (if (Th.terminal) " · ${p.id}" else ""), size = 10.sp, color = C.dim, maxLines = 1)
                }
                Divider()
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) = ChipBox(label, on, size = 11.sp, onClick = onClick)

@Composable
fun DumpScreen(model: AppModel, promptId: String) {
    val p = remember(promptId) { model.bank.get(promptId) }
    if (p == null) {
        Frame(model, Th.say("cat: no such file", "Not found")) { TText("  list not in memory.", size = 13.sp, color = C.blood) }
        return
    }
    var probe by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val result = remember(probe) {
        if (probe.isBlank()) null
        else {
            val mark = TimeSource.Monotonic.markNow()
            val j = Judge.judge(p, probe, 2)
            j to mark.elapsedNow().inWholeMicroseconds
        }
    }

    Frame(model, Th.say("cat ${p.id}", p.topic.lowercase().replaceFirstChar { it.uppercase() })) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                TText(if (Th.terminal) "> ${p.text}" else p.text, size = 18.sp, weight = FontWeight.Bold)
                TText("#${p.topic} · ${p.source.tag} · ${group(p.size)} answers" + if (Th.terminal) " · ${p.index.keyCount} hash keys" else "",
                    size = 11.sp, color = C.dim)
                DiscardLink(model, p.id)
                Spacer(Modifier.height(8.dp))
                Panel(Th.say("TIER MAP", "Tiers")) {
                    val maxN = Tier.validDescending.maxOf { p.countOf(it) }.coerceAtLeast(1)
                    for (t in Tier.validDescending) {
                        val n = p.countOf(t)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TText(t.label, Modifier.width(112.dp), size = 11.sp, color = tierColor(t))
                            TText("█".repeat(((n * 18f) / maxN).toInt().coerceAtLeast(if (n > 0) 1 else 0)) + " $n",
                                size = 11.sp, color = tierColor(t))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TermInput(probe, { probe = it }, Modifier.fillMaxWidth(), prompt = Th.say("probe", "$"), placeholder = Th.say("test an answer", "Test an answer"), size = 14.sp)
                Spacer(Modifier.height(6.dp))
                if (result != null) {
                    val (j, us) = result
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TText(j.entry?.name ?: "not found", Modifier.weight(1f), size = 14.sp, color = tierColor(j.tier),
                            weight = FontWeight.Bold, maxLines = 1)
                        TierBadge(j.tier, 10.sp)
                    }
                    TText(
                        (if (j.distance > 0) "fuzzy hit, ${j.distance} edit(s) · " else if (j.entry != null) "exact hash hit · " else "") +
                            "+${j.points} · ${us} µs",
                        size = 10.sp, color = C.dim,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (!reveal) TButton(Th.say("DUMP ALL ${p.size} ENTRIES (spoilers)", "Show all ${p.size} answers (spoilers)"), { reveal = true }, Modifier.fillMaxWidth(), textSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (reveal) {
                itemsIndexed(p.entries) { i, e ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        TText((i + 1).toString().padStart(4, '0'), Modifier.width(44.dp), size = 10.sp, color = C.line)
                        TText(e.name, Modifier.weight(1f), size = 12.sp, color = tierColor(e.tier), maxLines = 1)
                        TText(e.tier.label, size = 9.sp, color = tierColor(e.tier).copy(alpha = 0.7f))
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
