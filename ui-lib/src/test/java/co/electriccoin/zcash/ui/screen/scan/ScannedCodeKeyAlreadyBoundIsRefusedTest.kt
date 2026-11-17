package co.electriccoin.zcash.ui.screen.scan

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.OnAddressScannedUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillRestoreSeedUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyStore
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.1 item 3, §1.3 step 3): a scanned contact code whose relay key already
 * belongs to another address binds nothing and prefills nothing.
 *
 * At HEAD, ScanZashiAddressVM.onScanned (New Chat's camera and gallery scan) binds a code's relay key to the code's
 * address after the SDK calls the address valid, comparing only with the key stored for the SAME address, and then
 * prefills New Chat with that address. A code with a valid address B and the key already bound to A puts one key on two
 * addresses and starts a new chat with B.
 *
 * Wanted: B is not bound, New Chat is not prefilled with B, and New Chat is told the key already belongs to A (the
 * "already belongs to" notice, read here from the prefill by name). The ViewModel is built by reflection over its
 * constructor's parameter types, so this file compiles before and after the fix adds a parameter. All values are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannedCodeKeyAlreadyBoundIsRefusedTest {
    private val a = SyntheticUnifiedAddress.mainnet(4_801)
    private val b = SyntheticUnifiedAddress.mainnet(4_802)
    private val key = RelayKeyBinding.relayKey(81)
    private val vms = mutableListOf<ScanZashiAddressVM>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun allocate(type: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return checkNotNull(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    private fun scanVm(
        store: RelayKeyStore,
        prefill: PrefillZchatUseCase,
        router: NavigationRouter,
    ): ScanZashiAddressVM {
        val constructor = ScanZashiAddressVM::class.java.declaredConstructors.single()
        val args =
            constructor.parameterTypes.map { type ->
                when (type) {
                    ScanArgs::class.java -> ScanArgs(flow = ScanFlow.ZCHAT, isScanZip321Enabled = false)
                    SynchronizerProvider::class.java -> RelayKeyBinding.AnsweringSynchronizerProvider(RelayKeyBinding.SELF)
                    OnAddressScannedUseCase::class.java ->
                        OnAddressScannedUseCase(router, PrefillSendUseCase(), prefill, PrefillRestoreSeedUseCase())
                    PrefillRestoreSeedUseCase::class.java -> PrefillRestoreSeedUseCase()
                    PrefillZchatUseCase::class.java -> prefill
                    NavigationRouter::class.java -> router
                    ZchatPreferences::class.java -> store.proxy
                    else -> allocate(type)
                }
            }
        constructor.isAccessible = true
        val vm =
            try {
                constructor.newInstance(*args.toTypedArray()) as ScanZashiAddressVM
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        vms += vm
        return vm
    }

    /** The "key already belongs to" address the scan handed to New Chat (M1), or null (none, or no such field yet). */
    @Suppress("UNCHECKED_CAST")
    private fun keyBoundElsewhere(prefill: PrefillZchatUseCase): String? =
        runCatching {
            (
                PrefillZchatUseCase::class.java
                    .getDeclaredField("_keyBoundElsewhere")
                    .apply { isAccessible = true }
                    .get(prefill) as StateFlow<String?>
            ).value
        }.getOrNull()

    @Test
    fun `a scanned code with another valid address and a key bound elsewhere binds and prefills nothing`() =
        runBlocking {
            val store =
                RelayKeyStore().apply {
                    pubkeys[a] = key
                    relays[a] = RelayKeyBinding.RELAY
                }
            val prefill = PrefillZchatUseCase()
            val vm = scanVm(store, prefill, RelayKeyBinding.RecordingRouter())

            vm.onScanned(RelayKeyBinding.code(b, key)).join()

            assertNull(store.pubkeys[b], "the scan bound a relay key that belongs to another address")
            assertEquals(a, store.proxy.findPeerByNostrPubkey(key), "the key no longer resolves to the first address")
            assertFalse(store.wrote("setPeerNostrPubkey", b), "a relay key was written: ${store.writes}")
            assertFalse(store.wrote("setE2EKeyChanged", a), "the first address was flagged: ${store.writes}")
            assertNotEquals(b, prefill.scannedAddress.value, "New Chat was prefilled with the second address")
            assertEquals(a, keyBoundElsewhere(prefill), "New Chat was not told the key belongs to the first address")
        }

    @Test
    fun `a scanned code whose key is bound to no one binds it and prefills its address (guard)`() =
        runBlocking {
            val store = RelayKeyStore()
            val prefill = PrefillZchatUseCase()
            val vm = scanVm(store, prefill, RelayKeyBinding.RecordingRouter())

            vm.onScanned(RelayKeyBinding.code(b, key)).join()

            assertEquals(key, store.pubkeys[b])
            assertEquals(b, prefill.scannedAddress.value)
            assertNull(keyBoundElsewhere(prefill))
        }
}
