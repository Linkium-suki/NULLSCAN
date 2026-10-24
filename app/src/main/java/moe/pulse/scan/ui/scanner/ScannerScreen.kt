package moe.pulse.scan.ui.scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.PointF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.pulse.scan.core.data.PageRef
import moe.pulse.scan.core.data.ScanSession
import moe.pulse.scan.core.engine.Transform
import moe.pulse.scan.core.sensor.StabilityMonitor
import moe.pulse.scan.ui.theme.BadgeStyle
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import androidx.compose.ui.res.stringResource
import moe.pulse.scan.R
import moe.pulse.scan.ui.common.rememberToast

/**
 * Aether Lens: live CameraX viewfinder with OpenCV document-quad overlay,
 * gyroscope stability gate (velocity < 0.3 rad/s) and Photo Picker import.
 */
@Composable
fun ScannerScreen(
    onCaptured: () -> Unit,
    onImported: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) permissionDeniedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val toast = rememberToast()
    val gmsFallbackMsg = stringResource(R.string.gms_fallback_toast)
    var gmsToastShown by remember { mutableStateOf(false) }
    val stability = remember { StabilityMonitor(context) }
    DisposableEffect(Unit) {
        stability.start()
        onDispose { stability.stop() }
    }
    val isStable by stability.isStable.collectAsStateWithLifecycle()

    var quadPoints by remember { mutableStateOf<List<PointF>?>(null) }
    var sessionPages by remember { mutableIntStateOf(ScanSession.pages.size) }
    var importing by remember { mutableStateOf(false) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        importing = false
        if (uris.isNotEmpty()) {
            scope.launch {
                uris.forEach { uri ->
                    val out = File(context.cacheDir, "pages/p_${System.nanoTime()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    ScanSession.pages += moe.pulse.scan.core.data.PageRef(out)
                }
                onImported()
            }
        }
    }

    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }

    // Default scan path: Google's Play-services document scanner (auto crop,
    // multi-page). It is a Dynamite module inside GMS Core — never bundled in
    // the APK — so availability depends on the device and the module is
    // fetched by GMS Core itself; this app still needs no INTERNET permission.
    // If it is unavailable or cancelled, the manual viewfinder below remains.
    val gmsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                    ?.pages?.map { it.imageUri }
                if (!uris.isNullOrEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        File(context.cacheDir, "pages").mkdirs()
                        uris.forEach { uri ->
                            val f = File(context.cacheDir, "pages/gms_${System.nanoTime()}.jpg")
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                f.outputStream().use { input.copyTo(it) }
                            }
                            if (f.length() > 0) ScanSession.pages += PageRef(f)
                        }
                        withContext(Dispatchers.Main) { onImported() }
                    }
                }
            }
            // Any other code (module failure / cancel) — stay on the manual viewfinder.
        }
    }

    fun launchGmsScan() {
        val activity = context as? Activity ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                gmsLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                // GMS unavailable / module not ready — manual viewfinder is the fallback.
                if (!gmsToastShown) {
                    gmsToastShown = true
                    toast(gmsFallbackMsg)
                }
            }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        // Capture/import outputs live under cache/pages; create it up front so
        // ImageCapture's file writer never fails on a missing parent.
        File(context.cacheDir, "pages").mkdirs()
        launchGmsScan()
        val future = ProcessCameraProvider.getInstance(context)
        val provider = kotlinx.coroutines.suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            future.addListener(
                { cont.resume(future.get()) {} },
                ContextCompat.getMainExecutor(context),
            )
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        @Suppress("DEPRECATION")
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { proxy ->
            try {
                val relative = analyzeFrame(proxy)
                quadPoints = relative
            } catch (_: Exception) {
                // analyzer must never crash the camera thread
            } finally {
                proxy.close()
            }
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val primaryColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                quadPoints?.let { pts ->
                    val path = Path().apply {
                        moveTo(pts[0].x * size.width, pts[0].y * size.height)
                        for (i in 1 until 4) lineTo(pts[i].x * size.width, pts[i].y * size.height)
                        close()
                    }
                    drawPath(path, color = primaryColor, style = Stroke(width = 6f))
                    pts.forEach {
                        drawCircle(
                            color = primaryColor,
                            radius = 10f,
                            center = Offset(it.x * size.width, it.y * size.height),
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                Text(
                    text = if (permissionDeniedOnce) stringResource(R.string.camera_denied) else stringResource(R.string.camera_requesting),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                if (permissionDeniedOnce) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.grant_camera))
                    }
                }
            }
        }

        // Stability status chip
        FilledIconButton(
            onClick = { launchGmsScan() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 48.dp)
                .size(48.dp),
        ) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = stringResource(R.string.system_scanner_cd))
        }

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            shape = CircleShape,
            color = if (isStable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                text = if (isStable) stringResource(R.string.status_ready) else stringResource(R.string.status_unstable),
                style = BadgeStyle.copy(color = if (isStable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (importing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Bottom controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = {
                importing = true
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = stringResource(R.string.import_cd))
            }

            FilledIconButton(
                onClick = {
                    if (!isStable) return@FilledIconButton
                    val outFile = File(context.cacheDir, "pages/capture_${System.nanoTime()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(outFile).build()
                    imageCapture.takePicture(
                        options,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                ScanSession.pendingCrop = outFile
                                onCaptured()
                            }

                            override fun onError(exception: ImageCaptureException) {
                                android.util.Log.e("PulseScanner", "Capture failed", exception)
                            }
                        },
                    )
                },
                enabled = isStable,
                modifier = Modifier.size(88.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isStable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = stringResource(R.string.capture_cd), modifier = Modifier.size(36.dp))
            }

            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(
                    text = "$sessionPages",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** Runs quad detection on one frame; returns normalized points (0..1) or null. */
private fun analyzeFrame(proxy: ImageProxy): List<PointF>? {
    val rgba = proxy.toRgbaMat() ?: return null
    return try {
        val quad = Transform.findDocumentQuad(rgba) ?: return null
        val w = rgba.cols().toDouble()
        val h = rgba.rows().toDouble()
        quad.map { PointF((it.x / w).toFloat(), (it.y / h).toFloat()) }
    } finally {
        rgba.release()
    }
}

private fun ImageProxy.toRgbaMat(): Mat? {
    val nv21 = toNv21() ?: return null
    val yuv = Mat(height + height / 2, width, CvType.CV_8UC1)
    val rgba = Mat()
    try {
        yuv.put(0, 0, nv21)
        Imgproc.cvtColor(yuv, rgba, Imgproc.COLOR_YUV2RGBA_NV21)
    } finally {
        yuv.release()
    }
    val rotation = imageInfo.rotationDegrees
    val rotated = when (rotation) {
        90 -> Mat().also { Core.rotate(rgba, it, Core.ROTATE_90_CLOCKWISE); rgba.release() }
        180 -> Mat().also { Core.rotate(rgba, it, Core.ROTATE_180); rgba.release() }
        270 -> Mat().also { Core.rotate(rgba, it, Core.ROTATE_90_COUNTERCLOCKWISE); rgba.release() }
        else -> rgba
    }
    return rotated
}

private fun ImageProxy.toNv21(): ByteArray? {
    return try {
        val w = width
        val h = height
        val ySize = w * h
        val nv21 = ByteArray(ySize + ySize / 2)
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        if (yPlane.rowStride == w) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            for (row in 0 until h) {
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.get(nv21, row * w, w)
            }
        }
        var pos = ySize
        val uPlane = planes[1]
        val vPlane = planes[2]
        if (uPlane.pixelStride == 2 && vPlane.pixelStride == 2 && uPlane.rowStride == w) {
            val vBuffer = vPlane.buffer
            vBuffer.position(0)
            vBuffer.get(nv21, pos, minOf(vBuffer.remaining(), ySize / 2))
        } else {
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            for (row in 0 until h / 2) {
                for (col in 0 until w / 2) {
                    nv21[pos++] = vBuffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                    nv21[pos++] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                }
            }
        }
        nv21
    } catch (_: Exception) {
        null
    }
}
