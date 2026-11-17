package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1997 (GPT review of fix/2130-msg at c5d86ba62, Important; Fable review finding 3; money-path rule 2) — the ZC-1992 guard
 * of a block's owed-KEX payment (payOwedKex(forSend = false): owedKexOnlyForDeletedRows) answers "may pay" when a store read
 * fails, and reads the store's hidden list before the process-wide deleted set ([LiveSenders.isHidden]). A first message to a
 * new contact failed in its key exchange's build, so its row is FAILED and the KEX owed for it; the user deletes it while the
 * store's hidden list cannot be read, so Delete (cancelKexOwedOnlyFor) cannot judge it and the mark stays.
 *
 * Wanted: a block pays nothing for that first message's KEX
 * - (a) when the delete is recorded only in the process-wide set (another screen deleted it; its store write was lost) and
 *   the store's hidden list still cannot be read;
 * - (b) in a new process (the process-wide set is gone), the delete on disk, while the store's hidden list cannot be read.
 * Control: a lone Reset KEX mark of a contact whose only unsent row is deleted is still paid by a block under the same
 * failing read (owedKexIsLone exempts it). Real ChatViewModel send, delete and block tick over [FundsConfirmingChat]; every
 * address, key and txid is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockOwedKexForDeletedRowFailsClosedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1997guardfailsclosed01"
        const val TYPED = "synthetic first words failed then deleted"
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
    fun `control - a lone Reset key exchange is still paid by a block when the contact's only row is deleted`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
            val (vm, rowId) = firstMessageFailedInItsKex(world, TYPED)
            val faults = StoreFaults()
            vm.overStore(faults)
            faults.reasons[PEER] = ZchatPreferences.KEX_OWED_REASON_RESET
            vm.hideMessage(rowId)
            assertTrue("fixture: Delete took a Reset's owed KEX", PEER in world.chat.kexOwed)
            val fresh = world.restart(vm).also(::canHideMessages).overStore(faults)
            faults.failing += GET_HIDDEN
            world.block(fresh)
            assertTrue(
                "a block no longer pays a lone Reset key exchange (${world.seam.submits.get()} submits)",
                PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) { world.seam.submits.get() >= 1 },
            )
        }

    @Test
    fun `a block pays nothing for a deleted first message known only to the process-wide set when the store cannot be read`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
            val (a, rowId) = firstMessageFailedInItsKex(world, TYPED)
            val b = world.screen().also(::canHideMessages)
            val faults = StoreFaults()
            a.overStore(faults)
            b.overStore(faults)
            faults.failing += GET_HIDDEN
            faults.dropped += "hideMessage"
            b.hideMessage(rowId)
            assertTrue("fixture: the delete is not in the process-wide set", LiveSenders.isHidden(rowId))
            assertFalse("fixture: the delete reached the store", rowId in faults.hidden)
            assertTrue("fixture: Delete cleared the mark although it could not read the hidden list", PEER in world.chat.kexOwed)
            repeat(BLOCKS) {
                world.block(a)
                world.paymentsSettled()
            }
            assertEquals(
                "a block paid the key exchange of a first message the user deleted on another screen " +
                    "(the guard's store read failed and it did not ask the process-wide deleted set first)",
                0,
                world.seam.submits.get(),
            )
        }

    @Test
    fun `a block in a new process pays nothing for a deleted first message when the store's hidden list cannot be read`() =
        runBlocking {
            val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
            val (vm, rowId) = firstMessageFailedInItsKex(world, TYPED)
            val faults = StoreFaults()
            vm.overStore(faults)
            faults.failing += GET_HIDDEN
            vm.hideMessage(rowId)
            assertTrue("fixture: the delete did not reach the store", rowId in faults.hidden)
            assertTrue("fixture: Delete cleared the mark although it could not read the hidden list", PEER in world.chat.kexOwed)
            val fresh = world.restart(vm).also(::canHideMessages).overStore(faults)
            assertFalse("fixture: the new process still holds the delete in memory", LiveSenders.isHidden(rowId))
            repeat(BLOCKS) {
                world.block(fresh)
                world.paymentsSettled()
            }
            assertEquals(
                "a block paid the key exchange of a first message the user deleted, because the guard's store read failed",
                0,
                world.seam.submits.get(),
            )
        }
}

