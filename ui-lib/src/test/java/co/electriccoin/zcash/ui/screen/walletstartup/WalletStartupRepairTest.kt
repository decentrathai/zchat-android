package co.electriccoin.zcash.ui.screen.walletstartup

import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.test.Test

/**
 * P2f, plan change 4 (the Zodl iOS fix for a seed/database mismatch crash loop).
 *
 * When the SDK refuses the wallet database because it belongs to a different recovery phrase
 * (InitializeException.SeedNotRelevant, proven by the SDK against the accounts in the database) and the saved
 * phrase is readable, the start-up screen offers to set the old data aside and rescan. Only a tap does it: the
 * old database is renamed (never deleted), the wallet is marked as restoring and ZCHAT restarts, which rescans
 * for the saved phrase. With the phrase missing or unreadable, or for any other failure, the A3 safety screen
 * stays. The A3 screen's "Try again" restarts ZCHAT.
 */
class WalletStartupRepairTest {
    private val events = mutableListOf<String>()
    private val database = FakeDatabase()
    private val restoring = FakeRestoringState()

    private fun repair(wallet: suspend () -> PersistableWallet?) =
        WalletStartupRepair(
            persistableWalletProvider = FakeWalletProvider(wallet),
            walletRestoringStateProvider = restoring,
            walletDataDatabase = database,
            appRestarter = { events += "restart" },
        )

    private val readable = repair { savedWallet() }

    // ── what the screen offers ───────────────────────────────────────────────────────────────────

    @Test
    fun `a proven seed and database mismatch with a readable phrase offers to set the old data aside`() =
        runBlocking {
            assertEquals(
                "the SDK proved the database belongs to another phrase and the saved phrase is readable, so " +
                    "the screen must explain it and offer to set the old data aside and rescan",
                WalletStartupRepair.Offer.SetAsideAndRescan,
                readable.offerFor(InitializeException.SeedNotRelevant),
            )
            assertEquals("asking what to offer must change nothing", emptyList<String>(), events)
            assertEquals(emptyList<ZcashNetwork>(), database.setAsideCalls)
        }

    @Test
    fun `the mismatch is recognised when the SDK error is wrapped`() =
        runBlocking {
            assertEquals(
                WalletStartupRepair.Offer.SetAsideAndRescan,
                readable.offerFor(IllegalStateException("Synchronizer.new failed", InitializeException.SeedNotRelevant)),
            )
        }

    @Test
    fun `with no saved phrase the safety screen stays`() =
        runBlocking {
            assertEquals(
                WalletStartupRepair.Offer.SafetyScreen,
                repair { null }.offerFor(InitializeException.SeedNotRelevant),
            )
        }

    @Test
    fun `with a phrase that cannot be read the safety screen stays`() =
        runBlocking {
            assertEquals(
                WalletStartupRepair.Offer.SafetyScreen,
                repair { error("Keystore unavailable") }.offerFor(InitializeException.SeedNotRelevant),
            )
        }

    @Test
    fun `with a phrase that is not a valid recovery phrase the safety screen stays`() =
        runBlocking {
            val broken = savedWallet(SeedPhrase.new(List(24) { "abandon" }.joinToString(" ")))
            assertEquals(
                WalletStartupRepair.Offer.SafetyScreen,
                repair { broken }.offerFor(InitializeException.SeedNotRelevant),
            )
        }

    @Test
    fun `any other start-up failure keeps the safety screen`() =
        runBlocking {
            assertEquals(
                WalletStartupRepair.Offer.SafetyScreen,
                readable.offerFor(IllegalStateException("migration failed")),
            )
            assertEquals(WalletStartupRepair.Offer.SafetyScreen, readable.offerFor(null))
        }

    // ── the tap ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the tap sets the old data aside, marks the wallet as restoring and then restarts`() =
        runBlocking {
            assertTrue(readable.setAsideAndRescan(InitializeException.SeedNotRelevant))

            assertEquals(
                "the old database of the saved wallet's network is set aside, exactly once",
                listOf(ZcashNetwork.Mainnet),
                database.setAsideCalls,
            )
            assertEquals(
                "the rescan must show as a restore",
                listOf(WalletRestoringState.RESTORING),
                restoring.stored,
            )
            assertEquals(
                "the restart that starts the rescan must come after the old data is set aside",
                listOf("setAside", "restoring", "restart"),
                events,
            )
        }

    @Test
    fun `when the old data cannot be set aside nothing else happens`() =
        runBlocking {
            database.result = false

            assertFalse(readable.setAsideAndRescan(InitializeException.SeedNotRelevant))

            assertEquals(listOf("setAside"), events)
            assertEquals(emptyList<WalletRestoringState>(), restoring.stored)
        }

    @Test
    fun `the tap does nothing when the phrase is missing or the failure is not a mismatch`() =
        runBlocking {
            assertFalse(repair { null }.setAsideAndRescan(InitializeException.SeedNotRelevant))
            assertFalse(readable.setAsideAndRescan(IllegalStateException("migration failed")))

            assertEquals(emptyList<ZcashNetwork>(), database.setAsideCalls)
            assertEquals(emptyList<String>(), events)
        }

    @Test
    fun `Try again restarts ZCHAT and changes nothing`() {
        readable.tryAgain()

        assertEquals(listOf("restart"), events)
        assertEquals(emptyList<ZcashNetwork>(), database.setAsideCalls)
    }

    // ── fakes ────────────────────────────────────────────────────────────────────────────────────

    private fun savedWallet(seedPhrase: SeedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase)) =
        PersistableWallet(
            network = ZcashNetwork.Mainnet,
            endpoint = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
            birthday = BlockHeight.new(3_000_000L),
            seedPhrase = seedPhrase,
            walletInitMode = WalletInitMode.ExistingWallet,
        )

    private inner class FakeDatabase : WalletDataDatabase {
        var result = true
        val setAsideCalls = mutableListOf<ZcashNetwork>()

        override fun setAside(network: ZcashNetwork): Boolean {
            setAsideCalls += network
            events += "setAside"
            return result
        }
    }

    private inner class FakeRestoringState : WalletRestoringStateProvider {
        val stored = mutableListOf<WalletRestoringState>()
        private val state = MutableStateFlow(WalletRestoringState.NONE)

        override suspend fun get() = state.value

        override suspend fun store(value: WalletRestoringState) {
            stored += value
            events += "restoring"
            state.value = value
        }

        override fun observe(): Flow<WalletRestoringState> = state

        override suspend fun clear() = Unit
    }

    private class FakeWalletProvider(
        private val wallet: suspend () -> PersistableWallet?
    ) : PersistableWalletProvider {
        override val persistableWallet: Flow<PersistableWallet?> = flowOf(null)

        override suspend fun store(persistableWallet: PersistableWallet) = error("not used")

        override suspend fun getPersistableWallet() = wallet()

        override suspend fun requirePersistableWallet() = checkNotNull(wallet())
    }
}
