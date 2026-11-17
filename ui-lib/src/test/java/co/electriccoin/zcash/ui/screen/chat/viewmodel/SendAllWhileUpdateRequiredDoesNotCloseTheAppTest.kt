package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeGuard
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageAmount
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.ready
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-781 (review of 12 Oct, finding 1) — while the app is in its "update required" state, New Chat with a valid
 * address in a paid mode and a tap on Send All closes the app.
 *
 * Since `e8f646d98` (ZC-538) `CreateChunkedMessageProposalUseCase.priceSendAll` throws the update refusal ahead of its
 * own try, and `ZchatComposeVM.refreshSendAll` calls it inside `viewModelScope.launch` with no catch. The view model
 * scope has no exception handler, so on a phone the refusal reaches the thread's uncaught-exception handler and the
 * app closes. Nothing is priced, proposed or paid on this path either way.
 *
 * Wanted: the refusal is caught there, Send All is shown as unavailable (no amount, Send stays off) and the note under
 * the amount is the update sentence, shown in the phone's language. A real ZchatComposeVM on the JVM with the main
 * dispatcher unconfined (the RetestGap07 idiom), so the pricing coroutine runs inside the tap. The uncaught-exception
 * handler of the test thread stands in for the phone's. Every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendAllWhileUpdateRequiredDoesNotCloseTheAppTest {
    private val vms = mutableListOf<ZchatComposeVM>()
    private val uncaught = mutableListOf<Throwable>()
    private var handlerBefore: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        NetworkUpgradeTestApi.resetIfPresent()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        handlerBefore = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().setUncaughtExceptionHandler { _, error -> uncaught += error }
    }

    @After
    fun tearDown() {
        Thread.currentThread().uncaughtExceptionHandler = handlerBefore
        vms.forEach(RelayKeyBinding::close)
        vms.clear()
        Dispatchers.resetMain()
        NetworkUpgradeTestApi.resetIfPresent()
    }

    private fun tapSendAllOnNewChat(): co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState.Ready {
        val vm = composeVm(RelayKeyStore()).also { vms += it }
        vm.ready().onRecipientChange(SyntheticUnifiedAddress.mainnet(8_781))
        vm.ready().onMessageChange("synthetic hello")
        assertTrue(vm.ready().isValidAddress, "fixture: a valid address of another person")
        vm.ready().onAmountSelect(MessageAmount.SEND_ALL)
        return vm.ready()
    }

    @Test
    fun send_all_in_the_update_required_state_closes_nothing_and_shows_the_update_sentence() {
        NetworkUpgradeGuard.onSubmitResult(SubmitResult.Failure(emptyList(), 0, "incorrect consensus branch id"))
        assertTrue(NetworkUpgradeGuard.refusal() != null, "fixture: the app is in its update-required state")

        val state = tapSendAllOnNewChat()

        assertEquals(
            emptyList(),
            uncaught.map { it.javaClass.simpleName },
            "the update refusal left the Send All pricing uncaught: on a phone this closes the app",
        )
        assertTrue(state.isZeroAmount, "Send All has no amount, so Send stays off")
        assertEquals("", state.sendAllAmountDisplay, "no Send All figure is shown")
        assertEquals(
            NetworkUpgradeTestApi.MESSAGE,
            state.sendAllNote,
            "the note under the amount says why Send All is unavailable: the update sentence",
        )
        assertEquals(
            R.string.update_required_refusal,
            ChatSendCopy.localizedResource(state.sendAllNote),
            "the note has a resource, so a Spanish phone shows it in Spanish",
        )
    }

    @Test
    fun control_without_the_update_state_send_all_is_priced_as_before() {
        val state = tapSendAllOnNewChat()

        assertEquals(emptyList(), uncaught.map { it.javaClass.simpleName })
        assertFalse(
            state.sendAllNote == NetworkUpgradeTestApi.MESSAGE,
            "the update sentence shows only in the update-required state",
        )
    }

    @Test
    fun the_new_chat_screen_shows_the_unavailable_note_through_its_resource() {
        val src =
            SourceScan.stripComments(
                File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatComposeView.kt").readText(),
            )
        val site = src.indexOf("if (state.isZeroAmount) {")
        assertTrue(site >= 0, "fixture: the branch that shows why Send All has no amount")
        val block = src.substring(site, src.indexOf("} else if (state.sendAllNote.isNotEmpty())", site))
        assertTrue(
            Regex("""ChatSendCopy\s*\.localizedResource\(state\.sendAllNote\)\s*\?\.let\s*\{\s*stringResource\(it\)\s*\}""")
                .containsMatchIn(block),
            "the Send All note must be looked up as a resource before it is shown as it is",
        )
    }

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "repo root not found above the test working dir" }
    }
}
