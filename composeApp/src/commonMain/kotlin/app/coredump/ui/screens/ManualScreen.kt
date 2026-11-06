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

@Composable
fun ManualScreen(model: AppModel) {
    Frame(model, "man coredump") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel("NAME") {
                TText("coredump - name the rare thing before the garbage collector finds you", size = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Panel("SYNOPSIS") {
                for (l in listOf(
                    "each run = N prompts (default 7), T seconds each (default 25).",
                    "type any valid answer. every valid answer scores.",
                    "rarer answers score more. obvious ones barely count.",
                    "1 point = 10 frames of stack depth. go deep.",
                    "typos are forgiven by difficulty: 2 / 2 / 1 / 0 edits.",
                    "at the end: see what would have gone deeper.",
                )) TText("· $l", size = 12.sp, color = C.amber)
            }
            Spacer(Modifier.height(12.dp))
            Panel("TIERS") {
                for (t in Tier.entries.reversed()) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        TText(t.label.padEnd(12), Modifier.width(118.dp), size = 11.sp, color = tierColor(t), weight = FontWeight.Bold)
                        TText("${t.points}".padStart(3) + "  ", size = 11.sp, color = tierColor(t))
                        TText(t.blurb, size = 11.sp, color = C.dim)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel("RANKS  (average points per prompt)") {
                for (r in Rank.entries) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        TText("≥${r.minAvg}".padEnd(5), size = 11.sp, color = C.dim)
                        TText(r.title.padEnd(12), Modifier.width(110.dp), size = 11.sp, weight = FontWeight.Bold)
                        TText(r.line, size = 11.sp, color = C.dim)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel("SOURCES") {
                for (l in listOf(
                    "LOCAL      hand-ranked packs shipped in the app. offline.",
                    "WIKIDATA   live SPARQL lists. rarity = how many wikipedias cover it.",
                    "WIKIPEDIA  scraped \"List of …\" pages. rarity = page views, 30 days.",
                    "REMOTE     any JSON pack URL you add in config.",
                    "all lists + rankings are prefetched at boot and cached for a week.",
                )) TText(l, size = 11.sp, color = C.amber)
            }
            Spacer(Modifier.height(12.dp))
            Panel("1v1") {
                for (l in listOf(
                    "host: LINK 1v1 → HOST. a QR sigil appears.",
                    "peer: LINK 1v1 → JOIN → SCAN SIGIL (or dial ip:port pin).",
                    "same prompts, same clock. deeper answer takes the round.",
                    "runs over local Wi-Fi or a phone hotspot. Android ↔ iPhone works.",
                )) TText("· $l", size = 12.sp, color = C.amber)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
