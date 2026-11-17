package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.ZcashReceivers
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberAnnounceEntry
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.NEW
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakePassFixture.Companion.SELF
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.CURRENT_FEE
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.contact
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.mix
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RestoredSendScan.Companion.receiverForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ZC-757 case (k) (FIRST-CONTACT-SPEC 6.7 (k), N-13; rewritten on ZC-1082's restore filing) — the recipient text of a
 * restored wallet. After a restore the wallet library names our own invitation to M1 by M1's receiver form (one
 * receiver), never by M1's address text. ZC-1082 files such a form under the ONE address this phone knows that holds it
 * (RestoredOwnSendIsFiledUnderItsContactTest (g), where the contact is known before the pass).
 *
 * Here M1 is known only from the same restored history, as on a restored phone with no contact book: our own key
 * exchange to M1's form, M1's answer under our id (which files M1's chat under M1's address), then our own invitation to
 * M1's form. The rebuilt group must list M1 under M1's address (the chat that holds the key the next key change is
 * wrapped with), once, and never under M1's form or the fee's form, after any number of complete passes. When M1 is not
 * known at all, the form is kept (N-13 as it is) and still never the fee's.
 *
 * The group's info and member rows are kept between passes (a stored group), as on a phone. Every contact address is
 * synthetic; the fee address is the one the app pays. Failure messages name addresses by label only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltGroupMemberFromRestoredHistoryTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private val peer = contact(seed = 7571)
    private val peerForm = receiverForm(peer)
    private val feeForm = receiverForm(CURRENT_FEE)
    private val peerSaplingForm = receiverForm(peer, typecode = 2)
    private val stranger = contact(seed = 7572)

    /** A stranger's address made of M1's Orchard receiver and the stranger's own Sapling receiver. */
    private val strangerMix = mix(peer, 3, stranger, 2)

    /** The stranger's own Sapling receiver form (the second receiver of [strangerMix]). */
    private val strangerOwnForm = receiverForm(stranger, typecode = 2)

    /** Round 11: a built address T of M1's Orchard receiver F and a Sapling receiver of nobody this phone knows. */
    private val builtT = mix(peer, 3, contact(seed = 7573), 2)

    /** Round 15 (ZC-1168): a contact's full address (a transparent, a Sapling and an Orchard receiver). */
    private val fullUa = withTransparent(contact(seed = 7576), seed = 7576)

    /** Round 15 (ZC-1168): the same contact's smaller Unified Address (its Sapling and Orchard receivers only). */
    private val smallerUa = contact(seed = 7576)

    /** M1's key pair: [m1Key] is the key M1's rows carry; the pair lets recheck 6 P2's stranger send M1's public key. */
    private val m1Pair = E2EEncryption.generateKeyPair()
    private val m1Key = m1Pair.publicKey

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

    @Test
    fun `(k) M1's chat rebuilt from the same history - the rebuilt group lists M1 under its address, once`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            val own = "ZOWN0757"
            f.ownKexRestored("zc757-k-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            val theirs = E2EEncryption.generateKeyPair()
            f.received(
                "zc757-k-ack",
                ZMSGProtocol.createV4KEXAckMessage(own, peer, E2EEncryption.createKEXAckPayload(peer, theirs.publicKey, theirs.privateKey)),
                minedAt = NEW + 5,
            )
            f.sentRestored("zc757-k-invite", ownInvite(), listOf(feeForm, peerForm), recipient = peerForm, minedAt = NEW + 7)

            f.pass()

            assertEquals("fixture: M1's chat is not filed under M1's address", "contact", label(f.ids.conv(own)))
            assertEquals("the rebuilt group's member rows", listOf("contact", "self"), groups.rows())
        }

    @Test
    fun `(k) M1 not known at all - the row keeps M1's receiver form, never the fee's`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k2-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)

            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact form", "self"), groups.rows())
        }

    // ---- ZC-757 (k) recheck, 26 Oct: the repair of a roster an earlier build left (withFormRowRenamed) ---------------

    @Test
    fun `(k) recheck F1 - a stored row of M1's form moves to M1's address with every field, once`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-kf1-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // M1 becomes a contact with a finished key exchange: the stored key is the key the form row carries.
            // Round 9 (R2): vouching never moves a row, keyed or keyless; M1 answered our restored key exchange paid to its
            // form, claimed from M1's address (test-change-ok: ZC-757 round 9).
            m1AnsweredOwnKex(f, "zc757-kf1-o", peerForm, "ZKF1O757", NEW + 1)
            f.prefs.e2ePeerKey[peer] = m1Key
            f.book += peer
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact", "self"), groups.rows())
            assertEquals(
                "M1's row under M1's address keeps every field of its form row",
                masked(stored.copy(address = peer)),
                masked(groups.row(peer)),
            )
        }

    @Test
    fun `(k) recheck F1 control - M1 not known, the stored form row stays as it is`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-kf1c-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact form", "self"), groups.rows())
            assertEquals("M1's form row", masked(stored), masked(groups.row(peerForm)))
        }

    @Test
    fun `(k) recheck F2 - M1's form row never moves to a stranger's address that also holds the form`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-kf2-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // A stranger's chat, opened by the stranger's message from an address made of M1's Orchard receiver and the
            // stranger's own Sapling one. Not in the contact book, no own-id record: the phone does not vouch for it.
            f.ids.store.setConversationMapping("ZSTR0757", strangerMix)
            f.pass()

            assertEquals(
                "M1's form row (status, key and pending key fields stay on it)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("the key on a row under the stranger's address", null, masked(groups.row(strangerMix))?.publicKey)
        }

    @Test
    fun `(k) recheck nit A - two form rows of M1 (Orchard and Sapling) become one row under M1's address`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-ka-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val orchard = groups.row(peerForm)!!.copy(status = MemberStatus.INVITED, inviteStatus = InviteStatus.SENT)
            groups.replace(peerForm, orchard)
            val sapling =
                GroupMember(
                    address = peerSaplingForm,
                    joinedAt = Instant.ofEpochMilli(1_760_000_100_000L),
                    status = MemberStatus.ACTIVE,
                    inviteStatus = InviteStatus.SENT,
                )
            groups.add(sapling)
            assertEquals("fixture: the stored rows", listOf("contact form", "contact sapling form", "self"), groups.rows())

            // Owner W-90 (round 8): a keyless form row moves only with key-exchange evidence for that exact form, never on
            // vouching alone. M1 answered our restored key exchange paid to each of its forms (test-change-ok: W-90).
            m1AnsweredOwnKex(f, "zc757-ka-o", peerForm, "ZKAO0757", NEW + 1)
            m1AnsweredOwnKex(f, "zc757-ka-s", peerSaplingForm, "ZKAS0757", NEW + 3)
            f.book += peer
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact", "self"), groups.rows())
            assertEquals("M1's row keeps the most advanced status of its form rows", MemberStatus.ACTIVE, groups.row(peer)?.status)
        }

    @Test
    fun `(k) recheck F3 - a pass with several unresolved forms reads the known peers at most once more`() =
        runBlocking {
            val (f, _) = restoredPhone()
            val reads = AtomicInteger()
            countKnownPeerReads(f.vm, reads)
            for (seed in 7581..7584) {
                val form = receiverForm(contact(seed))
                f.sentRestored("zc757-kf3-invite-$seed", ownInvite(), listOf(feeForm, form), recipient = feeForm, minedAt = NEW + 7)
            }

            f.pass(times = 1)

            assertTrue("fixture: the pass reads the known peers", reads.get() >= 1)
            assertTrue(
                "reads of the known peers in one pass with four unresolved forms: ${reads.get()}, wanted at most 2",
                reads.get() <= 2,
            )
        }

    // ---- ZC-757 (k) recheck 2, 26 Oct: the merge of M1's form rows (withFormRowRenamed) and the stranger's row ---------

    @Test
    fun `(k) recheck 2 F1 - M1 left under the Sapling form and came back under the Orchard form - the merged row is ACTIVE`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k3f1-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val back =
                groups.row(peerForm)!!.copy(
                    joinedAt = Instant.ofEpochMilli(1_760_000_200_000L),
                    status = MemberStatus.ACTIVE,
                    inviteStatus = InviteStatus.SENT,
                )
            groups.replace(peerForm, back)
            groups.add(
                GroupMember(
                    address = peerSaplingForm,
                    joinedAt = Instant.ofEpochMilli(1_760_000_100_000L),
                    status = MemberStatus.LEFT,
                    inviteStatus = InviteStatus.SENT,
                ),
            )
            assertEquals("fixture: the stored rows", listOf("contact form", "contact sapling form", "self"), groups.rows())

            // Owner W-90 (round 8): a keyless form row moves only with key-exchange evidence for that exact form, never on
            // vouching alone. M1 answered our restored key exchange paid to each of its forms (test-change-ok: W-90).
            m1AnsweredOwnKex(f, "zc757-k3f1-o", peerForm, "Z3F1O757", NEW + 1)
            m1AnsweredOwnKex(f, "zc757-k3f1-s", peerSaplingForm, "Z3F1S757", NEW + 3)
            f.book += peer
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact", "self"), groups.rows())
            assertEquals(
                "M1's merged row takes the newest status (ACTIVE under the Orchard form after LEFT under the Sapling one)",
                MemberStatus.ACTIVE,
                groups.row(peer)?.status,
            )
        }

    @Test
    fun `(k) recheck 2 F2 - the merged row keeps the key, the pending key epoch, every pending txid and the invite status`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k3f2-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val invited =
                GroupMember(
                    address = peerForm,
                    publicKey = m1Key,
                    joinedAt = Instant.ofEpochMilli(1_760_000_100_000L),
                    status = MemberStatus.INVITED,
                    inviteStatus = InviteStatus.SENT,
                    pendingKeyEpoch = 3,
                    pendingKeyTxIds = listOf("synthetic-key-send-0757-a"),
                )
            groups.replace(peerForm, invited)
            groups.add(
                GroupMember(
                    address = peerSaplingForm,
                    joinedAt = Instant.ofEpochMilli(1_760_000_200_000L),
                    status = MemberStatus.ACTIVE,
                    pendingKeyTxIds = listOf("synthetic-key-send-0757-b"),
                ),
            )
            assertEquals("fixture: the stored rows", listOf("contact form", "contact sapling form", "self"), groups.rows())

            // Owner W-90 (round 8): a keyless form row moves only with key-exchange evidence for that exact form, never on
            // vouching alone. M1 answered our restored key exchange paid to each of its forms (test-change-ok: W-90).
            m1AnsweredOwnKex(f, "zc757-k3f2-o", peerForm, "Z3F2O757", NEW + 1)
            m1AnsweredOwnKex(f, "zc757-k3f2-s", peerSaplingForm, "Z3F2S757", NEW + 3)
            // M1 becomes a contact with a finished key exchange: the stored key is the key the Orchard form row carries.
            f.prefs.e2ePeerKey[peer] = m1Key
            f.book += peer
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact", "self"), groups.rows())
            val merged = masked(groups.row(peer))
            assertEquals("M1's merged row: status", MemberStatus.ACTIVE, merged?.status)
            assertEquals("M1's merged row: key (the first one set)", "M1's key", merged?.publicKey)
            assertEquals("M1's merged row: pending key epoch (the highest set)", 3, merged?.pendingKeyEpoch)
            assertEquals(
                "M1's merged row: pending key txids (the union)",
                setOf("synthetic-key-send-0757-a", "synthetic-key-send-0757-b"),
                merged?.pendingKeyTxIds?.toSet(),
            )
            assertEquals("M1's merged row: invite status (the most advanced)", InviteStatus.SENT, merged?.inviteStatus)
        }

    @Test
    fun `(k) recheck 2 F3 - our own invite to M1's form adds no row under a stranger's address that also holds it`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k3f3-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // As recheck F2: a stranger's chat from an address made of M1's Orchard receiver and the stranger's Sapling
            // one. Not in the contact book, no own-id record: the phone does not vouch for it.
            f.ids.store.setConversationMapping("ZSTR0758", strangerMix)
            f.pass()

            assertEquals("a row under the stranger's address", null, masked(groups.row(strangerMix)))
            assertEquals("the rebuilt group's member rows", listOf("contact form", "self"), groups.rows())
        }

    @Test
    fun `(k) recheck 2 nit - M1's chat filed earlier in the same pass, our own invite to M1's full address - one row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k3n-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            assertEquals("fixture: the stored rows", listOf("contact form", "self"), groups.rows())

            val own = "ZOWN0758"
            f.ownKexRestored("zc757-k3n-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 9)
            val theirs = E2EEncryption.generateKeyPair()
            f.received(
                "zc757-k3n-ack",
                ZMSGProtocol.createV4KEXAckMessage(own, peer, E2EEncryption.createKEXAckPayload(peer, theirs.publicKey, theirs.privateKey)),
                minedAt = NEW + 11,
            )
            f.sentRestored("zc757-k3n-invite2", ownInvite(), listOf(feeForm, peer), recipient = peer, minedAt = NEW + 13)
            f.pass()

            assertEquals("fixture: M1's chat is not filed under M1's address", "contact", label(f.ids.conv(own)))
            assertEquals("the rebuilt group's member rows", listOf("contact", "self"), groups.rows())
        }

    // ---- ZC-757 (k) recheck 3, 26 Oct: who may take M1's form row, and the merge's time and fields ---------------------

    @Test
    fun `(k) recheck 3 F1 - our own invite to a stranger's address that holds M1's form leaves M1's row and adds one keyless row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k4f1-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // The stranger built an address of M1's Orchard receiver and its own Sapling one and opened a chat with it
            // (not in the contact book, no own-id record). M1's full address is known nowhere. The user then invites the
            // stranger's address into the group: our own send names that full address.
            f.ids.store.setConversationMapping("ZSTR0759", strangerMix)
            f.sentRestored("zc757-k4f1-invite2", ownInvite(), listOf(feeForm, strangerMix), recipient = strangerMix, minedAt = NEW + 9)
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact form", "self", "stranger's address"), groups.rows())
            assertEquals(
                "M1's form row (status, key and pending key fields stay on it)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("the key on the row under the stranger's address", null, masked(groups.row(strangerMix))?.publicKey)
        }

    @Test
    fun `(k) recheck 3 F2 - M1 known only from a chat M1 opened - our own invite to M1's form keeps a row under the form`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            // The group is rebuilt first from our own invite to another member's full address.
            val other = contact(seed = 7586)
            f.sentRestored("zc757-k4f2-other", ownInvite(), listOf(feeForm, other), recipient = other, minedAt = NEW + 5)
            // M1's chat, opened by M1's message (not accepted: not in the contact book, no own-id record), known before
            // the pass; our own invitation to M1 is listed under M1's Orchard form, the form actually paid.
            f.ids.store.setConversationMapping("ZM1C0757", peer)
            f.sentRestored("zc757-k4f2-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)

            f.pass()

            assertEquals(
                "the rebuilt group's member rows other than the first invitee's (M1 must be in one)",
                listOf("contact form", "self"),
                groups.rows().filterNot { it.startsWith("other") },
            )
        }

    @Test
    fun `(k) recheck 3 F3 - an ACTIVE row with a chain time is not lowered by a row whose only time is the restore's clock`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k4f3-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val active =
                groups.row(peerForm)!!.copy(
                    joinedAt = Instant.ofEpochMilli(1_700_000_000_000L),
                    status = MemberStatus.ACTIVE,
                    inviteStatus = InviteStatus.SENT,
                )
            groups.replace(peerForm, active)
            // M1's announce record: activated at a chain time (after its join, long before this restore).
            val base = f.extra
            var announce = AnnounceState(members = mapOf(peerForm to MemberAnnounceEntry(activatedAtMs = 1_700_000_100_000L)))
            f.extra = { method, a ->
                when (method.name) {
                    "getGroupAnnounceState" -> announce
                    "saveGroupAnnounceState" -> {
                        announce = a[1] as AnnounceState
                        true
                    }
                    else -> base?.invoke(method, a)
                }
            }

            // Our own invite to M1's Sapling form, M1 not known yet: the pass adds an INVITED row under that form, its
            // only time the restore's wall clock.
            f.sentRestored("zc757-k4f3-invite2", ownInvite(), listOf(feeForm, peerSaplingForm), recipient = feeForm, minedAt = NEW + 9)
            f.pass()
            assertEquals("fixture: the stored rows", listOf("contact form", "contact sapling form", "self"), groups.rows())

            // Owner W-90 (round 8): a keyless form row moves only with key-exchange evidence for that exact form, never on
            // vouching alone. M1 answered our restored key exchange paid to each of its forms (test-change-ok: W-90).
            m1AnsweredOwnKex(f, "zc757-k4f3-o", peerForm, "Z4F3O757", NEW + 1)
            m1AnsweredOwnKex(f, "zc757-k4f3-s", peerSaplingForm, "Z4F3S757", NEW + 3)
            f.book += peer
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact", "self"), groups.rows())
            assertEquals(
                "M1's merged row keeps ACTIVE (chain time) over INVITED (the restore's wall clock)",
                MemberStatus.ACTIVE,
                groups.row(peer)?.status,
            )
        }

    @Test
    fun `(k) recheck 3 F4 - the merged row takes the newest row's key and only the pending txids of the highest epoch`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k4f4-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            // The older row carries no key (a row tied to M1 holds M1's key or none, ZC-757 (k) option B).
            val older =
                GroupMember(
                    address = peerForm,
                    joinedAt = Instant.ofEpochMilli(1_760_000_100_000L),
                    status = MemberStatus.ACTIVE,
                    inviteStatus = InviteStatus.SENT,
                    pendingKeyEpoch = 2,
                    pendingKeyTxIds = listOf("synthetic-key-send-0759-old"),
                )
            groups.replace(peerForm, older)
            groups.add(
                GroupMember(
                    address = peerSaplingForm,
                    publicKey = m1Key,
                    joinedAt = Instant.ofEpochMilli(1_760_000_200_000L),
                    status = MemberStatus.ACTIVE,
                    inviteStatus = InviteStatus.SENT,
                    pendingKeyEpoch = 3,
                    pendingKeyTxIds = listOf("synthetic-key-send-0759-new"),
                ),
            )
            assertEquals("fixture: the stored rows", listOf("contact form", "contact sapling form", "self"), groups.rows())

            // Owner W-90 (round 8): a keyless form row moves only with key-exchange evidence for that exact form, never on
            // vouching alone. M1 answered our restored key exchange paid to each of its forms (test-change-ok: W-90).
            m1AnsweredOwnKex(f, "zc757-k4f4-o", peerForm, "Z4F4O757", NEW + 1)
            m1AnsweredOwnKex(f, "zc757-k4f4-s", peerSaplingForm, "Z4F4S757", NEW + 3)
            // M1 becomes a contact with a finished key exchange: the stored key is the key the newest form row carries.
            f.prefs.e2ePeerKey[peer] = m1Key
            f.book += peer
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact", "self"), groups.rows())
            val merged = masked(groups.row(peer))
            assertEquals("M1's merged row: key (the newest row's)", "M1's key", merged?.publicKey)
            assertEquals("M1's merged row: pending key epoch (the highest)", 3, merged?.pendingKeyEpoch)
            assertEquals(
                "M1's merged row: pending key txids (only those of the highest epoch)",
                setOf("synthetic-key-send-0759-new"),
                merged?.pendingKeyTxIds?.toSet(),
            )
        }

    // ---- ZC-757 (k) recheck 4, 26 Oct: whose key exchange is evidence, and which row our own mined invite delivers ------

    @Test
    fun `(k) recheck 4 F1 - a stranger who answered our key exchange to its own receiver never takes M1's form row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k5f1-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // The stranger built an address of M1's Orchard receiver and its own Sapling one. We once started a key
            // exchange with the stranger that paid the stranger's own Sapling receiver, and the stranger answered under
            // its id (filed under the stranger's address). M1's full address is known nowhere.
            val strangerSaplingForm = receiverForm(stranger, typecode = 2)
            val own = "ZSTK0757"
            f.ownKexRestored(
                "zc757-k5f1-own",
                keyFor = strangerMix,
                outputs = listOf(feeForm, strangerSaplingForm),
                convId = own,
                minedAt = NEW + 9,
            )
            val theirs = E2EEncryption.generateKeyPair()
            f.received(
                "zc757-k5f1-ack",
                ZMSGProtocol.createV4KEXAckMessage(
                    own,
                    strangerMix,
                    E2EEncryption.createKEXAckPayload(strangerMix, theirs.publicKey, theirs.privateKey),
                ),
                minedAt = NEW + 11,
            )
            // Our next invite to M1's Orchard form (the form actually paid).
            f.sentRestored("zc757-k5f1-invite2", ownInvite(), listOf(feeForm, peerForm), recipient = peerForm, minedAt = NEW + 13)
            f.pass()

            assertEquals("fixture: the stranger's answer is filed under its address", "stranger's address", label(f.ids.conv(own)))
            assertEquals(
                "M1's form row (status, key and pending key fields stay on it; the key exchange never paid that form)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("the key on a row under the stranger's address", null, masked(groups.row(strangerMix))?.publicKey)
        }

    @Test
    fun `(k) recheck 4 F2 - our mined invite to M1's form marks only the form's row SENT, never the unvouched holder's`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k5f2-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            groups.replace(peerForm, groups.row(peerForm)!!.copy(status = MemberStatus.INVITED, inviteStatus = InviteStatus.FAILED))
            // The stranger's own row: invited earlier, its invite failed.
            groups.add(
                GroupMember(
                    address = strangerMix,
                    joinedAt = Instant.ofEpochMilli(1_760_000_100_000L),
                    status = MemberStatus.INVITED,
                    inviteStatus = InviteStatus.FAILED,
                ),
            )
            assertEquals("fixture: the stored rows", listOf("contact form", "self", "stranger's address"), groups.rows())

            // The stranger's chat (address of M1's Orchard receiver and its own Sapling one), not vouched: our mined
            // invite to M1's Orchard form is filed under the stranger's address.
            f.ids.store.setConversationMapping("ZSTR0760", strangerMix)
            f.pass()

            assertEquals(
                "the invite status of the stranger's own row (our invite paid M1's form, not the stranger)",
                InviteStatus.FAILED,
                groups.row(strangerMix)?.inviteStatus,
            )
            assertEquals(
                "the invite status of M1's form row (the form actually paid)",
                InviteStatus.SENT,
                groups.row(peerForm)?.inviteStatus,
            )
        }

    @Test
    fun `(k) recheck 4 nit - our own invite to M1's form is recorded under the form, never the unvouched holder`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            val base = f.extra
            var announce = AnnounceState()
            f.extra = { method, a ->
                when (method.name) {
                    "getGroupAnnounceState" -> announce
                    "saveGroupAnnounceState" -> {
                        announce = a[1] as AnnounceState
                        true
                    }
                    else -> base?.invoke(method, a)
                }
            }
            f.sentRestored("zc757-k5n-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            assertEquals("fixture: the stored rows", listOf("contact form", "self"), groups.rows())
            assertEquals("fixture: our invite is recorded under M1's form", true, announce.members[peerForm]?.invitedByUs)

            f.ids.store.setConversationMapping("ZSTR0761", strangerMix)
            f.pass()

            assertEquals(
                "our own invite recorded under the stranger's address (it paid M1's form)",
                false,
                announce.members[strangerMix]?.invitedByUs ?: false,
            )
        }

    // ---- ZC-757 (k) recheck 5, 27 Oct: evidence counts only for the form rows it is evidence for ---------------------

    @Test
    fun `(k) recheck 5 F1 - a key exchange that paid the stranger's own form never moves M1's form row with it`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k6f1-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)
            // The stranger's own row, under its own Sapling form (an earlier invite to the stranger, named by that form).
            groups.add(strangerOwnRow())
            assertEquals("fixture: the stored rows", listOf("contact form", "self", "stranger's own form"), groups.rows())

            // As recheck 4 F1: our own key exchange paid the stranger's own Sapling form and the stranger answered under
            // its id (filed under the stranger's address, which holds M1's Orchard form too); then our invite to M1's form.
            val own = "ZSTK0758"
            f.ownKexRestored(
                "zc757-k6f1-own",
                keyFor = strangerMix,
                outputs = listOf(feeForm, strangerOwnForm),
                convId = own,
                minedAt = NEW + 9,
            )
            val theirs = E2EEncryption.generateKeyPair()
            f.received(
                "zc757-k6f1-ack",
                ZMSGProtocol.createV4KEXAckMessage(
                    own,
                    strangerMix,
                    E2EEncryption.createKEXAckPayload(strangerMix, theirs.publicKey, theirs.privateKey),
                ),
                minedAt = NEW + 11,
            )
            f.sentRestored("zc757-k6f1-invite2", ownInvite(), listOf(feeForm, peerForm), recipient = peerForm, minedAt = NEW + 13)
            f.pass()

            assertEquals("fixture: the stranger's answer is filed under its address", "stranger's address", label(f.ids.conv(own)))
            assertEquals(
                "M1's form row (status, key and pending key fields stay on it; the key exchange paid only the stranger's form)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("M1's key on a row under the stranger's address", false, masked(groups.row(strangerMix))?.publicKey == "M1's key")
        }

    @Test
    fun `(k) recheck 5 F2 vouched - a vouched member whose address holds M1's form takes only its own form row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k6v-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)
            groups.add(strangerOwnRow())
            assertEquals("fixture: the stored rows", listOf("contact form", "self", "stranger's own form"), groups.rows())

            // This phone started a chat with the group member S (an own-id record names S's address, which holds M1's
            // Orchard form and S's own Sapling form): our stored invite to M1's form is now filed under S's address.
            f.ids.store.getOrCreateConversationId(strangerMix)
            f.pass()

            assertEquals(
                "M1's form row (another member's row: status, key and pending key fields stay on it)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("M1's key on a row under S's address", false, masked(groups.row(strangerMix))?.publicKey == "M1's key")
        }

    @Test
    fun `(k) recheck 5 F2 announce - a member the announce record activated takes only its own form row, never M1's`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            val base = f.extra
            var announce =
                AnnounceState(
                    members = mapOf(strangerMix to MemberAnnounceEntry(activatedAtMs = 1_760_000_150_000L, activatedBy = ActivatedBy.AUTH)),
                )
            f.extra = { method, a ->
                when (method.name) {
                    "getGroupAnnounceState" -> announce
                    "saveGroupAnnounceState" -> {
                        announce = a[1] as AnnounceState
                        true
                    }
                    else -> base?.invoke(method, a)
                }
            }
            f.sentRestored("zc757-k6a-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)
            groups.add(strangerOwnRow())
            assertEquals("fixture: the stored rows", listOf("contact form", "self", "stranger's own form"), groups.rows())

            // S's chat (not vouched: no contact, no own-id record), S's address holding M1's Orchard form and S's own
            // Sapling one; the announce record names S's address as activated through an authenticated step.
            f.ids.store.setConversationMapping("ZSTR0762", strangerMix)
            f.pass()

            assertEquals(
                "M1's form row (another member's row: status, key and pending key fields stay on it)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("M1's key on a row under S's address", false, masked(groups.row(strangerMix))?.publicKey == "M1's key")
        }

    @Test
    fun `(k) W-86 - a group rebuilt from our own invite to M1's form lists the paid form, never a stranger's address`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            // A stranger's chat, known before the pass, from an address made of M1's Orchard receiver and the stranger's
            // own Sapling one (not vouched: no contact, no own-id record). M1's full address is known nowhere.
            f.ids.store.setConversationMapping("ZSTR0763", strangerMix)
            f.sentRestored("zc757-k6r-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)

            f.pass()

            assertEquals(
                "the rebuilt group's member rows (owner W-86: an unvouched holder's row goes under the form actually paid)",
                listOf("contact form", "self"),
                groups.rows(),
            )
        }

    // ---- ZC-757 (k) recheck 6, 27 Oct: an exchange paid to a full address is no evidence for a form it merely holds ----

    @Test
    fun `(k) recheck 6 P1a - S answered our key exchange paid to S's full address - M1's form row stays, S gets its own row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k7a-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // After the restore the user starts a chat with S at S's address (M1's Orchard receiver and S's own Sapling
            // one); our key exchange is recorded as paid to that full address and S answers it under its id. The id is
            // filed under S by S's claim only (not in the contact book, no own-id record): S is not vouched. S is the
            // only known address that holds M1's form; M1's full address is known nowhere.
            val own = "ZS7A0757"
            f.ids.store.setConversationMapping(own, strangerMix)
            f.ownKexRestored("zc757-k7a-own", keyFor = strangerMix, outputs = listOf(feeForm, strangerMix), convId = own, minedAt = NEW + 9)
            f.received("zc757-k7a-ack", strangerAck(own), minedAt = NEW + 11)
            // Then the user invites S into the group: our own send names S's full address.
            f.sentRestored("zc757-k7a-invite2", ownInvite(), listOf(feeForm, strangerMix), recipient = strangerMix, minedAt = NEW + 13)
            f.pass()

            assertEquals("fixture: S's answer is filed under its address", "stranger's address", label(f.ids.conv(own)))
            assertM1RowStaysAndStrangerHasItsOwn(groups, stored)
        }

    @Test
    fun `(k) recheck 6 P1b - S answered our key exchange whose recipients are S's form and S - M1's form row stays`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k7b-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // This phone started a chat with S (an own-id record names S's address: S is vouched). Our restored key
            // exchange paid S's own Sapling form; its recipients are that form and, appended as its vouched holder, S's
            // full address (which also holds M1's Orchard form). S answered under the id, both mined before our invite to
            // M1's form (so the pass reads them before it replays that invite, now filed under S). Then we invite S.
            val own =
                f.ids.store
                    .getOrCreateConversationId(strangerMix)
                    .first
            f.ownKexRestored(
                "zc757-k7b-own",
                keyFor = strangerMix,
                outputs = listOf(feeForm, strangerOwnForm),
                convId = own,
                minedAt = NEW + 3,
            )
            f.received("zc757-k7b-ack", strangerAck(own), minedAt = NEW + 5)
            f.sentRestored("zc757-k7b-invite2", ownInvite(), listOf(feeForm, strangerMix), recipient = strangerMix, minedAt = NEW + 13)
            f.pass()

            assertEquals("fixture: S's answer is filed under its address", "stranger's address", label(f.ids.conv(own)))
            assertM1RowStaysAndStrangerHasItsOwn(groups, stored)
        }

    @Test
    fun `(k) recheck 6 P1c W-86 - our own invite paid to M1's form and filed under S is written under the form, never S`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            // As P1a: our key exchange paid to S's full address, S answered under its id, S not vouched. Then the group is
            // rebuilt from our own invite paid to M1's Orchard form, which the pass files under S (the one known holder).
            val own = "ZS7C0757"
            f.ids.store.setConversationMapping(own, strangerMix)
            f.ownKexRestored("zc757-k7c-own", keyFor = strangerMix, outputs = listOf(feeForm, strangerMix), convId = own, minedAt = NEW + 3)
            f.received("zc757-k7c-ack", strangerAck(own), minedAt = NEW + 5)
            f.sentRestored("zc757-k7c-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)

            f.pass()

            assertEquals("fixture: S's answer is filed under its address", "stranger's address", label(f.ids.conv(own)))
            assertEquals(
                "the rebuilt group's member rows (owner W-86: the row goes under the form actually paid; S never proved it)",
                listOf("contact form", "self"),
                groups.rows(),
            )
        }

    @Test
    fun `(k) recheck 6 P2 - a vouched S whose unaccepted key exchange carries M1's key never takes M1's keyed form row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k7p2-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // S sends a first key exchange that carries M1's PUBLIC key: the phone stores it and holds it for Accept. S
            // never answered an exchange of ours, so the key is not proven to be S's. Then the user saves S as a contact
            // (vouched), without Accept.
            val payload = E2EEncryption.createKEXPayload(strangerMix, m1Pair.publicKey, m1Pair.privateKey)
            f.received("zc757-k7p2-kex", ZMSGProtocol.createV4KEXMessage("ZS7P0757", strangerMix, payload), minedAt = NEW + 9)
            f.pass()
            f.book += strangerMix
            f.pass()

            assertEquals("fixture: the key stored for S is M1's", true, f.prefs.e2ePeerKey[strangerMix] == m1Key)
            assertEquals("fixture: S's key waits for Accept", true, f.prefs.awaitingAccept.containsKey(strangerMix))
            assertEquals(
                "M1's form row (status, key and pending key fields stay on it; S's key is only claimed, never proven)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("M1's key on a row under S's address", false, masked(groups.row(strangerMix))?.publicKey == "M1's key")
        }

    // ---- ZC-757 (k) round 8, 27 Oct (owner W-90; ZC-1137, ZC-1138): only key-exchange evidence for the exact form ----

    @Test
    fun `(k) round 8 a W-90 - a contact S, sole holder of M1's form, never takes M1's keyless form row when we invite S`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k8a-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm).copy(publicKey = null)
            groups.replace(peerForm, stored)

            // The user saves S (an address of M1's Orchard receiver and S's own Sapling one) as a contact: S is vouched and
            // the one known address that holds M1's form. M1's full address is known nowhere; S never answered a key
            // exchange of ours that paid M1's form. Then the user invites S into the group.
            f.book += strangerMix
            f.sentRestored("zc757-k8a-invite2", ownInvite(), listOf(feeForm, strangerMix), recipient = strangerMix, minedAt = NEW + 9)
            f.pass()

            assertM1RowStaysAndStrangerHasItsOwn(groups, stored)
        }

    @Test
    fun `(k) round 8 b ZC-1137 - our key exchange to M1's form mapped to a contact S with no answer never moves M1's keyed row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k8b-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // S (M1's Orchard receiver and S's own Sapling one) is a contact, the one known holder of M1's form. Our restored
            // key exchange paid M1's form; the pass restores its id as our own for S (restoreOwnConversationId), but no
            // answer under that id was ever taken.
            f.book += strangerMix
            val own = "ZK8B0757"
            f.ownKexRestored("zc757-k8b-own", keyFor = peerForm, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()

            assertEquals("fixture: our key exchange's id is mapped to S", "stranger's address", label(f.ids.conv(own)))
            assertEquals(
                "M1's form row (status, key and pending key fields stay on it; an id mapping is no answer)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("M1's key on a row under S's address", false, masked(groups.row(strangerMix))?.publicKey == "M1's key")
        }

    @Test
    fun `(k) round 8 c ZC-1138 - M1's older-key form row stays its own row when M1's answer paid to it carries M1's current key`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            val sapling = round8cRows(f, groups)

            // Later M1 answers our restored key exchange paid to its Sapling form, with M1's current key (the main row's key,
            // not the Sapling row's). Round 10 (ZC-1145, test-change-ok: orchestrator decision 27 Oct): a keyed form row
            // moves or merges only on an answer that carries the row's own key; from this phone's view this is round 10
            // P1-i's setup, so the row stays (the duplicate row is ZC-1138, low).
            val main = masked(groups.row(peer))
            m1AnsweredOwnKex(f, "zc757-k8c", peerSaplingForm, "ZK8C0757", NEW + 3)
            f.pass()

            assertEquals("fixture: M1's answer is filed under M1's address", "contact", label(f.ids.conv("ZK8C0757")))
            assertEquals(
                "the rebuilt group's member rows (no row lost, none merged)",
                listOf("contact", "contact sapling form", "self"),
                groups.rows(),
            )
            assertEquals(
                "M1's older-key Sapling form row (its own key and fields)",
                masked(sapling),
                masked(groups.row(peerSaplingForm)),
            )
            assertEquals("M1's main row (unchanged)", main, masked(groups.row(peer)))
        }

    @Test
    fun `(k) round 8 c ZC-1138 sibling - M1's older-key form row merges into M1's row when M1's answer paid to it carries that key`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            val older = E2EEncryption.generateKeyPair()
            round8cRows(f, groups, older.publicKey)

            // M1 answers our restored key exchange paid to its Sapling form with the Sapling row's own (older) key: evidence
            // for that exact form tied to that row.
            m1AnsweredOwnKex(f, "zc757-k8s", peerSaplingForm, "ZK8S0757", NEW + 3, older)
            f.pass()

            assertEquals("fixture: M1's answer is filed under M1's address", "contact", label(f.ids.conv("ZK8S0757")))
            assertEquals("the rebuilt group's member rows (the Sapling row is not left behind)", listOf("contact", "self"), groups.rows())
        }

    /**
     * Round 8 c's stored rows: M1's Orchard form row with M1's current key moved to M1's address on M1's answer, and M1's
     * Sapling form row with an older key of M1's ([saplingKey]) left under its form. Returns the Sapling row as stored.
     */
    private suspend fun round8cRows(
        f: HandshakePassFixture,
        groups: Groups,
        saplingKey: String = E2EEncryption.generateKeyPair().publicKey,
    ): GroupMember {
        f.sentRestored("zc757-k8c-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
        f.pass()
        groups.replace(peerForm, advanced(peerForm).copy(joinedAt = Instant.ofEpochMilli(1_760_000_200_000L)))
        val sapling =
            GroupMember(
                address = peerSaplingForm,
                publicKey = saplingKey,
                joinedAt = Instant.ofEpochMilli(1_760_000_100_000L),
                status = MemberStatus.ACTIVE,
                inviteStatus = InviteStatus.SENT,
            )
        groups.add(sapling)
        assertEquals("fixture: the stored rows", listOf("contact form", "contact sapling form", "self"), groups.rows())

        // M1 becomes a contact with a finished key exchange (the stored key is the Orchard row's): that row moves.
        // Round 9 (R2): vouching never moves a row, keyed or keyless; M1 answered our restored key exchange paid to its
        // Orchard form, claimed from M1's address (test-change-ok: ZC-757 round 9).
        m1AnsweredOwnKex(f, "zc757-k8c-o", peerForm, "ZK8CO757", NEW + 1)
        f.prefs.e2ePeerKey[peer] = m1Key
        f.book += peer
        f.pass()
        assertEquals(
            "fixture: M1's current-key form row moved, the older-key one stays",
            listOf("contact", "contact sapling form", "self"),
            groups.rows(),
        )
        return sapling
    }

    // ---- ZC-757 (k) round 9, 27 Oct (owner W-90): a row moves only on an answer from the target address -------------
    // R1: a member's form row moves to an address A only on an answer to our own key exchange that paid exactly that form
    // and whose own claimed address resolves to A; an id mapping never makes another party's answer count for A. R2:
    // vouching (contact book, own-id record, announce AUTH, stored key) never moves a row. R3: a merge into an existing
    // row never drops a form row whose key differs from that row's key.

    @Test
    fun `(k) round 9 1 - M1's answer to our key exchange paid to M1's form, its id mapped to a contact S, never moves M1's row to S`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k9a-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // S (M1's Orchard receiver and S's own Sapling one) is a contact, the one known holder of M1's form. Our restored
            // key exchange paid M1's form; on restore its id is mapped to S. M1 answers it, claiming M1's own address.
            f.book += strangerMix
            m1AnsweredOwnKex(f, "zc757-k9a", peerForm, "ZK9A0757", NEW + 3)
            f.pass()

            assertEquals(
                "M1's form row (status, key and pending key fields stay on it; M1's answer claims M1, not S)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("M1's key on a row under S's address", false, masked(groups.row(strangerMix))?.publicKey == "M1's key")
            assertEquals("M1's key stored for S's address", false, f.prefs.e2ePeerKey[strangerMix] == m1Key)
        }

    @Test
    fun `(k) round 9 2 - with the same evidence S's own row never swallows M1's form row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k9b-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)
            // S's own row under S's address, with a key of S's own.
            groups.add(strangerOwnRow().copy(address = strangerMix))
            assertEquals("fixture: the stored rows", listOf("contact form", "self", "stranger's address"), groups.rows())

            // As round 9 1: S a contact and sole holder of M1's form, our key exchange paid M1's form, its id mapped to S,
            // M1's answer claims M1's own address.
            f.book += strangerMix
            m1AnsweredOwnKex(f, "zc757-k9b", peerForm, "ZK9B0757", NEW + 3)
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact form", "self", "stranger's address"), groups.rows())
            assertEquals(
                "M1's form row (status, key and pending key fields stay on it)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("the key on S's own row", "another key", masked(groups.row(strangerMix))?.publicKey)
        }

    @Test
    fun `(k) round 9 2 R3 - a merge into S's existing row never drops a form row that carries another key`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k9r-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)
            groups.add(strangerOwnRow().copy(address = strangerMix))
            assertEquals("fixture: the stored rows", listOf("contact form", "self", "stranger's address"), groups.rows())

            // S is a contact and the sole holder of M1's form; our restored key exchange paid M1's form and S answers it
            // under its id, claiming S's own address (evidence under R1). M1's form row carries M1's key, S's row S's key.
            f.book += strangerMix
            val own = "ZK9R0757"
            f.ownKexRestored("zc757-k9r-own", keyFor = strangerMix, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.received("zc757-k9r-ack", strangerAck(own), minedAt = NEW + 4)
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact form", "self", "stranger's address"), groups.rows())
            assertEquals(
                "M1's form row (its key differs from S's row's key: it stays its own row)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("the key on S's own row", "another key", masked(groups.row(strangerMix))?.publicKey)
        }

    @Test
    fun `(k) round 9 3 - a contact S that stored M1's key through an accepted key exchange never takes M1's keyed row`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-k9c-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // S sent a first key exchange carrying M1's public key and the user accepted it: the key is stored for S, not
            // held for Accept, no claim mark. S is a contact and the one known holder of M1's form. No answer of anyone's
            // to a key exchange of ours paid to M1's form.
            f.prefs.e2ePeerKey[strangerMix] = m1Key
            f.book += strangerMix
            f.pass()

            assertEquals("fixture: S's key is not held for Accept", false, f.prefs.awaitingAccept.containsKey(strangerMix))
            assertEquals("fixture: S's key carries no claim mark", null, f.prefs.claims[strangerMix])
            assertEquals(
                "M1's form row (status, key and pending key fields stay on it; a stored key is vouching, never an answer)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("M1's key on a row under S's address", false, masked(groups.row(strangerMix))?.publicKey == "M1's key")
        }

    // ---- ZC-757 (k) round 10, 27 Oct (owner W-92, ZC-1145): an id restored for a form is not S's send id ------------
    // Premise of round 9: S (M1's Orchard receiver and S's own Sapling one) is a contact and the one known holder of M1's
    // Orchard form F; our restored mined key exchange X paid F. P1: restoreOwnConversationId also made X S's send id, so S
    // learns X from our next send and can answer X claiming S with S's own key. P2: M1's honest answer to X is refused.

    @Test
    fun `(k) round 10 P1-a - our restored key exchange paid only M1's form inside S's address - X never becomes S's send id`() =
        runBlocking {
            val (f, _) = restoredPhone()
            f.book += strangerMix
            val own = "ZKTA0757"
            f.ownKexRestored("zc757-kta-own", keyFor = peerForm, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()

            assertEquals(
                "the send id toward S is our key exchange's id (it paid only M1's form, which S's address merely holds)",
                false,
                f.ids.store.getConversationId(strangerMix) == own,
            )
        }

    @Test
    fun `(k) round 10 P1-i - S answers our key exchange paid to M1's form with S's row's key - M1's keyed row never merges into S's`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-kti-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)
            // S's own row under S's address, with S's key kS (the key S's answer carries).
            val sPair = E2EEncryption.generateKeyPair()
            val sRow = strangerOwnRow().copy(address = strangerMix, publicKey = sPair.publicKey)
            groups.add(sRow)
            assertEquals("fixture: the stored rows", listOf("contact form", "self", "stranger's address"), groups.rows())

            // S is a contact and the one known holder of M1's form; our restored key exchange X paid M1's form. S answers X,
            // claiming S's own address, with S's own key kS (never M1's key).
            f.book += strangerMix
            val own = "ZKTI0757"
            f.ownKexRestored("zc757-kti-own", keyFor = strangerMix, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.received("zc757-kti-ack", strangerAck(own, sPair), minedAt = NEW + 4)
            f.pass()

            assertEquals("the rebuilt group's member rows", listOf("contact form", "self", "stranger's address"), groups.rows())
            assertEquals(
                "M1's form row (status, M1's key and pending key fields stay on it; S's answer carried S's key, not M1's)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            assertEquals("S's own row (unchanged, none of M1's fields)", masked(sRow), masked(groups.row(strangerMix)))
        }

    @Test
    fun `(k) round 10 P1-ii - S with no row answers our key exchange paid to M1's form - M1's keyed row never moves to S`() =
        runBlocking {
            val (f, groups) = restoredPhone()
            f.sentRestored("zc757-ktj-invite", ownInvite(), listOf(feeForm, peerForm), recipient = feeForm, minedAt = NEW + 7)
            f.pass()
            val stored = advanced(peerForm)
            groups.replace(peerForm, stored)

            // As P1-i without a row of S's: S answers X claiming S with S's own key kS; then the user invites S.
            f.book += strangerMix
            val own = "ZKTJ0757"
            f.ownKexRestored("zc757-ktj-own", keyFor = strangerMix, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.received("zc757-ktj-ack", strangerAck(own), minedAt = NEW + 4)
            f.sentRestored("zc757-ktj-invite2", ownInvite(), listOf(feeForm, strangerMix), recipient = strangerMix, minedAt = NEW + 9)
            f.pass()

            assertEquals(
                "M1's form row (status, M1's key and pending key fields stay on it under M1's form)",
                masked(stored),
                masked(groups.row(peerForm)),
            )
            val sRow = masked(groups.row(strangerMix))
            assertEquals("M1's key on a row under S's address", false, sRow?.publicKey == "M1's key")
            assertEquals("M1's pending key txids on a row under S's address", false, sRow?.pendingKeyTxIds == stored.pendingKeyTxIds)
        }

    @Test
    fun `(k) round 10 P2 refused - M1's answer to our key exchange paid to M1's form, its id filed under S, is refused`() =
        runBlocking {
            // Round 12 (orchestrator decision 27 Oct, ZC-1155): an answer filed under one address (the restore's guess S)
            // that claims another address is refused, as in round 9: no key, no txid set, no id move, nothing paid.
            val root = RootMaterial()
            val (f, _) = restoredPhone(root)
            // As round 9 1: S a contact and the one known holder of M1's form, our restored key exchange paid M1's form and
            // its id is filed under S on restore; M1 answers it honestly, claiming M1's own address, with M1's key.
            f.book += strangerMix
            val own = "ZKTP0757"
            f.ownKexRestored("zc757-ktp-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            val before = snapshot(f, root)

            f.received("zc757-ktp-ack", answerClaiming(own, peer, m1Pair), minedAt = NEW + 4)
            f.pass()

            val checks = Checks()
            val after = snapshot(f, root)
            checks.equal(
                "M1's key stored for M1's address (the answer was filed under S but claims M1)",
                false,
                f.prefs.e2ePeerKey[peer] == m1Key,
            )
            checks.equal("M1's key stored for S's address", false, f.prefs.e2ePeerKey[strangerMix] == m1Key)
            checks.equal("keys stored or held for Accept, by address (a refused answer stores none)", before.keys, after.keys)
            checks.equal("KEX/KEXACK txid sets and scalars, by address (a refused answer changes none)", before.roots, after.roots)
            checks.equal("the address X is filed under after the refused answer", "stranger's address", label(f.ids.conv(own)))
            checks.equal("payments proposed after the refused answer (no key exchange answer, no ZBOOT)", before.proposals, after.proposals)
            checks.verify()
        }

    // ---- ZC-757 (k) round 11, 27 Oct (owner W-92, ZC-1150): the restored id stays off S after a restart -------------
    // Round 10 premise: S (M1's Orchard receiver F and S's own Sapling one) is a contact and the one known holder of F; our
    // restored mined key exchange X paid F, so the restore files X under S (conv:, own-id record) without making it S's send
    // id. F1: every new ViewModel (app restart, Chats -> Wallet -> Chats) repairs the id file and writes peer:S = X. F1 i:
    // S never learns X from us (the relay identity repair's ZBOOT to S does not carry it); with X known to S, S's answer
    // is the honest contact's case. Tests 3 and 4 let anyone answer under X directly: they do not depend on how X became
    // known. A built address T holds F and a receiver of nobody this phone knows (S can derive one): from this phone's
    // facts T's answer claiming T cannot be told apart from M1's honest answer claiming M1, so test 3 pins that nothing is
    // paid to T; since round 12 (ZC-1155) M1's answer, filed under S and claiming M1, is refused as well (round 9).

    @Test
    fun `(k) round 11 F1 - after a restart over the same stores our restored key exchange's id X is still not S's send id`() =
        runBlocking {
            val (f, _) = restoredPhone()
            f.book += strangerMix
            val own = "ZKU10757"
            f.ownKexRestored("zc757-ku1-own", keyFor = peerForm, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            assertEquals("fixture: before the restart X is not S's send id", false, f.ids.store.getConversationId(strangerMix) == own)

            // A second ChatViewModel on the same stores, as the app makes one on a restart or a return from the Wallet tab:
            // its init repairs the id file (validateAndRepairConvIdMappings), then the history pass runs again.
            secondViewModel(f)
            f.pass()

            assertEquals(
                "the send id toward S after the restart is X (X paid only M1's form, which S's address merely holds)",
                false,
                f.ids.store.getConversationId(strangerMix) == own,
            )
            assertEquals(
                "the id a first message to S would start under is X",
                false,
                f.ids.store.ownConversationIdForStart(strangerMix) == own,
            )
            val nextMessageId =
                f.ids.store
                    .getOrCreateConversationIdForMessage(strangerMix)
                    .first
            assertEquals("the id our next message to S would carry is X", false, nextMessageId == own)
        }

    @Test
    fun `(k) round 11 F1 i - after a restart the ZBOOT our app would send S to repair its relay identity does not carry X`() =
        runBlocking {
            // The protection against S answering X is that S never learns X: with X known to S its answer is the same
            // phone-side facts as the honest contact's (recheck nit A, recheck 2 F1/F2, recheck 3 F3/F4). This pins one
            // real send path to S beyond the id store's getters (F1): the relay identity repair's ZBOOT for S.
            val (f, _) = restoredPhone()
            f.book += strangerMix
            val own = "ZKU20757"
            f.ownKexRestored("zc757-ku2-own", keyFor = peerForm, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            // S is a contact this phone holds an E2E key pair of ours for, so a ZBOOT to S can be signed.
            val oursForS = E2EEncryption.generateKeyPair()
            f.prefs.ourKeyStore[strangerMix] = oursForS.publicKey to oursForS.privateKey

            // A second ChatViewModel on the same stores (restart, Chats -> Wallet -> Chats), its startup repair, a pass.
            secondViewModel(f)
            f.pass()
            PaymentHarness.setField(f.vm, "persistableWalletProvider", FixtureSeedWalletProvider)

            // Fixture: the builder makes a ZBOOT under an address's send id (a fresh contact with one of its own).
            val known = contact(seed = 7574)
            val oursForKnown = E2EEncryption.generateKeyPair()
            f.prefs.ourKeyStore[known] = oursForKnown.publicKey to oursForKnown.privateKey
            f.ids.store.setConversationId(known, "ZKUS0757")
            val knownMemo = ownZbootMemo(f.vm, known)
            val knownMemoId = knownMemo?.let { ZBootMessage.parse(it)?.convId }
            assertEquals("fixture: the ZBOOT builder carries a contact's send id", "ZKUS0757", knownMemoId)

            val memo = ownZbootMemo(f.vm, strangerMix)
            assertEquals(
                "the id the ZBOOT our app would send S carries is X (X paid only M1's form, which S's address merely holds)",
                false,
                memo != null && (ZBootMessage.parse(memo)?.convId == own || memo.contains(own)),
            )
        }

    @Test
    fun `(k) round 11 F1 ii iii refused - an answer to X claiming a built T pays nothing to T, M1's answer claiming M1 is refused too`() =
        runBlocking {
            // Round 12 (orchestrator decision 27 Oct, ZC-1155): both answers are filed under S (the restore's guess) and
            // claim another address, so both are refused (round 9): T's part as in round 11, M1's no longer completes.
            val root = RootMaterial()
            val (f, _) = restoredPhone(root)
            // S is a contact and the one known holder of M1's form F; our restored key exchange X (made for M1) paid F.
            f.book += strangerMix
            val own = "ZKU30757"
            f.ownKexRestored("zc757-ku3-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            val beforeAnswers = snapshot(f, root)

            // Someone who knows X answers it claiming T (F plus a receiver nobody here knows), with a key of its own.
            val theirs = E2EEncryption.generateKeyPair()
            f.received(
                "zc757-ku3-ack-t",
                answerClaiming(own, builtT, theirs),
                minedAt = NEW + 4,
            )
            f.pass()
            val toT = f.seam.proposalUris.count { it.contains(builtT) }

            // Then M1 answers X honestly, claiming M1's own address, with M1's key.
            f.received(
                "zc757-ku3-ack-m1",
                answerClaiming(own, peer, m1Pair),
                minedAt = NEW + 5,
            )
            f.pass()

            val checks = Checks()
            val afterAnswers = snapshot(f, root)
            checks.equal("payments proposed to T, an address the user never chose (after T's answer)", 0, toT)
            checks.equal(
                "payments proposed to T, an address the user never chose (after both answers)",
                0,
                f.seam.proposalUris.count { it.contains(builtT) },
            )
            checks.equal("a key stored for T", false, f.prefs.e2ePeerKey[builtT] != null)
            checks.equal(
                "M1's key stored for M1's address (M1's answer was filed under S but claims M1)",
                false,
                f.prefs.e2ePeerKey[peer] == m1Key,
            )
            checks.equal("M1's key stored for S's address", false, f.prefs.e2ePeerKey[strangerMix] == m1Key)
            checks.equal("keys stored or held for Accept, by address, across both refused answers", beforeAnswers.keys, afterAnswers.keys)
            checks.equal(
                "KEX/KEXACK txid sets and scalars, by address, across both refused answers",
                beforeAnswers.roots,
                afterAnswers.roots,
            )
            checks.equal("the address X is filed under after both refused answers", "stranger's address", label(f.ids.conv(own)))
            checks.equal(
                "payments proposed across both refused answers (no key exchange answer, no ZBOOT)",
                beforeAnswers.proposals,
                afterAnswers.proposals,
            )
            checks.verify()
        }

    @Test
    fun `(k) round 11 F2 - an answer claiming M1 that the judge then refuses leaves X filed where it was`() =
        runBlocking {
            val (f, _) = restoredPhone()
            f.book += strangerMix
            val own = "ZKU40757"
            f.ownKexRestored("zc757-ku4-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            val filedBefore = f.ids.conv(own)
            assertEquals("fixture: X is filed under S", "stranger's address", label(filedBefore))
            assertEquals("fixture: M1 has no send id", null, f.ids.peer(peer))

            // M1's address already holds another stored key, so an answer to X claiming M1 with M1's key is refused (neither
            // leg 1 nor leg 2: the held key differs; mined after the upgrade, so not leg 3).
            val other = E2EEncryption.generateKeyPair().publicKey
            f.prefs.e2ePeerKey[peer] = other
            f.received(
                "zc757-ku4-ack",
                answerClaiming(own, peer, m1Pair),
                minedAt = NEW + 4,
            )
            f.pass()

            assertEquals("fixture: the answer was refused (M1's held key unchanged)", true, f.prefs.e2ePeerKey[peer] == other)
            assertEquals("the address X is filed under after a refused answer", label(filedBefore), label(f.ids.conv(own)))
            assertEquals("X became M1's send id through a refused answer", false, f.ids.peer(peer) == own)
        }

    // ---- ZC-757 (k) round 12, 27 Oct (orchestrator decision, ZC-1155): an answer under a guessed holder is refused --------
    // An answer to our restored key exchange X, filed under the restore's guess S (whose address holds M1's form F), that
    // claims another address (M1 or a built T) is refused: no key for anyone, no KEX/KEXACK txid set changed for anyone,
    // no ZBOOT, conv:X unchanged (round-9 behaviour). The premise needs a deliberately built address; the round-11 take
    // broke M1's ratchet root (per-id readers follow conv:X = S). The nosendid marker (X never S's send id) stays.

    @Test
    fun `(k) round 12 - a refused answer to X claiming M1 never enters S's root material`() =
        runBlocking {
            val root = RootMaterial()
            val (f, _) = restoredPhone(root)
            f.book += strangerMix
            val own = "ZKV10757"
            f.ownKexRestored("zc757-kv1-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))

            // M1 answers X, claiming M1's own address: filed under S (conv:X = S), claiming another address, refused.
            val ack = "zc757-kv1-ack"
            f.received(ack, answerClaiming(own, peer, m1Pair), minedAt = NEW + 4)
            f.pass()

            val ackTxid = f.txid(ack)
            assertEquals(
                "M1's KEXACK txid in S's KEXACK txid set (the ratchet root S's chat derives from)",
                false,
                ackTxid in root.ack[strangerMix].orEmpty(),
            )
            assertEquals("M1's KEXACK txid as S's KEXACK scalar", false, root.ackScalar[strangerMix] == ackTxid)
            assertEquals("M1's KEXACK txid in S's KEX txid set", false, ackTxid in root.kex[strangerMix].orEmpty())
        }

    @Test
    fun `(k) round 12 - the startup repair writes nothing for an id carrying the nosendid marker`() =
        runBlocking {
            val (f, _) = restoredPhone()
            f.book += strangerMix
            val own = "ZKV20757"
            f.ownKexRestored("zc757-kv2-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            val marker = f.ids.file.getString("nosendid:$own", null)
            assertEquals("fixture: X carries the nosendid marker for S", "stranger's address", label(marker))
            val commitsBefore = f.ids.file.commits.size

            // A second ChatViewModel on the same stores: its init runs the id-file repair (no history pass after it).
            secondViewModel(f)

            val written =
                f.ids.file.commits
                    .drop(commitsBefore)
                    .flatten()
                    .filter { it.endsWith(own) || it == "peer:$strangerMix" }
                    .map { it.substringBefore(':') + ":" + if (it.endsWith(own)) "X" else "S" }
            assertEquals("id-file keys the startup repair wrote for the marked id X or for S", emptyList<String>(), written)
        }

    // ---- ZC-1152 (ZC-757 (k) rounds 11 and 12, 27 Oct): the guessed-holder answer pays no ZBOOT ---------------------
    // An answer under an id our restore filed by a guess (X under S, S the one known holder of M1's form F) that claims
    // another address is refused since round 12 (ZC-1155) and must pay nothing. Both tests run in a Tunnel chat with the
    // standing handshake consent, our relay identity derivable and no relay key of M1's known, so a KEXACK's ZBOOT goes
    // on-chain: the control shows the same answer, with X filed only for M1's form (no S), proposes that ZBOOT to M1; the
    // pin shows that with X filed under S by the guess no key is stored for M1 or S and nothing is proposed to anyone.

    @Test
    fun `(k) ZC-1152 control - M1's honest answer to X with no guessed holder proposes our paid ZBOOT to M1`() =
        runBlocking {
            val (f, _) = restoredPhone()
            zbootPaysOnChain(f)
            // No contact holds M1's form: X (made for M1, paid only F) is not filed under S by a guess.
            val own = "ZKU50757"
            f.ownKexRestored("zc757-ku5-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", false, f.ids.conv(own) == strangerMix)

            f.received("zc757-ku5-ack-m1", answerClaiming(own, peer, m1Pair), minedAt = NEW + 4)
            f.pass()

            assertEquals("fixture: M1's key stored for M1's address (the answer was taken)", true, f.prefs.e2ePeerKey[peer] == m1Key)
            assertEquals(
                "paid ZBOOT proposals to M1 after M1's answer taken without a guessed holder (the KEXACK's QUOTED_IN_SHIELDED ZBOOT)",
                true,
                zbootProposalsTo(f, peer) >= 1,
            )
        }

    @Test
    fun `(k) ZC-1152 - M1's answer to X filed under S by the restore's guess is refused and pays nothing`() =
        runBlocking {
            val (f, _) = restoredPhone()
            zbootPaysOnChain(f)
            // As the control, with S a contact and the one known holder of F: the restore files X under S by a guess.
            f.book += strangerMix
            val own = "ZKU60757"
            f.ownKexRestored("zc757-ku6-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            val keysBefore = f.prefs.e2ePeerKey.toMap()
            val heldBefore = f.prefs.awaitingAccept.toMap()
            val urisBefore = f.seam.proposalUris.size
            val requestsBefore = f.seam.proposalRequests.get()

            f.received("zc757-ku6-ack-m1", answerClaiming(own, peer, m1Pair), minedAt = NEW + 4)
            f.pass()

            val checks = Checks()
            checks.equal(
                "M1's key stored for M1's address (the answer was filed under S but claims M1)",
                false,
                f.prefs.e2ePeerKey[peer] == m1Key,
            )
            checks.equal("M1's key stored for S's address", false, f.prefs.e2ePeerKey[strangerMix] == m1Key)
            checks.equal("stored keys changed by the refused answer", true, f.prefs.e2ePeerKey.toMap() == keysBefore)
            checks.equal("keys held for Accept changed by the refused answer", true, f.prefs.awaitingAccept.toMap() == heldBefore)
            checks.equal("paid ZBOOT proposals to M1 for the refused answer", 0, zbootProposalsTo(f, peer))
            checks.equal("paid ZBOOT proposals to S for the refused answer", 0, zbootProposalsTo(f, strangerMix))
            checks.equal("payment URIs proposed to anyone after the refused answer", 0, f.seam.proposalUris.size - urisBefore)
            checks.equal("proposal requests after the refused answer", 0, f.seam.proposalRequests.get() - requestsBefore)
            checks.verify()
        }

    // ---- ZC-1166 (ZC-757 (k) round 14, 27 Oct): an id our own message files under S carries no marker ---------------
    // Round 13 (ZC-1164) refuses an answer filed under one address but claiming another only when its id carries the
    // nosendid marker. The restore of our own ordinary message (or ZBOOT) under X files X under the address its outputs
    // resolve to (restoreOwnConversationId(X, S), no marker): when the message paid only M1's form F inside S's address
    // (S a contact, the one known holder of F) and the pass reads it before our mined key exchange X (same block, a
    // tip-first scan, or unreadable key exchange outputs), conv:X = own:X = S unmarked and the key exchange branch, which
    // would write the marker, is skipped. An earlier test build (round 10) left the same unmarked id. M1's answer claiming
    // M1 is then leg 1 and taken under S. Wanted: refused as round 12 refuses it (no key stored or held for anyone, no
    // key-changed flag on S, M1's KEXACK txid in no root set of S, no proposal, conv:X unchanged). The honest case that
    // must stay taken (a chat this phone started with a receiver form, no restore guess) is
    // AnswerToAChatStartedWithAReceiverFormIsTakenTest.

    @Test
    fun `(k) round 14 1 - our own message under X paid to M1's form, read before our key exchange X - M1's answer is refused`() =
        runBlocking {
            val root = RootMaterial()
            val (f, _) = restoredPhone(root)
            val flagged = keyChangedFlags(f)
            zbootPaysOnChain(f)
            // S a contact and the one known holder of M1's form F. Our own ordinary message under X and our mined key
            // exchange X (made for M1) both paid F, in the same block; the pass reads the message first.
            f.book += strangerMix
            val own = "ZKW10757"
            f.sentRestored(
                "zc757-kw1-msg",
                ZMSGProtocol.createV4InitMessage(own, SELF, "synthetic first message"),
                listOf(feeForm, peerForm),
                recipient = null,
                minedAt = NEW + 3,
            )
            f.ownKexRestored("zc757-kw1-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S (by our own message)", "stranger's address", label(f.ids.conv(own)))
            assertEquals("fixture: X's own-id record names S", "stranger's address", label(f.ids.own(own)))
            // ZC-1167 (round 15, orchestrator decision 27 Oct): our own message paid only M1's form inside S's address, so
            // its restore files X under S with the nosendid marker, as the key exchange branch does.
            assertEquals(
                "X carries the nosendid marker for S after the pass (our own message paid only M1's form inside S's address)",
                "stranger's address",
                label(f.ids.file.getString("nosendid:$own", null)),
            )
            assertM1AnswerUnderUnmarkedXIsRefused(f, root, flagged, own, "zc757-kw1-ack")
        }

    @Test
    fun `(k) round 14 2 - X restored under S without the marker by an earlier build, M1's answer claiming M1 is refused`() =
        runBlocking {
            val root = RootMaterial()
            val (f, _) = restoredPhone(root)
            val flagged = keyChangedFlags(f)
            zbootPaysOnChain(f)
            // As a round 10 build left it: conv:X = own:X = S, no peer: entry, no nosendid marker. Our mined key exchange X
            // (made for M1) paid only M1's form F; S is a contact and the one known holder of F.
            f.book += strangerMix
            val own = "ZKW20757"
            check(
                f.ids.file
                    .edit()
                    .putString("conv:$own", strangerMix)
                    .putString("own:$own", strangerMix)
                    .commit(),
            ) { "fixture: the id file write failed" }
            f.ownKexRestored("zc757-kw2-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            assertEquals("fixture: X's own-id record names S", "stranger's address", label(f.ids.own(own)))
            assertEquals("fixture: X carries no nosendid marker", null, f.ids.file.getString("nosendid:$own", null))
            assertM1AnswerUnderUnmarkedXIsRefused(f, root, flagged, own, "zc757-kw2-ack")
        }

    // ---- ZC-1167, ZC-1168 (ZC-757 (k) round 15, 27 Oct): our own message's restore marks X; unreadable outputs wait ------
    // F1 (ZC-1167): the restore of our own ordinary message under X that paid only M1's form F inside S's address (S a
    // contact, the one known holder of F) calls the 2-argument restoreOwnConversationId, which makes X S's send id
    // (peer:S = X). S then learns X from our next send (getConversationId, ownConversationId, the relay identity ZBOOT),
    // and a chat the user starts with S pays S's full address under X: the round 14 rule (no paid text is the filed
    // address) stops refusing, and M1's answer, judged again every pass, is taken under S. With F1 fixed no new send goes
    // under X (S has no send id X), so test 2 pins the state a 57d800237 build leaves. F2 (ZC-1167): when our key exchange
    // X's outputs cannot be read, its paid texts are empty and M1's answer is taken that pass; it is never undone. F3
    // (ZC-1168, accepted limit): on a restored phone, an honest chat we started with a contact's smaller Unified Address
    // (in the contact book), whose output the restore names by its single receiver F, is filed under that address, and
    // the contact's answer claiming its full address is refused: nothing stored, nothing paid.

    @Test
    fun `(k) round 15 1 - our own message under X paid to M1's form - X is never S's send id, also after a restart`() =
        runBlocking {
            val (f, _) = restoredPhone()
            f.book += strangerMix
            val own = "ZKX10757"
            f.sentRestored(
                "zc757-kx1-msg",
                ZMSGProtocol.createV4InitMessage(own, SELF, "synthetic first message"),
                listOf(feeForm, peerForm),
                recipient = null,
                minedAt = NEW + 3,
            )
            f.ownKexRestored("zc757-kx1-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under S (by our own message)", "stranger's address", label(f.ids.conv(own)))
            val checks = Checks()
            checks.equal("after the pass: S's send id entry (peer:S) is X", false, f.ids.peer(strangerMix) == own)
            checks.equal("after the pass: getConversationId(S) is X", false, f.ids.store.getConversationId(strangerMix) == own)
            checks.equal("after the pass: ownConversationId(S) is X", false, f.ids.store.ownConversationId(strangerMix) == own)
            // S is a contact this phone holds an E2E key pair of ours for, so a ZBOOT to S can be signed.
            val oursForS = E2EEncryption.generateKeyPair()
            f.prefs.ourKeyStore[strangerMix] = oursForS.publicKey to oursForS.privateKey

            // A second ChatViewModel on the same stores (restart, Chats -> Wallet -> Chats), its startup repair, a pass.
            secondViewModel(f)
            f.pass()
            PaymentHarness.setField(f.vm, "persistableWalletProvider", FixtureSeedWalletProvider)

            // Fixture: the builder makes a ZBOOT under an address's send id (a fresh contact with one of its own).
            val known = contact(seed = 7575)
            val oursForKnown = E2EEncryption.generateKeyPair()
            f.prefs.ourKeyStore[known] = oursForKnown.publicKey to oursForKnown.privateKey
            f.ids.store.setConversationId(known, "ZKXS0757")
            val knownMemoId = ownZbootMemo(f.vm, known)?.let { ZBootMessage.parse(it)?.convId }
            assertEquals("fixture: the ZBOOT builder carries a contact's send id", "ZKXS0757", knownMemoId)

            checks.equal("after a restart: S's send id entry (peer:S) is X", false, f.ids.peer(strangerMix) == own)
            checks.equal("after a restart: getConversationId(S) is X", false, f.ids.store.getConversationId(strangerMix) == own)
            checks.equal("after a restart: ownConversationId(S) is X", false, f.ids.store.ownConversationId(strangerMix) == own)
            val memo = ownZbootMemo(f.vm, strangerMix)
            checks.equal(
                "after a restart: the ZBOOT our app would send S carries X",
                false,
                memo != null && (ZBootMessage.parse(memo)?.convId == own || memo.contains(own)),
            )
            checks.verify()
        }

    @Test
    fun `(k) round 15 2 - as an earlier build left it, our key exchange to S under X paid S's address - M1's answer is refused`() =
        runBlocking {
            val root = RootMaterial()
            val (f, _) = restoredPhone(root)
            val flagged = keyChangedFlags(f)
            zbootPaysOnChain(f)
            // As 57d800237 leaves it after our own message under X paid M1's form F inside S's address: conv:X = own:X = S
            // and peer:S = X, no nosendid marker. Our mined key exchange X (made for M1) paid only F; then the user started
            // a chat with S and that build sent our key exchange to S under X, paid to S's full address.
            f.book += strangerMix
            val own = "ZKX20757"
            check(
                f.ids.file
                    .edit()
                    .putString("conv:$own", strangerMix)
                    .putString("own:$own", strangerMix)
                    .putString("peer:$strangerMix", own)
                    .commit(),
            ) { "fixture: the id file write failed" }
            f.ownKexRestored("zc757-kx2-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.ownKex("zc757-kx2-own-s", to = strangerMix, convId = own, minedAt = NEW + 5)
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            assertEquals("fixture: X's own-id record names S", "stranger's address", label(f.ids.own(own)))
            assertEquals("fixture: X is S's send id (as the earlier build left it)", true, f.ids.peer(strangerMix) == own)
            assertM1AnswerUnderUnmarkedXIsRefused(f, root, flagged, own, "zc757-kx2-ack", minedAt = NEW + 6)
        }

    @Test
    fun `(k) round 15 3 - our key exchange X's outputs unreadable - M1's answer under X filed under S waits, then is refused`() =
        runBlocking {
            val root = RootMaterial()
            val (f, _) = restoredPhone(root)
            val flagged = keyChangedFlags(f)
            zbootPaysOnChain(f)
            // X filed under S unmarked (conv:X = own:X = S, as round 14 2), S a contact and the one known holder of M1's
            // form F. Our mined key exchange X (made for M1) paid F, but its outputs cannot be read this pass.
            f.book += strangerMix
            val own = "ZKX30757"
            check(
                f.ids.file
                    .edit()
                    .putString("conv:$own", strangerMix)
                    .putString("own:$own", strangerMix)
                    .commit(),
            ) { "fixture: the id file write failed" }
            f.ownKexRestored("zc757-kx3-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            f.unreadableOutputs += "zc757-kx3-own"
            f.pass()
            assertEquals("fixture: X is filed under S", "stranger's address", label(f.ids.conv(own)))
            val base = RefusalBaseline(snapshot(f, root), flagged.map(::label).sorted(), f.seam.proposalUris.size)

            // M1 answers X claiming M1, while our key exchange X's outputs still cannot be read.
            val ack = "zc757-kx3-ack"
            f.received(ack, answerClaiming(own, peer, m1Pair), minedAt = NEW + 4)
            f.pass()
            val checks = Checks()
            checkNothingTaken(checks, "outputs unreadable:", f, root, flagged, base, own, ack, filed = strangerMix, claimed = peer)

            // A later pass reads the outputs (F only, inside S's address): the answer is refused.
            f.unreadableOutputs.clear()
            f.pass()
            checkNothingTaken(checks, "outputs read later:", f, root, flagged, base, own, ack, filed = strangerMix, claimed = peer)
            checks.verify()
        }

    @Test
    fun `(k) round 15 4 ZC-1168 limit - restored chat with a smaller address of a contact - its answer claiming the full one is refused`() =
        runBlocking {
            // Accepted limit (ZC-1168): the restore names our key exchange by its single receiver F; the contact book holds
            // the contact's smaller Unified Address (Sapling and Orchard), so X is filed under it by the restore. The
            // contact answers claiming its full address (with a transparent receiver too): refused, nothing stored or paid.
            val root = RootMaterial()
            val (f, _) = restoredPhone(root)
            val flagged = keyChangedFlags(f)
            zbootPaysOnChain(f)
            val smallForm = receiverForm(smallerUa, typecode = 3)
            f.book += smallerUa
            val own = "ZKX40757"
            f.ownKexRestored("zc757-kx4-own", keyFor = smallerUa, outputs = listOf(feeForm, smallForm), convId = own, minedAt = NEW + 3)
            f.pass()
            assertEquals("fixture: X is filed under the smaller address", "smaller address", label(f.ids.conv(own)))
            assertEquals("fixture: F sits within the full address", true, ZcashReceivers.within(smallForm, fullUa))
            val base = RefusalBaseline(snapshot(f, root), flagged.map(::label).sorted(), f.seam.proposalUris.size)

            val ack = "zc757-kx4-ack"
            f.received(ack, answerClaiming(own, fullUa, m1Pair), minedAt = NEW + 4)
            f.pass()

            val checks = Checks()
            checkNothingTaken(checks, "", f, root, flagged, base, own, ack, filed = smallerUa, claimed = fullUa)
            checks.equal("payment URIs proposed to the full address", 0, f.seam.proposalUris.count { it.contains(fullUa) })
            checks.equal("payment URIs proposed to the smaller address", 0, f.seam.proposalUris.count { it.contains(smallerUa) })
            checks.verify()
        }

    // ---- ZC-1167 follow-up (ZC-757 (k) round 15b, orchestrator decision 27 Oct, owner W-95) -----------------------------
    // Round 15's fix marks X only when outgoingRecipientFromOutputs(tx) shows the paid text is not the filed address; when
    // the own message's outputs cannot be read (its chat found by a pending send matched by content) the restore still
    // makes X S's send id. Wanted: a restore that cannot prove the paid text is the filed address files X with the
    // nosendid marker (a restored chat may get a fresh chat id, W-95). Guard: an honest own message whose readable output
    // is S's full address keeps X as S's send id.

    @Test
    fun `(k) round 15b 1 - our own message under X with unreadable outputs, found by a pending send - X is never S's send id`() =
        runBlocking {
            val (f, _) = restoredPhone()
            f.book += strangerMix
            val own = "ZKY10757"
            val text = "synthetic first message 15b"
            f.sentRestored(
                "zc757-ky1-msg",
                ZMSGProtocol.createV4InitMessage(own, SELF, text),
                listOf(feeForm, peerForm),
                recipient = null,
                minedAt = NEW + 3,
            )
            f.unreadableOutputs += "zc757-ky1-msg"
            f.ownKexRestored("zc757-ky1-own", keyFor = peer, outputs = listOf(feeForm, peerForm), convId = own, minedAt = NEW + 3)
            // The one pending send with the same text, listed under S (the pass's strategy 4 match by content).
            val pending =
                ChatMessage(
                    id = "pending-zc757-ky1",
                    txId = null,
                    text = text,
                    timestamp = Instant.ofEpochSecond(1_700_000_000L),
                    isOutgoing = true,
                    peerAddress = strangerMix,
                    isPending = true,
                )
            PaymentHarness.setField(f.vm, "pendingMessages", MutableStateFlow(listOf(pending)))
            f.pass()
            assertEquals("fixture: X is filed under S (by our own message)", "stranger's address", label(f.ids.conv(own)))
            val checks = Checks()
            checks.equal("after the pass: S's send id entry (peer:S) is X", false, f.ids.peer(strangerMix) == own)
            checks.equal("after the pass: getConversationId(S) is X", false, f.ids.store.getConversationId(strangerMix) == own)
            checks.equal("after the pass: ownConversationId(S) is X", false, f.ids.store.ownConversationId(strangerMix) == own)
            val oursForS = E2EEncryption.generateKeyPair()
            f.prefs.ourKeyStore[strangerMix] = oursForS.publicKey to oursForS.privateKey

            // A second ChatViewModel on the same stores (restart, Chats -> Wallet -> Chats), its startup repair, a pass.
            secondViewModel(f)
            f.pass()
            PaymentHarness.setField(f.vm, "persistableWalletProvider", FixtureSeedWalletProvider)

            // Fixture: the builder makes a ZBOOT under an address's send id (a fresh contact with one of its own).
            val known = contact(seed = 7577)
            val oursForKnown = E2EEncryption.generateKeyPair()
            f.prefs.ourKeyStore[known] = oursForKnown.publicKey to oursForKnown.privateKey
            f.ids.store.setConversationId(known, "ZKYS0757")
            val knownMemoId = ownZbootMemo(f.vm, known)?.let { ZBootMessage.parse(it)?.convId }
            assertEquals("fixture: the ZBOOT builder carries a contact's send id", "ZKYS0757", knownMemoId)

            checks.equal("after a restart: S's send id entry (peer:S) is X", false, f.ids.peer(strangerMix) == own)
            checks.equal("after a restart: getConversationId(S) is X", false, f.ids.store.getConversationId(strangerMix) == own)
            checks.equal("after a restart: ownConversationId(S) is X", false, f.ids.store.ownConversationId(strangerMix) == own)
            val memo = ownZbootMemo(f.vm, strangerMix)
            checks.equal(
                "after a restart: the ZBOOT our app would send S carries X",
                false,
                memo != null && (ZBootMessage.parse(memo)?.convId == own || memo.contains(own)),
            )
            checks.verify()
        }

    @Test
    fun `(k) round 15b 2 guard - our own message under X paid to S's full address, outputs readable - X stays S's send id`() =
        runBlocking {
            val (f, _) = restoredPhone()
            f.book += strangerMix
            val own = "ZKY20757"
            f.sentRestored(
                "zc757-ky2-msg",
                ZMSGProtocol.createV4InitMessage(own, SELF, "synthetic first message 15b guard"),
                listOf(feeForm, strangerMix),
                recipient = null,
                minedAt = NEW + 3,
            )
            f.pass()
            val checks = Checks()
            checks.equal("after the pass: X is filed under", "stranger's address", label(f.ids.conv(own)))
            checks.equal("after the pass: X's nosendid marker", null, f.ids.file.getString("nosendid:$own", null))
            checks.equal("after the pass: S's send id entry (peer:S) is X", true, f.ids.peer(strangerMix) == own)
            checks.equal("after the pass: getConversationId(S) is X", true, f.ids.store.getConversationId(strangerMix) == own)

            secondViewModel(f)
            f.pass()
            checks.equal("after a restart: getConversationId(S) is X", true, f.ids.store.getConversationId(strangerMix) == own)
            checks.verify()
        }

    /** ZC-1166: M1 answers [own] (filed under S, unmarked) claiming M1, labelled [ack]; the answer must change nothing. */
    private suspend fun assertM1AnswerUnderUnmarkedXIsRefused(
        f: HandshakePassFixture,
        root: RootMaterial,
        flagged: Set<String>,
        own: String,
        ack: String,
        minedAt: Long = NEW + 4,
    ) {
        val state = RefusalBaseline(snapshot(f, root), flagged.map(::label).sorted(), f.seam.proposalUris.size)

        f.received(ack, answerClaiming(own, peer, m1Pair), minedAt = minedAt)
        f.pass()

        val checks = Checks()
        checkNothingTaken(checks, "", f, root, flagged, state, own, ack, filed = strangerMix, claimed = peer)
        checks.verify()
    }

    /** What an answer that must be refused is compared with: the snapshot, the flagged labels and the URI count before it. */
    private class RefusalBaseline(
        val snapshot: Snapshot,
        val flagged: List<String>,
        val uris: Int,
    )

    /**
     * ZC-1166, ZC-1167: adds to [checks] (each message led by [phase]) that the answer labelled [ack] under [own], filed
     * under [filed] and claiming [claimed] with [m1Pair]'s key, changed nothing since [base].
     */
    private fun checkNothingTaken(
        checks: Checks,
        phase: String,
        f: HandshakePassFixture,
        root: RootMaterial,
        flagged: Set<String>,
        base: RefusalBaseline,
        own: String,
        ack: String,
        filed: String,
        claimed: String,
    ) {
        val ackTxid = f.txid(ack)
        val after = snapshot(f, root)
        val f1 = label(filed)
        val c1 = label(claimed)
        checks.equal("$phase the answer's key stored for the claimed address ($c1)", false, f.prefs.e2ePeerKey[claimed] == m1Key)
        checks.equal("$phase the answer's key stored for the filed address ($f1)", false, f.prefs.e2ePeerKey[filed] == m1Key)
        checks.equal("$phase the answer's key held for Accept at the filed address", false, f.prefs.awaitingAccept[filed] == m1Key)
        checks.equal("$phase the answer's key held for Accept at the claimed address", false, f.prefs.awaitingAccept[claimed] == m1Key)
        checks.equal("$phase keys stored or held for Accept, by address (a refused answer stores none)", base.snapshot.keys, after.keys)
        checks.equal("$phase addresses flagged key-changed (a refused answer flags none)", base.flagged, flagged.map(::label).sorted())
        checks.equal("$phase the answer's txid in the filed address's KEXACK txid set", false, ackTxid in root.ack[filed].orEmpty())
        checks.equal("$phase the answer's txid as the filed address's KEXACK scalar", false, root.ackScalar[filed] == ackTxid)
        checks.equal("$phase the answer's txid in the filed address's KEX txid set", false, ackTxid in root.kex[filed].orEmpty())
        checks.equal(
            "$phase KEX/KEXACK txid sets and scalars, by address (a refused answer changes none)",
            base.snapshot.roots,
            after.roots,
        )
        checks.equal("$phase the address X is filed under after the refused answer", f1, label(f.ids.conv(own)))
        checks.equal("$phase proposal requests after the refused answer", base.snapshot.proposals, after.proposals)
        checks.equal("$phase payment URIs proposed to anyone after the refused answer", 0, f.seam.proposalUris.size - base.uris)
    }

    /** ZC-1166: the addresses the pass flags key-changed (setE2EKeyChanged true; false clears), read back by isE2EKeyChanged. */
    private fun keyChangedFlags(f: HandshakePassFixture): Set<String> {
        val flagged = ConcurrentHashMap.newKeySet<String>()
        val base = f.extra
        f.extra = { method, a ->
            when (method.name) {
                "setE2EKeyChanged" -> {
                    if (a[1] == true) flagged += a[0] as String else flagged -= a[0] as String
                    null
                }
                "isE2EKeyChanged" -> (a[0] as String) in flagged
                else -> base?.invoke(method, a) ?: ClaimMarkMaps.UNHANDLED
            }
        }
        return flagged
    }

    /**
     * ZC-1152: lets a KEXACK's QUOTED_IN_SHIELDED ZBOOT reach a proposal in [f]: every chat in Tunnel mode (the standing
     * consent of the fixture decides, no quote needed), the delivered-identity marker kept in a map that saves its writes,
     * no relay key known (so nothing goes over the relays) and our relay identity derived from the fixture seed.
     */
    private fun zbootPaysOnChain(f: HandshakePassFixture) {
        f.prefs.mode = ConversationMode.TUNNEL
        val sentBoot = ConcurrentHashMap<String, String>()
        val base = f.extra
        f.extra = { method, a ->
            when (method.name) {
                "getSentNostrBootPubkey" -> sentBoot[a[0] as String]
                "setSentNostrBootPubkey" -> {
                    if (a[1] == null) sentBoot.remove(a[0] as String) else sentBoot[a[0] as String] = a[1] as String
                    true
                }
                else -> base?.invoke(method, a) ?: ClaimMarkMaps.UNHANDLED
            }
        }
        PaymentHarness.setField(f.vm, "persistableWalletProvider", FixtureSeedWalletProvider)
    }

    /** ZC-1152: how many proposals in [f] pay [address] a memo that is a ZBOOT. */
    private fun zbootProposalsTo(
        f: HandshakePassFixture,
        address: String,
    ): Int =
        f.seam.proposalUris.count { uri ->
            uri.contains(address) &&
                Regex("""memo(?:\.\d+)?=([A-Za-z0-9_%-]+)""").findAll(uri).any { match ->
                    val encoded = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                    val memo =
                        runCatching {
                            String(
                                java.util.Base64
                                    .getUrlDecoder()
                                    .decode(encoded.trimEnd('=')),
                            )
                        }.getOrNull()
                    memo != null && ZBootMessage.parse(memo) != null
                }
        }

    /**
     * A second ChatViewModel on [f]'s stores (the same id file, prefs and group rows), as the app makes one on a restart or
     * a return from the Wallet tab, with its init's id-file repair run to its end (ChatViewModel init:
     * validateAndRepairConvIdMappings; the constructor-free ViewModel runs it by reflection).
     */
    private suspend fun secondViewModel(f: HandshakePassFixture) {
        f.open()
        wireUnsetFields(f.vm)
        ChatViewModel::class.java
            .getDeclaredField("repairComplete")
            .apply { isAccessible = true }
            .set(f.vm, kotlinx.coroutines.CompletableDeferred<Unit>())
        ChatViewModel::class.java
            .getDeclaredMethod("validateAndRepairConvIdMappings")
            .apply { isAccessible = true }
            .invoke(f.vm)
        f.idle()
    }

    /** ChatViewModel.ownZbootMemo([address]): the signed ZBOOT the relay identity repair hands the relays for [address]. */
    private suspend fun ownZbootMemo(
        vm: ChatViewModel,
        address: String,
    ): String? {
        val method =
            ChatViewModel::class.java
                .getDeclaredMethod("ownZbootMemo", String::class.java, kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }
        return kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try {
                method.invoke(vm, address, continuation)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as String?
    }

    /** An answer under [convId] to our key exchange, claiming [claimed], with the key [pair]. */
    private fun answerClaiming(
        convId: String,
        claimed: String,
        pair: E2EKeyPair,
    ): String {
        val payload = E2EEncryption.createKEXAckPayload(claimed, pair.publicKey, pair.privateKey)
        return ZMSGProtocol.createV4KEXAckMessage(convId, claimed, payload)
    }

    /** M1's answer, with M1's key [pair] (the current one by default), to our restored key exchange under [convId] that paid [form]. */
    private fun m1AnsweredOwnKex(
        f: HandshakePassFixture,
        label: String,
        form: String,
        convId: String,
        minedAt: Long,
        pair: E2EKeyPair = m1Pair,
    ) {
        f.ownKexRestored("$label-own", keyFor = peer, outputs = listOf(feeForm, form), convId = convId, minedAt = minedAt)
        f.received(
            "$label-ack",
            ZMSGProtocol.createV4KEXAckMessage(convId, peer, E2EEncryption.createKEXAckPayload(peer, pair.publicKey, pair.privateKey)),
            minedAt = minedAt + 1,
        )
    }

    /** S's answer under [convId] to our key exchange, with a key of S's own ([theirs]; a new one by default). */
    private fun strangerAck(
        convId: String,
        theirs: E2EKeyPair = E2EEncryption.generateKeyPair(),
    ): String =
        ZMSGProtocol.createV4KEXAckMessage(
            convId,
            strangerMix,
            E2EEncryption.createKEXAckPayload(strangerMix, theirs.publicKey, theirs.privateKey),
        )

    /** M1's form row is [stored] unchanged, and S has its own row under S's address with none of M1's fields. */
    private fun assertM1RowStaysAndStrangerHasItsOwn(
        groups: Groups,
        stored: GroupMember,
    ) {
        assertEquals("the rebuilt group's member rows", listOf("contact form", "self", "stranger's address"), groups.rows())
        assertEquals(
            "M1's form row (status, key and pending key fields stay on it)",
            masked(stored),
            masked(groups.row(peerForm)),
        )
        val own = masked(groups.row(strangerMix))
        assertEquals("S's row: key", null, own?.publicKey)
        assertEquals("S's row: pending key epoch", null, own?.pendingKeyEpoch)
        assertEquals("S's row: pending key txids", null, own?.pendingKeyTxIds)
    }

    /** S's own row under S's own Sapling form, with S's key (not M1's). */
    private fun strangerOwnRow(): GroupMember =
        GroupMember(
            address = strangerOwnForm,
            publicKey = E2EEncryption.generateKeyPair().publicKey,
            joinedAt = Instant.ofEpochMilli(1_760_000_100_000L),
            status = MemberStatus.ACTIVE,
            inviteStatus = InviteStatus.SENT,
        )

    // ---- fixture ----------------------------------------------------------------------------------------------------

    /** The group rows this phone stores (info and members), kept between passes. */
    private inner class Groups {
        val info = ConcurrentHashMap<String, String>()
        val members = ConcurrentHashMap<String, String>()

        fun stored(): List<GroupMember> =
            members[MA3.GID]?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }.orEmpty()

        fun row(address: String): GroupMember? = stored().singleOrNull { it.address == address }

        /** Writes [row] in place of the stored row of [address] (as an earlier build left it). */
        fun replace(
            address: String,
            row: GroupMember,
        ) {
            val rows = stored()
            check(rows.count { it.address == address } == 1) { "fixture: no single stored row of the address" }
            members[MA3.GID] = ZMSGGroupProtocol.serializeGroupMembers(rows.map { if (it.address == address) row else it })
        }

        fun add(row: GroupMember) {
            members[MA3.GID] = ZMSGGroupProtocol.serializeGroupMembers(stored() + row)
        }

        fun rows(): List<String> =
            members[MA3.GID]
                ?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) }
                .orEmpty()
                .map { label(it.address) }
                .sorted()
    }

    /**
     * Round 12: the root material this phone stores per address (ChatViewModel's ratchet root reads the KEX and KEXACK txid
     * sets and their legacy scalars, getKexTxIds/getE2EKexTxId and getKexAckTxIds/getE2EKexAckTxId). Only a test that
     * passes one to [restoredPhone] stores them; elsewhere they read empty, as before.
     */
    private class RootMaterial {
        val kex = ConcurrentHashMap<String, Set<String>>()
        val ack = ConcurrentHashMap<String, Set<String>>()
        val kexScalar = ConcurrentHashMap<String, String>()
        val ackScalar = ConcurrentHashMap<String, String>()

        @Suppress("UNCHECKED_CAST")
        fun answer(
            method: String,
            a: Array<Any?>,
        ): Any? =
            when (method) {
                "getKexTxIds" -> kex[a[0] as String].orEmpty()
                "setKexTxIds" -> kex.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                "getKexAckTxIds" -> ack[a[0] as String].orEmpty()
                "setKexAckTxIds" -> ack.put(a[0] as String, (a[1] as Set<String>).toSet()).let { null }
                "getE2EKexTxId" -> kexScalar[a[0] as String]
                "setE2EKexTxId" -> kexScalar.put(a[0] as String, a[1] as String).let { null }
                "getE2EKexAckTxId" -> ackScalar[a[0] as String]
                "setE2EKexAckTxId" -> ackScalar.put(a[0] as String, a[1] as String).let { null }
                else -> ClaimMarkMaps.UNHANDLED
            }
    }

    /** The labels of the key exchange transactions the round 12 snapshots name. */
    private val txLabels =
        listOf("zc757-ktp-own", "zc757-ktp-ack", "zc757-ku3-own", "zc757-ku3-ack-t", "zc757-ku3-ack-m1")

    /** What a refused answer must leave as it was, by label (no address, key or txid in a failure message). */
    private data class Snapshot(
        val keys: Map<String, String>,
        val roots: Map<String, String>,
        val proposals: Int,
    )

    private fun snapshot(
        f: HandshakePassFixture,
        root: RootMaterial,
    ): Snapshot {
        fun keyName(key: String) = if (key == m1Key) "M1's key" else "another key"

        // Every txid here is a synthetic one of a transaction the test labelled: named by its label.
        fun txName(txid: String): String = txLabels.firstOrNull { f.txid(it) == txid } ?: "other txid"

        fun names(txids: Set<String>?): List<String> = txids.orEmpty().map(::txName).sorted()

        val stored = f.prefs.e2ePeerKey.entries
        val held = f.prefs.awaitingAccept.entries
        val keys =
            stored.associate { "stored at ${label(it.key)}" to keyName(it.value) } +
                held.associate { "held for Accept at ${label(it.key)}" to keyName(it.value) }
        val addresses = (root.kex.keys + root.ack.keys + root.kexScalar.keys + root.ackScalar.keys).toSortedSet()
        val roots =
            addresses.associate { address ->
                val kexScalar = root.kexScalar[address]?.let(::txName)
                val ackScalar = root.ackScalar[address]?.let(::txName)
                label(address) to
                    "kex=${names(root.kex[address])} ack=${names(root.ack[address])} kexScalar=$kexScalar ackScalar=$ackScalar"
            }
        return Snapshot(keys, roots, f.seam.proposalRequests.get())
    }

    /** Collects every failed check of a test, so one failure report names all of them. */
    private class Checks {
        private val failed = mutableListOf<String>()

        fun <T> equal(
            what: String,
            expected: T,
            actual: T,
        ) {
            if (expected != actual) failed += "$what: expected <$expected> but was <$actual>"
        }

        fun verify() = assertEquals("failed checks", emptyList<String>(), failed)
    }

    private suspend fun restoredPhone(root: RootMaterial? = null): Pair<HandshakePassFixture, Groups> {
        val groups = Groups()
        val f = HandshakePassFixture(scheduler, peer)
        f.extra = { method, a ->
            when (method.name) {
                "saveGroupInfo" -> {
                    groups.info[a[0] as String] = a[1] as String
                    null
                }
                "getGroupInfo" -> groups.info[a[0] as String]
                "getAllGroupIds" -> groups.info.keys.toSet()
                "saveGroupMembers" -> {
                    groups.members[a[0] as String] = a[1] as String
                    null
                }
                "getGroupMembers" -> groups.members[a[0] as String]
                else -> if (root != null) root.answer(method.name, a) else ClaimMarkMaps.UNHANDLED
            }
        }
        f.open()
        wireUnsetFields(f.vm)
        return f to groups
    }

    /** M1's row as a 2.13 build before this fix stored it under [address]: every optional field set. */
    private fun advanced(address: String): GroupMember =
        GroupMember(
            address = address,
            publicKey = m1Key,
            joinedAt = Instant.ofEpochMilli(1_760_000_000_000L),
            status = MemberStatus.ACTIVE,
            isAdmin = true,
            nickname = null,
            inviteStatus = InviteStatus.SENT,
            pendingKeyEpoch = 2,
            pendingKeyTxIds = listOf("synthetic-key-send-0757"),
        )

    /** [row] with its address as a label and its key as a name (failure messages print no address or key). */
    private fun masked(row: GroupMember?): GroupMember? =
        row?.copy(address = label(row.address), publicKey = row.publicKey?.let { if (it == m1Key) "M1's key" else "another key" })

    /** Counts the pass's reads of its known peers (ZC-1082's readKnownPeers: one contact-book read each). */
    private fun countKnownPeerReads(
        vm: ChatViewModel,
        reads: AtomicInteger,
    ) {
        val field = ChatViewModel::class.java.getDeclaredField("contactBook").apply { isAccessible = true }
        val inner = field.get(vm) as ContactBook
        val counting =
            Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { _, method, args ->
                if (method.name == "getAllContacts" && Thread.currentThread().stackTrace.any { it.methodName == "readKnownPeers" }) {
                    reads.incrementAndGet()
                }
                try {
                    method.invoke(inner, *(args ?: emptyArray()))
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException
                }
            } as ContactBook
        field.set(vm, counting)
    }

    private fun ownInvite(): String =
        ZMSGGroupProtocol.createGroupInviteCompact(
            MA3.GID,
            "Restored group",
            SELF,
            0,
            "c3ludGhldGljLWdyb3VwLWtleS0wNzU3LTAwMDAwMDA=",
            isSessionEncrypted = false,
        )

    /** As RestoredOwnSendIsFiledUnderItsContactTest: the constructor-free ViewModel's unset fields get empty ones. */
    private fun wireUnsetFields(vm: ChatViewModel) {
        for (field in ChatViewModel::class.java.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            if (field.get(vm) != null) continue
            val type = field.type
            val value: Any? =
                when {
                    type.isAssignableFrom(ConcurrentHashMap::class.java) -> ConcurrentHashMap<Any, Any>()
                    type.isAssignableFrom(java.util.LinkedHashSet::class.java) -> Collections.synchronizedSet(LinkedHashSet<Any>())
                    type.isAssignableFrom(java.util.ArrayList::class.java) -> Collections.synchronizedList(ArrayList<Any>())
                    type == kotlinx.coroutines.sync.Mutex::class.java -> kotlinx.coroutines.sync.Mutex()
                    type == AtomicBoolean::class.java -> AtomicBoolean()
                    type == AtomicLong::class.java -> AtomicLong()
                    type == AtomicInteger::class.java -> AtomicInteger()
                    type == Any::class.java -> Any()
                    else -> null
                }
            if (value != null) field.set(vm, value)
        }
    }

    /** [address]'s receivers plus a transparent (P2PKH) receiver random in [seed], as one Unified Address. */
    private fun withTransparent(
        address: String,
        seed: Int,
    ): String {
        val shielded =
            ZcashReceivers
                .of(address)!!
                .sortedBy { it.typecode }
                .map { r -> r.typecode to ByteArray(r.hex.length / 2) { r.hex.substring(2 * it, 2 * it + 2).hexToByte() } }
        return ZcashReceivers.unifiedAddress("u", listOf(0 to kotlin.random.Random(seed).nextBytes(20)) + shielded)!!
    }

    private fun String.hexToByte(): Byte = toInt(16).toByte()

    private fun label(address: String?): String =
        when (address) {
            null -> "none"
            peer -> "contact"
            peerForm -> "contact form"
            peerSaplingForm -> "contact sapling form"
            strangerMix -> "stranger's address"
            strangerOwnForm -> "stranger's own form"
            builtT -> "built address T"
            fullUa -> "full address"
            smallerUa -> "smaller address"
            SELF -> "self"
            CURRENT_FEE -> "current fee address"
            feeForm -> "current fee form"
            else -> "other (${address.length} chars)"
        }
}
