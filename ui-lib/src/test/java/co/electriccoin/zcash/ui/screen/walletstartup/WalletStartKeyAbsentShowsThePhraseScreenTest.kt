package co.electriccoin.zcash.ui.screen.walletstartup

import androidx.security.crypto.MasterKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhoneContext
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.MasterKeyGuard
import co.electriccoin.zcash.ui.screen.chat.util.ResetWipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * ZC-868 (register, high; review REVIEW_T7aR_2_20261020.md finding 1): when the wallet-start check refuses the start
 * because the Keystore says the phrase store's key is absent ([MasterKeyGuard.checkBeforeWalletStart]), the user must
 * see the recovery-phrase screen ("ZCHAT cannot unlock the place where your recovery phrase is stored ... your written
 * recovery phrase"), not the chats screen ("your chats and your wallet are still on this phone"): the refusal is about
 * the phrase store's key. At 2fbdfcb87 the refusal is an error of the chat-store kind, so the screen's choice
 * ([WalletStartupRepair.offerFor], [walletStartupFailedContent]) takes the chats screen.
 *
 * Driven as the app does it: the start-up check with the guard as its phrase-store open, its state's cause, the
 * screen's offer and its content. The control: the same "absent" answer for a new chat store (the opener's check)
 * keeps the chats screen. The guard's Keystore question is its replaceable call, reached by name.
 */
class WalletStartKeyAbsentShowsThePhraseScreenTest {
    private var pristine: Any? = null

    @After
    fun tearDown() {
        if (pristine != null) keystoreCall().set(guardInstance(), pristine)
    }

    @Test
    fun `the wallet-start refusal on an absent key shows the recovery-phrase screen`() =
        runBlocking {
            val context = phoneWithPhraseStore()
            keystoreSaysAbsent()
            val check =
                StartupStorageCheck(
                    openSeedStore = { MasterKeyGuard.checkBeforeWalletStart(context) },
                    openChatStores = {},
                    ioDispatcher = Dispatchers.Unconfined,
                ).apply { resetStartOutcome = { ResetWipe.Outcome.NONE } }

            check.run()
            val state = check.state.value
            assertTrue("the wallet's start was not refused: $state", state is StartupStorageCheck.State.SeedStoreUnreadable)

            val offer = repair().offerFor(startupFailureCause(null, state))
            assertEquals("the refusal does not choose the recovery-phrase screen", WalletStartupRepair.Offer.SeedStoreUnreadable, offer)
            assertEquals(
                "the screen shown is not the recovery-phrase screen",
                R.string.wallet_startup_seed_unreadable_title,
                walletStartupFailedContent(offer).title,
            )
        }

    @Test
    fun `control - the same absent answer for a new chat store keeps the chats screen`() =
        runBlocking {
            val context = phoneWithPhraseStore()
            keystoreSaysAbsent()
            val thrown =
                runCatching { MasterKeyGuard.checkBeforeMakingKey(context, NEW_CHAT_STORE, MasterKey.DEFAULT_MASTER_KEY_ALIAS) }
                    .exceptionOrNull()
            assertTrue("the new chat store's key was not refused: $thrown", thrown != null)

            val offer = repair().offerFor(thrown)
            assertEquals(WalletStartupRepair.Offer.ChatStoresUnavailable, offer)
            assertEquals(R.string.wallet_startup_chats_unavailable_title, walletStartupFailedContent(offer).title)
        }

    // ------------------------------------------------------------------ fixture

    private fun phoneWithPhraseStore(): KeysetPhoneContext {
        val data = Files.createTempDirectory("zchat-zc868-screen").toFile().apply { deleteOnExit() }
        val files = KeysetPhone(File(data, "shared_prefs").apply { mkdirs() })
        files.put(PHRASE_STORE, Keysets.wrappedEntries())
        return KeysetPhoneContext(files, dataDir = data)
    }

    private fun keystoreSaysAbsent() {
        val field = keystoreCall()
        if (pristine == null) pristine = field.get(guardInstance())
        field.set(guardInstance(), { _: String -> false })
    }

    private fun guardInstance(): Any = MasterKeyGuard

    private fun keystoreCall() =
        MasterKeyGuard::class.java.declaredFields
            .first { it.name == "keystoreHasKey" }
            .apply { isAccessible = true }

    private fun repair() =
        WalletStartupRepair(
            persistableWalletProvider = unused<PersistableWalletProvider>(),
            walletRestoringStateProvider = unused<WalletRestoringStateProvider>(),
            walletDataDatabase =
                object : WalletDataDatabase {
                    override fun setAside(network: ZcashNetwork): Boolean = error("the wallet data must not be touched")
                },
            appRestarter = {},
        )

    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("${method.name} must not be called")
        } as T

    private companion object {
        const val PHRASE_STORE = "co.electriccoin.zcash.encrypted"
        const val NEW_CHAT_STORE = "zchat_contact_book_enc"
    }
}
