package app.coredump.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Rank
import app.coredump.core.Tier
import app.coredump.core.depthFrames
import app.coredump.dailyNumber
import app.coredump.data.History
import app.coredump.data.RunRecord
import app.coredump.platform.Platform
import app.coredump.ui.C
import app.coredump.ui.KV
import app.coredump.ui.Panel
import app.coredump.ui.RollingNumber
import app.coredump.ui.TText
import app.coredump.ui.group
import app.coredump.ui.tierColor

@Composable
fun StatsScreen(model: AppModel) {
    val runs = remember { History.runs }
    Frame(model, "cat /proc/self/stats") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (runs.isEmpty()) {
                TText("no runs yet. nothing has been dumped.", size = 13.sp, color = C.dim)
                return@Column
            }
            val prompts = runs.sumOf { it.tiers.size }
            val points = runs.sumOf { it.points }
            val avg = if (prompts > 0) points.toDouble() / prompts else 0.0
            Panel("PROCESS") {
                Row(verticalAlignment = Alignment.Bottom) {
                    RollingNumber(depthFrames(History.best), size = 30.sp, color = C.hot)
                    TText(" fr deepest", Modifier.padding(bottom = 4.dp), size = 12.sp, color = C.dim)
                }
                Spacer(Modifier.height(8.dp))
                KV("runs", group(runs.size))
                KV("prompts answered", group(prompts))
                KV("avg points / prompt", "${(avg * 10).toInt() / 10.0}")
                KV("lifetime rank", Rank.of(avg).title, C.hot)
                KV("daily streak", "${streak(runs)} day(s)")
                KV("total depth", "${group(depthFrames(points))} fr")
            }
            Spacer(Modifier.height(14.dp))
            Panel("TIER DISTRIBUTION") {
                val all = runs.flatMap { it.tiers }
                val max = Tier.entries.maxOf { t -> all.count { it == t } }.coerceAtLeast(1)
                for (t in Tier.entries.reversed()) {
                    val n = all.count { it == t }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        TText(t.label.padEnd(12), Modifier.width(112.dp), size = 11.sp, color = tierColor(t))
                        TText("█".repeat((n * 16 / max).coerceAtLeast(if (n > 0) 1 else 0)) + " $n", size = 11.sp, color = tierColor(t))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel("TOPICS  (avg points)") {
                val byTopic = HashMap<String, MutableList<Int>>()
                for (r in runs) r.topics.zip(r.tiers).forEach { (tp, t) -> byTopic.getOrPut(tp) { ArrayList() } += t.points }
                for ((tp, pts) in byTopic.entries.sortedByDescending { it.value.average() }.take(10)) {
                    KV("#$tp (${pts.size})", "${(pts.average() * 10).toInt() / 10.0}")
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel("RECENT") {
                for (r in runs.take(15)) RunLine(r)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RunLine(r: RunRecord) {
    val days = ((Platform.epochMillis() - r.at) / 86_400_000L).toInt()
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        TText(if (days == 0) "today" else "${days}d ago", Modifier.width(64.dp), size = 10.sp, color = C.line)
        TText(
            when {
                r.daily != null -> "daily #${r.daily}"
                r.opponent != null -> "vs ${r.opponent}"
                else -> r.mode
            }, Modifier.weight(1f), size = 11.sp, color = C.dim, maxLines = 1,
        )
        TText(r.tiers.joinToString("") { if (it.isValid) "▮" else "▯" }, size = 10.sp, color = C.dim)
        TText("  ${group(depthFrames(r.points))}", size = 11.sp, color = C.hot, weight = FontWeight.Bold)
    }
}

/** Consecutive days (ending today or yesterday) with a recorded daily dive. */
private fun streak(runs: List<RunRecord>): Int {
    val days = runs.mapNotNull { it.daily }.toSet()
    var d = dailyNumber(Platform.epochMillis())
    if (d !in days) d -= 1
    var n = 0
    while (d in days) { n++; d-- }
    return n
}
