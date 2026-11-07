package app.coredump.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

@Composable
actual fun rememberImagePicker(maxSide: Int, onResult: (ByteArray?) -> Unit): () -> Unit {
    val cb by rememberUpdatedState(onResult)
    return remember { { ImagePicker.present(maxSide) { cb(it) } } }
}

/** System photo picker (no photo-library permission needed); the chosen image comes back as a small PNG. */
object ImagePicker {
    /** The picker holds its delegate weakly: keep it alive until the pick is over. */
    private var delegate: PickDelegate? = null

    fun present(maxSide: Int, done: (ByteArray?) -> Unit) {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        var top: UIViewController? = root
        while (top?.presentedViewController != null) top = top.presentedViewController
        if (top == null) { done(null); return }
        val config = PHPickerConfiguration()
        config.filter = PHPickerFilter.imagesFilter
        config.selectionLimit = 1
        val picker = PHPickerViewController(configuration = config)
        val d = PickDelegate(maxSide) { bytes -> delegate = null; done(bytes) }
        delegate = d
        picker.delegate = d
        top.presentViewController(picker, animated = true, completion = null)
    }
}

class PickDelegate(private val maxSide: Int, private val done: (ByteArray?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) { done(null); return }
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val png = data?.let { runCatching { shrink(it, maxSide) }.getOrNull() }
            dispatch_async(dispatch_get_main_queue()) { done(png) }
        }
    }
}

/** Redraws the image with its longest side at most [maxSide] px (orientation applied) and returns PNG bytes. */
@OptIn(ExperimentalForeignApi::class)
private fun shrink(data: NSData, maxSide: Int): ByteArray? {
    val img = UIImage.imageWithData(data) ?: return null
    val (w, h) = img.size.useContents { width to height }
    if (w <= 0.0 || h <= 0.0) return null
    val s = minOf(1.0, maxSide / maxOf(w, h))
    val tw = maxOf(1.0, kotlin.math.floor(w * s))
    val th = maxOf(1.0, kotlin.math.floor(h * s))
    val format = UIGraphicsImageRendererFormat()
    format.scale = 1.0
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(tw, th), format = format)
    val out = renderer.imageWithActions { _ -> img.drawInRect(CGRectMake(0.0, 0.0, tw, th)) }
    val png = UIImagePNGRepresentation(out) ?: return null
    val n = png.length.toInt()
    if (n <= 0) return null
    val bytes = ByteArray(n)
    bytes.usePinned { memcpy(it.addressOf(0), png.bytes, png.length) }
    return bytes
}
