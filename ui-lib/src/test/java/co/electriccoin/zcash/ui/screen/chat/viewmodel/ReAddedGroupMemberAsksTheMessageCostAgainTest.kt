package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.SelfAddressRegistry
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-130 (fix-all 2.13.0, train T5b; high-items SPECS §2.2 case 1, the owner's case) — a member who left the group and
 * was added back is paid for by the next group message with no question.
 *
 * Member B has no free relay route, so each group message pays B an on-chain memo (about 17,000 zatoshi with the fee).
 * The user agreed to that once (R5-1: one consent per group). B leaves; no message is sent while B is out; the admin
 * adds B back and B joins. At 878158714 the stored consent still names B, so the next message pays B at once. A cut
 * made only when a message is sent cannot catch this: no message was sent while B was out.
 *
 * Wanted: the next message asks again (GROUP_MESSAGE, 2 on-chain) and nothing is paid before the answer.
 *
 * Runs the real GroupViewModel over [PaidGroupFixture], with the roster and the consent calls routed to the real
 * [ZchatPreferencesImpl] store ([realPreferences]) seeded with the fixture's roster, as KexFloorWaitsForAPutOffOfferTest
 * routes the KEX calls. Every address and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReAddedGroupMemberAsksTheMessageCostAgainTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()

    // sendGroupMessage pauses 500 ms between members; virtual time is advanced while a test waits.
    private val scheduler = TestCoroutineScheduler()

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

    @Test
    fun `a member who left and was added back with no message in between is asked about again`() =
        runBlocking {
            val f = PaidGroupFixture()
            val store = storeFor(f)
            val vm = viewModel(f, store)

            agreeToTheFirstMessage(f, vm)
            assertEquals(
                "fixture: the consent names both members",
                setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B),
                store.getGroupMessageSpendConsent(PaidGroupFixture.GROUP_ID),
            )

            // B leaves, then is added back and joins. No message is sent in between.
            store.saveGroupMembers(PaidGroupFixture.GROUP_ID, rosterWithB(store, MemberStatus.LEFT))
            store.saveGroupMembers(PaidGroupFixture.GROUP_ID, rosterWithB(store, MemberStatus.ACTIVE))

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 second") }

            assertEquals(
                "the next message paid for a member who left and came back, with no question",
                2,
                f.seam.proposalRequests.get(),
            )
            val question = vm.paidActionConfirmation.value
            assertNotNull("the member who came back must be asked about again", question)
            assertEquals("asked as a group message", "GROUP_MESSAGE", question!!.action.name)
            assertEquals("the question covers both paid members", 2, question.onChainMessages)

            vm.dismissPaidAction()
            assertEquals("declining pays nothing", 2, f.seam.proposalRequests.get())
        }

    @Test
    fun `control - with nobody leaving the next message asks nothing`() =
        runBlocking {
            val f = PaidGroupFixture()
            val store = storeFor(f)
            val vm = viewModel(f, store)

            agreeToTheFirstMessage(f, vm)
            store.saveGroupMembers(PaidGroupFixture.GROUP_ID, rosterWithB(store, MemberStatus.ACTIVE))

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 second") }

            assertNull("an unchanged paid set must not ask again", vm.paidActionConfirmation.value)
            assertEquals("the second message is sent to both members", 4, f.seam.proposalRequests.get())
        }

    private suspend fun agreeToTheFirstMessage(
        f: PaidGroupFixture,
        vm: GroupViewModel,
    ) {
        f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "ZC-130 first") }
        assertEquals("fixture: the first message is asked about", 2, vm.paidActionConfirmation.value?.onChainMessages)
        f.awaitNewJobs(vm) { vm.confirmPaidAction() }
        assertEquals("fixture: the first message paid both members", 2, f.seam.proposalRequests.get())
    }

    private suspend fun viewModel(
        f: PaidGroupFixture,
        store: ZchatPreferences,
    ): GroupViewModel {
        f.scheduler = scheduler
        val vm = f.groupViewModel().also { vms += it }
        GroupViewModel::class.java
            .getDeclaredField("zchatPreferences")
            .apply { isAccessible = true }
            .set(vm, routed(f.proxy, store))
        return vm
    }

    /** The fixture's preferences, with the roster and the message-spend consent calls answered by [store]. */
    private fun routed(
        rest: ZchatPreferences,
        store: ZchatPreferences,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            val target = if (method.name in STORE_CALLS) store else rest
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences

    private fun rosterWithB(
        store: ZchatPreferences,
        status: MemberStatus,
    ): String =
        ZMSGGroupProtocol.serializeGroupMembers(
            ZMSGGroupProtocol
                .deserializeGroupMembers(store.getGroupMembers(PaidGroupFixture.GROUP_ID)!!)
                .map { if (it.address == PaidGroupFixture.MEMBER_B) it.copy(status = status) else it },
        )

    private companion object {
        val STORE_CALLS =
            setOf(
                "getGroupMembers",
                "saveGroupMembers",
                "saveGroupMembersDurably",
                "getGroupMessageSpendConsent",
                "setGroupMessageSpendConsent",
            )

        /** A real store that knows our address, holding the fixture's roster. */
        fun storeFor(f: PaidGroupFixture): ZchatPreferencesImpl {
            val (store, files) = realPreferences()
            ZchatPreferencesImpl::class.java
                .getDeclaredField("selfAddresses\$delegate")
                .apply { isAccessible = true }
                .set(store, lazyOf(SelfAddressRegistry(files.getValue("prefs"))))
            store.registerSelfAddress(PaidGroupFixture.SELF)
            store.saveGroupMembers(PaidGroupFixture.GROUP_ID, f.rosterJson())
            return store
        }
    }
}
