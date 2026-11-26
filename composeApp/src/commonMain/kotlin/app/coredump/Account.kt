package app.coredump

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.coredump.data.Friend
import app.coredump.data.History
import app.coredump.data.Settings
import app.coredump.data.SettingsStore
import app.coredump.net.Cloud
import app.coredump.net.CloudLink
import app.coredump.net.CloudProfile
import app.coredump.platform.Platform
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The player's account (optional): sign-in and the sync of settings, looks, history and friends between devices
 * (phone apps and the web app). The ranked rating of a signed-in player lives on the server: it moves only when both
 * players of a ranked match sent the same result ([app.coredump.net.Cloud.reportRanked]).
 *
 * Sync: the server keeps one document per account. Each sync reads it, merges it with what is on this device and
 * writes the result back: runs and friends are added up (a friend removed on one device stays removed), settings
 * follow whichever device changed them last.
 */
@Stable
class Account(private val model: AppModel) {
    /** Accounts can be used (the server address and key are set). */
    var configured by mutableStateOf(false)
        private set
    var signedIn by mutableStateOf(false)
        private set
    var email by mutableStateOf("")
        private set
    var profile by mutableStateOf<CloudProfile?>(null)
        private set
    /** A request is under way (sign-in, sign-up…). */
    var busy by mutableStateOf(false)
        private set
    /** The last sync: "synced · 12:03" or what went wrong. */
    var syncNote by mutableStateOf<String?>(null)
        private set
    var lastSyncAt by mutableStateOf(0L)
        private set
    /** Signed in by a password-reset link: a new password is due. */
    var recovery by mutableStateOf(false)
    /** The ranked ladder (top ratings), loaded on demand. */
    var ladder by mutableStateOf<List<CloudProfile>?>(null)
        private set
    var ladderNote by mutableStateOf<String?>(null)
        private set
    /** Web app: the sign-in screen was shown at start once (on the first visit), or a sign-in link was used. */
    private var welcomed = false

    /** True the first time only (the web app then opens the sign-in screen once). */
    fun firstVisit(): Boolean {
        if (welcomed) return false
        welcomed = true
        saveMeta()
        return true
    }

    private val lock = Mutex()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val json = Json { ignoreUnknownKeys = true }

    // ---- this device's sync state (cloud_meta.json): when settings last changed here, friends removed here
    private var settingsAt = 0L
    private var gone = HashMap<String, Long>()
    private var pushedPrint = ""
    private var startedAt = 0L

    fun start() {
        loadMeta()
        startedAt = Platform.epochMillis()
        model.scope.launch {
            val cfg = runCatching { app.coredump.res.Res.readBytes("files/cloud.json").decodeToString() }.getOrNull()
            Cloud.configure(cfg)
            configured = Cloud.configured
            refresh()
            Cloud.pendingLink?.let { onLink(it) }
            while (true) {
                withTimeoutOrNull(if (lastSyncAt == 0L) 3_000 else 20_000) { kick.receive() }
                if (Cloud.session == null) { refresh(); continue }
                val due = lastSyncAt == 0L || print() != pushedPrint || Platform.epochMillis() - lastSyncAt > 4 * 60_000
                if (due) sync()
            }
        }
        app.coredump.platform.AppLife.onForeground { syncSoon() }
    }

    fun syncSoon() { kick.trySend(Unit) }

    private fun refresh() {
        val s = Cloud.session
        signedIn = s != null
        email = s?.email.orEmpty()
        if (s == null) profile = null
    }

    private fun onLink(link: CloudLink) {
        Cloud.pendingLink = null
        refresh()
        when {
            link.error != null -> model.toast = "sign-in link: ${link.error}"
            link.type == "recovery" -> {
                recovery = true
                welcomed = true
                // still booting: the boot screen opens it when done
                if (model.screen != Screen.Boot) model.go(Screen.Account)
            }
            link.session != null -> { model.toast = "e-mail confirmed · signed in as ${link.session.email}"; welcomed = true; syncSoon() }
        }
    }

