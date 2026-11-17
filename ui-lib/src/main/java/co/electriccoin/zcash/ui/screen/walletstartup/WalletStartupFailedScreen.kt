package co.electriccoin.zcash.ui.screen.walletstartup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.component.zashiVerticalGradient
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackup
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * Shown instead of a silent crash loop when the SDK cannot open the wallet (RD-4, see
 * [co.electriccoin.zcash.ui.common.provider.SynchronizerStartupFailureGuard]). It tells the user not to
 * uninstall or clear data, and keeps the recovery-phrase backup one tap away: that screen reads the seed from
 * the encrypted preferences and does not need a running synchronizer.
 *
 * P2f: "Try again" restarts ZCHAT (the SDK's synchronizer does not come back in the same process). When the SDK
 * proved that the wallet data belongs to a different recovery phrase and the saved phrase is readable, the
 * screen explains that instead and offers to set the old data aside (renamed, never deleted) and rescan
 * ([WalletStartupRepair]).
 *
 * R2-3: also shown when the store with the recovery phrase or the encrypted chat stores cannot be opened at
 * start-up (a phone-security error, [StartupStorageCheck]), with its own explanation and Try again.
 *
 * ZC-692-S: the two states after a finished Destroy All ([StartupStorageCheck.State.DestroyUnfinished],
 * [StartupStorageCheck.State.DestroyRecordStuck]) are drawn by their own view ([DestroyStartView]), not by this
 * screen's frame: they tell the user to uninstall, so they must show neither "Do not uninstall" nor the backup
 * button nor Try again.
 */
@Serializable
data object WalletStartupFailedArgs

@Composable
fun WalletStartupFailedScreen() {
    val activity = LocalActivity.current
    val navigationRouter = koinInject<NavigationRouter>()
    val synchronizerProvider = koinInject<SynchronizerProvider>()
    val startupStorageCheck = koinInject<StartupStorageCheck>()
    val repair = koinInject<WalletStartupRepair>()
    val appRestarter = koinInject<AppRestarter>()
    val error by synchronizerProvider.error.collectAsStateWithLifecycle()
    val storage by startupStorageCheck.state.collectAsStateWithLifecycle()
    // ZC-090-P: a Reset ZCHAT that could not be finished has its own screen with two buttons, and no other way on.
    if (storage == StartupStorageCheck.State.ResetUnfinished) {
        BackHandler { activity.finish() }
        ResetStartView(onTryAgain = { appRestarter.restart() }, onUninstall = { requestUninstall(activity) })
        return
    }
    // ZC-692-S: a finished Destroy All that left something, or whose record could not be removed, has its own
    // screen with one button; the frame below is not reached for it.
    val destroyContent = destroyStartContent(storage)
    if (destroyContent != null) {
        BackHandler { activity.finish() }
        DestroyStartView(content = destroyContent, onUninstall = { requestUninstall(activity) })
        return
    }
    // R2-3: a storage error found at start-up (the recovery phrase or the chats cannot be read) comes first.
    val cause = startupFailureCause(error, storage)
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var setAsideFailed by remember { mutableStateOf(false) }
    val offer by produceState<WalletStartupRepair.Offer>(WalletStartupRepair.Offer.SafetyScreen, cause) {
        value = repair.offerFor(cause)
    }

    // Nothing behind this screen works without a synchronizer; leaving it closes the app.
    BackHandler { activity.finish() }

    WalletStartupFailedView(
        errorName = startupErrorName(cause),
        offer = offer,
        isWorking = isWorking,
        setAsideFailed = setAsideFailed,
        onBackUpRecoveryPhrase = { navigationRouter.forward(WalletBackup(isOpenedFromSeedBackupInfo = false)) },
        onTryAgain = {
            isWorking = true
            repair.tryAgain()
        },
        onSetAsideAndRescan = {
            isWorking = true
            scope.launch {
                // On success ZCHAT restarts; getting here again means nothing was changed.
                setAsideFailed = !repair.setAsideAndRescan(cause)
                isWorking = false
            }
        },
        onRestoreChatCopy = {
            isWorking = true
            scope.launch {
                // R3-3: on success ZCHAT restarts; getting here again means nothing was changed.
                setAsideFailed = !repair.restoreChatCopy()
                isWorking = false
            }
        },
        onContinueWithoutChatCopy = {
            isWorking = true
            scope.launch {
                setAsideFailed = !repair.continueWithoutChatCopy()
                isWorking = false
            }
        },
        onChatCopyFailureRead = {
            isWorking = true
            scope.launch {
                // R4-5: on success ZCHAT restarts and does not ask about this copy again.
                setAsideFailed = !repair.acknowledgeChatCopyFailure()
                isWorking = false
            }
        },
        onChatCopyPauseRead = {
            isWorking = true
            scope.launch {
                // B5: on success ZCHAT restarts; the copy is offered again once the phone's security system answers.
                setAsideFailed = !repair.acknowledgeChatCopyPause()
                isWorking = false
            }
        },
        onChatCopyParkRead = {
            isWorking = true
            scope.launch {
                // F10: on success ZCHAT restarts; the parked copy is offered again only from Settings.
                setAsideFailed = !repair.acknowledgeChatCopyPark()
                isWorking = false
            }
        },
    )
}

/** ZC-692-S: asks the phone to uninstall ZCHAT (the system's own confirmation screen). Changes no data. */
@Suppress("TooGenericExceptionCaught")
private fun requestUninstall(activity: Activity) {
    try {
        activity.startActivity(Intent(Intent.ACTION_DELETE).apply { data = Uri.parse("package:${activity.packageName}") })
    } catch (e: Exception) {
        Twig.warn { "DESTROY - the uninstall screen could not be opened: ${e.javaClass.simpleName}" }
    }
}

/**
 * ZC-692-S: what the start shows after it finished a Destroy All and something could not be removed: a title, a
 * body, one line per kind that is left ([lines], each its own text), the action text and the ONE button, which asks
 * for the uninstall. There is no other way on.
 */
data class DestroyStartContent(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val lines: List<Int>,
    @StringRes val action: Int,
    @StringRes val button: Int,
)

/**
 * ZC-692-S: the content for the two Destroy All states of the start-up check, null for every other state (those
 * keep this screen's frame, unchanged).
 */
fun destroyStartContent(storage: StartupStorageCheck.State): DestroyStartContent? =
    when (storage) {
        is StartupStorageCheck.State.DestroyUnfinished ->
            DestroyStartContent(
                title = R.string.destroy_unfinished_title,
                body = R.string.destroy_unfinished_body,
                lines =
                    listOfNotNull(
                        R.string.destroy_unfinished_files.takeIf { DestroyWipe.KIND_FILES in storage.kinds },
                        R.string.destroy_unfinished_keys.takeIf { DestroyWipe.KIND_KEYS in storage.kinds },
                        R.string.destroy_unfinished_notifications.takeIf { DestroyWipe.KIND_NOTIFICATIONS in storage.kinds },
                        R.string.destroy_unfinished_process.takeIf { DestroyWipe.KIND_PROCESS in storage.kinds },
                        R.string.destroy_unfinished_jobs.takeIf { DestroyWipe.KIND_JOBS in storage.kinds },
                    ),
                action = R.string.destroy_unfinished_action,
                button = R.string.destroy_unfinished_button,
            )
        StartupStorageCheck.State.DestroyRecordStuck ->
            DestroyStartContent(
                title = R.string.destroy_record_stuck_title,
                body = R.string.destroy_record_stuck_body,
                lines = emptyList(),
                action = R.string.destroy_unfinished_action,
                button = R.string.destroy_unfinished_button,
            )
        else -> null
    }

/** ZC-692-S: the screen of [DestroyStartContent]. It has one button and names no other way on. */
@Composable
fun DestroyStartView(
    content: DestroyStartContent,
    onUninstall: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(zashiVerticalGradient(ZashiColors.Utility.ErrorRed.utilityError100))
    ) {
        Scaffold(containerColor = Color.Transparent) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .scaffoldPadding(paddingValues)
                        .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(48.dp))
                Image(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    painter = painterResource(R.drawable.ic_error_warning),
                    contentDescription = null
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    text = stringResource(content.title),
                    style = ZashiTypography.header6,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    text = stringResource(content.body),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textPrimary
                )
                content.lines.forEach { line ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        text = stringResource(line),
                        style = ZashiTypography.textSm,
                        fontWeight = FontWeight.SemiBold,
                        color = ZashiColors.Text.textPrimary
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    text = stringResource(content.action),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textPrimary
                )
                Spacer(Modifier.height(32.dp))
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(content.button),
                    onClick = onUninstall,
                )
            }
        }
    }
}

