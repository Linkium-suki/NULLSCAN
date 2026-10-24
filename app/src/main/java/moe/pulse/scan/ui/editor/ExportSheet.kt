package moe.pulse.scan.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import moe.pulse.scan.core.export.DocumentExporter

/**
 * Export panel: PDF (with watermark toggle), PNG/JPG gallery save,
 * OCR text export, encrypted archive save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    viewModel: EditorViewModel,
    watermark: Boolean,
    onWatermarkChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = moe.pulse.scan.ui.common.rememberToast()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Export",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        ExportRow(Icons.Filled.PictureAsPdf, "Export PDF", "Adaptive page size · PS_ file name") {
            onDismiss()
            viewModel.exportPdf(watermark) { file ->
                if (file != null) DocumentExporter.shareFile(context, file, "application/pdf")
                else snackbar("Export failed")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PDF watermark", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = watermark, onCheckedChange = onWatermarkChange)
        }
        ExportRow(Icons.Filled.Image, "Save page as PNG", "Pictures/Pulse") {
            onDismiss()
            viewModel.savePageImage("PNG") { uri -> if (uri == null) snackbar("Save failed") }
        }
        ExportRow(Icons.Filled.Image, "Save page as JPG", "Pictures/Pulse") {
            onDismiss()
            viewModel.savePageImage("JPEG") { uri -> if (uri == null) snackbar("Save failed") }
        }
        ExportRow(Icons.Filled.TextSnippet, "Export OCR text", "Current page, block order") {
            onDismiss()
            viewModel.exportOcrText { file ->
                if (file != null) DocumentExporter.shareFile(context, file, "text/plain")
                else snackbar("No text recognized")
            }
        }
        ExportRow(Icons.Filled.Archive, "Save to library", "AES-256-GCM encrypted") {
            onDismiss()
            viewModel.saveToLibrary { id ->
                if (id == null) snackbar("Archive failed") else snackbar("Saved to library")
            }
        }
    }
}

@Composable
private fun ExportRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier
            .fillMaxWidth()
            .clickableRow(onClick),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
