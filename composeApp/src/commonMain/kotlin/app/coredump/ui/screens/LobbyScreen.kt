package app.coredump.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.Screen
import app.coredump.game.LinkMode
import app.coredump.game.LobbyState
import app.coredump.net.JoinTicket
import app.coredump.platform.rememberQrScanLauncher
import app.coredump.qr.QrEncoder
import app.coredump.ui.C
import app.coredump.ui.Choice
import app.coredump.ui.Panel
import app.coredump.ui.Spinner
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.TermInput
import app.coredump.ui.Th

private enum class Tab(val label: String) { HOST("HOST"), JOIN("JOIN"), FRIENDS("FRIENDS"), RANKED("RANKED") }

/** Status of the invite being sent ([mode] "duel" or "party"), if any. */
@Composable
internal fun InviteStatus(model: AppModel, mode: String) {
    val inv = model.inviting ?: return
    if (inv.mode != mode) return
    val st by inv.status.collectAsState()
    val (text, color) = when (st) {
        app.coredump.game.Inviter.Status.SENDING -> "inviting ${inv.friendName}…" to C.amber
        app.coredump.game.Inviter.Status.SEEN -> "${inv.friendName} got your invite · waiting for an answer" to C.hot
        app.coredump.game.Inviter.Status.ACCEPTED -> "${inv.friendName} accepted · joining you" to C.hot
        app.coredump.game.Inviter.Status.DECLINED -> "${inv.friendName} declined" to C.blood
        app.coredump.game.Inviter.Status.NO_ANSWER -> "no answer from ${inv.friendName}: is their app open?" to C.blood
    }
    TText(text, Modifier.padding(top = 6.dp), size = 12.sp, color = color, weight = FontWeight.Bold)
}

/** Current streak in our games, newest first: "streak: 3 wins" / "2 losses" / "" (none or a draw last). */
private fun streakVs(games: List<app.coredump.data.RunRecord>): String {
    val sorted = games.sortedByDescending { it.at }
    val first = sorted.firstOrNull() ?: return ""
    fun res(r: app.coredump.data.RunRecord) = (r.points).compareTo(r.oppPoints ?: 0)
    val dir = res(first)
    if (dir == 0) return ""
    val n = sorted.takeWhile { res(it) == dir }.size
    return "streak: $n ${if (dir > 0) (if (n == 1) "win" else "wins") else (if (n == 1) "loss" else "losses")}"
}

/** Ranked: my rating, the fixed rules, find a random opponent. */
@Composable
private fun RankedPane(model: AppModel) {
    val s = model.settings
    val mm = model.matchmaker
    Panel(Th.say("RATING", "Your rating")) {
        TText("${s.elo}", size = 34.sp, weight = FontWeight.Bold, color = C.hot)
        TText("${s.rankedGames} ranked games · ${s.rankedWins} wins" + (if (s.rankedGames > 0) " (${s.rankedWins * 100 / s.rankedGames}%)" else ""),
            size = 12.sp, color = C.amber)
        TText("win streak ${s.streak} · best ${s.bestStreak}", size = 12.sp, color = C.dim)
    }
    Spacer(Modifier.height(14.dp))
    Panel(Th.say("RULES", "Ranked rules (fixed)")) {
        val c = app.coredump.game.Ranked.CONFIG
        for (line in listOf(
            "${c.rounds} prompts · ${c.secondsPerRound} s each · ${c.difficulty.label} typos · all topics",
            "speed penalty on: once your opponent answers, you lose ${app.coredump.core.SpeedTax.PER_SECOND} points a second (at most ${app.coredump.core.SpeedTax.MAX}) until you answer or skip",
            "a random online opponent near your rating; the search widens the longer you wait",
            "win: rating up, loss: down (Elo, more for a stronger opponent). friendly 1v1s count half as much",
        )) TText("· $line", size = 12.sp, color = C.amber)
    }
    Spacer(Modifier.height(14.dp))
    if (mm == null) {
        TButton(Th.say("QUEUE --ranked", "Find a match"), { model.findRanked() }, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
    } else {
        val st by mm.status.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (st.searching) { Spinner(C.hot); TText("  ", size = 12.sp) }
            TText(st.text, size = 13.sp, color = if (st.error != null) C.blood else C.amber, weight = FontWeight.Bold)
        }
        st.error?.let { TText(it, size = 11.sp, color = C.blood) }
        if (st.searching) TText("${st.waiting} other player${if (st.waiting == 1) "" else "s"} searching right now", size = 11.sp, color = C.dim)
        Spacer(Modifier.height(10.dp))
        TButton(if (st.searching) "Cancel" else "Search again", { if (st.searching) model.cancelRanked() else model.findRanked() },
            Modifier.fillMaxWidth(), color = if (st.searching) C.blood else C.hot)
    }
}

