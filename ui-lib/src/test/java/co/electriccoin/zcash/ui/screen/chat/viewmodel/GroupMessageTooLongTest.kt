package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
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
import java.security.SecureRandom
import javax.xml.parsers.DocumentBuilderFactory

/**
 * R6-3 (round 5 report §5 item 4) — a group message long enough to push its memo past Zcash's 512-byte
 * memo field fails for every member, and the user is only told "couldn't be delivered to anyone".
 *
 * A group message is ONE memo per member (a rawMemo, never chunked) that carries the sender's full address
 * next to the AES-GCM ciphertext, so the room left for the text is small — a little over a hundred bytes
 * with a real unified address. Today nothing looks at the length before the send: the cost prompt is raised
 * for a message that can never go out, a sequence number is taken, an optimistic bubble is drawn, and every
 * member's copy then trips the pre-submit memo guard. No money moves, but the user is given no reason and
 * no way forward.
 *
 * Wanted (PM, 23 Sep): a specific, translated error BEFORE anything is sent — no cost prompt for a message
 * that cannot be sent, no spend, no sequence number, and the typed text kept — that says how much to cut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMessageTooLongTest {
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
        return f.groupViewModel().also {
            vms += it
            it.consumeGroupSendEvent()
        }
    }

    /**
     * The size of the memo the fan-out builds for [plaintextBytes] bytes of text in the fixture's group, with
     * no signature (the builder drops the signature before it would overflow, so this is the floor). Built
     * with the real builder, so the test follows the real wire layout rather than a copy of it.
     */
    private fun unsignedMemoBytes(plaintextBytes: Int): Int =
        ZMSGGroupProtocol
            .createGroupMsgMessage(
                groupId = PaidGroupFixture.GROUP_ID,
                seq = 1L,
                epoch = 1,
                senderAddress = PaidGroupFixture.SELF,
                plaintext = "a".repeat(plaintextBytes),
                groupKey = ByteArray(32).also { SecureRandom().nextBytes(it) },
                signer = null,
            ).toByteArray(Charsets.UTF_8)
            .size

    /** The most bytes of text one group memo of the fixture's group can carry. */
    private val maxTextBytes: Int by lazy {
        val max = (0..ZMSGConstants.MAX_MEMO_SIZE).last { unsignedMemoBytes(it) <= ZMSGConstants.MAX_MEMO_SIZE }
        assertTrue("fixture: the group memo leaves room for some text ($max bytes)", max in 20 until 500)
        max
    }

    private fun assertRefusedAsTooLong(
        event: GroupSendResult?,
        expectedOverBy: Int,
    ) {
        assertNotNull("a message too long for a group memo produced no outcome at all", event)
        assertEquals(
            "a message too long for a group memo must be refused as too long, not reported as $event",
            "MessageTooLong",
            event!!.javaClass.simpleName,
        )
        val overBy =
            event.javaClass.methods
                .firstOrNull { it.name == "getOverBy" }
                ?.invoke(event) as? Int
                ?: run {
                    fail("the too-long outcome ($event) does not say by how much the message is too long (overBy)")
                    error("unreachable")
                }
        assertEquals(
            "the too-long outcome must say how many characters to cut (bytes over the memo, so cutting that " +
                "many characters of any kind is always enough)",
            expectedOverBy,
            overBy,
        )
    }

    @Test
    fun `a message too long for a group memo is refused before the cost prompt and spends nothing`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)
            val text = "a".repeat(maxTextBytes + 1)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, text) }

            assertNull(
                "the cost prompt was raised for a message that can never be sent — the user would be asked " +
                    "to pay for it first",
                vm.paidActionConfirmation.value,
            )
            assertRefusedAsTooLong(vm.groupSendEvent.value, expectedOverBy = 1)
            assertEquals("a message that cannot be sent spent money", 0, f.seam.proposalRequests.get())
            assertEquals("a message that cannot be sent took a group sequence number", 0, f.sequenceBumps.get())
            assertEquals("the typed text must still be there", text, f.draft(PaidGroupFixture.GROUP_ID))
        }

    @Test
    fun `a group that already agreed to pay still never starts a fan-out for a message that cannot fit`() =
        runBlocking {
            val f = PaidGroupFixture()
            f.proxy.setGroupMessageSpendConsent(
                PaidGroupFixture.GROUP_ID,
                setOf(PaidGroupFixture.MEMBER_A, PaidGroupFixture.MEMBER_B),
            )
            val vm = viewModel(f)
            val text = "a".repeat(maxTextBytes + 7)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, text) }

            assertRefusedAsTooLong(vm.groupSendEvent.value, expectedOverBy = 7)
            assertEquals(
                "a message that cannot fit started a fan-out (took a sequence number, drew a bubble)",
                0,
                f.sequenceBumps.get(),
            )
            assertEquals("a message that cannot be sent spent money", 0, f.seam.proposalRequests.get())
            assertEquals("the typed text must still be there", text, f.draft(PaidGroupFixture.GROUP_ID))
        }

    @Test
    fun `the cut is counted in bytes, so accented text is not under-reported`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)
            // "ñ" is two bytes in UTF-8: this many of them is (maxTextBytes + 2) or (maxTextBytes + 3) bytes.
            val count = maxTextBytes / 2 + 2
            val text = "ñ".repeat(count)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, text) }

            assertRefusedAsTooLong(vm.groupSendEvent.value, expectedOverBy = 2 * count - maxTextBytes)
            assertEquals("a message that cannot be sent spent money", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `control - the longest message that fits still goes to the cost prompt`() =
        runBlocking {
            val f = PaidGroupFixture()
            val vm = viewModel(f)

            f.awaitNewJobs(vm) { vm.sendGroupMessage(PaidGroupFixture.GROUP_ID, "a".repeat(maxTextBytes)) }

            assertNull("a message that fits was refused: ${vm.groupSendEvent.value}", vm.groupSendEvent.value)
            assertEquals(
                "a message that fits must be quoted for both paying members",
                2,
                vm.paidActionConfirmation.value?.onChainMessages,
            )
        }

    @Test
    fun `control - every memo the fan-out builds for a message that fits is within the memo field, signed or not`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        for (n in listOf(0, 1, maxTextBytes / 2, maxTextBytes - 1, maxTextBytes)) {
            for (signed in listOf(false, true)) {
                val memo =
                    ZMSGGroupProtocol.createGroupMsgMessage(
                        groupId = PaidGroupFixture.GROUP_ID,
                        seq = 1L,
                        epoch = 1,
                        senderAddress = PaidGroupFixture.SELF,
                        plaintext = "a".repeat(n),
                        groupKey = key,
                        signer = if (signed) ({ _: String -> "s".repeat(96) }) else null,
                    )
                assertTrue(
                    "a $n-byte group message (signed=$signed) built a memo over the memo field",
                    memo.toByteArray(Charsets.UTF_8).size <= ZMSGConstants.MAX_MEMO_SIZE,
                )
            }
        }
    }

    // ---- the group screen -------------------------------------------------------------------------

    @Test
    fun `the group screen says the message is too long, how much to cut, in English and in Spanish`() {
        // Round 8 D8: the banner text is built in GroupSendBanner.kt (groupSendBannerText), which GroupDetailView calls.
        val view = source(GROUP_DETAIL_VIEW) + source(GROUP_SEND_BANNER)
        val branch =
            Regex("""is GroupSendResult\.MessageTooLong ->([\s\S]*?)(?:is GroupSendResult\.|\n\s*}\s*\n)""")
                .find(view)
                ?.groupValues
                ?.get(1)
                ?: run {
                    fail("GroupDetailView has no branch for a message that is too long for a group")
                    error("unreachable")
                }
        assertTrue(
            "the too-long banner must be the translated plural, with the number of characters to cut: $branch",
            branch.contains("R.plurals.$TOO_LONG_KEY") && branch.contains("overBy"),
        )
        val nothingTransmitted =
            Regex("""val nothingTransmitted =([\s\S]*?)if \(!nothingTransmitted\)""")
                .find(view)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        assertTrue(
            "the composer clears the typed text after a message refused as too long — it must keep it",
            nothingTransmitted.contains("GroupSendResult.MessageTooLong"),
        )

        val english = plurals("values")[TOO_LONG_KEY]
        val spanish = plurals("values-es")[TOO_LONG_KEY]
        assertNotNull("values has no <plurals name=\"$TOO_LONG_KEY\">", english)
        assertNotNull("values-es has no <plurals name=\"$TOO_LONG_KEY\">", spanish)
        for ((qualifier, items) in listOf("values" to english!!, "values-es" to spanish!!)) {
            assertTrue("$qualifier/$TOO_LONG_KEY has no quantity=\"other\"", items.containsKey("other"))
            items.forEach { (quantity, text) ->
                assertTrue("$qualifier/$TOO_LONG_KEY[$quantity] must say how much to cut: $text", text.contains("%1\$d"))
            }
        }
        assertTrue("$TOO_LONG_KEY is not translated", english["other"] != spanish["other"])
    }

    private fun source(path: String): String {
        val file = uiLibDir.resolve(path)
        assertTrue("missing ${file.path}", file.isFile)
        return file.readText()
    }

    /** Every `<plurals>` in the chat money strings of [qualifier]: name to (quantity to text). */
    private fun plurals(qualifier: String): Map<String, Map<String, String>> {
        val file = uiLibDir.resolve("src/main/res/ui/common/$qualifier/chat_money_strings.xml")
        assertTrue("missing ${file.path}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
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
        const val TOO_LONG_KEY = "group_send_too_long"

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
