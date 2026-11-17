package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2ERatchet
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-545 and register ZC-564 (ZC-534-535-536-SPEC §3A.2, §3A.5 row 9; rule R9) — Reset encryption wipes a contact's
 * ratchet with no lock against the ratchet's users. A decrypt or an encrypt that loaded the state before the wipe's
 * delete saves it after: the state has no root id, so the next root starts from the old root's counters (the
 * conversation wedge, and a second paid Reset). The processor cache is per ViewModel instance, so another screen's
 * cached processor keeps using the old root. ZChat 2.12.4 has the same code.
 *
 * Must hold: while a ratchet user of the contact is in its ratchet call (held here inside the store's load or save),
 * Reset's wipe, and the accept, wait: no delete reaches the store and the contact's key is unchanged until the user
 * finishes; after the wipe no state exists under the conversation id and no save follows a delete; a user that starts
 * while the wipe holds the lock waits and then never uses the old root; a processor another ViewModel cached before
 * the wipe is never served after it; every ratchet call sits in a per-contact session block that holds no proposal,
 * relay or screen call and nests no other. The Reset cases fail before the fix: that is ZC-564.
 *
 * The held user is a decrypt (the history pass); the send sites are covered by the source scan.
 */
class ResetAndAcceptWaitForRatchetUsersTest : KeyChangeAcceptTestBase() {
    private val workers = CoroutineScope(Dispatchers.Default)

    @Test
    fun `Reset waits for a decrypt that is inside the store's save and its save never follows the delete`() =
        runBlocking {
            val (chat, vm, store) = chatWithLiveRatchet()
            val fromContact = contactCiphertext(chat, vm, "hello before the reset")
            store.holdSave = CompletableDeferred()
            val decrypt = workers.async { KeyAcceptApi.decrypt(vm, fromContact, PEER, KeyChangeAcceptChat.CONV_ID) }
            assertEquals("fixture: the decrypt is held inside save", "save", withTimeout(WAIT_MS) { store.parked.await() })

            val wipe = workers.async { KeyAcceptApi.resetWipe(vm, PEER) }
            delay(SETTLE_MS)

            assertEquals("Reset deleted ratchet state while a decrypt was still using it", emptyList<String>(), store.deletes())
            assertEquals("Reset wiped the key while a decrypt was still using its ratchet", chat.oldContact.publicKey, chat.storedKey())
            store.holdSave!!.complete(Unit)
            withTimeout(WAIT_MS) {
                assertEquals("the held decrypt must finish under the old root", "hello before the reset", decrypt.await())
                wipe.await()
            }
            val resurrected = store.saveAfterDelete(KeyChangeAcceptChat.CONV_ID)
            assertFalse("a save followed the wipe's delete: old-root counters survive the Reset", resurrected)
            assertNull("ratchet state exists after the Reset", store.stateOf(KeyChangeAcceptChat.CONV_ID))
            assertNull("the contact's key must be gone after the Reset", chat.storedKey())
        }

    @Test
    fun `Reset waits for a decrypt that is inside the store's load`() =
        runBlocking {
            val (chat, vm, store) = chatWithLiveRatchet()
            val fromContact = contactCiphertext(chat, vm, "hello in load")
            store.holdLoad = CompletableDeferred()
            val decrypt = workers.async { KeyAcceptApi.decrypt(vm, fromContact, PEER, KeyChangeAcceptChat.CONV_ID) }
            assertEquals("fixture: the decrypt is held inside load", "load", withTimeout(WAIT_MS) { store.parked.await() })

            val wipe = workers.async { KeyAcceptApi.resetWipe(vm, PEER) }
            delay(SETTLE_MS)

            assertEquals("Reset deleted ratchet state while a decrypt was still using it", emptyList<String>(), store.deletes())
            store.holdLoad!!.complete(Unit)
            withTimeout(WAIT_MS) {
                decrypt.await()
                wipe.await()
            }
            assertFalse("a save followed the wipe's delete", store.saveAfterDelete(KeyChangeAcceptChat.CONV_ID))
            assertNull("ratchet state exists after the Reset", store.stateOf(KeyChangeAcceptChat.CONV_ID))
        }

    @Test
    fun `a decrypt that starts while Reset's wipe runs waits and never uses the old root`() =
        runBlocking {
            val (chat, vm, store) = chatWithLiveRatchet()
            val fromContact = contactCiphertext(chat, vm, "hello during the reset")
            store.holdDelete = CompletableDeferred()
            val wipe = workers.async { KeyAcceptApi.resetWipe(vm, PEER) }
            assertEquals("fixture: the wipe is held inside delete", "delete", withTimeout(WAIT_MS) { store.parked.await() })
            val before = store.events.toList()

            val decrypt = workers.async { KeyAcceptApi.decrypt(vm, fromContact, PEER, KeyChangeAcceptChat.CONV_ID) }
            delay(SETTLE_MS)

            assertEquals("a decrypt ran its ratchet while the wipe was deleting its state", before, store.events.toList())
            store.holdDelete!!.complete(Unit)
            val shown = withTimeout(WAIT_MS) { wipe.await().let { decrypt.await() } }
            assertFalse("the old root decrypted after the wipe", shown == "hello during the reset")
            assertFalse("a save followed the wipe's delete", store.saveAfterDelete(KeyChangeAcceptChat.CONV_ID))
            assertNull("ratchet state exists after the Reset", store.stateOf(KeyChangeAcceptChat.CONV_ID))
        }

    @Test
    fun `a processor another ViewModel cached before a Reset is never served after it`() =
        runBlocking {
            val (chat, vm, store) = chatWithLiveRatchet()
            val other = chat.viewModel()
            AutoSpendVm.setField(other, "messageProcessors", ConcurrentHashMap<String, E2EMessageProcessor>())
            AutoSpendVm.setField(other, "ratchetStateStore", store)
            val cached = KeyAcceptApi.processor(other, PEER, KeyChangeAcceptChat.CONV_ID)
            assertNotNull("fixture: the other screen built its processor", cached)

            KeyAcceptApi.resetWipe(vm, PEER)

            val after = KeyAcceptApi.processor(other, PEER, KeyChangeAcceptChat.CONV_ID)
            assertNotSame("another screen still serves the old root's processor after a Reset", cached, after)
            assertNull("no key is stored after a Reset, so there is no ratchet", after)
        }

    @Test
    fun `the accept waits for a decrypt inside save and another ViewModel's cached processor is retired`() =
        runBlocking {
            val (chat, vm, store) = chatWithLiveRatchet()
            val newKeys = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, newKeys, "synthetic-zc545-quiesce-ack")
            assertNotNull("the refused key is not remembered", chat.record())
            val other = chat.viewModel()
            AutoSpendVm.setField(other, "messageProcessors", ConcurrentHashMap<String, E2EMessageProcessor>())
            AutoSpendVm.setField(other, "ratchetStateStore", store)
            val cached = KeyAcceptApi.processor(other, PEER, KeyChangeAcceptChat.CONV_ID)
            val fromContact = contactCiphertext(chat, vm, "hello before the accept")
            store.holdSave = CompletableDeferred()
            val decrypt = workers.async { KeyAcceptApi.decrypt(vm, fromContact, PEER, KeyChangeAcceptChat.CONV_ID) }
            assertEquals("fixture: the decrypt is held inside save", "save", withTimeout(WAIT_MS) { store.parked.await() })

            KeyAcceptApi.accept(vm, PEER, newKeys.publicKey, chat.our.publicKey)
            delay(SETTLE_MS)

            assertEquals("the accept deleted ratchet state while a decrypt was still using it", emptyList<String>(), store.deletes())
            assertEquals("the accept switched the key while the old ratchet was in use", chat.oldContact.publicKey, chat.storedKey())
            store.holdSave!!.complete(Unit)
            withTimeout(WAIT_MS) { assertEquals("hello before the accept", decrypt.await()) }
            chat.settle(vm)

            chat.assertAdopted(newKeys, "after the accept")
            assertFalse("a save followed the accept's delete", store.saveAfterDelete(KeyChangeAcceptChat.CONV_ID))
            val after = KeyAcceptApi.processor(other, PEER, KeyChangeAcceptChat.CONV_ID)
            assertNotNull("the other screen must build a ratchet from the new key", after)
            assertNotSame("another screen still serves the old root's processor after the accept", cached, after)
        }

    @Test
    fun `every ratchet call sits in a per-contact session block that holds nothing slow and nests no other`() {
        val source = SourceScan.stripComments(ChatResourceText.source(CVM))
        val ranges =
            Regex("""withPeerSession\(""")
                .findAll(source)
                .filterNot { source.substring(maxOf(0, it.range.first - 40), it.range.first).contains("fun ") }
                .map { match ->
                    val block = SourceScan.braceBlock(source.substring(match.range.first), "withPeerSession(")
                    match.range.first until match.range.first + block.length
                }.toList()
        assertTrue("no per-contact session block exists (Reset's wipe takes no lock against ratchet users)", ranges.isNotEmpty())
        for (call in RATCHET_CALLS) {
            val sites = Regex(Regex.escape(call)).findAll(source).map { it.range.first }.toList()
            assertTrue("fixture: $call is called in ChatViewModel", sites.isNotEmpty())
            for (at in sites) {
                val line = source.substring(0, at).count { it == '\n' } + 1
                val inBlock = ranges.any { at in it } || at in wipeRatchetState(source)
                assertTrue("$call near line $line (comments stripped) is outside every withPeerSession block", inBlock)
            }
        }
        for (range in ranges) {
            val block = source.substring(range.first, range.last + 1)
            val inner = block.substring("withPeerSession(".length)
            for (forbidden in NEVER_IN_A_SESSION_BLOCK) {
                assertFalse("a session block holds $forbidden:\n${block.take(300)}", forbidden in inner)
            }
        }
        // The wipe's ratchet half is only ever called from inside a session block.
        val wipeCalls = Regex("""wipeRatchetState\(peerAddress\)""").findAll(source).map { it.range.first }.toList()
        assertTrue("fixture: the ratchet half of the wipe is called", wipeCalls.isNotEmpty())
        for (at in wipeCalls) assertTrue("the ratchet wipe runs outside the session lock", ranges.any { at in it })
    }

    /** The body of wipeRatchetState: its delete is under the lock of its callers (asserted separately). */
    private fun wipeRatchetState(source: String): IntRange {
        val start = source.indexOf("private suspend fun wipeRatchetState(")
        if (start < 0) return IntRange.EMPTY
        return start until start + SourceScan.braceBlock(source.substring(start), "private suspend fun wipeRatchetState(").length
    }

    /** A chat whose ViewModel builds its own ratchet from the stored keys over a store that can be held. */
    private suspend fun chatWithLiveRatchet(): Triple<KeyChangeAcceptChat, ChatViewModel, GatedRatchetStore> {
        val chat = KeyChangeAcceptChat(PEER)
        val vm = chat.viewModel()
        val store = GatedRatchetStore()
        AutoSpendVm.setField(vm, "messageProcessors", ConcurrentHashMap<String, E2EMessageProcessor>())
        AutoSpendVm.setField(vm, "ratchetStateStore", store)
        return Triple(chat, vm, store)
    }

    /** A message the contact encrypted under the root this phone derives from the stored (old) pair (counter 1). */
    private suspend fun contactCiphertext(
        chat: KeyChangeAcceptChat,
        vm: ChatViewModel,
        text: String,
    ): String {
        val shared = vm.getE2ESharedKey(PEER)
        assertNotNull("fixture: the stored pair's shared key", shared)
        val root =
            E2ERatchet.deriveRatchetRoot(
                ecdhSharedSecret = shared!!,
                psk = null,
                kexTxid = E2ERatchet.canonicalTxidMaterial(chat.impl.getKexTxIds(PEER), chat.impl.getE2EKexTxId(PEER)),
                kexAckTxid = E2ERatchet.canonicalTxidMaterial(chat.impl.getKexAckTxIds(PEER), chat.impl.getE2EKexAckTxId(PEER)),
            )
        val contactIsLower = chat.oldContact.publicKey < chat.our.publicKey
        val contact = E2EMessageProcessor(root, KeyChangeAcceptChat.CONV_ID, contactIsLower, InMemoryRatchetStateStore())
        // The contact's second message: a decrypt saves the state only when the counter moves the high-water mark.
        contact.encryptOutgoing("an earlier message")
        return contact.encryptOutgoing(text)
    }

    @org.junit.After
    fun stopWorkers() {
        workers.cancel()
    }

    private companion object {
        const val PEER = "usyntheticzc545quiesce000001"
        const val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 300L
        val RATCHET_CALLS =
            listOf(".encryptOutgoing(", ".decryptIncoming(", ".settleLegacyJump(", ".adoptLegacyJump(", "ratchetStateStore.delete(")
        val NEVER_IN_A_SESSION_BLOCK =
            listOf(
                "withPeerSession(",
                "createChunkedMessageProposal(",
                ".publish(",
                "askBeforePaying(",
                "_sendMessageState",
                "payOwedKex(",
                "payQuotedOwedKex(",
            )
    }
}
