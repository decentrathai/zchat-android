package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (ZC-534-535-536-SPEC §3A.5 row 8; rules R4, R8) — while a refused key waits for the user, nothing but the
 * accept's own adoption may store a key for that contact. Between the accept's wipe and its adoption no key is stored,
 * and that state can outlive the process; a first-contact handler would then adopt whatever offer arrives next,
 * with no tap.
 *
 * Must hold with a record present, both while the OLD key is stored and while NO key is stored (an interrupted
 * accept): a verified KEX or KEXACK, with another key or with the record's own key, is never stored; it is refused
 * (flag, counted, or the record refreshed). The writers of a contact's key and the accept's section run under one
 * monitor with no suspension inside. (ZC-638: the two unsigned writers, the legacy E2E_INIT branch and a group
 * invitation's key field, are gone; OnlyASignedKeyOfferStoresAContactKeyTest pins that.) Control: with no record and no
 * key stored a KEX is adopted as first contact, as before.
 */
class NoKeyIsInstalledWhileARefusedKeyRecordExistsTest : KeyChangeAcceptTestBase() {
    @Test
    fun `with the old key stored no offer is stored while a record exists`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val remembered = E2EEncryption.generateKeyPair()
            val attacker = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, remembered)
            assertNotNull("the refused key is not remembered", chat.record())

            chat.kexFromContact(vm, attacker, "synthetic-zc545-guard-kex")
            chat.kexAckFromContact(vm, attacker, "synthetic-zc545-guard-ack")
            chat.kexAckFromContact(vm, remembered, "synthetic-zc545-guard-own")

            assertEquals("an offer was stored while a refused key waits", chat.oldContact.publicKey, chat.storedKey())
            assertEquals("the record's key changed", remembered.publicKey, chat.recordKey())
            assertEquals("the two other offers are counted", 2, KeyAcceptApi.get(chat.record()!!, "laterOffers"))
            assertEquals("the record's own key refreshes it", "synthetic-zc545-guard-own", KeyAcceptApi.get(chat.record()!!, "txId"))
            assertTrue("the banner must stay", chat.flag())
            assertEquals("a refused offer paid something", 0, chat.seam.proposals())
        }

    @Test
    fun `with no key stored after an interrupted accept no offer is adopted without a tap`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER, consent = true)
            val vm = chat.viewModel()
            val remembered = E2EEncryption.generateKeyPair()
            val attacker = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, remembered, "synthetic-zc545-guard-first")
            assertNotNull("the refused key is not remembered", chat.record())
            // The process ends between the accept's preference wipe and its adoption.
            chat.killAtWrite = "setE2EPeerPublicKey"
            chat.accept(vm, remembered.publicKey)
            assertTrue("fixture: the accept reached its adoption", chat.killed)
            assertNull("fixture: no key is stored after the wipe", chat.storedKey())
            ResetOwedChat.kill(vm)
            chat.revive()
            val later = chat.viewModel()

            chat.kexFromContact(later, attacker, "synthetic-zc545-guard-kex")
            chat.kexAckFromContact(later, attacker, "synthetic-zc545-guard-ack")
            chat.kexFromContact(later, remembered, "synthetic-zc545-guard-own")

            assertNull("an offer was adopted without a tap while the record exists", chat.storedKey())
            assertTrue("the banner must stay", chat.flag())
            assertEquals("the record must stay the key the user was shown", remembered.publicKey, chat.recordKey())
            assertTrue("sends must stay refused", chat.sendsRefused(later))
            assertEquals("a refused offer was answered with a paid KEXACK", 0, chat.seam.proposals())
            assertNull("an unauthenticated offer moved a conversation id", chat.impl.getPeerByConversationId("ATTK2345"))
        }

    @Test
    fun `control - with no record and no key stored a KEX is adopted as first contact`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            // After a Reset: no key of the contact, no record.
            val e2e = chat.files.getValue("e2ePrefs")
            val editor = e2e.edit()
            val oldKeyEntries = e2e.snapshot().filterValues { it == chat.oldContact.publicKey }
            oldKeyEntries.keys.forEach { editor.remove(it) }
            editor.commit()
            assertNull("fixture: no key stored", chat.storedKey())

            chat.kexFromContact(vm, newKeys, "synthetic-zc545-guard-control")
            chat.settle(vm)

            assertEquals("first contact must still adopt", newKeys.publicKey, chat.storedKey())
            assertFalse("first contact raises no banner", chat.flag())
        }

    @Test
    fun `one monitor wraps the accept's section and the key writers and nothing suspends inside`() {
        val source = SourceScan.stripComments(ChatResourceText.source(CVM))
        assertTrue("no process-wide key monitor exists", Regex("""private val peerKeyMonitor\s*=\s*Any\(\)""").containsMatchIn(source))
        assertTrue("the adoption's store part must not suspend", Regex("""private fun adoptKeyOffer\(""").containsMatchIn(source))
        val blocks = monitorBlocks(source)
        assertTrue("the accept's section and the KEX and KEXACK store part: ${blocks.size}", blocks.size >= 2)
        for (block in blocks) {
            for (forbidden in NEVER_IN_THE_MONITOR) {
                assertFalse("a suspension or a payment inside the key monitor ($forbidden):\n${block.take(400)}", forbidden in block)
            }
        }
        // Every write of a contact's key is inside the monitor.
        // ZC-634 (part B): the contact key's one write (storePeerKey) and the mark put on a stored key (markKeyClaim).
        val writes =
            Regex("""zchatPreferences\.(?:setE2EPeerPublicKey|storePeerKey|markKeyClaim)\(""")
                .findAll(source)
                .map { it.range.first }
                .toList()
        assertTrue("fixture: the writers of a contact's key", writes.isNotEmpty())
        val ranges = monitorRanges(source)
        for (at in writes) {
            assertTrue("a contact's key is stored outside the key monitor at offset $at", ranges.any { at in it })
        }
        val publicSetter = "fun setE2EPeerPublicKey(peerAddress: String, publicKey: String)"
        assertFalse("the unused public setter of a contact's key is still there", publicSetter in source)
        // Only the accept's adoption may write while a record exists.
        val adopt = SourceScan.braceBlock(source, "private fun adoptKeyOffer(")
        assertTrue("the adoption does not know the accept", "KeyOfferMode.ACCEPT" in adopt)
        assertTrue("the adoption does not know a live offer", "KeyOfferMode.LIVE" in adopt)
        assertTrue("the live handler's refusal does not remember the key", "rememberRefusedKeyOffer(" in adopt)
    }

    private fun monitorRanges(source: String): List<IntRange> =
        Regex("""synchronized\(peerKeyMonitor\)""")
            .findAll(source)
            .map { match ->
                val block = SourceScan.braceBlock(source.substring(match.range.first), "synchronized(peerKeyMonitor)")
                match.range.first until match.range.first + block.length
            }.toList()

    private fun monitorBlocks(source: String): List<String> = monitorRanges(source).map { source.substring(it.first, it.last + 1) }

    private companion object {
        const val PEER = "usyntheticzc545nokeywhile001"
        const val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        val NEVER_IN_THE_MONITOR =
            listOf(
                "withLock",
                "withPeerSession(",
                "delay(",
                "getOrCreateKeyPair(",
                "payKexAckOnce(",
                "sendNostrBootHandshake(",
            )
    }
}