/** Self-test only: the tab the 1v1 screen opens on. */
internal object LobbyProbe {
    var tab = 0
    /** The tab to open on (once; back to HOST afterwards). */
    fun take(): Int = tab.also { tab = 0 }
}

@Composable
fun LobbyScreen(model: AppModel) {
    var tab by remember { mutableStateOf(Tab.entries[LobbyProbe.take().coerceIn(0, Tab.entries.size - 1)]) }
    Frame(model, Th.say("./run --peer", "1v1")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Choice(Tab.entries, tab, { it.label }) { tab = it }
            Spacer(Modifier.height(14.dp))
            when (tab) {
                Tab.HOST -> HostPane(model)
                Tab.JOIN -> JoinPane(model)
                Tab.FRIENDS -> FriendsPane(model) { tab = it }
                Tab.RANKED -> RankedPane(model)
            }
            Spacer(Modifier.height(16.dp))
            TText("online relay: messages pass through a free public MQTT server (room code = key). direct: same Wi-Fi, no internet.",
                size = 10.sp, color = C.line)
        }
    }
}

@Composable
private fun HostPane(model: AppModel) {
    var mode by remember { mutableStateOf(model.hostLobby?.mode ?: LinkMode.RELAY) }
    Choice(LinkMode.entries, mode, { it.label }) { mode = it }
    TText(
        if (mode == LinkMode.RELAY) "works anywhere with internet (Wi-Fi or mobile data). recommended."
        else "same Wi-Fi only. blocked on many campus/hotel networks and inside LiveContainer.",
        size = 10.sp, color = C.dim,
    )
    Spacer(Modifier.height(12.dp))
    val lobby = remember(mode) { model.openHost(mode) }
    val st by lobby.state.collectAsState()
    LaunchedEffect(lobby) { if (st.ticket == null && st.peer == null && !st.busy) lobby.open() }
    val s = model.settings

    Panel(Th.say("MATCH PARAMS", "Match settings")) {
        TText("${s.rounds} prompts · ${fmtSeconds(s.seconds)} each · ${s.difficulty.label} · " +
            (if (s.topics.isEmpty()) "ALL topics" else s.topics.joinToString(" ") { "#$it" }), size = 12.sp)
        TLink("edit parameters", { model.go(Screen.Setup(forHost = true)) })
    }
    Spacer(Modifier.height(14.dp))
    StatusLine(st)
    val t = st.ticket
    if (t != null && st.peer == null) {
        Spacer(Modifier.height(12.dp))
        TText(Th.say("let the peer scan this sigil:", "Let your friend scan this code:"), size = 12.sp, color = C.dim)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrSigil(t.toUri(), Modifier.widthIn(max = 280.dp).fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        if (t.isRelay) {
            TText("or type this room code:", size = 11.sp, color = C.dim)
            TText(JoinTicket.pretty(t.room!!), size = 28.sp, weight = FontWeight.Bold, color = C.hot, spacing = 3.sp)
            TText("this is your own code: it stays the same, even if you leave the app to send it.", size = 10.sp, color = C.dim)
        } else {
            TText("manual:  ${t.host}:${t.port} ${t.pin}", size = 13.sp, weight = FontWeight.Bold, color = C.hot)
        }
        st.via?.let { TText("via $it", size = 10.sp, color = C.line) }
    }
    val peer = st.peer
    if (peer != null) {
        Spacer(Modifier.height(12.dp))
        Panel(Th.say("LINK ESTABLISHED", "Connected")) {
            TText("peer ${peer.uppercase()} is attached to your process.", size = 13.sp, color = C.hot)
            AddFriendLink(model, peer, st.peerCode)
        }
        Spacer(Modifier.height(12.dp))
        TButton(Th.say("EXECUTE MATCH", "Start match"), { model.startHosted() }, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
    }
    if (st.error != null) {
        Spacer(Modifier.height(10.dp))
        TButton("RETRY", { lobby.open() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun JoinPane(model: AppModel) {
    val join = remember { model.openGuest() }
    val st by join.state.collectAsState()
    var manual by remember { mutableStateOf("") }
    fun dial(raw: String?) {
        if (raw != null && raw.trim().startsWith("coredump://party?")) {
            // a MELTDOWN party code scanned here: go to the party screen and join it
            model.go(Screen.Meltdown); model.joinParty(raw); return
        }
        val t = raw?.let { JoinTicket.parse(it) }
        if (t == null) { if (raw != null) model.toast = "not a CORE_DUMP code"; return }
        join.connect(t) { session -> model.joined(session) }
    }
    val scan = rememberQrScanLauncher { dial(it) }
    DisposableEffect(Unit) { onDispose { } }

    TButton(Th.say("SCAN SIGIL", "Scan QR code"), { scan() }, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
    Spacer(Modifier.height(14.dp))
    TText("or type the host's room code:", size = 12.sp, color = C.dim)
    Spacer(Modifier.height(6.dp))
    TermInput(manual, { manual = it.uppercase() }, Modifier.fillMaxWidth(), prompt = "join", placeholder = "K7Q2-M9XA",
        size = 16.sp, onSubmit = { dial(manual) })
    TText("(direct Wi-Fi hosts show  ip:port pin  instead)", size = 10.sp, color = C.line)
    Spacer(Modifier.height(8.dp))
    TButton("CONNECT", { dial(manual) }, Modifier.fillMaxWidth(), enabled = manual.isNotBlank())
    Spacer(Modifier.height(14.dp))
    if (st.status != "idle") StatusLine(st)
    st.peer?.let { AddFriendLink(model, it, st.peerCode) }
    if (model.settings.friends.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Panel(Th.say("FRIENDS", "Join a friend")) {
            for (f in model.settings.friends.sortedBy { it.name.lowercase() }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TText(f.name, Modifier.weight(1f), size = 14.sp, weight = FontWeight.Bold)
                    TLink("join", { model.joinFriend(f) }, color = C.hot)
                }
            }
        }
    }
}

/** "Add as friend" under a connected player, when their code is known and they are not a friend yet. */
@Composable
private fun AddFriendLink(model: AppModel, name: String, code: String?) {
    if (code == null || model.isFriend(code)) return
    TLink("add ${name} as a friend", { model.addFriend(name, code) }, color = C.hot)
}

/** Friends: my code (never changes), add by code, each friend with our record and a join button, then every 1v1. */
@Composable
private fun FriendsPane(model: AppModel, goTab: (Tab) -> Unit) {
    val s = model.settings
    val code = remember { model.myRoom() }
    Panel(Th.say("YOUR CODE", "Your code")) {
        TText(JoinTicket.pretty(code), size = 28.sp, weight = FontWeight.Bold, color = C.hot, spacing = 3.sp)
        TText("it never changes. friends with this code can join you whenever you are on the host screen.", size = 11.sp, color = C.dim)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            TButton("Share code", {
                app.coredump.platform.shareText("Play CORE DUMP with me! My code: ${JoinTicket.pretty(code)}\n" +
                    JoinTicket("", 0, "", s.handle, code, s.roomBroker).toUri())
            }, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            TButton("Host now", { goTab(Tab.HOST) }, Modifier.weight(1f), primary = true)
        }
    }
    Spacer(Modifier.height(14.dp))
    var name by remember { mutableStateOf("") }
    var fcode by remember { mutableStateOf("") }
    Panel(Th.say("ADD FRIEND", "Add a friend")) {
        TermInput(fcode, { fcode = it.uppercase().take(12) }, Modifier.fillMaxWidth(), prompt = "code", placeholder = "K7Q2-M9XA", size = 15.sp,
            onSubmit = { if (model.addFriend(name, fcode)) { name = ""; fcode = "" } })
        Spacer(Modifier.height(8.dp))
        TermInput(name, { name = it.take(24) }, Modifier.fillMaxWidth(), prompt = "name", placeholder = "optional: they send theirs", size = 15.sp)
        Spacer(Modifier.height(8.dp))
        TButton("Send friend request", { if (model.addFriend(name, fcode)) { name = ""; fcode = "" } }, Modifier.fillMaxWidth(),
            enabled = fcode.isNotBlank(), primary = true)
        TText("the code is enough. they get a pop-up (or find it under requests) and their name comes back when they accept.",
            size = 10.sp, color = C.dim)
    }
    if (s.requests.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Panel(Th.say("REQUESTS", "Friend requests") + " (${s.requests.size})") {
            for (r in s.requests.sortedByDescending { it.at }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TText(r.name.ifBlank { JoinTicket.pretty(r.code) }, size = 15.sp, weight = FontWeight.Bold)
                        TText(JoinTicket.pretty(r.code) + (ratingLine(r.elo, r.streak).let { if (it.isEmpty()) "" else " · $it" }), size = 11.sp, color = C.dim)
                    }
                    TLink("accept", { model.acceptFriend(r.code) }, color = C.hot)
                    Spacer(Modifier.width(12.dp))
                    TLink("decline", { model.declineFriend(r.code) })
                }
            }
        }
    }
    val duels = app.coredump.data.History.runs.filter { it.mode == "1v1" }
    fun vs(f: app.coredump.data.Friend) = duels.filter { r -> (r.oppCode != null && r.oppCode == f.code) || (r.oppCode == null && r.opponent == f.name) }
    Spacer(Modifier.height(14.dp))
    Panel(Th.say("FRIENDS", "Friends") + " (${s.friends.size})") {
        if (s.friends.isEmpty()) TText("no friends yet: add one by code, or after a 1v1.", size = 12.sp, color = C.dim)
        for (f in s.friends.sortedBy { it.name.lowercase() }) {
            val games = vs(f)
            val w = games.count { (it.oppPoints ?: 0) < it.points }
            val l = games.count { (it.oppPoints ?: 0) > it.points }
            val d = games.size - w - l
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TText(f.name, size = 15.sp, weight = FontWeight.Bold)
                    val their = ratingLine(f.elo, f.streak)
                    TText((if (f.code.isNotEmpty()) JoinTicket.pretty(f.code) else "") + (if (their.isNotEmpty()) " · rating $their" else ""),
                        size = 11.sp, color = C.dim)
                    // our record and the current streak between us, under their name
                    TText(if (games.isEmpty()) "no games yet" else "you $w – $l them" + (if (d > 0) " · $d draw${if (d > 1) "s" else ""}" else "") +
                        streakVs(games).let { if (it.isEmpty()) "" else " · $it" }, size = 11.sp, color = C.amber)
                }
                Column(horizontalAlignment = Alignment.End) {
                    TLink("invite", { model.inviteFriend(f, "duel"); goTab(Tab.HOST) }, color = C.hot)
                    TLink("join", { goTab(Tab.JOIN); model.joinFriend(f) }, color = C.hot)
                    TLink("remove", { model.removeFriend(f) })
                }
            }
        }
        InviteStatus(model, "duel")
        if (s.friends.isNotEmpty()) TText("invite: they get a pop-up in the app (it must be open) and join you with one tap.", size = 10.sp, color = C.dim)
    }
    Spacer(Modifier.height(14.dp))
    Panel(Th.say("1V1 HISTORY", "1v1 history") + " (${duels.size})") {
        if (duels.isEmpty()) TText("no 1v1 games yet.", size = 12.sp, color = C.dim)
        for (r in duels.take(60)) {
            val op = r.oppPoints ?: 0
            val result = when { r.points > op -> "WON"; r.points < op -> "LOST"; else -> "DRAW" }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TText("${r.opponent ?: "?"} · ${r.points} – $op", size = 13.sp, weight = FontWeight.Bold)
                    TText(dayLabel(r.at), size = 10.sp, color = C.dim)
                }
                TText(result, size = 12.sp, weight = FontWeight.Bold, color = when (result) { "WON" -> C.hot; "LOST" -> C.blood; else -> C.dim })
                if (r.oppCode != null && !model.isFriend(r.oppCode)) {
                    Spacer(Modifier.width(10.dp))
                    TLink("+ friend", { model.addFriend(r.opponent ?: "FRIEND", r.oppCode) }, color = C.hot)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(st: LobbyState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (st.busy) { Spinner(C.hot); TText("  ", size = 12.sp) }
        TText(
            when {
                st.peer != null -> "status: linked"
                st.ticket != null && st.busy -> "status: listening · awaiting peer…"
                else -> "status: ${st.status}"
            },
            size = 12.sp, color = if (st.error != null) C.blood else C.amber,
        )
    }
    st.error?.let { TText("  $it", size = 11.sp, color = C.blood) }
}

/** QR drawn dark-on-amber (scanners need dark modules on a light field), framed by pulsing brackets. */
@Composable
fun QrSigil(data: String, modifier: Modifier = Modifier) {
    val m = remember(data) { QrEncoder.encode(data) }
    val t = rememberInfiniteTransition()
    val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse))
    Canvas(modifier.aspectRatio(1f)) {
        val pad = 14.dp.toPx()
        val side = size.minDimension - pad * 2
        val quiet = 3
        val n = m.size + quiet * 2
        val cell = side / n
        val origin = Offset(pad, pad)
        drawRect(C.amber, origin, Size(side, side))
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (m[x, y]) drawRect(C.bg, Offset(origin.x + (x + quiet) * cell, origin.y + (y + quiet) * cell), Size(cell + 0.6f, cell + 0.6f))
        }
        val L = 26.dp.toPx(); val w = 2.dp.toPx()
        val c = C.hot.copy(alpha = pulse)
        val e = 3.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(x, y), Offset(x + dx * L, y), w)
            drawLine(c, Offset(x, y), Offset(x, y + dy * L), w)
        }
        corner(e, e, 1f, 1f); corner(size.width - e, e, -1f, 1f)
        corner(e, size.height - e, 1f, -1f); corner(size.width - e, size.height - e, -1f, -1f)
        drawRect(C.line, origin, Size(side, side), style = Stroke(1f))
    }
}


/** "2026-11-10" from epoch ms (UTC calendar date). */
internal fun dayLabel(ms: Long): String {
    val z = ms / 86_400_000L + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = if (mp < 10L) mp + 3L else mp - 9L
    val y = yoe + era * 400L + if (m <= 2L) 1L else 0L
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
