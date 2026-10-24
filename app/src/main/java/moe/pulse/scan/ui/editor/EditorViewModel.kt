package moe.pulse.scan.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.pulse.scan.PulseApp
import moe.pulse.scan.core.crypto.DocLocker
import moe.pulse.scan.core.data.DocumentEntity
import moe.pulse.scan.core.data.PageEntity
import moe.pulse.scan.core.data.PageRef
import moe.pulse.scan.core.data.ScanSession
import moe.pulse.scan.core.engine.LazarusEngine
import moe.pulse.scan.core.export.DocumentExporter
import moe.pulse.scan.core.ocr.OcrProvider
import moe.pulse.scan.core.ocr.OcrResult

class EditorViewModel(application: Application, private val docKey: String) :
    AndroidViewModel(application) {

    data class EditorPage(
        val file: File,
        val rotation: Int = 0, // display rotation in degrees, applied at view/export time
    )

    val pages = mutableStateListOf<EditorPage>()
    var currentIndex by mutableIntStateOf(0)
        private set

    // Three-layer cache, mirroring the legacy editor. Snapshot-backed so
    // composables observing the bytes recompose when the pipeline finishes.
    private val originalCache = mutableStateMapOf<Int, ByteArray>()
    private val processedCache = mutableStateMapOf<Int, ByteArray>()
    private val ocrCache = mutableStateMapOf<Int, OcrResult>()
    private val pipelineMutex = Mutex()

    fun processedBytesFor(index: Int): ByteArray? = processedCache[index]
    fun originalBytesFor(index: Int): ByteArray? = originalCache[index]

    var engineOn by mutableStateOf(true)
    var ocrProtect by mutableStateOf(true)
    var removeRed by mutableStateOf(true)
    var removeBlue by mutableStateOf(true)
    var style by mutableIntStateOf(1) // 0 original / 1 de-pencil / 2 de-ink
    val selectedTags = mutableStateListOf<String>()

    var pageLoading by mutableStateOf(true)
    var exporting by mutableStateOf(false)
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var savedDocumentId by mutableStateOf<Long?>(null)

    private val app get() = getApplication<PulseApp>()
    private val ocrProvider by lazy { OcrProvider(app) }

    init {
        File(app.cacheDir, "pages").mkdirs()
        viewModelScope.launch { loadPages() }
    }

    private suspend fun loadPages() {
        withContext(Dispatchers.IO) {
            if (docKey == "new") {
                val refs = ScanSession.pages.toList()
                ScanSession.pages.clear()
                pages += refs.map { EditorPage(it.file) }
            } else {
                val id = docKey.removePrefix("doc/").toLongOrNull() ?: return@withContext
                val dao = app.database.archiveDao()
                val doc = dao.document(id) ?: return@withContext
                selectedTags += doc.tags.split(',').filter { it.isNotBlank() }
                val dbPages = dao.pagesOf(id)
                dbPages.forEach { entity ->
                    try {
                        val plain = DocLocker.decrypt(File(entity.path).readBytes())
                        val cacheFile = File(app.cacheDir, "pages/arch_${entity.id}_${System.nanoTime()}.jpg")
                        cacheFile.writeBytes(plain)
                        pages += EditorPage(cacheFile)
                    } catch (_: Exception) {
                        // unreadable page (key rotated / restore) — skip
                    }
                }
            }
        }
        loadCurrentPage()
    }

    fun loadCurrentPage() {
        val index = currentIndex
        if (index !in pages.indices) {
            pageLoading = false
            return
        }
        pageLoading = true
        statusMessage = "LOADING…"
        viewModelScope.launch {
            try {
                ensureOriginal(index)
                if (!ocrCache.containsKey(index)) {
                    runOcrForIndex(index)
                }
                if (processedCache.containsKey(index) || !engineOn) {
                    pageLoading = false
                    statusMessage = null
                } else {
                    runPipeline()
                }
            } catch (e: Exception) {
                pageLoading = false
                statusMessage = null
            }
        }
    }

    private fun runOcrForIndex(index: Int) {
        viewModelScope.launch {
            try {
                ensureOriginal(index)
                val result = withContext(Dispatchers.Default) {
                    ocrProvider.recognize(originalCache.getValue(index))
                }
                ocrCache[index] = result
                if (index == currentIndex && engineOn && ocrProtect) {
                    runPipeline()
                }
            } catch (_: Exception) {
                // OCR failure must not block the pipeline; protected zones stay empty.
            }
        }
    }

    private suspend fun runPipeline() {
        if (!engineOn) return
        pipelineMutex.withLock {
            val index = currentIndex
            if (index !in pages.indices) return
            if (!exporting) {
                pageLoading = true
                statusMessage = "PROCESSING…"
            }
            try {
                ensureOriginal(index)
                val zones = mutableListOf<RectF>()
                if (ocrProtect) {
                    ocrCache[index]?.let { result ->
                        zones += result.blocks.map { it.box }
                    }
                }
                val bytes = withContext(Dispatchers.Default) {
                    LazarusEngine.process(
                        originalCache.getValue(index),
                        LazarusEngine.Params(
                            protectedZones = zones,
                            removeRed = removeRed,
                            removeBlue = removeBlue,
                            style = style,
                        ),
                    )
                }
                processedCache[index] = bytes
            } catch (_: Exception) {
                // fall back to original display
            } finally {
                if (!exporting) {
                    pageLoading = false
                    statusMessage = null
                }
            }
        }
    }

    private suspend fun ensureOriginal(index: Int) {
        if (originalCache.containsKey(index)) return
        val page = pages.getOrNull(index) ?: return
        originalCache[index] = withContext(Dispatchers.IO) { page.file.readBytes() }
    }

    fun changePage(newIndex: Int) {
        if (newIndex !in pages.indices) return
        currentIndex = newIndex
        loadCurrentPage()
    }

    fun onParamChanged() {
        processedCache.clear()
        viewModelScope.launch { runPipeline() }
    }

    fun rotateCurrent() {
        val index = currentIndex
        if (index !in pages.indices) return
        pages[index] = pages[index].copy(rotation = (pages[index].rotation + 90) % 360)
    }

    fun deleteCurrent() {
        val index = currentIndex
        if (index !in pages.indices || pages.size <= 1) return
        val page = pages.removeAt(index)
        page.file.delete()
        clearCaches()
        currentIndex = index.coerceAtMost(pages.size - 1)
        loadCurrentPage()
    }

    fun moveCurrent(delta: Int) {
        val index = currentIndex
        val target = index + delta
        if (index !in pages.indices || target !in pages.indices) return
        val page = pages.removeAt(index)
        pages.add(target, page)
        clearCaches()
        currentIndex = target
        loadCurrentPage()
    }

    fun addPages(uris: List<Uri>, onAdded: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    try {
                        val out = File(app.cacheDir, "pages/page_${System.nanoTime()}.jpg")
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        pages += EditorPage(out)
                    } catch (_: Exception) {
                    }
                }
            }
            changePage(pages.size - 1)
            onAdded()
        }
    }

    private fun clearCaches() {
        originalCache.clear()
        processedCache.clear()
        ocrCache.clear()
    }

    /** Bytes of a page honoring engine state; processes silently when missing. */
    private suspend fun bytesForExport(index: Int): ByteArray? {
        if (!engineOn) return originalCache.getOrPut(index) { pages[index].file.readBytes() }
        processedCache[index]?.let { return it }
        if (ocrProtect && !ocrCache.containsKey(index)) {
            try {
                ocrCache[index] = ocrProvider.recognize(
                    originalCache.getOrPut(index) { pages[index].file.readBytes() }
                )
            } catch (_: Exception) {
            }
        }
        ensureOriginal(index)
        val zones = if (ocrProtect) ocrCache[index]?.blocks?.map { it.box } ?: emptyList() else emptyList()
        return withContext(Dispatchers.Default) {
            LazarusEngine.process(
                originalCache.getValue(index),
                LazarusEngine.Params(zones, removeRed, removeBlue, style),
            )
        }.also { processedCache[index] = it }
    }

    private fun applyRotation(bytes: ByteArray, rotation: Int): ByteArray {
        if (rotation % 360 == 0) return bytes
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        val out = java.io.ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
        if (rotated !== bmp) bmp.recycle()
        rotated.recycle()
        return out.toByteArray()
    }

    fun exportPdf(watermark: Boolean, onResult: (File?) -> Unit) {
        if (exporting) return
        exporting = true
        statusMessage = "BATCH PROCESSING…"
        viewModelScope.launch {
            var result: File? = null
            try {
                val rendered = ArrayList<ByteArray>(pages.size)
                for (i in pages.indices) {
                    statusMessage = "PROCESSING PAGE ${i + 1}/${pages.size}"
                    val bytes = bytesForExport(i)
                    if (bytes != null) rendered += applyRotation(bytes, pages[i].rotation)
                }
                if (rendered.isNotEmpty()) {
                    val name = DocumentExporter.generateFileName(selectedTags.toSet(), "pdf")
                    val out = File(DocumentExporter.ensureExportDir(app, "pdf"), name)
                    withContext(Dispatchers.IO) { DocumentExporter.exportPdf(app, rendered, out, watermark) }
                    result = out
                }
            } catch (_: Exception) {
                result = null
            } finally {
                exporting = false
                statusMessage = null
                onResult(result)
            }
        }
    }

    fun savePageImage(format: String, onResult: (Uri?) -> Unit) {
        val index = currentIndex
        if (index !in pages.indices || exporting) return
        exporting = true
        statusMessage = "PROCESSING…"
        viewModelScope.launch {
            var uri: Uri? = null
            try {
                val bytes = bytesForExport(index)
                if (bytes != null) {
                    val rotated = applyRotation(bytes, pages[index].rotation)
                    val name = DocumentExporter.generateFileName(selectedTags.toSet(), format.lowercase())
                    uri = withContext(Dispatchers.IO) {
                        DocumentExporter.saveImageToGallery(app, rotated, name, "image/${format.lowercase()}")
                    }
                }
            } catch (_: Exception) {
                uri = null
            } finally {
                exporting = false
                statusMessage = null
                onResult(uri)
            }
        }
    }

    fun exportOcrText(onResult: (File?) -> Unit) {
        val index = currentIndex
        if (index !in pages.indices || exporting) return
        exporting = true
        statusMessage = "OCR…"
        viewModelScope.launch {
            var file: File? = null
            try {
                ensureOriginal(index)
                val text = ocrCache.getOrPut(index) {
                    ocrProvider.recognize(originalCache.getValue(index))
                }.fullText
                if (text.isNotBlank()) {
                    val name = DocumentExporter.generateFileName(selectedTags.toSet(), "txt")
                    val out = File(DocumentExporter.ensureExportDir(app, "txt"), name)
                    file = withContext(Dispatchers.IO) { DocumentExporter.writeTextFile(out, text) }
                }
            } catch (_: Exception) {
                file = null
            } finally {
                exporting = false
                statusMessage = null
                onResult(file)
            }
        }
    }

    fun saveToLibrary(onDone: (Long?) -> Unit) {
        if (exporting) return
        exporting = true
        statusMessage = "ARCHIVING…"
        viewModelScope.launch {
            var docId: Long? = null
            try {
                val dao = app.database.archiveDao()
                val renderedPages = ArrayList<ByteArray>(pages.size)
                val ocrTexts = StringBuilder()
                for (i in pages.indices) {
                    statusMessage = "ARCHIVING PAGE ${i + 1}/${pages.size}"
                    bytesForExport(i)?.let { renderedPages += applyRotation(it, pages[i].rotation) }
                    try {
                        ensureOriginal(i)
                        val ocr = ocrCache.getOrPut(i) { ocrProvider.recognize(originalCache.getValue(i)) }
                        ocrTexts.append(ocr.fullText).append('\n')
                    } catch (_: Exception) {
                    }
                }
                if (renderedPages.isNotEmpty()) {
                    val title = DocumentExporter.generateFileName(selectedTags.toSet(), "pdf").removeSuffix(".pdf")
                    val pagesDir = File(app.filesDir, "pages").apply { mkdirs() }
                    val first = renderedPages.first()
                    val bmp = BitmapFactory.decodeByteArray(first, 0, first.size)
                    val thumb = if (bmp != null) {
                        val t = Bitmap.createScaledBitmap(bmp, 320, (320.0 * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
                        val thumbFile = File(pagesDir, "thumb_${System.currentTimeMillis()}.jpg")
                        thumbFile.outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                        if (t !== bmp) t.recycle()
                        bmp.recycle()
                        thumbFile
                    } else null

                    docId = dao.insertDocument(
                        DocumentEntity(
                            title = title,
                            tags = selectedTags.joinToString(","),
                            createdAt = System.currentTimeMillis(),
                            pageCount = renderedPages.size,
                            coverPath = thumb?.path ?: "",
                            ocrText = ocrTexts.toString(),
                        )
                    )
                    val entities = renderedPages.mapIndexed { i, bytes ->
                        val enc = withContext(Dispatchers.Default) { DocLocker.encrypt(bytes) }
                        val f = File(pagesDir, "${docId}_$i.bin")
                        f.writeBytes(enc)
                        // width/height are needed by the reader; cheap decode of the plain bytes
                        val dim = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dim)
                        PageEntity(docId = docId, order = i, path = f.path, width = dim.outWidth, height = dim.outHeight)
                    }
                    dao.insertPages(entities)
                    savedDocumentId = docId
                }
            } catch (_: Exception) {
                docId = null
            } finally {
                exporting = false
                statusMessage = null
                onDone(docId)
            }
        }
    }

    override fun onCleared() {
        ocrProvider.close()
        if (docKey == "new" && savedDocumentId == null) {
            pages.forEach { it.file.delete() }
        }
    }
}
