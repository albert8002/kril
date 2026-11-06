package app.coredump

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.coredump.core.GameConfig
import app.coredump.core.Prompt
import app.coredump.core.PromptPicker
import app.coredump.data.History
import app.coredump.data.QuestionBank
import app.coredump.data.Settings
import app.coredump.data.SettingsStore
import app.coredump.game.Arbiter
import app.coredump.game.GuestJoin
import app.coredump.game.HostLobby
import app.coredump.game.LinkMode
import app.coredump.game.MatchSession
import app.coredump.game.SoloSession
import app.coredump.ui.Fx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

sealed interface Screen {
    data object Boot : Screen
    data object Menu : Screen
    data class Setup(val forHost: Boolean) : Screen
    data class Play(val session: MatchSession) : Screen
    data class Summary(val session: MatchSession) : Screen
    data object Browse : Screen
    data class Dump(val promptId: String) : Screen
    data object Config : Screen
    data object Lobby : Screen
    data object Manual : Screen
    data object Stats : Screen
    data object Appearance : Screen
}

/** Daily run number: days since 2026-01-01 UTC (epoch day 20454). */
fun dailyNumber(epochMs: Long): Int = (epochMs / 86_400_000L - 20454L).toInt().coerceAtLeast(0)

@Stable
class AppModel(val scope: CoroutineScope) {
    val fx = Fx()
    val bank = QuestionBank(scope)
    var settings by mutableStateOf(SettingsStore.load())
        private set
    var screen by mutableStateOf<Screen>(Screen.Boot)
        private set
    var toast by mutableStateOf<String?>(null)
    /** Crash report from the previous session (shown once on the menu, kept in config until cleared). */
    var lastCrash by mutableStateOf(app.coredump.platform.CrashLog.last())
    /** Back pressed during a run: ask before killing it. */
    var confirmQuit by mutableStateOf(false)
    private val stack = ArrayList<Screen>()

    /** Prompt ids discarded on this device (mirrors [app.coredump.data.Discards] for the UI). */
    var discarded by mutableStateOf<Set<String>>(emptySet())
        private set
    private val removed = HashMap<String, Prompt>()
    private val discardJobs = HashMap<String, Job>()

    var hostLobby: HostLobby? = null
        private set
    var guestJoin: GuestJoin? = null
        private set

    init {
        History.load()
        app.coredump.ui.Th.set(app.coredump.ui.Style.byName(settings.theme), settings.palette)
    }

    fun go(s: Screen, push: Boolean = true) {
        if (push && screen != Screen.Boot) stack += screen
        screen = s
        fx.glitch()
    }

    fun home() {
        stack.clear()
        screen = Screen.Menu
        fx.glitch()
    }

    fun back(): Boolean {
        val cur = screen
        if (cur is Screen.Play) {
            val st = cur.session.state.value
            if (st.phase == app.coredump.game.Phase.ERROR || st.phase == app.coredump.game.Phase.OVER) {
                cur.session.quit(); closeLobbies(); home()
            } else {
                cur.session.pause()
                confirmQuit = true
            }
            return true
        }
        if (cur is Screen.Summary || cur is Screen.Lobby) { closeLobbies() }
        if (stack.isEmpty()) return false
        screen = stack.removeAt(stack.size - 1)
        fx.glitch()
        return true
    }

    fun update(f: (Settings) -> Settings) {
        val old = settings
        settings = f(settings)
        SettingsStore.save(settings)
        if (old.useWikidata != settings.useWikidata || old.useWikipedia != settings.useWikipedia ||
            old.remoteUrls != settings.remoteUrls) bank.sync(settings, force = false)
        if (old.theme != settings.theme || old.palette != settings.palette) {
            app.coredump.ui.Th.set(app.coredump.ui.Style.byName(settings.theme), settings.palette)
        }
    }

    fun isDiscarded(id: String) = id in discarded

    /** Read the saved list once the bank has loaded it. */
    fun syncDiscards() { discarded = app.coredump.data.Discards.ids }

    /** Never show this prompt again: hidden for good; a collected list is deleted from the device. */
    fun discard(id: String) {
        if (id in discarded) return
        discarded = discarded + id
        discardJobs[id] = scope.launch { bank.discard(id)?.let { removed[id] = it } }
    }

