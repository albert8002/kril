package app.coredump.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.data.RankedResult
import app.coredump.game.Ranked
import app.coredump.platform.Platform
import app.coredump.ui.C
import app.coredump.ui.LocalLook
import app.coredump.ui.Panel
import app.coredump.ui.Spinner
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.rememberClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * RANKED, its own place: my division on the ladder (BIT … CORE, 100 rating points each), the rating with its peak,
 * the queue for a random online opponent near my rating, my last ranked matches with a rating line, and the fixed
 * rules. Only ranked matches move this rating; friendly 1v1s are not rated.
 */
@Composable
fun RankedScreen(model: AppModel) {
    val s = model.settings
    val div = Ranked.division(s.elo)
    Frame(model, Th.say("./run --ranked", "Ranked")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // ---- the division and the rating
            Panel(Th.say("DIVISION", "Your division")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DivisionBadge(div.index, Modifier.size(74.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        TText(div.name, size = 26.sp, weight = FontWeight.Bold, color = C.hot, spacing = 2.sp)
                        TText("rating ${s.elo} · peak ${maxOf(s.peakElo, s.elo)}", size = 13.sp, color = C.amber)
                        val next = Ranked.DIVISIONS.getOrNull(div.index + 1)
                        TText(if (next == null) "the top of the ladder" else "${next.from - s.elo} to ${next.name}", size = 11.sp, color = C.dim)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Bar(div.progress(s.elo))
                Spacer(Modifier.height(8.dp))
                Ladder(div.index)
            }
            Spacer(Modifier.height(14.dp))
            // ---- the queue
            Panel(Th.say("QUEUE", "Play ranked")) {
                val mm = model.matchmaker
                if (mm == null) {
                    TText("a random online opponent near your rating · 5 prompts · 25 s each", size = 12.sp, color = C.dim)
                    Spacer(Modifier.height(10.dp))
                    TButton(Th.say("QUEUE --ranked", "Find a match"), { model.findRanked() }, Modifier.fillMaxWidth(), primary = true, textSize = 17.sp)
                } else {
                    val st by mm.status.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Radar(st.searching, Modifier.size(64.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (st.searching) { Spinner(C.hot); Spacer(Modifier.width(6.dp)) }
                                TText(st.text, size = 13.sp, color = if (st.error != null) C.blood else C.amber, weight = FontWeight.Bold)
                            }
                            st.error?.let { TText(it, size = 11.sp, color = C.blood) }
                            if (st.searching) TText("${st.waiting} other player${if (st.waiting == 1) "" else "s"} searching right now",
                                size = 11.sp, color = C.dim)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    TButton(if (st.searching) "Cancel" else "Search again", { if (st.searching) model.cancelRanked() else model.findRanked() },
                        Modifier.fillMaxWidth(), color = if (st.searching) C.blood else C.hot)
                }
            }
            Spacer(Modifier.height(14.dp))
            // ---- the record
            Panel(Th.say("RECORD", "Your record")) {
                val g = s.rankedGames
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("$g", "games")
                    Stat("${s.rankedWins}", "wins")
                    Stat(if (g > 0) "${s.rankedWins * 100 / g}%" else "–", "win rate")
                    Stat("${s.streak}", "streak")
                    Stat("${s.bestStreak}", "best")
                }
                if (s.rankedLog.size >= 2) {
                    Spacer(Modifier.height(10.dp))
                    RatingLine(s.rankedLog, Modifier.fillMaxWidth().height(70.dp))
                }
                if (s.rankedLog.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TText("no ranked matches yet: your first one places you.", size = 11.sp, color = C.dim)
                } else {
                    Spacer(Modifier.height(8.dp))
                    val now = Platform.epochMillis()
                    for (r in s.rankedLog.take(10)) MatchRow(r, now)
                }
            }
            Spacer(Modifier.height(14.dp))
            AccountLadder(model)
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("RULES", "Ranked rules (fixed)")) {
                val c = Ranked.CONFIG
                for (line in listOf(
                    "${c.rounds} prompts · ${c.secondsPerRound} s each · ${c.difficulty.label} typos · all topics",
                    "speed penalty on: once your opponent answers, you lose ${app.coredump.core.SpeedTax.PER_SECOND} points a second (at most ${app.coredump.core.SpeedTax.MAX}) until you answer or skip",
                    "a random online opponent near your rating; the search widens the longer you wait",
                    "win: rating up, loss: down (Elo: more for beating a stronger player). 100 rating points per division",
                    "only ranked matches count here: friendly 1v1s with friends are not rated",
                )) TText("· $line", size = 12.sp, color = C.amber)
            }
        }
    }
}

