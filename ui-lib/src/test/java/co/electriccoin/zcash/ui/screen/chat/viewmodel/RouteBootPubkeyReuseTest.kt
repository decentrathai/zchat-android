package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.MessageDigest

/**
 * RED regression pin for finding [11] — the MISSING reverse-uniqueness (pubkey-reuse) guard in
 * [ChatViewModel.routeIncomingBoot].
 *
 * routeIncomingBoot adopts a peer's announced NOSTR pubkey (setPeerNostrPubkey) after a valid v4
 * Schnorr signature, but — unlike the sibling guard in acceptMessageRequest (the
 * CONFLICT_PUBKEY_REUSED branch) — it never checks the announced key is not ALREADY bound to a
 * DIFFERENT peer. Because v4 only proves the sender holds the OLD key (their currently-known NOSTR
 * pubkey) and NEVER proves possession of the ANNOUNCED new key, an accepted OPEN contact (Alice, whose
 * current key K_att we hold) can announce a rotation to VICTIM Bob's pubkey K_bob — the ZBOOT verifies
 * against K_att and, absent the guard, binds Alice's peer entry to K_bob. Now modePrefs holds
 * pubkey:Alice=K_bob AND pubkey:Bob=K_bob, and findPeerByNostrPubkey(K_bob) (inbound DM dispatch) can
 * resolve Bob's genuine DMs to Alice's thread.
 *
 * Fixture idiom mirrors RetestGap01AcceptRequestPubkeyReuseTest / RetestGap11AcceptSelfSpoofTest: a
 * dynamic Proxy fake for the ~200-method ZchatPreferences seam that answers ONLY the routeIncomingBoot
 * calls up to the adopt and RECORDS every setPeerNostrPubkey write, plus a bare ChatViewModel allocated
 * WITHOUT its (init-heavy) constructor via sun.misc.Unsafe.allocateInstance. The ZBOOT is a GENUINELY
 * Schnorr-signed (BIP-340) v4 message built with the real NOSTRIdentity + JVM secp256k1 JNI, so the
 * production signature verification actually PASSES and the test exercises the real adopt path.
 *
 * RED-on-HEAD: with no guard, routeIncomingBoot falls through to setPeerNostrPubkey(ALICE, K_bob) and
 * records the cross-peer rebind — failing assertion (1). GREEN-after-fix: the guard sees
 * findPeerByNostrPubkey(K_bob) == BOB != ALICE and returns false BEFORE any adopt. All keys are
 * derived from synthetic seeds.
 */
class RouteBootPubkeyReuseTest {

