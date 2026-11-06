package app.coredump

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.coredump.platform.AndroidContext
import app.coredump.platform.CrashLog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidContext.init(applicationContext)
        AndroidContext.activity = java.lang.ref.WeakReference(this)
        installCrashHandler()
        SelfTest.enabled = intent?.getBooleanExtra("selftest", false) == true
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    /** Writes a crash report before the default handler kills the process (shown on next launch). */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashHook) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHook(prev))
    }

    private class CrashHook(val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { CrashLog.record("thread ${t.name}", e) }
            prev?.uncaughtException(t, e)
        }
    }
}