@Composable
private fun Stat(v: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TText(v, size = 18.sp, weight = FontWeight.Bold, color = C.hot)
        TText(label, size = 10.sp, color = C.dim)
    }
}

/** How far through the division (0..1), as a filled bar. */
@Composable
private fun Bar(f: Float) {
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(C.line.copy(alpha = 0.35f), cornerRadius = r)
        if (f > 0f) drawRoundRect(Brush.horizontalGradient(listOf(C.amber, C.hot)), size = Size(size.width * f, size.height), cornerRadius = r)
    }
}

/** The whole ladder in one row: passed divisions filled, mine ringed, the rest faint. */
@Composable
private fun Ladder(mine: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (d in Ranked.DIVISIONS) {
            val col = when { d.index < mine -> C.amber; d.index == mine -> C.hot; else -> C.line }
            TText(d.name, size = 8.sp, color = col, weight = if (d.index == mine) FontWeight.Bold else FontWeight.Normal, align = TextAlign.Center)
        }
    }
}

/**
 * A division's badge: a gem cut with one more facet per division (BIT: a dot … CORE: a glowing eight-pointed core),
 * slowly turning and breathing.
 */
@Composable
private fun DivisionBadge(index: Int, modifier: Modifier) {
    val motion = !LocalLook.current.reduceMotion
    val t by rememberClock(motion, fps = 30)
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 * 0.86f
        val pts = (index + 2).coerceAtLeast(3)
        val turn = t * 0.25f
        val breathe = 1f + 0.04f * sin(t * 2.1f)
        drawCircle(Brush.radialGradient(listOf(C.hot.copy(alpha = 0.35f), C.hot.copy(alpha = 0f)), c, r * 1.15f), r * 1.15f, c)
        val outer = Path()
        val inner = Path()
        for (i in 0 until pts * 2) {
            val a = turn + i * PI.toFloat() / pts
            val rr = (if (i % 2 == 0) r else r * 0.62f) * breathe
            val p = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
            if (i == 0) outer.moveTo(p.x, p.y) else outer.lineTo(p.x, p.y)
            val q = Offset(c.x + cos(a) * rr * 0.55f, c.y + sin(a) * rr * 0.55f)
            if (i == 0) inner.moveTo(q.x, q.y) else inner.lineTo(q.x, q.y)
        }
        outer.close(); inner.close()
        drawPath(outer, Brush.linearGradient(listOf(C.hot, C.amber), Offset(0f, 0f), Offset(size.width, size.height)))
        drawPath(outer, C.bg.copy(alpha = 0.5f), style = Stroke(2.dp.toPx()))
        drawPath(inner, C.bg.copy(alpha = 0.22f))
        // a glint crossing the gem
        val g = ((t * 0.35f) % 1f) * 2.4f - 0.7f
        drawLine(C.white.copy(alpha = 0.55f), Offset(c.x + r * (g - 0.3f), c.y - r * 0.6f), Offset(c.x + r * (g + 0.1f), c.y + r * 0.6f), 2.dp.toPx(), StrokeCap.Round)
        if (index == Ranked.DIVISIONS.lastIndex) drawCircle(C.white.copy(alpha = 0.6f + 0.3f * sin(t * 3f)), r * 0.14f, c)
    }
}

/** Searching: a radar sweep with blips (other players in the queue). */
@Composable
private fun Radar(searching: Boolean, modifier: Modifier) {
    val t by rememberClock(searching, fps = 30)
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        drawCircle(C.hot.copy(alpha = 0.08f), r, c)
        for (k in 1..3) drawCircle(C.hot.copy(alpha = 0.25f), r * k / 3f, c, style = Stroke(1.dp.toPx()))
        val a = t * 2.4f
        val tip = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        for (k in 0 until 10) {
            val b = a - k * 0.07f
            drawLine(C.hot.copy(alpha = 0.32f * (1f - k / 10f)), c, Offset(c.x + cos(b) * r, c.y + sin(b) * r), 3.dp.toPx())
        }
        drawLine(C.hot, c, tip, 2.dp.toPx(), StrokeCap.Round)
        // blips light up as the beam passes them, then fade
        for (i in 0 until 5) {
            val ba = i * 2.399f + 0.7f
            val br = r * (0.3f + 0.6f * ((i * 0.37f) % 1f))
            var since = (a - ba) % (2 * PI.toFloat())
            if (since < 0) since += 2 * PI.toFloat()
            val alpha = (1f - since / 2.5f).coerceIn(0f, 1f)
            if (alpha > 0f) drawCircle(C.amber.copy(alpha = alpha), 3.dp.toPx(), Offset(c.x + cos(ba) * br, c.y + sin(ba) * br))
        }
    }
}

