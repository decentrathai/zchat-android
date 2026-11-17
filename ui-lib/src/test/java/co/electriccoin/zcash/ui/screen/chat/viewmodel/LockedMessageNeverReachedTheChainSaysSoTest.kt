package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R9 (ZC-112) — a scheduled or locked message whose transaction never reached the chain says so.
 *
 * A scheduled, block-locked, payment-locked or conditional message is one on-chain send. When its transaction is stored
 * but never mined, today nothing tells the user: no row is kept for it and the send-time toast said it was on its way.
 *
 * Wanted: once the chain proves it can no longer be mined, the chat gets ONE note, under its own id, saying a locked
 * message did not go out and nothing was paid; the note carries no word of the message; nothing is re-sent (the
 * plaintext is not kept, so there is nothing to send); the entry is deleted after the note. A mined one gets no note.
 *
 * Every address, key and transaction id is synthetic.
 */
class LockedMessageNeverReachedTheChainSaysSoTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private companion object {
        const val SECRET = "synthetic locked plaintext 4711"
    }

    private fun notSentNoteText(): String {
        val id =
            runCatching {
                co.electriccoin.zcash.ui.R.string::class.java
                    .getField("chat_note_locked_message_not_sent")
                    .getInt(null)
            }.getOrElse {
                kotlin.test.fail("there is no text for a locked message that never reached the chain (chat_note_locked_message_not_sent)")
            }
        return TestChatNoteCopy.get(id)
    }

    @Test
    fun `a scheduled message that never reached the chain gets one note, with no plaintext, and nothing is re-sent`() =
        proven("scheduled") { vm, peer -> vm.sendScheduledMessage(peer, SECRET, System.currentTimeMillis() + 3_600_000L) }

    @Test
    fun `a block-locked message that never reached the chain gets one note`() =
        proven("block-locked") { vm, peer -> vm.sendBlockLockedMessage(peer, SECRET, LedgerWorld.H + 500) }

    @Test
    fun `a payment-locked message that never reached the chain gets one note`() =
        proven("payment-locked") { vm, peer -> vm.sendPaymentLockedMessage(peer, SECRET, 10_000L) }

    @Test
    fun `a conditional message that never reached the chain gets one note`() =
        proven("conditional") { vm, peer -> vm.sendConditionalMessage(peer, SECRET, "synthetic answer", "synthetic hint") }

    private fun proven(
        name: String,
        send: (ChatViewModel, String) -> Unit,
    ) = runBlocking {
        val world = LedgerWorld(mode = ConversationMode.VAULT, exchangeComplete = true)
        val vm = world.openVm()
        world.storedNeverMined()
        send(vm, world.peer)
        HandshakeHarness.awaitIdle(vm)
        assertEquals(
            "fixture sanity ($name): the locked message was paid once (${HandshakeHarness.sendMessageState(vm)})",
            1,
            world.seam.submits.get(),
        )
        world.walletListsStoredUnmined()

        world.twoPassesPastExpiry(vm)
        world.pass(vm, world.wallet.scanned + 1)

        val notes = ArrayList(world.prefs.rows.values)
        assertEquals(
            "$name: the locked message was stored, never mined and is past its expiry on two passes, and the chat " +
                "still says nothing about it",
            1,
            notes.size,
        )
        assertEquals("$name: the note says it did not go out", notSentNoteText(), notes.single().text)
        assertTrue(
            "$name: the note is a system note of this chat",
            notes.single().id.startsWith("sysnote-") && notes.single().peerAddress == world.peer,
        )
        assertTrue("$name: no word of the message is in the note", !notes.single().text.contains("4711"))
        assertEquals("$name: nothing is re-sent", 1, world.seam.proposals.get())
        assertTrue("$name: the entry is deleted after its note", world.ledger.all().isEmpty())
    }

    @Test
    fun `control - a locked message that was mined gets no note`() =
        runBlocking {
            val world = LedgerWorld(mode = ConversationMode.VAULT, exchangeComplete = true)
            val vm = world.openVm()
            world.storedNeverMined()
            vm.sendScheduledMessage(world.peer, SECRET, System.currentTimeMillis() + 3_600_000L)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("fixture sanity: the locked message was paid once", 1, world.seam.submits.get())
            world.wallet.txs = listOf(world.chainTx(1, minedHeight = LedgerWorld.H + 2))

            world.twoPassesPastExpiry(vm)

            assertTrue("a mined locked message is not a failed one", world.prefs.rows.isEmpty())
        }

    @Test
    fun `the text exists in English and Spanish and promises no resend`() {
        val en = stringOf("values", "chat_note_locked_message_not_sent")
        val es = stringOf("values-es", "chat_note_locked_message_not_sent")
        assertTrue("English: $en", en.contains("did not go out") && en.contains("nothing was paid"))
        assertTrue("Spanish: $es", es.contains("no salió") && es.contains("no se pagó nada"))
        assertTrue("no promise of an automatic resend", !en.contains("automatically") && !es.contains("automáticamente"))
    }

    private fun stringOf(
        folder: String,
        key: String,
    ): String {
        val start = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/res/ui/common/$folder/chat_surface_strings.xml") }
                .firstOrNull { it.isFile } ?: kotlin.test.fail("chat_surface_strings.xml ($folder) not found")
        return Regex("<string name=\"$key\">(.*?)</string>").find(file.readText())?.groupValues?.get(1)
            ?: kotlin.test.fail("no `$key` in $folder: a locked message that never reached the chain has no text")
    }
}
