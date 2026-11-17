package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver.Z
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-493 — the never-delete guard beside [ExpiredGroupMessageIsNotShownAsSentTest].
 *
 * An own group message whose chain copies all expired unmined is MARKED when the group screen draws it. It may have
 * reached members over the relays, a late mine can still bring it back, and its stored row is the only copy of the
 * text (the later group Resend reads it), so the fix must not skip, hide, retire, delete or rewrite any stored row and
 * must not add a stored key. Every case here holds before the fix and must hold after it: an implementation that
 * skips the expired transaction in the history scan, or retires its rows, turns them red.
 *
 * Real ChatViewModel and GroupViewModel over [AnnouncePhone]; the chain is an [OpenGroupChain]. All addresses, keys
 * and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredGroupCopyMarkKeepsEveryStoredRowTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val h = ExpiredGroupHarness(scheduler)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        runBlocking { h.forgetGroup() }
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `the scan stores the same rows whether the copy expired or mined`() =
        runBlocking {
            val expiredPhone = h.phone()
            val minedPhone = h.phone()
            // One group key on both phones, so both chains carry the very same memo.
            minedPhone.keys["$GID|1"] = expiredPhone.keys.getValue("$GID|1")
            val own = h.own(expiredPhone)

            h.open(expiredPhone, listOf(OpenGroupChain.failed(800, own)))
            h.open(minedPhone, listOf(OpenGroupChain.sent(800, own, 3_000_900L)))

            val expired = OpenGroup.rows(expiredPhone.messages, GID)
            val mined = OpenGroup.rows(minedPhone.messages, GID)
            assertEquals("the scan skipped the row of the expired copy", 1, expired.size)
            assertEquals("fixture: the mined copy is stored once", 1, mined.size)
            assertEquals(
                "the scan stores another row for an expired copy than for a mined one",
                withoutChainPosition(mined.single()).toString(),
                withoutChainPosition(expired.single()).toString(),
            )
            for (row in expired + mined) {
                val keys = row.keys().asSequence().toSet()
                assertTrue("a stored group row has a key outside the stored format: $keys", SCHEMA.containsAll(keys))
            }
        }

    @Test
    fun `the text of an expired message is still stored after two scans and a live pass`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)
            val chat = h.chat(phone)
            val chain = listOf(OpenGroupChain.failed(800, own))

            h.open(phone, chain)
            assertTrue("the first scan did not store the text", h.storedTexts(phone).contains(ExpiredGroupHarness.TEXT))
            h.livePass(chat, own)
            assertTrue("the live pass lost the text", h.storedTexts(phone).contains(ExpiredGroupHarness.TEXT))
            h.open(phone, chain)
            assertTrue("the second scan lost the text", h.storedTexts(phone).contains(ExpiredGroupHarness.TEXT))
        }

    @Test
    fun `a held row stays held, hidden and uncounted beside an expired own copy`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)
            // A row the live path held (GR-OPEN G1): a pre-removal-key message from someone who is no member.
            phone.messages[GID] =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "synthetic-held-row")
                            .put("groupId", GID)
                            .put("seq", 5)
                            .put("epoch", 1)
                            .put("sender", Z)
                            .put("decrypted", HELD_TEXT)
                            .put("timestamp", T + 5)
                            .put("held", "removal"),
                    ).toString()

            val (_, shown) = h.open(phone, listOf(OpenGroupChain.failed(800, own)))

            assertEquals("the held mark is gone from the store", "removal", h.storedRow(phone, HELD_TEXT)?.optString("held"))
            assertFalse("a held message is shown", shown.messages.any { it.decryptedContent == HELD_TEXT })
            assertFalse("a shown row is held", shown.messages.any { it.held })
            assertFalse("a held message is the group's last message", shown.lastMessage?.decryptedContent == HELD_TEXT)
            assertFalse("fixture: our own message is shown once, not held", h.only(shown).held)
        }

    @Test
    fun `an unsigned message under a pre-removal key that was never shown is still not added beside an expired own copy`() =
        runBlocking {
            val phone = AnnouncePhone()
            val admin = AnnounceDriver.memberPhone(phone, extraRows = listOf(h.member(Y), h.member(Z)))
            val chat = h.chat(phone)
            AnnounceDriver.receive(
                chat,
                AnnounceDriver.kick(phone, admin, Z, newEpoch = 2, newKey = AnnounceDriver.newGroupKey()),
                Instant.ofEpochMilli(T),
                AnnounceDriver.txId(720),
            )
            assertEquals("fixture: the removal raised the floor", 2, phone.floors[GID])
            val forged = AnnounceDriver.gm(phone, Y, "forged with the old key", epoch = 1, seq = 9)
            val own = h.own(phone, text = "ours after the removal", epoch = 2)

            h.open(phone, listOf(OpenGroupChain.received(721, forged, 3_000_721L), OpenGroupChain.failed(722, own)))

            assertTrue("the scan skipped our own expired message", h.storedTexts(phone).contains("ours after the removal"))
            assertFalse(
                "the scan added an unsigned message that decrypts only under a pre-removal key",
                h.storedTexts(phone).contains("forged with the old key"),
            )
        }

    @Test
    fun `relay rows are carried with every field beside an expired own copy`() =
        runBlocking {
            val phone = h.phone(extraRows = listOf(h.member(Y)))
            phone.link(Y)
            val chat = h.chat(phone)
            AnnounceDriver.receive(
                chat,
                AnnounceDriver.gm(phone, Y, RELAY_TEXT, epoch = 1, seq = 3),
                Instant.ofEpochMilli(T + 3_000),
                sender = Y,
                eventId = "synthetic-event-gm-y",
            )
            // Another own message that went free over the relays: its grps- row is its only trace.
            h.storeAsTheSendDoes(phone, h.ownRow(phone, text = OWN_RELAY_TEXT, seq = 2))
            val relayRow = h.storedRow(phone, RELAY_TEXT)
            val ownRelayRow = h.storedRow(phone, OWN_RELAY_TEXT)
            assertTrue("fixture: Y's relay message is a grpn- row", relayRow?.optString("id")?.startsWith("grpn-") == true)
            assertTrue("fixture: our relay-sent message is a grps- row", ownRelayRow?.optString("id")?.startsWith("grps-") == true)
            val relayBefore = carriedForm(relayRow)
            val ownRelayBefore = carriedForm(ownRelayRow)

            val (_, shown) = h.open(phone, listOf(OpenGroupChain.failed(800, h.own(phone))))

            assertEquals("the scan changed a relay row", relayBefore, carriedForm(h.storedRow(phone, RELAY_TEXT)))
            assertEquals("the scan changed our relay-sent row", ownRelayBefore, carriedForm(h.storedRow(phone, OWN_RELAY_TEXT)))
            assertFalse("another member's relay message is shown as failed", h.only(shown, RELAY_TEXT).isFailed)
            assertFalse("our relay-sent message is shown as failed", h.only(shown, OWN_RELAY_TEXT).isFailed)
            assertTrue("the scan skipped our own expired message", h.storedTexts(phone).contains(ExpiredGroupHarness.TEXT))
        }

    @Test
    fun `an expired copy in another group marks nothing here`() =
        runBlocking {
            val phone = h.phone()
            val own = h.own(phone)
            // The same sender, seq and epoch, in ANOTHER group.
            val elsewhere =
                ZMSGGroupProtocol.createGroupMsgMessage(
                    OTHER_GROUP,
                    1,
                    1,
                    phone.self,
                    "a message of another group",
                    ZMSGGroupProtocol.decodeGroupKey(phone.keys.getValue("$GID|1")),
                    null,
                )

            val (_, mined) = h.open(phone, listOf(OpenGroupChain.failed(800, elsewhere), OpenGroupChain.sent(801, own, 3_000_901L)))
            assertFalse("a mined message is shown as failed because another group's copy expired", h.only(mined).isFailed)

            val relayOnly = h.phone()
            h.storeAsTheSendDoes(relayOnly, h.ownRow(relayOnly))
            val (_, noCopy) = h.open(relayOnly, listOf(OpenGroupChain.failed(800, elsewhere)))
            assertFalse("a message with no chain copy is shown as failed because another group's copy expired", h.only(noCopy).isFailed)
            assertFalse("a message of another group was stored here", h.storedTexts(relayOnly).contains("a message of another group"))
        }

    /**
     * A stored row as the history merge has always carried it: every field but the live path's "txId", a copy of the
     * row id that no reader reads and the merge's rewrite leaves out (so, before and after the fix).
     */
    private fun carriedForm(row: JSONObject?): String {
        val copy = JSONObject(row?.toString() ?: throw AssertionError("the row is no longer stored"))
        copy.remove("txId")
        return copy.toString()
    }

    /** A stored row without the two fields that say where on the chain it was mined. */
    private fun withoutChainPosition(row: JSONObject): JSONObject =
        JSONObject(row.toString()).apply {
            remove("blockHeight")
            remove("txIndex")
        }

    private companion object {
        const val HELD_TEXT = "held until its sender posts again"
        const val RELAY_TEXT = "from Y over the relays"
        const val OWN_RELAY_TEXT = "ours over the relays"
        const val OTHER_GROUP = "zgrp_ffeeddccbbaa998877665544"

        /** Every key a stored group-message row may carry today. */
        val SCHEMA =
            setOf(
                "id",
                "groupId",
                "txId",
                "seq",
                "epoch",
                "sender",
                "encrypted",
                "decrypted",
                "nonce",
                "timestamp",
                "blockHeight",
                "txIndex",
                "held",
            )
    }
}
