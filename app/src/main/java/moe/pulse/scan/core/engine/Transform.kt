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

    /**
     * Median near-horizontal text-line angle in degrees (positive = page is
     * rotated clockwise). Returns 0 when fewer than 5 plausible text lines
     * are found, so callers can skip deskew safely.
     */
    fun estimateSkewAngle(bgr: Mat): Double {
        val gray = Mat()
        val edges = Mat()
        val lines = Mat()
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Canny(gray, edges, 50.0, 150.0)
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, 60, gray.cols() / 4.0, 25.0)
            val angles = ArrayList<Double>(lines.rows())
            for (i in 0 until lines.rows()) {
                val l = lines.get(i, 0)
                var ang = Math.toDegrees(Math.atan2(l[3] - l[1], l[2] - l[0]))
                while (ang > 45.0) ang -= 90.0
                while (ang <= -45.0) ang += 90.0
                if (Math.abs(ang) < 20.0) angles.add(ang)
            }
            if (angles.size < 5) return 0.0
            angles.sort()
            return angles[angles.size / 2]
        } finally {
            gray.release()
            edges.release()
            lines.release()
        }
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
