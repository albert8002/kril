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
import app.coredump.core.hexAddr
import app.coredump.game.MatchSession
import app.coredump.ui.C
import app.coredump.ui.DecodeText
import app.coredump.ui.Th
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

    Frame(model, Th.say("CORE DUMPED", "Results"), onBack = { model.home() }) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item {
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth()) {
                    TText(
                        if (st.daily != null) Th.say("daily run #${st.daily} · exited", "Daily run #${st.daily}")
                        else Th.say("process exited · status ${if (my > 0) 0 else 139}", "Run complete"),
                        size = 11.sp, color = C.dim,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        RollingNumber(my, size = 40.sp, color = C.hot, durationMs = 2000)
                        TText(" / $maxPts pts", Modifier.padding(bottom = 6.dp), size = 13.sp, color = C.dim)
                    }
                    TText(Th.say("${hexAddr(my)} · avg ${(avg * 10).toInt() / 10.0} per prompt", "Average ${(avg * 10).toInt() / 10.0} per prompt"),
                        size = 11.sp, color = C.dim)
                    Spacer(Modifier.height(14.dp))
                    TText(Th.say("RANK", "Rank"), size = 10.sp, color = C.dim, spacing = 2.sp)
                    DecodeText(rank.title, size = 28.sp, color = C.amber, durationMs = 1100)
                    TText(rank.line, size = 12.sp, color = C.dim)
                }
                Spacer(Modifier.height(12.dp))
                TButton(Th.say("SHARE DUMP", "Share result"), { shareText(shareCard(st, rank)) }, Modifier.fillMaxWidth(), textSize = 13.sp)

                val opp = st.oppName
                if (session.isMulti && opp != null) {
                    Spacer(Modifier.height(14.dp))
                    val o = st.oppTotal
                    Panel(Th.say("DUEL", "1v1")) {
                        Row {
                            TText(st.myName, Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold)
                            TText("$my pts", size = 14.sp, color = C.hot)
                        }
                        Row {
                            TText(opp, Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold, color = C.dim)
                            TText("$o pts", size = 14.sp, color = C.dim)
                        }
                        Spacer(Modifier.height(6.dp))
                        TText(
                            when {
                                my > o -> Th.say("WINNER: YOU. the peer was garbage-collected.", "You win!")
                                my < o -> Th.say("WINNER: ${opp.uppercase()}. you were garbage-collected.", "$opp wins.")
                                else -> Th.say("DEADLOCK. nobody wins.", "It's a draw.")
                            },
                            size = 13.sp, color = if (my >= o) C.hot else C.blood, weight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Panel(Th.say("TIER HISTOGRAM", "Tiers")) {
                    for (t in Tier.entries.reversed()) {
                        val n = rounds.count { it.me.tier == t }
                        if (n == 0) continue
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TText(t.label, Modifier.width(112.dp), size = 11.sp, color = tierColor(t))
                            TText("█".repeat(n) + "  $n", size = 11.sp, color = tierColor(t))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                TText(Th.say("STACK TRACE  (tap a prompt for rarer answers)", "Prompts · tap one to see rarer answers"), size = 10.sp, color = C.dim, spacing = 1.sp)
                Spacer(Modifier.height(6.dp))
            }
            items(rounds, key = { it.index }) { r ->
                RoundRow(model, r, session.isMulti)
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!session.isMulti) {
                TButton(Th.say("AGAIN", "Play again"), { model.replay(session) }, Modifier.weight(1f), primary = true)
                Spacer(Modifier.width(10.dp))
            }
            TButton(Th.say("SHELL", "Menu"), { model.home() }, Modifier.weight(1f))
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
                    TText(Th.say("nothing rarer existed. you hit bedrock.", "Nothing rarer existed — top score."), size = 11.sp, color = C.hot)
                } else {
                    TText(Th.say("WOULD HAVE SCORED MORE:", "Would have scored more:"), size = 10.sp, color = C.dim, spacing = 1.sp)
                    for (g in better) {
                        Row(Modifier.padding(vertical = 2.dp)) {
                            TText("${g.tier.label} +${g.tier.points}", Modifier.width(140.dp), size = 11.sp, color = tierColor(g.tier))
                            TText(g.names.joinToString(" · "), size = 11.sp, color = C.amber)
                        }
                    }
                }
                val pid = r.promptId
                if (pid != null && model.bank.get(pid) != null) {
                    TLink(Th.say("cat full dump (${r.listSize} entries)", "See all ${r.listSize} answers"), { model.go(Screen.Dump(pid)) })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Divider()
    }
}

private fun answerText(t: Tier, canonical: String?, input: String): String = when (t) {
    Tier.TIMEOUT -> Th.say("<timeout>", "time's up")
    Tier.SEGFAULT -> Th.say("$input  ✗ segfault", "$input  ✗")
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
        st.daily != null -> "CORE DUMP daily #${st.daily}"
        st.oppName != null -> "CORE DUMP 1v1 vs ${st.oppName}"
        else -> "CORE DUMP run"
    }
    val grid = st.rounds.joinToString("") { tierGlyph(it.me.tier) }
    val opp = if (st.oppName != null) " · ${st.oppName} ${st.oppTotal} pts" else ""
    return "$head\n${st.myTotal} pts · ${rank.title}$opp\n$grid"
}
