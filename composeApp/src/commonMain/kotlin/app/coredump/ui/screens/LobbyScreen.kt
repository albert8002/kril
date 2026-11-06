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

private enum class Tab(val label: String) { HOST("HOST"), JOIN("JOIN") }

@Composable
fun LobbyScreen(model: AppModel) {
    var tab by remember { mutableStateOf(Tab.HOST) }
    Frame(model, "./dive --peer --lan") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Choice(Tab.entries, tab, { it.label }) { tab = it }
            Spacer(Modifier.height(14.dp))
            when (tab) {
                Tab.HOST -> HostPane(model)
                Tab.JOIN -> JoinPane(model)
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

    Panel("MATCH PARAMS") {
        TText("${s.rounds} prompts · ${fmtSeconds(s.seconds)} each · ${s.difficulty.label} · " +
            (if (s.topics.isEmpty()) "ALL topics" else s.topics.joinToString(" ") { "#$it" }), size = 12.sp)
        TLink("edit parameters", { model.go(Screen.Setup(forHost = true)) })
    }
    Spacer(Modifier.height(14.dp))
    StatusLine(st)
    val t = st.ticket
    if (t != null && st.peer == null) {
        Spacer(Modifier.height(12.dp))
        TText("let the peer scan this sigil:", size = 12.sp, color = C.dim)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrSigil(t.toUri(), Modifier.widthIn(max = 280.dp).fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        if (t.isRelay) {
            TText("or type this room code:", size = 11.sp, color = C.dim)
            TText(JoinTicket.pretty(t.room!!) + if (t.broker != 0) " /${t.broker}" else "", size = 28.sp,
                weight = FontWeight.Bold, color = C.hot, spacing = 3.sp)
        } else {
            TText("manual:  ${t.host}:${t.port} ${t.pin}", size = 13.sp, weight = FontWeight.Bold, color = C.hot)
        }
        st.via?.let { TText("via $it", size = 10.sp, color = C.line) }
    }
    val peer = st.peer
    if (peer != null) {
        Spacer(Modifier.height(12.dp))
        Panel("LINK ESTABLISHED") {
            TText("peer ${peer.uppercase()} is attached to your process.", size = 13.sp, color = C.hot)
        }
        Spacer(Modifier.height(12.dp))
        TButton("EXECUTE MATCH", { model.startHosted() }, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
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
        val t = raw?.let { JoinTicket.parse(it) }
        if (t == null) { if (raw != null) model.toast = "not a CORE_DUMP code"; return }
        join.connect(t) { session -> model.joined(session) }
    }
    val scan = rememberQrScanLauncher { dial(it) }
    DisposableEffect(Unit) { onDispose { } }

    TButton("SCAN SIGIL", { scan() }, Modifier.fillMaxWidth(), primary = true, textSize = 16.sp)
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
