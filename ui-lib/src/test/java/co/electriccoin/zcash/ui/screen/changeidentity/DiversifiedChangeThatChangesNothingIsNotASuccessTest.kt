package co.electriccoin.zcash.ui.screen.changeidentity

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.datasource.OwnChatIdentityStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * ZC-350 (T7b triage 13 Oct, SMALL work order; keys and identity): Change Identity in the Diversified mode says
 * "Success!" and nothing changes.
 *
 * `ChangeIdentityVM.performDiversifiedChange` asks the wallet for its next shielded address and then reads the chat
 * identity through `GetDefaultUnifiedAddressUseCase`. By design (P2g) that use case returns the pinned chat identity,
 * which never moves, so the "new" identity is the old address.
 *
 * Review 4 of train T7a1 (14 Oct, finding 1, Important; orchestrator's decision in the register): the first fix still
 * asked the wallet for its next shielded address and then said "Nothing was changed; try again", which is false twice:
 * the wallet had moved to its next address, and a retry can never succeed while the identity is pinned. Wanted: with a
 * pinned chat identity the screen decides BEFORE it asks the wallet, so the wallet's address index stays where it was,
 * and it shows the true sentence `identity_error_diversified_pinned` (EN "Diversified addresses do not change your chat
 * identity in this version.", with a Spanish line); no success dialog, no identity made. The option stays. A wallet
 * whose identity is not pinned and whose address does move still says Success (control).
 *
 * The real ChangeIdentityVM, the real GetDefaultUnifiedAddressUseCase over an in-memory identity store, and the real
 * IdentityManagerImpl over in-memory preferences. The new key and its R field are reached by name, so this file
 * compiles before they exist. Every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiversifiedChangeThatChangesNothingIsNotASuccessTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a Diversified change with a pinned chat identity asks the wallet nothing and says why nothing changes`() =
        runBlocking {
            val h = Harness(pinned = true, account = syntheticZashiAccount())
            val vm = h.vm()
            // ZC-1026: the screen reads its address off the main thread; wait for it rather than read it at once.
            withTimeoutOrNull(WAIT_MS) { while (vm.state.value.currentAddress != OLD) delay(POLL_MS) }
            assertEquals("fixture: the screen shows the identity", OLD, vm.state.value.currentAddress)
            awaitDefaultIdentity(h)
            val identitiesBefore = h.identities.getAllIdentities().map { it.id }
            val activeBefore = h.identities.getActiveIdentity()?.id

            confirm(vm)

            val shown = vm.state.value
            assertFalse(
                "the wallet was moved to its next shielded address though a pinned chat identity can never change",
                h.requested,
            )
            assertFalse("the screen says Success! though the chat identity did not change", shown.showSuccessDialog)
            assertEquals(
                "a Diversified change with a pinned identity does not say that this version never changes the identity",
                stringRes(pinnedSentence()),
                shown.errorMessage,
            )
            assertEquals("an identity was made for the unchanged address", identitiesBefore, h.identities.getAllIdentities().map { it.id })
            assertEquals("another identity was made the active one", activeBefore, h.identities.getActiveIdentity()?.id)
        }

    @Test
    fun `the sentence is true in English and Spanish and offers no retry`() {
        val en = strings("values")[PINNED_KEY]
        val es = strings("values-es")[PINNED_KEY]
        assertEquals("values/destroy_identity_strings.xml $PINNED_KEY", PINNED_EN, en)
        assertTrue("values-es/destroy_identity_strings.xml has no $PINNED_KEY", es != null && es.isNotBlank())
        assertTrue("the Spanish line is the English one", es != en)
        listOf("try again", "inténtalo", "intenta").forEach { retry ->
            assertFalse("the sentence offers a retry that can never work ($retry)", (en + " " + es).contains(retry, ignoreCase = true))
        }
    }

    @Test
    fun `control - a Diversified change of an identity that is not pinned and moves still says Success`() =
        runBlocking {
            val h = Harness(pinned = false, account = syntheticZashiAccount())
            val vm = h.vm()
            awaitDefaultIdentity(h)

            confirm(vm)

            val shown = vm.state.value
            assertTrue("fixture: the wallet was asked for its next address", h.requested)
            assertTrue("a change that moved the address does not say Success", shown.showSuccessDialog)
            assertNull(shown.errorMessage)
            assertEquals(NEW, shown.currentAddress)
            assertEquals(NEW, h.identities.getActiveIdentity()?.address)
        }

    /**
     * The screen sets up its default identity off the main thread, from the identity it reads then; a change confirmed
     * before that has finished races it. Every case waits for it first.
     */
    private suspend fun awaitDefaultIdentity(h: Harness) {
        val ready = withTimeoutOrNull(WAIT_MS) { while (h.identities.getAllIdentities().isEmpty()) delay(POLL_MS) } != null
        assertTrue("fixture: the default identity was never set up", ready)
    }

    private suspend fun confirm(vm: ChangeIdentityVM) {
        vm.state.value.onModeSelected(IdentityMode.DIVERSIFIED)
        vm.state.value.onConfirmClick()
        vm.state.value.onConfirmationDialogConfirm()
        val settled = withTimeoutOrNull(WAIT_MS) { while (vm.state.value.isProcessing) delay(POLL_MS) } != null
        assertTrue("the change never finished", settled)
    }

    private class Harness(
        private val pinned: Boolean,
        account: ZashiAccount,
    ) {
        @Volatile var requested = false

        val identities = IdentityManagerImpl(MemoryContext())

        private val accounts: AccountDataSource =
            object : AccountDataSource by Zc101FakeAccountDataSource(account) {
                override suspend fun requestNextShieldedAddress() {
                    requested = true
                }
            }

        /** P2g's pinned identity: [OLD] for ever. */
        private val pinnedStore =
            object : OwnChatIdentityStore {
                override fun pinnedIdentity(accountKey: String): String = OLD

                override fun pinIdentityIfAbsent(
                    accountKey: String,
                    address: String,
                ): String = OLD
            }

        /** The wallet, read fresh when nothing is pinned: [OLD], or [NEW] once it was asked for its next address. */
        private val wallet =
            object : SynchronizerProvider {
                override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
                override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

                override suspend fun getSynchronizer(): Synchronizer = sdk

                override fun resetSynchronizer() = Unit

                private val sdk =
                    Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, m, a ->
                        when (m.name) {
                            "getUnifiedAddress" -> if (requested) NEW else OLD
                            "hashCode" -> System.identityHashCode(p)
                            "equals" -> p === a?.get(0)
                            "toString" -> "MovingWallet"
                            else -> throw UnsupportedOperationException("unexpected Synchronizer call ${m.name}")
                        }
                    } as Synchronizer
            }

        fun vm(): ChangeIdentityVM =
            ChangeIdentityVM(
                navigationRouter = Zc101RecordingNavigationRouter(),
                getDefaultUnifiedAddress =
                    GetDefaultUnifiedAddressUseCase(
                        synchronizerProvider = if (pinned) unused() else wallet,
                        getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accounts),
                        identityStore = { if (pinned) pinnedStore else OwnChatIdentityStore.NotPinned },
                    ),
                zchatPreferences = noContacts(),
                resetZashiUseCase = bare(ResetZashiUseCase::class.java),
                identityManager = identities,
                accountDataSource = accounts,
            )
    }

    /** A context whose only use is IdentityManagerImpl's preferences file, kept in memory. */
    private class MemoryContext : ContextWrapper(null) {
        private val files = HashMap<String, SharedPreferences>()

        override fun getSharedPreferences(
            name: String,
            mode: Int,
        ): SharedPreferences = files.getOrPut(name) { MemorySharedPreferences() }

        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val OLD = "usyntheticzc350identityold0001"
        const val NEW = "usyntheticzc350identitynew0002"
        const val PINNED_KEY = "identity_error_diversified_pinned"
        const val PINNED_EN = "Diversified addresses do not change your chat identity in this version."
        const val WAIT_MS = 10_000L
        const val POLL_MS = 10L

        /** The new sentence's R field, by name, so this file compiles before it exists. */
        fun pinnedSentence(): Int =
            runCatching { R.string::class.java.getField(PINNED_KEY).getInt(null) }
                .getOrElse { throw AssertionError("a Diversified change with a pinned identity has no sentence: no R.string.$PINNED_KEY") }

        private val uiLibDir: File by lazy {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var found: File? = null
            while (dir != null && found == null) {
                found = listOf(dir, File(dir, "ui-lib")).firstOrNull { File(it, "src/main/res/ui/common").isDirectory }
                dir = dir.parentFile
            }
            found ?: throw AssertionError("fixture: ui-lib not found")
        }

        /** The identity screen's texts in [values] (`values` or `values-es`), comments removed. */
        fun strings(values: String): Map<String, String> {
            val file = File(uiLibDir, "src/main/res/ui/common/$values/destroy_identity_strings.xml")
            assertTrue("fixture: no ${file.path}", file.isFile)
            val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text)
                .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
        }

        /** The store as the screen reads it: no contacts, no chats. */
        fun noContacts(): ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, m, a ->
                when (m.name) {
                    "getAllContactAddresses", "getAllConversationPeerAddresses" -> emptySet<String>()
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a?.get(0)
                    "toString" -> "NoContactsPrefs"
                    else -> throw UnsupportedOperationException("unexpected preferences call ${m.name}")
                }
            } as ZchatPreferences

        inline fun <reified T> unused(): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, m, a ->
                when (m.name) {
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a?.get(0)
                    "toString" -> "Unused${T::class.java.simpleName}"
                    else -> throw UnsupportedOperationException("unexpected call ${m.name}")
                }
            } as T

        /** An instance with no constructor run: the Diversified path never touches it. */
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> bare(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, type) as T
        }
    }
}
