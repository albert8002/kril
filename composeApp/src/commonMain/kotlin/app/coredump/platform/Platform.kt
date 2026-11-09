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

/**
 * Returns a function that opens the system photo picker and reports the chosen image as a small PNG
 * (longest side ≤ [maxSide] px), or null (cancelled / unreadable). The result arrives on the main thread.
 */
@Composable
expect fun rememberImagePicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit

/** Opens the system share sheet with [text]. */
expect fun shareText(text: String)

/** Light or dark system bar icons to match the active palette. */
expect fun setSystemBarsDark(dark: Boolean)

/** Plays 16-bit mono PCM at [app.coredump.ui.Sfx.RATE] Hz; several sounds may overlap. gain: 0..1. Never throws. */
expect object SoundOut {
    fun play(pcm: ShortArray, gain: Float)

    /** Loops [pcm] as background music at [gain] (0..1); null stops it. The same array again only changes the level. */
    fun music(pcm: ShortArray?, gain: Float)

    /** The app went to the background (false) or came back (true): music pauses meanwhile. */
    fun foreground(on: Boolean)
}
