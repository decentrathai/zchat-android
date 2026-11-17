package co.electriccoin.zcash.ui.screen.walletbackup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupConsentStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupRemindMeCountStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupRemindMeTimestampStorageProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.usecase.GetPersistableWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.OnUserSavedWalletBackupUseCase
import co.electriccoin.zcash.ui.common.usecase.RemindWalletBackupLaterUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletBackupData
import co.electriccoin.zcash.ui.common.usecase.WalletBackupMessageUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections

/**
 * SEED-AUTH (fix-all 2.13.0, train T4b; spec `prep-20260928/fold-now-specs/SEED-AUTH/SPEC.md`, review R1-R8).
 *
 * At HEAD the recovery phrase and its QR can be shown with no device authentication:
 * - `BiometricRepositoryImpl.requestBiometrics` begins `if (!canAuthenticate()) return`, so whenever the biometric
 *   check does not answer SUCCESS (in practice: a phone with no screen lock, or an OEM build that breaks the
 *   DEVICE_CREDENTIAL contract) Reveal shows the 24 words and the QR with no prompt at all;
 * - once revealed, the words and the QR stay revealed through a trip to the background and back.
 *
 * The fix adds a separate, fail-closed `requestSecretAccess` that always prompts on a phone with a screen lock (and
 * falls back to the screen-lock screen when the biometric stack is broken), Reveal uses it every time, and the screen
 * hides the words and the QR when the app stops. `requestBiometrics`, the app lock and Send stay byte-identical (S4).
 *
 * The phrase here is the public BIP-39 test vector ("abandon" x23 + "art"). No test asserts on it or prints it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeedRevealNeedsDeviceAuthTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── G: the gate (pure) ──────────────────────────────────────────────────────────────────────

    @Test
    fun secure_phone_is_always_prompted_even_when_the_biometric_check_fails() {
        // Statuses that reach the gate on a phone WITH a screen lock only through an OEM deviation (review R1).
        val oemDeviationStatuses = listOf(NONE_ENROLLED, NO_HARDWARE, HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, UNSUPPORTED, UNKNOWN)
        val expected =
            mapOf(
                34 to DEVICE_CREDENTIAL,
                30 to DEVICE_CREDENTIAL,
                29 to WEAK_OR_CREDENTIAL,
                28 to WEAK_OR_CREDENTIAL,
                27 to STRONG_OR_CREDENTIAL
            )
        for (status in oemDeviationStatuses) {
            for ((sdk, mask) in expected) {
                val decision = SeedAuthApi.gate(isDeviceSecure = true, canAuthenticateStatus = status, sdkInt = sdk)
                assertEquals(
                    "OEM deviation: status $status on sdk $sdk must still prompt (never a silent skip)",
                    SeedAuthApi.Decision("Prompt", mask),
                    decision
                )
            }
        }
    }

    @Test
    fun secure_phone_with_working_biometrics_gets_the_app_lock_prompt() {
        val appLockMask =
            mapOf(
                27 to STRONG_OR_CREDENTIAL,
                28 to WEAK_OR_CREDENTIAL,
                29 to WEAK_OR_CREDENTIAL,
                30 to STRONG_OR_CREDENTIAL,
                34 to STRONG_OR_CREDENTIAL
            )
        for ((sdk, mask) in appLockMask) {
            assertEquals(
                "SUCCESS on sdk $sdk: the same prompt as the app lock",
                SeedAuthApi.Decision("Prompt", mask),
                SeedAuthApi.gate(isDeviceSecure = true, canAuthenticateStatus = SUCCESS, sdkInt = sdk)
            )
        }
    }

    @Test
    fun only_a_phone_without_a_screen_lock_is_shown_without_a_prompt() {
        for (status in listOf(SUCCESS, NONE_ENROLLED, NO_HARDWARE, HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, UNSUPPORTED, UNKNOWN)) {
            for (sdk in listOf(27, 28, 29, 30, 34)) {
                assertEquals(
                    "no screen lock, status $status, sdk $sdk",
                    "NoScreenLock",
                    SeedAuthApi.gate(isDeviceSecure = false, canAuthenticateStatus = status, sdkInt = sdk).kind
                )
            }
        }
    }

    @Test
    fun a_broken_biometric_stack_falls_back_to_the_screen_lock_not_to_a_skip() {
        // HW_UNAVAILABLE, UNABLE_TO_PROCESS, NO_SPACE, VENDOR, NO_BIOMETRICS, HW_NOT_PRESENT, NO_DEVICE_CREDENTIAL,
        // SECURITY_UPDATE_REQUIRED: the same broken stack that could make canAuthenticate fail (review R5).
        for (code in listOf(1, 2, 4, 8, 11, 12, 14, 15)) {
            assertTrue("error $code falls back to the screen-lock screen", SeedAuthApi.credentialFallback(code))
        }
        // TIMEOUT, CANCELED, LOCKOUT, LOCKOUT_PERMANENT, USER_CANCELED, NEGATIVE_BUTTON: a plain failure (retry).
        for (code in listOf(3, 5, 7, 9, 10, 13)) {
            assertFalse("error $code is a plain failure, not a fallback", SeedAuthApi.credentialFallback(code))
        }
    }

    // ── V: the screen's view model ──────────────────────────────────────────────────────────────

    @Test
    fun reveal_asks_the_strict_gate_not_the_skippable_one() {
        for (fromInfo in listOf(false, true)) {
            screen(fromInfo, Answer.Access("CONFIRMED")) { h ->
                h.tapPrimary()
                assertEquals(
                    "fromInfo=$fromInfo: Reveal asks the strict gate once, never requestBiometrics",
                    listOf("requestSecretAccess"),
                    h.calls.toList()
                )
                assertTrue("fromInfo=$fromInfo: confirmed → revealed", h.state().seed.isRevealed)
            }
        }
    }

    @Test
    fun a_cancelled_or_failed_confirmation_keeps_the_words_and_the_qr_hidden_and_says_so() {
        val failures: List<() -> Exception> = listOf({ BiometricsCancelledException() }, { BiometricsFailureException() })
        for (fromInfo in listOf(false, true)) {
            for (failure in failures) {
                screen(fromInfo, Answer.Throw(failure)) { h ->
                    h.tapPrimary()
                    val state = h.state()
                    val what = "fromInfo=$fromInfo, ${failure().javaClass.simpleName}"
                    assertFalse("$what: the words stay hidden", state.seed.isRevealed)
                    assertFalse("$what: the birthday stays hidden", state.birthday.isRevealed)
                    assertTrue("$what: no QR exists", state.qrState == null)
                    assertEquals(
                        "$what: the screen says it couldn't confirm",
                        noticeOf("seed_recovery_auth_failed"),
                        SeedAuthApi.securityNotice(state)
                    )
                }
            }
        }
        // The Proxy (and a real implementation) may throw these checked exceptions only when the method declares them;
        // without @Throws they arrive as UndeclaredThrowableException and the catch in the view model misses them.
        val method = BiometricRepository::class.java.methods.firstOrNull { it.name == "requestSecretAccess" }
        assertNotNull("BiometricRepository has no requestSecretAccess", method)
        val declared = method!!.exceptionTypes.toSet()
        assertTrue(
            "requestSecretAccess must be @Throws(BiometricsFailureException, BiometricsCancelledException), declares $declared",
            declared.containsAll(setOf(BiometricsFailureException::class.java, BiometricsCancelledException::class.java))
        )
    }

    @Test
    fun a_phone_without_a_screen_lock_reveals_with_an_honest_notice() {
        for (fromInfo in listOf(false, true)) {
            screen(fromInfo, Answer.Access("NO_SCREEN_LOCK")) { h ->
                h.tapPrimary()
                val state = h.state()
                assertTrue("fromInfo=$fromInfo: no screen lock → the words show (D1)", state.seed.isRevealed)
                assertEquals(
                    "fromInfo=$fromInfo: … with the honest notice",
                    noticeOf("seed_recovery_no_screen_lock"),
                    SeedAuthApi.securityNotice(state)
                )
            }
            screen(fromInfo, Answer.Access("CONFIRMED")) { h ->
                h.tapPrimary()
                assertTrue("fromInfo=$fromInfo: confirmed → revealed", h.state().seed.isRevealed)
                assertEquals("fromInfo=$fromInfo: confirmed → no notice", null, SeedAuthApi.securityNotice(h.state()))
            }
        }
    }

    @Test
    fun going_to_the_background_hides_the_words_and_the_qr_and_the_next_reveal_asks_again() {
        for (fromInfo in listOf(false, true)) {
            screen(fromInfo, Answer.Access("CONFIRMED")) { h ->
                h.tapPrimary()
                assertTrue("fromInfo=$fromInfo: revealed", h.state().seed.isRevealed)
                h.state().onShowQrClick!!.invoke()
                h.runCurrent()
                assertTrue("fromInfo=$fromInfo: the QR is shown", h.state().showQrCode && h.state().qrState != null)

                SeedAuthApi.screenStopped(h.vm)
                h.runCurrent()
                val stopped = h.state()
                assertFalse("fromInfo=$fromInfo: the words are hidden after ON_STOP", stopped.seed.isRevealed)
                assertFalse("fromInfo=$fromInfo: the birthday is hidden after ON_STOP", stopped.birthday.isRevealed)
                assertFalse("fromInfo=$fromInfo: the QR is closed after ON_STOP", stopped.showQrCode)
                assertTrue("fromInfo=$fromInfo: no QR exists after ON_STOP", stopped.qrState == null)

                h.tapPrimary()
                assertEquals(
                    "fromInfo=$fromInfo: the next Reveal asks again",
                    listOf("requestSecretAccess", "requestSecretAccess"),
                    h.calls.toList()
                )
                assertTrue("fromInfo=$fromInfo: revealed again after a new confirmation", h.state().seed.isRevealed)
                assertFalse("fromInfo=$fromInfo: the QR does not come back by itself", h.state().showQrCode)
            }
        }
    }

    @Test
    fun hide_then_reveal_asks_again() {
        screen(fromInfo = false, answer = Answer.Access("CONFIRMED")) { h ->
            h.tapPrimary()
            assertTrue("revealed", h.state().seed.isRevealed)
            h.tapPrimary()
            assertFalse("hidden by the same button", h.state().seed.isRevealed)
            h.tapPrimary()
            assertEquals("every Reveal asks the strict gate", listOf("requestSecretAccess", "requestSecretAccess"), h.calls.toList())
        }
    }

    @Test
    fun nothing_secret_exists_before_the_confirmation() {
        for (fromInfo in listOf(false, true)) {
            screen(fromInfo, Answer.Access("CONFIRMED")) { h ->
                val fresh = h.state()
                assertFalse("fromInfo=$fromInfo: a fresh screen hides the words", fresh.seed.isRevealed)
                assertFalse("fromInfo=$fromInfo: a fresh screen hides the birthday", fresh.birthday.isRevealed)
                assertTrue("fromInfo=$fromInfo: a fresh screen has no QR", fresh.qrState == null)
                fresh.onShowQrClick?.invoke()
                h.runCurrent()
                assertTrue("fromInfo=$fromInfo: Show QR before any Reveal builds no QR", h.state().qrState == null)
                assertTrue("fromInfo=$fromInfo: nothing asked yet", h.calls.isEmpty())
            }
        }
    }

    // ── S: source guards ────────────────────────────────────────────────────────────────────────

    @Test
    fun every_way_to_the_phrase_goes_through_the_one_gated_screen() {
        val files = SeedAuthApi.mainKotlinFiles()
        val encoders = files.filter { it.readText().contains("SeedBackupQrData.encode(") }.map { it.name }
        assertEquals("only the gated screen builds the seed QR", listOf("WalletBackupViewModel.kt"), encoders)

        val seedTexts =
            files
                .filter { it.readText().contains("SeedTextState(") && it.readText().contains("seedPhrase") }
                .map { it.name }
        assertEquals("only the gated screen feeds the phrase into SeedTextState", listOf("WalletBackupViewModel.kt"), seedTexts)
        // WalletBackupView.kt's two @Preview states use fixture text, not the phrase.
        val view = SeedAuthApi.source("screen/walletbackup/WalletBackupView.kt")
        assertFalse("the view never reads the phrase", view.contains("seedPhrase"))

        val routeCalls = Regex("""\bWalletBackup\(isOpenedFromSeedBackupInfo""")
        val constructors =
            files.flatMap { file ->
                file.readLines().filter { routeCalls.containsMatchIn(it) }.map { file.name to it.trim() }
            }
        assertTrue("the route is opened from somewhere", constructors.isNotEmpty())
        for ((file, line) in constructors) {
            assertTrue(
                "$file: a WalletBackup(...) is only ever a navigation target: $line",
                line.contains("navigationRouter.forward(WalletBackup(")
            )
        }
        val routes = files.flatMap { f -> f.readLines().filter { it.contains("AndroidWalletBackup(it.toRoute())") }.map { f.name } }
        assertEquals("one composable route draws the screen", listOf("WalletNavGraph.kt"), routes)
    }

    @Test
    fun the_screen_hides_the_phrase_when_the_app_stops() {
        val screen = SeedAuthApi.source("screen/walletbackup/AndroidWalletBackup.kt")
        assertTrue(
            "a lifecycle observer on the screen's owner",
            screen.contains("LocalLifecycleOwner.current") && screen.contains("LifecycleEventObserver")
        )
        assertTrue("…removed on dispose", screen.contains("removeObserver("))
        assertTrue(
            "ON_STOP calls onScreenStopped() unless the activity is only being recreated (fold/unfold, review R7)",
            Regex(
                """Lifecycle\.Event\.ON_STOP\s*&&\s*!\s*\w+\.isChangingConfigurations[\s\S]{0,80}onScreenStopped\(\)"""
            ).containsMatchIn(screen)
        )
        assertTrue("the activity is the project's LocalActivity", screen.contains("LocalActivity.current"))
    }

    @Test
    fun the_prompt_host_accepts_the_gate_authenticators() {
        val activity = SeedAuthApi.source("BiometricActivity.kt")
        assertTrue(
            "createIntent takes the gate's authenticators, defaulted",
            Regex("""authenticators:\s*Int\?\s*=\s*null""").containsMatchIn(activity)
        )
        assertTrue(
            "…and puts EXTRA_AUTHENTICATORS only when given",
            Regex("""if \(authenticators != null\) putExtra\(EXTRA_AUTHENTICATORS, authenticators\)""").containsMatchIn(activity)
        )
        assertTrue(
            "the prompt uses EXTRA_AUTHENTICATORS, else the repository's mask",
            Regex("""getIntExtra\(EXTRA_AUTHENTICATORS,\s*biometricRepository\.allowedAuthenticators\)""").containsMatchIn(activity)
        )
        assertTrue(
            "…and passes it to setAllowedAuthenticators",
            Regex("""setAllowedAuthenticators\(authenticators\)""").containsMatchIn(activity)
        )
        // Review R5: the screen-lock fallback exists only in secret mode.
        assertEquals("one confirm-device-credential call", 1, Regex("""createConfirmDeviceCredentialIntent\(""").findAll(activity).count())
        assertTrue(
            "the fallback is reached only in secret mode and only for a broken-stack error",
            activity.contains("if (isSecretMode && secretCredentialFallback(errorCode) && launchScreenLockFallback(")
        )
        assertEquals("the fallback is defined once and called once", 2, Regex("""launchScreenLockFallback\(""").findAll(activity).count())
        // Review R6: a result-less destroy in secret mode is a failure, so Reveal is never wedged.
        val onDestroy = SeedAuthApi.bodyAfter(activity, "override fun onDestroy()")
        assertTrue(
            "onDestroy sends a Failure when the host finishes with no result",
            onDestroy.contains("if (isSecretMode && isFinishing && !resultSent)")
        )
        assertTrue("…a Failure", onDestroy.contains("BiometricResult.Failure(requestCode)"))
    }

    @Test
    fun app_lock_and_other_callers_are_unchanged() {
        val authentication = SeedAuthApi.source("common/viewmodel/AuthenticationViewModel.kt")
        assertTrue(
            "the app-lock session stays 15 minutes",
            authentication.contains("private val AUTHENTICATE_TIMEOUT = 15.minutes.inWholeMilliseconds")
        )
        val main = SeedAuthApi.source("MainActivity.kt")
        assertTrue(
            "onStart runs the app-lock check",
            SeedAuthApi.bodyAfter(main, "override fun onStart()").contains("authenticationViewModel.runAuthenticationRequiredCheck()")
        )
        assertTrue(
            "onStop stores the background time",
            SeedAuthApi
                .bodyAfter(
                    main,
                    "override fun onStop()"
                ).contains("authenticationViewModel.persistGoToBackgroundTime(System.currentTimeMillis())")
        )

        // Send and every other requestBiometrics caller keep today's behaviour (D5): the three bodies are byte-identical
        // to their text at 484812d1e / 1a2c89232.
        // QA2 Q2-101 (W-137, ZC-1600): requestBiometrics passes the optional prompt title (null for every caller but the
        // Address Book), so its pin was re-taken; canAuthenticate and allowedAuthenticators are unchanged.
        val repository = SeedAuthApi.source("common/repository/BiometricRepository.kt")
        assertEquals(
            "requestBiometrics body",
            "06a3668b1c8aecd395f1b6910ab694e47fbeb77ed06ed92ab74e15abb573cb3d",
            sha256(SeedAuthApi.bodyAfter(repository, "override suspend fun requestBiometrics(request: BiometricRequest)"))
        )
        assertEquals(
            "canAuthenticate body",
            "de7210a4b294de4c73f3260670a7e067015fd39430b26bacf283b77a2997b212",
            sha256(SeedAuthApi.bodyAfter(repository, "private fun canAuthenticate(): Boolean"))
        )
        assertEquals(
            "allowedAuthenticators getter",
            "cfd1eaa57450f6981aaa6dbac32eb72fdd53ea2fe1e8943940352c60a2f96148",
            sha256(SeedAuthApi.bodyAfter(repository, "override val allowedAuthenticators: Int"))
        )
    }

    @Test
    fun seed_auth_texts_exist_in_english_and_spanish() {
        val en = SeedAuthApi.resource("ui/seed_recovery/values/strings.xml")
        val es = SeedAuthApi.resource("ui/seed_recovery/values-es/strings.xml")
        for (key in listOf("seed_recovery_no_screen_lock", "seed_recovery_auth_failed")) {
            val english = stringValue(en, key)
            val spanish = stringValue(es, key)
            assertTrue("EN $key is defined and not empty", !english.isNullOrBlank())
            assertTrue("ES $key is defined and not empty", !spanish.isNullOrBlank())
            assertNotEquals("ES $key is translated", english, spanish)
        }
    }

    @Test
    fun hidden_words_never_animate_from_sharp() {
        val view = SeedAuthApi.source("screen/walletbackup/WalletBackupView.kt")
        assertTrue(
            "ZashiSeedText is keyed on isRevealed, so a hidden instance starts blurred (review R4)",
            Regex("""key\(state\.seed\.isRevealed\)\s*\{\s*ZashiSeedText\(""").containsMatchIn(view)
        )
        assertTrue(
            "BDSecret is keyed on isRevealed, so a hidden instance starts blurred (review R4)",
            Regex("""key\(state\.seed\.isRevealed\)\s*\{\s*BDSecret\(""").containsMatchIn(view)
        )
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────────────

    private sealed interface Answer {
        data class Access(
            val name: String
        ) : Answer

        class Throw(
            val exception: () -> Exception
        ) : Answer
    }

    private class Harness(
        val vm: WalletBackupViewModel,
        val calls: List<String>,
        private val scope: TestScope,
    ) {
        fun state(): WalletBackupState = checkNotNull(vm.state.value) { "harness: the screen state is null" }

        fun runCurrent() = scope.runCurrent()

        fun tapPrimary() {
            state().primaryButton.onClick()
            scope.runCurrent()
        }
    }

    private fun screen(
        fromInfo: Boolean,
        answer: Answer,
        block: (Harness) -> Unit
    ) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val store = ViewModelStore()
        try {
            val vm =
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(
                            modelClass: Class<T>
                        ): T = backupViewModel(fromInfo, biometrics(calls, answer)) as T
                    }
                )[WalletBackupViewModel::class.java]
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertNotNull("harness: the screen state was never produced (collect it first)", vm.state.value)
            block(Harness(vm, calls, this))
        } finally {
            store.clear()
        }
    }

    /** A BiometricRepository that records calls; requestBiometrics returns (as at HEAD with a SUCCESS status). */
    private fun biometrics(
        calls: MutableList<String>,
        answer: Answer
    ): BiometricRepository =
        Proxy.newProxyInstance(
            BiometricRepository::class.java.classLoader,
            arrayOf(BiometricRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "fake BiometricRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "getAllowedAuthenticators" -> STRONG_OR_CREDENTIAL
                "onBiometricResult" -> Unit
                "requestBiometrics" -> {
                    calls += "requestBiometrics"
                    Unit
                }
                "requestSecretAccess" -> {
                    calls += "requestSecretAccess"
                    when (answer) {
                        is Answer.Access -> SeedAuthApi.secretAccess(answer.name)
                        is Answer.Throw -> throw answer.exception()
                    }
                }
                else -> throw UnsupportedOperationException("BiometricRepository.${method.name}() is not used here")
            }
        } as BiometricRepository

    private fun backupViewModel(
        fromInfo: Boolean,
        biometricRepository: BiometricRepository
    ): WalletBackupViewModel {
        val navigationRouter = unused<NavigationRouter>()
        return WalletBackupViewModel(
            walletBackupMessageUseCase =
                object : WalletBackupMessageUseCase {
                    override fun observe(): Flow<WalletBackupData> = emptyFlow()
                },
            getPersistableWallet = GetPersistableWalletUseCase(WalletProvider()),
            args = WalletBackup(isOpenedFromSeedBackupInfo = fromInfo),
            navigationRouter = navigationRouter,
            onUserSavedWalletBackup = OnUserSavedWalletBackupUseCase(navigationRouter, unused<WalletBackupFlagStorageProvider>()),
            remindWalletBackupLater =
                RemindWalletBackupLaterUseCase(
                    navigationRouter,
                    unused<WalletBackupConsentStorageProvider>(),
                    unused<WalletBackupRemindMeCountStorageProvider>(),
                    unused<WalletBackupRemindMeTimestampStorageProvider>(),
                ),
            biometricRepository = biometricRepository,
        )
    }

    private class WalletProvider : PersistableWalletProvider {
        private val wallet =
            PersistableWallet(
                network = ZcashNetwork.Mainnet,
                endpoint = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
                birthday = BlockHeight.new(BIRTHDAY),
                seedPhrase = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE - 1) { "abandon" } + "art"),
                walletInitMode = WalletInitMode.NewWallet,
            )

        override val persistableWallet: Flow<PersistableWallet?> = flowOf(wallet)

        override suspend fun store(persistableWallet: PersistableWallet) = error("not used")

        override suspend fun getPersistableWallet(): PersistableWallet = wallet

        override suspend fun requirePersistableWallet(): PersistableWallet = wallet
    }

    private fun noticeOf(name: String): StringResource = StringResource.ByResource(SeedAuthApi.stringId(name), emptyList())

    private fun stringValue(
        xml: String,
        key: String
    ): String? =
        Regex("""<string name="$key"[^>]*>([\s\S]*?)</string>""")
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.trim()

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BIRTHDAY = 3_000_000L

        // androidx.biometric.BiometricManager statuses
        const val SUCCESS = 0
        const val HW_UNAVAILABLE = 1
        const val NONE_ENROLLED = 11
        const val NO_HARDWARE = 12
        const val SECURITY_UPDATE_REQUIRED = 15
        const val UNSUPPORTED = -2
        const val UNKNOWN = -1

        // androidx.biometric.BiometricManager.Authenticators
        const val DEVICE_CREDENTIAL = 32768
        const val STRONG_OR_CREDENTIAL = 32783
        const val WEAK_OR_CREDENTIAL = 33023

        inline fun <reified T : Any> unused(): T =
            Proxy.newProxyInstance(
                T::class.java.classLoader,
                arrayOf(T::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "unused<${T::class.java.simpleName}>"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> throw UnsupportedOperationException("${T::class.java.simpleName}.${method.name}() is not used here")
                }
            } as T
    }
}
