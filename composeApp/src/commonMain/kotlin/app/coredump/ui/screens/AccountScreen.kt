package app.coredump.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.Account
import app.coredump.AppModel
import app.coredump.Screen
import app.coredump.game.Ranked
import app.coredump.platform.Platform
import app.coredump.ui.C
import app.coredump.ui.Choice
import app.coredump.ui.KV
import app.coredump.ui.Panel
import app.coredump.ui.Spinner
import app.coredump.ui.TButton
import app.coredump.ui.TLink
import app.coredump.ui.TText
import app.coredump.ui.TermInput
import app.coredump.ui.Th
import kotlinx.coroutines.launch

private enum class AuthTab(val label: String) { SIGN_IN("Sign in"), SIGN_UP("New account"), RESET("Forgot password") }

/**
 * The account: sign in or make one (name, e-mail, password), then the same settings, looks, history, friends and
 * ranked rating on every device and in the web app. Signed in: sync state, password, sign out, delete.
 */
@Composable
fun AccountScreen(model: AppModel) {
    val acc = model.account
    Frame(model, Th.say("passwd --account", "Account")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            when {
                !acc.configured -> NotSetUp(model)
                acc.recovery && acc.signedIn -> NewPassword(model)
                acc.signedIn -> SignedIn(model)
                else -> SignedOut(model)
            }
            Spacer(Modifier.height(18.dp))
            TText("an account is optional: everything works without one, on this device only.", size = 10.sp, color = C.line)
        }
    }
}

@Composable
private fun NotSetUp(model: AppModel) {
    Panel(Th.say("ACCOUNTS", "Accounts")) {
        TText("accounts aren't switched on for this build yet.", size = 13.sp, color = C.amber)
        Spacer(Modifier.height(6.dp))
        TText("the game works fully without one. once accounts are on, you can sign in here and keep your stats, looks, " +
            "friends and ranked rating on every device.", size = 11.sp, color = C.dim)
        if (Platform.web) {
            Spacer(Modifier.height(12.dp))
            TButton(Th.say("PLAY", "Play"), { model.home() }, Modifier.fillMaxWidth(), primary = true, textSize = 13.sp)
        }
    }
}

