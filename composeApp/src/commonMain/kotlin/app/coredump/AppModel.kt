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
    data object Fetch : Screen
    /** Own names, colours and icons for tiers and ranks. */
    data object Looks : Screen
    /** MELTDOWN setup: bots, host a party, join one. */
    data object Meltdown : Screen
    /** A MELTDOWN game in progress (or just over). */
    data class Melt(val session: app.coredump.game.MeltSession) : Screen
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
    var partyHost by mutableStateOf<app.coredump.game.PartyHost?>(null)
        private set
    var partyGuest by mutableStateOf<app.coredump.game.PartyGuest?>(null)
        private set
    /** Bots and bot level of the last offline MELTDOWN game (for "again"). */
    var lastMelt = 3 to 1
        private set
    /** Back pressed during a MELTDOWN game: ask before leaving. */
    var confirmLeaveMelt by mutableStateOf(false)
    private var partyWatch: Job? = null

    init {
        History.load()
        app.coredump.ui.Th.set(app.coredump.ui.Style.byName(settings.theme), settings.palette)
        app.coredump.core.Names.spicy = settings.spicyNames
        app.coredump.core.Names.custom = settings.custom
        app.coredump.ui.CustomLooks.changed(settings.custom)
        app.coredump.ui.Sfx.configure(settings.sound, settings.volume)
        app.coredump.ui.Music.configure(settings.music, settings.musicVolume,
            app.coredump.ui.Style.byName(if (settings.song == "MATCH") settings.theme else settings.song))
    }

    fun go(s: Screen, push: Boolean = true) {
        if (push && screen != Screen.Boot) stack += screen
        if (screen != Screen.Boot) app.coredump.ui.Sfx.play(app.coredump.ui.Cue.NAV)
        screen = s
        fx.glitch()
    }

    fun home() {
        stack.clear()
        app.coredump.ui.Sfx.play(app.coredump.ui.Cue.NAV)
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
        if (cur is Screen.Melt) {
            if (cur.session.state.value.phase == app.coredump.game.MeltPhase.OVER) leaveMelt(cur.session) else confirmLeaveMelt = true
            return true
        }
        if (cur is Screen.Summary || cur is Screen.Lobby) { closeLobbies() }
        if (cur is Screen.Meltdown) { closeParty() }
        if (stack.isEmpty()) return false
        screen = stack.removeAt(stack.size - 1)
        app.coredump.ui.Sfx.play(app.coredump.ui.Cue.NAV)
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
        app.coredump.core.Names.spicy = settings.spicyNames
        app.coredump.core.Names.custom = settings.custom
        app.coredump.ui.CustomLooks.changed(settings.custom)
        app.coredump.ui.Sfx.configure(settings.sound, settings.volume)
        app.coredump.ui.Music.configure(settings.music, settings.musicVolume,
            app.coredump.ui.Style.byName(if (settings.song == "MATCH") settings.theme else settings.song))
    }

    /** 1v1 links swap collected lists in the background (when allowed in settings). */
    private fun shareWrap(l: app.coredump.net.PeerLink): app.coredump.net.PeerLink =
        app.coredump.game.SharingLink(l, bank, scope, settings.shareLists)

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

    /** Prompts for a game: not shown in the last [Settings.cooldownDays] days when possible. */
    fun pick(cfg: GameConfig): List<Prompt> =
        PromptPicker.pick(bank.all(), cfg, History.recent.toSet(), Random.Default, History.seen,
            settings.cooldownDays * 86_400_000L, app.coredump.platform.Platform.epochMillis())

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
            .filter { it.source == app.coredump.core.SourceKind.BUNDLED && it.size >= 24 && !it.niche }
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
        return HostLobby(scope, settings.handle, mode, ::shareWrap, myRoom(), settings.roomBroker,
            onBroker = { b -> if (b != settings.roomBroker) update { it.copy(roomBroker = b) } }).also { hostLobby = it }
    }

    fun openGuest(): GuestJoin {
        hostLobby?.close(); hostLobby = null
        return guestJoin ?: GuestJoin(scope, settings.handle, ::shareWrap, myRoom()).also { guestJoin = it }
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

    /** Adds (or renames) a friend by room code. Returns false when the code is not a room code. */
    fun addFriend(name: String, code: String): Boolean {
        val c = code.uppercase().filter { it in app.coredump.net.JoinTicket.ROOM_ALPHABET }
        if (c.length != app.coredump.net.JoinTicket.ROOM_LEN) { toast = "a friend code has 8 letters/digits (e.g. K7Q2-M9XA)"; return false }
        if (c == settings.room) { toast = "that is your own code"; return false }
        val n = name.trim().ifBlank { "FRIEND" }.take(24)
        update { s -> s.copy(friends = s.friends.filter { it.code != c } + app.coredump.data.Friend(n, c, app.coredump.platform.Platform.epochMillis())) }
        toast = "$n added to friends"
        return true
    }

    fun removeFriend(f: app.coredump.data.Friend) {
        update { s -> s.copy(friends = s.friends.filter { it != f }) }
    }

    fun isFriend(code: String?): Boolean = code != null && settings.friends.any { it.code == code }

    /** Joins a friend's room (they must be on their host screen). */
    fun joinFriend(f: app.coredump.data.Friend) {
        if (f.code.length != app.coredump.net.JoinTicket.ROOM_LEN) { toast = "no code for ${f.name} yet"; return }
        openGuest().connect(app.coredump.net.JoinTicket("", 0, "", f.name, f.code, 0)) { s -> joined(s) }
    }

    /** My own room code (made the first time it is needed, then kept for good). */
    fun myRoom(): String {
        if (settings.room.length != app.coredump.net.JoinTicket.ROOM_LEN) update { it.copy(room = app.coredump.net.JoinTicket.newRoom()) }
        return settings.room
    }

    // ------------------------------------------------------------------ MELTDOWN

    private val meltSeen = HashSet<String>()

    /** Next MELTDOWN category: a big list (60+ answers) nobody saw in this game; never a niche one. */
    fun meltPrompt(): Prompt? {
        val all = bank.all().filter { !it.niche && it.id !in discarded }
        val big = all.filter { it.size >= 60 }.ifEmpty { all.filter { it.size >= 30 } }
        val fresh = big.filter { it.id !in meltSeen }.ifEmpty { meltSeen.clear(); big }
        val p = fresh.randomOrNull() ?: return null
        meltSeen += p.id
        return p
    }

    private fun me() = app.coredump.game.MeltPlayer(app.coredump.game.Meltdown.newId(), settings.handle.ifBlank { "YOU" }.take(16), bot = false)

    /** Offline game against [bots] bots (level 0 easy … 2 hard). */
    fun startMeltBots(bots: Int, level: Int) {
        closeParty()
        lastMelt = bots to level
        meltSeen.clear()
        val me = me()
        val used = mutableSetOf(me.name)
        val rivals = app.coredump.game.Meltdown.botNames.filter { it !in used }.shuffled().take(bots.coerceIn(1, app.coredump.game.Meltdown.MAX_PLAYERS - 1))
            .map { app.coredump.game.MeltPlayer("bot-" + it.lowercase(), it, bot = true) }
        val game = app.coredump.game.MeltdownGame(scope, (listOf(me) + rivals).shuffled(), ::meltPrompt, level)
        val s = app.coredump.game.LocalMelt(scope, me.id, game)
        game.start()
        go(Screen.Melt(s))
    }

    /** Opens my party room (same code as 1v1: friends join with it). */
    fun openPartyHost(): app.coredump.game.PartyHost {
        partyGuest?.leave(); partyGuest = null
        closeLobbies()
        partyHost?.let { return it }
        return app.coredump.game.PartyHost(scope, myRoom(), me(), settings.roomBroker,
            onBroker = { b -> if (b != settings.roomBroker) update { it.copy(roomBroker = b) } }).also { partyHost = it; it.open() }
    }

    fun startParty(bots: Int, level: Int) {
        val h = partyHost ?: return
        if (h.lobby.value.players.size + bots < 2) { toast = "need at least 2 players: wait for a friend or add bots"; return }
        meltSeen.clear()
        val myId = h.lobby.value.players.first().id
        val game = h.start(bots, level, ::meltPrompt)
        val s = app.coredump.game.LocalMelt(scope, myId, game, online = true, onLeave = { h.close(); if (partyHost === h) partyHost = null })
        go(Screen.Melt(s))
    }

    /** Joins a party by code / QR text / friend code; moves to the arena when the host starts. */
    fun joinParty(raw: String, name: String = "HOST") {
        val t = app.coredump.net.JoinTicket.parse(raw.replace("coredump://party?", "coredump://join?"))
        if (t?.room == null) { toast = "not a party code (8 letters/digits, e.g. K7Q2-M9XA)"; return }
        val room: String = t.room
        if (room == settings.room) { toast = "that is your own code"; return }
        partyHost?.close(); partyHost = null
        closeLobbies()
        partyGuest?.leave()
        val g = app.coredump.game.PartyGuest(scope, settings.handle.ifBlank { "GUEST" }.take(16), room, t.broker)
        partyGuest = g
        g.connect()
        partyWatch?.cancel()
        partyWatch = scope.launch {
            g.state.collect { st ->
                if (st.phase != app.coredump.game.MeltPhase.LOBBY && partyGuest === g && screen == Screen.Meltdown) {
                    if (t.hostName != "HOST") app.coredump.game.PeerBook.note(t.hostName, room)
                    go(Screen.Melt(g))
                }
            }
        }
        if (name != "HOST") toast = "calling $name…"
    }

    fun leaveMelt(s: app.coredump.game.MeltSession) {
        confirmLeaveMelt = false
        val st = s.state.value
        if (st.phase == app.coredump.game.MeltPhase.OVER) {
            val me = st.player(s.myId)
            if (me != null) update { it.copy(meltGames = it.meltGames + 1, meltWins = it.meltWins + if (me.place == 1) 1 else 0) }
        }
        s.leave()
        if (s === partyGuest) partyGuest = null
        partyWatch?.cancel(); partyWatch = null
        partyHost?.close(); partyHost = null
        // back to the MELTDOWN setup
        while (stack.isNotEmpty() && stack.last() is Screen.Melt) stack.removeAt(stack.size - 1)
        if (stack.lastOrNull() == Screen.Meltdown) { screen = stack.removeAt(stack.size - 1); fx.glitch() } else home()
    }

    /** Offline game over: same bots, new game. */
    fun meltAgain(s: app.coredump.game.MeltSession) {
        leaveMelt(s)
        startMeltBots(lastMelt.first, lastMelt.second)
    }

    fun closeParty() {
        partyWatch?.cancel(); partyWatch = null
        partyHost?.close(); partyHost = null
        partyGuest?.leave(); partyGuest = null
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
