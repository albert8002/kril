package app.coredump.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Topics
import app.coredump.ui.C
import app.coredump.ui.ChipBox
import app.coredump.ui.Choice
import app.coredump.ui.Cue
import app.coredump.ui.Panel
import app.coredump.ui.ProgressBar
import app.coredump.ui.Sfx
import app.coredump.ui.Spinner
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.Toggle
import app.coredump.ui.group

/** Amounts offered for one request (lists). */
private val AMOUNTS = listOf(5, 10, 20, 30, 50, 100)

/**
 * "Fetch more lists": pick the categories, how many lists, and watch them arrive. Each list is ranked and
 * playable the moment it lands; the player can leave this screen (or play) while it runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FetchScreen(model: AppModel) {
    val s = model.settings
    val bank by model.bank.state.collectAsState()
    val col = bank.collect
    val avail = remember(bank.prompts, col.running, s.niche) { model.bank.available(s.niche) }
    val chosen = s.fetchTopics.filter { (avail[it] ?: 0) > 0 }.toSet()
    val pool = if (chosen.isEmpty()) avail.values.sum() else chosen.sumOf { avail[it] ?: 0 }
    val ask = minOf(s.fetchCount, pool)

    // a chime when a request finishes with new lists
    var wasRunning by remember { mutableStateOf(col.running) }
    LaunchedEffect(col.running) {
        if (wasRunning && !col.running && col.added > 0) Sfx.play(Cue.FETCHED)
        wasRunning = col.running
    }

    Frame(model, Th.say("fetch --lists", "Fetch lists")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel(Th.say("PROGRESS", "Progress")) {
                if (col.running || col.total > 0) {
                    val f = if (col.total == 0) 0f else col.done / col.total.toFloat()
                    ProgressBar(f)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (col.running) { Spinner(C.hot, 12.sp); Spacer(Modifier.width(6.dp)) }
                        TText("${col.done} / ${col.total} checked · +${col.added} new" + if (col.failed > 0) " · ${col.failed} failed" else "",
                            size = 12.sp, color = C.amber, maxLines = 1)
                    }
                    val what = if (col.topics.isEmpty()) "all categories" else col.topics.joinToString(", ") { Topics.label(it) }
                    TText(what, size = 11.sp, color = C.dim, maxLines = 2)
                } else {
                    TText("Pick categories and an amount, then fetch.", size = 12.sp, color = C.dim)
                }
                if (col.message.isNotEmpty() && !col.running) TText(col.message, size = 11.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                TText("${group(bank.prompts)} lists on this device · ${col.library} collected" +
                    if (col.shared > 0) " · ${col.shared} from friends" else "", size = 11.sp, color = C.dim)
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("CATEGORIES", "Categories") + "  (${if (chosen.isEmpty()) "ALL" else chosen.size.toString()})") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChipBox("${Th.say("#ALL", "All")} ${avail.values.sum()}", chosen.isEmpty(), size = 11.sp) {
                        model.update { it.copy(fetchTopics = emptySet()) }
                    }
                    for (t in Topics.all.filter { (avail[it.key] ?: 0) > 0 }) {
                        val n = avail[t.key] ?: 0
                        ChipBox("${Th.say("#" + t.key.replace(" & ", "&").replace(' ', '_'), t.icon + " " + t.label)} $n", t.key in chosen, size = 11.sp) {
                            model.update { st ->
                                val cur = st.fetchTopics.filter { (avail[it] ?: 0) > 0 }.toSet()
                                st.copy(fetchTopics = if (t.key in cur) cur - t.key else cur + t.key)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TText("${group(pool)} new lists available in this selection", size = 11.sp, color = if (pool == 0) C.blood else C.dim)
                Spacer(Modifier.height(4.dp))
                Toggle(Th.say("include niche lists", "Include niche lists"), s.niche, { v -> model.update { it.copy(niche = v) } },
                    "hyper-specific lists: players from one country, sub-genres, rare species…")
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("AMOUNT", "How many")) {
                Choice(AMOUNTS, AMOUNTS.minByOrNull { kotlin.math.abs(it - s.fetchCount) } ?: 20, { "$it" }) { v ->
                    model.update { it.copy(fetchCount = v) }
                }
                Spacer(Modifier.height(6.dp))
                TText("Each list takes a few seconds. You can keep playing while they arrive; every list is playable as soon as it lands.",
                    size = 10.sp, color = C.dim)
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (col.running) {
                TButton(Th.say("STOP", "Stop"), { model.bank.stopCollect() }, Modifier.fillMaxWidth(), color = C.blood)
            } else {
                TButton(
                    if (ask == 0) Th.say("NOTHING LEFT HERE", "Nothing left in this selection")
                    else Th.say("FETCH $ask LISTS", "Fetch $ask list" + if (ask == 1) "" else "s"),
                    { model.bank.requestMore(ask, chosen) }, Modifier.fillMaxWidth(), enabled = ask > 0, primary = true,
                )
            }
        }
    }
}

@Composable
internal fun FetchStatusLine(model: AppModel) {
    val bank by model.bank.state.collectAsState()
    val col = bank.collect
    if (!col.running) return
    Spacer(Modifier.height(6.dp))
    ProgressBar(if (col.total == 0) 0f else col.done / col.total.toFloat())
    TText("${col.done}/${col.total} · +${col.added}", size = 10.sp, color = C.dim, weight = FontWeight.Normal)
}
