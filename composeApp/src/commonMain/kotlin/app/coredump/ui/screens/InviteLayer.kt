package app.coredump.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.AppModel
import app.coredump.game.Invite
import app.coredump.ui.C
import app.coredump.ui.ModalCard
import app.coredump.ui.Style
import app.coredump.ui.TButton
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.rememberHaptic

/** "ALEX is inviting you to a 1v1": one card per invite, each with ACCEPT and DECLINE, over a dimmed screen. */
@Composable
fun InviteLayer(model: AppModel) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))
            .clickable(remember { MutableInteractionSource() }, null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(24.dp).widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (inv in model.invites.takeLast(3)) InviteCard(model, inv)
            if (model.invites.size > 3) TText("+${model.invites.size - 3} more", size = 11.sp, color = C.dim)
        }
    }
}

private fun head(mode: String): String = when (Th.style) {
    Style.TERMINAL -> "INCOMING CONNECTION"
    Style.PAPER -> "Telegram"
    Style.CHAT -> "New message"
    Style.METRO -> "Platform call"
    Style.DESKTOP -> "Invitation.exe"
    Style.ORACLE -> "A summons"
    Style.ABYSS -> "Sonar ping"
    Style.JACKPOT -> "High-roller table"
    Style.PIXEL -> "PLAYER 2 WANTS IN"
    Style.COMIC -> "KNOCK KNOCK!"
    else -> if (mode == "party") "Party invite" else "Game invite"
}

@Composable
private fun InviteCard(model: AppModel, inv: Invite) {
    val haptic = rememberHaptic()
    val pop = remember(inv.id) { Animatable(0f) }
    LaunchedEffect(inv.id) { haptic(true); pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
    val friend = model.isFriend(inv.code)
    val what = if (inv.mode == "party") "a MELTDOWN party" else "a 1v1"
    ModalCard(head(inv.mode), C.hot, Modifier.fillMaxWidth().graphicsLayer {
        val s = 0.85f + 0.15f * pop.value
        scaleX = s; scaleY = s; alpha = pop.value.coerceIn(0f, 1f)
    }) {
        TText("${inv.from} is inviting you to $what", size = 18.sp, weight = FontWeight.Bold, color = C.amber)
        Spacer(Modifier.height(4.dp))
        TText(if (friend) "from your friends" else "not on your friends list", size = 11.sp, color = C.dim)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            TButton(Th.say("ACCEPT", "Accept"), { model.acceptInvite(inv) }, Modifier.weight(1f), primary = true)
            Spacer(Modifier.width(10.dp))
            TButton(Th.say("DECLINE", "Decline"), { model.declineInvite(inv) }, Modifier.weight(1f), color = C.blood)
        }
    }
}