/**
 * ZC-090-P: the screen of [StartupStorageCheck.State.ResetUnfinished]: what is left of the reset, Try again (a restart:
 * the next start runs the pass again) and Uninstall. Its own view: none of the start-up frame's parts, not Destroy's.
 */
@Composable
fun ResetStartView(
    onTryAgain: () -> Unit,
    onUninstall: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(zashiVerticalGradient(ZashiColors.Utility.ErrorRed.utilityError100))
    ) {
        Scaffold(containerColor = Color.Transparent) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .scaffoldPadding(paddingValues)
                        .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(48.dp))
                Image(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    painter = painterResource(R.drawable.ic_error_warning),
                    contentDescription = null
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    text = stringResource(R.string.reset_unfinished_title),
                    style = ZashiTypography.header6,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    text = stringResource(R.string.reset_unfinished_body),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textPrimary
                )
                Spacer(Modifier.height(32.dp))
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.reset_unfinished_try_again),
                    colors = ZashiButtonDefaults.secondaryColors(),
                    onClick = onTryAgain,
                )
                Spacer(Modifier.height(12.dp))
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.reset_unfinished_uninstall),
                    colors = ZashiButtonDefaults.tertiaryColors(),
                    onClick = onUninstall,
                )
            }
        }
    }
}

/** R5-3: one thing a button of the start-up screen does. The screen itself performs it ([WalletStartupRepair]). */
enum class WalletStartupAction {
    TRY_AGAIN,
    SET_ASIDE_AND_RESCAN,
    RESTORE_CHAT_COPY,
    CONTINUE_WITHOUT_CHAT_COPY,
    CHAT_COPY_FAILURE_READ,
    CHAT_COPY_PAUSE_READ,
    CHAT_COPY_PARK_READ,
}

