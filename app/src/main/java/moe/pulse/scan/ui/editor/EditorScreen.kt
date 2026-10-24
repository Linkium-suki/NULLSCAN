package moe.pulse.scan.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import moe.pulse.scan.PulseApp
import moe.pulse.scan.core.data.PulseSettings
import moe.pulse.scan.core.export.Tags
import moe.pulse.scan.ui.common.rememberToast
import moe.pulse.scan.ui.theme.BadgeStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    docKey: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PulseApp
    val toast = rememberToast()
    val scope = rememberCoroutineScope()

    val vm: EditorViewModel = viewModel(
        key = docKey,
        factory = viewModelFactory { initializer { EditorViewModel(app, docKey) } },
    )
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = PulseSettings())

    var showExport by remember { mutableStateOf(false) }

    val addPagesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) vm.addPages(uris) {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "PAGE ${vm.currentIndex + 1} OF ${vm.pages.size}",
                        style = BadgeStyle,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        addPagesPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add pages")
                    }
                    IconButton(onClick = { showExport = true }) {
                        Icon(Icons.Filled.SaveAlt, contentDescription = "Export")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            val pagerState = rememberPagerState(pageCount = { vm.pages.size })
            LaunchedEffect(pagerState.currentPage) {
                vm.changePage(pagerState.currentPage)
            }
            LaunchedEffect(vm.currentIndex) {
                if (pagerState.currentPage != vm.currentIndex) {
                    pagerState.animateScrollToPage(vm.currentIndex)
                }
            }

            // Naming system: legacy tag taxonomy
            Box(modifier = Modifier.height(96.dp)) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Tags.ALL.forEach { tag ->
                        val selected = tag in vm.selectedTags
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected) vm.selectedTags.remove(tag) else vm.selectedTags.add(tag)
                            },
                            label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

            // Preview area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                if (vm.pages.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { index ->
                        PagePreview(vm = vm, index = index)
                    }

                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                        color = if (vm.engineOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = if (vm.engineOn) "PULSE: ON" else "ORIGINAL",
                            style = BadgeStyle.copy(
                                color = if (vm.engineOn) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                if (vm.pageLoading || vm.exporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = vm.statusMessage ?: "PROCESSING…",
                                style = BadgeStyle.copy(color = MaterialTheme.colorScheme.onPrimary),
                            )
                        }
                    }
                }
            }

            // Page navigation
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                    }
                }, enabled = vm.currentIndex > 0) {
                    Text("‹", style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    "PAGE ${vm.currentIndex + 1} OF ${vm.pages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(vm.pages.size - 1))
                    }
                }, enabled = vm.currentIndex < vm.pages.size - 1) {
                    Text("›", style = MaterialTheme.typography.headlineSmall)
                }
            }

            // Control panel
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = vm.engineOn, onCheckedChange = {
                                vm.engineOn = it
                                vm.onParamChanged()
                            })
                            Spacer(Modifier.width(8.dp))
                            Text("ENGINE", style = MaterialTheme.typography.labelLarge)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("OCR PROTECT", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(4.dp))
                            Switch(checked = vm.ocrProtect, onCheckedChange = {
                                vm.ocrProtect = it
                                vm.onParamChanged()
                            })
                        }
                    }

                    if (vm.engineOn) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("FILTER", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(12.dp))
                            FilterChip(
                                selected = vm.removeRed,
                                onClick = { vm.removeRed = !vm.removeRed; vm.onParamChanged() },
                                label = { Text("RED", style = MaterialTheme.typography.labelMedium) },
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = vm.removeBlue,
                                onClick = { vm.removeBlue = !vm.removeBlue; vm.onParamChanged() },
                                label = { Text("BLUE", style = MaterialTheme.typography.labelMedium) },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("STYLE", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(12.dp))
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                                val labels = listOf("Original", "De-Pencil", "De-Ink")
                                labels.forEachIndexed { i, label ->
                                    SegmentedButton(
                                        selected = vm.style == i,
                                        onClick = { vm.style = i; vm.onParamChanged() },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                    )
                                }
                            }
                        }

                        // Page ops
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AssistChip(onClick = { vm.rotateCurrent() }, label = { Text("Rotate") })
                            AssistChip(onClick = { vm.moveCurrent(-1) }, label = { Text("Move up") })
                            AssistChip(onClick = { vm.moveCurrent(+1) }, label = { Text("Move down") })
                            AssistChip(
                                onClick = { vm.deleteCurrent() },
                                label = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            )
                        }
                    }
                }
            }

            Text(
                "POWERED BY PULSE ENGINE",
                style = BadgeStyle.copy(color = MaterialTheme.colorScheme.outline, fontSize = MaterialTheme.typography.labelSmall.fontSize),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showExport) {
        ExportSheet(
            viewModel = vm,
            watermark = settings.watermark,
            onWatermarkChange = { enabled ->
                scope.launch { app.settings.setWatermark(enabled) }
            },
            onDismiss = { showExport = false },
        )
    }
}

@Composable
private fun PagePreview(vm: EditorViewModel, index: Int) {
    val isCurrent = index == vm.currentIndex
    val rawBytes: ByteArray? = when {
        vm.engineOn -> vm.processedBytesFor(index)
        else -> vm.originalBytesFor(index)
    }
    var scale by remember(index, vm.engineOn) { mutableFloatStateOf(1f) }
    var offset by remember(index, vm.engineOn) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offset += panChange
    }

    val bmp = remember(rawBytes) {
        rawBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                        rotationZ = vm.pages.getOrNull(index)?.rotation?.toFloat() ?: 0f
                    }
                    .transformable(transformState),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        } else if (isCurrent) {
            CircularProgressIndicator()
        }
    }
}
