package app.coredump.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
import app.coredump.Screen
import app.coredump.core.Rank
import app.coredump.core.RoundView
import app.coredump.core.Tier
import app.coredump.core.depthFrames
import app.coredump.core.hexAddr
import app.coredump.game.MatchSession
import app.coredump.ui.C
import app.coredump.ui.DecodeText
import app.coredump.ui.DaemonShaft
import app.coredump.platform.shareText
import app.coredump.ui.Divider
import app.coredump.ui.Panel
import app.coredump.ui.RollingNumber
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.TierBadge
import app.coredump.ui.rememberHaptic
import app.coredump.ui.tierColor

@Composable
fun SummaryScreen(model: AppModel, session: MatchSession) {
    val st by session.state.collectAsState()
    val rounds = st.rounds
    val my = st.myTotal
    val avg = if (rounds.isEmpty()) 0.0 else my.toDouble() / rounds.size
    val rank = Rank.of(avg)
    val maxPts = rounds.size * Tier.ZERO_DAY.points

    Frame(model, "CORE DUMPED", onBack = { model.home() }) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(14.dp))
                Row {
                    Column(Modifier.weight(1f)) {
                        TText(if (st.daily != null) "daily dive #${st.daily} · exited" else "process exited · status ${if (my > 0) 0 else 139}",
                            size = 11.sp, color = C.dim)
                        Spacer(Modifier.height(6.dp))
                        RollingNumber(depthFrames(my), size = 36.sp, color = C.hot, durationMs = 2400)
                        TText("frames deep", size = 13.sp, color = C.dim)
                        TText("${hexAddr(depthFrames(my))} · ${my}/${maxPts} pts", size = 11.sp, color = C.dim)
                        TText("avg ${(avg * 10).toInt() / 10.0} per prompt", size = 11.sp, color = C.dim)
                        Spacer(Modifier.height(14.dp))
                        TText("RANK", size = 10.sp, color = C.dim, spacing = 2.sp)
                        DecodeText(rank.title, size = 28.sp, color = C.amber, durationMs = 1100)
                        TText(rank.line, size = 12.sp, color = C.dim)
                    }
                    var acc = 0
                    val marks = rounds.map { r -> acc += r.me.points; depthFrames(acc) to "#${r.index + 1} +${r.me.points}" }
                    DaemonShaft(
                        depthFrames = depthFrames(my), startFrom = 0, markers = marks, fallMs = 2600,
                        lastTier = rounds.maxByOrNull { it.me.points }?.me?.tier,
                        modifier = Modifier.width(72.dp).height(300.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                TButton("SHARE DUMP", { shareText(shareCard(st, rank)) }, Modifier.fillMaxWidth(), textSize = 13.sp)

                val opp = st.oppName
                if (session.isMulti && opp != null) {
                    Spacer(Modifier.height(14.dp))
                    val o = st.oppTotal
                    Panel("DUEL") {
                        Row {
                            TText(st.myName, Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold)
                            TText("${depthFrames(my)} fr", size = 14.sp, color = C.hot)
                        }
                        Row {
                            TText(opp, Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold, color = C.dim)
                            TText("${depthFrames(o)} fr", size = 14.sp, color = C.dim)
                        }
                        Spacer(Modifier.height(6.dp))
                        TText(
                            when {
                                my > o -> "WINNER: YOU. the peer was garbage-collected."
                                my < o -> "WINNER: ${opp.uppercase()}. you were garbage-collected."
                                else -> "DEADLOCK. nobody wins."
                            },
                            size = 13.sp, color = if (my >= o) C.hot else C.blood, weight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Panel("TIER HISTOGRAM") {
                    for (t in Tier.entries.reversed()) {
                        val n = rounds.count { it.me.tier == t }
                        if (n == 0) continue
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TText(t.label.padEnd(12), size = 11.sp, color = tierColor(t))
                            TText("█".repeat(n) + "  $n", size = 11.sp, color = tierColor(t))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                TText("STACK TRACE  (tap a frame for deeper answers)", size = 10.sp, color = C.dim, spacing = 1.sp)
                Spacer(Modifier.height(6.dp))
            }
            items(rounds, key = { it.index }) { r ->
                RoundRow(model, r, session.isMulti)
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton("AGAIN", { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(10.dp))
            }
            TButton("SHELL", { model.home() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RoundRow(model: AppModel, r: RoundView, multi: Boolean) {
    var open by remember { mutableStateOf(false) }
    val haptic = rememberHaptic()
    val better = r.betterThan(r.me.points)
    Column(
        Modifier.fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, null) { haptic(false); open = !open }
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TText("#${(r.index + 1).toString().padStart(2, '0')} ", size = 11.sp, color = C.dim)
            TText(r.prompt, Modifier.weight(1f), size = 12.sp, color = C.amber, maxLines = 2)
            TText(if (open) " [-]" else " [+]", size = 11.sp, color = C.dim)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TText("  ${answerText(r.me.tier, r.me.canonical, r.me.input)}", Modifier.weight(1f), size = 13.sp,
                color = tierColor(r.me.tier), weight = FontWeight.Bold, maxLines = 1)
            TierBadge(r.me.tier, 10.sp)
            TText(" +${r.me.points}".padStart(5), size = 12.sp, color = tierColor(r.me.tier))
        }
        val opp = r.opp
        if (multi && opp != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TText("  ${opp.player}: ${answerText(opp.tier, opp.canonical, opp.input)}", Modifier.weight(1f), size = 11.sp,
                    color = C.dim, maxLines = 1)
                TText("+${opp.points}", size = 11.sp, color = C.dim)
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 14.dp, top = 6.dp)) {
                if (better.isEmpty()) {
                    TText("nothing deeper existed. you hit bedrock.", size = 11.sp, color = C.hot)
                } else {
                    TText("WOULD HAVE GONE DEEPER:", size = 10.sp, color = C.dim, spacing = 1.sp)
                    for (g in better) {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            TText("${g.tier.label} +${g.tier.points}".padEnd(16), size = 11.sp, color = tierColor(g.tier))
                            TText(g.names.joinToString(" · "), size = 11.sp, color = C.amber)
                        }
                    }
                }
                val pid = r.promptId
                if (pid != null && model.bank.get(pid) != null) {
                    TLink("cat full dump (${r.listSize} entries)", { model.go(Screen.Dump(pid)) })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Divider()
    }
}

private fun answerText(t: Tier, canonical: String?, input: String): String = when (t) {
    Tier.TIMEOUT -> "<timeout>"
    Tier.SEGFAULT -> "$input  ✗ segfault"
    else -> canonical ?: input
}

private fun tierGlyph(t: Tier): String = when (t) {
    Tier.TIMEOUT, Tier.SEGFAULT -> "⬛"
    Tier.HELLO_WORLD, Tier.COPYPASTA -> "🟨"
    Tier.LEGACY -> "🟧"
    Tier.MEMORY_LEAK -> "🟥"
    Tier.HEISENBUG -> "🟪"
    Tier.ZERO_DAY -> "💀"
}

/** Spoiler-free result card, Wordle style. */
fun shareCard(st: app.coredump.game.MatchState, rank: Rank): String {
    val head = when {
        st.daily != null -> "CORE_DUMP daily #${st.daily}"
        st.oppName != null -> "CORE_DUMP 1v1 vs ${st.oppName}"
        else -> "CORE_DUMP dive"
    }
    val grid = st.rounds.joinToString("") { tierGlyph(it.me.tier) }
    val opp = if (st.oppName != null) " · ${st.oppName} ${depthFrames(st.oppTotal)} fr" else ""
    return "$head\n${depthFrames(st.myTotal)} frames deep · ${rank.title}$opp\n$grid"
}
