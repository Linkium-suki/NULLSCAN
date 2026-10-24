package moe.pulse.scan

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import moe.pulse.scan.core.data.PulseSettings
import moe.pulse.scan.ui.crop.CropScreen
import moe.pulse.scan.ui.editor.EditorScreen
import moe.pulse.scan.ui.library.LibraryScreen
import moe.pulse.scan.ui.scanner.ScannerScreen
import moe.pulse.scan.ui.settings.SettingsScreen
import moe.pulse.scan.ui.theme.PulseTheme

/**
 * FragmentActivity (not ComponentActivity) so BiometricPrompt is available
 * for the archive lock.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PulseApp
        setContent {
            val settings by app.settings.settings
                .collectAsStateWithLifecycle(initialValue = PulseSettings())
            PulseTheme(dynamicColor = settings.dynamicColor) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(
                            onScan = { nav.navigate("scanner") },
                            onOpenDocument = { id -> nav.navigate("editor/doc/$id") },
                            onOpenSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("scanner") {
                        ScannerScreen(
                            onCaptured = { nav.navigate("crop") },
                            onImported = { nav.navigate("editor/new") },
                        )
                    }
                    composable("crop") {
                        CropScreen(
                            onConfirm = {
                                nav.navigate("editor/new") {
                                    popUpTo("scanner") { inclusive = true }
                                }
                            },
                            onCancel = { nav.popBackStack() },
                        )
                    }
                    composable("editor/new") {
                        EditorScreen(
                            docKey = "new",
                            onClose = {
                                nav.navigate("library") {
                                    popUpTo("library") { inclusive = true }
                                }
                            },
                        )
                    }
                    composable("editor/doc/{docId}") { entry ->
                        val docId = entry.arguments?.getString("docId") ?: "0"
                        EditorScreen(
                            docKey = "doc/$docId",
                            onClose = {
                                nav.navigate("library") {
                                    popUpTo("library") { inclusive = true }
                                }
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
