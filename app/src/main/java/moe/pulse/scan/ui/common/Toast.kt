package moe.pulse.scan.ui.common

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Returns a fire-and-forget toast function bound to the current context. */
@Composable
fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
}
