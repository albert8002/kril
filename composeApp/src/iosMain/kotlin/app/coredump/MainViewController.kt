package app.coredump

import androidx.compose.ui.window.ComposeUIViewController
import app.coredump.platform.CrashLog
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import app.coredump.platform.IosBridge

@OptIn(ExperimentalNativeApi::class)
fun MainViewController(onDark: (Boolean) -> Unit): UIViewController {
    IosBridge.onDark = onDark
    // Save a report for any uncaught Kotlin exception; the runtime still terminates afterwards.
    setUnhandledExceptionHook { e -> runCatching { CrashLog.record("ios", e) } }
    SelfTest.enabled = NSProcessInfo.processInfo.arguments.any { it == "-selftest" || it == "--selftest" }
    return ComposeUIViewController { App() }
}
