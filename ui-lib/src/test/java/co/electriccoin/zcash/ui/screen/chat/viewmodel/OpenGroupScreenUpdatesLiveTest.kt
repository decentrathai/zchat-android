package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.ADMIN
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Z
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Round 9 R9-5 (REVIEW_R8 F10 = device D-F4) — the open group screen follows what ChatViewModel stores.
 *
 * At 3bcbc11ab GroupViewModel.loadGroupDetail runs only when the screen opens (AndroidChat's LaunchedEffect) and after
 * this screen's own actions. ChatViewModel writes the group stores (ZchatPreferences.saveGroupMessages,
 * saveGroupMembers, saveGroupAnnounceState) when a message or an accept arrives, and nothing tells the open screen. On
 * the member-announce gate runs the creator's screen still said "No messages yet" 20 s after the phone had decrypted a
 * message, and "Waiting for 1 invited member" 1.5 min after the accept.
 *
 * Wanted:
 * 1. With a GroupViewModel showing group G, a ChatViewModel stores an incoming GROUP_MSG from an invitee (which also
 *    turns the invitee ACTIVE) and one from a member not listed yet. With no further call, and within the 250 ms
 *    debounce, the screen's conversation lists both messages and both members ACTIVE.
 * 2. Control: a write for another group does not reload G.
 * 3. ZchatPreferencesImpl's group saves (messages, members, the durable members write, the announce state) each mark
 *    the group's store as changed (ZchatPreferences.markGroupStoreChanged), which is what the screen follows. The
 *    store's fake here marks it the same way when that API exists, so (1) and (2) run against the real signal.
 *
 * Consent GRANTED; nothing is proposed. All addresses, keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenGroupScreenUpdatesLiveTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** Let [ms] of screen time pass (the debounce is 250 ms). */
    private fun pass(ms: Long = 1_000) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun shown(vm: GroupViewModel): GroupConversation =
        (vm.groupDetailState.value as? GroupDetailState.Success)?.conversation
            ?: throw AssertionError("the group screen is not showing a group: ${vm.groupDetailState.value}")

    private fun texts(c: GroupConversation): List<String?> = c.messages.map(GroupMessage::decryptedContent)

    private fun status(
        c: GroupConversation,
        address: String
    ): MemberStatus? = c.members.firstOrNull { it.address == address }?.status

    @Test
    fun `the open group screen lists a stored group message and a member's join with no further call`() =
        runBlocking {
            val phone = AnnouncePhone()
            AnnounceDriver.memberPhone(
                phone,
                extraRows = listOf(GroupMember(address = Y, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.INVITED)),
            )
            val f = GroupPaidOnceFixture().apply { scheduler = this@OpenGroupScreenUpdatesLiveTest.scheduler }
            val gvm = AnnounceDriver.groupVm(phone, f)
            AnnounceDriver.loadedConversation(gvm, f)
            pass()
            val opened = shown(gvm)
            assertEquals("fixture: the screen opens with no messages", emptyList<String?>(), texts(opened))
            assertEquals("fixture: Y is still invited", MemberStatus.INVITED, status(opened, Y))

            val seam = HandshakeSeam(ADMIN)
            val cvm = AnnounceDriver.chatVm(phone, seam)
            AnnounceDriver.receive(
                cvm,
                AnnounceDriver.gm(phone, Y, "hello from Y"),
                Instant.ofEpochMilli(T + 1_000),
                AnnounceDriver.txId(1)
            )
            AnnounceDriver.receive(
                cvm,
                AnnounceDriver.gm(phone, Z, "hello from Z", seq = 2),
                Instant.ofEpochMilli(T + 2_000),
                AnnounceDriver.txId(2),
            )
            assertEquals("fixture: both messages are stored", 2, phone.storedMessageIds(GID).size)
            assertEquals("fixture: Y is ACTIVE in the store", MemberStatus.ACTIVE, phone.status(GID, Y))

            pass()
            val now = shown(gvm)
            assertEquals(
                "the open group screen does not show the messages ChatViewModel stored (it still says 'No messages yet')",
                listOf("hello from Y", "hello from Z"),
                texts(now),
            )
            assertEquals("the open group screen still shows Y as invited after Y's message", MemberStatus.ACTIVE, status(now, Y))
            assertEquals("the open group screen does not list the new member Z", MemberStatus.ACTIVE, status(now, Z))
            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, f.seam.proposalRequests.get())
        }

    @Test
    fun `control - a write for another group does not reload the open group`() =
        runBlocking {
            val phone = AnnouncePhone()
            AnnounceDriver.memberPhone(phone)
            val f = GroupPaidOnceFixture().apply { scheduler = this@OpenGroupScreenUpdatesLiveTest.scheduler }
            val gvm = AnnounceDriver.groupVm(phone, f)
            AnnounceDriver.loadedConversation(gvm, f)
            pass()
            val before = gvm.groupDetailState.value
            val readsBefore = phone.calls.count { it == "getGroupInfo" }

            val other = "zgrp_ffeeddccbbaa998877665544"
            phone.proxy.saveGroupMessages(other, "[]")
            phone.proxy.saveGroupMembers(other, "[]")
            phone.proxy.saveGroupAnnounceState(other, phone.state(other))
            pass()

            assertSame("a write for another group reloaded the open group", before, gvm.groupDetailState.value)
            assertEquals("a write for another group read the open group again", readsBefore, phone.calls.count { it == "getGroupInfo" })
        }

    @Test
    fun `the store's group saves mark the group as changed`() {
        val source = sourceOf("ZchatPreferences.kt")
        val impl = source.substringAfter("class ZchatPreferencesImpl(")
        for (name in listOf("saveGroupMessages", "saveGroupMembers", "saveGroupMembersDurably", "saveGroupAnnounceState")) {
            val start = impl.indexOf("override fun $name(")
            if (start < 0) fail("ZchatPreferencesImpl has no $name")
            val body =
                impl.substring(start).let { rest ->
                    rest.substring(
                        0,
                        rest.indexOf("override fun ", 1).takeIf { it > 0 } ?: rest.length
                    )
                }
            assertTrue(
                "ZchatPreferencesImpl.$name does not mark the group's store as changed, so an open group screen never hears of it",
                body.contains("markGroupStoreChanged(groupId)"),
            )
        }
        val companion = ZchatPreferences.Companion::class.java
        assertTrue(
            "ZchatPreferences has no groupStoreVersion for the group screen to follow",
            companion.methods.any { it.name == "getGroupStoreVersion" },
        )
    }

    private fun sourceOf(name: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/$name") }
                .firstOrNull { it.isFile }
                ?: throw AssertionError("could not find $name from ${start.path}")
        return file.readText()
    }
}
