package moe.pulse.scan.core.segment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Subject cutout, two engines:
 *  - Portrait: ML Kit Selfie Segmentation with the model bundled in the APK
 *    (a genuinely embedded small model; no network I/O).
 *  - Subject: interactive GrabCut over a user-drawn rectangle (pure OpenCV).
 * Both produce a soft 8-bit alpha mask, featherable, composable onto a
 * transparent / white / black background.
 */
class CutoutEngine(context: Context) {

    private val appContext = context.applicationContext

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    /** AI portrait mask (resized to the source image, soft 0..255). */
    suspend fun portraitMask(jpegBytes: ByteArray): Mat? {
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val image = InputImage.fromBitmap(bmp, 0)
        val mask: SegmentationMask = suspendCancellableCoroutine { cont ->
            segmenter.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        try {
            val w = mask.width
            val h = mask.height
            val small = Mat(h, w, CvType.CV_8UC1)
            val data = ByteArray(w * h)
            val raw = mask.buffer
            val floats = raw.asFloatBuffer()
            if (floats.capacity() == w * h) {
                var i = 0
                while (floats.hasRemaining() && i < data.size) {
                    data[i++] = (floats.get().coerceIn(0f, 1f) * 255f).toInt().toByte()
                }
            } else {
                var i = 0
                while (raw.hasRemaining() && i < data.size) {
                    data[i++] = ((raw.get().toInt() and 0xFF) * 255 / 255).toByte()
                }
            }
            small.put(0, 0, data)
            val resized = Mat()
            Imgproc.resize(
                small, resized,
                Size(bmp.width.toDouble(), bmp.height.toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR
            )
            small.release()
            return resized
        } finally {
            bmp.recycle()
        }
    }

    /** GrabCut mask from a normalized rect (0..1), soft-free (binary) then blurred. */
    fun grabCutMask(jpegBytes: ByteArray, rectF: RectF, maxDim: Int = 1600): Mat? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxDim) sample *= 2
        val scaled = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, scaled) ?: return null

        val src = Mat()
        val mask = Mat()
        val bgd = Mat()
        val fgd = Mat()
        try {
            Utils.bitmapToMat(bmp, src) // RGBA
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)
            val rx = (rectF.left * src.cols()).toInt().coerceIn(0, src.cols() - 2)
            val ry = (rectF.top * src.rows()).toInt().coerceIn(0, src.rows() - 2)
            val rw = (rectF.width() * src.cols()).toInt().coerceAtMost(src.cols() - rx - 1).coerceAtLeast(2)
            val rh = (rectF.height() * src.rows()).toInt().coerceAtMost(src.rows() - ry - 1).coerceAtLeast(2)
            val rect = Rect(rx, ry, rw, rh)
            mask.setTo(Scalar(Imgproc.GC_BGD.toDouble()))
            Imgproc.grabCut(src, mask, rect, bgd, fgd, 3, Imgproc.GC_INIT_WITH_RECT)
            val out = Mat(mask.size(), CvType.CV_8UC1)
            Core.compare(mask, Scalar(Imgproc.GC_PR_FGD.toDouble()), out, Core.CMP_EQ)
            val fg = Mat(mask.size(), CvType.CV_8UC1)
            Core.compare(mask, Scalar(Imgproc.GC_FGD.toDouble()), fg, Core.CMP_EQ)
            Core.max(out, fg, out)
            fg.release()
            val resized = Mat()
            Imgproc.resize(
                out, resized,
                Size(opts.outWidth.toDouble(), opts.outHeight.toDouble()),
                0.0, 0.0, Imgproc.INTER_LINEAR
            )
            out.release()
            return resized
        } finally {
            src.release()
            mask.release()
            bgd.release()
            fgd.release()
            bmp.recycle()
        }
    }

    fun feather(mask: Mat, radiusPx: Int): Mat {
        if (radiusPx <= 0) return mask.clone()
        val out = Mat()
        Imgproc.GaussianBlur(mask, out, Size(0.0, 0.0), radiusPx.toDouble())
        return out
    }

    /** bg: 0 transparent, 1 white, 2 black. Returns an ARGB_8888 bitmap. */
    fun composite(bgr: Mat, alpha: Mat, bg: Int): Bitmap {
        val rgba = Mat()
        val chans = ArrayList<Mat>(4)
        val merged = Mat()
        try {
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
            Core.split(rgba, chans)
            if (bg == 0) {
                chans[3] = alpha.clone()
                Core.merge(chans, merged)
            } else {
                val a = Mat()
                alpha.convertTo(a, CvType.CV_32FC1, 1.0 / 255.0)
                val inv = Mat()
                val one = Mat(rgba.size(), CvType.CV_32FC1, Scalar.all(1.0))
                Core.subtract(one, a, inv)
                one.release()
                val bgVal = if (bg == 1) 255.0 else 0.0
                for (c in 0..2) {
                    val f = Mat()
                    chans[c].convertTo(f, CvType.CV_32FC1)
                    Core.multiply(f, a, f)
                    val fill = Mat(rgba.size(), CvType.CV_32FC1, Scalar(bgVal))
                    Core.multiply(fill, inv, fill)
                    Core.add(f, fill, f)
                    f.convertTo(chans[c], CvType.CV_8UC1)
                    f.release()
                    fill.release()
                }
                a.release()
                inv.release()
                Core.merge(chans, merged)
            }
            val bmp = Bitmap.createBitmap(merged.cols(), merged.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(merged, bmp)
            return bmp
        } finally {
            rgba.release()
            merged.release()
            chans.forEach { it.release() }
        }
    }

    fun decodeBgr(bytes: ByteArray): Mat? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val m = Mat()
        Utils.bitmapToMat(bmp, m)
        Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2BGR)
        bmp.recycle()
        return m
    }

    fun close() {
        segmenter.close()
    }
}
