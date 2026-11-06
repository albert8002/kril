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

@Composable
fun ConfigScreen(model: AppModel) {
    val s = model.settings
    val bank by model.bank.state.collectAsState()
    var handle by remember { mutableStateOf(s.handle) }
    var urls by remember { mutableStateOf(s.remoteUrls.joinToString("\n")) }

    Frame(model, "vim ~/.coredumprc") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Panel("IDENTITY") {
                TermInput(
                    handle,
                    { v -> handle = v.uppercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(14) },
                    Modifier.fillMaxWidth(), prompt = "handle", size = 15.sp,
                    onSubmit = { model.update { it.copy(handle = handle.ifBlank { "ANON" }) } },
                )
                Spacer(Modifier.height(6.dp))
                TButton("SAVE HANDLE", { model.update { it.copy(handle = handle.ifBlank { "ANON" }) }; model.toast = "handle set: ${handle.ifBlank { "ANON" }}" },
                    Modifier.fillMaxWidth(), textSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            Panel("DEFAULT RUN") {
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
            Panel("SOURCES") {
                Toggle("bundled packs", true, {}, "always on · offline")
                Toggle("wikidata live lists", s.useWikidata, { v -> model.update { it.copy(useWikidata = v) } },
                    "weekly refresh of 30 lists · full set ships inside the app")
                Toggle("verify unlisted answers online", s.verifyOnline, { v -> model.update { it.copy(verifyOnline = v) } },
                    "not in the list? check Wikidata · score by how many wikipedias cover it")
                Toggle("wikipedia list scraping", s.useWikipedia, { v -> model.update { it.copy(useWikipedia = v) } },
                    "\"List of …\" pages · rarity = wikipedia language editions")
                Spacer(Modifier.height(8.dp))
                TText("remote pack URLs (one per line, JSON pack or index)", size = 11.sp, color = C.dim)
                Spacer(Modifier.height(4.dp))
                TermInput(urls, { urls = it }, Modifier.fillMaxWidth(), prompt = "url", size = 11.sp,
                    singleLine = false, imeAction = ImeAction.Default, placeholder = "https://…/packs/index.json")
                Spacer(Modifier.height(6.dp))
                TButton("SAVE URLS", {
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
                    TButton("SYNC NOW", { model.bank.sync(model.settings, force = true) }, Modifier.weight(1f), textSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    TButton("PURGE CACHE", { model.bank.purgeCaches(); model.toast = "cache purged. restart to reload." },
                        Modifier.weight(1f), textSize = 12.sp, color = C.blood)
                }
            }
            Spacer(Modifier.height(14.dp))
            Panel("DISPLAY") {
                Toggle("scanlines", s.scanlines, { v -> model.update { it.copy(scanlines = v) } })
                Toggle("phosphor flicker", s.flicker, { v -> model.update { it.copy(flicker = v) } })
                Toggle("reduce motion", s.reduceMotion, { v -> model.update { it.copy(reduceMotion = v) } },
                    "no shake, tear, typewriter or rain")
                Toggle("haptics", s.haptics, { v -> model.update { it.copy(haptics = v) } })
                Spacer(Modifier.height(6.dp))
                TText("text size", size = 13.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                Choice(listOf(0.9f, 1f, 1.15f, 1.3f), s.textScale, { "${(it * 100).toInt()}%" }) { v ->
                    model.update { it.copy(textScale = v) }
                }
                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.height(6.dp))
                TText("glow", size = 13.sp, color = C.dim)
                Spacer(Modifier.height(6.dp))
                Choice(listOf(0f, 0.5f, 1f, 1.5f), s.glow, { if (it == 0f) "OFF" else "${(it * 100).toInt()}%" }) { v ->
                    model.update { it.copy(glow = v) }
                }
            }
            Spacer(Modifier.height(14.dp))
            TText("CORE_DUMP 1.0.0 · ${Platform.name} · font: DejaVu Sans Mono (free license)", size = 10.sp, color = C.line)
            TText("lists from Wikidata (CC0) and Wikipedia (CC BY-SA). no data leaves the device except those queries.",
                size = 10.sp, color = C.line)
            Spacer(Modifier.height(20.dp))
        }
    }
}
