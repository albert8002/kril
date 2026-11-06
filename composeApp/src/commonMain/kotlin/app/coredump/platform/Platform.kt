package app.coredump.platform

import androidx.compose.runtime.Composable

/** Small platform layer: storage, clock, network address. */
expect object Platform {
    val name: String
    fun epochMillis(): Long
    fun readText(file: String): String?
    fun writeText(file: String, text: String)
    fun delete(file: String)
    /** Best guess of this device's IPv4 address on the local Wi-Fi. */
    fun localIpv4(): String?
}

/** System back (Android). No-op on iOS. */
@Composable
expect fun PlatformBack(enabled: Boolean, onBack: () -> Unit)

/** Returns a function that opens the camera QR scanner and reports the decoded text (null = cancelled). */
@Composable
expect fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit

/** Opens the system share sheet with [text]. */
expect fun shareText(text: String)

/** Light or dark system bar icons to match the active palette. */
expect fun setSystemBarsDark(dark: Boolean)
