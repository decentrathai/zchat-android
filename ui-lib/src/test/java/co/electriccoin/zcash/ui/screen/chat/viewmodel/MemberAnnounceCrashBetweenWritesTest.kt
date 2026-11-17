package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerEntry
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.D
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 9 R9-3 (Round 8b reviewer note 4) — the app dies between the two MA-V3 ledger writes.
 *
 * MA-V3 records a quoted announce pair that went out free over the relays with two compare-and-sets under the group
 * lock: this run's CHAIN_PENDING claim is released (`pending -> null`), then the relay delivery is written
 * (`null -> RELAY_*`). If the process dies after the first write, the pair has no ledger entry and is owed again. Its
 * recipient now has a relay key, so the next pass must re-send it free over the relays and must never pay for it.
 *
 * The creator C has A, B and D, none reachable over the relays. C taps "Introduce them" and accepts the 6 quoted pairs.
 * Right after the first CHAIN_PENDING claim of a pair to A, A's relay key is stored (the MA-V3 race), so that pair goes
 * out over the relays. The store is copied at the moment the first MA-V3 write (`pending -> null`) of that pair reaches
 * the disk: that copy is what the next process starts from. The second write never happened in it.
 *
 * Wanted, in the next process (a new screen over that copy, the announcer's process state reset): opening the group
 * re-sends the pair to A over the relays, records it as a relay delivery, and creates no on-chain proposal; the pair is
 * not in the Introduce quote, and accepting that quote pays nothing to A.
 *
 * Consent GRANTED; proposals are counted on the creator's wallet. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberAnnounceCrashBetweenWritesTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a pair whose relay delivery was not recorded before the app died is re-sent free and never paid`() =
        runBlocking {
            val admin = MA3.adminPhone(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, D to MemberStatus.ACTIVE))
            listOf(A, B, D).forEach { MA3.memberPhone(it, admin) }
            net.register()
            val seam = AnnounceSeam()
            val f = GroupPaidOnceFixture().apply { scheduler = this@MemberAnnounceCrashBetweenWritesTest.scheduler }
            val vm = MA3.groupVm(admin, f, seam)

            var raced: AnnounceLedgerEntry? = null
            var afterDeath: AnnouncerPhone? = null
            var relayCopiesAtDeath = 0
            val store = admin.proxy
            val dying =
                Proxy.newProxyInstance(
                    ZchatPreferences::class.java.classLoader,
                    arrayOf(ZchatPreferences::class.java),
                ) { _, method, args ->
                    val result =
                        try {
                            method.invoke(store, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    if (method.name == "casGroupAnnounce" && result == true) {
                        val expected = args!![2] as AnnounceLedgerEntry?
                        val next = args[3] as AnnounceLedgerEntry?
                        val claimed = raced
                        if (claimed == null && next?.state == AnnounceLedgerState.CHAIN_PENDING && next.rAddr == A) {
                            // The MA-V3 race: A becomes reachable over the relays after the claim, before the relay try.
                            raced = next
                            admin.base.nostrKeys[A] = NPUB.getValue(A)
                        } else if (afterDeath == null && claimed != null && expected == claimed && next == null) {
                            // The first MA-V3 write reached the disk; the app dies before the second one.
                            afterDeath = copyOf(admin)
                            relayCopiesAtDeath = relayCopies(claimed.sAddr)
                        }
                    }
                    result
                } as ZchatPreferences
            GroupViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .set(vm, dying)

            MA3.load(vm, f)
            assertTrue("GroupViewModel has no introduceMembers", MA3.callGroupVm(vm, f, "introduceMembers"))
            assertEquals("the quote does not count the 6 pairs", 6, vm.paidActionConfirmation.value?.onChainMessages)
            MA3.confirm(vm, f)

            assertNotNull("no pair to A was claimed for payment", raced)
            val subject = raced!!.sAddr
            val restarted = afterDeath
            assertNotNull("the pair to A was never released from CHAIN_PENDING for its relay delivery (MA-V3's first write)", restarted)
            restarted!!
            assertEquals(
                "fixture sanity: in the store the next process starts from, the pair has no ledger entry",
                0,
                restarted.ledger().count { it.rAddr == A && it.sAddr == subject },
            )
            assertEquals("fixture sanity: the pair went out over the relays before the app died", 1, relayCopiesAtDeath)
            assertTrue(
                "fixture sanity: the pair was not paid on-chain before the app died",
                seam.proposals.none { it.recipient == A && MA3.parseGp(it.memo)?.subject == subject },
            )

            // The next process: the announcer's process state is new, the screen is new, the store is the copy.
            MA3.resetRuntime()
            val seam2 = AnnounceSeam()
            val f2 = GroupPaidOnceFixture().apply { scheduler = this@MemberAnnounceCrashBetweenWritesTest.scheduler }
            val vm2 = MA3.groupVm(restarted, f2, seam2)
            MA3.load(vm2, f2)

            assertEquals("the next pass paid on-chain: ${seam2.proposals.map { it.recipient }}", 0, seam2.proposals.size)
            assertTrue(
                "the next pass did not re-send the pair to A over the relays",
                relayCopies(subject) > relayCopiesAtDeath,
            )
            val entry = restarted.ledger().singleOrNull { it.rAddr == A && it.sAddr == subject }
            assertNotNull("the re-sent pair to A has no ledger entry", entry)
            assertTrue(
                "the re-sent pair to A is not recorded as a relay delivery: ${entry!!.state}",
                entry.state == AnnounceLedgerState.RELAY_UNCONFIRMED || entry.state == AnnounceLedgerState.RELAY_FINAL,
            )

            // Introduce again: nothing to A is quoted or paid (A is reachable over the relays now).
            assertTrue(MA3.callGroupVm(vm2, f2, "introduceMembers"))
            if (vm2.paidActionConfirmation.value != null) MA3.confirm(vm2, f2)
            assertNull("a question is still open after the second tap", vm2.paidActionConfirmation.value)
            assertTrue(
                "a pair to A was paid on-chain after the restart: ${seam2.proposals.map { it.recipient to MA3.parseGp(it.memo)?.subject }}",
                seam2.proposals.none { it.recipient == A },
            )
        }

    /** The relay `A` announces of [subject] published to A so far. */
    private fun relayCopies(subject: String): Int = net.gps(NPUB.getValue(A)).count { it.op == AnnounceOp.A && it.subject == subject }

    /** A copy of every store of [phone] as it is now: what a process started after this moment reads. */
    private fun copyOf(phone: AnnouncerPhone): AnnouncerPhone {
        val copy = AnnouncerPhone(phone.self)
        copyStores(phone.base, copy.base, AnnouncePhone::class.java)
        copyStores(phone, copy, AnnouncerPhone::class.java)
        return copy
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyStores(
        from: Any,
        to: Any,
        type: Class<*>,
    ) {
        for (field in type.declaredFields) {
            field.isAccessible = true
            val source = field.get(from)
            val target = field.get(to)
            when {
                source is ConcurrentHashMap<*, *> && target is ConcurrentHashMap<*, *> ->
                    (target as MutableMap<Any?, Any?>).putAll(source as Map<Any?, Any?>)
                source is MutableSet<*> && target is MutableSet<*> && source !== target ->
                    (target as MutableSet<Any?>).addAll(source)
            }
        }
    }
}
