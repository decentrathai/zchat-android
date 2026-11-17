package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.groupSendBannerText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * Round 8 P5 (PHONE_TRIAGE F5a) — removing a member, or rotating the key, while another member has no secure
 * session with us strands that member without a word.
 *
 * The admin (the fixture's SELF) runs a group at key epoch 1 with K ([GroupPaidOnceFixture.MEMBER_P]: a KEX session
 * with us, so the new key can be sent to K) and R ([MEMBER_R]: ACTIVE, holding the current key, and NO session: no
 * pairwise key either way). Neither has a Nostr key, so every memo to them is on-chain.
 *
 * At 2ee0cf1d2, `kickMember(G, K)`:
 * - asks nothing (R is left out of the quote because it has no session, so the quote is empty and runs at once),
 *   moves the group to epoch 2 and raises the removal floor to 2, although R can never be sent the epoch-2 key;
 * - then says R "hasn't received the new group key yet — retrying automatically", while the retry skips a member
 *   with no session and nothing in the app starts a key exchange with R;
 * - and every later group message is quoted, and paid on-chain, for R, who cannot decrypt it (and whose own posts at
 *   epoch 1 are dropped under the floor).
 *
 * Wanted:
 * 1. Before anything is quoted or changed, a blocking question names R (the members that can't be sent the new key);
 *    until it is answered, and after Cancel, the epoch, the roster and the removal floor are unchanged. The same for a
 *    rotation.
 * 2. After "Remove anyway" the removal goes through, and the banner counts R as having no secure session, not as a
 *    member the app is "retrying automatically".
 * 3. A group message is neither quoted nor sent to R while R is owed the key and has no session; the cost line says
 *    one member can't read new messages yet, and a send that reaches nobody else says so instead of "nobody else is
 *    in this group".
 *
 * Controls: a rotation where every key holder has a session asks only the cost question; a member owed the key who
 * HAS a session still gets (and is quoted for) a message.
 *
 * The new question, its answers and the new event / cost fields are read by reflection so this file compiles on the
 * build before them. All addresses, keys and group ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KickStrandsMemberWithoutSessionTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
    private val scheduler = TestCoroutineScheduler()

    /** Every removal floor the view model wrote, in order. */
    private val floorWrites: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private fun fixture(
        withR: Boolean = true,
        kPendingKeyEpoch: Int? = null,
    ): GroupPaidOnceFixture =
        GroupPaidOnceFixture(
            memberPendingKeyEpoch = kPendingKeyEpoch,
            consent = true,
            extraActiveMembers = if (withR) listOf(MEMBER_R) else emptyList(),
        )

    /** The fixture's view model, with its preferences wrapped so the removal floor is recorded. */
    private suspend fun viewModel(f: GroupPaidOnceFixture): GroupViewModel {
        f.scheduler = scheduler
        val vm = f.groupViewModel()
        vms += vm
        val inner = f.proxy
        val recording =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "setGroupRemovalFloor" -> {
                        floorWrites += args!![1] as Int
                        null
                    }
                    "getGroupRemovalFloor" -> floorWrites.lastOrNull() ?: 0
                    else ->
                        try {
                            method.invoke(inner, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, recording)
        vm.consumeGroupSendEvent()
        return vm
    }

    private fun roster(f: GroupPaidOnceFixture): List<GroupMember> = ZMSGGroupProtocol.deserializeGroupMembers(f.rosterJson()!!)

    private fun row(
        f: GroupPaidOnceFixture,
        address: String,
    ): GroupMember = roster(f).single { it.address == address }

    private fun floor(): Int = floorWrites.lastOrNull() ?: 0

    // ── the new question, read by reflection ─────────────────────────────────────────────────────────

    /** The value of the view model's "members can't be sent the new key" question, or null (none, or no such API). */
    private fun unkeyableQuestion(vm: GroupViewModel): Any? =
        vm.javaClass.methods
            .firstOrNull { it.name == "getRekeyUnkeyableConfirmation" && it.parameterCount == 0 }
            ?.invoke(vm)
            ?.let { (it as StateFlow<*>).value }

    @Suppress("UNCHECKED_CAST")
    private fun namesIn(question: Any): List<String> = question.javaClass.getMethod("getNames").invoke(question) as List<String>

    private fun answer(
        vm: GroupViewModel,
        name: String,
    ) {
        val m = vm.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        assertNotNull("GroupViewModel has no $name()", m)
        m!!.invoke(vm)
    }

    private fun intField(
        target: Any,
        getter: String,
    ): Int? =
        target.javaClass.methods
            .firstOrNull { it.name == getter && it.parameterCount == 0 }
            ?.invoke(target) as Int?

    private fun banner(event: GroupSendResult): String =
        groupSendBannerText(
            event,
            { id -> ChatResourceText.english(id) },
            { id, quantity, args -> ChatResourceText.englishPlural(id, quantity, args) },
        )

    /** Kick K; answer "Remove anyway" if the question is asked (it is not, before the fix); accept the cost if asked. */
    private suspend fun kickKAndRemoveAnyway(
        f: GroupPaidOnceFixture,
        vm: GroupViewModel,
    ) {
        f.awaitNewJobs(vm) { vm.kickMember(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }
        if (unkeyableQuestion(vm) != null) f.awaitNewJobs(vm) { answer(vm, "confirmRekeyUnkeyable") }
        if (vm.paidActionConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmPaidAction() }
    }

    // ── 1. the question comes first, and nothing changes before it is answered ───────────────────────

    @Test
    fun `removing K while R has no session first asks a question naming R and changes nothing`() =
        runBlocking {
            val f = fixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.kickMember(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }

            val question = unkeyableQuestion(vm)
            assertNotNull(
                "no question names the member that can't be sent the new key; the removal already ran: epoch=${f.epoch()}, " +
                    "K=${row(f, GroupPaidOnceFixture.MEMBER_P).status}, floor=${floor()}",
                question,
            )
            assertEquals(listOf(R_NAME), namesIn(question!!))
            assertEquals("the epoch moved before the question was answered", 1, f.epoch())
            assertEquals(
                "K was removed before the question was answered",
                MemberStatus.ACTIVE,
                row(f, GroupPaidOnceFixture.MEMBER_P).status,
            )
            assertEquals("the removal floor moved before the question was answered", 0, floor())
            assertNull("the cost is asked before the question", vm.paidActionConfirmation.value)
            assertEquals("fixture: nothing paid", 0, f.submits())
        }

    @Test
    fun `Cancel leaves the epoch, the roster and the removal floor as they were`() =
        runBlocking {
            val f = fixture()
            val vm = viewModel(f)
            val before = f.rosterJson()

            f.awaitNewJobs(vm) { vm.kickMember(GroupPaidOnceFixture.GROUP_ID, GroupPaidOnceFixture.MEMBER_P) }
            assertNotNull("no question was asked: epoch=${f.epoch()}", unkeyableQuestion(vm))
            f.awaitNewJobs(vm) { answer(vm, "dismissRekeyUnkeyable") }

            assertNull("the question is still on screen after Cancel", unkeyableQuestion(vm))
            assertNull("Cancel led to the cost question", vm.paidActionConfirmation.value)
            assertEquals(1, f.epoch())
            assertEquals(before, f.rosterJson())
            assertEquals(0, floor())
            assertTrue("fixture: no floor write at all", floorWrites.isEmpty())
            assertNull("Cancel produced a banner", vm.groupSendEvent.value)
            assertEquals(0, f.submits())
        }

    @Test
    fun `rotating the key while R has no session asks the same question first`() =
        runBlocking {
            val f = fixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }

            val question = unkeyableQuestion(vm)
            assertNotNull("a rotation strands R without a question (the cost question came first)", question)
            assertEquals(listOf(R_NAME), namesIn(question!!))
            assertNull("the cost is asked before the question", vm.paidActionConfirmation.value)
            assertEquals(1, f.epoch())
        }

    // ── 2. after "Remove anyway", R is not "retrying automatically" ──────────────────────────────────

    @Test
    fun `after Remove anyway the banner says R has no secure session and does not claim a retry`() =
        runBlocking {
            val f = fixture()
            val vm = viewModel(f)

            kickKAndRemoveAnyway(f, vm)

            // The removal itself went through.
            assertEquals(2, f.epoch())
            assertEquals(MemberStatus.LEFT, row(f, GroupPaidOnceFixture.MEMBER_P).status)
            assertEquals(2, floor())
            assertEquals("R is owed the new key", 2, row(f, MEMBER_R).pendingKeyEpoch)

            val event = vm.groupSendEvent.value
            assertTrue("fixture: the removal is reported incomplete: $event", event is GroupSendResult.RemovalIncomplete)
            event as GroupSendResult.RemovalIncomplete
            assertEquals("R is counted as a send that failed and is retried", 0, event.pendingMembers)
            assertEquals("R is not counted as a member with no secure session", 1, intField(event, "getNoSessionMembers"))
            val text = banner(event)
            assertFalse("R can't be sent the key, yet the banner says it retries: $text", text.contains("retrying automatically"))
            assertEquals("1 member can't be sent the new key: no secure session", text)
        }

    // ── 3. no message is quoted or sent to R ─────────────────────────────────────────────────────────

    @Test
    fun `after the removal a message is neither quoted nor sent to R, and the cost line says R can't read it`() =
        runBlocking {
            val f = fixture()
            val vm = viewModel(f)
            kickKAndRemoveAnyway(f, vm)
            vm.consumeGroupSendEvent()
            val paidBefore = f.submits()

            val cost = vm.groupMessageCost(GroupPaidOnceFixture.GROUP_ID)
            assertEquals("a message is quoted on-chain for R, who can't decrypt it", 0, cost.onChainMessages)
            assertEquals("R is still counted as a recipient", 0, cost.recipients)
            assertEquals("the cost line does not say a member can't read new messages", 1, intField(cost, "getCannotRead"))

            f.proxy.setGroupDraft(GroupPaidOnceFixture.GROUP_ID, "hello group")
            f.awaitNewJobs(vm) { vm.sendGroupMessage(GroupPaidOnceFixture.GROUP_ID, "hello group") }
            if (vm.paidActionConfirmation.value != null) f.awaitNewJobs(vm) { vm.confirmPaidAction() }

            assertEquals("a group message memo was paid for R", paidBefore, f.submits())
            val event = vm.groupSendEvent.value
            assertTrue("the send that reaches nobody must say so: $event", event is GroupSendResult.NoActiveRecipients)
            val text = banner(event!!)
            assertFalse("R is still in the group, yet the banner says nobody else is: $text", text.contains("nobody else"))
            assertTrue("the banner must name R: $text", text.contains(R_NAME))
            assertEquals("the typed text is kept", "hello group", f.draft())
        }

    // ── controls ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `control - a rotation where every key holder has a session asks only the cost question`() =
        runBlocking {
            val f = fixture(withR = false)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.rotateGroupKey(GroupPaidOnceFixture.GROUP_ID) }

            assertNull("a question about unkeyable members with none of them", unkeyableQuestion(vm))
            assertEquals(1, vm.paidActionConfirmation.value?.onChainMessages)
        }

    @Test
    fun `control - a member owed the key who has a session is still quoted and sent a message`() =
        runBlocking {
            val f = fixture(withR = false, kPendingKeyEpoch = 1)
            val vm = viewModel(f)

            val cost = vm.groupMessageCost(GroupPaidOnceFixture.GROUP_ID)
            assertEquals(1, cost.recipients)
            assertEquals(1, cost.onChainMessages)
        }

    private companion object {
        const val MEMBER_R = "usyntheticp5strandedmemberr01"

        /** How the group screens name R (no nickname, no saved contact): GroupMember.displayName. */
        val R_NAME = "${MEMBER_R.take(8)}...${MEMBER_R.takeLast(6)}"
    }
}
