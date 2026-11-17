package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminNotice
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SpanishSendPaymentDialogTest
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CONFIRMED
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CREATOR_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * KI-25 pair 3, ZC-598 (KI-25-SPEC 3.6b R17, D4; section 4.3 class 15; the reproducer of 2.7) — a key that claims the
 * admin's address of a group joined with the creator's plaintext relay invite (`plainKeyPhone()`) never makes its
 * sender the admin by itself.
 *
 * - (a) a key under the creator address as an unsolicited answer leaves it (contact key, no key pair of ours, no hold;
 *   `adoptKeyOffer`'s KEXACK branch stores the key and turns E2E on with no tap): an on-chain removal naming the creator
 *   address and signed with that key changes nothing, nothing is written to the record, the state is NOT_APPLICABLE
 *   and there is no line; a sealed removal under the creator's seal still applies.
 * - (b) a finished chat at the creator address: the signed removal changes nothing; UNKNOWN, the check line, the
 *   candidate is that chat (source ADDRESS) and the card's body is the address body; after the verified mark and
 *   Confirm the same removal on the next pass applies; in UNKNOWN the key request goes over the relays, with no price
 *   question and no proposal.
 * - (c) the join itself: a plaintext relay invite arrives while a key is already stored under the inviter's address,
 *   once with no key pair of ours and once as a finished chat: the record has no creator key both times, and a removal
 *   signed with that key changes nothing.
 * - (d) ZC-729's telling half: an Open group whose admin's finished chat sits under another address text `Y2` (no relay
 *   key there): NOT_APPLICABLE and no line; a relay removal the creator's relay identity sealed and the admin's key for
 *   `Y2` signed changes nothing, is kept with `Y2` as its signer chat, and the group then reads UNKNOWN with the check
 *   line, no candidate and the Open group's own last line. The sealed route still applies; the same removal sealed by
 *   another contact is not kept; aged entries leave and the group reads NOT_APPLICABLE again; an entry kept as accepted
 *   does not make the group ask.
 *
 * At the base (a), (b) and (d) fail (the removal applies and the key is frozen; nothing is kept and nothing is said);
 * (c) passes for the record and fails for the removal.
 * Nothing is paid by any case. All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimedKeyIsNeverTheGroupAdminByItselfTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AdminCheck.forgetConfirmedPasses()
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
        AdminCheck.forgetConfirmedPasses()
    }

    private val kickTime: Instant = Instant.ofEpochMilli(T + 60_000)

    private fun onChain(
        vm: ChatViewModel,
        memo: String,
        n: Int = 6,
    ) = AnnounceDriver.receive(vm, memo, kickTime, AnnounceDriver.txId(n), minedHeight = HEIGHT + n)

    private fun signedKick(
        p: AdminCheckPhone,
        signer: E2EKeyPair,
        newKey: String? = null,
    ): String = AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = newKey, signer = signer, wrapFor = X_OLD)

    @Test
    fun `a - a key an unsolicited answer leaves at the creator address makes nobody the admin, and the seal still works`() =
        runBlocking {
            val p = AdminCheck.plainKeyPhone()
            val claimed = E2EEncryption.generateKeyPair()
            p.base.peerKeys[X_OLD] = claimed.publicKey
            val vm = AdminCheck.chatVm(p, HandshakeSeam(X_OLD))
            val record = p.base.infos.getValue(GID)

            repeat(3) { onChain(vm, signedKick(p, claimed)) }

            assertEquals("a removal signed with a claimed key was applied", MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(1, p.base.epochs[GID])
            assertTrue(p.base.info(GID).isActive)
            assertEquals("a claimed key was written into the record", record, p.base.infos.getValue(GID))
            assertEquals("NOT_APPLICABLE", AdminCheck.keyState(vm))
            assertNull("the group shows a line", GroupAdminCheck.notice(p.prefs, emptyList(), GID, newestReceivedUnreadable = false))

            AnnounceDriver.receive(vm, sealedKick(M, 2), kickTime.plusSeconds(60), sender = X_OLD, eventId = "synthetic-zc598-sealed-kick")
            assertEquals("the creator's sealed removal no longer applies", MemberStatus.LEFT, p.base.status(GID, M))
        }

    @Test
    fun `b - a finished chat at the creator address asks once, and its removal applies only after the confirmation`() =
        runBlocking {
            val p = AdminCheck.plainKeyPhone()
            val chat = p.base.link(X_OLD)
            val seam = HandshakeSeam(X_OLD)
            val vm = AdminCheck.chatVm(p, seam)
            val record = p.base.infos.getValue(GID)
            val newKey = AnnounceDriver.newGroupKey()
            val removal = signedKick(p, chat, newKey)

            onChain(vm, removal)
            assertEquals("a removal signed by the chat at the creator address was applied", MemberStatus.ACTIVE, p.base.status(GID, M))
            assertEquals(record, p.base.infos.getValue(GID))
            assertEquals("a finished chat at an Open group's creator address does not ask", "UNKNOWN", AdminCheck.keyState(vm))
            assertEquals(GroupAdminNotice.CHECK_LINE, GroupAdminCheck.notice(p.prefs, emptyList(), GID, newestReceivedUnreadable = false))
            val info = p.base.info(GID)
            val candidate = GroupAdminCheck.recordedCandidate(p.prefs, emptyList(), info, null)
            assertEquals(X_OLD to "ADDRESS", candidate?.let { it.first to it.second.name })

            // The key request in that state: over the relays, no price question, no proposal.
            p.base.states[GID] = p.base.state(GID).copy(rosterQueryAtMs = T)
            AdminCheck.relayRecorder(published)
            val f = GroupPaidOnceFixture().also { it.scheduler = scheduler }
            val screen = AdminCheck.groupVm(p, f)
            f.awaitNewJobs(screen) { screen.requestGroupKeySync(GID) }
            assertNull("the key request asked a price", screen.paidActionConfirmation.value)
            val relayKeys = published.map { it.second }
            assertEquals("the key request did not go once over the relays to the creator", listOf(CREATOR_NOSTR), relayKeys)
            assertEquals(0, f.seam.proposalRequests.get())

            p.verified += X_OLD
            assertEquals(CONFIRMED, AdminCheck.confirm(vm, X_OLD, chat.publicKey))
            HandshakeHarness.awaitIdle(vm)
            onChain(vm, removal, n = 7)
            assertEquals("the removal was not applied after the confirmation", MemberStatus.LEFT, p.base.status(GID, M))
            assertEquals(newKey, p.groupKey(2))
            assertEquals("a payment was proposed", 0, seam.proposalRequests.get())
        }

    // ------------------------------------------------------------------ (c)

    /** X's phone joins the Open group from the creator's plaintext relay invite while [before] is already at C. */
    private suspend fun joinWith(before: (AnnouncePhone) -> E2EKeyPair): Triple<AnnouncePhone, ChatViewModel, E2EKeyPair> {
        net.register()
        val phone = AnnouncePhone(OpenGroup.X)
        phone.nostrKeys[OpenGroup.C] = MA3.NPUB.getValue(OpenGroup.C)
        val key = before(phone)
        val chat = AnnounceDriver.chatVm(phone, HandshakeSeam(OpenGroup.X))
        AutoSpendVm.setField(chat, "contactBook", SavedContacts(setOf(OpenGroup.C)))
        MA3.relay(chat, OpenGroup.plainInvite(), sender = OpenGroup.C, timeMs = MA3.now() - 3_000_000L)
        check(phone.infos[OpenGroup.GID] != null) { "fixture: the plaintext relay invite did not create the group" }
        return Triple(phone, chat, key)
    }

    @Test
    fun `c - the join stores no creator key from a key already at the inviter's address, and that key's removal changes nothing`() =
        runBlocking {
            val withoutPair = { phone: AnnouncePhone ->
                E2EEncryption.generateKeyPair().also { phone.peerKeys[OpenGroup.C] = it.publicKey }
            }
            val finished = { phone: AnnouncePhone -> phone.link(OpenGroup.C) }
            for ((what, before) in listOf("a key with no key pair of ours" to withoutPair, "a finished chat" to finished)) {
                MA3.resetRuntime()
                val (phone, chat, key) = joinWith(before)
                assertFalse(
                    "the join with $what at the inviter's address stored a creator key",
                    JSONObject(phone.infos.getValue(OpenGroup.GID)).has("creator_pub"),
                )
                val signedData = ZMSGGroupProtocol.groupKickSignedData(OpenGroup.GID, OpenGroup.X, OpenGroup.C, 2, null)
                val kick =
                    ZMSGGroupProtocol.createGroupKickMessage(
                        OpenGroup.GID,
                        OpenGroup.X,
                        OpenGroup.C,
                        2,
                        null,
                        E2EEncryption.sign(key.privateKey, signedData),
                    )
                AnnounceDriver.receive(chat, kick, kickTime, AnnounceDriver.txId(9), minedHeight = HEIGHT + 9)
                assertTrue("a removal signed with $what at the inviter's address was applied", phone.info(OpenGroup.GID).isActive)
            }
        }

    // ------------------------------------------------------------------ (d)

    private fun openGroupAdminElsewhere(p: AdminCheckPhone): Boolean {
        val method =
            GroupAdminCheck::class.java.declaredMethods.firstOrNull { it.name == "openGroupAdminElsewhere" && it.parameterCount == 3 }
                ?: throw AssertionError(
                    "KI-25 pair 3 (ZC-598): no such operation: GroupAdminCheck.openGroupAdminElsewhere(prefs, savedContacts, groupId) " +
                        "(the card ends with group_admin_no_candidate_open)",
                )
        return method.invoke(GroupAdminCheck, p.prefs, emptyList<String>(), GID) as Boolean
    }

    @Test
    fun `d - ZC-729 - the admin's chat under another address text - the sealed signed removal is kept and the group says so`() =
        runBlocking {
            val p = AdminCheck.plainKeyPhone()
            val admin = p.base.link(Y2)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(X_OLD))
            assertEquals("NOT_APPLICABLE", AdminCheck.keyState(vm))
            assertNull(GroupAdminCheck.notice(p.prefs, emptyList(), GID, newestReceivedUnreadable = false))

            val removal = AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = null, signer = admin, wrapFor = Y2)
            AdminCheck.relayDeliver(vm, removal, X_OLD, KEPT_729, kickTime)

            assertEquals("the signed removal was applied", MemberStatus.ACTIVE, p.base.status(GID, M))
            val kept = AdminCheck.keptChanges(p)
            val keptIds = kept.map { it.getString("id") }
            assertEquals("R19's list does not hold the removal the creator's identity sealed", listOf(KEPT_729), keptIds)
            assertEquals("the kept removal's signer chat is not Y2", Y2, kept.single().getString("by"))
            assertEquals("UNKNOWN", AdminCheck.keyState(vm))
            assertEquals(GroupAdminNotice.CHECK_LINE, GroupAdminCheck.notice(p.prefs, emptyList(), GID, newestReceivedUnreadable = false))
            assertNull("a candidate was offered", GroupAdminCheck.recordedCandidate(p.prefs, emptyList(), p.base.info(GID), null))
            assertTrue("the card does not end with the Open group's line", openGroupAdminElsewhere(p))

            // Aged out: NOT_APPLICABLE again.
            val aged = kept.single().put("kept", System.currentTimeMillis() - 91L * 24 * 60 * 60 * 1_000)
            p.base.states[GID] = p.base.state(GID).copy(keptAdminChanges = emptyList())
            AdminCheck.putKept(p, listOf(aged))
            val pass = AdminCheck.beginPass(vm)
            AdminCheck.endPass(vm, pass, complete = true)
            assertEquals("an aged entry kept the group asking", "NOT_APPLICABLE", AdminCheck.keyState(vm))

            // The sealed route still applies.
            AnnounceDriver.receive(vm, sealedKick(M, 2), kickTime.plusSeconds(120), sender = X_OLD, eventId = "synthetic-zc729-sealed")
            assertEquals("the creator's sealed removal no longer applies", MemberStatus.LEFT, p.base.status(GID, M))
        }

    @Test
    fun `d - the same removal sealed by another contact is not kept, and an entry kept as accepted does not make the group ask`() =
        runBlocking {
            val p = AdminCheck.plainKeyPhone()
            val admin = p.base.link(Y2)
            p.base.link(OTHER)
            p.base.nostrKeys[OTHER] = "6d".repeat(32)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(X_OLD))
            val removal = AdminCheck.kickNaming(p, naming = X_OLD, kicked = M, newEpoch = 2, newKey = null, signer = admin, wrapFor = Y2)

            AdminCheck.relayDeliver(vm, removal, OTHER, "synthetic-zc729-other-seal", kickTime)
            val keptIds = AdminCheck.keptChanges(p).map { it.getString("id") }
            assertEquals("a removal another contact sealed was kept", emptyList<String>(), keptIds)
            assertEquals("NOT_APPLICABLE", AdminCheck.keyState(vm))

            // A sealed key change under the creator's seal whose key-store commit is refused: kept as accepted.
            p.keyCommitFails = true
            val newKey = AnnounceDriver.newGroupKey()
            AdminCheck.relayDeliver(vm, sealedKey(3, newKey), X_OLD, "synthetic-zc729-sealed-key", kickTime.plusSeconds(60))
            val accepted = AdminCheck.keptChanges(p).any { it.optBoolean("acc", false) }
            assertTrue("fixture: the sealed key change was not kept as accepted", accepted)
            assertEquals("an entry kept as accepted made the group ask", "NOT_APPLICABLE", AdminCheck.keyState(vm))
            assertNull(GroupAdminCheck.notice(p.prefs, emptyList(), GID, newestReceivedUnreadable = false))

            p.keyCommitFails = false
            val pass = AdminCheck.beginPass(vm)
            AdminCheck.endPass(vm, pass, complete = false)
            assertEquals("the accepted entry was not applied at the next pass's end", newKey, p.groupKey(3))
        }

    @Test
    fun `texts - the address body and the Open group's no-candidate line, English and Spanish`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val expected =
            mapOf(
                "group_admin_check_body_address" to
                    "There is a secure chat at the address stored as this group’s admin, but this phone has not confirmed that it " +
                    "belongs to the admin. Until you confirm it, the admin’s changes may be ignored here, and someone the admin " +
                    "removed may still read what you write in this group.",
                "group_admin_no_candidate_open" to
                    "ZChat can’t confirm this group’s admin on this phone. Until ZChat can, the admin’s changes may not be applied " +
                    "here. The admin has already changed the group key, so this phone may not be able to read new messages in this " +
                    "group, and the member list here may be out of date.",
            )
        for ((key, english) in expected) {
            assertEquals("English $key", english, en[key])
            val spanish = es[key] ?: throw AssertionError("no Spanish $key")
            assertNotEquals("$key is not translated", english, spanish)
            assertEquals(
                "$key: the Spanish placeholders differ",
                SpanishSendPaymentDialogTest.placeholders(english).sorted(),
                SpanishSendPaymentDialogTest.placeholders(spanish).sorted(),
            )
        }
        val open = en.getValue("group_admin_no_candidate_open")
        for (old in listOf("are applied once", "kept for", "next changes", "are not applied", "can’t read", "an update", "This version")) {
            assertFalse("group_admin_no_candidate_open still says \"$old\"", open.contains(old))
        }
        val card = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupSettingsView.kt")
        assertTrue("the card never shows the address body", card.contains("R.string.group_admin_check_body_address"))
        assertTrue("the card never shows the Open group's line", card.contains("R.string.group_admin_no_candidate_open"))
    }

    private companion object {
        /** A sealed GROUP_KICK of this group, as the Open-group wire fixes it: no signature, no key. */
        const val KEPT_729 = "synthetic-zc729-relay-kick"
        const val OTHER = "usyntheticzc729othercontact1"

        /** A sealed GROUP_KEY of this group: no signature, the key in plaintext as `gk`. */
        fun sealedKey(
            epoch: Int,
            gk: String,
        ): String =
            "ZMSG:3.0:GROUP:GY:$GID:" +
                JSONObject()
                    .put("signer", X_OLD)
                    .put("epoch", epoch)
                    .put("reason", "rotation")
                    .put("gk", gk)
                    .put("sig", "")
                    .toString()

        fun sealedKick(
            kicked: String,
            newEpoch: Int,
        ): String =
            "ZMSG:3.0:GROUP:GK:$GID:" +
                JSONObject()
                    .put("kicked", kicked)
                    .put("kicker", X_OLD)
                    .put("new_epoch", newEpoch)
                    .put("sig", "")
                    .toString()
    }
}
