package co.electriccoin.zcash.ui.screen.error

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException
import co.electriccoin.zcash.ui.common.model.ScannedMultiPaymentException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.moneyRefusalMessage
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.viewmodel.STACKTRACE_LIMIT
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class ErrorVM(
    args: ErrorArgs,
    private val navigateToErrorBottom: NavigateToErrorUseCase,
    private val navigationRouter: NavigationRouter,
    private val sendEmailUseCase: SendEmailUseCase,
    private val optInExchangeRateAndTor: OptInExchangeRateAndTorUseCase
) : ViewModel() {
    val state: StateFlow<ErrorState> = MutableStateFlow(createState(args)).asStateFlow()

    override fun onCleared() {
        navigateToErrorBottom.clear()
        super.onCleared()
    }

    private fun onBack() = navigationRouter.back()

    private fun createState(args: ErrorArgs): ErrorState =
        when (args) {
            is ErrorArgs.SyncError -> createSyncErrorState(args)
            is ErrorArgs.ShieldingError -> createShieldingErrorState(args)
            is ErrorArgs.General -> createGeneralErrorState(args)
            is ErrorArgs.ShieldingGeneralError -> createGeneralShieldingErrorState(args)
            is ErrorArgs.SynchronizerTorInitError -> createSdkSynchronizerError()
        }

    private fun createSdkSynchronizerError(): ErrorState =
        ErrorState(
            title = stringRes(R.string.error_tor_title),
            message = stringRes(R.string.error_tor_message),
            positive =
                ButtonState(
                    text = stringRes(R.string.error_tor_negative),
                    onClick = ::onDisableTorClick
                ),
            negative =
                ButtonState(
                    text = stringRes(R.string.error_tor_positive),
                    onClick = { navigationRouter.back() }
                ),
            onBack = ::onBack,
        )

    private fun createSyncErrorState(args: ErrorArgs.SyncError) =
        ErrorState(
            title = stringRes(R.string.error_sync_title),
            message = stringRes(args.synchronizerError.getStackTrace(STACKTRACE_LIMIT).orEmpty()),
            positive =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_report),
                    onClick = { sendReportClick(args) }
                ),
            onBack = ::onBack,
        )

    private fun createShieldingErrorState(args: ErrorArgs.ShieldingError) =
        ErrorState(
            // R2-6 (a): a shielding that may be on the wire is "Sent — Not Confirmed", never "Unable to shield".
            title =
                if (args.error is SubmitResult.SentUnconfirmed) {
                    stringRes(R.string.send_confirmation_unconfirmed_title)
                } else {
                    stringRes(R.string.error_shielding_title)
                },
            message =
                when (args.error) {
                    is SubmitResult.GrpcFailure -> stringRes(R.string.error_shielding_message_grpc)
                    is SubmitResult.Failure ->
                        if (args.error.txIds.isEmpty() && args.error.description == NetworkUpgradeRequiredException.MESSAGE) {
                            stringRes(R.string.update_required_refusal)
                        } else {
                            stringRes(
                                R.string.error_shielding_message,
                                stringRes(
                                    buildString {
                                        appendLine("Error code: ${args.error.code}")
                                        appendLine(args.error.description ?: "Unknown error")
                                    }
                                )
                            )
                        }

                    is SubmitResult.Partial ->
                        stringRes(
                            R.string.error_shielding_message,
                            args.error.statuses.joinToString()
                        )

                    is SubmitResult.SentUnconfirmed -> stringRes(R.string.send_confirmation_unconfirmed_subtitle)

                    is SubmitResult.Success -> stringRes("")
                },
            positive =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_report),
                    onClick = { sendReportClick(args) }
                ),
            onBack = ::onBack,
        )

    private fun createGeneralErrorState(args: ErrorArgs.General): ErrorState {
        val multiPayment = args.exception as? ScannedMultiPaymentException
        return ErrorState(
            title =
                if (multiPayment != null) {
                    stringRes(R.string.scan_multi_payment_title)
                } else {
                    stringRes(R.string.error_general_title)
                },
            message =
                if (multiPayment != null) {
                    stringRes(R.string.scan_multi_payment_message, multiPayment.payments)
                } else {
                    stringRes(
                        R.string.error_general_message,
                        // A typed money/privacy refusal already carries a sentence written for the user;
                        // wrapping that in a Java stack trace wastes the only explanation they get. Anything
                        // else keeps the trace, which is what the Report button needs.
                        // Round 2 report 4 item 16: the refusal in the phone's language (ChatSendCopy), else as it is.
                        moneyRefusalMessage(args.exception)?.let { refusal ->
                            ChatSendCopy.localizedResource(refusal)?.let { stringRes(it) } ?: stringRes(refusal)
                        } ?: stringRes(args.exception.stackTraceToString().take(STACKTRACE_LIMIT))
                    )
                },
            positive =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_report),
                    onClick = { sendReportClick(args.exception) }
                ),
            onBack = ::onBack,
        )
    }

    private fun createGeneralShieldingErrorState(args: ErrorArgs.ShieldingGeneralError) =
        ErrorState(
            title = stringRes(R.string.error_shielding_title),
            message =
                if (moneyRefusalMessage(args.exception) == NetworkUpgradeRequiredException.MESSAGE) {
                    stringRes(R.string.update_required_refusal)
                } else {
                    stringRes(
                        R.string.error_shielding_message,
                        stringRes(args.exception.stackTraceToString().take(STACKTRACE_LIMIT))
                    )
                },
            positive =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_ok),
                    onClick = { navigationRouter.back() }
                ),
            negative =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_report),
                    onClick = { sendReportClick(args.exception) }
                ),
            onBack = ::onBack,
        )

    private fun sendReportClick(args: ErrorArgs.ShieldingError) =
        viewModelScope.launch {
            withContext(NonCancellable) {
                navigationRouter.back()
                when (args.error) {
                    is SubmitResult.Failure -> sendEmailUseCase(args.error)
                    is SubmitResult.GrpcFailure -> sendEmailUseCase(args.error)
                    is SubmitResult.Partial -> sendEmailUseCase(args.error)
                    is SubmitResult.SentUnconfirmed ->
                        sendEmailUseCase(
                            IllegalStateException("Shielding sent but not confirmed: ${args.error.description}")
                        )
                    is SubmitResult.Success -> {
                        // do nothing
                    }
                }
            }
        }

    private fun sendReportClick(args: ErrorArgs.SyncError) =
        viewModelScope.launch {
            withContext(NonCancellable) {
                navigationRouter.back()
                sendEmailUseCase(args.synchronizerError)
            }
        }

    private fun sendReportClick(exception: Exception) =
        viewModelScope.launch {
            withContext(NonCancellable) {
                navigationRouter.back()
                sendEmailUseCase(exception)
            }
        }

    private fun onDisableTorClick() = viewModelScope.launch { optInExchangeRateAndTor(false) }
}
