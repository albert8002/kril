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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Rank
import app.coredump.core.Tier
import app.coredump.ui.C
import app.coredump.ui.Panel
import app.coredump.ui.TText
import app.coredump.ui.tierColor
import app.coredump.ui.Th

@Composable
fun ManualScreen(model: AppModel) {
    Frame(model, Th.say("man coredump", "How to play")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel(Th.say("NAME", "The idea")) {
                TText(Th.say("coredump - name the rare thing before the garbage collector finds you",
                    "Every prompt has a long list of valid answers. Name one — the rarer it is, the more it scores."), size = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Panel(Th.say("SYNOPSIS", "Rules")) {
                for (l in listOf(
                    "each run = N prompts (default 7), T seconds each (default 25).",
                    "type any valid answer. every valid answer scores.",
                    "wrong? keep typing. free retries until the clock dies.",
                    "close typo? tap the 'did you mean' chip.",
                    "not in the list? it's checked online (Wikidata) and scored by obscurity.",
                    "rarer answers score more. obvious ones barely count.",
                    "typos are forgiven by difficulty: 2 / 2 / 1 / 0 edits.",
                    "at the end: see which answers would have scored more.",
                )) TText("· $l", size = 12.sp, color = C.amber)
            }
            Spacer(Modifier.height(12.dp))
            Panel(Th.say("TIERS", "Tiers")) {
                for (t in Tier.entries.reversed()) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        TText(t.label, Modifier.width(118.dp), size = 11.sp, color = tierColor(t), weight = FontWeight.Bold)
                        TText("${t.points}", Modifier.width(34.dp), size = 11.sp, color = tierColor(t))
                        TText(t.blurb, size = 11.sp, color = C.dim)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel(Th.say("RANKS  (average points per prompt)", "Ranks (average points per prompt)")) {
                for (r in Rank.entries) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        TText("≥${r.minAvg}", Modifier.width(40.dp), size = 11.sp, color = C.dim)
                        TText(r.title, Modifier.width(110.dp), size = 11.sp, weight = FontWeight.Bold)
                        TText(r.line, size = 11.sp, color = C.dim)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel(Th.say("SOURCES", "Where the lists come from")) {
                for (l in listOf(
                    "LOCAL · hand-ranked packs shipped in the app. offline.",
                    "WIKIDATA · ranked lists. rarity = how many Wikipedias cover the answer.",
                    "WIKIPEDIA · \"List of …\" pages, ranked the same way.",
                    "REMOTE · any JSON pack URL you add in settings.",
                    "~470 lists ship inside the app. about 12 new lists are collected each day; tap FETCH MORE LISTS for more.",
                )) TText(l, size = 11.sp, color = C.amber)
            }
            Spacer(Modifier.height(12.dp))
            Panel("1v1") {
                for (l in listOf(
                    "host: LINK 1v1 → HOST. a QR sigil + room code appear.",
                    "peer: LINK 1v1 → JOIN → SCAN SIGIL or type the room code.",
                    "same prompts, same clock. the rarer answer takes the round.",
                    "ONLINE RELAY works on any network (Android ↔ iPhone, LiveContainer).",
                    "DIRECT WI-FI needs both on one network that allows device-to-device traffic.",
                )) TText("· $l", size = 12.sp, color = C.amber)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