/** R5-3: one button of the start-up screen: the text it shows and what it does. */
data class WalletStartupButton(
    @StringRes val text: Int,
    val action: WalletStartupAction,
)

/**
 * R5-3: what the start-up screen says and offers for one [WalletStartupRepair.Offer]. [buttons] follow the
 * "back up recovery phrase" button, the first of them being the screen's main one; [actionFailedMessage] is
 * shown when that action changed nothing.
 */
data class WalletStartupFailedContent(
    @StringRes val title: Int,
    @StringRes val message: Int,
    val buttons: List<WalletStartupButton>,
    @StringRes val actionFailedMessage: Int,
    /**
     * ZC-705: false when there is no wallet on the phone (the protected storage could not be set up): the screen then
     * shows neither "Do not uninstall ZCHAT or clear its data." nor the "Back up recovery phrase" button.
     */
    val hasWallet: Boolean = true,
)

/**
 * R5-3: the text and buttons of [WalletStartupFailedView], as a value. Kept out of the composable so that what
 * each start-up failure tells the user — above all "the key is gone" ([WalletStartupRepair.Offer
 * .ChatCopyRestoreFailed]), which no test could reach before — can be checked without a device.
 */
fun walletStartupFailedContent(offer: WalletStartupRepair.Offer): WalletStartupFailedContent =
    when (offer) {
        WalletStartupRepair.Offer.SetAsideAndRescan ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_mismatch_title,
                message = R.string.wallet_startup_mismatch_message,
                buttons =
                    listOf(
                        WalletStartupButton(R.string.wallet_startup_mismatch_button, WalletStartupAction.SET_ASIDE_AND_RESCAN)
                    ),
                actionFailedMessage = R.string.wallet_startup_mismatch_failed,
            )
        WalletStartupRepair.Offer.SeedStoreUnreadable ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_seed_unreadable_title,
                message = R.string.wallet_startup_seed_unreadable_message,
                buttons = listOf(WalletStartupButton(R.string.wallet_startup_failed_try_again, WalletStartupAction.TRY_AGAIN)),
                actionFailedMessage = R.string.wallet_startup_mismatch_failed,
            )
        WalletStartupRepair.Offer.StorageNotSetUp ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_storage_not_set_up_title,
                message = R.string.wallet_startup_storage_not_set_up_message,
                buttons = listOf(WalletStartupButton(R.string.wallet_startup_failed_try_again, WalletStartupAction.TRY_AGAIN)),
                actionFailedMessage = R.string.wallet_startup_mismatch_failed,
                hasWallet = false,
            )
        WalletStartupRepair.Offer.ChatStoresUnavailable ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_chats_unavailable_title,
                message = R.string.wallet_startup_chats_unavailable_message,
                buttons = listOf(WalletStartupButton(R.string.wallet_startup_failed_try_again, WalletStartupAction.TRY_AGAIN)),
                actionFailedMessage = R.string.wallet_startup_mismatch_failed,
            )
        WalletStartupRepair.Offer.RestoreChatCopy,
        WalletStartupRepair.Offer.RestoreChatCopyAfterUse ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_chat_copy_title,
                // C6 (R6-4 review findings 14, 15): after the user chatted on during a paused restore, the offer says
                // that restoring sets the current chats aside as a copy that is offered back.
                message =
                    if (offer == WalletStartupRepair.Offer.RestoreChatCopyAfterUse) {
                        R.string.wallet_startup_chat_copy_reoffer_after_use_message
                    } else {
                        R.string.wallet_startup_chat_copy_message
                    },
                buttons =
                    listOf(
                        WalletStartupButton(R.string.wallet_startup_chat_copy_restore, WalletStartupAction.RESTORE_CHAT_COPY),
                        WalletStartupButton(
                            R.string.wallet_startup_chat_copy_continue,
                            WalletStartupAction.CONTINUE_WITHOUT_CHAT_COPY
                        ),
                    ),
                actionFailedMessage = R.string.wallet_startup_chat_copy_failed,
            )
        WalletStartupRepair.Offer.ChatCopyRestoreFailed ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_chat_copy_gone_title,
                message = R.string.wallet_startup_chat_copy_gone_message,
                buttons =
                    listOf(
                        WalletStartupButton(
                            R.string.wallet_startup_chat_copy_gone_button,
                            WalletStartupAction.CHAT_COPY_FAILURE_READ
                        )
                    ),
                actionFailedMessage = R.string.wallet_startup_chat_copy_failed,
            )
        WalletStartupRepair.Offer.ChatCopyRestorePaused ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_chat_copy_paused_title,
                message = R.string.wallet_startup_chat_copy_paused_message,
                buttons =
                    listOf(
                        WalletStartupButton(
                            R.string.wallet_startup_chat_copy_paused_button,
                            WalletStartupAction.CHAT_COPY_PAUSE_READ
                        )
                    ),
                actionFailedMessage = R.string.wallet_startup_chat_copy_failed,
            )
        WalletStartupRepair.Offer.ChatCopyRestoreParked ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_chat_copy_parked_title,
                message = R.string.wallet_startup_chat_copy_parked_message,
                buttons =
                    listOf(
                        WalletStartupButton(
                            R.string.wallet_startup_chat_copy_parked_button,
                            WalletStartupAction.CHAT_COPY_PARK_READ
                        )
                    ),
                actionFailedMessage = R.string.wallet_startup_chat_copy_failed,
            )
        WalletStartupRepair.Offer.SafetyScreen ->
            WalletStartupFailedContent(
                title = R.string.wallet_startup_failed_title,
                message = R.string.wallet_startup_failed_message,
                buttons = listOf(WalletStartupButton(R.string.wallet_startup_failed_try_again, WalletStartupAction.TRY_AGAIN)),
                actionFailedMessage = R.string.wallet_startup_mismatch_failed,
            )
    }

