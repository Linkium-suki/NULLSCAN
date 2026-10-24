package moe.pulse.scan.ui.library

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.pulse.scan.PulseApp
import moe.pulse.scan.core.data.DocumentEntity
import moe.pulse.scan.core.data.PulseSettings
import moe.pulse.scan.ui.common.rememberToast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onScan: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PulseApp
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    var query by remember { mutableStateOf("") }
    val docs by remember(query) {
        if (query.isBlank()) app.database.archiveDao().observeAll()
        else app.database.archiveDao().search(query)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = PulseSettings())

    // Archive lock
    var unlocked by remember { mutableStateOf(!settings.biometricLock) }
    fun runUnlockPrompt() {
        val activity = context as? FragmentActivity
        val manager = BiometricManager.from(context)
        val canAuth = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (activity == null || canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pulse")
            .setSubtitle("Your archive is protected")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }
    LaunchedEffect(settings.biometricLock) {
        if (settings.biometricLock && !unlocked) runUnlockPrompt()
    }

    var pendingDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete document?") },
            text = { Text("“${doc.title}” and its ${doc.pageCount} encrypted pages will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = doc
                    pendingDelete = null
                    scope.launch {
                        app.database.archiveDao().pagesOf(target.id).forEach { File(it.path).delete() }
                        app.database.archiveDao().deleteDocument(target.id)
                        toast("Deleted")
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pulse") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onScan) {
                Icon(Icons.Filled.DocumentScanner, contentDescription = "Scan")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search title, tags, OCR text…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
            Spacer(Modifier.height(12.dp))

            if (!unlocked) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.height(48.dp))
                        Text("Archive locked", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                        Button(onClick = { runUnlockPrompt() }) { Text("Unlock") }
                    }
                }
            } else if (docs.isEmpty() && query.isBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.SignalCellular4Bar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(56.dp),
                        )
                        Text("Nothing scanned yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                        Text(
                            "Tap the scanner to capture or import paper.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                ) {
                    items(docs, key = { it.id }) { doc ->
                        DocumentCard(
                            doc = doc,
                            onClick = { onOpenDocument(doc.id) },
                            onDelete = { pendingDelete = doc },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(
    doc: DocumentEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onClick) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (doc.coverPath.isNotBlank()) {
                    AsyncImage(
                        model = File(doc.coverPath),
                        contentDescription = doc.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(doc.createdAt)) +
                        " · ${doc.pageCount} pages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (doc.tags.isNotBlank()) {
                    Text(
                        doc.tags.replace(",", " · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
