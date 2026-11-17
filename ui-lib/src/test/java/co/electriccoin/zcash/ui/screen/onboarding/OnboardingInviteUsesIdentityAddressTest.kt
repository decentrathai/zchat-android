package co.electriccoin.zcash.ui.screen.onboarding

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.WalletAddress
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ZchatContactCode
import co.electriccoin.zcash.ui.screen.onboarding.viewmodel.OnboardingGuideVM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Bridge 3 (transition plan section 2.3, copy deck NK3): the onboarding invite carries your chat identity.
 *
 * Onboarding hands out your first ZChat invite (Copy, Share, QR). It was built from
 * `account.unified.address`, the rotating Receive address, which is a different address on every visit
 * and never the chat identity every chat message carries (My Address, [GetDefaultUnifiedAddressUseCase]).
 * A friend who added you from that invite stored an address your messages never come from, so your first
 * reply opened a second, separate chat on their side. Invite Friend and My Address already use the chat
 * identity; onboarding must too, and must never fall back to the rotating address while the identity is
 * not known yet (the wallet is still being created).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingInviteUsesIdentityAddressTest {
    private val identity = "u1synthetic-chat-identity"
    private val rotating = "u1synthetic-rotating-receive"

    private val vms = mutableListOf<OnboardingGuideVM>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // The seed wait (deriveOurNostr) never completes here: no wallet is ever persisted.
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun onboarding_invite_carries_the_chat_identity_not_the_rotating_receive_address() =
        runBlocking {
            val vm = onboardingVm(wallet = IdentityWallet(identity))

            val address = withTimeout(TIMEOUT_MS) { vm.userAddress.first { it != null } }
            assertEquals(identity, address, "onboarding shows an address other than the chat identity")

            val code = withTimeout(TIMEOUT_MS) { vm.contactCode.first { it != null } }
            val parsed = assertNotNull(ZchatContactCode.parse(code), "onboarding invite does not parse: $code")
            assertEquals(identity, parsed.zcashAddress, "the onboarding invite carries another address")
        }

    @Test
    fun onboarding_invite_never_falls_back_to_the_rotating_address_while_the_identity_is_unknown() =
        runBlocking {
            // The wallet is still being created: the chat identity can't be read yet.
            val gate = CompletableDeferred<Unit>()
            val vm = onboardingVm(wallet = IdentityWallet(identity, gate))

            val seen = mutableListOf<String?>()
            val watcher = launch(Dispatchers.Default) { vm.userAddress.collect { seen += it } }
            val codes = mutableListOf<String?>()
            val codeWatcher = launch(Dispatchers.Default) { vm.contactCode.collect { codes += it } }
            delay(WAIT_MS)
            assertNull(vm.userAddress.value, "onboarding showed an address before the chat identity was known")
            assertEquals(emptyList(), seen.filterNotNull(), "onboarding showed the rotating address")
            assertEquals(emptyList(), codes.filterNotNull(), "onboarding offered an invite before the identity was known")

            gate.complete(Unit)
            val address = withTimeout(TIMEOUT_MS) { vm.userAddress.first { it != null } }
            assertEquals(identity, address)
            watcher.cancel()
            codeWatcher.cancel()
        }

    // ------------------------------------------------------------------ fixture

    private fun onboardingVm(wallet: IdentityWallet): OnboardingGuideVM {
        // The account's unified address is the rotating Receive address.
        val account =
            runBlocking {
                val base = syntheticZashiAccount()
                base.copy(unified = base.unified.copy(address = WalletAddress.Unified.new(rotating)))
            }
        val accounts = Zc101FakeAccountDataSource(account)
        val context = ContextWrapper(null)
        return OnboardingGuideVM(
            observeSelectedWalletAccount = ObserveSelectedWalletAccountUseCase(accounts),
            getDefaultUnifiedAddress =
                GetDefaultUnifiedAddressUseCase(
                    synchronizerProvider = wallet,
                    getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accounts),
                ),
            walletRepository = walletRepository(),
            copyToClipboard = CopyToClipboardUseCase(context),
            navigationRouter = unexpected(),
            persistableWalletProvider = persistableWalletProvider(),
            zchatPreferences = unexpected(),
            contactBook = unexpected<ContactBook>(),
            context = context,
        ).also { vms += it }
    }

    /** Only the creation-failed flag is read while the invite screen shows. */
    private fun walletRepository(): WalletRepository =
        proxy { name, _ ->
            when (name) {
                "getWalletCreationFailed" -> MutableStateFlow(false)
                else -> error("unexpected WalletRepository call $name")
            }
        }

    /** No wallet is persisted yet (onboarding waits for it before deriving the chat key). */
    private fun persistableWalletProvider(): PersistableWalletProvider =
        proxy { name, args ->
            when {
                name == "getPersistableWallet" && args.isNullOrEmpty() -> MutableStateFlow(null)
                else -> error("unexpected PersistableWalletProvider call $name")
            }
        }

    private inline fun <reified T> unexpected(): T = proxy { name, _ -> error("unexpected ${T::class.simpleName} call $name") }

    private inline fun <reified T> proxy(crossinline answer: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { p, m, a ->
            when (m.name) {
                "toString" -> "fake ${T::class.simpleName}"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.get(0)
                else -> answer(m.name, a)
            }
        } as T

    /** The wallet: its chat identity, readable once [gate] opens. */
    private class IdentityWallet(
        private val identity: String,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer {
            gate?.await()
            return proxy
        }

        override fun resetSynchronizer() = Unit

        private val proxy =
            Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, m, a ->
                when (m.name) {
                    "getUnifiedAddress" -> identity
                    "toString" -> "IdentityWallet"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a?.get(0)
                    else -> error("unexpected Synchronizer call ${m.name}")
                }
            } as Synchronizer
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val WAIT_MS = 300L
    }
}
