package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-634, ZC-950, ZC-949 (FIRST-CONTACT-SPEC revision 4a, 2.2, 5.5 items 1 and 5, 6.4), part A: while a key waits for
 * Accept it binds nothing (FC-4), and a hold whose key this wallet's own mined answer settled is dropped.
 *
 * Today a key exchange's own relay fields are bound with the hold up (handleKEXMessage "applyKEXNostr(senderAddress,
 * stored"), and a ZBOOT signed with the waiting key binds its relay identity (routeIncomingBoot never reads the hold),
 * so the sender's relay messages are that address's before any tap; and a hold whose key this wallet's own answer already
 * answered on the chain stays up, its banner saying nothing has been paid (ZC-949).
 *
 * Wanted: a v2/v3 ZBOOT at an address whose key waits is refused unrecorded (a later pass adopts it after Accept), unless
 * it came sealed by the relay key already bound there; the relay fields of a waiting key exchange are never bound; a hold
 * whose key this wallet's own mined answer answered, and that answer is settled (design 5.5 item 2a: this wallet never
 * began a key exchange of its own there, or each one it began was answered under its own id by the address it went to),
 * is dropped. The claim marks (items 2 to 4, 6 to 9: replay (c), an Open contact where this phone switched E2E on, the
 * held ZBOOT, "Mark as verified", the texts) are not in this part. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimedKeyNeverMovesARelayIdentityTest {
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

    // ---- (a), (c), (aa): a ZBOOT signed with a waiting key ----------------------------------------------------------------

    @Test
    fun `(a) a ZBOOT signed with a waiting key binds nothing and is not recorded`() {
        val waiting = E2EEncryption.generateKeyPair()
        val boots = Boots()
        boots.e2ePeer[PEER] = waiting.publicKey
        boots.ours[PEER] = E2EEncryption.generateKeyPair().publicKey
        boots.holds[PEER] = waiting.publicKey
        val raw = v3Boot(waiting, nostr(21).publicKey.hex())

        boots.route(PEER, raw)

        assertTrue("a ZBOOT signed with a key that waits for Accept bound a relay identity: ${boots.binds}", boots.binds.isEmpty())
        assertTrue("a refused ZBOOT was recorded as processed, so it could not be adopted after Accept", boots.recorded.isEmpty())
    }

    @Test
    fun `(c) after Accept the same ZBOOT, read again, binds`() {
        val waiting = E2EEncryption.generateKeyPair()
        val boots = Boots()
        boots.e2ePeer[PEER] = waiting.publicKey
        boots.ours[PEER] = E2EEncryption.generateKeyPair().publicKey
        boots.holds[PEER] = waiting.publicKey
        val relay = nostr(22).publicKey.hex()
        val raw = v3Boot(waiting, relay)
        boots.route(PEER, raw)

        boots.holds.remove(PEER) // the tap
        boots.route(PEER, raw)

        assertTrue("the ZBOOT read again after Accept must bind: ${boots.binds}", boots.binds.any { it.second == relay })
    }

    @Test
    fun `(aa) a saved contact's waiting request, then its ZBOOT, binds nothing before Accept`() {
        val waiting = E2EEncryption.generateKeyPair()
        val boots = Boots(saved = setOf(PEER))
        boots.e2ePeer[PEER] = waiting.publicKey
        boots.ours[PEER] = E2EEncryption.generateKeyPair().publicKey
        boots.holds[PEER] = waiting.publicKey

        boots.route(PEER, v3Boot(waiting, nostr(23).publicKey.hex()))

        assertTrue("a saved contact's waiting key bound a relay identity: ${boots.binds}", boots.binds.isEmpty())
    }

    @Test
    fun `(e) an Open contact whose claimed key waits keeps its relay identity before Accept`() {
        val claimed = E2EEncryption.generateKeyPair()
        val boots = Boots()
        val bound = nostr(26).publicKey.hex()
        boots.nostr[PEER] = bound
        boots.e2ePeer[PEER] = claimed.publicKey
        boots.ours[PEER] = E2EEncryption.generateKeyPair().publicKey
        boots.holds[PEER] = claimed.publicKey

        boots.route(PEER, v3Boot(claimed, nostr(27).publicKey.hex()))

        assertEquals("a waiting claim replaced an Open contact's relay identity: ${boots.binds}", bound, boots.nostr[PEER])
        assertTrue("nothing may be bound or replaced: ${boots.binds}", boots.binds.isEmpty())
    }

    @Test
    fun `control - a ZBOOT sealed by the relay key already bound there is adopted while the request waits`() {
        val waiting = E2EEncryption.generateKeyPair()
        val boots = Boots()
        boots.e2ePeer[PEER] = waiting.publicKey
        boots.ours[PEER] = E2EEncryption.generateKeyPair().publicKey
        boots.holds[PEER] = waiting.publicKey
        val relay = nostr(24).publicKey.hex()

        assertTrue("fixture: routeIncomingBoot has its sealedByBoundKey form", boots.route(PEER, v3Boot(waiting, relay), sealed = true))

        assertTrue("a ZBOOT the bound relay key sealed must still be adopted: ${boots.binds}", boots.binds.any { it.second == relay })
    }

    // ---- (b): the relay fields of a waiting key exchange ----------------------------------------------------------------

    @Test
    fun `(b) a waiting request whose key exchange carries relay fields binds nothing before Accept`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 63401)
            val keys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(peer, firstContact = true)
            val seam = HandshakeSeam(peer)
            val vm = handshakeViewModel(prefs, seam)
            val relayKey = nostr(25).publicKey.hex()
            // The app's builder writes an address or relay fields, never both; a forged memo carries both (2.2 step 2).
            val signed = E2EEncryption.createKEXPayload(peer, keys.publicKey, keys.privateKey)
            val relayB64 =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(RELAY.toByteArray(Charsets.UTF_8))
            val payload = signed.substringBeforeLast(":") + ":" + relayKey + ":" + relayB64 + ":" + peer

            handleKexMemo(vm, ZMSGProtocol.createV4KEXMessage("ZC634B01", peer, payload), "synthetic-zc634-b")
            awaitIdle(vm)

            assertEquals("fixture sanity: the request waits", keys.publicKey, prefs.awaitingAccept[peer])
            val bindCalls = setOf("isOwnNostrPubkey", "findPeerByNostrPubkey", "setPeerNostrPubkey", "setPeerNostrRelay")
            assertFalse(
                "the waiting key exchange's relay fields went to the relay binding before Accept: ${prefs.unexpectedCalls}",
                prefs.unexpectedCalls.any { it in bindCalls },
            )
        }

    // ---- ZC-949: a hold whose key this wallet's own mined answer settled ------------------------------------------------

    @Test
    fun `(ZC-949) a hold whose key this wallet's own mined answer already answered, and settled, is dropped`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, A)
            f.open()
            val theirs = E2EEncryption.generateKeyPair()
            f.received("zc949-kex", kexFrom(A, theirs, "ZC949K01"), minedAt = NEW + 10)
            f.pass()
            assertEquals("fixture sanity: the request waits", theirs.publicKey, f.prefs.awaitingAccept[A])
            val ours = requireNotNull(f.prefs.ourKeyStore[A]) { "fixture: the handler made our key pair" }

            // A second device on the same phrase (or a later pass) answered it: our own mined answer under its id.
            val payload = E2EEncryption.createKEXAckPayload(OWN_UA, ours.first, ours.second)
            val ownAck = ZMSGProtocol.createV4KEXAckMessage("ZC949K01", OWN_UA, payload)
            f.sent("zc949-own-ack", ownAck, A, minedAt = NEW + 12)
            f.pass()

            assertNull("the hold stays although this wallet's own mined answer settled that key", f.prefs.awaitingAccept[A])
            assertEquals("the answered key is recorded", theirs.publicKey, f.prefs.sentKexAck[A])
            assertEquals("nothing is paid", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `(ZC-949) guard - with an own key exchange of this wallet to that address the answer is not settled and the hold stays`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, A)
            val (own, _) = f.ids.store.getOrCreateConversationIdForMessage(A)
            f.open()
            val theirs = E2EEncryption.generateKeyPair()
            f.received("zc949g-kex", kexFrom(A, theirs, "ZC949K02"), minedAt = NEW + 10)
            f.pass()
            assertEquals("fixture sanity: the request waits", theirs.publicKey, f.prefs.awaitingAccept[A])
            val ours = requireNotNull(f.prefs.ourKeyStore[A])
            f.ownKex("zc949g-own-kex", A, own, minedAt = NEW + 11)
            val payload = E2EEncryption.createKEXAckPayload(OWN_UA, ours.first, ours.second)
            val ownAck = ZMSGProtocol.createV4KEXAckMessage("ZC949K02", OWN_UA, payload)
            f.sent("zc949g-own-ack", ownAck, A, minedAt = NEW + 12)
            f.pass()

            assertEquals("an answer that is not settled must not drop the hold", theirs.publicKey, f.prefs.awaitingAccept[A])
            assertEquals(0, f.seam.proposalRequests.get())
        }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private companion object {
        val PEER = SyntheticUnifiedAddress.mainnet(seed = 63400)
        val A = SyntheticUnifiedAddress.mainnet(seed = 63409)

        /**
         * The address this wallet signs its own answers with: a full-length unified address, as the app's own answer carries
         * (the fixture's SELF is too short for the payload's address field, so an answer signed for it reads as no answer).
         */
        val OWN_UA = SyntheticUnifiedAddress.mainnet(seed = 63408)
        const val BOOT_CONV = "ZC634BT1"
        const val RELAY = "wss://relay.zsend.xyz"

        fun nostr(seed: Int): NOSTRIdentity = NOSTRIdentity.fromSeed(ByteArray(64) { (it + seed).toByte() })

        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        fun kexFrom(
            sender: String,
            keys: E2EKeyPair,
            convId: String,
        ): String = ZMSGProtocol.createV4KEXMessage(convId, sender, E2EEncryption.createKEXPayload(sender, keys.publicKey, keys.privateKey))

        fun v3Boot(
            signer: E2EKeyPair,
            relayKey: String,
        ): String {
            val signed = ZBootMessage.signedDataFor(BOOT_CONV, relayKey, RELAY, 1L)
            val sig = E2EEncryption.sign(signer.privateKey, signed)
            return ZBootMessage(BOOT_CONV, relayKey, RELAY, sig, epoch = 1L, version = 3).serialize()
        }
    }

    /**
     * routeIncomingBoot over a lenient store: contact keys [e2ePeer], our keys [ours], holds [holds], bound relay keys
     * [nostr]; [binds] records every relay key bound, [recorded] every ZBOOT signature recorded as processed.
     */
    private class Boots(
        private val saved: Set<String> = emptySet(),
    ) {
        val e2ePeer = ConcurrentHashMap<String, String>()
        val ours = ConcurrentHashMap<String, String>()
        val holds = ConcurrentHashMap<String, String>()
        val nostr = ConcurrentHashMap<String, String>()
        val binds: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())
        val recorded: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val prefs: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "getE2EPeerPublicKey" -> e2ePeer[a[0] as String]
                    "getE2EOurPublicKey" -> ours[a[0] as String]
                    "getKexAskedKey" -> e2ePeer[a[0] as String]
                    "getInboundKexAwaitingAccept" -> holds[a[0] as String]
                    "getPeerNostrPubkey" -> nostr[a[0] as String]
                    "findPeerByNostrPubkey" -> nostr.entries.firstOrNull { it.value.equals(a[0] as String, ignoreCase = true) }?.key
                    "hasProcessedBootSig" -> (a[0] as String) in recorded
                    "markBootSigProcessed" -> {
                        recorded += a[0] as String
                        null
                    }
                    "setPeerNostrPubkey" -> {
                        binds += (a[0] as String) to (a[1] as String?)
                        (a[1] as String?)?.let { nostr[a[0] as String] = it }
                        null
                    }
                    "resolvePeerAddress" -> a[0]
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "ZC634BootPrefs"
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            List::class.java -> emptyList<Any>()
                            Set::class.java -> emptySet<Any>()
                            Map::class.java -> emptyMap<Any, Any>()
                            else -> null
                        }
                }
            } as ZchatPreferences

        private val vm: ChatViewModel =
            run {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
                (allocate.invoke(unsafe, ChatViewModel::class.java) as ChatViewModel).also {
                    ChatViewModel::class.java.declaredFields
                        .first { f -> f.type == ZchatPreferences::class.java }
                        .apply { isAccessible = true }
                        .set(it, prefs)
                    if (saved.isNotEmpty()) AutoSpendVm.setField(it, "contactBook", SavedContacts(saved))
                }
            }

        /**
         * routeIncomingBoot([peer], [raw]) as attributeBoot calls it ([sealed] false) or as the relay reader's attributed
         * call ([sealed] true). @return false when [sealed] is true and the sealedByBoundKey form is missing.
         */
        fun route(
            peer: String,
            raw: String,
            sealed: Boolean = false,
        ): Boolean {
            val three =
                ChatViewModel::class.java.declaredMethods.firstOrNull {
                    it.name == "routeIncomingBoot" && it.parameterCount == 3 && it.parameterTypes[2] == java.lang.Boolean.TYPE
                }
            if (sealed && three == null) return false
            val method = three ?: ChatViewModel::class.java.getDeclaredMethod("routeIncomingBoot", String::class.java, String::class.java)
            method.isAccessible = true
            runCatching {
                try {
                    if (three != null) method.invoke(vm, peer, raw, sealed) else method.invoke(vm, peer, raw)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            return true
        }
    }
}
