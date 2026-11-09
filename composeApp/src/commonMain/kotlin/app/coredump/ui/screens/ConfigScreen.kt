package app.coredump.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.core.Difficulty
import app.coredump.core.SourceKind
import app.coredump.data.SyncState
import app.coredump.platform.Platform
import app.coredump.ui.C
import app.coredump.ui.Choice
import app.coredump.ui.KV
import app.coredump.ui.Panel
import app.coredump.ui.Stepper
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.TermInput
import app.coredump.ui.Toggle
import app.coredump.ui.Th
import app.coredump.ui.TLink
import app.coredump.Screen
import app.coredump.platform.CrashLog
import app.coredump.platform.shareText

@Composable
fun ConfigScreen(model: AppModel) {
    val s = model.settings
    val bank by model.bank.state.collectAsState()
    var handle by remember { mutableStateOf(s.handle) }
    var urls by remember { mutableStateOf(s.remoteUrls.joinToString("\n")) }
    var secret by remember { mutableStateOf(false) }
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }

    Frame(model, Th.say("vim ~/.coredumprc", "Settings")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel(Th.say("IDENTITY", "Name")) {
                TermInput(
                    handle,
                    { v -> handle = v.uppercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(14) },
                    Modifier.fillMaxWidth(), prompt = "handle", size = 15.sp,
                    onSubmit = { model.update { it.copy(handle = handle.ifBlank { "ANON" }) } },
                )
                Spacer(Modifier.height(6.dp))
                TButton(Th.say("SAVE HANDLE", "Save name"), { model.update { it.copy(handle = handle.ifBlank { "ANON" }) }; model.toast = "handle set: ${handle.ifBlank { "ANON" }}" },
                    Modifier.fillMaxWidth(), textSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("DEFAULT RUN", "Default run")) {
                Stepper("prompts per run", s.rounds, { v -> model.update { it.copy(rounds = v) } }, 1..50)
                TText("time per prompt", size = 13.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                Choice(listOf(10, 15, 25, 40, 60, 90, 0), s.seconds, { fmtSeconds(it) }) { v -> model.update { it.copy(seconds = v) } }
                Spacer(Modifier.height(10.dp))
                TText("difficulty", size = 13.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                Choice(Difficulty.entries, s.difficulty, { it.label }) { v -> model.update { it.copy(difficulty = v) } }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("LIBRARY", "Collected lists")) {
                val col = bank.collect
                KV("collected lists on this device", "${col.library}")
                KV("last batch", if (col.lastAt == 0L) "never" else "${((Platform.epochMillis() - col.lastAt) / 3_600_000L)} h ago")
                if (col.message.isNotEmpty()) TText(col.message, size = 11.sp, color = C.dim)
                Spacer(Modifier.height(8.dp))
                TButton(if (col.running) "collecting ${col.done}/${col.total}…" else Th.say("FETCH MORE LISTS", "Fetch more lists"),
                    { model.go(Screen.Fetch) }, Modifier.fillMaxWidth(), textSize = 12.sp)
                TText("about ${app.coredump.data.QuestionBank.DAILY_BATCH} new lists are added automatically once a day while the app is used.", size = 10.sp, color = C.dim)
                Spacer(Modifier.height(8.dp))
                KV("answers checked online (cached)", "${app.coredump.data.VerifyCache.size()}")
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("DISCARDED", "Discarded questions")) {
                val n = model.discarded.size
                KV(Th.say("rm'd prompts", "discarded on this device"), "$n")
                TText("discarded questions never come up again. collected ones are also deleted from the device.", size = 10.sp, color = C.dim)
                if (n > 0) {
                    Spacer(Modifier.height(8.dp))
                    TButton(Th.say("RESTORE ALL", "Restore all"), { model.restoreDiscards() }, Modifier.fillMaxWidth(), textSize = 12.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("SOURCES", "Sources")) {
                Toggle("bundled packs", true, {}, "always on · offline")
                Toggle("collect new Wikidata lists", s.useWikidata, { v -> model.update { it.copy(useWikidata = v) } },
                    "daily batch + FETCH MORE · ranked lists also ship inside the app")
                Toggle("search answers online", s.verifyOnline, { v -> model.update { it.copy(verifyOnline = v) } },
                    "not in the list? look it up on Wikipedia / Wikidata: other names count, and unlisted answers that fit score by fame")
                Toggle("share lists in 1v1", s.shareLists, { v -> model.update { it.copy(shareLists = v) } },
                    "swap collected lists with the other player in the background")
                Toggle("wikipedia list scraping", s.useWikipedia, { v -> model.update { it.copy(useWikipedia = v) } },
                    "\"List of …\" pages · rarity = wikipedia language editions")
                Spacer(Modifier.height(8.dp))
                TText("remote pack URLs (one per line, JSON pack or index)", size = 11.sp, color = C.dim)
                Spacer(Modifier.height(4.dp))
                TermInput(urls, { urls = it }, Modifier.fillMaxWidth(), prompt = "url", size = 11.sp,
                    singleLine = false, imeAction = ImeAction.Default, placeholder = "https://…/packs/index.json")
                Spacer(Modifier.height(6.dp))
                TButton(Th.say("SAVE URLS", "Save URLs"), {
                    val list = urls.split('\n', ',', ' ').map { it.trim() }.filter { it.startsWith("http") }
                    model.update { it.copy(remoteUrls = list) }
                    model.toast = "${list.size} remote source(s)"
                }, Modifier.fillMaxWidth(), textSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                for (k in SourceKind.entries) {
                    val st = bank.sources.getValue(k)
                    KV(k.tag, when (st.state) {
                        SyncState.SYNCING -> "syncing ${st.done}/${st.total}"
                        SyncState.OK -> "${st.count} prompts ✓ ${st.detail}"
                        SyncState.ERROR -> "failed · ${st.count} kept"
                        SyncState.OFF -> "off"
                        SyncState.IDLE -> "${st.count} prompts"
                    }, if (st.state == SyncState.ERROR) C.blood else C.amber)
                    if (st.state == SyncState.ERROR && st.detail.isNotEmpty()) TText("  ${st.detail}", size = 10.sp, color = C.blood)
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    TButton(Th.say("SYNC NOW", "Sync now"), { model.bank.sync(model.settings, force = true) }, Modifier.weight(1f), textSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    TButton(Th.say("PURGE CACHE", "Clear cache"), { model.bank.purgeCaches(); model.toast = "cache cleared. restart to reload." },
                        Modifier.weight(1f), textSize = 12.sp, color = C.blood)
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("DISPLAY", "Look")) {
                KV("theme", "${Th.style.label} · ${Th.pal.name}")
                TLink(Th.say("change theme, colours, text size", "Change theme, colours and text size"), { model.go(Screen.Appearance) }, color = C.hot)
                TLink(Th.say("own tier & rank names, colours, icons", "Customize tiers and ranks"), { model.go(Screen.Looks) }, color = C.hot)
                Toggle("haptics", s.haptics, { v -> model.update { it.copy(haptics = v) } })
                if (secret || s.spicyNames) {
                    Toggle(Th.say("spicy rank names", "Spicy rank names"), s.spicyNames, { v -> model.update { it.copy(spicyNames = v) } },
                        "crude names for answer tiers and end-of-run ranks")
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("AUDIO", "Sound")) {
                Toggle(Th.say("sound effects", "Sound effects"), s.sound, { v ->
                    model.update { it.copy(sound = v) }
                    if (v) app.coredump.ui.Sfx.play(app.coredump.ui.Cue.HIT, 4)
                }, "every theme has its own sounds")
                if (s.sound) {
                    Spacer(Modifier.height(6.dp))
                    TText("volume", size = 13.sp, color = C.dim)
                    Spacer(Modifier.height(6.dp))
                    val levels = listOf(0.25f, 0.5f, 0.75f, 1f)
                    Choice(levels, levels.minByOrNull { kotlin.math.abs(it - s.volume) } ?: 0.75f, { "${(it * 100).toInt()}%" }) { v ->
                        model.update { it.copy(volume = v) }
                        app.coredump.ui.Sfx.play(app.coredump.ui.Cue.HIT, 3)
                    }
                    Spacer(Modifier.height(6.dp))
                    TLink(Th.say("play a sample", "Play a sample"), { app.coredump.ui.Sfx.play(app.coredump.ui.Cue.HIT, 6) }, color = C.hot)
                }
                Spacer(Modifier.height(10.dp))
                Toggle(Th.say("music", "Music"), s.music, { v -> model.update { it.copy(music = v) } },
                    "one theme in 7/4, arranged for every look")
                if (s.music) {
                    Spacer(Modifier.height(6.dp))
                    TText(Th.say("music volume", "Music volume"), size = 13.sp, color = C.dim)
                    Spacer(Modifier.height(6.dp))
                    val mLevels = listOf(0.25f, 0.5f, 0.75f, 1f)
                    Choice(mLevels, mLevels.minByOrNull { kotlin.math.abs(it - s.musicVolume) } ?: 0.5f, { "${(it * 100).toInt()}%" }) { v ->
                        model.update { it.copy(musicVolume = v) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Th.say("CRASH REPORTS", "Crash reports"), color = if (model.lastCrash != null) C.blood else C.line) {
                val crash = model.lastCrash
                val notes = remember(crash) { CrashLog.notes() }
                if (crash == null && notes == null) {
                    TText("no crashes recorded.", size = 12.sp, color = C.dim)
                } else {
                    if (crash != null) {
                        TText("last crash:", size = 11.sp, color = C.blood, weight = androidx.compose.ui.text.font.FontWeight.Bold)
                        TText(crash.lines().take(14).joinToString("\n"), size = 9.sp, color = C.dim)
                    } else {
                        TText("no crash · ${notes?.lines()?.count { it.isNotBlank() && it.first().isDigit() } ?: 0} handled errors logged", size = 11.sp, color = C.dim)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TButton("SHARE REPORT", { shareText(crash ?: notes.orEmpty()) }, Modifier.weight(1f), textSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        TButton("CLEAR", { CrashLog.clear(); model.lastCrash = null }, Modifier.weight(1f), textSize = 12.sp, color = C.blood)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            TText("CORE DUMP 1.1 · ${Platform.name} · fonts: DejaVu, Lora, Poppins, Inter (free licenses)",
                Modifier.clickable(remember { MutableInteractionSource() }, null) {
                    // hidden: five quick taps reveal the spicy-names switch
                    val now = Platform.epochMillis()
                    taps = if (now - lastTap < 1500) taps + 1 else 1
                    lastTap = now
                    if (taps >= 5 && !secret) { secret = true; model.toast = "hidden setting unlocked (Look)" }
                }, size = 10.sp, color = C.dim)
            TText("lists from Wikidata (CC0) and Wikipedia (CC BY-SA). no data leaves the device except those queries" +
                (if (s.shareLists) " and, in 1v1, collected lists swapped with the other player." else "."),
                size = 10.sp, color = C.dim)
            Spacer(Modifier.height(20.dp))
        }
    }
}
