package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-893 (FC-2B; FIRST-CONTACT-SPEC revision 4, 5.4b, 6.3b) — a key this phone neither asked for nor answered counts
 * for nothing: it skips no hold, no ZBOOT is believed on its word, and it is dropped when this phone starts its own
 * exchange there.
 *
 * "Planted" = the state an answer nobody asked for (or an invitation's key field) left before this build: a stored
 * contact key, E2E on, no key pair of ours, no asked record. Today a key exchange repeating it finds E2E on and sets no
 * hold (isFirstKeyExchange); a ZBOOT signed with it binds a relay identity; a planted key blocks an Open contact's v4
 * rotation (the downgrade guard); and this phone's own start makes its key pair next to it, so the planted key then
 * counts. Wanted (5.4b items 1 to 6), and the accept of a request writes the asked record. Every address, key and id is
 * synthetic.
 *
 * (d) drives both unsealed paths (attributeBoot: the history's on-chain ZBOOT and an unattributed relay one) and the
 * attributed call of a reader that did not seal it; route T's later chat open pays nothing when the mode stays unset,
 * so the mode is asserted here. (g) uses an established chat: with a planted key and no key pair of ours the key-change
 * dialog has no safety number to show (refusedKeyOffer needs our key), so its accept cannot be reached; the case pins
 * 5.4b item 2 for the accept that exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyExchangeAfterAnUnaskedAnswerWaitsForAcceptTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun planted(
        peer: String,
        key: E2EKeyPair,
    ): HandshakePrefs =
        HandshakePrefs(peer, firstContact = true).also {
            it.e2ePeerKey[peer] = key.publicKey
            it.e2eEnabled += peer
        }

    // ---- (a), (b): a key exchange repeating a planted key waits for Accept --------------------------------------------

    @Test
    fun `(a) a key exchange with a planted key waits for Accept, which makes our key pair and pays one answer`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 89301)
            val key = E2EEncryption.generateKeyPair()
            val prefs = planted(peer, key)
            val seam = HandshakeSeam(peer)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(peer, key), "synthetic-fc2b-a")
            awaitIdle(vm)

            assertEquals("a key exchange over a planted key must wait for Accept", key.publicKey, prefs.awaitingAccept[peer])
            assertEquals("nothing may be proposed before the tap", 0, seam.proposalRequests.get())

            vm.acceptInboundKexRequest(peer)
            awaitIdle(vm)
            assertNotNull("Accept needs our key pair", prefs.ourKeyStore[peer])
            assertEquals("Accept pays exactly one answer", 1, seam.proposalRequests.get())
        }

    @Test
    fun `(b) the same at a saved contact waits for Accept too`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 89302)
            val key = E2EEncryption.generateKeyPair()
            val prefs = planted(peer, key)
            val seam = HandshakeSeam(peer)
            val vm =
                handshakeViewModel(prefs, seam).also {
                    AutoSpendVm.setField(it, "contactBook", SavedContacts(setOf(peer)))
                }

            handleKexMemo(vm, kexMemoFrom(peer, key), "synthetic-fc2b-b")
            awaitIdle(vm)

            assertEquals("a saved contact's planted key must wait for Accept too", key.publicKey, prefs.awaitingAccept[peer])
            assertEquals(0, seam.proposalRequests.get())
        }

    // ---- (c): our own start drops the planted key -----------------------------------------------------------------------

    @Test
    fun `(c) our own start over a planted key removes it before our key pair exists`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 89303)
            val key = E2EEncryption.generateKeyPair()
            val prefs = planted(peer, key)
            val seam = HandshakeSeam(peer)
            val vm = handshakeViewModel(prefs, seam)

            vm.setE2EEnabled(peer, true)
            awaitIdle(vm)

            assertNotNull("fixture sanity: our start made our key pair", prefs.ourKeyStore[peer])
            assertNull("the planted key still counts next to our new key pair", prefs.e2ePeerKey[peer])
            assertEquals("our key exchange is proposed once", 1, seam.proposalRequests.get())
            val believed = Boots.routeOn(vm, peer, v3Boot(key, nostr(11).publicKey.hex()), sealed = false)
            assertEquals("a ZBOOT signed with the dropped key must not be believed", false, believed)
        }

    // ---- (d), (e), (f): ZBOOTs ------------------------------------------------------------------------------------------

    @Test
    fun `(d) a ZBOOT signed with a planted key binds no relay identity`() {
        val planted = E2EEncryption.generateKeyPair()
        val boot = Boots()
        boot.e2ePeer[PEER] = planted.publicKey
        boot.convIds[BOOT_CONV] = PEER
        val raw = v3Boot(planted, nostr(7).publicKey.hex())

        boot.route(PEER, raw, sealed = false)
        val attributed = boot.attribute(raw)

        assertTrue("a ZBOOT signed with a key this phone never asked for bound a relay identity: ${boot.binds}", boot.binds.isEmpty())
        assertNull("attributeBoot (an on-chain or unattributed relay ZBOOT) adopted it for a contact", attributed)
        assertTrue("the chat's mode changed on a planted key's word: ${boot.modes}", boot.modes.isEmpty())
    }

    @Test
    fun `(e) the same ZBOOT sealed by the relay key already bound there is adopted`() {
        val planted = E2EEncryption.generateKeyPair()
        val boot = Boots()
        boot.e2ePeer[PEER] = planted.publicKey
        val newRelay = nostr(8).publicKey.hex()
        val raw = v3Boot(planted, newRelay)

        val answered = boot.route(PEER, raw, sealed = true)

        assertTrue("routeIncomingBoot has no sealedByBoundKey parameter", answered)
        assertTrue("a ZBOOT the bound relay key sealed must still be adopted: ${boot.binds}", boot.binds.any { it.second == newRelay })
    }

    @Test
    fun `(f) a planted key at an Open contact does not block that contact's own v4 rotation`() {
        val planted = E2EEncryption.generateKeyPair()
        val bound = nostr(9)
        val next = nostr(10).publicKey.hex()
        val boot = Boots()
        boot.e2ePeer[PEER] = planted.publicKey
        boot.nostr[PEER] = bound.publicKey.hex()
        val signed = ZBootMessage.signedDataFor(BOOT_CONV, next, RELAY, 1L)
        val raw =
            ZBootMessage(BOOT_CONV, next, RELAY, bound.signHashSchnorr(sha256(signed)).hex(), epoch = 1L, version = 4)
                .serialize()

        boot.route(PEER, raw, sealed = false)

        val followed = boot.binds.any { it.second == next }
        assertTrue("the Open contact's own rotation was refused because of a planted key: ${boot.binds}", followed)
    }

    // ---- (g), (h): an accept writes the asked record ----------------------------------------------------------------------

    @Test
    fun `(g) the accept of a contact's new key writes the asked record for that key`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(SyntheticUnifiedAddress.mainnet(seed = 89307))
            val vm = chat.viewModel()
            val accepted = E2EEncryption.generateKeyPair()
            chat.raiseBanner(vm, accepted)
            chat.accept(vm, accepted.publicKey)

            assertEquals("fixture sanity: the accepted key is stored", accepted.publicKey, chat.storedKey())
            val asked = chat.impl.getKexAskedKey(chat.peer)
            assertEquals("the accept wrote no asked record for the key the user accepted", accepted.publicKey, asked)
        }

    @Test
    fun `(h) a request the user accepted names its key in the asked record`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(seed = 89308)
            val key = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(peer, firstContact = true)
            val seam = HandshakeSeam(peer)
            val vm = handshakeViewModel(prefs, seam)
            handleKexMemo(vm, kexMemoFrom(peer, key), "synthetic-fc2b-h")
            awaitIdle(vm)
            assertEquals("fixture sanity: the request waits", key.publicKey, prefs.awaitingAccept[peer])

            vm.acceptInboundKexRequest(peer)
            awaitIdle(vm)

            assertEquals("the accepted request's key has no asked record", key.publicKey, prefs.askedKey[peer])
        }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private companion object {
        val PEER = SyntheticUnifiedAddress.mainnet(seed = 89300)
        const val BOOT_CONV = "FC2BBOOT"
        const val RELAY = "wss://relay.zsend.xyz"

        fun nostr(seed: Int): NOSTRIdentity = NOSTRIdentity.fromSeed(ByteArray(64) { (it + seed).toByte() })

        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        fun v3Boot(
            signer: E2EKeyPair,
            relayKey: String,
        ): String {
            val signed = ZBootMessage.signedDataFor(BOOT_CONV, relayKey, RELAY, 1L)
            return ZBootMessage(BOOT_CONV, relayKey, RELAY, E2EEncryption.sign(signer.privateKey, signed), epoch = 1L, version = 3)
                .serialize()
        }
    }

    /**
     * routeIncomingBoot over a lenient store: the contact keys in [e2ePeer], no key pair of ours, no asked record, the bound
     * relay keys in [nostr]; [binds] records every relay key it binds.
     */
    private class Boots {
        val e2ePeer = ConcurrentHashMap<String, String>()
        val nostr = ConcurrentHashMap<String, String>()
        val convIds = ConcurrentHashMap<String, String>()
        val modes: MutableList<Pair<String, Any?>> = Collections.synchronizedList(mutableListOf())
        val binds: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

        private val prefs: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "getE2EPeerPublicKey" -> e2ePeer[a[0] as String]
                    "getPeerNostrPubkey" -> nostr[a[0] as String]
                    "findPeerByNostrPubkey" -> nostr.entries.firstOrNull { it.value.equals(a[0] as String, ignoreCase = true) }?.key
                    "setPeerNostrPubkey" -> {
                        binds += (a[0] as String) to (a[1] as String?)
                        (a[1] as String?)?.let { nostr[a[0] as String] = it }
                        null
                    }
                    "resolvePeerAddress" -> a[0]
                    "getPeerByConversationId" -> convIds[a[0] as String]
                    "getAllConversationPeerAddresses" -> convIds.values.toList()
                    "setConversationMode" -> {
                        modes += (a[0] as String) to a.getOrNull(1)
                        null
                    }
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a.getOrNull(0)
                    "toString" -> "FC2BBootPrefs"
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
                }
            }

        /**
         * routeIncomingBoot([peer], [raw]) as the relay reader's attributed call ([sealed] true) or attributeBoot's ([sealed]
         * false). @return false when [sealed] is true and the sealedByBoundKey form does not exist (the caller asserts
         * it), true otherwise. What it adopted is in [binds].
         */
        fun route(
            peer: String,
            raw: String,
            sealed: Boolean,
        ): Boolean {
            if (sealed && sealedForm() == null) return false
            runCatching { routeOn(vm, peer, raw, sealed) }
            return true
        }

        /** attributeBoot([raw]): the peer it adopted the ZBOOT for, or null. */
        fun attribute(raw: String): String? {
            val method = ChatViewModel::class.java.getDeclaredMethod("attributeBoot", String::class.java)
            method.isAccessible = true
            return runCatching {
                try {
                    method.invoke(vm, raw) as String?
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }.getOrNull()
        }

        companion object {
            fun sealedForm(): java.lang.reflect.Method? =
                ChatViewModel::class.java.declaredMethods.firstOrNull {
                    it.name == "routeIncomingBoot" && it.parameterCount == 3 && it.parameterTypes[2] == java.lang.Boolean.TYPE
                }

            /**
             * routeIncomingBoot on [vm]: the sealedByBoundKey form with [sealed] when it exists, else (before the fix) the
             * two-argument form, which every caller reached. @return its answer.
             */
            fun routeOn(
                vm: ChatViewModel,
                peer: String,
                raw: String,
                sealed: Boolean,
            ): Boolean {
                val three = sealedForm()
                val method =
                    three ?: ChatViewModel::class.java.getDeclaredMethod("routeIncomingBoot", String::class.java, String::class.java)
                method.isAccessible = true
                return try {
                    (if (three != null) method.invoke(vm, peer, raw, sealed) else method.invoke(vm, peer, raw)) as Boolean
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
    }
}
