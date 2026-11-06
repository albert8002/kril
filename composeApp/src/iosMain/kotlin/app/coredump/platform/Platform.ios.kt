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
import platform.posix.AF_INET
import platform.posix.INET_ADDRSTRLEN
import platform.posix.freeifaddrs
import platform.posix.getifaddrs
import platform.posix.ifaddrs
import platform.posix.inet_ntop
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