/** My rating after each of my last ranked matches, oldest left. */
@Composable
private fun RatingLine(log: List<RankedResult>, modifier: Modifier) {
    val pts = log.take(RankedResult.KEEP).reversed().let { l -> listOf(l.first().after - l.first().delta) + l.map { it.after } }
    Canvas(modifier) {
        val lo = pts.min() - 10; val hi = pts.max() + 10
        val span = (hi - lo).coerceAtLeast(1)
        fun at(i: Int) = Offset(size.width * i / (pts.size - 1).coerceAtLeast(1), size.height * (1f - (pts[i] - lo).toFloat() / span))
        // division lines in view
        for (d in Ranked.DIVISIONS) if (d.from in lo..hi && d.index > 0) {
            val y = size.height * (1f - (d.from - lo).toFloat() / span)
            drawLine(C.line.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        val path = Path()
        for (i in pts.indices) { val p = at(i); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(path, C.hot, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(C.hot, 3.5f.dp.toPx(), at(pts.lastIndex))
    }
}

@Composable
private fun MatchRow(r: RankedResult, now: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        val (word, col) = when { r.won -> "WIN" to C.hot; r.drawn -> "DRAW" to C.amber; else -> "LOSS" to C.blood }
        TText(word, Modifier.width(44.dp), size = 11.sp, weight = FontWeight.Bold, color = col)
        Column(Modifier.weight(1f)) {
            TText("vs ${r.opp} (${r.oppElo})", size = 12.sp, color = C.amber, maxLines = 1)
            TText("${r.mine} – ${r.theirs} · ${ago(now - r.at)}", size = 10.sp, color = C.dim)
        }
        TText((if (r.delta >= 0) "+" else "") + r.delta, size = 13.sp, weight = FontWeight.Bold, color = if (r.delta >= 0) C.hot else C.blood)
    }
}

private fun ago(ms: Long): String {
    val m = ms / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "${m} min ago"; m < 60 * 24 -> "${m / 60} h ago"; else -> "${m / (60 * 24)} d ago" }
}

/** Signed in: the server's ladder (top ratings). Signed out: what an account changes for ranked. */
@Composable
private fun AccountLadder(model: AppModel) {
    val acc = model.account
    if (!acc.configured) return
    Panel(Th.say("LADDER", "Ladder")) {
        if (!acc.signedIn) {
            TText("signed out: this rating lives on this device only. with an account it is kept on the server and moves " +
                "only when both players report the same result; matches between two accounts are rated there.", size = 11.sp, color = C.dim)
            Spacer(Modifier.height(6.dp))
            app.coredump.ui.TLink(Th.say("sign in / make an account →", "Sign in or make an account →"), { model.go(app.coredump.Screen.Account) }, color = C.hot)
            return@Panel
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { if (acc.ladder == null) acc.loadLadder() }
        val top = acc.ladder
        acc.ladderNote?.let { TText(it, size = 11.sp, color = C.dim) }
        if (top != null && top.isEmpty()) TText("no rated matches yet. be the first.", size = 11.sp, color = C.dim)
        val me = acc.profile?.id
        top?.take(20)?.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TText("${i + 1}.".padEnd(4), size = 12.sp, color = C.dim)
                TText(p.handle, Modifier.weight(1f), size = 13.sp, weight = if (p.id == me) FontWeight.Bold else FontWeight.Normal,
                    color = if (p.id == me) C.hot else C.amber)
                TText("${Ranked.division(p.elo).name} ${p.elo}", size = 12.sp, color = C.dim)
            }
        }
        Spacer(Modifier.height(6.dp))
        app.coredump.ui.TLink("refresh", { acc.loadLadder() }, color = C.dim)
    }
}
