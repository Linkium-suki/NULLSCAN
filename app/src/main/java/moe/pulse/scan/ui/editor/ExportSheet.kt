package moe.pulse.scan.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.pulse.scan.R
import moe.pulse.scan.core.export.DocumentExporter
import moe.pulse.scan.ui.common.rememberToast

/**
 * Export panel: PDF straight to Downloads/Pulse (share as optional second
 * path), PNG/JPG to the gallery, OCR text, encrypted archive save.
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
    val toast = rememberToast()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.export_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            ExportRow(Icons.Filled.PictureAsPdf, stringResource(R.string.export_pdf), stringResource(R.string.export_pdf_sub)) {
                onDismiss()
                viewModel.savePdf(watermark) { ok ->
                    toast(context.getString(if (ok) R.string.saved_toast else R.string.save_failed_toast))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.pdf_watermark), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = watermark, onCheckedChange = onWatermarkChange)
            }
            ExportRow(Icons.Filled.Share, stringResource(R.string.share_pdf), stringResource(R.string.share_pdf_sub)) {
                onDismiss()
                viewModel.sharePdf(watermark) { file ->
                    if (file != null) DocumentExporter.shareFile(context, file, "application/pdf")
                    else toast(context.getString(R.string.save_failed_toast))
                }
            }
            ExportRow(Icons.Filled.Image, stringResource(R.string.save_page_png), stringResource(R.string.save_gallery_sub)) {
                onDismiss()
                viewModel.savePageImage("PNG") { uri -> if (uri == null) toast(context.getString(R.string.save_failed_toast)) }
            }
            ExportRow(Icons.Filled.Image, stringResource(R.string.save_page_jpg), stringResource(R.string.save_gallery_sub)) {
                onDismiss()
                viewModel.savePageImage("JPEG") { uri -> if (uri == null) toast(context.getString(R.string.save_failed_toast)) }
            }
            ExportRow(Icons.Filled.TextSnippet, stringResource(R.string.export_ocr_text), stringResource(R.string.export_ocr_sub)) {
                onDismiss()
                viewModel.saveOcrText { ok ->
                    toast(context.getString(if (ok) R.string.saved_toast else R.string.no_text_toast))
                }
            }
            ExportRow(Icons.Filled.Archive, stringResource(R.string.save_to_library), stringResource(R.string.save_to_library_sub)) {
                onDismiss()
                viewModel.saveToLibrary { id ->
                    toast(context.getString(if (id != null) R.string.archived_toast else R.string.archive_failed_toast))
                }
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
            .clickable(onClick = onClick),
    )
}
