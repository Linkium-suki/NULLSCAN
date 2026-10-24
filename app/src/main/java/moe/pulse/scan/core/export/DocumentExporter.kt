package moe.pulse.scan.core.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import moe.pulse.scan.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export helpers: PDF (native android.graphics.pdf), gallery PNG/JPG,
 * OCR text file, all shared through FileProvider. File naming follows the
 * legacy scheme with the Pulse brand: PS_<tags|BATCH>_<yyyyMMdd_HHmm>.<ext>
 */
object DocumentExporter {

    const val BRAND = "PULSE"
    const val FILE_PREFIX = "PS"
    const val WATERMARK_TEXT = "PAGE %d | $BRAND"

    fun generateFileName(tags: Set<String>, ext: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val tagPart = if (tags.isEmpty()) "BATCH" else tags.sorted().joinToString("-")
        return "${FILE_PREFIX}_${tagPart}_$timestamp.$ext"
    }

    fun exportPdf(context: Context, pages: List<ByteArray>, outFile: File, watermark: Boolean): File {
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@forEachIndexed
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas: Canvas = page.canvas
                canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), null)
                if (watermark) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(158, 158, 158) // grey500
                        textSize = (bitmap.width * 0.025f).coerceAtLeast(24f)
                    }
                    val text = WATERMARK_TEXT.format(index + 1)
                    val x = bitmap.width - paint.measureText(text) - bitmap.width * 0.03f
                    val y = bitmap.height - bitmap.height * 0.03f
                    canvas.drawText(text, x, y, paint)
                }
                pdf.finishPage(page)
                bitmap.recycle()
            }
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return outFile
    }

    /** Saves image bytes to MediaStore Pictures/Pulse (API 29+) or app external dir on older versions. */
    fun saveImageToGallery(context: Context, bytes: ByteArray, displayName: String, mime: String): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Pulse")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            return uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Pulse").apply { mkdirs() }
            val file = File(dir, displayName)
            file.writeBytes(bytes)
            return Uri.fromFile(file)
        }
    }

    fun shareFile(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_via)))
    }

    fun writeTextFile(outFile: File, text: String): File {
        outFile.parentFile?.mkdirs()
        outFile.writeText(text)
        return outFile
    }

    fun ensureExportDir(context: Context, kind: String): File =
        File(File(context.cacheDir, "shared"), kind).apply { mkdirs() }
}
