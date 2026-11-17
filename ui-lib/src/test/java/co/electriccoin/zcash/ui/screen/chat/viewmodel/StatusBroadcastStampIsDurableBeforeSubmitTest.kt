package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACTS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.CONTACT_C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.STATUS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.peerHash
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.question
import co.electriccoin.zcash.ui.screen.chat.viewmodel.StatusBroadcastChat.Companion.runToEnd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-283 (fix-all 2.13.0, train T5b; r8-findings SPECS §2, S3) — each contact's "may have been sent" stamp is on disk
 * before its status memo is handed to the SDK (R4-1).
 *
 * At 998956906 nothing about a status broadcast is written: a process killed after a memo's submit (or a submit whose
 * failure leaves it "maybe sent") leaves no trace, and setting the same status again pays that contact again.
 *
 * Wanted (SPECS §2.3 (4), §2.4): right before a contact's memo is submitted, the record names that contact (by its hash)
 * for this text; when that write is not saved, that contact's memo is not submitted (nothing sent, nothing paid) and the
 * broadcast goes on to the next contact. Nothing is left recorded for the contact whose stamp was not saved.
 *
 * All addresses and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBroadcastStampIsDurableBeforeSubmitTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        StatusBroadcastChat.resetStatusBroadcastFlag()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        StatusBroadcastChat.resetStatusBroadcastFlag()
    }

    @Test
    fun `each contact is recorded on disk before its status memo reaches the SDK`() =
        runBlocking {
            val chat = StatusBroadcastChat()
            val vm = chat.viewModel(OwnChangeAccounts.create())

            vm.setUserStatus(STATUS, broadcast = true)
            vm.confirmPaidAction()
            runToEnd(vm, chat.heights)

            assertEquals("fixture: each contact submitted once", CONTACTS, chat.seam.submittedTo.toList())
            for (contact in CONTACTS) {
                val atSubmit = chat.recordAtSubmit[contact]
                assertTrue(
                    "$contact's memo reached the SDK with nothing on disk naming it",
                    atSubmit != null && peerHash(contact) in atSubmit,
                )
            }
        }

    @Test
    fun `a contact whose stamp was not saved is not sent and the broadcast goes on`() =
        runBlocking {
            val chat = StatusBroadcastChat()
            // The write that first names B (B's stamp) reports "not saved".
            chat.refuseRecordWrite = { before, value ->
                value != null && peerHash(CONTACT_B) in value && (before == null || peerHash(CONTACT_B) !in before)
            }
            val vm = chat.viewModel(OwnChangeAccounts.create())

            vm.setUserStatus(STATUS, broadcast = true)
            assertEquals("fixture: asked for 3", "STATUS_BROADCAST" to 3, question(vm))
            vm.confirmPaidAction()
            runToEnd(vm, chat.heights)

            assertEquals(
                "B's memo went to the SDK although its stamp was not saved",
                listOf(CONTACT_A, CONTACT_C),
                chat.seam.submittedTo.toList(),
            )
            assertTrue("A and C are recorded", chat.recordWrites.isNotEmpty())
            val onDisk = chat.record ?: ""
            assertTrue("A is recorded", peerHash(CONTACT_A) in onDisk)
            assertTrue("C is recorded", peerHash(CONTACT_C) in onDisk)
            assertFalse("nothing is recorded for B", peerHash(CONTACT_B) in onDisk)
        }
}
