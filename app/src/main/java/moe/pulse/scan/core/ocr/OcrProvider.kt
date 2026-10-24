package moe.pulse.scan.core.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrBlock(val text: String, val box: RectF)

data class OcrResult(val blocks: List<OcrBlock>) {
    val fullText: String get() = blocks.joinToString("\n") { it.text }
}

/**
 * On-device Chinese OCR. The bundled model ships inside the APK, so this
 * class performs no network I/O whatsoever.
 */
class OcrProvider(context: Context) {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(jpegBytes: ByteArray): OcrResult {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return OcrResult(emptyList())
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        bitmap.recycle()
        val blocks = text.textBlocks.map { block ->
            OcrBlock(
                text = block.text,
                box = RectF(block.boundingBox),
            )
        }
        return OcrResult(blocks)
    }

    fun close() {
        recognizer.close()
    }
}
