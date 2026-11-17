package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.time.Instant

/**
 * ZC-897 (HIGH, Fold lane 13 Oct, seen twice) — a group invite cannot be read when the group's creator holds an
 * unanswered key exchange from the invitee.
 *
 * The invitee sent the creator a first key exchange; the creator never tapped Accept ("Not now"), so the creator holds
 * the invitee's key (stored with the Accept hold) and its own key pair for them, while the invitee never received the
 * creator's key. GroupViewModel.buildCompactInviteMemo sees a session (both keys on the creator's side) and wraps the
 * group key under it (`k2`). The invitee cannot derive that session ("no KEX session matched"), saves the group with no
 * key and the creator shows the member as invited for good.
 *
 * Wanted (revised after the T7bG review of 19 Oct, finding 1): the plain-key form is never built for such a contact (a
 * forged key exchange can bind a stranger's relay key to its address); the invite is held until Accept instead
 * (InviteWaitsForTheContactsAcceptTest). A contact with a finished key exchange gets the session-wrapped form, which its
 * phone reads (control).
 *
 * Both phones are driven for real: the creator's GroupViewModel builds the invite, the invitee's ChatViewModel reads it
 * as the history scan hands it over. Nothing is proposed or paid on the invitee's phone. All addresses, keys, ids and
 * times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteToAContactWhoseKeyExchangeWaitsCanBeReadTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private val groupKey = AnnounceDriver.newGroupKey()
    private val epoch = 0

    /** The creator's phone and the invitee's phone, with the invitee's first key exchange at the creator. */
    private class Phones(
        val creator: AnnouncePhone,
        val invitee: AnnouncePhone,
    )

    private fun phones(creatorAccepted: Boolean): Phones {
        val creator = AnnouncePhone(self = CREATOR)
        val invitee = AnnouncePhone()
        val inviteeKeys = E2EEncryption.generateKeyPair()
        val creatorKeys = E2EEncryption.generateKeyPair()
        // The invitee sent its key exchange: it holds its own key pair for the creator.
        invitee.ourKeys[CREATOR] = inviteeKeys
        // The creator read it: the invitee's key is stored, and the creator has a key pair for the invitee.
        creator.peerKeys[invitee.self] = inviteeKeys.publicKey
        creator.ourKeys[invitee.self] = creatorKeys
        if (creatorAccepted) {
            // Accept answered it: the invitee has the creator's key.
            invitee.peerKeys[CREATOR] = creatorKeys.publicKey
        } else {
            // "Not now": the Accept hold stands, the invitee never got the creator's key.
            creator.awaitingAccept[invitee.self] = inviteeKeys.publicKey
        }
        return Phones(creator, invitee)
    }

    /** The creator's own invite builder (GroupViewModel.buildCompactInviteMemo), as the create and Resend paths call it. */
    private suspend fun inviteMemo(p: Phones): String {
        val gvm = AnnounceDriver.groupVm(p.creator, GroupPaidOnceFixture())
        return try {
            GroupViewModel::class.java
                .getDeclaredMethod(
                    "buildCompactInviteMemo",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
                .invoke(gvm, GROUP, "Synthetic ZC-897 group", CREATOR, epoch, groupKey, p.invitee.self) as String
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private suspend fun inviteeReads(
        p: Phones,
        memo: String,
    ): HandshakeSeam {
        val seam = HandshakeSeam(CREATOR)
        val vm = AnnounceDriver.chatVm(p.invitee, seam)
        AnnounceDriver.receive(vm, memo, Instant.ofEpochMilli(AnnounceDriver.T + 897_000), AnnounceDriver.txId(897))
        HandshakeHarness.awaitIdle(vm)
        return seam
    }

    /**
     * Changed after the T7bG review (19 Oct, finding 1): the plain-key form for a held contact leaked the group key to a
     * relay key a forged key exchange can bind. Such an invite is now held until Accept (InviteWaitsForTheContactsAcceptTest);
     * the builder itself never writes the group key in plain text for a contact that has a key exchange with us.
     */
    @Test
    fun `the invite builder never writes the plain group key for a contact whose key exchange waits for Accept`() =
        runBlocking {
            val p = phones(creatorAccepted = false)
            val payload = JSONObject(ZMSGGroupProtocol.parsePayload(inviteMemo(p))!!)
            assertFalse("the plain group key is written for a contact whose key exchange waits", payload.has("group_key"))
            assertTrue("the invite is not session-wrapped", payload.has("k2"))
        }

    @Test
    fun `control - an invite to a contact with a finished key exchange stays session-wrapped and is read with its key`() =
        runBlocking {
            val p = phones(creatorAccepted = true)
            val memo = inviteMemo(p)
            val payload = JSONObject(ZMSGGroupProtocol.parsePayload(memo)!!)
            assertTrue("a finished key exchange no longer gets the session-wrapped invite", payload.has("k2"))
            assertTrue("a session-wrapped invite carries the key in plaintext too", !payload.has("group_key"))

            inviteeReads(p, memo)
            assertEquals("the invitee could not read a session-wrapped invite", groupKey, p.invitee.keys["$GROUP|$epoch"])
        }

    private companion object {
        const val GROUP = "zgrp_8970a1b2c3d4e5f6a7b8c9d0"
        const val CREATOR = "usyntheticzc897creator00001"
    }
}
