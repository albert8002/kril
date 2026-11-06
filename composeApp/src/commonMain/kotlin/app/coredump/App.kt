package app.coredump

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.platform.PlatformBack
import app.coredump.ui.C
import app.coredump.ui.CrtScreen
import app.coredump.ui.LocalFx
import app.coredump.ui.LocalLook
import app.coredump.ui.LocalMono
import app.coredump.ui.Look
import app.coredump.ui.TText
import app.coredump.ui.monoFamily
import app.coredump.ui.screens.BootScreen
import app.coredump.ui.screens.BrowseScreen
import app.coredump.ui.screens.ConfigScreen
import app.coredump.ui.screens.DumpScreen
import app.coredump.ui.screens.LobbyScreen
import app.coredump.ui.screens.ManualScreen
import app.coredump.ui.screens.MenuScreen
import app.coredump.ui.screens.PlayScreen
import app.coredump.ui.screens.SetupScreen
import app.coredump.ui.screens.SummaryScreen
import kotlinx.coroutines.delay

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val model = remember { AppModel(scope) }
    val s = model.settings
    val look = Look(
        glow = s.glow,
        scanlines = s.scanlines,
        flicker = s.flicker,
        reduceMotion = s.reduceMotion,
        haptics = s.haptics,
    )

    CompositionLocalProvider(
        LocalMono provides monoFamily(),
        LocalLook provides look,
        LocalFx provides model.fx,
    ) {
        CrtScreen(look, model.fx) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                AnimatedContent(
                    targetState = model.screen,
                    transitionSpec = {
                        (fadeIn(tween(240, delayMillis = 70)) + scaleIn(tween(300, delayMillis = 70), initialScale = 0.985f))
                            .togetherWith(fadeOut(tween(110)))
                    },
                ) { screen ->
                    when (screen) {
                        Screen.Boot -> BootScreen(model)
                        Screen.Menu -> MenuScreen(model)
                        is Screen.Setup -> SetupScreen(model, screen.forHost)
                        is Screen.Play -> PlayScreen(model, screen.session)
                        is Screen.Summary -> SummaryScreen(model, screen.session)
                        Screen.Browse -> BrowseScreen(model)
                        is Screen.Dump -> DumpScreen(model, screen.promptId)
                        Screen.Config -> ConfigScreen(model)
                        Screen.Lobby -> LobbyScreen(model)
                        Screen.Manual -> ManualScreen(model)
                    }
                }

                val t = model.toast
                if (t != null) {
                    LaunchedEffect(t) { delay(2600); model.toast = null }
                    TText(
                        "! $t",
                        Modifier.align(Alignment.BottomCenter).padding(20.dp)
                            .background(C.bg).border(1.dp, C.blood).padding(horizontal = 14.dp, vertical = 10.dp),
                        size = 13.sp, color = C.blood,
                    )
                }
            }
        }
    }

    PlatformBack(enabled = model.screen != Screen.Boot && model.screen != Screen.Menu) { model.back() }
}