/** The store method a failing hidden-list read throws from. */
internal const val GET_HIDDEN = "getHiddenMessageIds"

/**
 * ZC-1997..ZC-1999: what a screen's store does on top of [FundsConfirmingChat]'s: it keeps the hidden list, the owed KEX's
 * reason and whether the exchange is complete, throws on each method named in [failing], and drops each write in [dropped].
 */
internal class StoreFaults {
    val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val dropped: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val hidden: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val reasons = ConcurrentHashMap<String, String>()

    @Volatile var exchangeComplete: Boolean = false
}

/** This screen's store, wrapped by [faults] (call again after every [FundsConfirmingChat.screen] or restart). */
@Suppress("UNCHECKED_CAST")
internal fun ChatViewModel.overStore(faults: StoreFaults): ChatViewModel {
    val inner = storeOf(this)
    val wrapped =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name in faults.failing) throw IllegalStateException("synthetic store failure: ${method.name}")
            when {
                method.name in faults.dropped -> if (method.returnType == java.lang.Boolean.TYPE) false else null
                method.name == "hideMessage" -> {
                    faults.hidden += args!![0] as String
                    null
                }
                method.name == "hideMessages" -> {
                    faults.hidden += args!![0] as Set<String>
                    null
                }
                method.name == GET_HIDDEN -> HashSet(faults.hidden)
                method.name == "getKexOwedReason" -> faults.reasons[args!![0] as String]
                method.name == "setKexOwedReason" -> {
                    val reason = args!![1] as String?
                    if (reason == null) faults.reasons.remove(args[0] as String) else faults.reasons[args[0] as String] = reason
                    true
                }
                method.name == "isE2EKeyExchangeComplete" && faults.exchangeComplete -> true
                else ->
                    try {
                        method.invoke(inner, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
    PaymentHarness.setField(this, "zchatPreferences", wrapped)
    return this
}

/** The store [vm] reads now. */
internal fun storeOf(vm: ChatViewModel): ZchatPreferences =
    ChatViewModel::class.java
        .getDeclaredField("zchatPreferences")
        .apply { isAccessible = true }
        .get(vm) as ZchatPreferences

/**
 * ZC-1997..ZC-1999: in [world] (plan Fundable) a first message to its peer is accepted and its key exchange's build (build 1)
 * fails: the row stands FAILED, nothing is paid, the KEX is owed for it. Returns the screen and the row's id.
 */
internal suspend fun firstMessageFailedInItsKex(
    world: FundsConfirmingChat,
    typed: String,
): Pair<ChatViewModel, String> {
    world.funds.parkFirstBuild = true
    val vm = world.screen().also(::canHideMessages)
    world.chat.typeAndSend(vm, typed)
    world.question(vm)
    vm.confirmPaidAction()
    withTimeout(HandshakeHarness.TIMEOUT_MS) { world.funds.firstBuildEntered.await() }
    world.funds.firstBuildGate.completeExceptionally(IllegalStateException("synthetic build failure"))
    val rowId = world.row()?.id
    assertNotNull("fixture: the first message left no row", rowId)
    assertTrue(
        "fixture: the failed first message's row is not FAILED in memory (${world.row()})",
        PaymentHarness.eventually(FundsConfirmingChat.SETTLE_MS) {
            PaymentHarness.pendingMessages(vm).any { it.id == rowId && it.status == MessageStatus.FAILED }
        },
    )
    world.paymentsSettled()
    assertEquals("fixture: the failed first message paid something", 0, world.seam.submits.get())
    assertTrue("fixture: the failed first message left no owed KEX", world.chat.peer in world.chat.kexOwed)
    return vm to rowId!!
}
