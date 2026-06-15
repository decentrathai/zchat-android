package co.electriccoin.zcash.ui.screen.restore.date

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreBDEstimationArgs
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.toKotlinInstant
import java.time.YearMonth
import java.time.ZoneId

class RestoreBDDateVM(
    private val args: RestoreBDDateArgs,
    private val navigationRouter: NavigationRouter,
    private val application: Application,
) : ViewModel() {
    @Suppress("MagicNumber")
    private val selection = MutableStateFlow<YearMonth>(YearMonth.of(2018, 10))

    private val isEstimating = MutableStateFlow(false)

    private val estimateError = MutableStateFlow<StringResource?>(null)

    val state: StateFlow<RestoreBDDateState?> =
        combine(selection, isEstimating, estimateError) { selection, estimating, error ->
            createState(selection, estimating, error)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = createState(selection.value, isEstimating.value, estimateError.value)
        )

    private fun createState(
        selection: YearMonth,
        estimating: Boolean,
        error: StringResource?
    ) = RestoreBDDateState(
        title = stringRes(R.string.restore_title),
        subtitle = stringRes(R.string.restore_bd_date_subtitle),
        message = stringRes(R.string.restore_bd_date_message),
        note = stringRes(R.string.restore_bd_date_note),
        next =
            ButtonState(
                stringRes(R.string.restore_bd_height_btn),
                onClick = ::onEstimateClick,
                isEnabled = !estimating,
                isLoading = estimating
            ),
        dialogButton =
            IconButtonState(
                icon = R.drawable.ic_help,
                onClick = ::onInfoButtonClick,
            ),
        onBack = ::onBack,
        onYearMonthChange = ::onYearMonthChange,
        selection = selection,
        error = error,
    )

    @Suppress("TooGenericExceptionCaught")
    private fun onEstimateClick() {
        if (isEstimating.value) return
        viewModelScope.launch {
            isEstimating.update { true }
            estimateError.update { null }
            try {
                val instant =
                    selection.value
                        .atDay(1)
                        .atStartOfDay()
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toKotlinInstant()
                val bday =
                    SdkSynchronizer.estimateBirthdayHeight(
                        context = application,
                        date = instant,
                        network = VersionInfo.NETWORK
                    )
                navigationRouter.forward(RestoreBDEstimationArgs(seed = args.seed, blockHeight = bday.value))
            } catch (e: Exception) {
                Twig.error(e) { "Failed to estimate birthday height" }
                estimateError.update { stringRes(R.string.restore_bd_estimation_error_failed) }
            } finally {
                isEstimating.update { false }
            }
        }
    }

    private fun onBack() = navigationRouter.back()

    private fun onInfoButtonClick() = navigationRouter.forward(SeedInfo)

    private fun onYearMonthChange(yearMonth: YearMonth) {
        estimateError.update { null }
        selection.update { yearMonth }
    }
}
