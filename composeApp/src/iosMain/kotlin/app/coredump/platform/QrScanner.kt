package app.coredump.platform

import app.coredump.net.SigilReader
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferGetWidthOfPlane
import platform.CoreVideo.CVPixelBufferIsPlanar
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.NSTextAlignmentCenter
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/**
 * Full-screen camera scanner presented over the Compose view controller: every frame goes to the sigil reader
 * ([FrameTap]); QR codes (direct Wi-Fi codes, older app versions) still come through AVFoundation's own reader.
 */
object QrScanner {
    fun present(onResult: (String?) -> Unit) {
        val root = UIApplication.sharedApplication.keyWindow?.rootViewController
        var top = root
        while (top?.presentedViewController != null) top = top.presentedViewController
        if (top == null) { onResult(null); return }
        val vc = ScanViewController(onResult)
        vc.modalPresentationStyle = UIModalPresentationFullScreen
        top.presentViewController(vc, animated = true, completion = null)
    }
}

class ScanViewController(
    private val onResult: (String?) -> Unit,
) : UIViewController(nibName = null, bundle = null), AVCaptureMetadataOutputObjectsDelegateProtocol {

    private val session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer? = null
    private val tap = FrameTap { code -> dispatch_async(dispatch_get_main_queue()) { finish(code) } }
    private var delivered = false
    private val label = UILabel()
    private val cancel = UIButton.buttonWithType(UIButtonTypeSystem)

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor
        val amber = UIColor.colorWithRed(1.0, 0.69, 0.0, 1.0)

        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) as? AVCaptureDeviceInput }
        if (input != null && session.canAddInput(input)) {
            session.addInput(input)
            val output = AVCaptureMetadataOutput()
            if (session.canAddOutput(output)) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
                output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
            }
            // raw frames for the sigil reader: luminance plane of full-range YUV, 1280×720 is plenty
            if (session.canSetSessionPreset(AVCaptureSessionPreset1280x720)) session.sessionPreset = AVCaptureSessionPreset1280x720
            val video = AVCaptureVideoDataOutput()
            // kCVPixelBufferPixelFormatTypeKey = "PixelFormatType"; '420f' = 875704422
            video.videoSettings = mapOf<Any?, Any?>("PixelFormatType" to NSNumber.numberWithUnsignedInt(875704422u))
            video.alwaysDiscardsLateVideoFrames = true
            video.setSampleBufferDelegate(tap, dispatch_queue_create("app.coredump.sigil", null))
            if (session.canAddOutput(video)) session.addOutput(video)
            val layer = AVCaptureVideoPreviewLayer(session = session)
            layer.videoGravity = AVLayerVideoGravityResizeAspectFill
            view.layer.addSublayer(layer)
            preview = layer
        }

        label.text = if (input == null) "camera unavailable. allow camera access in Settings." else "> hold the host's sigil in view"
        label.textColor = amber
        label.font = UIFont.fontWithName("Menlo-Bold", 15.0) ?: UIFont.boldSystemFontOfSize(15.0)
        label.textAlignment = NSTextAlignmentCenter
        label.numberOfLines = 0
        view.addSubview(label)

        cancel.setTitle("[ ABORT ]", forState = UIControlStateNormal)
        cancel.setTitleColor(amber, forState = UIControlStateNormal)
        cancel.titleLabel?.font = UIFont.fontWithName("Menlo-Bold", 17.0) ?: UIFont.boldSystemFontOfSize(17.0)
        cancel.addTarget(this, NSSelectorFromString("onCancel"), UIControlEventTouchUpInside)
        view.addSubview(cancel)

        if (input != null) {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
                session.startRunning()
            }
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.layer.bounds
        val w = view.bounds.useContents { size.width }
        val h = view.bounds.useContents { size.height }
        label.setFrame(CGRectMake(20.0, 70.0, w - 40.0, 60.0))
        cancel.setFrame(CGRectMake(20.0, h - 110.0, w - 40.0, 54.0))
    }

    @ObjCAction
    fun onCancel() {
        finish(null)
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val code = didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject ?: return
        val text = code.stringValue ?: return
        finish(text)
    }

    private fun finish(text: String?) {
        if (delivered) return
        delivered = true
        session.stopRunning()
        dismissViewControllerAnimated(true) { onResult(text) }
    }
}

/**
 * Camera frames (on its own queue) to the sigil reader: copies the luminance plane (or the green of BGRA) into a
 * reused buffer, halving frames wider than 1600 px; [onCode] once two frames agree.
 */
@OptIn(ExperimentalForeignApi::class)
class FrameTap(private val onCode: (String) -> Unit) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    private val reader = SigilReader()
    private var buf = ByteArray(0)
    private var row = ByteArray(0)
    private var done = false

    @ObjCSignatureOverride
    override fun captureOutput(output: AVCaptureOutput, didOutputSampleBuffer: CMSampleBufferRef?, fromConnection: AVCaptureConnection) {
        if (done) return
        val img = CMSampleBufferGetImageBuffer(didOutputSampleBuffer) ?: return
        CVPixelBufferLockBaseAddress(img, kCVPixelBufferLock_ReadOnly)
        var w = 0
        var h = 0
        try {
            val planar = CVPixelBufferIsPlanar(img)
            val srcW = (if (planar) CVPixelBufferGetWidthOfPlane(img, 0uL) else CVPixelBufferGetWidth(img)).toInt()
            val srcH = (if (planar) CVPixelBufferGetHeightOfPlane(img, 0uL) else CVPixelBufferGetHeight(img)).toInt()
            val stride = (if (planar) CVPixelBufferGetBytesPerRowOfPlane(img, 0uL) else CVPixelBufferGetBytesPerRow(img)).toInt()
            val base = (if (planar) CVPixelBufferGetBaseAddressOfPlane(img, 0uL) else CVPixelBufferGetBaseAddress(img))
                ?.reinterpret<ByteVar>() ?: return
            val step = if (srcW > 1600) 2 else 1
            w = srcW / step; h = srcH / step
            if (buf.size < w * h) buf = ByteArray(w * h)
            if (planar && step == 1) {
                buf.usePinned { dst ->
                    for (y in 0 until h) memcpy(dst.addressOf(y * w), base + (y.toLong() * stride), w.convert())
                }
            } else {
                // every [step]-th pixel; BGRA: the green byte stands in for luminance
                val px = if (planar) 1 else 4
                val off = if (planar) 0 else 1
                if (row.size < stride) row = ByteArray(stride)
                row.usePinned { r ->
                    for (y in 0 until h) {
                        memcpy(r.addressOf(0), base + (y.toLong() * step * stride), stride.convert())
                        for (x in 0 until w) buf[y * w + x] = row[x * step * px + off]
                    }
                }
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(img, kCVPixelBufferLock_ReadOnly)
        }
        if (w == 0 || h == 0) return
        val code = runCatching { reader.scan(buf, w, h) }.getOrNull() ?: return
        done = true
        onCode(code)
    }
}
