package co.electriccoin.zcash.ui.screen.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.spackle.versionCodeCompat
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.design.component.AppAlertDialog
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdateChecker"
private const val PREFS_NAME = "zchat_update_check"
private const val KEY_DISMISSED_AT = "dismissed_at"
private const val KEY_DISMISSED_VERSION = "dismissed_version"
private const val DISMISS_COOLDOWN_MS = 24L * 60 * 60 * 1000
private const val VERSION_URL = "https://api.zsend.xyz/app/version"
private const val APK_FILENAME = "zchat-update.apk"
private const val VERSION_REQUEST_TIMEOUT_MS = 15_000L

/** A real version document is well under 10 KB; the cap leaves room for a long changelog. */
private const val MAX_VERSION_BODY_BYTES = 64L * 1024
private val VERSION_JSON = Json { ignoreUnknownKeys = true }

@Serializable
data class ChangelogEntry(
    val type: String,
    val text: String
)

@Serializable
internal data class AppVersionResponse(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val changelog: List<ChangelogEntry> = emptyList()
)

private sealed interface UpdateState {
    data object Hidden : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Prompt(val remote: AppVersionResponse) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Installing(val apkFile: File) : UpdateState

    // ES-A: the failure text as a string resource, shown in the language the app shows.
    data class Failed(
        @StringRes val message: Int
    ) : UpdateState
}

/**
 * One-shot event bus for "user tapped Check for Updates". Backed by a SharedFlow
 * with replay=0 + DROP_OLDEST so rapid double-taps collapse into one check, and
 * — critically — emitting doesn't cancel the observer the way a mutableStateOf
 * key on LaunchedEffect would.
 */
object UpdateCheckTrigger {
    private val _events = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun trigger() {
        _events.tryEmit(Unit)
    }
}

private fun isDismissedRecently(context: Context, remoteVersionCode: Int? = null): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val dismissedAt = prefs.getLong(KEY_DISMISSED_AT, 0)
    val dismissedVersion = prefs.getInt(KEY_DISMISSED_VERSION, 0)
    // If a newer version appeared since last dismissal, show prompt again
    if (remoteVersionCode != null && remoteVersionCode > dismissedVersion) return false
    return System.currentTimeMillis() - dismissedAt < DISMISS_COOLDOWN_MS
}

private fun recordDismissal(context: Context, versionCode: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_DISMISSED_AT, System.currentTimeMillis())
        .putInt(KEY_DISMISSED_VERSION, versionCode)
        .apply()
}

private suspend fun fetchLatestVersion(): AppVersionResponse? = fetchVersionDocument(VERSION_URL)

