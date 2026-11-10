package app.coredump

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coredump.platform.CrashLog
import app.coredump.platform.PlatformBack
import app.coredump.ui.C
import app.coredump.ui.LocalDisplay
import app.coredump.ui.LocalFx
import app.coredump.ui.LocalLook
import app.coredump.ui.LocalMono
import app.coredump.ui.Look
import app.coredump.ui.TText
import app.coredump.ui.Th
import app.coredump.ui.ThemedScreen
import app.coredump.ui.box
import app.coredump.ui.displayFont
import app.coredump.ui.screens.AppearanceScreen
import app.coredump.ui.screens.BootScreen
import app.coredump.ui.screens.BrowseScreen
import app.coredump.ui.screens.ConfigScreen
import app.coredump.ui.screens.DumpScreen
import app.coredump.ui.screens.LobbyScreen
import app.coredump.ui.screens.ManualScreen
import app.coredump.ui.screens.MenuScreen
import app.coredump.ui.screens.PlayScreen
import app.coredump.ui.screens.SetupScreen
import app.coredump.ui.screens.StatsScreen
import app.coredump.ui.screens.SummaryScreen
import app.coredump.ui.styleFont
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

@Composable
fun App() {
    // App-wide scope: a failing background job is logged, never takes the app down.
    val scope = remember {
        CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> CrashLog.note("coroutine", e) })
    }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    val model = remember { AppModel(scope) }
    val s = model.settings
    val look = Look(
        glow = s.glow,
        scanlines = s.scanlines,
        flicker = s.flicker,
        reduceMotion = s.reduceMotion,
        haptics = s.haptics,
        hyper = s.theme in s.hyper && !s.reduceMotion,
    )
    if (SelfTest.enabled) LaunchedEffect(Unit) { SelfTest.run(model) }

    val style = Th.style
    val dark = Th.pal.barsDark
    LaunchedEffect(dark) { app.coredump.platform.setSystemBarsDark(dark) }
    CompositionLocalProvider(
        LocalMono provides styleFont(style),
        LocalDisplay provides displayFont(style),
        LocalLook provides look,
        LocalFx provides model.fx,
    ) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * s.textScale)) {
            ThemedScreen(look, model.fx) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AnimatedContent(
                        targetState = model.screen,
                        transitionSpec = {
                            // each archetype changes screens its own way
                            when (Th.style) {
                                app.coredump.ui.Style.TERMINAL ->
                                    (fadeIn(tween(240, delayMillis = 70)) + scaleIn(tween(300, delayMillis = 70), initialScale = 0.985f))
                                        .togetherWith(fadeOut(tween(110)))
                                app.coredump.ui.Style.NEON ->   // zoom through the cabinet glass
                                    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(320, delayMillis = 60), initialScale = 1.08f))
                                        .togetherWith(fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 0.92f))
                                app.coredump.ui.Style.PAPER ->  // page turn
                                    (slideInHorizontally(tween(340)) { it / 3 } + fadeIn(tween(260)))
                                        .togetherWith(slideOutHorizontally(tween(260)) { -it / 5 } + fadeOut(tween(200)))
                                app.coredump.ui.Style.CLEAN ->  // card rises
                                    (slideInVertically(tween(300)) { it / 12 } + fadeIn(tween(240)))
                                        .togetherWith(fadeOut(tween(120)))
                                app.coredump.ui.Style.PIXEL ->  // stepped fade, like an old handheld
                                    fadeIn(tween(240, delayMillis = 120, easing = Steps4)).togetherWith(fadeOut(tween(120, easing = Steps4)))
                                app.coredump.ui.Style.COMIC ->  // panel slams in from the left
                                    (slideInHorizontally(spring(dampingRatio = 0.62f, stiffness = 420f)) { -it } + fadeIn(tween(120)))
                                        .togetherWith(slideOutHorizontally(tween(200)) { it / 2 } + fadeOut(tween(160)))
                                app.coredump.ui.Style.ABYSS ->  // sink: the next screen rises from below, slowly
                                    (slideInVertically(tween(560, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(460)))
                                        .togetherWith(slideOutVertically(tween(420)) { -it / 10 } + fadeOut(tween(300)))
                                app.coredump.ui.Style.METRO ->  // train pulls in from the right
                                    slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { it }
                                        .togetherWith(slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { -it })
                                app.coredump.ui.Style.JACKPOT ->  // reel drops in and settles
                                    (slideInVertically(spring(dampingRatio = 0.72f, stiffness = 380f)) { -it } + fadeIn(tween(120)))
                                        .togetherWith(slideOutVertically(tween(220)) { it / 2 } + fadeOut(tween(180)))
                                app.coredump.ui.Style.CHAT ->  // pushed like a chat thread
                                    slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it }
                                        .togetherWith(slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(300)))
                                app.coredump.ui.Style.DESKTOP ->  // windows just appear
                                    fadeIn(tween(60)).togetherWith(fadeOut(tween(40)))
                                app.coredump.ui.Style.ORACLE ->  // slow fade, like smoke
                                    (fadeIn(tween(520)) + scaleIn(tween(620), initialScale = 0.96f)).togetherWith(fadeOut(tween(320)))
                            }
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
                            Screen.Stats -> StatsScreen(model)
                            Screen.Appearance -> AppearanceScreen(model)
                            Screen.Fetch -> app.coredump.ui.screens.FetchScreen(model)
                            Screen.Looks -> app.coredump.ui.screens.LooksScreen(model)
                            Screen.Meltdown -> app.coredump.ui.screens.MeltdownScreen(model)
                            is Screen.Melt -> app.coredump.ui.screens.MeltScreen(model, screen.session)
                        }
                    }

                    val t = model.toast
                    if (t != null) {
                        LaunchedEffect(t) { delay(2600); model.toast = null }
                        TText(
                            (if (Th.terminal) "! " else "") + t,
                            Modifier.align(Alignment.BottomCenter).padding(20.dp)
                                .box(Th.style, C.blood, C.bg, pill = Th.style == app.coredump.ui.Style.CLEAN || Th.style == app.coredump.ui.Style.NEON || Th.style == app.coredump.ui.Style.CHAT || Th.style == app.coredump.ui.Style.ABYSS)
                                .padding(horizontal = 16.dp, vertical = 11.dp),
                            size = 13.sp, color = C.blood,
                        )
                    }
                }
            }
        }
    }

    PlatformBack(enabled = model.screen != Screen.Boot && model.screen != Screen.Menu) { model.back() }
}

private val Steps4 = Easing { (it * 4f).toInt() / 4f }