    // ------------------------------------------------------------------ sign-in

    private fun task(what: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        model.scope.launch {
            try { block() } catch (e: Throwable) {
                Cloud.note(what, e)
                model.toast = (e.message ?: "failed").take(140)
            } finally { busy = false; refresh() }
        }
    }

    fun signIn(email: String, password: String, done: () -> Unit = {}) = task("sign-in") {
        Cloud.signIn(email, password)
        refresh()
        model.toast = "signed in"
        sync()
        done()
    }

    /** [done] (true = signed in now, false = confirm the e-mail first). */
    fun signUp(handle: String, email: String, password: String, done: (Boolean) -> Unit = {}) = task("sign-up") {
        val h = cleanHandle(handle)
        if (h.length < 2) throw app.coredump.net.CloudError("a name needs 2 to 14 letters or digits")
        if (!Cloud.handleFree(h)) throw app.coredump.net.CloudError("the name $h is taken")
        val now = Cloud.signUp(email, password, h)
        if (now) {
            refresh()
            model.update { it.copy(handle = h) }
            model.toast = "account made · signed in as $h"
            sync()
        } else model.toast = "almost done: open the link in the e-mail we sent, then sign in"
        done(now)
    }

    fun sendReset(email: String) = task("reset") {
        Cloud.sendReset(email)
        model.toast = "a reset link is on its way to $email"
    }

    fun setPassword(password: String) = task("password") {
        Cloud.setPassword(password)
        recovery = false
        model.toast = "new password saved"
        sync()
    }

    fun signOut() = task("sign-out") {
        Cloud.signOut()
        recovery = false
        lastSyncAt = 0L
        syncNote = null
        ladder = null
        model.toast = "signed out · this device keeps its data"
    }

    fun deleteAccount() = task("delete") {
        Cloud.deleteAccount()
        recovery = false
        lastSyncAt = 0L
        syncNote = null
        model.toast = "account deleted · this device keeps its own copy"
    }

    /** Adds a friend by account name (they get a friend request like with a code). */
    fun addByName(name: String, done: () -> Unit = {}) = task("find") {
        val h = cleanHandle(name)
        val p = Cloud.find(h) ?: throw app.coredump.net.CloudError("no player called $h")
        if (p.id == Cloud.session?.uid) throw app.coredump.net.CloudError("that is you")
        if (p.code.length != app.coredump.net.JoinTicket.ROOM_LEN) throw app.coredump.net.CloudError("${p.handle} hasn't been online with the app yet")
        if (model.addFriend(p.handle, p.code)) done()
    }

    fun loadLadder() {
        if (Cloud.session == null) return
        model.scope.launch {
            ladderNote = "loading…"
            runCatching { Cloud.ladder() }
                .onSuccess { ladder = it; ladderNote = null }
                .onFailure { ladderNote = it.message ?: "failed" }
        }
    }

    /** A new name for the account (from the settings): false + a note when it is taken. */
    fun rename(handle: String) {
        if (Cloud.session == null) return
        model.scope.launch {
            val r = runCatching { Cloud.setHandle(handle) }.getOrElse { "failed" }
            when (r) {
                "ok" -> { model.toast = "name set: $handle"; syncSoon() }
                "taken" -> { model.toast = "the name $handle is taken"; profile?.let { p -> model.applyFromCloud(model.settings.copy(handle = p.handle)) } }
                else -> model.toast = "name not saved ($r)"
            }
        }
    }

    // ------------------------------------------------------------------ ranked

