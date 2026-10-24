package moe.pulse.scan.core.data

import java.io.File

/**
 * In-memory working set for the current scan session (photos taken or
 * imported that have not been archived yet). Kept process-local by design;
 * the durable copy lives in the encrypted archive once saved.
 */
data class PageRef(val file: File)

object ScanSession {
    val pages = mutableListOf<PageRef>()
    var pendingCrop: File? = null
    var pendingCutout: File? = null

    fun clear() {
        pages.forEach { it.file.delete() }
        pages.clear()
        pendingCrop?.delete()
        pendingCrop = null
        pendingCutout?.delete()
        pendingCutout = null
    }
}
