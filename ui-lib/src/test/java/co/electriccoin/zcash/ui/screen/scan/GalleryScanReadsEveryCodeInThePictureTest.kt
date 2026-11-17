package co.electriccoin.zcash.ui.screen.scan

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.OnAddressScannedUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillRestoreSeedUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.design.util.QrDrawFixtures
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyStore
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QR-7 (fix-all 2.13.0, train T4c; qr-sync-ui fold spec §1.3 and §1.4.2-§1.4.3 with §9 rows 3, 4 and 6): a picture from
 * the gallery gives the scanner every code in it, and the scanner says what it found.
 *
 * At the parent the gallery handed the screen one text or null; null showed "This QR code is not a valid Zcash
 * Address." (the text for a bad address), and a picture with two people's codes started a chat with, or prefilled a
 * send to, whichever one ZXing found first.
 *
 * (A) behaviour: `ScanZashiAddressVM.onScannedTexts(texts)` and `onPictureHasNoCode()`, reached by reflection so this
 * compiles before they exist; the ViewModel is built over its constructor's parameter types (the
 * ScannedFullCodeAfterCutCopyBindsRealAddressTest idiom). States are compared by name.
 * (B) wiring, source scan: the converter returns a list from QrPictureDecoder, ScanView routes an empty list to its own
 * callback and text, both scan screens pass the list, ScanGenericAddressVM chooses with the picker and its Quantum Shield
 * branch is unchanged, and the scan log never prints the scanned text. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryScanReadsEveryCodeInThePictureTest {
    private val alice = SyntheticUnifiedAddress.mainnet(58_201)
    private val bob = SyntheticUnifiedAddress.mainnet(58_202)
    private val aliceKey = RelayKeyBinding.relayKey(821)
    private val bobKey = RelayKeyBinding.relayKey(822)
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

    // ------------------------------------------------------------------ (A) behaviour

    private class Rig(
        val vm: ScanZashiAddressVM,
        val store: RelayKeyStore,
        val prefill: PrefillZchatUseCase,
        val router: RelayKeyBinding.RecordingRouter,
    )

    private fun allocate(type: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return checkNotNull(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    private fun rig(flow: ScanFlow): Rig {
        val store = RelayKeyStore()
        val prefill = PrefillZchatUseCase()
        val router = RelayKeyBinding.RecordingRouter()
        val constructor = ScanZashiAddressVM::class.java.declaredConstructors.single()
        val args =
            constructor.parameterTypes.map { type ->
                when (type) {
                    ScanArgs::class.java -> ScanArgs(flow = flow, isScanZip321Enabled = flow != ScanFlow.ZCHAT)
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
        return Rig(vm, store, prefill, router)
    }

    private fun call(
        vm: ScanZashiAddressVM,
        name: String,
        vararg args: Any
    ) = runBlocking {
        val method =
            ScanZashiAddressVM::class.java.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: fail("ScanZashiAddressVM.$name missing: the gallery hands the scanner one text or an error")
        val result =
            try {
                method.invoke(vm, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        (result as? Job)?.join()
    }

    @Test
    fun a_picture_with_two_peoples_codes_starts_nothing_in_new_chat() {
        val r = rig(ScanFlow.ZCHAT)
        call(r.vm, "onScannedTexts", listOf(RelayKeyBinding.code(alice, aliceKey), RelayKeyBinding.code(bob, bobKey)))

        assertEquals("MANY_CODES_IN_PICTURE", r.vm.state.value.name, "a two-person picture must say so")
        assertTrue(r.store.pubkeys.isEmpty(), "a relay key was stored from a two-person picture: ${r.store.writes}")
        assertNull(r.prefill.scannedAddress.value, "New Chat was prefilled from a two-person picture")
        assertTrue(r.router.backs.isEmpty() && r.router.replaced.isEmpty(), "the scanner left a two-person picture")
    }

    @Test
    fun an_invite_screenshot_imports_the_code_not_the_bare_address() {
        val r = rig(ScanFlow.ZCHAT)
        call(r.vm, "onScannedTexts", listOf(alice, RelayKeyBinding.code(alice, aliceKey)))

        assertEquals(aliceKey, r.store.pubkeys[alice], "the code's relay key was not stored: ${r.store.writes}")
        assertEquals(alice, r.prefill.scannedAddress.value, "New Chat was not prefilled with the address")
        assertEquals("VALID", r.vm.state.value.name)
    }

    @Test
    fun a_picture_with_two_addresses_prefills_no_send() {
        val r = rig(ScanFlow.SEND)
        call(r.vm, "onScannedTexts", listOf(alice, bob))

        assertEquals("MANY_CODES_IN_PICTURE", r.vm.state.value.name, "Wallet Send must not pick a recipient")
        assertTrue(r.router.backs.isEmpty() && r.router.replaced.isEmpty(), "Send moved on from a two-address picture")
    }

    @Test
    fun a_picture_with_no_code_says_so_and_not_invalid_address() {
        val r = rig(ScanFlow.ZCHAT)
        call(r.vm, "onPictureHasNoCode")

        assertEquals("NO_CODE_IN_PICTURE", r.vm.state.value.name, "no code found must not read as a bad address")
    }

    @Test
    fun one_code_in_a_picture_imports_as_a_camera_scan_does() {
        val r = rig(ScanFlow.ZCHAT)
        call(r.vm, "onScannedTexts", listOf(RelayKeyBinding.code(alice, aliceKey)))

        assertEquals(aliceKey, r.store.pubkeys[alice])
        assertEquals(alice, r.prefill.scannedAddress.value)
    }

    // ------------------------------------------------------------------ (B) wiring

    private val base = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/scan"

    private fun src(name: String) = QrDrawFixtures.source("$base/$name")

    private fun squash(text: String) = QrDrawFixtures.squash(text)

    @Test
    fun the_converter_returns_every_code_from_the_decoder() {
        val converter = squash(src("ImageUriToQrCodeConverter.kt"))
        assertTrue(Regex("""\): List<String> =""").containsMatchIn(converter), "the converter must return List<String>")
        assertTrue("QrPictureDecoder.decode(" in converter, "the converter must delegate to QrPictureDecoder.decode")
        assertFalse("MultiFormatReader" in converter, "the converter still runs its own single reader")
        val decoder = src("QrPictureDecoder.kt")
        for (piece in listOf("GenericMultipleBarcodeReader", "GlobalHistogramBinarizer", "HybridBinarizer", "invert()")) {
            assertTrue(piece in decoder, "QrPictureDecoder must use $piece")
        }
    }

    @Test
    fun the_gallery_sends_no_code_to_its_own_callback_and_text() {
        val view = squash(src("ScanView.kt"))
        val handler = view.substringAfter("rememberLauncherForActivityResult(").substringBefore("ConstraintLayout(")
        assertTrue("onPictureHasNoCode()" in handler, "an empty picture must call onPictureHasNoCode")
        assertTrue("onScanTexts(" in handler, "a picture's codes must go to onScanTexts")
        assertFalse("onScanError()" in handler, "the gallery still reports a picture with no code as a bad address")
        assertTrue("R.string.scan_picture_no_code" in view, "ScanView must show scan_picture_no_code")
        assertTrue("R.string.scan_picture_many_codes" in view, "ScanView must show scan_picture_many_codes")
        assertTrue(
            Regex("""onScanTexts: \(List<String>\) -> Unit = \{ it\.firstOrNull\(\)\?\.let\(onScan\) \}""").containsMatchIn(view),
            "a caller that passes no onScanTexts must keep today's single-text behaviour"
        )
        assertTrue("onPictureHasNoCode: () -> Unit = onScanError" in view, "a missed caller must keep today's error")
    }

    @Test
    fun both_scan_screens_pass_the_list_and_the_no_code_callback() {
        for (screen in listOf("ScanZashiAddressScreen.kt", "ScanGenericAddressScreen.kt")) {
            val text = squash(src(screen))
            assertTrue(Regex("""onScanTexts = \{ \w+\.onScannedTexts\(it\) \}""").containsMatchIn(text), "$screen: onScanTexts")
            assertTrue(Regex("""onPictureHasNoCode = \{ \w+\.onPictureHasNoCode\(\) \}""").containsMatchIn(text), "$screen: no code")
        }
    }

    @Test
    fun the_generic_scanner_chooses_with_the_picker_and_keeps_its_quantum_shield_branch() {
        val vm = src("ScanGenericAddressVM.kt")
        assertTrue("ScannedTexts.pick(null, texts)" in vm, "ScanGenericAddressVM must choose with ScannedTexts.pick(null, texts)")
        assertTrue("ScanValidationState.MANY_CODES_IN_PICTURE" in vm, "ScanGenericAddressVM must say a picture has many codes")
        assertTrue("ScanValidationState.NO_CODE_IN_PICTURE" in vm, "ScanGenericAddressVM must say a picture has no code")
        // The ZCPSK branch as it stands at 255a05268 (D3-3 re-anchors on it), byte for byte.
        val zcpsk =
            """
            if (result.startsWith("ZCPSK:") &&
                co.electriccoin.zcash.ui.screen.chat.filesharing.QuantumShieldScanBridge.hasPending()
            ) {
                state.update { ScanValidationState.VALID }
                co.electriccoin.zcash.ui.screen.chat.filesharing.QuantumShieldScanBridge.consume(result)
                hasBeenScannedSuccessfully = true
                navigateToScanAddress.onScanCancelled(args) // go back to chat
                return@withLock
            }
            """.trimIndent().prependIndent("                    ")
        assertTrue(zcpsk in vm, "the Quantum Shield (ZCPSK) branch of ScanGenericAddressVM changed")
    }

    @Test
    fun the_scan_log_prints_no_scanned_text() {
        val vm = src("ScanZashiAddressVM.kt")
        assertFalse("result.take(" in vm, "ScanZashiAddressVM logs the start of the scanned text (a seed backup is JSON)")
        val converter = src("ImageUriToQrCodeConverter.kt") + src("QrPictureDecoder.kt")
        assertFalse(Regex("""Twig\.\w+\s*(\([^)]*\))?\s*\{[^}]*\.text\b""").containsMatchIn(converter), "a decoded text is logged")
    }
}