@Suppress("LongParameterList", "LongMethod")
@Composable
fun WalletStartupFailedView(
    errorName: String?,
    onBackUpRecoveryPhrase: () -> Unit,
    offer: WalletStartupRepair.Offer = WalletStartupRepair.Offer.SafetyScreen,
    isWorking: Boolean = false,
    setAsideFailed: Boolean = false,
    onTryAgain: () -> Unit = {},
    onRestoreChatCopy: () -> Unit = {},
    onContinueWithoutChatCopy: () -> Unit = {},
    onChatCopyFailureRead: () -> Unit = {},
    onChatCopyPauseRead: () -> Unit = {},
    onChatCopyParkRead: () -> Unit = {},
    onSetAsideAndRescan: () -> Unit = {},
) {
    val content = walletStartupFailedContent(offer)
    val title = content.title
    val message = content.message
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(zashiVerticalGradient(ZashiColors.Utility.ErrorRed.utilityError100))
    ) {
        Scaffold(containerColor = Color.Transparent) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .scaffoldPadding(paddingValues)
                        .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(48.dp))
                Image(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    painter = painterResource(R.drawable.ic_error_warning),
                    contentDescription = null
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    text = stringResource(title),
                    style = ZashiTypography.header6,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary
                )
                Spacer(Modifier.height(16.dp))
                // ZC-705: with no wallet on the phone there is nothing to keep by not uninstalling.
                if (content.hasWallet) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.wallet_startup_failed_do_not_uninstall),
                        style = ZashiTypography.textMd,
                        fontWeight = FontWeight.SemiBold,
                        color = ZashiColors.Text.textPrimary
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    text = stringResource(message),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textPrimary
                )
                if (errorName != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        text = stringResource(R.string.wallet_startup_failed_details, errorName),
                        style = ZashiTypography.textXs,
                        color = ZashiColors.Text.textTertiary
                    )
                }
                if (setAsideFailed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        text = stringResource(content.actionFailedMessage),
                        style = ZashiTypography.textSm,
                        fontWeight = FontWeight.SemiBold,
                        color = ZashiColors.Text.textPrimary
                    )
                }
                Spacer(Modifier.height(32.dp))
                // ZC-705: with no wallet there is no recovery phrase to back up.
                if (content.hasWallet) {
                    ZashiButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.wallet_startup_failed_backup_button),
                        onClick = onBackUpRecoveryPhrase,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                content.buttons.forEachIndexed { index, button ->
                    if (index > 0) Spacer(Modifier.height(12.dp))
                    ZashiButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(button.text),
                        enabled = !isWorking,
                        isLoading = isWorking && index == 0,
                        colors =
                            if (index == 0) {
                                ZashiButtonDefaults.secondaryColors()
                            } else {
                                ZashiButtonDefaults.tertiaryColors()
                            },
                        onClick = {
                            when (button.action) {
                                WalletStartupAction.TRY_AGAIN -> onTryAgain()
                                WalletStartupAction.SET_ASIDE_AND_RESCAN -> onSetAsideAndRescan()
                                WalletStartupAction.RESTORE_CHAT_COPY -> onRestoreChatCopy()
                                WalletStartupAction.CONTINUE_WITHOUT_CHAT_COPY -> onContinueWithoutChatCopy()
                                WalletStartupAction.CHAT_COPY_FAILURE_READ -> onChatCopyFailureRead()
                                WalletStartupAction.CHAT_COPY_PAUSE_READ -> onChatCopyPauseRead()
                                WalletStartupAction.CHAT_COPY_PARK_READ -> onChatCopyParkRead()
                            }
                        },
                    )
                }
            }
        }
    }
}

