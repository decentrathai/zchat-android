package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-506 Part A, compat-2124 — what a v2.12.4 creator's phone does with a "I joined" reply (GROUP_ACCEPT) that carries
 * the new `key_epoch` value: exactly what it does with the same reply without it.
 *
 * [Frozen2124Accept] holds VERBATIM copies from v2.12.4 (a1fb4136f), frozen here so this test keeps modelling a 2.12.4
 * phone whatever HEAD does next (the pattern of GroupAnnounceIgnoredBy2124Test):
 * - ZMSGGroupProtocol.kt:93-98, parsePayload (with isGroupMessage and the prefix);
 * - ZMSGGroupProtocol.kt:171-175, groupAcceptSignedData, and :181-192, createGroupAcceptMessage;
 * - ChatViewModel.kt:8466-8545, processGroupAccept from its first line to the roster it is about to save, reduced to a
 *   pure function: the two stores it reads (the roster, the peers' E2E public keys) are arguments, the log lines are
 *   dropped, the member status is this file's own enum, and what it decided is returned instead of written.
 * No token of the logic differs from v2.12.4.
 *
 * The 2.12.4 handler reads three named keys (`accepter` with getString, `accepter_pub` and `sig` with optString) and
 * nothing else, and its signature check covers `GA|group|address|pubkey`, which the new value is not part of. So a reply
 * built by HEAD with a key epoch (also one whose key and signature are nothing but '/', which HEAD writes unescaped)
 * must give the frozen code the same accepter, key, signature, matched member, signature verdict, adoption decision and
 * roster as the reply 2.12.4 itself would have built from the same parts.
 *
 * All addresses, keys and group ids are synthetic.
 */
class GroupAcceptKeyEpochIgnoredBy2124Test {
    private val invitedAs = "u1" + "i".repeat(176)
    private val declared = "u1" + "d".repeat(176)

    private fun roster(address: String = invitedAs) =
        listOf(
            Frozen2124Accept.Member("u1" + "c".repeat(176), Frozen2124Accept.Status.ACTIVE),
            Frozen2124Accept.Member(address, Frozen2124Accept.Status.INVITED),
        )

    private fun on2124(
        memo: String,
        roster: List<Frozen2124Accept.Member>,
        peerKeys: Map<String, String>,
    ): Frozen2124Accept.Outcome? = Frozen2124Accept.processGroupAccept(GID, Frozen2124Accept.parsePayload(memo)!!, roster, peerKeys::get)

    @Test
    fun `a signed accept with a key epoch is verified and adopted by the frozen handler exactly as without it`() {
        val member = E2EEncryption.generateKeyPair()
        val sig = E2EEncryption.sign(member.privateKey, ZMSGGroupProtocol.groupAcceptSignedData(GID, declared, member.publicKey))
        val keys = mapOf(invitedAs to member.publicKey)
        val legacy = on2124(Frozen2124Accept.createGroupAcceptMessage(GID, declared, member.publicKey, sig), roster(), keys)
        assertNotNull(legacy)
        assertTrue("fixture: the frozen handler verifies its own accept", legacy!!.signatureValid)
        assertTrue("fixture: the frozen handler adopts the declared address", legacy.shouldAdopt)

        for (epoch in EPOCHS) {
            val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, declared, member.publicKey, sig, keyEpoch = epoch)
            assertTrue("fixture: key epoch $epoch is on the wire", JSONObject(Frozen2124Accept.parsePayload(memo)!!).has("key_epoch"))
            assertEquals("key epoch $epoch", legacy, on2124(memo, roster(), keys))
        }
    }

    @Test
    fun `a signed accept at the invited address is activated without adoption, with and without a key epoch`() {
        val member = E2EEncryption.generateKeyPair()
        val sig = E2EEncryption.sign(member.privateKey, ZMSGGroupProtocol.groupAcceptSignedData(GID, invitedAs, member.publicKey))
        val keys = mapOf(invitedAs to member.publicKey)
        val legacy = on2124(Frozen2124Accept.createGroupAcceptMessage(GID, invitedAs, member.publicKey, sig), roster(), keys)!!
        assertTrue(legacy.signatureValid)
        assertFalse(legacy.shouldAdopt)
        assertEquals(Frozen2124Accept.Status.ACTIVE, legacy.roster.single { it.address == invitedAs }.status)

        for (epoch in EPOCHS) {
            val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, invitedAs, member.publicKey, sig, keyEpoch = epoch)
            assertEquals("key epoch $epoch", legacy, on2124(memo, roster(), keys))
        }
    }

    @Test
    fun `an accept whose key and signature are nothing but slashes reads the same on the frozen handler`() {
        val key = "/".repeat(124)
        val sig = "/".repeat(96)
        val keys = mapOf(invitedAs to key)
        val legacy = on2124(Frozen2124Accept.createGroupAcceptMessage(GID, declared, key, sig), roster(), keys)!!
        assertEquals("fixture: matched by the key, the address differs", invitedAs, legacy.matched)
        assertFalse("fixture: slashes are no signature", legacy.signatureValid)

        for (epoch in EPOCHS) {
            val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, declared, key, sig, keyEpoch = epoch)
            assertFalse("HEAD escaped a slash", memo.contains("\\/"))
            val outcome = on2124(memo, roster(), keys)
            assertEquals("key epoch $epoch", legacy, outcome)
            assertEquals(key, outcome!!.accepterPub)
            assertEquals(sig, outcome.signature)
        }
    }

    @Test
    fun `an unsigned accept with a key epoch, as a stranger's invite is answered, activates the invited row as before`() {
        val legacy = on2124(Frozen2124Accept.createGroupAcceptMessage(GID, invitedAs, ""), roster(), emptyMap())!!
        assertEquals(invitedAs, legacy.matched)
        assertEquals("", legacy.signature)

        for (epoch in EPOCHS) {
            val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, invitedAs, "", keyEpoch = epoch)
            assertFalse(memo.contains("\"sig\""))
            assertEquals("key epoch $epoch", legacy, on2124(memo, roster(), emptyMap()))
        }
    }

    @Test
    fun `an accept with a key epoch that names nobody on the roster changes nothing, as before`() {
        val stranger = "u1" + "s".repeat(176)
        val memo = ZMSGGroupProtocol.createGroupAcceptMessage(GID, stranger, "cHVi", "c2ln", keyEpoch = 3)
        val outcome = on2124(memo, roster(), emptyMap())!!
        assertEquals(null, outcome.matched)
        assertEquals(roster(), outcome.roster)
    }

    private companion object {
        const val GID = "zgrp_0a1b2c3d4e5f60718293a4b5"
        val EPOCHS = listOf(0, 1, 7, 99_999, Int.MAX_VALUE)
    }
}