    /**
     * A ranked match ended while signed in. The server rates it once both players sent the same result; this asks
     * a few times (the other report may take a moment) and then shows the new rating.
     */
    fun reportRanked(matchId: String, oppUid: String, oppName: String, mine: Int, theirs: Int) {
        val before = model.settings.elo
        model.toast = "ranked · checking the result with the server…"
        model.scope.launch {
            var reply: app.coredump.net.RankedReply? = null
            for (wait in listOf(1_500L, 2_500L, 4_000L, 6_000L, 9_000L, 12_000L, 15_000L, 0L)) {
                reply = runCatching { Cloud.reportRanked(matchId, oppUid, mine, theirs) }
                    .onFailure { Cloud.note("ranked", it) }.getOrNull()
                if (reply != null && reply.state != "waiting") break
                if (wait > 0) delay(wait)
            }
            when (reply?.state) {
                "done" -> {
                    sync()
                    val after = reply?.elo ?: model.settings.elo
                    val d = reply?.delta ?: (after - before)
                    val div = app.coredump.game.Ranked.division(after)
                    val up = app.coredump.game.Ranked.division(before).index < div.index
                    model.toast = if (up) "promoted to ${div.name} · rating $after (+$d)" else "ranked · rating $after (${if (d >= 0) "+" else ""}$d)"
                }
                "disputed" -> model.toast = "ranked: your result and $oppName's differ · not rated"
                "limit" -> model.toast = "ranked: enough rated games against $oppName today · not rated"
                "waiting" -> model.toast = "ranked: $oppName's result hasn't arrived yet · your rating moves when it does"
                "no account" -> model.toast = "ranked: $oppName has no account · not rated"
                else -> model.toast = "ranked: couldn't reach the server · not rated yet"
            }
        }
    }

    // ------------------------------------------------------------------ sync

    /** Settings fields that sync (all but rating, friends, requests and this device's room). */
    private fun plain(s: Settings) = s.copy(handle = "", friends = emptyList(), requests = emptyList(), elo = 0, rankedGames = 0,
        rankedWins = 0, streak = 0, bestStreak = 0, peakElo = 0, rankedLog = emptyList(), room = "", roomBroker = 0, rescoredAt = 0L,
        meltGames = 0, meltWins = 0)

    /** Called by the app whenever the settings change on this device. */
    fun changed(old: Settings, new: Settings) {
        var dirty = false
        if (plain(old) != plain(new)) { settingsAt = Platform.epochMillis(); dirty = true }
        val before = old.friends.map { it.code }.filter { it.isNotBlank() }.toSet()
        val after = new.friends.map { it.code }.filter { it.isNotBlank() }.toSet()
        val now = Platform.epochMillis()
        for (c in before - after) { gone[c] = now; dirty = true }
        for (c in after - before) if (gone.remove(c) != null) dirty = true
        if (dirty) saveMeta()
        if (Cloud.session != null && old.handle != new.handle && new.handle != profile?.handle) rename(new.handle)
    }

    /** Cheap fingerprint of what would be uploaded (changes when anything that syncs changed). */
    private fun print(): String {
        val s = model.settings
        return "${plain(s).hashCode()}|${s.friends.hashCode()}|${s.meltGames}|${History.runs.size}|${History.runs.firstOrNull()?.at}|" +
            "${History.recent.firstOrNull()}|${History.seen.size}|${gone.size}|$settingsAt"
    }

    private fun blob(): JsonObject = buildJsonObject {
        put("v", 1)
        put("settings", SettingsStore.toJson(model.settings.copy(requests = emptyList())))
        put("settingsAt", settingsAt)
        put("gone", buildJsonObject { for ((k, v) in gone) put(k, v) })
        put("history", History.toJson(seenSince = Platform.epochMillis() - 7L * 86_400_000L))
        put("device", Platform.name)
        put("savedAt", Platform.epochMillis())
    }

    private fun mergeFriends(local: List<Friend>, remote: List<Friend>): List<Friend> {
        val out = LinkedHashMap<String, Friend>()
        for (f in local) out[f.code.ifBlank { "name:" + f.name }] = f
        for (f in remote) {
            val k = f.code.ifBlank { "name:" + f.name }
            if (k !in out) out[k] = f
        }
        return out.values.filter { f -> val g = gone[f.code]; f.code.isBlank() || g == null || g < f.at }
    }