@PreviewScreens
@Composable
private fun WalletStartupFailedPreview() =
    ZcashTheme {
        WalletStartupFailedView(
            errorName = "MigrationFailed",
            onBackUpRecoveryPhrase = {},
        )
    }

@PreviewScreens
@Composable
private fun WalletStartupSeedMismatchPreview() =
    ZcashTheme {
        WalletStartupFailedView(
            errorName = "SeedNotRelevant",
            onBackUpRecoveryPhrase = {},
            offer = WalletStartupRepair.Offer.SetAsideAndRescan,
        )
    }

@PreviewScreens
@Composable
private fun WalletStartupChatCopyKeptPreview() =
    ZcashTheme {
        WalletStartupFailedView(
            errorName = null,
            onBackUpRecoveryPhrase = {},
            offer = WalletStartupRepair.Offer.RestoreChatCopy,
        )
    }

@PreviewScreens
@Composable
private fun WalletStartupChatCopyGonePreview() =
    ZcashTheme {
        WalletStartupFailedView(
            errorName = null,
            onBackUpRecoveryPhrase = {},
            offer = WalletStartupRepair.Offer.ChatCopyRestoreFailed,
        )
    }

@PreviewScreens
@Composable
private fun WalletStartupChatCopyPausedPreview() =
    ZcashTheme {
        WalletStartupFailedView(
            errorName = null,
            onBackUpRecoveryPhrase = {},
            offer = WalletStartupRepair.Offer.ChatCopyRestorePaused,
        )
    }

@PreviewScreens
@Composable
private fun WalletStartupChatCopyParkedPreview() =
    ZcashTheme {
        WalletStartupFailedView(
            errorName = null,
            onBackUpRecoveryPhrase = {},
            offer = WalletStartupRepair.Offer.ChatCopyRestoreParked,
        )
    }

@PreviewScreens
@Composable
private fun WalletStartupSeedUnreadablePreview() =
    ZcashTheme {
        WalletStartupFailedView(
            errorName = "EncryptedPreferencesUnavailableException",
            onBackUpRecoveryPhrase = {},
            offer = WalletStartupRepair.Offer.SeedStoreUnreadable,
        )
    }
