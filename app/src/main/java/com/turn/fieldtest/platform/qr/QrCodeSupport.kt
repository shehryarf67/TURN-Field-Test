package com.turn.fieldtest.platform.qr

import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

interface QrCodeGenerator {
    fun generate(payload: QrAnchorPayload, sizePixels: Int = 1024): Bitmap
    fun writePng(payload: QrAnchorPayload, output: OutputStream, sizePixels: Int = 1024)
}

class ZxingQrCodeGenerator(
    private val payloadCodec: QrAnchorPayloadCodec = QrAnchorPayloadCodec(),
) : QrCodeGenerator {
    override fun generate(payload: QrAnchorPayload, sizePixels: Int): Bitmap {
        require(sizePixels in 128..4096) { "QR image size must be between 128 and 4096 pixels" }
        val encoded = payloadCodec.encode(payload)
        val matrix = QRCodeWriter().encode(
            encoded,
            BarcodeFormat.QR_CODE,
            sizePixels,
            sizePixels,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
            ),
        )
        val pixels = IntArray(sizePixels * sizePixels)
        for (y in 0 until sizePixels) {
            val rowOffset = y * sizePixels
            for (x in 0 until sizePixels) {
                pixels[rowOffset + x] = if (matrix[x, y]) BLACK else WHITE
            }
        }
        return Bitmap.createBitmap(sizePixels, sizePixels, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePixels, 0, 0, sizePixels, sizePixels)
        }
    }

    override fun writePng(payload: QrAnchorPayload, output: OutputStream, sizePixels: Int) {
        val bitmap = generate(payload, sizePixels)
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not encode QR PNG" }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val BLACK = 0xff000000.toInt()
        const val WHITE = 0xffffffff.toInt()
    }
}

data class QrScanFrameResult(
    val rawPayload: String,
    val rotationDegrees: Int,
)

/**
 * CameraX analyzer backed by ML Kit's on-device QR recognizer. It closes every ImageProxy and
 * permits only one in-flight frame, preventing camera-buffer starvation during field use.
 */
@ExperimentalGetImage
class MlKitQrAnalyzer(
    private val onPayload: (QrScanFrameResult) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    ),
) : ImageAnalysis.Analyzer, Closeable {
    private val processing = AtomicBoolean(false)
    @Volatile private var closed = false

    override fun analyze(imageProxy: ImageProxy) {
        if (closed || !processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        scanner.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnSuccessListener { barcodes ->
                barcodes.asSequence()
                    .filter { it.format == Barcode.FORMAT_QR_CODE }
                    .mapNotNull { it.rawValue }
                    .firstOrNull()
                    ?.let { onPayload(QrScanFrameResult(it, rotation)) }
            }
            .addOnFailureListener(onFailure)
            .addOnCompleteListener {
                processing.set(false)
                imageProxy.close()
            }
    }

    override fun close() {
        closed = true
        scanner.close()
    }
}
