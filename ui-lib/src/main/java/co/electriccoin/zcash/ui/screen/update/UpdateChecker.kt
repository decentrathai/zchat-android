package co.electriccoin.zcash.ui.screen.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.design.component.AppAlertDialog
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdateChecker"
private const val PREFS_NAME = "zchat_update_check"
private const val KEY_DISMISSED_AT = "dismissed_at"
private const val DISMISS_COOLDOWN_MS = 24L * 60 * 60 * 1000
private const val VERSION_URL = "https://api.zsend.xyz/app/version"
private const val APK_FILENAME = "zchat-update.apk"

@Serializable
private data class AppVersionResponse(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String
)

private sealed interface UpdateState {
    data object Hidden : UpdateState
    data object UpToDate : UpdateState
    data class Prompt(val remote: AppVersionResponse) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Installing(val apkFile: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

object UpdateCheckTrigger {
    var manualCheck = mutableStateOf(false)
}

private fun versionNameToCode(versionName: String): Int {
    // Handle formats like "2.9.2-foss-debug (16)" or "2.9.2 (16)" or "2.9.2"
    // Strip everything after space, then strip suffixes like "-foss-debug"
    val raw = versionName.split(" ").first()  // "2.9.2-foss-debug"
    val parts = raw.split(".").map { segment ->
        // Take only leading digits from each segment: "2-foss-debug" → 2
        segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
    if (parts.size < 3) return 0
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}

private fun isDismissedRecently(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val dismissedAt = prefs.getLong(KEY_DISMISSED_AT, 0)
    return System.currentTimeMillis() - dismissedAt < DISMISS_COOLDOWN_MS
}

private fun recordDismissal(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_DISMISSED_AT, System.currentTimeMillis())
        .apply()
}

@Suppress("TooGenericExceptionCaught")
private suspend fun fetchLatestVersion(): AppVersionResponse? =
    withContext(Dispatchers.IO) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            HttpClient(OkHttp) {
                install(ContentNegotiation) { json(json) }
            }.use { client ->
                client.get(VERSION_URL).body<AppVersionResponse>()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Version check failed", e)
            null
        }
    }

@Suppress("TooGenericExceptionCaught", "MagicNumber")
private suspend fun downloadApk(
    context: Context,
    downloadUrl: String,
    onProgress: (Float) -> Unit
): File = withContext(Dispatchers.IO) {
    val apkFile = File(context.externalCacheDir, APK_FILENAME)
    if (apkFile.exists()) apkFile.delete()

    val url = URL(downloadUrl)
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("Server returned ${connection.responseCode}")
        }

        val totalBytes = connection.contentLength.toLong()
        var downloadedBytes = 0L

        connection.inputStream.use { input ->
            apkFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        onProgress(downloadedBytes.toFloat() / totalBytes)
                    }
                }
            }
        }
    } finally {
        connection.disconnect()
    }

    apkFile
}

private fun installApk(context: Context, apkFile: File) {
    val authority = getFileProviderAuthority(context)
    val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun getFileProviderAuthority(context: Context): String {
    val versionInfo = VersionInfo.new(context)
    val base = "xyz.zsend.zchat"
    val network = if (versionInfo.network == cash.z.ecc.android.sdk.model.ZcashNetwork.Testnet) ".testnet" else ""
    val foss = if (versionInfo.distribution == co.electriccoin.zcash.ui.common.model.DistributionDimension.FOSS) ".foss" else ""
    val debug = if (versionInfo.isDebuggable) ".debug" else ""
    return "$base$network$foss$debug.provider"
}

@Suppress("LongMethod")
@Composable
fun UpdateCheckOverlay() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val getVersionInfo: GetVersionInfoProvider = koinInject()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Hidden) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    val manualCheck by UpdateCheckTrigger.manualCheck

    LaunchedEffect(Unit) {
        if (isDismissedRecently(context)) return@LaunchedEffect

        val remote = fetchLatestVersion() ?: return@LaunchedEffect
        val localCode = versionNameToCode(getVersionInfo().versionName)

        if (remote.versionCode > localCode) {
            state = UpdateState.Prompt(remote)
        }
    }

    LaunchedEffect(manualCheck) {
        if (!manualCheck) return@LaunchedEffect
        UpdateCheckTrigger.manualCheck.value = false

        val remote = fetchLatestVersion()
        if (remote == null) {
            state = UpdateState.Failed("Could not reach update server. Check your connection.")
            return@LaunchedEffect
        }
        val localCode = versionNameToCode(getVersionInfo().versionName)
        if (remote.versionCode > localCode) {
            state = UpdateState.Prompt(remote)
        } else {
            state = UpdateState.UpToDate
        }
    }

    when (val current = state) {
        is UpdateState.Hidden -> { /* nothing */ }

        is UpdateState.UpToDate -> {
            AppAlertDialog(
                title = "Up to Date",
                text = { Text("You're running the latest version of ZCHAT (v${getVersionInfo().versionName.split(" ").first()}).") },
                confirmButtonText = "OK",
                onConfirmButtonClick = { state = UpdateState.Hidden },
                onDismissRequest = { state = UpdateState.Hidden }
            )
        }

        is UpdateState.Prompt -> {
            AppAlertDialog(
                title = "Update Available",
                text = {
                    Text("ZCHAT v${current.remote.versionName} is available. You have v${getVersionInfo().versionName.split(" ").first()}.")
                },
                confirmButtonText = "Update Now",
                onConfirmButtonClick = {
                    state = UpdateState.Downloading(0f)
                    scope.launch {
                        try {
                            val apkFile = downloadApk(context, current.remote.downloadUrl) { progress ->
                                downloadProgress = progress
                            }
                            state = UpdateState.Installing(apkFile)
                            installApk(context, apkFile)
                        } catch (e: Exception) {
                            Log.e(TAG, "Download failed", e)
                            state = UpdateState.Failed(e.message ?: "Download failed")
                        }
                    }
                },
                dismissButtonText = "Later",
                onDismissButtonClick = {
                    state = UpdateState.Hidden
                    recordDismissal(context)
                },
                onDismissRequest = {
                    state = UpdateState.Hidden
                    recordDismissal(context)
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { /* non-dismissable during download */ },
                confirmButton = {},
                shape = RoundedCornerShape(ZcashTheme.dimens.regularRippleEffectCorner),
                containerColor = ZashiColors.Surfaces.bgPrimary,
                title = { Text("Downloading Update...") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(downloadProgress * 100).toInt()}%")
                    }
                }
            )
        }

        is UpdateState.Installing -> {
            AlertDialog(
                onDismissRequest = { state = UpdateState.Hidden },
                shape = RoundedCornerShape(ZcashTheme.dimens.regularRippleEffectCorner),
                containerColor = ZashiColors.Surfaces.bgPrimary,
                title = { Text("Ready to Install") },
                text = {
                    Text("If the installer didn't open, tap Install below.")
                },
                confirmButton = {
                    TextButton(onClick = { installApk(context, current.apkFile) }) {
                        Text("Install")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { state = UpdateState.Hidden }) {
                        Text("Dismiss")
                    }
                }
            )
        }

        is UpdateState.Failed -> {
            AppAlertDialog(
                title = "Update Failed",
                text = { Text(current.message) },
                confirmButtonText = "OK",
                onConfirmButtonClick = { state = UpdateState.Hidden },
                onDismissRequest = { state = UpdateState.Hidden }
            )
        }
    }
}
