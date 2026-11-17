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
import java.lang.reflect.Modifier
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D3-3 S2 (fix-all 2.13.0, train T5; t5-specs SPECS §2.2 "ACTIVE rescan", §2.5): F11 answers a rescan that matches
 * neither key of an active shield with the neutral NOT_THIS_CHATS_CODE, because without a statement from the other
 * phone it cannot tell another chat's code from a one-side re-setup. With D3's verified claim it can: when the other
 * phone's newest claim commits to exactly the secret just scanned, the other phone set Extra Security up again
 * (OTHER_PHONE_SET_UP_AGAIN). Nothing is stored either way, and the confirmation still holds only {code, problem}.
 *
 * An active shield has no UI entry to scan again (T3 phone finding 5; the menu offers Extra Security only when it is
 * off), so this test is the only proof of the branch; on the phones the one-side re-setup shows as the KEYS_DIFFER alert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuantumShieldRescanSetUpAgainTest {
    private val scopes = SendHarnessScopes()
    private val relay = QsClaimRelay()

    private companion object {
        const val SET_UP_AGAIN = "OTHER_PHONE_SET_UP_AGAIN"
        const val NOT_THIS_CHATS_CODE = "NOT_THIS_CHATS_CODE"
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

    /** Alice with an ACTIVE, bound shield set up with SECRET_B, and Bob's key pair. */
    private suspend fun activeAlice(tag: Int): Pair<QsPhone, E2EKeyPair> {
        val ours = E2EEncryption.generateKeyPair()
        val bob = E2EEncryption.generateKeyPair()
        val relayKey = RelayKeyBinding.relayKey(3_500 + tag)
        val prefs = QsPhonePrefs("usyntheticd33s2bob$tag", ours.publicKey, bob.publicKey, relayKey, ours.privateKey)
        val a = QsPhone.open(prefs).withOwnSecret(SECRET_A)
        assertTrue(a.complete(QuantumShield.toQRPayload(SECRET_B)), "the genuine setup completes")
        a.idle()
        a.dismiss()
        return a to bob
    }

    private suspend fun bobClaims(
        a: QsPhone,
        bob: E2EKeyPair,
        secret: ByteArray,
    ) {
        val ours = a.ourPub
        a.receive(
            QsClaim(QsClaim.STATE_UNPADDED, QuantumShield.commitment(secret), "", System.currentTimeMillis(), "")
                .sign(bob.privateKey, bob.publicKey, ours)
                .encode(),
        )
        a.idle()
        assertNotNull(a.prefs.claimRaw["peer"], "control: Bob's verified claim is stored")
    }

    private suspend fun assertRescan(
        a: QsPhone,
        qr: String,
        expected: String,
        why: String,
    ) {
        val before = a.snapshot()
        a.dismiss()
        assertFalse(a.complete(qr), "$why: a rescan that matches neither key is not a completed setup")
        a.idle()
        assertEquals(expected, a.problem, "$why: got ${a.confirmation}")
        assertNull(a.code, "$why: no code for a key this phone does not hold")
        assertEquals(before, a.snapshot(), "$why: nothing is stored")
        assertEquals("ACTIVE", a.vm.getQuantumShieldStatus(a.peer).name, "$why: the shield stays on with its key")
    }

    @Test
    fun `a rescan of the QR Bob's verified claim commits to says the other phone set it up again`() =
        runBlocking<Unit> {
            val (a, bob) = activeAlice(1)
            bobClaims(a, bob, SECRET_B_AGAIN)
            assertRescan(a, QuantumShield.toQRPayload(SECRET_B_AGAIN), SET_UP_AGAIN, "Bob's new QR")
            assertRescan(a, paddedQr(SECRET_B_AGAIN), SET_UP_AGAIN, "a padded copy of Bob's new QR")
        }

    @Test
    fun `without that claim the rescan stays not this chat's code`() =
        runBlocking<Unit> {
            val (noClaim, _) = activeAlice(2)
            assertRescan(noClaim, QuantumShield.toQRPayload(SECRET_B_AGAIN), NOT_THIS_CHATS_CODE, "no claim")

            val (otherCommit, bob) = activeAlice(3)
            bobClaims(otherCommit, bob, SECRET_B_AGAIN)
            assertRescan(otherCommit, QuantumShield.toQRPayload(SECRET_OTHER), NOT_THIS_CHATS_CODE, "a QR the claim does not commit to")

            val (changed, bob2) = activeAlice(4)
            bobClaims(changed, bob2, SECRET_B_AGAIN)
            changed.prefs.keyChanged += changed.peer
            assertRescan(changed, QuantumShield.toQRPayload(SECRET_B_AGAIN), NOT_THIS_CHATS_CODE, "the peer's key changed")
        }

    @Test
    fun `the problem is an enum value and the confirmation still holds only the code and the problem`() {
        val problems =
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.crypto.QsProblem")
                .let { checkNotNull(it.enumConstants) }
                .map { it.toString() }
                .toSet()
        assertTrue(SET_UP_AGAIN in problems, "QsProblem has $SET_UP_AGAIN: $problems")
        assertTrue("QR_OLD_FORMAT" in problems, "QsProblem has QR_OLD_FORMAT: $problems")
        assertTrue(NOT_THIS_CHATS_CODE in problems, "F11's value stays: $problems")
        val cls = Class.forName("co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldConfirmation")
        val fields =
            cls.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
                .toSet()
        assertEquals(setOf("code", "problem"), fields)
    }
}
