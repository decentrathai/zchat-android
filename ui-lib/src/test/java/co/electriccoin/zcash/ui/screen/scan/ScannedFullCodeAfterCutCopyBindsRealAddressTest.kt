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

/**
 * T4b2 cross review, finding T4b2-G1 (guard, green before and after the fix): the scan of a contact's full code still
 * binds the real address when the only holder of its relay key is a cut copy of that address.
 *
 * The fix stops a received KEX or KEXACK from taking a relay key from a cut copy (KexDoesNotTakeRelayKeyFromCutCopyTest)
 * and leaves that to the user's own paste or scan of the code. This pins the scan half (the paste half is
 * ContactCodeAfterCutCopyBindsRealAddressTest): New Chat's camera and gallery scan binds the key to the real address and
 * prefills it. The ViewModel is built by reflection over its constructor's parameter types (the
 * ScannedCodeKeyAlreadyBoundIsRefusedTest idiom). All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannedFullCodeAfterCutCopyBindsRealAddressTest {
    private val real = SyntheticUnifiedAddress.mainnet(57_101)
    private val cut = real.take(102)
    private val key = RelayKeyBinding.relayKey(711)
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

    @Test
    fun `scanning the full code binds the real address when the key's only holder is the cut copy (guard)`() =
        runBlocking {
            val store =
                RelayKeyStore().apply {
                    pubkeys[cut] = key
                    relays[cut] = RelayKeyBinding.RELAY
                }
            val prefill = PrefillZchatUseCase()
            val vm = scanVm(store, prefill, RelayKeyBinding.RecordingRouter())

            vm.onScanned(RelayKeyBinding.code(real, key)).join()

            assertEquals(key, store.pubkeys[real], "the scan of the full code did not bind the real address: ${store.writes}")
            assertEquals(RelayKeyBinding.RELAY, store.relays[real])
            assertEquals(real, prefill.scannedAddress.value, "New Chat was not prefilled with the real address")
            assertFalse(store.wrote("setE2EKeyChanged", real), "the real address was flagged: ${store.writes}")
        }
}
