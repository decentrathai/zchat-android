package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
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
 * G3 8c (fix-all 2.13.0, train T4b; groups SPEC §3.3, §3.5 J5 with review H) — New Chat calls an address of this wallet
 * "Invalid Zcash address".
 *
 * ZchatComposeVM.updateState refuses any address isSelfAddress knows (every account's addresses, [13]) and our default
 * address, and that is right: Send stays off. But the screen then shows `new_chat_invalid_address` for an address that
 * is valid and simply belongs to this wallet (on the test rig: the HONOR holds the Seeker's viewing key as an account,
 * so the Seeker's address is the HONOR's own).
 *
 * Wanted: the Ready state carries `isOwnAddress` (isSelfAddress or the default address), `isValidAddress` keeps its
 * value (false, Send stays off), and ZchatComposeView's one supporting-text site shows `new_chat_own_address` for it.
 * The new field is read by reflection, so this file compiles before the fix. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnAddressIsNotCalledInvalidTest {
    private val vms = mutableListOf<ZchatComposeVM>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        vms.forEach(RelayKeyBinding::close)
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun isOwnAddress(state: ZchatComposeState.Ready): Boolean? =
        runCatching {
            state.javaClass
                .getDeclaredField("isOwnAddress")
                .apply { isAccessible = true }
                .getBoolean(state)
        }.getOrNull()

    @Test
    fun an_address_of_another_account_of_this_wallet_is_own_not_invalid_and_send_stays_off() {
        val otherAccount = SyntheticUnifiedAddress.mainnet(8_301)
        val store = RelayKeyStore().apply { selfAddresses += otherAccount }
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(otherAccount)
        vm.ready().onMessageChange("synthetic hello")

        val state = vm.ready()
        assertFalse(state.isValidAddress, "Send must stay off for an address of this wallet")
        assertEquals(true, isOwnAddress(state), "the state must say the address belongs to this wallet (isOwnAddress)")
    }

    @Test
    fun our_default_address_is_own_too() {
        val store = RelayKeyStore()
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(RelayKeyBinding.SELF)

        val state = vm.ready()
        assertFalse(state.isValidAddress, "Send must stay off for our own address")
        assertEquals(true, isOwnAddress(state), "our default address must count as our own (isOwnAddress)")
    }

    @Test
    fun control_another_persons_address_is_not_own() {
        val store = RelayKeyStore()
        val vm = composeVm(store).also { vms += it }

        vm.ready().onRecipientChange(SyntheticUnifiedAddress.mainnet(8_302))

        val state = vm.ready()
        assertTrue(state.isValidAddress, "fixture: a contact's valid address")
        assertEquals(false, isOwnAddress(state), "a contact's address is not ours")
    }

    @Test
    fun the_one_supporting_text_site_shows_the_own_address_text_before_the_invalid_one() {
        val src = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatComposeView.kt").readText()
        assertEquals(
            1,
            Regex("""R\.string\.new_chat_invalid_address""").findAll(src).count(),
            "fixture: one supporting-text site shows new_chat_invalid_address",
        )
        val site = src.indexOf("supportingText = if (state.codeNotice != null)")
        assertTrue(site >= 0, "fixture: the recipient field's supporting text")
        val block = src.substring(site, src.indexOf("R.string.new_chat_invalid_address", site))
        assertTrue(
            Regex("""else if \(state\.isOwnAddress\)\s*\{\s*\{\s*Text\(stringResource\(R\.string\.new_chat_own_address\)""")
                .containsMatchIn(block),
            "the recipient field must show new_chat_own_address for an address of this wallet, before the invalid text",
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
