package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * #187 [7] — control-message AUTHORIZATION survives admin address-representation drift.
 *
 * The on-device bug: an admin whose unified-address representation drifts (X at group-create time →
 * Y live, #205/#214) has their KEX'd E2E pubkey stored ONLY under rep Y, with NO key and NO alias
 * under the group's committed `creatorAddress` = X. `verifyGroupAdminControl` then AUTHENTICATES the
 * admin's signed GROUP_KICK/GROUP_KEY (their signature verifies against the key held under Y) but
 * AUTHORIZES only via `isGroupControlAdmin(creatorAddress=X, signerAddress=Y, resolvePeerAddress)`,
 * and on a receiver `resolvePeerAddress` is identity (the only alias writer runs admin-side) — so
 * X != Y and the legitimate admin's own kick/rotation is silently REJECTED.
 *
 * Option A: capture the creator's E2E pubkey into GroupInfo at JOIN (`creator_pub`) and authorize by
 * KEY-IDENTITY (`signerPub == info.creatorPublicKey`), falling back to the legacy string gate only
 * when no key was captured. `signerPub` was already ECDSA-verified against the signature two lines
 * above, so `signerPub == creatorPublicKey` is equivalent to re-verifying the signature against the
 * creator's stored key — and is immune to the address drift X→Y.
 *
 * Fixture idiom mirrors [RouteBootPubkeyReuseTest]: a dynamic Proxy fake for the ~200-method
 * ZchatPreferences seam answering ONLY the verifyGroupAdminControl reads, a bare ChatViewModel
 * allocated WITHOUT its init-heavy constructor via sun.misc.Unsafe, and the private method invoked by
 * reflection. Keys are REAL P-256 keypairs and the signatures are REAL SHA256withECDSA, so the
 * production verify() actually runs. The GroupInfo JSON is hand-crafted (rather than via
 * serializeGroupInfo) so this test compiles + reproduces RED against the CURRENT code, whose
 * serialize/deserialize don't yet know `creator_pub`.
 *
 * RED-on-HEAD: the drifted-admin case (creator_pub captured) asserts TRUE but HEAD's sole string gate
 * returns FALSE (X != Y). GREEN-after-fix: key-identity authorizes. The forgery/stranger cases stay
 * FALSE on both HEAD and fix — a valid signature over the attacker's OWN key must never authorize.
 */
class GroupControlAuthorizationTest {

    private companion object {
        const val GID = "zgrp_authztest"
        // Admin's committed creatorAddress at group-create time, and their drifted LIVE rep.
        const val X = "u1creator-address-original"
        const val Y = "u1creator-address-drifted"
        const val Z = "u1stranger-attacker-address"
        const val KICKED = "u1kicked-member-address"
        const val WRAPPED_KEY = "d3JhcHBlZC1uZXcta2V5LWJhc2U2NA=="
        const val NEW_EPOCH = 1
    }

    /** A GroupInfo JSON in exactly the schema deserializeGroupInfo reads; creator_pub optional. */
    private fun groupInfoJson(creator: String, creatorPub: String?): String =
        JSONObject().apply {
            put("group_id", GID)
            put("name", "Authz Test")
            put("creator", creator)
            put("created_at", 1_000L)
            put("admin_policy", "CREATOR_ONLY")
            put("current_epoch", 0)
            put("is_active", true)
            creatorPub?.let { put("creator_pub", it) }
        }.toString()

    /**
     * Answers exactly the ZchatPreferences reads verifyGroupAdminControl performs.
     * [peerKeys]: address → stored E2E pubkey (models "key under Y only, none under X").
     * resolvePeerAddress is IDENTITY — the receiver-side reality that severs authorization under drift
     * (the only alias writer runs admin-side).
     */
    private class AuthzPrefs(
        private val peerKeys: Map<String, String?>,
        private val groupInfo: String?,
    ) {
        val savedGroupInfo = mutableListOf<Pair<String, String>>()

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getE2EPeerPublicKey" -> peerKeys[args!![0] as String]
                    "getGroupInfo" -> groupInfo
                    "resolvePeerAddress" -> args!![0] as String // identity: no alias on a receiver
                    "saveGroupInfo" -> {
                        savedGroupInfo.add((args!![0] as String) to (args[1] as String))
                        null
                    }
                    "toString" -> "AuthzPrefs"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException(
                        "unexpected ZchatPreferences call on verifyGroupAdminControl path: ${method.name}",
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

    private fun invokeVerify(
        prefs: ZchatPreferences,
        signerAddress: String,
        signedData: String,
        signature: String,
    ): Boolean {
        val vm = allocateChatViewModel()
        injectPrefs(vm, prefs)
        val method = ChatViewModel::class.java
            .getDeclaredMethod(
                "verifyGroupAdminControl",
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )
            .apply { isAccessible = true }
        return method.invoke(vm, GID, signerAddress, signedData, signature) as Boolean
    }

    @Test
    fun `a rep-drifted admin with a captured creator_pub authorizes their own signed kick`() {
        // Admin's real keypair; their key is held ONLY under the drifted rep Y, none under X.
        val admin = E2EEncryption.generateKeyPair()
        val signedData = ZMSGGroupProtocol.groupKickSignedData(GID, KICKED, Y, NEW_EPOCH, WRAPPED_KEY)
        val sig = E2EEncryption.sign(admin.privateKey, signedData)
        // Sanity: the AUTHENTICATE half is genuinely satisfied (real signature over the real key).
        assertTrue(E2EEncryption.verify(admin.publicKey, signedData, sig))

        val prefs = AuthzPrefs(
            peerKeys = mapOf(Y to admin.publicKey), // stored under Y only — no key under X
            groupInfo = groupInfoJson(creator = X, creatorPub = admin.publicKey), // captured at JOIN
        )

        // MUTATION that reddens this (and HEAD already reds it): authorize via the sole
        // isGroupControlAdmin string gate — creatorAddress X != signer rep Y under identity
        // canonicalization → the legitimate admin's kick is rejected.
        assertTrue(
            "a rep-drifted admin whose captured creator_pub matches the signer key must authorize",
            invokeVerify(prefs.proxy, Y, signedData, sig),
        )
    }

    @Test
    fun `a stranger self-signing with their OWN key is rejected even when a creator_pub is captured`() {
        // Attacker Z signs a kick with their OWN authentic key: AUTHENTICATE passes (as Z), but Z's
        // key != the captured creator key → AUTHORIZE must fail. A valid signature over the attacker's
        // own key must never authorize a control message.
        val z = E2EEncryption.generateKeyPair()
        val adminPub = E2EEncryption.generateKeyPair().publicKey // the real creator key we captured
        val zSignedData = ZMSGGroupProtocol.groupKickSignedData(GID, KICKED, Z, NEW_EPOCH, WRAPPED_KEY)
        val zSig = E2EEncryption.sign(z.privateKey, zSignedData)
        assertTrue(E2EEncryption.verify(z.publicKey, zSignedData, zSig)) // authenticates as Z

        val prefs = AuthzPrefs(
            peerKeys = mapOf(Z to z.publicKey),
            groupInfo = groupInfoJson(creator = X, creatorPub = adminPub),
        )

        assertFalse(
            "a valid signature over the attacker's own key must not authorize (key-identity gate)",
            invokeVerify(prefs.proxy, Z, zSignedData, zSig),
        )
    }

    @Test
    fun `a stranger is rejected on the legacy no-creator_pub fallback`() {
        // Legacy group, no captured key and X holds no key (drifted-at-upgrade): the fallback is the
        // existing canonical string gate, which must still reject a non-creator signer.
        val z = E2EEncryption.generateKeyPair()
        val zSignedData = ZMSGGroupProtocol.groupKickSignedData(GID, KICKED, Z, NEW_EPOCH, WRAPPED_KEY)
        val zSig = E2EEncryption.sign(z.privateKey, zSignedData)

        val prefs = AuthzPrefs(
            peerKeys = mapOf(Z to z.publicKey), // no key under X → Tier-1 snapshot yields null
            groupInfo = groupInfoJson(creator = X, creatorPub = null),
        )

        assertFalse(
            "legacy string-gate fallback must still reject a stranger (Z != X)",
            invokeVerify(prefs.proxy, Z, zSignedData, zSig),
        )
    }

    // ---------- pure AUTHORIZE decision (ZMSGGroupProtocol.isGroupControlAuthorized), real keys ----------
    // The exact branch verifyGroupAdminControl runs, extracted for fast unit coverage without reflection.
    // `canonicalize = { it }` is the receiver's reality (no alias) that severs the address-string gate.

    @Test
    fun `pure gate - legacy null creator_pub rejects a drifted admin (the bug at helper level)`() {
        val admin = E2EEncryption.generateKeyPair()
        assertFalse(
            ZMSGGroupProtocol.isGroupControlAuthorized(
                creatorPublicKey = null,
                signerPub = admin.publicKey,
                creatorAddress = X,
                signerAddress = Y,
                canonicalize = { it },
            ),
        )
    }

    @Test
    fun `pure gate - a captured creator_pub authorizes the drifted admin by key identity`() {
        val admin = E2EEncryption.generateKeyPair()
        // MUTATION that reddens this: revert verifyGroupAdminControl to the sole isGroupControlAdmin
        // string gate (drop the creatorPublicKey branch) — X != Y under identity canon → reject.
        assertTrue(
            ZMSGGroupProtocol.isGroupControlAuthorized(
                creatorPublicKey = admin.publicKey,
                signerPub = admin.publicKey, // == getE2EPeerPublicKey(Y), the drifted live rep
                creatorAddress = X,
                signerAddress = Y,
                canonicalize = { it },
            ),
        )
    }

    @Test
    fun `pure gate - a captured creator_pub rejects a stranger's own-key signature`() {
        val admin = E2EEncryption.generateKeyPair()
        val z = E2EEncryption.generateKeyPair()
        assertFalse(
            ZMSGGroupProtocol.isGroupControlAuthorized(
                creatorPublicKey = admin.publicKey,
                signerPub = z.publicKey,
                creatorAddress = X,
                signerAddress = Z,
                canonicalize = { it },
            ),
        )
    }

    @Test
    fun `pure gate - legacy fallback still rejects a stranger`() {
        val z = E2EEncryption.generateKeyPair()
        assertFalse(
            ZMSGGroupProtocol.isGroupControlAuthorized(
                creatorPublicKey = null,
                signerPub = z.publicKey,
                creatorAddress = X,
                signerAddress = Z,
                canonicalize = { it },
            ),
        )
    }

    // ---------------------------- Tier-1 local snapshot (heal) + serialization -----------------------------

    @Test
    fun `a verified control writes nothing - the first look does`() {
        // KI-25 pair 3, ZC-598 (D1): a legacy group (creator_pub == null) whose creatorAddress X holds a key. A control
        // signed with that key no longer authorizes by itself and writes nothing; the one look in the first start's move
        // (GroupAdminCheck.firstLook) is what writes a finished chat's key at X into the record.
        val admin = E2EEncryption.generateKeyPair()
        val signedData = ZMSGGroupProtocol.groupKeySignedData(GID, X, NEW_EPOCH, WRAPPED_KEY, "rotate")
        val sig = E2EEncryption.sign(admin.privateKey, signedData)
        val record = groupInfoJson(creator = X, creatorPub = null)
        val prefs = AuthzPrefs(peerKeys = mapOf(X to admin.publicKey), groupInfo = record)

        assertFalse("a key at the creator address authorized a control by itself", invokeVerify(prefs.proxy, X, signedData, sig))
        assertTrue("a control wrote a creator key", prefs.savedGroupInfo.isEmpty())

        val look =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
                when (method.name) {
                    "getE2EPeerPublicKey" -> if (args!![0] == X) admin.publicKey else null
                    "getE2EPrivateKey" -> if (args!![0] == X) "synthetic-our-private-key-for-x" else null
                    "getInboundKexAwaitingAccept" -> null
                    "isGroupSelfCreated", "isSelfAddress" -> false
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> "FirstLookPrefs"
                }
            } as ZchatPreferences
        val firstLook =
            co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck::class.java.declaredMethods
                .firstOrNull { it.name == "firstLook" && it.parameterCount == 2 }
                ?: throw AssertionError("KI-25 pair 3 (ZC-598): no such operation: GroupAdminCheck.firstLook(prefs, info)")
        val info = ZMSGGroupProtocol.deserializeGroupInfo(record)!!
        val looked = firstLook.invoke(co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck, look, info) as GroupInfo
        assertEquals("the first look did not write the finished chat's key", admin.publicKey, looked.creatorPublicKey)
    }

    @Test
    fun `serialize round-trips creator_pub and legacy JSON without it stays null and wire-identical`() {
        val admin = E2EEncryption.generateKeyPair()
        val info = GroupInfo(
            groupId = GID,
            name = "g",
            creatorAddress = X,
            createdAt = Instant.ofEpochMilli(1_000L),
            creatorPublicKey = admin.publicKey,
        )
        assertEquals(
            "captured creator_pub must survive a serialize→deserialize round-trip",
            admin.publicKey,
            ZMSGGroupProtocol.deserializeGroupInfo(ZMSGGroupProtocol.serializeGroupInfo(info))?.creatorPublicKey,
        )
        // Backward compat: legacy JSON with no creator_pub loads as null AND re-serializes without the
        // key (byte-identical wire for un-healed groups → no migration).
        val legacyInfo = ZMSGGroupProtocol.deserializeGroupInfo(groupInfoJson(creator = X, creatorPub = null))
        assertNull("legacy JSON loads creatorPublicKey as null", legacyInfo?.creatorPublicKey)
        assertFalse(
            "a null creatorPublicKey must not emit creator_pub",
            ZMSGGroupProtocol.serializeGroupInfo(legacyInfo!!).contains("creator_pub"),
        )
    }
}