@Composable
private fun SignedOut(model: AppModel) {
    val acc = model.account
    var tab by remember { mutableStateOf(AuthTab.SIGN_IN) }
    var handle by remember { mutableStateOf(Account.cleanHandle(model.settings.handle).takeIf { it != "ANON" }.orEmpty()) }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    Panel(Th.say("WHY", "Why an account")) {
        TText("the same stats, looks, friends and ranked rating on your phone and in the browser. " +
            "your ranked rating is kept on the server: it moves only when both players report the same result.",
            size = 11.sp, color = C.dim)
    }
    Spacer(Modifier.height(14.dp))
    Choice(AuthTab.entries, tab, { it.label }) { tab = it; note = null }
    Spacer(Modifier.height(12.dp))
    Panel(tab.label.let { Th.say(it.uppercase(), it) }) {
        if (tab == AuthTab.SIGN_UP) {
            TermInput(handle, { handle = Account.cleanHandle(it) }, Modifier.fillMaxWidth(), prompt = "name",
                placeholder = "2–14 letters or digits", size = 14.sp, imeAction = ImeAction.Next)
            Spacer(Modifier.height(8.dp))
        }
        TermInput(email, { email = it.trim().take(120) }, Modifier.fillMaxWidth(), prompt = "e-mail", placeholder = "you@example.com",
            size = 14.sp, keyboardType = KeyboardType.Email, imeAction = if (tab == AuthTab.RESET) ImeAction.Done else ImeAction.Next)
        if (tab != AuthTab.RESET) {
            Spacer(Modifier.height(8.dp))
            TermInput(pw, { pw = it.take(72) }, Modifier.fillMaxWidth(), prompt = "password", placeholder = if (tab == AuthTab.SIGN_UP) "8 characters or more" else "",
                size = 14.sp, password = true, imeAction = if (tab == AuthTab.SIGN_UP) ImeAction.Next else ImeAction.Done,
                onSubmit = { if (tab == AuthTab.SIGN_IN) note = signIn(model, email, pw) })
        }
        if (tab == AuthTab.SIGN_UP) {
            Spacer(Modifier.height(8.dp))
            TermInput(pw2, { pw2 = it.take(72) }, Modifier.fillMaxWidth(), prompt = "again", placeholder = "the same password",
                size = 14.sp, password = true)
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            AuthTab.SIGN_IN -> TButton(if (acc.busy) "signing in…" else Th.say("SIGN IN", "Sign in"), { note = signIn(model, email, pw) },
                Modifier.fillMaxWidth(), enabled = !acc.busy, primary = true, textSize = 13.sp)
            AuthTab.SIGN_UP -> TButton(if (acc.busy) "making the account…" else Th.say("MAKE ACCOUNT", "Make the account"), {
                note = when {
                    handle.length < 2 -> "a name needs 2 to 14 letters or digits"
                    !Account.emailOk(email) -> "that e-mail address doesn't look right"
                    pw.length < 8 -> "the password needs 8 characters or more"
                    pw != pw2 -> "the two passwords differ"
                    else -> { acc.signUp(handle, email, pw) { now -> if (now) { model.back() } else { tab = AuthTab.SIGN_IN } }; null }
                }
            }, Modifier.fillMaxWidth(), enabled = !acc.busy, primary = true, textSize = 13.sp)
            AuthTab.RESET -> TButton(if (acc.busy) "sending…" else Th.say("SEND RESET LINK", "Send a reset link"), {
                note = if (!Account.emailOk(email)) "that e-mail address doesn't look right" else { acc.sendReset(email); null }
            }, Modifier.fillMaxWidth(), enabled = !acc.busy, primary = true, textSize = 13.sp)
        }
        note?.let { Spacer(Modifier.height(6.dp)); TText(it, size = 11.sp, color = C.blood) }
        if (tab == AuthTab.SIGN_UP) {
            Spacer(Modifier.height(8.dp))
            TText("you may get an e-mail with a link to confirm the address; open it, then sign in here. " +
                "your name is shown to other players; your e-mail address is not.", size = 10.sp, color = C.dim)
        }
        if (tab == AuthTab.RESET) {
            Spacer(Modifier.height(8.dp))
            TText("the link opens the web app, where you set a new password.", size = 10.sp, color = C.dim)
        }
    }
    if (Platform.web) {
        Spacer(Modifier.height(14.dp))
        TLink(Th.say("skip: play without an account →", "Play without an account →"), { model.home() }, color = C.hot)
    }
}

/** Starts the sign-in; returns a note for a form that isn't ready, or null. */
private fun signIn(model: AppModel, email: String, pw: String): String? {
    if (!Account.emailOk(email)) return "that e-mail address doesn't look right"
    if (pw.isEmpty()) return "type your password"
    model.account.signIn(email, pw) { if (model.screen == Screen.Account) model.back().also { if (!it) model.home() } }
    return null
}

@Composable
private fun NewPassword(model: AppModel) {
    val acc = model.account
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    Panel(Th.say("NEW PASSWORD", "Set a new password")) {
        TText("signed in as ${acc.email} by the reset link. choose a new password.", size = 11.sp, color = C.dim)
        Spacer(Modifier.height(8.dp))
        TermInput(pw, { pw = it.take(72) }, Modifier.fillMaxWidth(), prompt = "password", placeholder = "8 characters or more", size = 14.sp, password = true)
        Spacer(Modifier.height(8.dp))
        TermInput(pw2, { pw2 = it.take(72) }, Modifier.fillMaxWidth(), prompt = "again", size = 14.sp, password = true)
        Spacer(Modifier.height(12.dp))
        TButton(if (acc.busy) "saving…" else Th.say("SAVE PASSWORD", "Save the password"), {
            note = when {
                pw.length < 8 -> "the password needs 8 characters or more"
                pw != pw2 -> "the two passwords differ"
                else -> { acc.setPassword(pw); null }
            }
        }, Modifier.fillMaxWidth(), enabled = !acc.busy, primary = true, textSize = 13.sp)
        note?.let { Spacer(Modifier.height(6.dp)); TText(it, size = 11.sp, color = C.blood) }
        Spacer(Modifier.height(8.dp))
        TLink("not now", { acc.recovery = false }, color = C.dim)
    }
}

