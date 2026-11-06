package app.coredump.platform

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

@SuppressLint("StaticFieldLeak")
object AndroidContext {
    lateinit var app: Context
        private set
    var activity: java.lang.ref.WeakReference<android.app.Activity>? = null

    fun init(c: Context) { app = c.applicationContext }
}

actual object Platform {
    actual val name: String = "Android ${android.os.Build.VERSION.RELEASE}"

    actual fun epochMillis(): Long = System.currentTimeMillis()

    private fun file(name: String) = File(AndroidContext.app.filesDir, name)

    actual fun readText(file: String): String? = runCatching {
        val f = file(file)
        if (f.exists()) f.readText() else null
    }.getOrNull()

    actual fun writeText(file: String, text: String) {
        val f = file(file)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    actual fun delete(file: String) { file(file).delete() }

    actual fun localIpv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
            .filter { (_, a) -> !a.isLoopbackAddress && a.isSiteLocalAddress }
        // prefer Wi-Fi, then hotspot/AP interfaces, then anything private
        val pick = candidates.firstOrNull { it.first.startsWith("wlan") }
            ?: candidates.firstOrNull { it.first.startsWith("ap") || it.first.startsWith("swlan") }
            ?: candidates.firstOrNull()
        pick?.second?.hostAddress
    }.getOrNull()
}

@Composable
actual fun PlatformBack(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> cb(r.contents) }
    return remember(launcher) {
        {
            launcher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("> align the host's sigil")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }
    }
}

actual fun shareText(text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    val chooser = android.content.Intent.createChooser(send, "share dump").apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { AndroidContext.app.startActivity(chooser) }
}

actual fun setSystemBarsDark(dark: Boolean) {
    val a = AndroidContext.activity?.get() as? androidx.activity.ComponentActivity ?: return
    a.runOnUiThread {
        runCatching {
            val t = android.graphics.Color.TRANSPARENT
            val style = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t)
            a.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
    }
}
