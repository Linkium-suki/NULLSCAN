package moe.pulse.scan.core.engine

import android.graphics.RectF
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Pulse restoration pipeline — a faithful Kotlin/OpenCV port of the legacy
 * Dart `LazarusEngine`. Order of operations is preserved exactly:
 *
 * 1. decode
 * 2. HSV ink separation (red wraps around the hue circle in two segments)
 * 3. per-channel illumination normalization ("Skyfsm"): morphological CLOSE
 *    background with an adaptive odd kernel >= 15, then divide by 255
 * 4. gray-threshold stroke erase (style 1: threshold 200, style 2: 160 + 3x3
 *    MORPH_OPEN) with OCR-zone forced recall (THRESH_BINARY_INV at 200)
 * 5. USM sharpen (1.5 / -0.5, Gaussian sigma 3)
 * 6. JPEG encode
 *
 * style: 0 = original (ink removal + light correction only),
 *        1 = de-pencil (light stains), 2 = de-ink (strong, fine strokes).
 */
object LazarusEngine {

    data class Params(
        val protectedZones: List<RectF> = emptyList(),
        val removeRed: Boolean = true,
        val removeBlue: Boolean = true,
        /** 0 original, 1 de-pencil, 2 de-ink, 3 adaptive black & white. */
        val style: Int = 1,
        val deskew: Boolean = false,
        val enhanceLowLight: Boolean = false,
    )

    fun process(imageBytes: ByteArray, params: Params): ByteArray {
        var src = decode(imageBytes) ?: return imageBytes
        val hsv = Mat()
        val masks = mutableListOf<Mat>()
        try {
            // 1.5 deskew (optional): straighten the page by its median text-line angle
            if (params.deskew) {
                val angle = Transform.estimateSkewAngle(src)
                if (Math.abs(angle) >= 0.3 && Math.abs(angle) <= 15.0) {
                    val rot = Imgproc.getRotationMatrix2D(
                        Point(src.cols() / 2.0, src.rows() / 2.0), angle, 1.0
                    )
                    val rotated = Mat()
                    Imgproc.warpAffine(
                        src, rotated, rot, src.size(),
                        Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
                    )
                    rot.release()
                    val old = src
                    src = rotated
                    old.release()
                }
            }

            // 2. ink separation
            Imgproc.cvtColor(src, hsv, Imgproc.COLOR_BGR2HSV)
            if (params.removeRed) {
                val mask1 = Mat()
                val mask2 = Mat()
                val redMask = Mat()
                Core.inRange(hsv, Scalar(0.0, 40.0, 40.0), Scalar(10.0, 255.0, 255.0), mask1)
                Core.inRange(hsv, Scalar(170.0, 40.0, 40.0), Scalar(180.0, 255.0, 255.0), mask2)
                Core.add(mask1, mask2, redMask)
                src.setTo(Scalar(255.0, 255.0, 255.0), redMask)
                masks += listOf(mask1, mask2, redMask)
            }
            if (params.removeBlue) {
                val blueMask = Mat()
                Core.inRange(hsv, Scalar(100.0, 40.0, 40.0), Scalar(130.0, 255.0, 255.0), blueMask)
                src.setTo(Scalar(255.0, 255.0, 255.0), blueMask)
                masks += blueMask
            }

            // 3. illumination normalization
            val channels = mutableListOf<Mat>()
            Core.split(src, channels)
            var kSizeVal = Math.round(src.cols() / 30.0).toInt()
            if (kSizeVal % 2 == 0) kSizeVal++
            if (kSizeVal < 15) kSizeVal = 15
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kSizeVal.toDouble(), kSizeVal.toDouble()))
            val bg = Mat()
            for (channel in channels) {
                Imgproc.morphologyEx(channel, bg, Imgproc.MORPH_CLOSE, kernel)
                Core.divide(channel, bg, channel, 255.0)
            }
            kernel.release()
            bg.release()
            Core.merge(channels, src)
            channels.forEach { it.release() }

            // 3.5 low-light boost (optional): CLAHE on the L channel of Lab
            if (params.enhanceLowLight) {
                val lab = Mat()
                try {
                    Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
                    val labChans = mutableListOf<Mat>()
                    Core.split(lab, labChans)
                    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                    clahe.apply(labChans[0], labChans[0])
                    Core.merge(labChans, lab)
                    labChans.forEach { it.release() }
                    lab.copyTo(src)
                } finally {
                    lab.release()
                }
            }

            if (params.style == 0) {
                return encode(src) ?: imageBytes
            }

            if (params.style == 3) {
                val bw = adaptiveBinary(src, params)
                return try { encode(bw) ?: imageBytes } finally { bw.release() }
            }

