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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                    .setCaptureActivity(SigilScanActivity::class.java)
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("> align the host's sigil")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }
    }
}

@Composable
actual fun rememberImagePicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResult)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            cb(null)
        } else {
            scope.launch {
                val png = withContext(Dispatchers.IO) { runCatching { shrinkImage(uri, maxSide) }.getOrNull() }
                cb(png)
            }
        }
    }
    return remember(launcher) {
        {
            launcher.launch(androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

/** Decodes [uri] at a small size (photo orientation applied on Android 9+) and returns it as PNG bytes. */
private fun shrinkImage(uri: android.net.Uri, maxSide: Int): ByteArray? {
    val cr = AndroidContext.app.contentResolver
    val bmp: android.graphics.Bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
        val src = android.graphics.ImageDecoder.createSource(cr, uri)
        android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val s = minOf(1f, maxSide.toFloat() / maxOf(w, h).coerceAtLeast(1))
            dec.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val s = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
        if (s < 1f) android.graphics.Bitmap.createScaledBitmap(raw, maxOf(1, (raw.width * s).toInt()), maxOf(1, (raw.height * s).toInt()), true) else raw
    }
    val out = java.io.ByteArrayOutputStream()
    if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) return null
    return out.toByteArray()
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

/**
 * One streaming AudioTrack fed by a small mixer thread (low latency, any number of overlapping sounds).
 * The track pauses after a second of silence and wakes up on the next sound.
 */
actual object SoundOut {
    private const val FRAMES = 256
    private class Voice(val pcm: ShortArray, val gain: Float) { var pos = 0 }
    private val lock = java.lang.Object()
    private val voices = ArrayList<Voice>()
    private var thread: Thread? = null

    // music: the playing loop, the one waiting to replace it (after a short fade), levels and position
    private var loopPcm: ShortArray? = null
    private var nextPcm: ShortArray? = null
    private var hasNext = false
    private var loopPos = 0
    private var loopTarget = 0f
    private var loopGain = 0f
    private var front = true

    actual fun play(pcm: ShortArray, gain: Float) {
        runCatching {
            synchronized(lock) {
                if (voices.size >= 10) voices.removeAt(0)
                voices += Voice(pcm, gain.coerceIn(0f, 1f))
                ensureThread()
                lock.notifyAll()
            }
        }
    }

    actual fun music(pcm: ShortArray?, gain: Float) {
        runCatching {
            synchronized(lock) {
                if (pcm !== loopPcm) { nextPcm = pcm; hasNext = true }
                loopTarget = if (pcm == null) 0f else gain.coerceIn(0f, 1f)
                if (pcm != null) ensureThread()
                lock.notifyAll()
            }
        }
    }

    actual fun foreground(on: Boolean) {
        runCatching { synchronized(lock) { front = on; if (on && loopPcm != null) ensureThread(); lock.notifyAll() } }
    }

    /** Call with [lock] held. */
    private fun ensureThread() {
        if (thread?.isAlive != true) thread = Thread({ loop() }, "sfx").apply { isDaemon = true; start() }
    }

    /** Something to play: an effect, or music while the app is in front. Call with [lock] held. */
    private fun busy(): Boolean = voices.isNotEmpty() || (front && (loopPcm != null || (hasNext && nextPcm != null)))

    /** Adds this block of music to [mix]: level glides (no clicks), a new loop fades the old one out first. */
    private fun mixMusic(mix: FloatArray) {
        if (!front) return
        val want = if (hasNext) 0f else loopTarget
        val end = loopGain + (want - loopGain) * 0.25f
        val m = loopPcm
        if (m != null && m.isNotEmpty()) {
            for (i in mix.indices) {
                val g = loopGain + (end - loopGain) * (i.toFloat() / mix.size)
                mix[i] += m[loopPos] * g
                loopPos++
                if (loopPos >= m.size) loopPos = 0
            }
        }
        loopGain = end
        if (hasNext && (loopGain < 0.003f || m == null)) {
            loopPcm = nextPcm; nextPcm = null; hasNext = false; loopPos = 0; loopGain = 0f
        }
    }

    private fun loop() {
        val rate = app.coredump.ui.Sfx.RATE
        val track = runCatching {
            val min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT)
            android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, FRAMES * 2 * 4))
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: run { synchronized(lock) { voices.clear(); thread = null }; return }
        val mix = FloatArray(FRAMES)
        val out = ShortArray(FRAMES)
        var quiet = 0
        try {
            track.play()
            while (true) {
                synchronized(lock) {
                    if (!busy()) {
                        quiet++
                        if (quiet > rate / FRAMES) {   // ~1 s of silence: stop until the next sound
                            track.pause(); track.flush()
                            while (!busy()) lock.wait()
                            track.play()
                            quiet = 0
                        }
                    } else quiet = 0
                    java.util.Arrays.fill(mix, 0f)
                    mixMusic(mix)
                    val it = voices.iterator()
                    while (it.hasNext()) {
                        val v = it.next()
                        val n = minOf(FRAMES, v.pcm.size - v.pos)
                        for (i in 0 until n) mix[i] += v.pcm[v.pos + i] * v.gain
                        v.pos += n
                        if (v.pos >= v.pcm.size) it.remove()
                    }
                }
                for (i in 0 until FRAMES) {
                    // soft limit so overlapping sounds never clip harshly
                    val x = mix[i] / 32767f
                    out[i] = ((x / (1f + kotlin.math.abs(x) * 0.6f)) * 32767f).toInt().coerceIn(-32767, 32767).toShort()
                }
                track.write(out, 0, FRAMES)
            }
        } catch (e: Throwable) {
            // audio is a nice-to-have: give up quietly
        } finally {
            runCatching { track.release() }
            synchronized(lock) { voices.clear(); thread = null }
        }
    }
}
