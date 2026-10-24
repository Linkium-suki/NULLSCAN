package moe.pulse.scan.core.engine

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Document quad detection ("Aether Lens") and four-point perspective warp.
 */
object Transform {

    /** Returns quad points ordered tl, tr, br, bl or null when no plausible document is found. */
    fun findDocumentQuad(rgba: Mat): Array<Point>? {
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            val frameArea = rgba.size().area()
            val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(5)
            for (contour in sorted) {
                val area = Imgproc.contourArea(contour)
                if (area < frameArea * 0.15) continue
                val c2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx2f = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx2f, 0.02 * peri, true)
                val approx = approx2f.toArray()
                c2f.release()
                approx2f.release()
                if (approx.size == 4 && Imgproc.isContourConvex(MatOfPoint(*approx))) {
                    contours.forEach { it.release() }
                    return orderQuad(approx)
                }
            }
            contours.forEach { it.release() }
            return null
        } finally {
            gray.release()
            blurred.release()
            edges.release()
        }
    }

    /** Warps [src] so that [quad] (any corner order) fills the output rectangle. */
    fun fourPointTransform(src: Mat, quad: Array<Point>): Mat {
        val (tl, tr, br, bl) = orderQuad(quad)
        val widthTop = dist(tl, tr)
        val widthBottom = dist(bl, br)
        val heightLeft = dist(tl, bl)
        val heightRight = dist(tr, br)
        val outW = maxOf(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val outH = maxOf(heightLeft, heightRight).toInt().coerceAtLeast(1)

        val srcTri = MatOfPoint2f(tl, tr, br, bl)
        val dstTri = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((outW - 1).toDouble(), 0.0),
            Point((outW - 1).toDouble(), (outH - 1).toDouble()),
            Point(0.0, (outH - 1).toDouble()),
        )
        val m = Imgproc.getPerspectiveTransform(srcTri, dstTri)
        val dst = Mat()
        Imgproc.warpPerspective(src, dst, m, Size(outW.toDouble(), outH.toDouble()))
        m.release()
        srcTri.release()
        dstTri.release()
        return dst
    }

    fun orderQuad(points: Array<Point>): Array<Point> {
        val bySum = points.sortedBy { it.x + it.y }
        val tl = bySum.first()
        val br = bySum.last()
        val byDiff = points.sortedBy { it.y - it.x }
        val tr = byDiff.first()
        val bl = byDiff.last()
        return arrayOf(tl, tr, br, bl)
    }

    private fun dist(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt(dx * dx + dy * dy)
    }
}