    private companion object {
        const val ALICE = "usynthetic-alice-open-contact-address"
        const val BOB = "usynthetic-bob-victim-address"
        const val CONV_ID = "conv1234" // 8-char, matches ZMSGConstants.CONV_ID_LENGTH
        const val RELAY = "wss://relay.zsend.xyz"
        const val EPOCH = 1L
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Records setPeerNostrPubkey writes and answers the exact ZchatPreferences calls routeIncomingBoot
     * makes on the v4 adopt path. Seeded so K_att is bound to ALICE and K_bob is bound to BOB — so the
     * reverse-uniqueness guard (once present) resolves findPeerByNostrPubkey(K_bob) -> BOB != ALICE.
     */
    private class RecordingPrefs(kAtt: String, kBob: String) {
        // Insertion order matters ONLY for readability; findPeerByNostrPubkey scans by value below.
        val bindings: MutableMap<String, String?> = linkedMapOf(ALICE to kAtt, BOB to kBob)
        val pubkeyWrites: MutableList<Pair<String, String?>> = mutableListOf()

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    // No E2E key for the OPEN peer -> v4 Schnorr path (not the downgrade-guarded branch).
                    "getE2EPeerPublicKey" -> null
                    "getPeerNostrPubkey" -> bindings[args!![0] as String]
                    "findPeerByNostrPubkey" -> {
                        val key = args!![0] as String
                        bindings.entries.firstOrNull { it.value?.equals(key, ignoreCase = true) == true }?.key
                    }
                    "hasProcessedBootSig" -> false
                    "markBootSigProcessed" -> null
                    "getPeerBootEpoch" -> 0L
                    "setPeerNostrPubkey" -> {
                        val addr = args!![0] as String
                        val pk = args[1] as String?
                        pubkeyWrites.add(addr to pk)
                        bindings[addr] = pk
                        null
                    }
                    "setPeerNostrRelay" -> null
                    "setPeerBootEpoch" -> null
                    "setE2EKeyChanged" -> null
                    // Reached only on the (buggy) HEAD fall-through, past the recorded rebind.
                    "getConversationModeOrNull" -> null
                    "isConversationModeExplicit" -> false
                    "setConversationMode" -> null
                    "toString" -> "RecordingZchatPreferences"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException(
                        "unexpected ZchatPreferences call on routeIncomingBoot path: ${method.name}",
                    )
                }
            } as ZchatPreferences
    }

    private fun allocateChatViewModel(): ChatViewModel {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
    }

    private fun injectPrefs(vm: ChatViewModel, prefs: ZchatPreferences) {
        val field = ChatViewModel::class.java.declaredFields.first { it.type == ZchatPreferences::class.java }
        field.isAccessible = true
        field.set(vm, prefs)
    }

    @Test
    fun `v4 ZBOOT announcing another peer's NOSTR key is REFUSED - no cross-peer rebind`() {
        // Attacker (Alice) identity: we hold her current key K_att; she signs the rotation with it.
        val attacker = NOSTRIdentity.fromSeed(ByteArray(64) { (it + 1).toByte() })
        val kAtt = attacker.publicKey.toHex()
        // Victim Bob's key, already bound (in our store) to BOB. Alice announces THIS key.
        val kBob = NOSTRIdentity.fromSeed(ByteArray(64) { (it + 100).toByte() }).publicKey.toHex()

        // Genuine BIP-340 v4 ZBOOT: signedData binds the ANNOUNCED (victim) key + epoch; signed by K_att.
        val signedData = ZBootMessage.signedDataFor(CONV_ID, kBob, RELAY, EPOCH)
        val sigHex = attacker.signHashSchnorr(sha256(signedData)).toHex()
        val raw = ZBootMessage(
            convId = CONV_ID,
            senderNostrPubkeyHex = kBob,
            relayUrl = RELAY,
            signature = sigHex,
            epoch = EPOCH,
            version = 4,
        ).serialize()

        val rec = RecordingPrefs(kAtt, kBob)
        val vm = allocateChatViewModel()
        injectPrefs(vm, rec.proxy)

        val method = ChatViewModel::class.java
            .getDeclaredMethod("routeIncomingBoot", String::class.java, String::class.java)
            .apply { isAccessible = true }

        val result = runCatching { method.invoke(vm, ALICE, raw) as Boolean }

        // (1) LOAD-BEARING ANCHOR: Alice's peer entry must NOT be rebound to Bob's key. On HEAD (no
        // guard) routeIncomingBoot records setPeerNostrPubkey(ALICE, K_bob) -> this fails RED.
        assertFalse(
            "must NOT rebind ALICE to the victim's announced NOSTR key (pubkey reuse)",
            rec.pubkeyWrites.any { it.first == ALICE && it.second == kBob },
        )
        org.junit.Assert.assertEquals(
            "ALICE's binding must remain her original key, unchanged",
            kAtt,
            rec.bindings[ALICE],
        )
        // (2) The refused boot returns false (guard rejects). On HEAD the fall-through returns true or
        // throws in emitKeyRotationNote on the uninitialised VM — either way not a clean false.
        org.junit.Assert.assertEquals(
            "refused pubkey-reuse ZBOOT must return false",
            false,
            result.getOrNull(),
        )
    }
}
