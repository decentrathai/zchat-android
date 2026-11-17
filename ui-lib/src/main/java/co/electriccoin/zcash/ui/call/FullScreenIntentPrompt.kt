package co.electriccoin.zcash.ui.call

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.electriccoin.zcash.ui.R

/**
 * ZC-1912 (owner W-148; HONOR denies the full-screen intent by default, owner test 16 Nov): on Android 14+ with the
 * full-screen intent not allowed, an incoming call cannot open the call screen over the lock screen. The app asks once
 * per app run (Open settings or Not now) and opens Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT for ZChat. Checked
 * again each time the app comes to the front, so it goes away once allowed. The answer is kept in memory only: no new
 * store on the phone (the Destroy All inventory stays as it is). Hosted by MainActivity.
 */
@Composable
fun FullScreenIntentPrompt() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var wanted by remember { mutableStateOf(promptWanted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) wanted = promptWanted(context)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!wanted) return
    val answer: (Boolean) -> Unit = { openSettings ->
        markAnswered()
        wanted = false
        if (openSettings) openFullScreenIntentSettings(context)
    }
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = { Text(stringResource(R.string.call_fsi_prompt_title)) },
        text = { Text(stringResource(R.string.call_fsi_prompt_body)) },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text(stringResource(R.string.call_fsi_prompt_open)) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.call_fsi_prompt_later)) } },
    )
}

private fun promptWanted(context: Context): Boolean {
    if (FullScreenIntentPromptMemory.answered) return false
    val allowed =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() }.getOrNull()
        } else {
            true
        }
    return CallLockScreen.needsFullScreenIntentGuide(Build.VERSION.SDK_INT, allowed)
}

private fun markAnswered() {
    FullScreenIntentPromptMemory.answered = true
}

/** Once per app run: the process (kept by the foreground service) remembers the answer. */
private object FullScreenIntentPromptMemory {
    @Volatile
    var answered = false
}

private fun openFullScreenIntentSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val exact =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:${context.packageName}"))
    // Some phones lack the screen for one app; the app's own settings page is the fallback.
    val fallback =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(exact) }
        .recoverCatching { context.startActivity(fallback) }
}
