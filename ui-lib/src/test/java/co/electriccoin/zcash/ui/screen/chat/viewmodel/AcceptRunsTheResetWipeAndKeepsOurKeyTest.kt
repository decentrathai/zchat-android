package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (ZC-534-535-536-SPEC §3A.5 row 4; glue G1, G2) — the accept is Reset encryption's wipe followed by the
 * first-contact adoption, not a second wipe written beside it.
 *
 * Must hold: on identical chats the accept and Reset's wipe (wipeAndReestablishSecureSession with its KEX owed) leave
 * the same ratchet deletes, the same wipe steps and the same stored E2E entries, except what the accept keeps on
 * purpose (our key pair, its provenance and version) and what its adoption stores (the contact's new key and the
 * txid of the memo that carried it); both run wipeRatchetState and wipeSessionPrefs inside withPeerSession.
 */
class AcceptRunsTheResetWipeAndKeepsOurKeyTest : KeyChangeAcceptTestBase() {
    @Test
    fun `the accept and Reset leave the same state except our kept key pair and the adopted key`() =
        runBlocking {
            val newKeys = E2EEncryption.generateKeyPair()

            val accepted = KeyChangeAcceptChat(PEER)
            val acceptVm = accepted.viewModel()
            accepted.kexAckFromContact(acceptVm, newKeys, TXID)
            assertNotNull("the refused key is not remembered", accepted.record())
            accepted.accept(acceptVm, newKeys.publicKey)
            accepted.assertAdopted(newKeys, "after the accept")

            val reset = KeyChangeAcceptChat(PEER)
            val resetVm = reset.viewModel()
            reset.kexAckFromContact(resetVm, newKeys, TXID)
            KeyAcceptApi.resetWipe(resetVm, PEER)

            assertEquals(
                "the accept deletes the ratchet state Reset deletes",
                reset.reset.ratchetDeletes.toList(),
                accepted.reset.ratchetDeletes.toList(),
            )
            assertEquals(
                "the accept runs Reset's wipe steps (its key wipe keeps our pair)",
                reset.reset.wipes.map { if (it == "clearE2EKeys") "E2E-KEYS" else it },
                accepted.reset.wipes.map { if (it == "clearE2EPeerState") "E2E-KEYS" else it },
            )
            val afterReset = e2eEntries(reset)
            val afterAccept = e2eEntries(accepted)
            val onlyAfterReset = afterReset.filter { (k, v) -> afterAccept[k] != v }
            assertTrue("Reset left entries the accept did not: ${onlyAfterReset.keys}", onlyAfterReset.isEmpty())
            val kept =
                setOf<Any?>(
                    accepted.our.publicKey,
                    accepted.our.privateKey,
                    KeyChangeAcceptChat.PROVENANCE,
                    KeyChangeAcceptChat.KEY_VERSION,
                    newKeys.publicKey,
                    TXID,
                )
            // ZC-893 (FC-2B, 5.4b item 2): the accepted key's asked record (kexasked:, the same key) is kept beside it.
            // ZC-634 (part B, 5.5 item 2; RED j): a key the accept takes from a key exchange is stored with its claim mark
            // ("keyclaim:", naming that key), in the key's own commit.
            val extra =
                afterAccept.filter { (k, v) ->
                    afterReset[k] != v && !(k.startsWith("keyclaim:") && v.toString().contains(newKeys.publicKey))
                }
            val unexpected = extra.filterValues { it !in kept }
            assertTrue("the accept left more than our key pair and the adopted key: ${unexpected.keys}", unexpected.isEmpty())
            assertEquals("our key pair, provenance, version, the adopted key, its asked record and its txid", 7, extra.size)
        }

    @Test
    fun `Reset and the accept run the two halves of one wipe inside the per-contact session lock`() {
        val source = SourceScan.stripComments(ChatResourceText.source(CVM))
        assertTrue("the ratchet half of the wipe does not exist", "private suspend fun wipeRatchetState(" in source)
        assertTrue("the preference half of the wipe does not exist", "private fun wipeSessionPrefs(" in source)
        val reset = SourceScan.braceBlock(source, "private suspend fun wipeAndReestablishSecureSession(")
        val resetLocked = SourceScan.braceBlock(reset, "withPeerSession(peerAddress)")
        assertTrue("Reset's ratchet wipe is outside the session lock", "wipeRatchetState(peerAddress)" in resetLocked)
        assertTrue("Reset's preference wipe is outside the session lock", "wipeSessionPrefs(" in resetLocked)
        assertTrue("Reset must wipe our key pair too", "keepOurKeys = false" in resetLocked)
        assertTrue("Reset must clear the refusal too", "keepRefusal = false" in resetLocked)
        val accept = SourceScan.braceBlock(source, "private suspend fun adoptRefusedKey(")
        val acceptLocked = SourceScan.braceBlock(accept, "withPeerSession(peerAddress)")
        assertTrue("the accept's ratchet wipe is outside the session lock", "wipeRatchetState(peerAddress)" in acceptLocked)
        assertTrue("the accept does not run Reset's preference wipe", "wipeSessionPrefs(" in acceptLocked)
        assertTrue("the accept must keep our key pair", "keepOurKeys = true" in acceptLocked)
        assertTrue("the accept must keep the refusal until its last write", "keepRefusal = true" in acceptLocked)
        assertEquals(
            "one implementation of each half, called by Reset and by the accept",
            2,
            Regex("""wipeRatchetState\(peerAddress\)""").findAll(source).count(),
        )
    }

    /** The contact's entries in the E2E key file. */
    private fun e2eEntries(chat: KeyChangeAcceptChat): Map<String, Any?> {
        val file = chat.files.getValue("e2ePrefs")
        return file.snapshot().filterKeys { PEER in it }
    }

    private companion object {
        const val PEER = "usyntheticzc545samewipe00001"
        const val TXID = "synthetic-zc545-samewipe-ack"
        const val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
    }
}
