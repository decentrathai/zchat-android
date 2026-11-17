package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1991 (review of fix/2130-msg, 17 Nov 2026; register medium; money-path rule 2) — the ZC-1976 run: a first message
 * waiting for its funds is resumed at a block, the user deletes it while the run takes the quoted ZBOOT, the run writes the
 * allowance and then stops for the deleted row (stopForDeletedRow). Whether the allowance goes is judged by
 * anotherUnsentRowNeedsSetup over the stored rows that stop reads. Two wrong answers:
 * (a) the stored rows cannot be read: `getOrDefault(emptyList())` reads that as "no other message needs it" and withdraws the
 *     allowance, though another unsent message of the contact may still need it;
 * (b) another unsent message of the contact was deleted on this screen (hideMessage) but its removal never reached the store
 *     (the store's hide write was lost, so its row is still stored and not in the stored hidden ids): it still counts as
 *     needing the allowance, so the allowance stands for a contact the user deleted every message of.
 *
 * Wanted: (a) an unreadable list keeps the allowance; (b) a row hidden on this screen does not count, so the allowance goes.
 * The control shows the stop sees the other row the store lists and keeps the allowance for it. The other row exists only in
 * the reads the stop makes (found by its stack), so nothing else sends it; nothing is paid in any case. Real ChatViewModel
 * send, block tick and delete over [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopForDeletedRowJudgesOtherRowsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1991stopjudgesrows01"
        const val TYPED = "synthetic first words deleted before the allowance"
        const val OTHER_ID = "pending_synthetic_zc1991_other_row"
        const val OTHER_TEXT = "synthetic other unsent words"
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `control - another unsent row of the contact keeps the allowance when the deleted row's run stops`() =
        runBlocking {
            val (world, _, store) = stoppedRun(StopRead.WITH_OTHER_ROW)
            assertTrue("fixture: the stop never read the stored rows", store.stopReads.isNotEmpty())
            assertTrue(
                "fixture: the allowance was withdrawn though another unsent row needs it (writes ${store.quotedWrites})",
                PEER in store.quoted,
            )
            assertEquals("something was paid for a message the user deleted", 0, world.seam.submits.get())
        }

    @Test
    fun `an unreadable list of stored rows keeps the ZBOOT allowance`() =
        runBlocking {
            val (world, _, store) = stoppedRun(StopRead.UNREADABLE)
            assertTrue("fixture: the stop never tried to read the stored rows", store.stopReads.isNotEmpty())
            assertTrue(
                "an unreadable list of stored rows was taken as no other message needing the ZBOOT allowance: " +
                    "it was withdrawn (writes ${store.quotedWrites})",
                PEER in store.quoted,
            )
            assertEquals("something was paid for a message the user deleted", 0, world.seam.submits.get())
        }

    @Test
    fun `another row the user deleted on this screen does not keep the ZBOOT allowance`() =
        runBlocking {
            val (world, _, store) = stoppedRun(StopRead.WITH_OTHER_ROW, hideOtherFirst = true)
            assertTrue("fixture: the stop never read the stored rows", store.stopReads.isNotEmpty())
            assertTrue("fixture: the other row's hide reached the store", OTHER_ID !in world.chat.prefs.getHiddenMessageIds())
            assertFalse(
                "a row the user deleted on this screen (its removal not on disk) still counted as needing the ZBOOT " +
                    "allowance: it stands for a contact with no message left (writes ${store.quotedWrites})",
                PEER in store.quoted,
            )
            assertEquals("something was paid for a message the user deleted", 0, world.seam.submits.get())
        }

    private enum class StopRead { WITH_OTHER_ROW, UNREADABLE }

    /**
     * The ZC-1976 run: a waiting first message, resumed at a block and deleted inside its read of the ZBOOT allowance; the
     * stop's reads of the stored rows answer as [stopRead]. With [hideOtherFirst] the other row was deleted on this screen
     * first, its store write lost. Returns once the run stopped and payments settled.
     */
    private suspend fun stoppedRun(
        stopRead: StopRead,
        hideOtherFirst: Boolean = false,
    ): Triple<FundsConfirmingChat, ChatViewModel, StopStore> {
        val world = FundsConfirmingChat(PEER)
        val vm = world.screen().also(::canHideMessages)
        val store = StopStore(vm, stopRead)
        val row = world.acceptWhileConfirming(vm, TYPED)
        assertEquals("fixture: the Pay paid while the funds confirm", 0, world.seam.submits.get())
        if (hideOtherFirst) vm.hideMessage(OTHER_ID)
        store.deleteAt = row.id
        world.plan = "Fundable"
        world.block(vm)
        assertTrue(
            "fixture: the resumed send never read its ZBOOT allowance (${HandshakeHarness.sendMessageState(vm)})",
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { store.deleted },
        )
        assertTrue(
            "fixture: the run never wrote the ZBOOT allowance (writes ${store.quotedWrites})",
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { PEER to true in store.quotedWrites },
        )
        assertTrue(
            "fixture: the run never stopped for the deleted row (${HandshakeHarness.sendMessageState(vm)})",
            PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { store.stopReads.isNotEmpty() },
        )
        world.paymentsSettled()
        return Triple(world, vm, store)
    }

    /**
     * The screen's store with the per-contact ZBOOT allowance kept ([quoted], every write in [quotedWrites]), as in
     * DeleteBeforeTheZbootAllowanceIsWrittenLeavesNoneTest: while [deleteAt] names a live row, the first read of the
     * allowance made by takeUnsentZbootQuote deletes that row on the screen first. The stored-row reads made by
     * stopForDeletedRow (counted in [stopReads]) also list [OTHER_ID], another unsent first message of the contact, or fail
     * ([StopRead.UNREADABLE]); the store's hide of [OTHER_ID] is lost.
     */
    private class StopStore(
        private val vm: ChatViewModel,
        private val stopRead: StopRead,
    ) {
        val quoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val quotedWrites: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())
        val stopReads: MutableList<StopRead> = Collections.synchronizedList(mutableListOf())
        private val accounts = ConcurrentHashMap<String, String>()

        @Volatile var deleteAt: String? = null

        @Volatile var deleted: Boolean = false

        private val base: ZchatPreferences =
            ChatViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .get(vm) as ZchatPreferences

        private val otherRow =
            ZchatPreferences.PendingMessageData(
                id = OTHER_ID,
                text = OTHER_TEXT,
                timestampMillis = 1L,
                peerAddress = PEER,
                isOutgoing = true,
                isPending = true,
                status = "SENDING",
            )

        private val store: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                fun arg(i: Int) = args!![i]
                when (method.name) {
                    "isZbootQuoted" -> {
                        deleteIfTakingTheQuote(arg(0) as String)
                        arg(0) in quoted
                    }
                    "setZbootQuoted" -> {
                        val at = arg(0) as String
                        val on = arg(1) as Boolean
                        quotedWrites += at to on
                        if (on) quoted += at else quoted -= at
                        true
                    }
                    "getZbootQuotedAccount" -> accounts[arg(0) as String]
                    "setZbootQuotedAccount" -> {
                        val key = arg(1) as String?
                        if (key == null) accounts.remove(arg(0) as String) else accounts[arg(0) as String] = key
                        true
                    }
                    "hideMessage" ->
                        if (arg(0) == OTHER_ID) Unit else base.hideMessage(arg(0) as String)
                    "getPendingMessages" -> pendingMessages()
                    "toString" -> "StopStore"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else ->
                        try {
                            method.invoke(base, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        init {
            PaymentHarness.setField(vm, "zchatPreferences", store)
        }

        private fun pendingMessages(): List<ZchatPreferences.PendingMessageData> {
            val stored = base.getPendingMessages()
            if (Thread.currentThread().stackTrace.none { it.methodName == "stopForDeletedRow" }) return stored
            stopReads += stopRead
            return when (stopRead) {
                StopRead.UNREADABLE -> throw IllegalStateException("synthetic unreadable pending list")
                StopRead.WITH_OTHER_ROW -> stored + otherRow
            }
        }

        private fun deleteIfTakingTheQuote(peer: String) {
            val rowId = deleteAt ?: return
            if (peer != PEER || deleted || !LiveSenders.isLive(rowId)) return
            if (Thread.currentThread().stackTrace.none { it.methodName == "takeUnsentZbootQuote" }) return
            deleted = true
            vm.hideMessage(rowId)
        }
    }
}