    /** Pulls, merges and pushes. Safe to call any time; returns true when it worked. */
    suspend fun sync(): Boolean = lock.withLock {
        if (Cloud.session == null) { refresh(); return false }
        try {
            val p = Cloud.profile(cleanHandle(model.settings.handle).ifBlank { "PLAYER" })
            val remote = Cloud.loadSave()
            var merged = model.settings
            if (remote != null && remote.isNotEmpty()) {
                val remoteAt = (remote["settingsAt"] as? JsonPrimitive)?.longOrNull ?: 0L
                (remote["gone"] as? JsonObject)?.forEach { (k, v) ->
                    val t = (v as? JsonPrimitive)?.longOrNull ?: return@forEach
                    if ((gone[k] ?: 0L) < t) gone[k] = t
                }
                val horizon = Platform.epochMillis() - 120L * 86_400_000L
                gone.entries.removeAll { it.value < horizon }
                (remote["settings"] as? JsonObject)?.let { rs ->
                    val r = SettingsStore.fromJson(rs)
                    val base = if (remoteAt > settingsAt) r else merged
                    merged = base.copy(
                        handle = merged.handle, requests = merged.requests, friends = mergeFriends(merged.friends, r.friends),
                        room = merged.room, roomBroker = merged.roomBroker, rescoredAt = merged.rescoredAt,
                        meltGames = maxOf(merged.meltGames, r.meltGames), meltWins = maxOf(merged.meltWins, r.meltWins),
                    )
                    if (remoteAt > settingsAt) settingsAt = remoteAt
                }
                (remote["history"] as? JsonObject)?.let { History.merge(it) }
            }
            // the rating is the server's
            val log = runCatching { Cloud.rankedLog() }.getOrNull()
            merged = merged.copy(handle = p.handle, elo = p.elo, peakElo = maxOf(p.peakElo, p.elo), rankedGames = p.games,
                rankedWins = p.wins, streak = p.streak, bestStreak = p.bestStreak, rankedLog = log ?: merged.rankedLog)
            if (merged != model.settings) model.applyFromCloud(merged)
            profile = p
            // my room code on the profile: friends who add me by name invite me there
            val room = model.myRoom()
            if (p.code != room) runCatching { Cloud.setCode(room) }
            val print = print()
            if (print != pushedPrint || remote == null) {
                Cloud.putSave(blob())
                pushedPrint = print
            }
            saveMeta()
            lastSyncAt = Platform.epochMillis()
            syncNote = "synced"
            refresh()
            true
        } catch (e: Throwable) {
            Cloud.note("sync", e)
            syncNote = "sync failed: " + (e.message ?: "offline?").take(100)
            refresh()
            false
        }
    }

    // ------------------------------------------------------------------ device state

    private fun loadMeta() {
        runCatching {
            val o = json.parseToJsonElement(Platform.readText(META) ?: return).let { it as JsonObject }
            settingsAt = (o["settingsAt"] as? JsonPrimitive)?.longOrNull ?: 0L
            welcomed = (o["welcomed"] as? JsonPrimitive)?.content == "true"
            (o["gone"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { gone[k] = it } }
        }
    }

    private fun saveMeta() {
        runCatching {
            Platform.writeText(META, buildJsonObject {
                put("settingsAt", settingsAt)
                put("welcomed", welcomed)
                put("gone", buildJsonObject { for ((k, v) in gone) put(k, v) })
            }.toString())
        }
    }

    companion object {
        private const val META = "cloud_meta.json"

        /** An account name: what the settings allow (letters, digits, _ and -; upper case; at most 14). */
        fun cleanHandle(s: String): String = s.uppercase().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(14)

        /** Rough check before asking the server. */
        fun emailOk(s: String): Boolean = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(s.trim())
    }
}
