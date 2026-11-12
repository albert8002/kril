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
import app.coredump.ui.TierBadge
import app.coredump.ui.rankGradient
import app.coredump.core.Spam
import androidx.compose.foundation.layout.Box
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
                    "close to an answer? the app asks: submit the suggestion, or search what you typed.",
                    "\"did you mean\" only offers names that hold every word you typed (or a typo of it). a longer name only from 3 typed words on: \"robin\" never brings up \"The Batman and Robin Adventures\", \"batman and robin\" does.",
                    "after each prompt: the full list of answers, by tier.",
                    "music: one theme in 7/4 (felt as 4 + 3) with a returning hook, arranged 12 ways, one per look; terminal plays Deep Sea. pick a song or match the look in settings → sound.",
                    "score runs: points travel in each look's own world (toward the kernel, deeper down, past stations …), speeding up, counting and slowing down. tap to skip; off in appearance.",
                    "ADHD-fy: a switch on every look's menu (and in appearance) for more motion, more colour and flourishes everywhere. each look keeps its own setting.",
                    "people: a surname is enough, spelt exactly (\"einstein\", \"van gogh\"; not \"einstien\"). typos only in full names.",
                    "\"river\", \"lake\", \"mount\", word order, accents and plurals don't matter.",
                    "not in the list? it's checked online (Wikidata) and scored by measured fame: Wikipedia editions, page views, mentions, plus fans (Deezer) for music, readers (Open Library) for books, popularity (TVmaze) for TV. famous → 30, known → 60, niche → 85.",
                    "guesses cost: a surname many people share, or a very common word, scores one step lower. results are cached.",
                    "rarer answers score more. obvious ones barely count.",
                    ".reversed() (setup): the other way round, the most common answer scores most (\"name an animal\": dog).",
                    "stuck? skip the prompt (0 points).",
                    "key-mashing (\"asdfg\", \"jjjj\") costs ${Spam.PENALTY} points each, up to ${Spam.PENALTY * Spam.MAX_PER_ROUND} per prompt.",
                    "typos are forgiven by difficulty: 2 / 2 / 1 / 0 edits, and by length: none up to 4 letters, 1 up to 7, 2 from 8.",
                    "a prompt you've seen won't come back for a few days (setup → repeats).",
                    "niche lists (players from one country, sub-genres…) are off unless you turn them on.",
                    "at the end: see which answers would have scored more.",
                )) TText("· $l", size = 12.sp, color = C.amber)
            }
            Spacer(Modifier.height(12.dp))
            Panel(Th.say("TIERS", "Tiers")) {
                for (t in Tier.entries.reversed()) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.width(170.dp)) { TierBadge(t, 10.sp, animate = false) }
                        TText("${t.points}", Modifier.width(34.dp), size = 11.sp, color = tierColor(t), weight = FontWeight.Bold)
                        TText(t.blurb, size = 11.sp, color = C.dim)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel(Th.say("RANKS  (average points per prompt)", "Ranks (average points per prompt)")) {
                for (r in Rank.entries) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        TText("≥${r.minAvg}", Modifier.width(40.dp), size = 11.sp, color = C.dim)
                        TText(r.title, Modifier.width(140.dp), size = 11.sp, weight = FontWeight.Bold,
                            color = if (Th.style == app.coredump.ui.Style.PIXEL) C.amber else rankGradient(r)[1])
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
                    "MORE · rhymes, synonyms and word endings (Datamuse), top songs per artist (Deezer), books per subject (Open Library), Pokémon per type (PokéAPI), famous people by job and nationality (Wikidata).",
                    "hundreds of lists ship inside the app. about 30 more are downloaded each day; tap FETCH MORE LISTS for more.",
                )) TText(l, size = 11.sp, color = C.amber)
            }
            Spacer(Modifier.height(12.dp))
            Panel("1v1") {
                for (l in listOf(
                    "host: LINK 1v1 → HOST. a sigil + room code appear.",
                    "peer: LINK 1v1 → JOIN → SCAN SIGIL or type the room code.",
                    "every look has its own sigil (a memory map, a halftone, a neon sun, a honeycomb, a pixel gem, a starburst, a ball of glowing dots, a metro map, a roulette wheel, a chat spiral, a tile board, a patch of night sky); any phone reads any of them. direct Wi-Fi games show a QR code instead.",
                    "same prompts, same clock. the rarer answer takes the round.",
                    "ONLINE RELAY works on any network (Android ↔ iPhone, LiveContainer).",
                    "DIRECT WI-FI needs both on one network that allows device-to-device traffic.",
                    "your room code never changes: send it, leave the app, come back. the host screen reconnects with the same code.",
                    "FRIENDS (main menu, also a tab here): add people by code alone (they get a friend request), see their rating, your record and streak against each, invite them to a 1v1 or a party, or join them, in one tap.",
                    "speed penalty (host setting): once one player answers, the other loses ${app.coredump.core.SpeedTax.PER_SECOND} points a second (at most ${app.coredump.core.SpeedTax.MAX}) until they answer or skip.",
                    "RANKED tab: a random online opponent near your rating, fixed rules (5 prompts, 25 s, speed penalty on). wins raise your rating (Elo); friendly 1v1s count half.",
                )) TText("· $l", size = 12.sp, color = C.amber)
            }
            Spacer(Modifier.height(12.dp))
            Panel("MELTDOWN") {
                for (l in listOf(
                    "battle royale for 2–8 players: bots offline, or friends online (HOST PARTY / JOIN PARTY, same code as 1v1).",
                    "one player holds the core. name an answer nobody said yet this round: the core passes on.",
                    "a wrong or repeated answer does not pass it. keep trying: a miss shows \"did you mean\"; answers not on the list are checked online by the host.",
                    "every turn has a countdown shown on the core (the host sets 5–20 s and the topics). still holding it at 0: it blows, −1 heart, new category.",
                    "type ahead while you wait: enter queues it, it goes out the moment the core reaches you. × clears it.",
                    "rarer answers fill your meter faster (common +1 … legendary +6). full meter (10): +1 heart, 3 at most.",
                    "everyone starts with 2 hearts. 0 hearts: out. the last one standing wins.",
                )) TText("· $l", size = 12.sp, color = C.amber)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
