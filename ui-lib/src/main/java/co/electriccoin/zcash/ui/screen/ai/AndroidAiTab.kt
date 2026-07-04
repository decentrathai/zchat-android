package co.electriccoin.zcash.ui.screen.ai

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321BuildUriUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase
import co.electriccoin.zcash.ui.screen.chat.ChatList
import co.electriccoin.zcash.ui.screen.more.MoreArgs
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.wallettab.WalletTab
import org.koin.compose.koinInject

@Composable
fun AndroidAiTab() {
    val context = LocalContext.current
    val prefs = remember { AiPreferences(context.applicationContext) }
    val imageStore = remember { AiImageStore(context.applicationContext) }
    val getDefaultUA: GetDefaultUnifiedAddressUseCase = koinInject()
    // The pubkey we send to the AI backend is sha256(default UA). The default UA is
    // deterministic per seed, so the hash is stable across reinstalls and lets the
    // backend rebind to the same AiAccount instead of minting a fresh $0.20 trial.
    val walletPubkeyResolver: suspend () -> String? = remember { { WalletPubkey.deriveOrNull(getDefaultUA) } }
    val vm: AiTabVM = viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AiTabVM(prefs = prefs, imageStore = imageStore, walletPubkeyResolver = walletPubkeyResolver) as T
        }
    })
    val state by vm.state.collectAsState()

    // Refresh the balance whenever the AI tab resumes (e.g. returning from the Send/top-up flow) so a
    // completed top-up self-dismisses the stale "Out of credit" error once the new balance lands.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshBalanceNow()
        }
    }

    // Top-up sheet state
    var topupData by remember { mutableStateOf<TopupAddressResult.Success?>(null) }
    // Top-up HISTORY sheet: visibility lives here; the list itself (loading/error/data) in the VM.
    var showTopupHistory by remember { mutableStateOf(false) }
    val topupHistory by vm.topupHistory.collectAsState()

    val zip321Build: Zip321BuildUriUseCase = koinInject()
    val zip321Parse: Zip321ParseUriValidationUseCase = koinInject()
    val prefillSend: PrefillSendUseCase = koinInject()
    val navigationRouter: NavigationRouter = koinInject()
    val shareScope = rememberCoroutineScope()

    AiTabView(
        state = state,
        onSelectModel = vm::selectModel,
        onSend = vm::send,
        onGenerateImage = { prompt -> vm.generateImage(prompt) },
        onSelectMode = vm::setMode,
        onTopupClick = {
            vm.loadTopupAddress { r ->
                when (r) {
                    is TopupAddressResult.Success -> topupData = r
                    is TopupAddressResult.Failure -> Toast.makeText(
                        context,
                        "Top-up not configured yet: ${r.error}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        },
        onTopupHistoryClick = {
            showTopupHistory = true
            vm.loadTopupHistory()
        },
        onDismissError = vm::clearError,
        onClearChat = vm::clearCurrentChat,
        onNewChat = vm::newChat,
        onShowHistory = vm::setShowHistory,
        onOpenConversation = vm::openConversation,
        onDeleteConversation = vm::deleteConversation,
        onDeleteImage = vm::deleteImage,
        onClearAllImages = vm::clearAllImages,
        onRenameConversation = vm::renameConversation,
        onSetRetention = vm::setRetention,
        onRetry = vm::retryFailed,
        onRegenerate = vm::regenerate,
        onStop = vm::stopGeneration,
        onRefreshBalance = vm::refreshBalanceNow,
        onRefreshModels = vm::refreshModels,
        loadImageBitmap = vm::loadImageBitmap,
        onSendViaZchat = { bmp ->
            // Cache the bitmap off-main, then arm it as a single-image share and open the SharePicker.
            shareScope.launch {
                val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cacheAiImageForZchatSend(context.applicationContext, bmp)
                }
                if (file == null) {
                    Toast.makeText(context, "Couldn't prepare the image to send.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore.setPending(
                    co.electriccoin.zcash.ui.screen.chat.share.PendingShareStore.PendingShare.Images(listOf(file)),
                )
                navigationRouter.forward(co.electriccoin.zcash.ui.screen.chat.SharePicker)
            }
        },
        onChatsTab = { navigationRouter.replace(ChatList) },
        onWalletTab = { navigationRouter.replace(WalletTab) },
        onMoreTab = { navigationRouter.forward(MoreArgs) },
    )

    topupData?.let { t ->
        AiTopupSheet(
            address = t.address,
            memo = t.memo,
            tiers = t.tiers,
            zecUsdPrice = t.zecUsdPrice,
            buildZip321Uri = { addr, amount, memoStr ->
                zip321Build(address = addr, amount = amount, memo = memoStr)
            },
            onPayInWallet = { uri ->
                // Parse the URI we just built — gives us a PaymentRequest the prefill
                // bus understands — then jump to Send with the form pre-filled.
                val parsed = zip321Parse(uri)
                if (parsed is Zip321ParseUriValidationUseCase.Zip321ParseUriValidation.Valid) {
                    prefillSend.requestFromZip321(parsed.payment)
                    navigationRouter.forward(Send())
                    topupData = null
                } else {
                    Toast.makeText(context, "Could not start payment — copy address+memo instead.", Toast.LENGTH_LONG).show()
                }
            },
            onShowHistory = {
                showTopupHistory = true
                vm.loadTopupHistory()
            },
            onDismiss = { topupData = null },
        )
    }

    // Rendered after (= on top of) the top-up sheet, so History opened from there layers correctly
    // and Back/dismiss returns to the top-up sheet.
    if (showTopupHistory) {
        AiTopupHistorySheet(
            state = topupHistory,
            onRetry = vm::loadTopupHistory,
            onDismiss = { showTopupHistory = false },
        )
    }
}
