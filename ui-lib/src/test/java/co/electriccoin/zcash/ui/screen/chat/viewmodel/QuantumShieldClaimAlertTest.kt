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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D3-3 S3 (fix-all 2.13.0, train T5; t5-specs SPECS §2.2 "Alerts", §2.5): what an active shield shows once the other
 * phone's verified claim is in.
 *
 * The alerts live in their own holder (`quantumShieldAlerts`, never in the scan confirmation), are recomputed on every
 * stored claim, local Quantum Shield change and refresh tick, and are never sticky. NONE unless our shield is ACTIVE and
 * a verified claim exists; then
 * - KEYS_DIFFER when (a) the claim's key check is set and differs from ours (no time bound), or (b) the claim says `n`
 *   and arrived after our setup, or (c) the claim has no key check, says `u`/`p`, commits to a secret other than the
 *   one we scanned and arrived after our setup; (b) and (c) only for a claim made no earlier than 10 minutes before our
 *   setup (a late claim from before this setup raises nothing), and (c) needs the setup record (a shield set up before
 *   the update has none);
 * - DOWNGRADED when the key checks are equal, our key is the unbound one, and both secrets are unpadded (`u`/`u`);
 * - CONFIRMED when the key checks are equal otherwise (a `p` peer: CONFIRMED, never DOWNGRADED).
 * A key-changed peer's claim never counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuantumShieldClaimAlertTest {
    private val scopes = SendHarnessScopes()
    private val relay = QsClaimRelay()

    private companion object {
        const val TEN_MINUTES = 10 * 60 * 1000L
        val SECRET_A = ByteArray(32) { it.toByte() }
        val SECRET_B = ByteArray(32) { (it + 100).toByte() }
        val SECRET_B_AGAIN = ByteArray(32) { (it * 7 + 11).toByte() }
        val SECRET_OTHER = ByteArray(32) { (it * 13 + 3).toByte() }

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

    private class Alice(
        val phone: QsPhone,
        val bob: E2EKeyPair,
        private val scannedAt: Long,
    ) {
        val ourPub: String get() = phone.ourPub

        /** The local setup time from the setup record (the time of the scan while a build writes none). */
        val activeAt: Long get() = (phone.prefs.claimRaw["setup"] as String?)?.substringBefore('|')?.toLong() ?: scannedAt

        fun keyCheck(psk: ByteArray): String = QuantumShield.keyCheck(psk, ourPub, bob.publicKey)

        val boundKc: String get() = keyCheck(QuantumShield.derivePSK(SECRET_A, SECRET_B, ourPub, bob.publicKey))

        /** Bob's claim, signed with Bob's key for Alice's, as the relay reader hands it over. */
        suspend fun bobSays(
            state: String,
            commit: String,
            kc: String,
            seq: Long = System.currentTimeMillis(),
        ) {
            Thread.sleep(2) // the receive time is strictly after the setup
            phone.receive(QsClaim(state, commit, kc, seq, "").sign(bob.privateKey, bob.publicKey, ourPub).encode())
            phone.idle()
        }
    }

    /** Alice with an ACTIVE, bound shield set up by scanning Bob's SECRET_B (unless [scan] is false: PENDING). */
    private suspend fun alice(
        tag: Int,
        scan: Boolean = true,
    ): Alice {
        val ours = E2EEncryption.generateKeyPair()
        val bob = E2EEncryption.generateKeyPair()
        val relayKey = RelayKeyBinding.relayKey(3_600 + tag)
        val prefs = QsPhonePrefs("usyntheticd33s3bob$tag", ours.publicKey, bob.publicKey, relayKey, ours.privateKey)
        val phone = QsPhone.open(prefs).withOwnSecret(SECRET_A)
        val scannedAt = System.currentTimeMillis()
        if (scan) {
            assertTrue(phone.complete(QuantumShield.toQRPayload(SECRET_B)))
            phone.idle()
            phone.dismiss()
        }
        return Alice(phone, bob, scannedAt)
    }

    private fun Alice.assertAlert(
        expected: String,
        why: String,
    ) = assertEquals(expected, phone.alert(), why)

    // ------------------------------------------------------------------------------------------------ KEYS_DIFFER

    @Test
    fun `(a) a key check that differs from ours is KEYS_DIFFER, with no time bound`() =
        runBlocking<Unit> {
            val a = alice(1)
            val otherKc = a.keyCheck(QuantumShield.derivePSK(SECRET_A, SECRET_OTHER, a.ourPub, a.bob.publicKey))
            a.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B), otherKc)
            a.assertAlert("KEYS_DIFFER", "rule (a)")

            val late = alice(2)
            late.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B), otherKc, seq = late.activeAt - 2 * TEN_MINUTES)
            late.assertAlert("KEYS_DIFFER", "rule (a) keeps no time bound")
        }

    @Test
    fun `(b) an n claim after our setup is KEYS_DIFFER`() =
        runBlocking<Unit> {
            val a = alice(3)
            a.bobSays(QsClaim.STATE_NONE, "", "")
            a.assertAlert("KEYS_DIFFER", "rule (b)")
        }

    @Test
    fun `(c) a claim committing to another secret after our setup is KEYS_DIFFER`() =
        runBlocking<Unit> {
            val a = alice(4)
            a.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B_AGAIN), "")
            a.assertAlert("KEYS_DIFFER", "rule (c): the other phone set it up again")
        }

    @Test
    fun `a pending claim for the secret we scanned raises nothing`() =
        runBlocking<Unit> {
            val a = alice(5)
            a.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B), "")
            a.assertAlert("NONE", "Bob has not scanned yet")
        }

    @Test
    fun `a claim made before this setup that arrives late raises nothing for (b) and (c)`() =
        runBlocking<Unit> {
            val b = alice(6)
            b.bobSays(QsClaim.STATE_NONE, "", "", seq = b.activeAt - TEN_MINUTES - 1_000)
            b.assertAlert("NONE", "rule (b) for a claim made before this setup")

            val c = alice(7)
            c.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_OTHER), "", seq = c.activeAt - TEN_MINUTES - 1_000)
            c.assertAlert("NONE", "rule (c) for a claim made before this setup")

            val edge = alice(8)
            edge.bobSays(QsClaim.STATE_NONE, "", "", seq = edge.activeAt - TEN_MINUTES)
            edge.assertAlert("KEYS_DIFFER", "a claim made exactly 10 minutes before the setup still counts")
        }

    @Test
    fun `nothing without an active shield`() =
        runBlocking<Unit> {
            val a = alice(9, scan = false)
            a.bobSays(QsClaim.STATE_NONE, "", "")
            a.assertAlert("NONE", "our shield is pending")
        }

    // ------------------------------------------------------------------------------------ CONFIRMED, DOWNGRADED

    @Test
    fun `equal key checks are CONFIRMED, and a later matching claim clears KEYS_DIFFER`() =
        runBlocking<Unit> {
            val a = alice(10)
            a.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B_AGAIN), "")
            a.assertAlert("KEYS_DIFFER", "rule (c) first")
            a.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B), a.boundKc)
            a.assertAlert("CONFIRMED", "a later matching claim clears KEYS_DIFFER (never sticky)")
        }

    @Test
    fun `two phones that each read a padded copy are DOWNGRADED on both`() =
        runBlocking<Unit> {
            val (a, b) = QsPhone.pair(E2EEncryption.generateKeyPair(), E2EEncryption.generateKeyPair(), 40)
            a.withOwnSecret(SECRET_A)
            b.withOwnSecret(SECRET_B)
            // No claim yet on either side: each padded copy is accepted with the 2.12.4 key, as today.
            assertTrue(a.complete(paddedQr(SECRET_B)))
            assertTrue(b.complete(paddedQr(SECRET_A)))
            a.idle()
            b.idle()
            assertEquals(false, a.prefs.qs["bound"])
            assertEquals(false, b.prefs.qs["bound"])
            // The real claims each phone published after its setup reach the other.
            a.receive(relay.newestFrom(b))
            b.receive(relay.newestFrom(a))
            a.idle()
            b.idle()
            assertEquals("DOWNGRADED", a.alert(), "A: both secrets unpadded, the unbound key")
            assertEquals("DOWNGRADED", b.alert(), "B: both secrets unpadded, the unbound key")
        }

    @Test
    fun `a p peer is CONFIRMED, never DOWNGRADED`() =
        runBlocking<Unit> {
            val (a, b) = QsPhone.pair(E2EEncryption.generateKeyPair(), E2EEncryption.generateKeyPair(), 41)
            a.withOwnSecret(SECRET_A)
            b.withOwnSecret(SECRET_B, padded = true) // made by 2.12.4 before B was updated
            assertTrue(a.complete(paddedQr(SECRET_B)))
            assertTrue(b.complete(QuantumShield.toQRPayload(SECRET_A)))
            a.idle()
            b.idle()
            a.receive(relay.newestFrom(b))
            b.receive(relay.newestFrom(a))
            a.idle()
            b.idle()
            assertEquals("CONFIRMED", a.alert(), "A: the peer's secret is padded")
            assertEquals("CONFIRMED", b.alert(), "B: our own secret is padded")
        }

    // ----------------------------------------------------------------------------------- gate, record, not sticky

    @Test
    fun `a key-changed peer's claim counts for nothing, on the next tick`() =
        runBlocking<Unit> {
            val a = alice(11)
            a.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_B), a.boundKc)
            a.assertAlert("CONFIRMED", "control")
            a.phone.prefs.keyChanged += a.phone.peer
            a.phone.tick()
            a.assertAlert("NONE", "the peer's key changed")
        }

    @Test
    fun `turning our shield off clears the alert`() =
        runBlocking<Unit> {
            val a = alice(12)
            a.bobSays(QsClaim.STATE_NONE, "", "")
            a.assertAlert("KEYS_DIFFER", "control")
            a.phone.reset()
            a.phone.idle()
            a.assertAlert("NONE", "a local change recomputes: no shield, no alert")
        }

    @Test
    fun `a shield set up before the update has no setup record - (b) applies, (c) does not`() =
        runBlocking<Unit> {
            for ((i, case) in listOf("b", "c").withIndex()) {
                val a = alice(20 + i, scan = false)
                a.phone.prefs.qs["psk"] =
                    Base64.getEncoder().encodeToString(QuantumShield.derivePSK(SECRET_A, SECRET_B, a.ourPub, a.bob.publicKey))
                a.phone.prefs.qs["bound"] = true
                val status = a.phone.vm.getQuantumShieldStatus(a.phone.peer)
                assertEquals("ACTIVE", status.name)
                if (case == "b") {
                    a.bobSays(QsClaim.STATE_NONE, "", "", seq = 5L)
                    a.assertAlert("KEYS_DIFFER", "rule (b) with no setup record")
                } else {
                    a.bobSays(QsClaim.STATE_UNPADDED, QuantumShield.commitment(SECRET_OTHER), "")
                    a.assertAlert("NONE", "rule (c) needs the setup record")
                }
            }
        }

    @Test
    fun `alerts are their own holder, not the scan confirmation`() =
        runBlocking<Unit> {
            val a = alice(13)
            a.bobSays(QsClaim.STATE_NONE, "", "")
            assertNotEquals(QsPhone.NO_ALERT_HOLDER, a.phone.alert())
            assertTrue(a.phone.confirmation == null, "a claim never sets the scan confirmation")
            val alertClass = Class.forName("co.electriccoin.zcash.ui.screen.chat.crypto.QsAlert")
            val alerts = checkNotNull(alertClass.enumConstants).map { it.toString() }
            assertEquals(listOf("NONE", "KEYS_DIFFER", "DOWNGRADED", "CONFIRMED"), alerts)
            assertNotNull(a.phone.prefs.claimRaw["setup"])
        }
}
