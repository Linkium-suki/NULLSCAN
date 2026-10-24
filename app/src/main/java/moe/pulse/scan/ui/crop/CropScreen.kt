package moe.pulse.scan.ui.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.pulse.scan.core.data.PageRef
import moe.pulse.scan.core.data.ScanSession
import moe.pulse.scan.core.engine.Transform
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point

/**
 * Manual corner fine-tuning ("drag the four handles"), initialized from the
 * auto-detected quad, then warped via perspective transform.
 * Corner coordinates are stored normalized (0..1) to stay resolution-free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sourceFile = remember { ScanSession.pendingCrop }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember {
        mutableStateOf(
            listOf(
                Offset(0.15f, 0.15f), Offset(0.85f, 0.15f),
                Offset(0.85f, 0.85f), Offset(0.15f, 0.85f),
            )
        )
    }
    var activeHandle by remember { mutableIntStateOf(-1) }
    var processing by remember { mutableStateOf(false) }

    LaunchedEffect(sourceFile) {
        if (sourceFile == null) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) { decodeDownsampled(sourceFile, 2048) }
        bitmap = bmp
        bmp ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val mat = Mat()
            try {
                Utils.bitmapToMat(bmp, mat)
                val quad = Transform.findDocumentQuad(mat)
                if (quad != null) {
                    val w = mat.cols().toFloat()
                    val h = mat.rows().toFloat()
                    corners = quad.map { Offset((it.x / w).toFloat(), (it.y / h).toFloat()) }
                }
            } finally {
                mat.release()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adjust corners") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        bitmap ?: return@IconButton
                        scope.launch(Dispatchers.Default) {
                            val mat = Mat()
                            try {
                                Utils.bitmapToMat(bitmap, mat)
                                val quad = Transform.findDocumentQuad(mat)
                                if (quad != null) {
                                    val w = mat.cols().toFloat()
                                    val h = mat.rows().toFloat()
                                    corners = quad.map { Offset((it.x / w).toFloat(), (it.y / h).toFloat()) }
                                }
                            } finally {
                                mat.release()
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Auto-detect")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(
                    onClick = {
                        val bmp = bitmap ?: return@Button
                        processing = true
                        scope.launch(Dispatchers.IO) {
                            val src = Mat()
                            try {
                                Utils.bitmapToMat(bmp, src)
                                val quad = corners.map {
                                    Point(it.x * src.cols().toDouble(), it.y * src.rows().toDouble())
                                }.toTypedArray()
                                val dst = Transform.fourPointTransform(src, quad)
                                val out = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
                                Utils.matToBitmap(dst, out)
                                dst.release()
                                File(context.cacheDir, "pages").mkdirs()
                                val outFile = File(context.cacheDir, "pages/page_${System.nanoTime()}.jpg")
                                outFile.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                out.recycle()
                                ScanSession.pages += PageRef(outFile)
                            } finally {
                                src.release()
                            }
                            withContext(Dispatchers.Main) {
                                processing = false
                                onConfirm()
                            }
                        }
                    },
                    enabled = !processing && bitmap != null,
                ) {
                    if (processing) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp).fillMaxWidth(0.1f), strokeWidth = 2.dp)
                    }
                    Text("Add to document")
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(padding),
        ) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                return@BoxWithConstraints
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize()
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Crop source",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )

                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bmp) {
                            // Fit rect in pixel space, shared by hit-testing and drawing.
                            fun fit(): List<Float> {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val s = minOf(w / bmp.width, h / bmp.height)
                                val dw = bmp.width * s
                                val dh = bmp.height * s
                                return listOf((w - dw) / 2f, (h - dh) / 2f, dw, dh)
                            }
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val (l, t, dw, dh) = fit()
                                    activeHandle = nearestHandle(pos, l, t, dw, dh, corners, threshold = 96f)
                                },
                                onDragEnd = { activeHandle = -1 },
                                onDragCancel = { activeHandle = -1 },
                            ) { change, _ ->
                                change.consume()
                                if (activeHandle >= 0 && activeHandle < corners.size) {
                                    val (l, t, dw, dh) = fit()
                                    val pos = change.position
                                    val nx = ((pos.x - l) / dw).coerceIn(0f, 1f)
                                    val ny = ((pos.y - t) / dh).coerceIn(0f, 1f)
                                    corners = corners.toMutableList().also {
                                        it[activeHandle] = Offset(nx, ny)
                                    }
                                }
                            }
                        },
                ) {
                    val (left, top, drawW, drawH) = run {
                        val w = size.width
                        val h = size.height
                        val s = minOf(w / bmp.width, h / bmp.height)
                        val dw = bmp.width * s
                        val dh = bmp.height * s
                        listOf((w - dw) / 2f, (h - dh) / 2f, dw, dh)
                    }
                    val pts = corners.map { Offset(left + it.x * drawW, top + it.y * drawH) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    drawPath(path, color = primaryColor, style = Stroke(width = 4f))
                    pts.forEachIndexed { index, p ->
                        drawCircle(
                            color = Color.White,
                            radius = if (index == activeHandle) 26f else 20f,
                            center = p,
                        )
                        drawCircle(
                            color = primaryColor,
                            radius = if (index == activeHandle) 20f else 15f,
                            center = p,
                        )
                    }
                }
            }
        }
    }
}

private fun nearestHandle(
    pos: Offset,
    left: Float,
    top: Float,
    drawW: Float,
    drawH: Float,
    corners: List<Offset>,
    threshold: Float,
): Int {
    var best = -1
    var bestDist = Float.MAX_VALUE
    corners.forEachIndexed { index, c ->
        val p = Offset(left + c.x * drawW, top + c.y * drawH)
        val d = (p - pos).getDistance()
        if (d < bestDist) {
            bestDist = d
            best = index
        }
    }
    return if (bestDist <= threshold) best else -1
}

private fun decodeDownsampled(file: File, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
