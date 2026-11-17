package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.RelayCopyMarksApi
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.PEER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayIdentity.ourRelayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Base64
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * ZC-420 compat-2124 control (fix-all 2.13.0, train T6b; ZC-420-SPEC §2.3, §7): the relay identity this build sends, and
 * re-sends after a relay copy only a public relay took, is read by ZChat 2.12.4.
 *
 * [Frozen2124] is 2.12.4's ZBOOT parser and signature check, copied from the tag v2.12.4 (`28713c7b0`, whose
 * ui-lib/src/main equals a1fb4136f): `ZBootMessage.kt` lines 41-50 (signedData), 64-65 (signedDataFor), 71-77 (parse),
 * 96-107 (parseV3), 118-124 (validCommon) and `E2EEncryption.kt` lines 436-453 (verify), with its KEY_ALGORITHM "EC"
 * (line 120) and ZMSGConstants.CONV_ID_LENGTH 8 (line 23). Left out: the v2 and v4 branches (this build's ZBOOT to a
 * contact with keys is version 3) and verify's error log line. 2.12.4's routeIncomingBoot (ChatViewModel.kt:925-965) parses
 * the memo and verifies a version below 4 with the contact's E2E key. Checked: the memo the relay-first ZBOOT publishes,
 * at epoch 0 and at a rotated epoch, and (once the repair exists) the memo its builder `ownZbootMemo` makes. Passes before
 * and after the fix: no wire change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedeliveredZbootIsReadBy2124Test {
    private val sendScopes = SendHarnessScopes()
    private val net = IdentityRelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        net.install()
    }

    @After
    fun tearDown() {
        net.uninstall()
        RelayCopyMarksApi.unregister()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `the relay identity at epoch 0 parses and verifies with 2_12_4's code`() = readBy2124(rotation = 0)

    @Test
    fun `the relay identity at a rotated epoch parses and verifies with 2_12_4's code`() = readBy2124(rotation = 3)

    private fun readBy2124(rotation: Int) =
        runBlocking {
            val chat = RelayIdentityChat()
            chat.rotation.set(rotation)
            net.answer = Triple(1, "rumor-2124", 1)
            val vm = chat.viewModel()

            vm.ensureNostrBootstrapSent(PEER)
            awaitIdle(vm)

            assertEquals("fixture: one relay ZBOOT", 1, net.published.size)
            val memos = mutableListOf(net.published[0].memo)
            ownZbootMemo(vm)?.let { memos += it }
            val ourKeys = chat.prefs.ourKeys.getValue(PEER)
            val ourE2EPublic = ourKeys.publicKey
            for (memo in memos) {
                val boot = Frozen2124.parse(memo)
                assertNotNull("2.12.4 does not parse the ZBOOT", boot)
                boot!!
                assertEquals(ourRelayKey(rotation), boot.senderNostrPubkeyHex)
                assertEquals(rotation.toLong(), boot.epoch)
                assertTrue("2.12.4 routes only a version below 4 to the E2E check", boot.version < 4)
                assertTrue("2.12.4 does not verify the ZBOOT", Frozen2124.verify(ourE2EPublic, boot.signedData(), boot.signature))
            }
        }

    /** ChatViewModel.ownZbootMemo(peer) once the repair exists (ZC-420), else null. */
    private suspend fun ownZbootMemo(vm: ChatViewModel): String? {
        val method =
            ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == "ownZbootMemo" && it.parameterCount == 2 } ?: return null
        method.isAccessible = true
        return suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try {
                method.invoke(vm, PEER, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as String?
    }

    /** 2.12.4's code (lines named in the class KDoc); only the wrapper and the left-out branches differ. */
    private object Frozen2124 {
        data class ZBootMessage(
            val convId: String,
            val senderNostrPubkeyHex: String,
            val relayUrl: String,
            val signature: String,
            val epoch: Long = 0L,
            val version: Int = 3,
        ) {
            fun signedData(): String =
                if (version >= 3) {
                    signedDataFor(convId, senderNostrPubkeyHex, relayUrl, epoch)
                } else {
                    error("v2 is not sent by this build")
                }
        }

        private const val PREFIX_V3 = "ZBOOT|v3|"
        private val HEX_64 = Regex("^[0-9a-f]{64}$")
        private const val CONV_ID_LENGTH = 8
        private const val KEY_ALGORITHM = "EC"

        fun signedDataFor(
            convId: String,
            pubkey: String,
            relay: String,
            epoch: Long,
        ): String = "$convId|$pubkey|$relay|$epoch"

        fun parse(raw: String): ZBootMessage? =
            when {
                raw.startsWith(PREFIX_V3) -> parseV3(raw.removePrefix(PREFIX_V3))
                else -> null
            }

        private fun parseV3(body: String): ZBootMessage? {
            val parts = body.split("|")
            if (parts.size < 5) return null
            val convId = parts[0]
            val pubkey = parts[1]
            val relay = parts[2]
            val epoch = parts[3].toLongOrNull() ?: return null
            val sig = parts[4]
            if (!validCommon(convId, pubkey, relay, sig)) return null
            if (epoch < 0) return null
            return ZBootMessage(convId, pubkey, relay, sig, epoch = epoch, version = 3)
        }

        private fun validCommon(
            convId: String,
            pubkey: String,
            relay: String,
            sig: String,
        ): Boolean {
            if (convId.length != CONV_ID_LENGTH) return false
            if (!HEX_64.matches(pubkey)) return false
            if (!relay.startsWith("wss://") || relay.length > 80) return false
            if (sig.isEmpty() || sig.length > 200) return false
            return true
        }

        fun verify(
            publicKeyBase64: String,
            message: String,
            signatureBase64: String,
        ): Boolean =
            try {
                val keyFactory = java.security.KeyFactory.getInstance(KEY_ALGORITHM)
                val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
                val publicKeySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
                val publicKey = keyFactory.generatePublic(publicKeySpec)

                val signature = java.security.Signature.getInstance("SHA256withECDSA")
                signature.initVerify(publicKey)
                signature.update(message.toByteArray(Charsets.UTF_8))

                val signatureBytes = Base64.getDecoder().decode(signatureBase64)
                signature.verify(signatureBytes)
            } catch (e: Exception) {
                false
            }
    }
}