@Composable
private fun SignedIn(model: AppModel) {
    val acc = model.account
    val s = model.settings
    val p = acc.profile
    var changing by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(0) }

    Panel(Th.say("WHOAMI", "Signed in")) {
        KV("name", p?.handle ?: s.handle)
        KV("e-mail", acc.email)
        val div = Ranked.division(s.elo)
        KV("ranked", "${div.name} · ${s.elo} (best ${s.peakElo})")
        KV("rated games", "${s.rankedGames} · ${s.rankedWins} won")
        Spacer(Modifier.height(6.dp))
        TText("change your name in settings: it changes on the account too (names are unique).", size = 10.sp, color = C.dim)
    }
    Spacer(Modifier.height(14.dp))
    Panel(Th.say("SYNC", "Sync")) {
        val at = acc.lastSyncAt
        KV("last sync", if (at == 0L) "not yet" else "${((Platform.epochMillis() - at) / 60_000L).coerceAtLeast(0)} min ago")
        acc.syncNote?.let { TText(it, size = 11.sp, color = if (it.startsWith("sync failed")) C.blood else C.dim) }
        Spacer(Modifier.height(8.dp))
        TButton(Th.say("SYNC NOW", "Sync now"), {
            model.scope.launch {
                val ok = model.account.sync()
                model.toast = if (ok) "synced" else (model.account.syncNote ?: "sync failed")
            }
        }, Modifier.fillMaxWidth(), textSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        TText("settings, looks, runs and friends sync by themselves every few minutes and when the app opens. " +
            "the ranked rating comes from the server.", size = 10.sp, color = C.dim)
    }
    Spacer(Modifier.height(14.dp))
    Panel(Th.say("SECURITY", "Password and account")) {
        if (changing) {
            TermInput(pw, { pw = it.take(72) }, Modifier.fillMaxWidth(), prompt = "new password", placeholder = "8 characters or more",
                size = 14.sp, password = true)
            Spacer(Modifier.height(8.dp))
            TButton(if (acc.busy) "saving…" else "Save the new password", {
                if (pw.length >= 8) { acc.setPassword(pw); pw = ""; changing = false } else model.toast = "the password needs 8 characters or more"
            }, Modifier.fillMaxWidth(), enabled = !acc.busy, textSize = 12.sp)
        } else TButton(Th.say("CHANGE PASSWORD", "Change password"), { changing = true }, Modifier.fillMaxWidth(), textSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        TButton(Th.say("SIGN OUT", "Sign out"), { acc.signOut() }, Modifier.fillMaxWidth(), enabled = !acc.busy, textSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        when (deleting) {
            0 -> TLink("delete the account…", { deleting = 1 }, color = C.dim)
            else -> {
                TText("this deletes the account and its copy on the server (name, rating, synced data). " +
                    "this device keeps its own data.", size = 11.sp, color = C.blood)
                Spacer(Modifier.height(6.dp))
                TButton("Delete the account for good", { acc.deleteAccount(); deleting = 0 }, Modifier.fillMaxWidth(),
                    enabled = !acc.busy, color = C.blood, textSize = 12.sp)
                TLink("keep it", { deleting = 0 }, color = C.dim)
            }
        }
        if (acc.busy) { Spacer(Modifier.height(6.dp)); Spinner() }
    }
}
