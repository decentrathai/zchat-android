package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * R6-1 (round 5 report §5 item 1) — a group you paid to create swallows every message while its
 * other members are still Invited.
 *
 * On the phone: the creator's side shows "2 members · 1 active", the only invitee is still Invited,
 * and tapping Send does nothing — no bubble, no banner, nothing. In the tree:
 *  - `messageRecipients` keeps only ACTIVE members, so the send has nobody to go to;
 *  - `askForQuotedGroupMessage` reads the empty paid set as "nothing to pay for" and runs the fan-out
 *    anyway: it takes a sequence number, draws an optimistic bubble, finds no recipient, removes the
 *    bubble again and emits a banner that names nobody and is English on every phone;
 *  - and the group screen consumes that banner's event before it shows it, so the snackbar is cancelled
 *    by its own key change within a frame — which is why the phone showed nothing at all.
 *
 * Wanted (PM, 23 Sep): Send never silently does nothing and never spends; it tells the user WHO has
 * not joined yet, in a translated message (English and Spanish); the typed text stays. A member who is
 * not ACTIVE is never paid for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupNobodyJoinedSendTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()
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

    private suspend fun viewModel(f: PaidGroupFixture): GroupViewModel {
        f.scheduler = scheduler
        // The send-outcome flow is process-wide: start every test from no outcome.
        return f.groupViewModel().also {
            vms += it
            it.consumeGroupSendEvent()
        }
    }

    /** Replace the roster: the creator plus [others], stored exactly as the app stores it. */
    private fun roster(
        f: PaidGroupFixture,
        vararg others: GroupMember,
    ) {
        val self =
            GroupMember(
                address = PaidGroupFixture.SELF,
                joinedAt = Instant.ofEpochMilli(1_000L),
                status = MemberStatus.ACTIVE,
                isAdmin = true,
            )
        f.proxy.saveGroupMembers(
            PaidGroupFixture.GROUP_ID,
            ZMSGGroupProtocol.serializeGroupMembers(listOf(self) + others),
        )
    }

    private fun invited(
        address: String,
        nickname: String? = null,
    ) = GroupMember(
        address = address,
        joinedAt = Instant.ofEpochMilli(1_000L),
        status = MemberStatus.INVITED,
        nickname = nickname,
        inviteStatus = InviteStatus.SENT,
    )

    private fun active(address: String) =
        GroupMember(
            address = address,
            joinedAt = Instant.ofEpochMilli(1_000L),
            status = MemberStatus.ACTIVE,
            inviteStatus = InviteStatus.SENT,
        )

    /** The names the send's outcome gives for the members who have not joined, reached by name. */
    private fun namedNotJoined(event: GroupSendResult): List<*> {
        val getter =
            event.javaClass.methods.firstOrNull { it.name == "getNotJoined" }
                ?: run {
                    fail(
                        "the outcome of a send nobody could receive ($event) does not say who has not joined " +
                            "yet: it carries no notJoined list",
                    )
                    error("unreachable")
                }
        return getter.invoke(event) as List<*>
    }

    // ---- the view model ---------------------------------------------------------------------------

    @Test
    fun `a message to a group whose members are all invited tells the user who has not joined`() =
        runBlocking {
            val f = PaidGroupFixture()
            roster(
                f,
                invited(PaidGroupFixture.MEMBER_A, nickname = "Ana"),
                invited(PaidGroupFixture.MEMBER_B),
            )
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "R6-1 nobody joined") }

            val event = vm.groupSendEvent.value
            assertNotNull("tapping Send in a group nobody has joined produced no outcome at all", event)
            assertTrue(
                "the outcome must say that nobody could receive the message, not $event",
                event is GroupSendResult.NoActiveRecipients,
            )
            assertEquals(
                "the outcome must name every member who has not joined yet (nickname, else the short address)",
                listOf("Ana", invited(PaidGroupFixture.MEMBER_B).displayName),
                namedNotJoined(event!!),
            )
        }

    @Test
    fun `a message nobody can receive never starts a fan-out`() =
        runBlocking {
            val f = PaidGroupFixture()
            roster(f, invited(PaidGroupFixture.MEMBER_A), invited(PaidGroupFixture.MEMBER_B))
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "R6-1 no fan-out") }

            // A fan-out takes the next sequence number and draws an optimistic bubble before it looks
            // for recipients. With nobody to send to it must not start: nothing to take, nothing to draw.
            assertEquals(
                "a message nobody could receive still took a group sequence number (the fan-out ran)",
                0,
                f.sequenceBumps.get(),
            )
        }

    @Test
    fun `a message nobody can receive spends nothing and asks nothing and keeps the text`() =
        runBlocking {
            val f = PaidGroupFixture()
            roster(f, invited(PaidGroupFixture.MEMBER_A), invited(PaidGroupFixture.MEMBER_B))
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "R6-1 keep me") }

            assertEquals("a message nobody could receive spent money", 0, f.seam.proposalRequests.get())
            assertNull("a message that pays for nobody must not raise a cost prompt", vm.paidActionConfirmation.value)
            assertEquals("the typed text must still be there", "R6-1 keep me", f.draft(PaidGroupFixture.GROUP_ID))
            assertTrue("fixture: the send is not still running", !vm.isSendingMessage.value)
        }

    @Test
    fun `a message to a group nobody else is in says so and spends nothing`() =
        runBlocking {
            val f = PaidGroupFixture()
            val gone =
                GroupMember(
                    address = PaidGroupFixture.MEMBER_A,
                    joinedAt = Instant.ofEpochMilli(1_000L),
                    status = MemberStatus.LEFT,
                )
            roster(f, gone)
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "R6-1 alone") }

            val event = vm.groupSendEvent.value
            assertTrue(
                "a group nobody else is in must say nobody could receive it, not $event",
                event is GroupSendResult.NoActiveRecipients,
            )
            assertEquals(
                "a member who left is not someone who has yet to join",
                emptyList<Any>(),
                namedNotJoined(event!!),
            )
            assertEquals("a message nobody could receive spent money", 0, f.seam.proposalRequests.get())
            assertEquals("the typed text must still be there", "R6-1 alone", f.draft(PaidGroupFixture.GROUP_ID))
        }

    @Test
    fun `control - with one member active and one invited only the active member is paid for`() =
        runBlocking {
            val f = PaidGroupFixture()
            roster(f, active(PaidGroupFixture.MEMBER_A), invited(PaidGroupFixture.MEMBER_B))
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "R6-1 one of two") }
            assertEquals(
                "the quote must count only the active member",
                1,
                vm.paidActionConfirmation.value?.onChainMessages,
            )

            f.awaitNewJobs(vm) { vm.confirmPaidAction() }
            assertEquals("a member who has not joined was paid for", 1, f.seam.proposalRequests.get())
        }

    // ---- the group screen -------------------------------------------------------------------------

    @Test
    fun `the group screen names who has not joined in English and in Spanish`() {
        // Round 8 D8: the banner text is built in GroupSendBanner.kt (groupSendBannerText), which GroupDetailView calls.
        val view = source(GROUP_DETAIL_VIEW) + source(GROUP_SEND_BANNER)
        val branch =
            Regex("""is GroupSendResult\.NoActiveRecipients ->([\s\S]*?)is GroupSendResult\.AllFailed""")
                .find(view)
                ?.groupValues
                ?.get(1)
                ?: run {
                    fail("GroupDetailView has no NoActiveRecipients branch before AllFailed")
                    error("unreachable")
                }
        assertTrue(
            "the no-one-joined banner is a hard-coded English literal, not a string resource: $branch",
            branch.contains("R.plurals.$NOT_JOINED_KEY") && !branch.contains("\"Waiting for members"),
        )

        listOf("values", "values-es").forEach { qualifier ->
            val plurals = plurals(qualifier)
            val items =
                plurals[NOT_JOINED_KEY] ?: run {
                    fail("$qualifier has no <plurals name=\"$NOT_JOINED_KEY\">")
                    error("unreachable")
                }
            items.forEach { (quantity, text) ->
                assertTrue("$qualifier/$NOT_JOINED_KEY[$quantity] must name the members: $text", text.contains("%1\$s"))
            }
            assertTrue(
                "$qualifier has no <string name=\"$NOBODY_ELSE_KEY\"> for a group nobody else is in",
                strings(qualifier).containsKey(NOBODY_ELSE_KEY),
            )
        }
        assertTrue(
            "a group nobody else is in must get its own line, not a list of no names",
            view.contains("R.string.$NOBODY_ELSE_KEY"),
        )
    }

    @Test
    fun `the group screen shows the send outcome instead of cancelling it by consuming it`() {
        val view = source(GROUP_DETAIL_VIEW)
        // The outcome effect is keyed on the event. Consuming the event (it becomes null) changes the key,
        // which cancels the effect — and with it a showSnackbar() the effect itself is suspended in. The
        // banner must be shown from a scope that consuming the event does not cancel.
        assertTrue(
            "the group screen shows its send-outcome banner inside the effect keyed on that outcome, so " +
                "consuming the outcome cancels the banner before it can be seen",
            Regex("""\w+\.launch\s*\{\s*snackbarHostState\.showSnackbar""").containsMatchIn(view) &&
                view.contains("rememberCoroutineScope()"),
        )
    }

    private fun source(path: String): String {
        val file = uiLibDir.resolve(path)
        assertTrue("missing ${file.path}", file.isFile)
        return file.readText()
    }

    /** Every `<string>` in the chat money strings of [qualifier]: name to text. */
    private fun strings(qualifier: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moneyStrings(qualifier))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i) as Element
            el.getAttribute("name") to el.textContent
        }
    }

    private fun moneyStrings(qualifier: String): File {
        val file = uiLibDir.resolve("src/main/res/ui/common/$qualifier/chat_money_strings.xml")
        assertTrue("missing ${file.path}", file.isFile)
        return file
    }

    /** Every `<plurals>` in the chat money strings of [qualifier]: name to (quantity to text). */
    private fun plurals(qualifier: String): Map<String, Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moneyStrings(qualifier))
        val nodes = doc.getElementsByTagName("plurals")
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i) as Element
            val items = el.getElementsByTagName("item")
            el.getAttribute("name") to
                (0 until items.length).associate { j ->
                    val item = items.item(j) as Element
                    item.getAttribute("quantity") to item.textContent
                }
        }
    }

    private companion object {
        const val GROUP_DETAIL_VIEW = "src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupDetailView.kt"
        const val GROUP_SEND_BANNER = "src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupSendBanner.kt"
        const val NOT_JOINED_KEY = "group_send_not_joined"
        const val NOBODY_ELSE_KEY = "group_send_nobody_else"

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/common").isDirectory }
                ?: error("could not locate the ui-lib module from ${start.path}")
        }
    }
}
