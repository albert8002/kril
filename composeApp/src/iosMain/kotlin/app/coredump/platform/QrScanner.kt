package app.coredump.platform

import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
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

/** Full-screen AVFoundation QR scanner presented over the Compose view controller. */
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
            val layer = AVCaptureVideoPreviewLayer(session = session)
            layer.videoGravity = AVLayerVideoGravityResizeAspectFill
            view.layer.addSublayer(layer)
            preview = layer
        }

        label.text = if (input == null) "camera unavailable. allow camera access in Settings." else "> align the host's sigil"
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
