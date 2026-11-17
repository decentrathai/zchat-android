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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1976 (Fable review of fix/2130-msg, 16 Nov 2026; register medium; money-path rule 2) — a first message waiting for its
 * funds is resumed at a block. Its send passes ZC-1959's re-read after the plan check, then takes the quoted ZBOOT
 * (takeUnsentZbootQuote: it reads whether an allowance stands, reads the selected account, then writes the allowance). A
 * Delete that lands between the re-read and that write finds no allowance to withdraw; the run then writes it, and the
 * next re-read stops the run for the deleted row (stopForDeletedRow) without withdrawing it. A ZBOOT allowance stands for a
 * contact the user deleted the only message of, so a later KEXACK or inbound ZBOOT may pay on it.
 *
 * Wanted: after such a run nothing stays: no ZBOOT allowance for the contact, and nothing paid. The Delete is made inside the
 * run's own read of the allowance (the read takeUnsentZbootQuote makes, found by its stack), the point the review names. The
 * control shows the same resume with no delete writes the allowance (the fixture reaches the write). Real ChatViewModel
 * send, block tick and delete over [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteBeforeTheZbootAllowanceIsWrittenLeavesNoneTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1976deletezbootquote01"
        const val TYPED = "synthetic first words deleted before the allowance"
        const val BLOCKS = 2
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
    fun `control - with no delete the resumed send writes the ZBOOT allowance and pays`() =
        runBlocking {
            val (world, vm, store) = waitingRow()
            store.deleteAt = null
            world.plan = "Fundable"
            world.block(vm)
            assertTrue(
                "fixture: the resumed row paid nothing (${HandshakeHarness.sendMessageState(vm)})",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= 1 },
            )
            assertTrue("fixture: the resumed send wrote no ZBOOT allowance", PEER to true in store.quotedWrites)
        }

    @Test
    fun `a row deleted while its send takes the quoted ZBOOT leaves no ZBOOT allowance behind`() =
        runBlocking {
            val (world, vm, store) = waitingRow()
            world.plan = "Fundable"
            world.block(vm)
            assertTrue(
                "fixture: the resumed send never read its ZBOOT allowance (${HandshakeHarness.sendMessageState(vm)})",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { store.deleted },
            )
            world.paymentsSettled()
            assertNull("fixture: the deleted row is still on disk", world.row())

            assertFalse(
                "a ZBOOT allowance stands for a contact whose only message the user deleted (writes ${store.quotedWrites})",
                PEER in store.quoted,
            )
            assertEquals("something was paid for a message the user deleted", 0, world.seam.submits.get())
            repeat(BLOCKS) {
                world.block(vm)
                world.paymentsSettled()
            }
            assertEquals("a later block paid for the deleted message", 0, world.seam.submits.get())
            assertFalse("a later block left a ZBOOT allowance for the deleted message", PEER in store.quoted)
        }

    /** A first message paid while its funds confirm (a waiting row), on a store that keeps the ZBOOT allowance. */
    private suspend fun waitingRow(): Triple<FundsConfirmingChat, ChatViewModel, ZbootAllowanceStore> {
        val world = FundsConfirmingChat(PEER)
        val vm = world.screen().also(::canHideMessages)
        val store = ZbootAllowanceStore(vm)
        val row = world.acceptWhileConfirming(vm, TYPED)
        assertEquals("fixture: the Pay paid while the funds confirm", 0, world.seam.submits.get())
        assertFalse("fixture: the waiting Pay wrote the allowance already", PEER in store.quoted)
        store.deleteAt = row.id
        return Triple(world, vm, store)
    }

    /**
     * The screen's store with the per-contact ZBOOT allowance kept ([quoted], every write in [quotedWrites]). While
     * [deleteAt] names a live row, the first read of the allowance made by takeUnsentZbootQuote deletes that row on the
     * screen (hideMessage) before it answers, as a Delete landing in that read's IO hop does.
     */
    private class ZbootAllowanceStore(
        private val vm: ChatViewModel,
    ) {
        val quoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val quotedWrites: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())
        private val accounts = ConcurrentHashMap<String, String>()

        @Volatile var deleteAt: String? = null

        @Volatile var deleted: Boolean = false

        private val base: ZchatPreferences =
            ChatViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .get(vm) as ZchatPreferences

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
                    "toString" -> "ZbootAllowanceStore"
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

        private fun deleteIfTakingTheQuote(peer: String) {
            val rowId = deleteAt ?: return
            if (peer != PEER || deleted || !LiveSenders.isLive(rowId)) return
            if (Thread.currentThread().stackTrace.none { it.methodName == "takeUnsentZbootQuote" }) return
            deleted = true
            vm.hideMessage(rowId)
        }
    }
}
