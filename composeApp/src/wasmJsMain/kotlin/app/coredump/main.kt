package app.coredump

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.coredump.platform.AppLife
import app.coredump.platform.SoundOut
import app.coredump.platform.WebStore
import app.coredump.platform.jsBooted
import app.coredump.platform.jsHref
import app.coredump.platform.jsOnVisibility
import kotlinx.browser.document

/** The web app: stored files are read first (so reads are instant), then the same app as on the phones is drawn. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    WebStore.load {
        val href = jsHref()
        SelfTest.enabled = Regex("[?&]selftest([=&#]|$)").containsMatchIn(href)
        // a sign-in link from an e-mail brings its tokens in the address: taken, then cleared from the address bar
        if (app.coredump.net.Cloud.bootLink(href)) app.coredump.platform.jsClearHash()
        jsOnVisibility { on ->
            SoundOut.foreground(on)
            if (on) AppLife.foreground()
        }
        ComposeViewport(document.body!!) {
            LaunchedEffect(Unit) { jsBooted() }
            App()
        }
    }
}
