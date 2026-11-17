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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1979 (Fable review of fix/2130-msg, 16 Nov 2026; register medium; money-path rule 2) — the user deletes a first message
 * that waits for its funds. The Delete hides the id (durably) and removes the row with a commit() whose failure is only
 * logged (ZchatPreferences.removePendingMessage). The row is then still on disk, and resumeFundsWaitRows reads every waiting
 * row without looking at the hidden ids: after a restart a block sends the deleted message and pays its key exchange.
 *
 * Wanted: a waiting row whose id the user hid (deleted) is never resumed or paid, whether or not its removal reached the
 * disk. The control shows the same restart and block with no delete pays the key exchange. Real ChatViewModel send, block
 * tick, delete and restart over [FundsConfirmingChat]; every address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenWaitingRowIsNeverResumedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1979hiddenwaiting01"
        const val TYPED = "synthetic first words deleted while waiting"
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
    fun `control - with no delete the waiting row pays its key exchange after a restart`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val disk = FailingRemoveStore()
            val vm = world.screen().also(::canHideMessages).also(disk::install)
            world.acceptWhileConfirming(vm, TYPED)
            assertEquals("fixture: the Pay paid while the funds confirm", 0, world.seam.submits.get())

            val after = world.restart(vm).also(::canHideMessages).also(disk::install)
            world.plan = "Fundable"
            world.block(after)
            assertTrue(
                "fixture: the waiting row paid nothing after a restart (${HandshakeHarness.sendMessageState(after)})",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= 1 },
            )
        }

    @Test
    fun `a waiting row the user deleted is never resumed or paid even when its removal did not reach the disk`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER)
            val disk = FailingRemoveStore()
            val vm = world.screen().also(::canHideMessages).also(disk::install)
            val row = world.acceptWhileConfirming(vm, TYPED)
            assertEquals("fixture: the Pay paid while the funds confirm", 0, world.seam.submits.get())

            vm.hideMessage(row.id)
            assertTrue("fixture: the Delete did not hide the row's id", row.id in disk.hidden)
            assertNotNull("fixture: the failed removal took the row off the disk", world.row())

            val after = world.restart(vm).also(::canHideMessages).also(disk::install)
            world.plan = "Fundable"
            repeat(BLOCKS) {
                world.block(after)
                world.paymentsSettled()
            }
            assertEquals(
                "a block paid for a waiting message the user deleted (its id hidden, its removal not on disk)",
                0,
                world.seam.submits.get(),
            )
        }

    /**
     * The world's store with a durable hidden-id set and a removal of a pending row whose commit() failed: the row stays
     * on disk, as ZchatPreferences.removePendingMessage leaves it after logging the failure.
     */
    private class FailingRemoveStore {
        val hidden: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private var base: ZchatPreferences? = null

        private val store: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "removePendingMessage" -> null
                    "hideMessage" -> {
                        hidden += args!![0] as String
                        null
                    }
                    "hideMessages" -> {
                        @Suppress("UNCHECKED_CAST")
                        hidden += args!![0] as Set<String>
                        null
                    }
                    "unhideMessage" -> {
                        hidden -= args!![0] as String
                        null
                    }
                    "getHiddenMessageIds" -> HashSet(hidden)
                    "toString" -> "FailingRemoveStore"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else ->
                        try {
                            method.invoke(base!!, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        /** Wraps the store [FundsConfirmingChat.screen] gave [vm]. */
        fun install(vm: ChatViewModel) {
            val field = ChatViewModel::class.java.getDeclaredField("zchatPreferences").apply { isAccessible = true }
            val current = field.get(vm) as ZchatPreferences
            if (current !== store) base = current
            PaymentHarness.setField(vm, "zchatPreferences", store)
        }
    }
}
