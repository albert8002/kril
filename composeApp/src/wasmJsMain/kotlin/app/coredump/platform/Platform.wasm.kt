package app.coredump.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.coredump.sigil.SigilFrame
import app.coredump.sigil.SigilScanner

/**
 * Files of the web app: all of them are read into memory once at start ([WebStore.load], before the app is drawn), so
 * reads stay instant like on a phone; every write goes to memory and to the browser's IndexedDB at once.
 */
internal object WebStore {
    val files = HashMap<String, String>()
    var idb = false

    fun load(done: () -> Unit) {
        jsStoreLoad({ k, v -> files[k] = v }) { ok -> idb = ok; done() }
    }
}

actual object Platform {
    actual val name: String = "Web"
    actual val web: Boolean = true

    actual fun epochMillis(): Long = jsNow().toLong()

    actual fun readText(file: String): String? = WebStore.files[file]

    actual fun writeText(file: String, text: String) {
        WebStore.files[file] = text
        jsStorePut(file, text, WebStore.idb)
    }

    actual fun delete(file: String) {
        WebStore.files.remove(file)
        jsStoreDelete(file, WebStore.idb)
    }

    actual fun localIpv4(): String? = null
}

/** The browser's back button (and swipe back): one history entry while a screen other than the menu is open. */
@Composable
actual fun PlatformBack(enabled: Boolean, onBack: () -> Unit) {
    val cb by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val h = jsBackListen { cb() }
        onDispose { jsBackStop(h) }
    }
}

/** Bytes from a string of char codes 0..255. */
internal fun bytesOf(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

@Composable
actual fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResult)
    return remember {
        {
            // every look's sigil (it may send its code over several frames), and plain QR codes where the browser reads them
            val sigils = SigilScanner()
            jsScanOpen({ lum, w, h, t ->
                runCatching { sigils.feed(SigilFrame(bytesOf(lum), w, h, w, 0, t.toLong())) }.getOrNull()
            }) { code -> cb(code) }
        }
    }
}

@Composable
actual fun rememberImagePicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResult)
    return remember(maxSide) { { jsPickImage(maxSide) { s -> cb(s?.let { bytesOf(it) }) } } }
}

actual fun shareText(text: String) = jsShare(text)

actual fun setSystemBarsDark(dark: Boolean) = jsColorScheme(dark)

/**
 * Web Audio: each sound is turned into a browser sound buffer once (kept by array identity, like the phone apps) and
 * played through its own gain. Nothing sounds before the first tap (browsers need one); music waiting for it starts then.
 */
actual object SoundOut {
    private const val MAX_SOUNDS = 96
    private val ids = HashMap<ShortArray, Int>()
    private var next = 0
    private var armed = false
    private var musicPcm: ShortArray? = null
    private var musicGain = 0f

    private fun arm() {
        if (armed) return
        armed = true
        jsAudioArm { musicPcm?.let { music(it, musicGain) } }
    }

    /** 16-bit PCM as char codes 0..32767 (one bit less; char codes stay clear of the surrogate range). */
    private fun encode(pcm: ShortArray): String {
        val sb = StringBuilder(pcm.size)
        for (v in pcm) sb.append(((v.toInt() shr 1) + 16384).toChar())
        return sb.toString()
    }

    private fun id(pcm: ShortArray): Int? {
        ids[pcm]?.let { return it }
        if (!jsAudioRunning()) return null
        if (ids.size >= MAX_SOUNDS) {
            ids.clear()
            jsAudioForget()
            // the music loop is made again below if it is still wanted
            musicPcm?.let { if (it !== pcm) jsAudioMusic(-1, 0.0) }
        }
        val i = next++
        jsAudioMake(i, encode(pcm), app.coredump.ui.Sfx.RATE)
        ids[pcm] = i
        return i
    }

    actual fun play(pcm: ShortArray, gain: Float) {
        runCatching {
            arm()
            if (!jsAudioRunning()) return
            val i = id(pcm) ?: return
            jsAudioPlay(i, gain.coerceIn(0f, 1f).toDouble())
        }
    }

    actual fun music(pcm: ShortArray?, gain: Float) {
        runCatching {
            arm()
            musicPcm = pcm
            musicGain = gain.coerceIn(0f, 1f)
            if (pcm == null) { jsAudioMusic(-1, 0.0); return }
            if (!jsAudioRunning()) return
            val i = id(pcm) ?: return
            jsAudioMusic(i, musicGain.toDouble())
        }
    }

    actual fun foreground(on: Boolean) {
        runCatching { jsAudioFront(on) }
    }
}
