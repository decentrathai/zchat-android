package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * W-72 (register ZC-847, ZC-855), SPEC_F_20261019 rule R6 with SPEC_DECISIONS D4, RED test 11 (the contact's side): a
 * mined memo from a contact this phone holds a key for, in the INIT form under an id that address owns here (its `conv:`
 * entry names it, it is not one of our own ids nor the chat's own id) and that the ratchet opens with the stored key, makes
 * that id the reply id ("replyconv:", unanswered): the contact's probe. Nothing else does: an unmined memo, a plaintext
 * memo, a memo the stored key does not open, a memo under one of our own ids. The history pass reads it; the real
 * conversation-id store keeps it.
 *
 * Today no reply id exists. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyIdComesOnlyFromAnAuthenticatedMinedProbeTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(11) a mined INIT under the contact's new id that the stored key opens sets the reply id`() =
        runBlocking {
            val chat = PassChat(seed = 72111)
            chat.received("w72-11-probe", chat.probeFromContact(THEIR_PROBE, "hello from 2.13"), minedAt = NEW + 20)

            chat.pass()

            assertEquals("the contact's authenticated probe did not become the reply id", THEIR_PROBE, chat.replyId())
        }

    @Test
    fun `(11) an unmined probe, a plaintext one, one the stored key does not open, or one under our own id sets nothing`() =
        runBlocking {
            val cases =
                listOf<Pair<String, (PassChat) -> Unit>>(
                    "unmined" to { c -> c.received("w72-11-unmined", c.probeFromContact(THEIR_PROBE, "x"), minedAt = null) },
                    "plaintext" to { c ->
                        c.received("w72-11-plain", ZMSGProtocol.createV4InitMessage(THEIR_PROBE, c.peer, "plain words"), minedAt = NEW + 21)
                    },
                    "other key" to { c ->
                        c.received("w72-11-other", c.probeFromContact(THEIR_PROBE, "x", root = ByteArray(32) { 9 }), minedAt = NEW + 22)
                    },
                    "our own id" to { c -> c.received("w72-11-own", c.probeFromContact(c.ownId, "x"), minedAt = NEW + 23) },
                )
            for ((label, add) in cases) {
                AutoSpendVm.resetProcessState()
                AutoSpendVm.setHistoryRead(true)
                val chat = PassChat(seed = 72112 + cases.indexOfFirst { it.first == label })
                add(chat)

                chat.pass()

                assertNull("$label: set a reply id", chat.replyId())
            }
        }

    private inner class PassChat(
        seed: Int,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(seed = seed)
        val f = HandshakePassFixture(scheduler, peer, firstContact = false)
        val ownId: String =
            f.ids.store
                .getOrCreateConversationIdForMessage(peer)
                .first
        private val root = ByteArray(32) { 7 }

        init {
            f.prefs.e2ePeerKey[peer] = E2EEncryption.generateKeyPair().publicKey
            f.extra = { method, args ->
                if (method.name in REPLY_CALLS) {
                    try {
                        method.invoke(f.ids.store, *args)
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw e.targetException
                    }
                } else {
                    ClaimMarkMaps.UNHANDLED
                }
            }
        }

        /** The contact's probe: the v4 INIT under [convId], encrypted by the contact's side of a ratchet over [root]. */
        fun probeFromContact(
            convId: String,
            text: String,
            root: ByteArray = this.root,
        ): String {
            val wire = runBlocking { E2EMessageProcessor(root, convId, false, InMemoryRatchetStateStore()).encryptOutgoing(text) }
            return ZMSGProtocol.createV4InitMessage(convId, peer, wire)
        }

        fun received(
            label: String,
            memo: String,
            minedAt: Long?,
        ) = f.received(label, memo, minedAt)

        suspend fun pass() {
            val vm = f.open()
            val ratchets = InMemoryRatchetStateStore()
            val processors =
                object : ConcurrentHashMap<String, E2EMessageProcessor>() {
                    override fun get(key: String): E2EMessageProcessor? =
                        super.get(key) ?: if (key.startsWith("$peer:")) {
                            computeIfAbsent(key) { E2EMessageProcessor(root, key.substringAfter(':'), true, ratchets) }
                        } else {
                            null
                        }
                }
            AutoSpendVm.setField(vm, "messageProcessors", processors)
            f.pass()
        }

        fun replyId(): String? {
            val method =
                f.ids.store.javaClass.methods
                    .firstOrNull { it.name == "getReplyConversationId" && it.parameterCount == 1 }
                    ?: throw AssertionError("the store has no getReplyConversationId")
            return method.invoke(f.ids.store, peer) as String?
        }
    }

    private companion object {
        const val THEIR_PROBE = "ZC72THR2"
        val REPLY_CALLS = setOf("getReplyConversationId", "armReplyConversationId", "answerReplyConversationId")
    }
}
