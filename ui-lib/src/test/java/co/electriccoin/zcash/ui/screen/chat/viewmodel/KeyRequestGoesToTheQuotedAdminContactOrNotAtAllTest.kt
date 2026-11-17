package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.CREATOR_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.T
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y2
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y2_NOSTR
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y_NOSTR
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R3 and R4, section 6 Money; section 4.1 class 7) — "Tap to sync group keys"
 * on a member's phone: the key request goes to the admin's contact, to the address the price question quoted, signed,
 * or it does not go at all.
 *
 * The phone holds the admin's chat (and key) under Y, while the group's stored creator address is X_OLD, which holds
 * no key and no relay route. At `00f1a349b` the request is addressed to X_OLD: the phone asks a price for one on-chain
 * message and, after Pay, sends an unsigned request there for about 17,000 zat. The admin's phone ignores an unsigned
 * on-chain request, and in a group whose admin the phone cannot confirm the answer would be refused anyway.
 *
 * Wanted:
 * - (a) R4a: while the admin is not confirmed, no price question, nothing paid, nothing published;
 * - (b), (c) R3: in a group with a creator key, the request goes to the contact that holds it, signed with our key
 *   for that contact: free over the relays, else quoted as one on-chain message and paid once, to that contact;
 * - (d) R4b: the send takes its address from the quote; when that address is no longer the admin's contact nothing
 *   is sent, free or paid, and the new plan is quoted;
 * - (e) R4c: never on-chain when the admin's ZChat is known to be older than 2.13; the free relay copy only;
 * - (f) R4d: never on-chain unsigned;
 * - (g) control: a healthy group (the admin's chat is at the creator address) asks and pays as today.
 *
 * Each case taps as the user would: when a price question is raised it is read, then Pay is tapped, so that the
 * case sees what the phone would have sent. Every proposal is counted on the SDK seam; no ZEC moves. All addresses,
 * keys and ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyRequestGoesToTheQuotedAdminContactOrNotAtAllTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private companion object {
        const val NO_QUESTION = "no price question"
        const val NOTHING_SENT = "nothing sent"

        fun question(messages: Int) = "a price question for $messages on-chain message(s), then Pay"
    }

    /** The group screen of [p], which already asked the admin for the member list once (so opening it sends nothing). */
    private inner class Screen(
        val p: AdminCheckPhone,
    ) {
        val f = GroupPaidOnceFixture().also { it.scheduler = scheduler }
        lateinit var vm: GroupViewModel

        suspend fun open(): Screen {
            p.base.states[GID] = p.base.state(GID).copy(rosterQueryAtMs = T)
            vm = AdminCheck.groupVm(p, f)
            return this
        }

        fun proposals(): Int = f.seam.proposalRequests.get()

        /** The on-chain messages the price question on screen counts, or null when there is none. */
        fun asked(): Int? = vm.paidActionConfirmation.value?.onChainMessages

        suspend fun pay() = f.awaitNewJobs(vm) { vm.confirmPaidAction() }

        /**
         * The tap on "Tap to sync group keys", and Pay when a price is asked. Returns what happened, in order: the
         * question (or none), then every key request that left the phone, with where it went and how it was signed.
         */
        suspend fun tapSync(): String {
            f.awaitNewJobs(vm) { vm.requestGroupKeySync(GID) }
            val asked = asked()
            if (asked != null) pay()
            return listOf(asked?.let { question(it) } ?: NO_QUESTION, sent()).joinToString("; ")
        }

        /** Every key request sent so far: the free relay copies, then the on-chain proposals. */
        fun sent(): String {
            val relay = published.toList().map { (memo, relayKey) -> "relay to ${who(relayKey)}, ${signing(memo)}" }
            val uris = f.seam.proposalUris.toList()
            val chain = uris.map { uri -> "on-chain to ${who(payee(uri))}, ${signing(firstMemo(uri))}" }
            return (relay + chain).ifEmpty { listOf(NOTHING_SENT) }.joinToString(" + ")
        }

        private fun payee(zip321Uri: String): String = zip321Uri.substringAfter("zcash:").substringBefore("?")

        private fun who(addressOrRelayKey: String): String =
            when (addressOrRelayKey) {
                Y, Y_NOSTR -> "Y"
                Y2, Y2_NOSTR -> "Y2"
                X_OLD, CREATOR_NOSTR -> "X_OLD"
                M -> "M"
                else -> "an unknown address"
            }

        /** How [memo] is signed: a key request for this group from this phone, signed with our key for which chat. */
        private fun signing(memo: String): String {
            if (ZMSGGroupProtocol.parseMessageType(memo) != GroupMessageType.GROUP_KEY_REQUEST) return "not a key request"
            val request =
                ZMSGGroupProtocol.parsePayload(memo)?.let { ZMSGGroupProtocol.parseGroupKeyRequestPayload(it) }
                    ?: return "an unreadable key request"
            if (ZMSGGroupProtocol.parseGroupId(memo) != GID || request.requester != p.self) return "a key request of another group or phone"
            val signature = request.signature ?: return "unsigned"
            val signedData = ZMSGGroupProtocol.groupKeyRequestSignedData(GID, p.self, request.haveEpoch)
            val chat =
                p.base.ourKeys.entries
                    .filter { (_, ours) -> runCatching { E2EEncryption.verify(ours.publicKey, signedData, signature) }.getOrDefault(false) }
                    .map { who(it.key) }
                    .toSortedSet()
            return if (chat.isEmpty()) "signed with an unknown key" else "signed with our key for ${chat.joinToString("/")}"
        }
    }

    /** The admin's chat [Y] can be reached over the relays, and a publish there is acked. */
    private fun relayRouteToY(p: AdminCheckPhone) {
        p.base.nostrKeys[Y] = Y_NOSTR
        AdminCheck.relayRecorder(published)
    }

    // ------------------------------------------------------------------ (a)

    @Test
    fun `a - while the admin is not confirmed the tap asks no price, pays nothing and publishes nothing`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            relayRouteToY(p)
            val screen = Screen(p).open()

            val happened = screen.tapSync()

            assertEquals(
                "a group that cannot confirm its admin asked a price for a key request that cannot be answered",
                "$NO_QUESTION; $NOTHING_SENT",
                happened,
            )
            assertEquals(0, screen.proposals())
            assertEquals(emptyList<Pair<String, String>>(), published.toList())
        }

    // ------------------------------------------------------------------ (b)

    @Test
    fun `b - with a relay route the request goes free to the contact that holds the creator key, signed for it`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            relayRouteToY(p)
            val screen = Screen(p).open()

            val happened = screen.tapSync()

            assertEquals(
                "the key request did not go free and signed to the contact that holds the group's creator key",
                "$NO_QUESTION; relay to Y, signed with our key for Y",
                happened,
            )
            assertEquals("a request that went free over the relays was also paid for", 0, screen.proposals())
            assertEquals("the request was not published once, to the admin's relay key", listOf(Y_NOSTR), published.map { it.second })
        }

    // ------------------------------------------------------------------ (c)

    @Test
    fun `c - with no relay route the question counts one message and Pay sends one signed request to that contact`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val screen = Screen(p).open()

            val happened = screen.tapSync()

            assertEquals(
                "the paid key request did not go, signed, to the contact that holds the group's creator key",
                "${question(1)}; on-chain to Y, signed with our key for Y",
                happened,
            )
            assertEquals("Pay did not make exactly the one proposal the question counted", 1, screen.proposals())
            val uri = screen.f.seam.proposalUris[0]
            assertTrue("the proposal does not pay the admin's contact", uri.startsWith("zcash:$Y?"))
            assertFalse("the proposal names the stored creator address, which holds no key", X_OLD in uri)
        }

    // ------------------------------------------------------------------ (d)

    @Test
    fun `d - a quote for an address that is no longer the admin's contact sends nothing and the new plan is quoted`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            // The plan was quoted while the admin's chat was under Y. Since then the creator key is held by the chat
            // under Y2, and the chat under Y holds another key. Y can still be reached over the relays; Y2 cannot.
            relayRouteToY(p)
            p.base.link(Y2)
            p.base.peerKeys[Y2] = p.admin.publicKey
            p.base.peerKeys[Y] = E2EEncryption.generateKeyPair().publicKey
            val screen = Screen(p).open()

            AdminCheck.requestKeySyncQuoted(screen.vm, screen.f, recipients = listOf(Y), paid = setOf(Y))

            assertEquals("a key request was sent under a quote for an address that is no longer the admin's", NOTHING_SENT, screen.sent())
            assertEquals(0, screen.proposals())
            assertEquals(emptyList<Pair<String, String>>(), published.toList())
            assertEquals("the changed plan was not quoted again (one on-chain message, to the admin's contact)", 1, screen.asked())

            screen.pay()

            assertEquals(
                "the new plan is not a signed request to the contact that holds the creator key now",
                "on-chain to Y2, signed with our key for Y2",
                screen.sent(),
            )
            assertEquals("Pay on the new plan did not make exactly one proposal", 1, screen.proposals())
        }

    // ------------------------------------------------------------------ (e)

    @Test
    fun `e - an admin known to run a ZChat older than 2_13 with no relay route is asked nothing and nothing is paid`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            p.ironwoodCaps[Y] = false
            val screen = Screen(p).open()

            val happened = screen.tapSync()

            assertEquals(
                "a price was asked for an on-chain key request that the admin's older ZChat cannot see",
                "$NO_QUESTION; $NOTHING_SENT",
                happened,
            )
            assertEquals(0, screen.proposals())
        }

    @Test
    fun `e - an admin known to run a ZChat older than 2_13 with a relay route gets the free relay copy only`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            p.ironwoodCaps[Y] = false
            relayRouteToY(p)
            val screen = Screen(p).open()

            val happened = screen.tapSync()

            assertEquals(
                "the free relay copy to the admin's contact was not the only thing sent",
                "$NO_QUESTION; relay to Y, signed with our key for Y",
                happened,
            )
            assertEquals(0, screen.proposals())
        }

    // ------------------------------------------------------------------ (f)

    @Test
    fun `f - a request that cannot be signed and has no relay route is asked nothing and never goes on-chain`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            // The contact (a saved one) still holds the creator key; our own key pair for that chat is gone.
            p.name(Y, "Synthetic Admin")
            p.base.ourKeys.remove(Y)
            val screen = Screen(p).open()

            val happened = screen.tapSync()

            assertEquals(
                "a price was asked for an on-chain key request that cannot be signed (the admin's phone ignores it)",
                "$NO_QUESTION; $NOTHING_SENT",
                happened,
            )
            assertEquals(0, screen.proposals())
        }

    // ------------------------------------------------------------------ (g)

    /** A healthy group: the admin's chat is at the stored creator address, and the creator key is stored. */
    private fun healthyPhone(): AdminCheckPhone =
        AdminCheck.pinnedPhone().also { p ->
            p.base.infos[GID] = ZMSGGroupProtocol.serializeGroupInfo(p.base.info(GID).copy(creatorAddress = Y))
            p.base.rosters[GID] =
                ZMSGGroupProtocol.serializeGroupMembers(
                    p.base.roster(GID).map { if (it.address == X_OLD) it.copy(address = Y) else it },
                )
            check(p.base.roster(GID).count { it.status == MemberStatus.ACTIVE } == 3) { "fixture: the healthy group has three members" }
            check(p.base.info(GID).createdAt == Instant.ofEpochMilli(T))
        }

    @Test
    fun `g - control - a healthy group with no relay route asks for one message and Pay sends one signed request`() =
        runBlocking {
            val screen = Screen(healthyPhone()).open()

            val happened = screen.tapSync()

            assertEquals("${question(1)}; on-chain to Y, signed with our key for Y", happened)
            assertEquals(1, screen.proposals())
        }

    @Test
    fun `g - control - a healthy group with a relay route sends the signed request free`() =
        runBlocking {
            val p = healthyPhone()
            relayRouteToY(p)
            val screen = Screen(p).open()

            val happened = screen.tapSync()

            assertEquals("$NO_QUESTION; relay to Y, signed with our key for Y", happened)
            assertEquals(0, screen.proposals())
        }
}