            // 4. stroke erase system
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)

            val threshVal = if (params.style == 2) 160.0 else 200.0
            val threshBinary = Mat()
            Imgproc.threshold(gray, threshBinary, threshVal, 255.0, Imgproc.THRESH_BINARY)

            val whiteMat = Mat(gray.rows(), gray.cols(), CvType.CV_8UC1, Scalar(255.0))
            val contentMask = Mat()
            Core.subtract(whiteMat, threshBinary, contentMask)
            threshBinary.release()

            if (params.style == 2) {
                val morphKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.morphologyEx(contentMask, contentMask, Imgproc.MORPH_OPEN, morphKernel)
                morphKernel.release()
            }

            // 4.5 OCR forced recall
            for (zone in params.protectedZones) {
                var x = zone.left.toInt().coerceIn(0, contentMask.cols() - 1)
                var y = zone.top.toInt().coerceIn(0, contentMask.rows() - 1)
                var w = zone.width().toInt()
                var h = zone.height().toInt()
                if (x + w > contentMask.cols()) w = contentMask.cols() - x
                if (y + h > contentMask.rows()) h = contentMask.rows() - y
                if (w > 0 && h > 0) {
                    val rect = org.opencv.core.Rect(x, y, w, h)
                    val grayRoi = gray.submat(rect)
                    val textRoi = Mat()
                    Imgproc.threshold(grayRoi, textRoi, 200.0, 255.0, Imgproc.THRESH_BINARY_INV)
                    val targetRoi = contentMask.submat(rect)
                    val temp = Mat()
                    Core.add(targetRoi, textRoi, temp)
                    temp.copyTo(targetRoi)
                    temp.release()
                    textRoi.release()
                    targetRoi.release()
                    grayRoi.release()
                }
            }

            // 4.6 apply erase mask
            val eraseMask = Mat()
            Core.subtract(whiteMat, contentMask, eraseMask)
            src.setTo(Scalar(255.0, 255.0, 255.0), eraseMask)
            eraseMask.release()
            contentMask.release()
            whiteMat.release()
            gray.release()

            // 5. USM sharpen
            val blurred = Mat()
            Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
            Core.addWeighted(src, 1.5, blurred, -0.5, 0.0, src)
            blurred.release()

            return encode(src) ?: imageBytes
        } finally {
            hsv.release()
            masks.forEach { it.release() }
            src.release()
        }
    }

    /** Style 3: adaptive binary — clean white paper, ink-black text; OCR zones recalled. */
    private fun adaptiveBinary(src: Mat, params: Params): Mat {
        val gray = Mat()
        val bw = Mat()
        val out = Mat()
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
            var block = Math.round(Math.min(src.cols(), src.rows()) / 20.0).toInt()
            if (block % 2 == 0) block++
            if (block < 15) block = 15
            Imgproc.adaptiveThreshold(
                gray, bw, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 15.0
            )
            // OCR zone recall: force dark text pixels inside protected zones back to ink
            for (zone in params.protectedZones) {
                var x = zone.left.toInt().coerceIn(0, bw.cols() - 1)
                var y = zone.top.toInt().coerceIn(0, bw.rows() - 1)
                var w = zone.width().toInt()
                var h = zone.height().toInt()
                if (x + w > bw.cols()) w = bw.cols() - x
                if (y + h > bw.rows()) h = bw.rows() - y
                if (w > 0 && h > 0) {
                    val rect = org.opencv.core.Rect(x, y, w, h)
                    val grayRoi = gray.submat(rect)
                    val textRoi = Mat()
                    Imgproc.threshold(grayRoi, textRoi, 200.0, 255.0, Imgproc.THRESH_BINARY_INV)
                    val bwRoi = bw.submat(rect)
                    bwRoi.setTo(Scalar(0.0), textRoi)
                    bwRoi.release()
                    textRoi.release()
                    grayRoi.release()
                }
            }
            Imgproc.cvtColor(bw, out, Imgproc.COLOR_GRAY2BGR)
            return out
        } finally {
            gray.release()
            bw.release()
        }
    }

    private fun decode(bytes: ByteArray): Mat? {
        val data = Mat(1, bytes.size, CvType.CV_8UC1)
        try {
            data.put(0, 0, bytes)
            val decoded = Imgcodecs.imdecode(data, Imgcodecs.IMREAD_COLOR)
            return if (decoded.empty()) { decoded.release(); null } else decoded
        } finally {
            data.release()
        }
    }

    private fun encode(mat: Mat): ByteArray? {
        val buf = MatOfByte()
        try {
            val ok = Imgcodecs.imencode(".jpg", mat, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92))
            return if (ok) buf.toArray() else null
        } finally {
            buf.release()
        }
    }
}
