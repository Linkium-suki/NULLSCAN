package moe.pulse.scan.ui.cutout

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.pulse.scan.PulseApp
import moe.pulse.scan.core.data.ScanSession
import moe.pulse.scan.core.export.DocumentExporter
import moe.pulse.scan.core.segment.CutoutEngine
import org.opencv.core.Core
import org.opencv.core.Mat

class CutoutViewModel(application: Application) : AndroidViewModel(application) {

    /** 0 = AI portrait, 1 = GrabCut frame. */
    var mode by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var resultBitmap by mutableStateOf<Bitmap?>(null)
    var feather by mutableFloatStateOf(1.5f)
    var bg by mutableIntStateOf(0)
    /** Normalized subject box for GrabCut. */
    var rect by mutableStateOf<RectF?>(null)

    val hasSource: Boolean get() = sourceBytes != null

    private val app get() = getApplication<PulseApp>()
    private val engine by lazy { CutoutEngine(app) }
    private var sourceBytes: ByteArray? = null
    private var sourceBgr: Mat? = null
    private var lastMask: Mat? = null

    init {
        val f = ScanSession.pendingCutout
        if (f != null && f.exists()) {
            sourceBytes = f.readBytes()
            ScanSession.pendingCutout = null
        }
    }

    fun sourceBitmap(): Bitmap? = sourceBytes?.let {
        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
    }

    /** Re-composites the cached mask with current feather/bg (after a run). */
    fun recomposite() {
        val mask = lastMask ?: return
        if (running) return
        viewModelScope.launch(Dispatchers.Default) {
            val bgr = sourceBgr ?: return@launch
            val feathered = engine.feather(mask, featherPx())
            val comp = engine.composite(bgr, feathered, bg)
            feathered.release()
            withContext(Dispatchers.Main) { resultBitmap = comp }
        }
    }

    private fun featherPx(): Int {
        val bgr = sourceBgr ?: return 0
        return ((feather / 10f) * (minOf(bgr.cols(), bgr.rows()) / 20.0)).toInt().coerceIn(0, 60)
    }

    fun run() {
        val bytes = sourceBytes ?: return
        if (running) return
        running = true
        viewModelScope.launch {
            var ok = false
            try {
                withContext(Dispatchers.Default) {
                    if (sourceBgr == null) sourceBgr = engine.decodeBgr(bytes)
                    val bgr = sourceBgr ?: return@withContext
                    val mask = when (mode) {
                        0 -> engine.portraitMask(bytes)
                        else -> engine.grabCutMask(bytes, rect ?: RectF(0.08f, 0.08f, 0.92f, 0.92f))
                    }
                    if (mask != null && Core.countNonZero(mask) > 200) {
                        lastMask?.release()
                        lastMask = mask
                        val feathered = engine.feather(mask, featherPx())
                        val comp = engine.composite(bgr, feathered, bg)
                        feathered.release()
                        withContext(Dispatchers.Main) { resultBitmap = comp }
                        ok = true
                    } else {
                        mask?.release()
                    }
                }
            } catch (_: Exception) {
                ok = false
            } finally {
                running = false
                runResult(ok)
            }
        }
    }

    private var runResult: (Boolean) -> Unit = {}
    fun onResult(callback: (Boolean) -> Unit) { runResult = callback }

    fun save(onDone: (Boolean) -> Unit) {
        val bmp = resultBitmap ?: return onDone(false)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    val name = DocumentExporter.generateFileName(emptySet(), "png")
                    DocumentExporter.saveImageToGallery(app, out.toByteArray(), name, "image/png") != null
                } catch (_: Exception) {
                    false
                }
            }
            onDone(ok)
        }
    }

    override fun onCleared() {
        engine.close()
        sourceBgr?.release()
        lastMask?.release()
        super.onCleared()
    }
}
