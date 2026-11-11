package app.coredump.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIDevice
import platform.UIKit.popoverPresentationController
import platform.posix.AF_INET
import platform.posix.INET_ADDRSTRLEN
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntop
import platform.posix.sockaddr_in

actual object Platform {
    actual val name: String = "iOS ${UIDevice.currentDevice.systemVersion}"

    actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    private fun path(file: String): String {
        val dirs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val dir = dirs.firstOrNull() as? String ?: ""
        return "$dir/$file"
    }

    actual fun readText(file: String): String? =
        NSString.stringWithContentsOfFile(path(file), NSUTF8StringEncoding, null)

    actual fun writeText(file: String, text: String) {
        NSString.create(string = text).writeToFile(path(file), atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    actual fun delete(file: String) {
        NSFileManager.defaultManager.removeItemAtPath(path(file), null)
    }

    actual fun localIpv4(): String? = memScoped {
        val head = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(head.ptr) != 0) return@memScoped null
        var wifi: String? = null
        var hotspot: String? = null
        var any: String? = null
        try {
            var cur = head.value
            while (cur != null) {
                val ifa = cur.pointed
                val addr = ifa.ifa_addr
                if (addr != null && addr.pointed.sa_family.toInt() == AF_INET) {
                    val name = ifa.ifa_name?.toKString().orEmpty()
                    val sin = addr.reinterpret<sockaddr_in>()
                    val buf = allocArray<ByteVar>(INET_ADDRSTRLEN)
                    inet_ntop(AF_INET, sin.pointed.sin_addr.ptr, buf, INET_ADDRSTRLEN.convert())
                    val ip = buf.toKString()
                    if (!ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                        when {
                            name == "en0" -> wifi = ip
                            name.startsWith("bridge") -> hotspot = ip
                            any == null && !name.startsWith("pdp_ip") && !name.startsWith("utun") -> any = ip
                        }
                    }
                }
                cur = ifa.ifa_next
            }
        } finally {
            freeifaddrs(head.value)
        }
        wifi ?: hotspot ?: any
    }
}

@Composable
actual fun PlatformBack(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; every screen has an on-screen ◂.
}

@Composable
actual fun rememberQrScanLauncher(onResult: (String?) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResult)
    return remember { { QrScanner.present { cb(it) } } }
}

actual fun shareText(text: String) {
    val root = platform.UIKit.UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
    var top: platform.UIKit.UIViewController = root
    while (top.presentedViewController != null) top = top.presentedViewController!!
    val vc = platform.UIKit.UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    vc.popoverPresentationController?.sourceView = top.view
    top.presentViewController(vc, animated = true, completion = null)
}

/** Set by the Swift host: switches SwiftUI's colour scheme (status bar, keyboard) to match the palette. */
object IosBridge {
    var onDark: ((Boolean) -> Unit)? = null
}

actual fun setSystemBarsDark(dark: Boolean) {
    runCatching { IosBridge.onDark?.invoke(dark) }
}

/**
 * Each sound is a short in-memory WAV played by an AVAudioPlayer (ambient: mixes with music, obeys the silent
 * switch). Players are made once per sound and reused (making one per tap/keystroke stalled the main thread):
 * up to [PER_SOUND] of the same sound can overlap; the [MAX_SOUNDS] most recent sounds keep their players.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual object SoundOut {
    private const val PER_SOUND = 3
    private const val MAX_SOUNDS = 48
    private val pool = LinkedHashMap<ShortArray, ArrayList<platform.AVFAudio.AVAudioPlayer>>()
    private var ready = false

    private fun session() {
        if (ready) return
        ready = true
        // ambient: mixes with other audio and follows the silent switch (the player activates the session)
        runCatching<Unit> {
            platform.AVFAudio.AVAudioSession.sharedInstance().setCategory(platform.AVFAudio.AVAudioSessionCategoryAmbient, null)
        }
    }

    private fun player(pcm: ShortArray): platform.AVFAudio.AVAudioPlayer? {
        val wav = app.coredump.ui.Wav.encode(pcm, app.coredump.ui.Sfx.RATE)
        val data: platform.Foundation.NSData = wav.usePinned { pinned ->
            platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = wav.size.convert())
        }
        return platform.AVFAudio.AVAudioPlayer(data = data, error = null).also { it.prepareToPlay() }
    }

    actual fun play(pcm: ShortArray, gain: Float) {
        runCatching {
            session()
            // most recently used last (so the oldest sound is dropped first)
            val list = pool.remove(pcm) ?: ArrayList()
            pool[pcm] = list
            while (pool.size > MAX_SOUNDS) {
                val oldest = pool.keys.first()
                pool.remove(oldest)?.forEach { it.stop() }
            }
            val p = list.firstOrNull { !it.playing }
                ?: (if (list.size < PER_SOUND) player(pcm)?.also { list += it } else list.first())
                ?: return
            p.stop()
            p.currentTime = 0.0
            p.volume = gain.coerceIn(0f, 1f)
            p.play()
        }
    }

    private var loopPcm: ShortArray? = null
    private var loop: platform.AVFAudio.AVAudioPlayer? = null
    private var loopGain = 0f
    private var front = true
    private var watching = false

    actual fun music(pcm: ShortArray?, gain: Float) {
        runCatching {
            watch()
            loopGain = gain.coerceIn(0f, 1f)
            if (pcm == null) {
                loop?.stop(); loop = null; loopPcm = null
                return
            }
            val same = pcm === loopPcm && loop != null
            if (!same) {
                loop?.stop()
                if (!ready) {
                    ready = true
                    runCatching<Unit> {
                        platform.AVFAudio.AVAudioSession.sharedInstance().setCategory(platform.AVFAudio.AVAudioSessionCategoryAmbient, null)
                    }
                }
                val wav = app.coredump.ui.Wav.encode(pcm, app.coredump.ui.Sfx.RATE)
                val data: platform.Foundation.NSData = wav.usePinned { pinned ->
                    platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = wav.size.convert())
                }
                val p = platform.AVFAudio.AVAudioPlayer(data = data, error = null)
                p.numberOfLoops = -1
                p.prepareToPlay()
                loop = p
                loopPcm = pcm
            }
            // a level change on the same loop fades (music ducking under big effects); a new loop starts at its level
            if (same) { loop?.setVolume(loopGain, fadeDuration = 0.25) } else { loop?.volume = loopGain }
            if (front && loop?.playing != true) loop?.play()
        }
    }

    actual fun foreground(on: Boolean) {
        runCatching {
            front = on
            if (on) { if (loop?.playing != true) loop?.play() } else loop?.pause()
        }
    }

    /** Pause with the app, go on when it is back (also after a phone call or Siri took the audio). */
    private fun watch() {
        if (watching) return
        watching = true
        val c = platform.Foundation.NSNotificationCenter.defaultCenter
        c.addObserverForName(platform.UIKit.UIApplicationWillResignActiveNotification, null,
            platform.Foundation.NSOperationQueue.mainQueue) { _ -> foreground(false) }
        c.addObserverForName(platform.UIKit.UIApplicationDidBecomeActiveNotification, null,
            platform.Foundation.NSOperationQueue.mainQueue) { _ -> foreground(true) }
    }
}