/** VERBATIM v2.12.4 (a1fb4136f) code; see [GroupAcceptKeyEpochIgnoredBy2124Test] for the lines and the reduction. */
@Suppress("MaxLineLength", "TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
internal object Frozen2124Accept {
    // ZMSGConstants.Prefixes.GROUP at a1fb4136f
    private const val GROUP_PREFIX = "ZMSG:3.0:GROUP:"

    enum class Status { INVITED, ACTIVE, LEFT }

    data class Member(
        val address: String,
        val status: Status,
    )

    /** What the frozen handler read and decided, and the roster it was about to save (unchanged when it returned early). */
    data class Outcome(
        val accepter: String,
        val accepterPub: String,
        val signature: String,
        val matched: String?,
        val signatureValid: Boolean,
        val shouldAdopt: Boolean,
        val roster: List<Member>,
    )

    fun isGroupMessage(memo: String): Boolean = memo.startsWith(GROUP_PREFIX)

    // ZMSGGroupProtocol.kt:93-98

    /**
     * Parse payload JSON from a group message
     */
    fun parsePayload(memo: String): String? {
        if (!isGroupMessage(memo)) return null
        val parts = memo.removePrefix(GROUP_PREFIX).split(":", limit = 3)
        if (parts.size < 3) return null
        return parts[2]
    }

    // ZMSGGroupProtocol.kt:171-175
    fun groupAcceptSignedData(
        groupId: String,
        accepterAddress: String,
        accepterPublicKey: String
    ): String = "GA|$groupId|$accepterAddress|$accepterPublicKey"

    // ZMSGGroupProtocol.kt:181-192
    fun createGroupAcceptMessage(
        groupId: String,
        accepterAddress: String,
        accepterPublicKey: String,
        signature: String = ""
    ): String {
        val payload =
            JSONObject().apply {
                put("accepter", accepterAddress)
                put("accepter_pub", accepterPublicKey)
                if (signature.isNotEmpty()) put("sig", signature)
            }
        return "${GROUP_PREFIX}GA:$groupId:$payload"
    }

    // ChatViewModel.kt:8466-8545 (processGroupAccept), reduced as the class comment says.
    fun processGroupAccept(
        groupId: String,
        payload: String,
        members: List<Member>,
        getE2EPeerPublicKey: (String) -> String?,
    ): Outcome? {
        try {
            val json = org.json.JSONObject(payload)
            val accepterAddress = json.getString("accepter")
            // The joiner's E2E public key in OUR shared session — a STABLE cryptographic identity that
            // does NOT drift across the joiner's UA representations the way the address string does.
            val accepterPub = json.optString("accepter_pub", "")
            val signature = json.optString("sig", "")

            val matched =
                members.firstOrNull { member ->
                    member.address == accepterAddress ||
                        (
                            accepterPub.isNotEmpty() &&
                                getE2EPeerPublicKey(member.address) == accepterPub
                        )
                }
            if (matched == null) {
                return Outcome(accepterAddress, accepterPub, signature, null, signatureValid = false, shouldAdopt = false, roster = members)
            }

            if (matched.status == Status.LEFT) {
                return Outcome(accepterAddress, accepterPub, signature, matched.address, false, shouldAdopt = false, roster = members)
            }

            val accepterAddrValid =
                accepterAddress.startsWith("u1") &&
                    accepterAddress.length > 100 &&
                    !accepterAddress.contains('|')
            val storedPub = getE2EPeerPublicKey(matched.address)
            val signatureValid =
                signature.isNotEmpty() &&
                    storedPub != null &&
                    runCatching {
                        E2EEncryption.verify(
                            storedPub,
                            groupAcceptSignedData(groupId, accepterAddress, accepterPub),
                            signature
                        )
                    }.getOrDefault(false)
            val shouldAdopt = accepterAddrValid && signatureValid && matched.address != accepterAddress

            val updated =
                members.map { member ->
                    if (member.address == matched.address) {
                        member.copy(
                            address = if (shouldAdopt) accepterAddress else member.address,
                            status = Status.ACTIVE
                        )
                    } else {
                        member
                    }
                }
            return Outcome(accepterAddress, accepterPub, signature, matched.address, signatureValid, shouldAdopt, updated)
        } catch (e: Exception) {
            return null
        }
    }
}
