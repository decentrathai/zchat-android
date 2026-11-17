package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-545 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §3A.5 row 1; owner decision W-32) — a contact's changed key is
 * refused and flagged, and then forgotten: the KEX and KEXACK branches of ChatViewModel.handleKEXMessage return before
 * anything keeps the key, and the memo's txid is already marked processed, so the key exists on the chain only. The ×
 * ("Accept the new key") therefore has nothing to adopt and the safety dialog shows the OLD pair's number.
 *
 * Must hold: the first refused key since the banner went up is remembered once, whole (key, memo, txid, first seen);
 * nothing is switched or used before the user accepts; a different later key is only counted; the same key again
 * refreshes memo and txid (a KEXACK replaces a KEX) and keeps first seen; a KEX whose signature fails is neither flagged
 * nor remembered. (ZC-638: the unsigned legacy E2E_INIT branch is gone; OnlyASignedKeyOfferStoresAContactKeyTest pins
 * that.)
 */
class KeyChangeRemembersTheFirstOfferOnlyTest : KeyChangeAcceptTestBase() {
    @Test
    fun `a changed key from a KEX is refused, flagged and remembered once, and nothing is switched or used`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val newKeys = E2EEncryption.generateKeyPair()
            val sharedBefore = vm.getE2ESharedKey(PEER)
            val numberBefore = vm.computeSafetyNumber(PEER)

            val memo = chat.kexFromContact(vm, newKeys, "synthetic-zc545-first-kex")

            assertTrue("the changed key must raise the banner", chat.flag())
            assertFalse("a key change ends the earlier verification", chat.impl.isE2EVerified(PEER))
            assertEquals("the changed key must never be switched to silently", chat.oldContact.publicKey, chat.storedKey())
            assertArrayEquals("the refused key was used for the shared key", sharedBefore, vm.getE2ESharedKey(PEER))
            assertEquals("the stored pair's number changed before any accept", numberBefore, vm.computeSafetyNumber(PEER))
            assertTrue("sends must stay refused while the banner is up", chat.sendsRefused(vm))
            val record = chat.record()
            assertNotNull("the refused key is not remembered: the × has nothing to adopt", record)
            assertEquals("the WHOLE key is remembered", newKeys.publicKey, KeyAcceptApi.get(record!!, "publicKey"))
            assertEquals("its memo is remembered for the adoption", memo, KeyAcceptApi.get(record, "memo"))
            assertEquals("its txid is remembered", "synthetic-zc545-first-kex", KeyAcceptApi.get(record, "txId"))
            assertTrue("first seen is recorded", (KeyAcceptApi.get(record, "firstSeenMs") as Long) > 0L)
            assertEquals("nothing else arrived yet", 0, KeyAcceptApi.get(record, "laterOffers"))
            assertEquals("a refused offer pays nothing", 0, chat.seam.proposals())
        }

    @Test
    fun `a different second key is only counted and the same key again refreshes memo and txid`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val first = E2EEncryption.generateKeyPair()
            val second = E2EEncryption.generateKeyPair()
            val firstMemo = chat.kexFromContact(vm, first, "synthetic-zc545-kex-1")
            val firstSeen = KeyAcceptApi.get(chat.record()!!, "firstSeenMs") as Long

            chat.kexFromContact(vm, second, "synthetic-zc545-kex-2")

            var record = chat.record()!!
            assertEquals("a later different key replaced the first offer", first.publicKey, KeyAcceptApi.get(record, "publicKey"))
            assertEquals("a later different key replaced the first memo", firstMemo, KeyAcceptApi.get(record, "memo"))
            assertEquals("the later key is counted", 1, KeyAcceptApi.get(record, "laterOffers"))
            assertEquals("first seen moved", firstSeen, KeyAcceptApi.get(record, "firstSeenMs"))
            assertEquals("nothing is switched", chat.oldContact.publicKey, chat.storedKey())

            val ackMemo = chat.kexAckFromContact(vm, first, "synthetic-zc545-kexack-1")

            record = chat.record()!!
            assertEquals("still the first key", first.publicKey, KeyAcceptApi.get(record, "publicKey"))
            assertEquals("the newest memo of the same key wins (a KEXACK replaces a KEX)", ackMemo, KeyAcceptApi.get(record, "memo"))
            assertEquals("its txid follows the memo", "synthetic-zc545-kexack-1", KeyAcceptApi.get(record, "txId"))
            assertEquals("first seen is kept", firstSeen, KeyAcceptApi.get(record, "firstSeenMs"))
            assertEquals("the same key again is not a later offer", 1, KeyAcceptApi.get(record, "laterOffers"))
            assertEquals("nothing is switched", chat.oldContact.publicKey, chat.storedKey())
            assertTrue("the banner stays", chat.flag())
            assertEquals("refused offers pay nothing", 0, chat.seam.proposals())
        }

    @Test
    fun `a KEX whose signature fails is neither flagged nor remembered`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val vm = chat.viewModel()
            val claimed = E2EEncryption.generateKeyPair()
            val signer = E2EEncryption.generateKeyPair()
            // The payload names [claimed]'s key but is signed by another key: the self-signature does not verify.
            val forged = chat.kexMemo(signer).replace(signer.publicKey, claimed.publicKey)

            chat.deliver(vm, forged, "synthetic-zc545-forged")

            assertFalse("a KEX that does not verify raised the banner", chat.flag())
            assertEquals("the stored key must stay", chat.oldContact.publicKey, chat.storedKey())
            assertNull("a KEX that does not verify was remembered", chat.record())
        }

    private companion object {
        const val PEER = "usyntheticzc545remembers0001"
    }
}
