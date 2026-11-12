package app.coredump.platform

import android.os.Bundle
import app.coredump.net.SigilReader
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.Result
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory

/**
 * The camera scanner: zxing-embedded's capture screen with a decoder that looks for a sigil first ([SigilReader])
 * and falls back to QR codes (direct Wi-Fi codes and older app versions).
 */
class SigilScanActivity : CaptureActivity() {
    private var view: DecoratedBarcodeView? = null

    override fun initializeContent(): DecoratedBarcodeView = super.initializeContent().also { view = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // after the capture manager set its own factory from the intent
        view?.barcodeView?.decoderFactory = SigilDecoders
    }
}

object SigilDecoders : DecoderFactory {
    override fun createDecoder(baseHints: Map<DecodeHintType, *>?): Decoder {
        val qr = MultiFormatReader()
        qr.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        return SigilDecoder(qr)
    }
}

/** One per decoder thread: the sigil reader keeps its buffers and needs two agreeing frames. */
class SigilDecoder(qr: MultiFormatReader) : Decoder(qr) {
    private val sigils = SigilReader()

    override fun decode(source: LuminanceSource): Result? {
        val code = runCatching { sigils.scan(source.matrix, source.width, source.height) }.getOrNull()
        if (code != null) return Result(code, null, null, BarcodeFormat.QR_CODE)
        return super.decode(source)
    }
}
