package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.QsClaim
import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D3-3 S1 (fix-all 2.13.0, train T5; t5-specs SPECS §2.2 "Scan rule", §2.5): a padded copy of a current QR is refused
 * when the other phone said, in a verified claim, that its code is not a padded one.
 *
 * The '=' padding of a Quantum Shield QR is the only thing that tells the scanning phone which key its peer will derive
 * (R6-5): a padded QR means "2.12.4 made this", so the scanner derives the unbound key and shows no code. Anyone who
 * re-encodes a current phone's QR with padding therefore turns a setup with a code into one without (qs-downgrade
 * REVIEW), silently. D3's claim fixes that for chats with a claim:
 * - (a) a verified claim `u` (the peer's secret is unpadded) or `n` (no secret) and a padded QR → refused: problem
 *   QR_OLD_FORMAT, false, and nothing is written (no key, no bound record, no setup record);
 * - (b) no claim → accepted as today (the 2.12.4 key: UNBOUND_GOLDEN), the control that keeps 2.12.4 pairing;
 * - (c) a claim `p` (the peer's own secret is a padded one it made before its update) with the commitment of the
 *   scanned secret → accepted; a padded QR of any other secret → refused;
 * - (d) a claim that is not verified (a bad signature, another signer) or a peer whose key changed → as (b);
 * - (e) two phones: both QRs padded → both refuse; the genuine QRs → both bound, with the pinned code;
 * - on success the records are written bound → setup → key (a crash in between leaves the setup pending).
 *
 * Only synthetic secrets and keys are used; D3-3's new names are read by name so this compiles at RED.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuantumShieldPaddingDowngradeTest {
    private val scopes = SendHarnessScopes()
    private val relay = QsClaimRelay()

    private companion object {
        const val QR_OLD_FORMAT = "QR_OLD_FORMAT"

        val PUB_A: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 3 + 1).toByte() })
        val PUB_B: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 5 + 2).toByte() })
        val SECRET_A = ByteArray(32) { it.toByte() }
        val SECRET_B = ByteArray(32) { (it + 100).toByte() }
        val SECRET_OTHER = ByteArray(32) { (it * 13 + 3).toByte() }

        const val UNBOUND_GOLDEN = "af14c2e1bac457da14f581c41be17f1686aa0049b10463b3715c8a6c910eff88"
        const val BOUND_GOLDEN = "01e36d2f93263d86e83a9d96ffd9787f3f59fdf8c4c0fe6f93cc375f9d09825a"
        const val BOUND_CODE_GOLDEN = "845693"

        fun paddedQr(secret: ByteArray): String = "ZCPSK:" + Base64.getEncoder().encodeToString(secret)
    }

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterChatNotifier()
        relay.register()
    }

    @After
    fun tearDown() {
        relay.unregister()
        scopes.close()
        Dispatchers.resetMain()
    }

    /** Alice (PENDING with SECRET_A, real keys) and Bob's key pair, which signs the claims Alice receives. */
    private suspend fun alice(tag: Int): Pair<QsPhone, E2EKeyPair> {
        val ours = E2EEncryption.generateKeyPair()
        val bob = E2EEncryption.generateKeyPair()
        val relayKey = RelayKeyBinding.relayKey(3_300 + tag)
        val prefs = QsPhonePrefs("usyntheticd33s1bob$tag", ours.publicKey, bob.publicKey, relayKey, ours.privateKey)
        return QsPhone.open(prefs).withOwnSecret(SECRET_A) to bob
    }

    private fun bobSays(
        alice: QsPhone,
        bob: E2EKeyPair,
        state: String,
        commit: String,
        seq: Long = System.currentTimeMillis(),
    ): String {
        val claim = QsClaim(state = state, commit = commit, kc = "", seq = seq, sig = "")
        return claim.sign(bob.privateKey, bob.publicKey, alice.ourPub).encode()
    }

    private suspend fun assertRefused(
        phone: QsPhone,
        qr: String,
        why: String,
    ) {
        val before = phone.snapshot()
        val writes = phone.prefs.writes.size
        val key = setOf("psk", "bound", "claim:setup")
        phone.dismiss()
        assertFalse(phone.complete(qr), "$why: a padded copy against the peer's claim must not complete")
        phone.idle()
        assertEquals(QR_OLD_FORMAT, phone.problem, "$why: the refusal names the old format, got ${phone.confirmation}")
        assertNull(phone.code, "$why: no code is shown")
        assertEquals(before, phone.snapshot(), "$why: nothing is stored")
        assertEquals(
            emptyList(),
            phone.prefs.writes
                .drop(writes)
                .filter { it in key },
            "$why: no key, bound record or setup record is written",
        )
    }

    private suspend fun assertAcceptedUnbound(
        phone: QsPhone,
        qr: String,
        why: String,
    ) {
        assertTrue(phone.complete(qr), "$why: accepted as today")
        phone.idle()
        assertNull(phone.problem, "$why: no problem")
        assertEquals(UNBOUND_GOLDEN, phone.pskHex, "$why: the 2.12.4 key, as today")
        assertEquals(false, phone.prefs.qs["bound"], "$why: recorded as the unbound key")
    }

    @Test
    fun `(a) a padded copy of Bob's QR is refused when Bob's verified claim says u or n`() =
        runBlocking<Unit> {
            for ((i, state) in listOf(QsClaim.STATE_UNPADDED, QsClaim.STATE_NONE).withIndex()) {
                val (a, bob) = alice(10 + i)
                val commit = if (state == QsClaim.STATE_NONE) "" else QuantumShield.commitment(SECRET_B)
                a.receive(bobSays(a, bob, state, commit))
                a.idle()
                assertNotNull(a.prefs.claimRaw["peer"], "control: Bob's verified claim ($state) is stored")
                assertRefused(a, paddedQr(SECRET_B), "claim $state")
                assertEquals("PENDING", a.vm.getQuantumShieldStatus(a.peer).name, "claim $state: the shield stays pending")
            }
        }

    @Test
    fun `(b) with no claim a padded QR still pairs with the 2_12_4 key`() =
        runBlocking<Unit> {
            val (a, _) = alice(20)
            assertAcceptedUnbound(a, paddedQr(SECRET_B), "no claim")
            assertNull(a.code)
        }

    @Test
    fun `(c) a p claim accepts the padded QR of its own secret only`() =
        runBlocking<Unit> {
            val (a, bob) = alice(30)
            a.receive(bobSays(a, bob, QsClaim.STATE_PADDED, QuantumShield.commitment(SECRET_B)))
            a.idle()
            assertNotNull(a.prefs.claimRaw["peer"], "control: Bob's p claim is stored")
            assertRefused(a, paddedQr(SECRET_OTHER), "p claim, another secret")
            assertAcceptedUnbound(a, paddedQr(SECRET_B), "p claim, its own secret")
        }

    @Test
    fun `(c) an unpadded QR always takes today's path`() =
        runBlocking<Unit> {
            val (a, bob) = alice(35)
            a.receive(bobSays(a, bob, QsClaim.STATE_NONE, ""))
            a.idle()
            assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)), "an unpadded QR is never refused")
            assertNull(a.problem)
            assertEquals(true, a.prefs.qs["bound"])
        }

    @Test
    fun `(d) an unverified claim or a key-changed peer changes nothing`() =
        runBlocking<Unit> {
            // A bad signature: the signature of another claim.
            val (bad, bob) = alice(40)
            val other = bobSays(bad, bob, QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B), seq = 7)
            val otherSig = checkNotNull(QsClaim.parse(other)).sig
            val now = System.currentTimeMillis()
            val forged = QsClaim(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B), "", now, otherSig).encode()
            bad.receive(forged)
            bad.idle()
            assertAcceptedUnbound(bad, paddedQr(SECRET_B), "bad signature")

            // Another signer.
            val (third, _) = alice(41)
            third.receive(bobSays(third, E2EEncryption.generateKeyPair(), QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B)))
            third.idle()
            assertAcceptedUnbound(third, paddedQr(SECRET_B), "another signer")

            // A stored claim, then the peer's key changed.
            val (changed, bob2) = alice(42)
            changed.receive(bobSays(changed, bob2, QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B)))
            changed.idle()
            assertNotNull(changed.prefs.claimRaw["peer"], "control: the claim was stored before the key changed")
            changed.prefs.keyChanged += changed.peer
            assertAcceptedUnbound(changed, paddedQr(SECRET_B), "key-changed peer")
        }

    @Test
    fun `(e) two phones - padded copies are refused on both, the genuine QRs bind with the pinned code`() =
        runBlocking<Unit> {
            val prefsA = QsPhonePrefs("usyntheticd33s1peerb", PUB_A, PUB_B, RelayKeyBinding.relayKey(3_401), null)
            val prefsB = QsPhonePrefs("usyntheticd33s1peera", PUB_B, PUB_A, RelayKeyBinding.relayKey(3_402), null)
            val a = QsPhone.open(prefsA).withOwnSecret(SECRET_A)
            val b = QsPhone.open(prefsB).withOwnSecret(SECRET_B)
            val qrA = a.initiate()
            val qrB = b.initiate()
            // The fake carrier: each phone's start claim reaches the other.
            a.carry(QsClaim(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B), "", 1L, ""))
            b.carry(QsClaim(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_A), "", 1L, ""))

            assertRefused(a, paddedQr(SECRET_B), "A scans B's padded copy")
            assertRefused(b, paddedQr(SECRET_A), "B scans A's padded copy")

            a.dismiss()
            b.dismiss()
            assertTrue(a.complete(qrB), "A scans B's genuine QR")
            assertTrue(b.complete(qrA), "B scans A's genuine QR")
            a.idle()
            b.idle()
            assertEquals(BOUND_GOLDEN, a.pskHex)
            assertEquals(BOUND_GOLDEN, b.pskHex)
            assertEquals(BOUND_CODE_GOLDEN, a.code)
            assertEquals(BOUND_CODE_GOLDEN, b.code)
        }

    @Test
    fun `a completed setup writes bound, then the setup record, then the key`() =
        runBlocking<Unit> {
            val (a, _) = alice(50)
            val before = System.currentTimeMillis()
            assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)))
            val after = System.currentTimeMillis()
            a.idle()
            assertEquals(
                listOf("bound", "claim:setup", "psk"),
                a.prefs.writes.filter { it == "psk" || it == "bound" || it == "claim:setup" },
                "the setup record is written between the bound record and the key",
            )
            val setup = a.prefs.claimRaw["setup"] as String?
            assertNotNull(setup, "the setup record exists")
            val activeAt = setup.substringBefore('|').toLong()
            assertTrue(activeAt in before..after, "the setup record holds the local setup time")
            assertEquals(QuantumShield.commitment(SECRET_B), setup.substringAfter('|'), "and the commitment of the QR we scanned")
        }
}
