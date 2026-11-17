package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G G7 (ZC-083) — with a Keystone account selected, the group screen's announce pass publishes nothing.
 *
 * While a Keystone account is selected the chat is closed on every transport: a group memo carries our address, so
 * none goes over the relays. The group memo funnel refuses, but the member announcer's relay pass calls the relay send
 * directly, and that send has no Keystone check in GroupViewModel (ChatViewModel's has one).
 *
 * Wanted: opening the group on the creator's phone with a Keystone account selected publishes no member announce. With
 * the software account selected the same open publishes them (control).
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupAnnouncePassPublishesNothingOnAKeystoneAccountTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val net = RelayNet()

    private fun world(): GroupLedgerWorld {
        val world = GroupLedgerWorld(scheduler = scheduler)
        listOf(MA3.A, MA3.B, MA3.D).forEach {
            world.admin.base.nostrKeys[it] = MA3.NPUB.getValue(it)
            world.admin.gp1 += it
        }
        return world
    }

    private suspend fun keystoneAccount(): WalletAccount {
        val zero = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))
        return KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-f8g-keystone"), zero),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-f8g-keystone"), Zatoshi(0)),
            isSelected = true,
        )
    }

    @Test
    fun `an announce pass on a Keystone account publishes nothing`() =
        runBlocking {
            val world = world()
            net.register()
            val s = world.screen(fixture = GroupPaidOnceFixture(selectedAccount = ::keystoneAccount), accountSource = null)

            world.open(s)

            assertEquals(
                "member announces went out over the relays while a Keystone account is selected",
                0,
                net.gps().size,
            )
            assertEquals(0, world.seam.proposals.size)
        }

    @Test
    fun `the same pass on the software account publishes the announces`() =
        runBlocking {
            val world = world()
            net.register()
            val s = world.screen()

            world.open(s)

            assertTrue("control: the software account's announce pass published nothing", net.gps().isNotEmpty())
            assertEquals(0, world.seam.proposals.size)
        }
}
