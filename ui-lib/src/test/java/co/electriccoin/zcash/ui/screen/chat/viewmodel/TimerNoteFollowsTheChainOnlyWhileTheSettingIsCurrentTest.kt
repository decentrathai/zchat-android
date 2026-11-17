package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R10 (ZC-112) — a paid disappearing-timer change that never reached the chain is corrected by a note, only while
 * that setting is still the current one.
 *
 * In a Shielded chat the timer travels as one paid on-chain control. When it is stored but never mined, the chat's note
 * keeps saying the timer applies "on both devices", while the contact never got it.
 *
 * Wanted: once the chain proves it can no longer be mined, and while the chat's timer is still that setting, ONE
 * correction note ("set on this phone only") is written under its own id; the setting's own note is not rewritten; the
 * entry is deleted. If the user has set another value meanwhile, nothing is written and the entry is dropped. Nothing
 * is ever re-sent.
 *
 * (The entry is seeded as the timer's sender writes it: the paid timer needs a live ratchet session, which this JVM
 * fixture does not build. That the sender writes it is checked by the source scan that rides with the fix.)
 *
 * Every address, key and transaction id is synthetic.
 */
class TimerNoteFollowsTheChainOnlyWhileTheSettingIsCurrentTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun LedgerWorld.seedTimer(
        since: Long,
        number: Int,
    ) {
        assertTrue("this build keeps no record of a paid timer's send attempt", ledger.exists)
        val id = SendAttemptHarness.txId(number)
        wallet.txs = wallet.txs + SendAttemptHarness.ChainTx(number, peer, null, null, seam.expiry, amount = 1_000L)
        ledger.seedLive(
            "TIMER",
            peer,
            since.toString(),
            startedAt = since + 1,
            attemptHeight = LedgerWorld.H,
            accountHash = accountHash(),
            txIds = listOf(id),
            expiries = mapOf(id to seam.expiry),
        )
    }

    @Test
    fun `while the setting is current one correction note is written under its own id and the setting's note is untouched`() =
        runBlocking {
            val world = LedgerWorld(mode = ConversationMode.VAULT, exchangeComplete = true)
            val vm = world.openVm()
            val since = 1_760_000_000_000L
            world.prefs.timers[world.peer] = ZchatPreferences.DisappearingTtl(3_600L, since)
            val settingNoteId = SysNotes.ttlNoteId(world.peer, since)
            val settingNote =
                ZchatPreferences.PendingMessageData(
                    id = settingNoteId,
                    text = "synthetic setting note",
                    timestampMillis = since,
                    peerAddress = world.peer,
                    isOutgoing = false,
                    isPending = false,
                    status = "SENT",
                )
            world.prefs.rows[settingNoteId] = settingNote
            world.seedTimer(since, 8_801)

            world.twoPassesPastExpiry(vm)
            world.pass(vm, world.wallet.scanned + 1)

            assertEquals("the setting's own note is not rewritten", settingNote, world.prefs.rows[settingNoteId])
            val rows = world.prefs.rows
            val others = rows.values.filter { it.id != settingNoteId }
            assertEquals("one correction note, written once", 1, others.size)
            assertEquals(TestChatNoteCopy.get(co.electriccoin.zcash.ui.R.string.chat_note_timer_this_phone_only), others.single().text)
            assertTrue("the entry is deleted", world.ledger.all().isEmpty())
            assertEquals("nothing is ever re-sent", 0, world.seam.proposals.get())
        }

    @Test
    fun `after the user set another value nothing is written and the entry is dropped`() =
        runBlocking {
            val world = LedgerWorld(mode = ConversationMode.VAULT, exchangeComplete = true)
            val vm = world.openVm()
            val since = 1_760_000_000_000L
            world.seedTimer(since, 8_802)
            // A newer setting.
            world.prefs.timers[world.peer] = ZchatPreferences.DisappearingTtl(60L, since + 5_000L)

            world.twoPassesPastExpiry(vm)

            assertTrue("no note for a setting that is no longer current", world.prefs.rows.isEmpty())
            assertTrue("the stale entry is dropped: ${world.ledger.all()}", world.ledger.all().isEmpty())
            assertEquals(0, world.seam.proposals.get())
        }
}
