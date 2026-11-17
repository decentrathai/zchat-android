package co.electriccoin.zcash.ui.screen.error

import androidx.lifecycle.ViewModelStore
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.viewmodel.STACKTRACE_LIMIT
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ZC-538, ZC-771: the real shielding error state must not wrap an update refusal in retry copy. */
class ShieldingUpdateRefusalDoesNotSuggestRetryTest {
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() = NetworkUpgradeTestApi.resetIfPresent()

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        NetworkUpgradeTestApi.resetIfPresent()
    }

    private data class Fixture(
        val state: ErrorState,
        val navigation: Zc101RecordingNavigationRouter,
    )

    private fun fixture(args: ErrorArgs): Fixture {
        val navigation = Zc101RecordingNavigationRouter()
        val vm =
            ErrorVM(
                args = args,
                navigateToErrorBottom = NavigateToErrorUseCase(navigation),
                navigationRouter = navigation,
                // These state/navigation checks never invoke the email or Tor action dependencies.
                sendEmailUseCase = NetworkUpgradeTestApi.allocate(SendEmailUseCase::class.java) as SendEmailUseCase,
                optInExchangeRateAndTor =
                    NetworkUpgradeTestApi.allocate(OptInExchangeRateAndTorUseCase::class.java) as OptInExchangeRateAndTorUseCase,
            )
        val store = ViewModelStore()
        store.put("shielding-error", vm)
        stores += store
        return Fixture(vm.state.value, navigation)
    }

    private fun assertDirectUpdateResource(args: ErrorArgs) {
        val message = assertIs<StringResource.ByResource>(fixture(args).state.message)
        val updateResource = assertNotNull(ChatResourceText.stringId("update_required_refusal"), "update refusal resource is absent")
        assertEquals(updateResource, message.resource, "shielding update refusal still selects retry wrapper")
        assertTrue(message.args.isEmpty(), "direct update copy must have no nested retry-wrapper argument")
    }

    private fun updateException(): Exception {
        val type =
            try {
                Class.forName(NetworkUpgradeTestApi.MODEL + "NetworkUpgradeRequiredException")
            } catch (_: ClassNotFoundException) {
                null
            }
        return assertIs<Exception>(
            assertNotNull(type, "ZC-771: typed update refusal is absent").getDeclaredConstructor().newInstance()
        )
    }

    @Test
    fun direct_update_refusal_selects_the_refusal_resource_without_retry_wrapper() {
        assertDirectUpdateResource(ErrorArgs.ShieldingGeneralError(updateException()))
    }

    @Test
    fun wrapped_update_refusal_selects_the_refusal_resource_without_retry_wrapper() {
        assertDirectUpdateResource(
            ErrorArgs.ShieldingGeneralError(
                IllegalStateException("synthetic outer shielding failure", updateException())
            )
        )
    }

    @Test
    fun empty_id_submit_backstop_update_refusal_selects_the_refusal_resource_without_retry_wrapper() {
        assertDirectUpdateResource(
            ErrorArgs.ShieldingError(SubmitResult.Failure(emptyList(), 0, NetworkUpgradeTestApi.MESSAGE))
        )
    }

    @Test
    fun control_ordinary_errors_keep_the_generic_shielding_wrapper_and_stacktrace_fallback() {
        val errors =
            listOf(
                IllegalStateException("synthetic ordinary shielding failure"),
                IllegalStateException("synthetic ordinary wrapper", IOException("synthetic ordinary cause")),
            )
        for (error in errors) {
            val message = assertIs<StringResource.ByResource>(fixture(ErrorArgs.ShieldingGeneralError(error)).state.message)
            assertEquals(R.string.error_shielding_message, message.resource)
            assertEquals(
                listOf<Any>(StringResource.ByString(error.stackTraceToString().take(STACKTRACE_LIMIT))),
                message.args,
            )
        }
    }

    @Test
    fun control_stored_update_sentence_and_ordinary_empty_id_failures_keep_the_existing_wrapper() {
        val failures =
            listOf(
                SubmitResult.Failure(listOf("synthetic-zc771-stored"), 0, NetworkUpgradeTestApi.MESSAGE),
                SubmitResult.Failure(emptyList(), -7, "synthetic ordinary submit refusal"),
            )
        for (failure in failures) {
            val message = assertIs<StringResource.ByResource>(fixture(ErrorArgs.ShieldingError(failure)).state.message)
            assertEquals(R.string.error_shielding_message, message.resource)
            assertEquals(
                listOf<Any>(StringResource.ByString("Error code: ${failure.code}\n${failure.description}\n")),
                message.args,
            )
        }
    }

    @Test
    fun control_sent_unconfirmed_keeps_the_existing_unconfirmed_title_and_copy() {
        val result =
            SubmitResult.SentUnconfirmed(listOf("synthetic-zc771-stored"), NetworkUpgradeTestApi.MESSAGE)
        val state = fixture(ErrorArgs.ShieldingError(result)).state
        assertEquals(StringResource.ByResource(R.string.send_confirmation_unconfirmed_title, emptyList()), state.title)
        assertEquals(StringResource.ByResource(R.string.send_confirmation_unconfirmed_subtitle, emptyList()), state.message)
    }

    @Test
    fun control_update_and_ordinary_errors_keep_the_existing_buttons_and_back_actions() {
        val cases =
            listOf(
                ErrorArgs.ShieldingGeneralError(updateException()),
                ErrorArgs.ShieldingGeneralError(
                    IllegalStateException("synthetic outer shielding failure", updateException())
                ),
                ErrorArgs.ShieldingGeneralError(IllegalStateException("synthetic ordinary shielding failure")),
                ErrorArgs.ShieldingError(SubmitResult.Failure(emptyList(), 0, NetworkUpgradeTestApi.MESSAGE)),
                ErrorArgs.ShieldingError(
                    SubmitResult.Failure(listOf("synthetic-zc771-stored"), 0, NetworkUpgradeTestApi.MESSAGE)
                ),
                ErrorArgs.ShieldingError(SubmitResult.Failure(emptyList(), -7, "synthetic ordinary submit refusal")),
            )
        for (args in cases) {
            val (state, navigation) = fixture(args)
            assertEquals(StringResource.ByResource(R.string.error_shielding_title, emptyList()), state.title)
            assertEquals(
                StringResource.ByResource(co.electriccoin.zcash.ui.design.R.string.general_ok, emptyList()),
                state.positive.text,
            )
            assertEquals(
                StringResource.ByResource(co.electriccoin.zcash.ui.design.R.string.general_report, emptyList()),
                state.negative.text,
            )
            state.positive.onClick()
            state.onBack()
            assertEquals(listOf("back", "back"), navigation.commands)
        }
    }
}
