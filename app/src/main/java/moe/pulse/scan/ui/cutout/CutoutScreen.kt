package moe.pulse.scan.ui.cutout

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.pulse.scan.PulseApp
import moe.pulse.scan.R
import moe.pulse.scan.ui.common.rememberToast
import androidx.compose.foundation.layout.Spacer

/**
 * Interactive cutout: AI portrait segmentation (bundled ML Kit model) or
 * GrabCut over a user-drawn frame, with feathering and background options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CutoutScreen(
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PulseApp
    val toast = rememberToast()

    val vm: CutoutViewModel = viewModel(
        factory = viewModelFactory { initializer { CutoutViewModel(app) } },
    )
    vm.onResult { ok -> if (!ok) toast(context.getString(R.string.cutout_no_subject_toast)) }

    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragRect by remember { mutableStateOf<RectF?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cutout_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_cd))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        dragStart = null
                        dragRect = null
                        vm.rect = null
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.cutout_redraw))
                    }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = vm.mode == 0,
                        onClick = { vm.mode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        label = { Text(stringResource(R.string.cutout_mode_ai), style = MaterialTheme.typography.labelMedium) },
                    )
                    SegmentedButton(
                        selected = vm.mode == 1,
                        onClick = { vm.mode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        label = { Text(stringResource(R.string.cutout_mode_grabcut), style = MaterialTheme.typography.labelMedium) },
                    )
                }

                if (vm.mode == 1) {
                    Text(
                        stringResource(R.string.cutout_drag_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.cutout_feather), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = vm.feather,
                        onValueChange = { vm.feather = it; vm.recomposite() },
                        valueRange = 0f..10f,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.cutout_background), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.size(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                        val bgs = listOf(R.string.bg_transparent, R.string.bg_white, R.string.bg_black)
                        bgs.forEachIndexed { i, res ->
                            SegmentedButton(
                                selected = vm.bg == i,
                                onClick = { vm.bg = i; vm.recomposite() },
                                shape = SegmentedButtonDefaults.itemShape(i, bgs.size),
                                label = { Text(stringResource(res), style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (vm.mode == 1 && vm.rect == null && dragRect != null) vm.rect = dragRect
                            vm.run()
                        },
                        enabled = vm.hasSource && !vm.running && (vm.mode == 0 || dragRect != null || vm.rect != null),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (vm.running) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            stringResource(R.string.cutout_run),
                            modifier = Modifier.padding(start = if (vm.running) 8.dp else 0.dp),
                        )
                    }
                    Button(
                        onClick = {
                            vm.save { ok ->
                                toast(context.getString(if (ok) R.string.cutout_saved_toast else R.string.cutout_failed_toast))
                            }
                        },
                        enabled = vm.resultBitmap != null && !vm.running,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.cutout_apply))
                    }
                }
            }
        },
    ) { padding ->
        val source = vm.sourceBitmap()
        if (!vm.hasSource || source == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.cutout_failed_toast))
            }
            return@Scaffold
        }
        BoxWithConstraintsCompat(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(padding),
            source = source,
            vm = vm,
            dragStart = dragStart,
            dragRect = dragRect,
            onDragStart = { dragStart = it },
            onDragUpdate = { rect -> dragRect = rect },
            onDragEnd = { vm.rect = dragRect },
        )
    }
}

@Composable
private fun BoxWithConstraintsCompat(
    modifier: Modifier,
    source: Bitmap,
    vm: CutoutViewModel,
    dragStart: Offset?,
    dragRect: RectF?,
    onDragStart: (Offset) -> Unit,
    onDragUpdate: (RectF?) -> Unit,
    onDragEnd: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val result = vm.resultBitmap

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
        val show = result ?: source
        androidx.compose.foundation.layout.Box(modifier = Modifier.align(Alignment.Center).fillMaxSize()) {
            Image(
                bitmap = show.asImageBitmap(),
                contentDescription = stringResource(R.string.crop_source_cd),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(vm.mode, source) {
                        if (vm.mode != 1) return@pointerInput
                        detectDragGestures(
                            onDragStart = { pos ->
                                onDragStart(pos)
                                onDragUpdate(null)
                            },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd,
                        ) { change, amount ->
                            change.consume()
                            val start = dragStart ?: return@detectDragGestures
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val s = minOf(w / source.width, h / source.height)
                            val dw = source.width * s
                            val dh = source.height * s
                            val left = (w - dw) / 2f
                            val top = (h - dh) / 2f
                            val p1 = change.position
                            val nx1 = ((minOf(start.x, p1.x) - left) / dw).coerceIn(0f, 1f)
                            val ny1 = ((minOf(start.y, p1.y) - top) / dh).coerceIn(0f, 1f)
                            val nx2 = ((maxOf(start.x, p1.x) - left) / dw).coerceIn(0f, 1f)
                            val ny2 = ((maxOf(start.y, p1.y) - top) / dh).coerceIn(0f, 1f)
                            onDragUpdate(RectF(nx1, ny1, nx2, ny2))
                        }
                    },
            ) {
                if (vm.mode != 1) return@Canvas
                val w = size.width
                val h = size.height
                val s = minOf(w / source.width, h / source.height)
                val dw = source.width * s
                val dh = source.height * s
                val left = (w - dw) / 2f
                val top = (h - dh) / 2f
                val r = dragRect ?: vm.rect
                r?.let { box ->
                    val p = Path().apply {
                        moveTo(left + box.left * dw, top + box.top * dh)
                        lineTo(left + box.right * dw, top + box.top * dh)
                        lineTo(left + box.right * dw, top + box.bottom * dh)
                        lineTo(left + box.left * dw, top + box.bottom * dh)
                        close()
                    }
                    drawPath(p, color = primaryColor.copy(alpha = 0.15f))
                    drawPath(p, color = primaryColor, style = Stroke(width = 4f))
                }
            }

            if (vm.running) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                        .padding(24.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.cutout_running),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