/**
 * Fetches and decodes the update server's version document from [url]; null on any failure.
 *
 * Bounded on purpose, because this runs on every launch of every foss install and the response is
 * not trusted: at most [MAX_VERSION_BODY_BYTES] of the body are ever read, and the whole request —
 * connect, headers and body — must finish within [requestTimeoutMillis]. An unbounded read let a
 * hostile or man-in-the-middled server stream an endless body until OutOfMemoryError (an Error,
 * not caught below) killed the process; a dribbling server never tripped OkHttp's per-read timeout.
 * Oversize, timeout and malformed JSON are all plain exceptions here, so they all mean null.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun fetchVersionDocument(
    url: String,
    requestTimeoutMillis: Long = VERSION_REQUEST_TIMEOUT_MS
): AppVersionResponse? =
    withContext(Dispatchers.IO) {
        try {
            HttpClient(OkHttp) {
                install(HttpTimeout) { this.requestTimeoutMillis = requestTimeoutMillis }
            }.use { client ->
                client.prepareGet(url).execute { response ->
                    // Read one byte past the cap: anything that reaches it is not a version document.
                    val body = response.bodyAsChannel().readRemaining(MAX_VERSION_BODY_BYTES + 1)
                    if (body.remaining > MAX_VERSION_BODY_BYTES) {
                        throw IOException("Version response exceeds $MAX_VERSION_BODY_BYTES bytes")
                    }
                    VERSION_JSON.decodeFromString<AppVersionResponse>(body.readText())
                }
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
    // Download into the app-PRIVATE internal cache, not externalCacheDir. On API 27-29 the external
    // cache is world-readable, so a co-installed app could swap the APK between download completion
    // and the install prompt. The FileProvider cache-path (path=".") already covers cacheDir.
    val apkDir = File(context.cacheDir, "apk_updates")
    if (!apkDir.exists()) apkDir.mkdirs()
    val apkFile = File(apkDir, APK_FILENAME)
    if (apkFile.exists()) apkFile.delete()

    val url = URL(downloadUrl)
    // Refuse to fetch an update over cleartext — a MITM could otherwise serve a substituted APK.
    if (!url.protocol.equals("https", ignoreCase = true)) {
        throw Exception("Refusing non-HTTPS update URL")
    }
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

/** What the downloaded APK file itself declares, or null when the system cannot parse it. */
@Suppress("TooGenericExceptionCaught")
private suspend fun readDownloadedBuild(
    context: Context,
    apkFile: File
): PackageInfo? =
    withContext(Dispatchers.IO) {
        try {
            val packageManager = context.packageManager
            if (AndroidApiVersion.isAtLeastTiramisu) {
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the downloaded update", e)
            null
        }
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
    // Google Play forbids an app updating itself outside Play (Device & Network Abuse). In a store
    // build the whole updater is inert: no version fetch, no APK download, no install prompt. The
    // matching REQUEST_INSTALL_PACKAGES permission is declared only in ui-lib/src/foss.
    if (!BuildConfig.IS_SELF_UPDATE_ENABLED) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val getVersionInfo: GetVersionInfoProvider = koinInject()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Hidden) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    // Silent check on first composition (only shows a dialog if a newer version exists
    // AND it wasn't dismissed in the last 24h).
    LaunchedEffect(Unit) {
        val remote = fetchLatestVersion() ?: return@LaunchedEffect
        if (isDismissedRecently(context, remote.versionCode)) return@LaunchedEffect
        val installed = getVersionInfo()

        if (isUpdateAvailable(
                installedVersionName = installed.versionName,
                installedVersionCode = installed.versionCode,
                publishedVersionName = remote.versionName,
                publishedVersionCode = remote.versionCode
            )
        ) {
            state = UpdateState.Prompt(remote)
        }
    }

    // Manual check: always shows a result dialog (UpToDate / Prompt / Failed). The
    // collector runs in a single coroutine keyed on Unit, so emitting an event
    // can't cancel the in-flight fetch — fixes the prior self-cancel bug where
    // writing to the trigger key inside the effect aborted fetchLatestVersion.
    LaunchedEffect(Unit) {
        UpdateCheckTrigger.events.collect {
            state = UpdateState.Checking
            val remote = fetchLatestVersion()
            if (remote == null) {
                state = UpdateState.Failed(R.string.update_dialog_failed_unreachable)
                return@collect
            }
            val installed = getVersionInfo()
            state = if (isUpdateAvailable(
                    installedVersionName = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    publishedVersionName = remote.versionName,
                    publishedVersionCode = remote.versionCode
                )
            ) {
                UpdateState.Prompt(remote)
            } else {
                Log.d(
                    TAG,
                    "Already up to date: local=${installed.versionName} (${installed.versionCode}) " +
                        "remote=${remote.versionName} (${remote.versionCode})"
                )
                UpdateState.UpToDate
            }
        }
    }

    when (val current = state) {
        is UpdateState.Hidden -> { /* nothing */ }

        is UpdateState.Checking -> {
            AlertDialog(
                onDismissRequest = { /* block dismiss while in-flight */ },
                confirmButton = {},
                shape = RoundedCornerShape(ZcashTheme.dimens.regularRippleEffectCorner),
                containerColor = ZashiColors.Surfaces.bgPrimary,
                title = { Text(stringResource(R.string.update_dialog_checking)) },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
            )
        }

        is UpdateState.UpToDate -> {
            val upgradeNotice by NetworkUpgradeGuard.state.collectAsStateWithLifecycle()
            AppAlertDialog(
                title = stringResource(R.string.update_dialog_up_to_date_title),
                text = {
                    Text(
                        if (upgradeNotice != null) {
                            stringResource(R.string.update_required_up_to_date_body)
                        } else {
                            stringResource(
                                R.string.update_dialog_up_to_date_body,
                                getVersionInfo().versionName.split(" ").first()
                            )
                        }
                    )
                },
                confirmButtonText = stringResource(R.string.update_dialog_ok),
                onConfirmButtonClick = { state = UpdateState.Hidden },
                onDismissRequest = { state = UpdateState.Hidden }
            )
        }

        is UpdateState.Prompt -> {
            var detailsExpanded by remember { mutableStateOf(false) }
            val localVersion = getVersionInfo().versionName.split(" ").first()
            val hasChangelog = current.remote.changelog.isNotEmpty()

            AlertDialog(
                onDismissRequest = {
                    state = UpdateState.Hidden
                    recordDismissal(context, current.remote.versionCode)
                },
                shape = RoundedCornerShape(NightwireColors.RadiusModal),
                containerColor = NightwireColors.BgElevated,
                title = {
                    Text(
                        text = stringResource(R.string.update_dialog_available_title),
                        color = NightwireColors.AccentPrimary,
                        fontFamily = RajdhaniFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                text = {
                    Column {
                        Text(
                            text =
                                stringResource(
                                    R.string.update_dialog_available_body,
                                    current.remote.versionName,
                                    localVersion
                                ),
                            color = NightwireColors.TextSecondary,
                            fontSize = 14.sp
                        )

                        if (hasChangelog) {
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { detailsExpanded = !detailsExpanded }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text =
                                        stringResource(
                                            if (detailsExpanded) {
                                                R.string.update_dialog_hide_details
                                            } else {
                                                R.string.update_dialog_show_details
                                            }
                                        ),
                                    color = NightwireColors.AccentPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = if (detailsExpanded) {
                                        Icons.Default.KeyboardArrowUp
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = null,
                                    tint = NightwireColors.AccentPrimary
                                )
                            }

                            AnimatedVisibility(
                                visible = detailsExpanded,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    current.remote.changelog.forEach { entry ->
                                        ChangelogRow(entry)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            // reset so a retry after a failed download can't show the previous attempt's %
                            downloadProgress = 0f
                            state = UpdateState.Downloading(0f)
                            scope.launch {
                                try {
                                    val apkFile = downloadApk(
                                        context,
                                        current.remote.downloadUrl
                                    ) { progress ->
                                        downloadProgress = progress
                                    }
                                    // Ask the FILE what it is, not the server what it should be.
                                    val downloaded = readDownloadedBuild(context, apkFile)
                                    val installedNow = getVersionInfo()
                                    val installable =
                                        isDownloadedUpdateInstallable(
                                            installedPackageName = context.packageName,
                                            installedVersionName = installedNow.versionName,
                                            installedVersionCode = installedNow.versionCode,
                                            downloadedPackageName = downloaded?.packageName,
                                            downloadedVersionName = downloaded?.versionName,
                                            downloadedVersionCode = downloaded?.versionCodeCompat ?: 0L
                                        )
                                    if (!installable) {
                                        Log.w(
                                            TAG,
                                            "Refusing downloaded update: file=${downloaded?.packageName} " +
                                                "${downloaded?.versionName} (${downloaded?.versionCodeCompat}) " +
                                                "installed=${context.packageName} ${installedNow.versionName}"
                                        )
                                        apkFile.delete()
                                        // Silence the silent check for the cooldown so a mis-published file
                                        // is not downloaded again on every launch.
                                        recordDismissal(context, current.remote.versionCode)
                                        state = UpdateState.Failed(R.string.update_dialog_failed_not_newer)
                                        return@launch
                                    }
                                    state = UpdateState.Installing(apkFile)
                                    installApk(context, apkFile)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Download failed", e)
                                    // Never surface a raw exception string to the user.
                                    state = UpdateState.Failed(R.string.update_dialog_failed_download)
                                }
                            }
                        },
                        shape = RoundedCornerShape(NightwireColors.RadiusButton),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NightwireColors.AccentPrimary,
                            contentColor = NightwireColors.TextOnAccent
                        ),
                        modifier = Modifier.shadow(
                            elevation = 12.dp,
                            shape = RoundedCornerShape(NightwireColors.RadiusButton),
                            ambientColor = NightwireColors.AccentPrimaryGlow,
                            spotColor = NightwireColors.AccentPrimaryGlow
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.update_dialog_update_now),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            state = UpdateState.Hidden
                            recordDismissal(context, current.remote.versionCode)
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.update_dialog_not_now),
                            color = NightwireColors.TextSecondary
                        )
                    }
                }
            )
        }

        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = { /* non-dismissable during download */ },
                confirmButton = {},
                shape = RoundedCornerShape(ZcashTheme.dimens.regularRippleEffectCorner),
                containerColor = ZashiColors.Surfaces.bgPrimary,
                title = { Text(stringResource(R.string.update_dialog_downloading)) },
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
                title = { Text(stringResource(R.string.update_dialog_ready_title)) },
                text = {
                    Text(stringResource(R.string.update_dialog_ready_body))
                },
                confirmButton = {
                    TextButton(onClick = { installApk(context, current.apkFile) }) {
                        Text(stringResource(R.string.update_dialog_install))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { state = UpdateState.Hidden }) {
                        Text(stringResource(R.string.update_dialog_dismiss))
                    }
                }
            )
        }

        is UpdateState.Failed -> {
            AppAlertDialog(
                title = stringResource(R.string.update_dialog_failed_title),
                text = { Text(stringResource(current.message)) },
                confirmButtonText = stringResource(R.string.update_dialog_ok),
                onConfirmButtonClick = { state = UpdateState.Hidden },
                onDismissRequest = { state = UpdateState.Hidden }
            )
        }
    }
}

@Composable
private fun ChangelogRow(entry: ChangelogEntry) {
    Row(verticalAlignment = Alignment.Top) {
        val (prefix, color) = when (entry.type) {
            "added" -> "+" to NightwireColors.AccentSuccess
            "improved" -> "~" to NightwireColors.AccentPrimary
            "fixed" -> "\u2713" to NightwireColors.AccentPrimary
            "removed" -> "\u2212" to NightwireColors.ColorDanger
            "security" -> "\u26E8" to NightwireColors.ColorWarning
            else -> "\u2022" to NightwireColors.TextSecondary
        }
        Text(
            text = prefix,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.width(20.dp)
        )
        Text(
            text = entry.text,
            color = NightwireColors.TextPrimary,
            fontSize = 13.sp
        )
    }
}
