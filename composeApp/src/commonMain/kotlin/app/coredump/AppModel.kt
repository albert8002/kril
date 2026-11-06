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
import app.coredump.game.GuestJoin
import app.coredump.game.HostLobby
import app.coredump.game.MatchSession
import app.coredump.game.SoloSession
import app.coredump.ui.Fx
import kotlinx.coroutines.CoroutineScope
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
}

@Stable
class AppModel(val scope: CoroutineScope) {
    val fx = Fx()
    val bank = QuestionBank(scope)
    var settings by mutableStateOf(SettingsStore.load())
        private set
    var screen by mutableStateOf<Screen>(Screen.Boot)
        private set
    var toast by mutableStateOf<String?>(null)
    private val stack = ArrayList<Screen>()

    var hostLobby: HostLobby? = null
        private set
    var guestJoin: GuestJoin? = null
        private set

    init { History.load() }

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
        if (cur is Screen.Play) { cur.session.quit(); closeLobbies(); home(); return true }
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
    }

    fun pick(cfg: GameConfig): List<Prompt> =
        PromptPicker.pick(bank.all(), cfg, History.recent.toSet(), Random.Default)

    fun startSolo() {
        val cfg = settings.gameConfig()
        val prompts = pick(cfg)
        if (prompts.isEmpty()) { toast = "no prompts match. widen topics."; return }
        val s = SoloSession(prompts, cfg, settings.handle, scope)
        s.start()
        go(Screen.Play(s), push = false)
    }

    fun replay(prev: MatchSession) {
        if (prev.isMulti) { home(); return }
        stack.clear(); screen = Screen.Menu
        startSolo()
    }

    fun openHost(): HostLobby {
        guestJoin?.cancel(); guestJoin = null
        val l = hostLobby ?: HostLobby(scope, settings.handle).also { hostLobby = it }
        return l
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
        val s = l.begin(prompts, cfg) ?: run { toast = "no peer linked yet."; return }
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