    fun undoDiscard(id: String) {
        if (id !in discarded) return
        discarded = discarded - id
        val job = discardJobs.remove(id)
        scope.launch {
            job?.join()
            val p = removed.remove(id)
            if (p != null) bank.restore(p) else app.coredump.data.Discards.remove(id)
        }
    }

    /** Forget every discard. Built-in lists return on the next start; deleted collected lists can be fetched again. */
    fun restoreDiscards() {
        val back = removed.values.toList()
        removed.clear(); discardJobs.clear()
        bank.clearDiscards()
        discarded = emptySet()
        scope.launch { for (p in back) bank.restore(p) }
        toast = app.coredump.ui.Th.say("discard list wiped. built-ins return on next boot.", "Restored. Built-in lists return after a restart.")
    }

    fun setTheme(style: app.coredump.ui.Style, paletteId: String) =
        update { it.copy(theme = style.name, palette = paletteId) }

    fun pick(cfg: GameConfig): List<Prompt> =
        PromptPicker.pick(bank.all(), cfg, History.recent.toSet(), Random.Default)

    fun arbiter(cfg: GameConfig) = Arbiter(bank, cfg.difficulty.maxEdit, settings.verifyOnline)

    fun startSolo() {
        val cfg = settings.gameConfig()
        val prompts = pick(cfg)
        if (prompts.isEmpty()) { toast = "no prompts match. widen topics."; return }
        val s = SoloSession(prompts, cfg, settings.handle, scope, arbiter(cfg))
        s.start()
        go(Screen.Play(s), push = false)
    }

    /** Same 7 hand-ranked prompts for everyone today (seeded by the date). */
    fun dailyPrompts(n: Int): List<Prompt> {
        val pool = bank.all()
            .filter { it.source == app.coredump.core.SourceKind.BUNDLED && it.size >= 24 }
            .sortedBy { it.id }
        val rnd = Random(n * 7919L + 17L)
        val out = ArrayList<Prompt>()
        val topics = HashSet<String>()
        for (p in pool.shuffled(rnd)) {
            if (out.size >= 7) break
            if (topics.add(p.topic) || topics.size >= 7) out += p
        }
        return out
    }

    fun startDaily() {
        val n = dailyNumber(app.coredump.platform.Platform.epochMillis())
        val prompts = dailyPrompts(n)
        if (prompts.isEmpty()) { toast = "daily run unavailable."; return }
        val cfg = GameConfig(prompts.size, 25, app.coredump.core.Difficulty.NORMAL, emptySet())
        val s = SoloSession(prompts, cfg, settings.handle, scope, arbiter(cfg), daily = n)
        s.start()
        go(Screen.Play(s), push = false)
    }

    fun abortRun(s: MatchSession) {
        confirmQuit = false
        s.quit(); closeLobbies(); home()
    }

    fun replay(prev: MatchSession) {
        if (prev.isMulti || prev.state.value.daily != null) { home(); return }
        stack.clear(); screen = Screen.Menu
        startSolo()
    }

    fun openHost(mode: LinkMode): HostLobby {
        guestJoin?.cancel(); guestJoin = null
        val cur = hostLobby
        if (cur != null && cur.mode == mode) return cur
        cur?.close()
        return HostLobby(scope, settings.handle, mode).also { hostLobby = it }
    }

    fun openGuest(): GuestJoin {
        hostLobby?.close(); hostLobby = null
        return guestJoin ?: GuestJoin(scope, settings.handle).also { guestJoin = it }
    }

    fun startHosted() {
        val l = hostLobby ?: return
        val cfg = settings.gameConfig()
        val prompts = pick(cfg)
        if (prompts.isEmpty()) { toast = "no prompts match. widen topics."; return }
        val s = l.begin(prompts, cfg, arbiter(cfg)) ?: run { toast = "no peer linked yet."; return }
        hostLobby = null
        go(Screen.Play(s), push = false)
    }

    fun joined(s: MatchSession) {
        guestJoin = null
        go(Screen.Play(s), push = false)
    }

    fun closeLobbies() {
        hostLobby?.close(); hostLobby = null
        guestJoin?.cancel(); guestJoin = null
    }

    fun finished(s: MatchSession) {
        stack.clear()
        stack += Screen.Menu
        screen = Screen.Summary(s)
        fx.glitch()
    }
}
