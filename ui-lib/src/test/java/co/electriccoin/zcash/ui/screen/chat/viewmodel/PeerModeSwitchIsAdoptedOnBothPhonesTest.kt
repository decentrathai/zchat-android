package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.invoke
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RealModeStorePhone.Companion.state
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * ZC-1478 (OT213n B1a; owner findings 2 Nov 2026, decision W-122; FIX_PLAN REVISION 1; FABLE_PLAN_CRITIQUE M1, M2, M3, S7).
 *
 * The owner's case: HONOR switched a Tunnel chat to Shielded, the Fold showed Accept / Keep, he tapped Cancel, and the two
 * phones stayed in different modes for good. At 669cf19e1 handleModeControl never adopts a contact's switch to Shielded
 * (it stores an Accept/Keep prompt instead) and never adopts any switch in a chat whose mode the user picked by hand.
 *
 * W-122: a mode change applies on BOTH phones at once, any direction, also to Shielded; no Accept. Bounded by:
 *  - M2: Shielded is adopted only from a contact whose relay rumors carry the Ironwood capability (2.13); a 2.12.4 phone
 *    cannot read Shielded messages, so its ZMODE Shielded leaves the mode as it is;
 *  - M3: of two crossed switches the newer one wins on both phones: a contact's switch older than our own last switch is
 *    not adopted, and our own mode is sent to the contact again (free, over the relays) so it converges;
 *  - M1: an adopted Shielded mode pays nothing by itself (no key exchange, ZBOOT or any spend on a chat open, under the
 *    standing consent), and the receiver's first send after it asks once with the per-message price.
 *
 * Driven through the real handler and send paths ([RealModeStorePhone]: the real preferences store keeps the mode, its
 * pin and every mode-change record, so the fix may store its own last-switch time or a "moved by the contact" mark under
 * any name). The 2.12.4 side uses [Frozen2124], copied from v2.12.4.
 *
 * Tests here that pass at 669cf19e1 are guards for what the fix must keep (named "guard").
 * Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerModeSwitchIsAdoptedOnBothPhonesTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc1478modepeer00001"
        const val PEER_RELAY_KEY = "5c2e9a7b31d40f6e8a9b0c1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6f7"
        const val HOUR = 60L * 60 * 1000
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    // ── W-122: adopt on both phones ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a 2_13 contact's switch to Shielded is adopted with no Accept prompt, also in a chat whose mode was picked by hand`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, exchangeComplete = true, ironwood = true)
            phone.real.setConversationModeExplicit(PEER)
            val vm = phone.viewModel()

            invoke(vm, "handleModeControl", PEER, zmode(ConversationMode.VAULT, System.currentTimeMillis()))
            awaitIdle(vm)

            assertEquals(
                "the contact's switch to Shielded was not adopted (W-122: both phones at once)",
                ConversationMode.VAULT,
                phone.mode(),
            )
            assertFalse("an Accept/Keep prompt was stored; W-122 removes it for a 2.13 contact", "setPendingModeSwitch" in phone.calls)
            assertEquals("adopting a mode spends nothing", 0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `a contact's newer switch to a free mode is adopted although our mode was picked by hand`() =
        runBlocking {
            val phone = phone(ConversationMode.VAULT, exchangeComplete = true, ironwood = true)
            phone.relaysUp()
            val vm = phone.viewModel()
            vm.changeConversationMode(PEER, ConversationMode.VAULT) // the user's own pick (pins, sends nothing)
            awaitIdle(vm)

            invoke(vm, "handleModeControl", PEER, zmode(ConversationMode.TUNNEL, System.currentTimeMillis() + 60_000L))
            awaitIdle(vm)

            assertEquals(
                "a hand-picked mode still blocks the contact's newer switch (W-122 / M3: the explicit pin no longer blocks)",
                ConversationMode.TUNNEL,
                phone.mode(),
            )
        }

    // ── M3: crossed switches converge ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `M3 a contact's switch older than our own last switch is not adopted and our mode is sent to them again`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, exchangeComplete = true, ironwood = true)
            phone.relaysUp()
            val vm = phone.viewModel()
            val beforeOurs = System.currentTimeMillis() - 5_000L
            vm.changeConversationMode(PEER, ConversationMode.OPEN) // ours, now
            awaitIdle(vm)
            assertEquals("fixture: our switch was delivered over the relays", 1, zmodesPublished(phone).size)
            phone.published.clear()

            // The contact switched to Shielded a few seconds BEFORE us; the two ZMODEs crossed on the relays.
            invoke(vm, "handleModeControl", PEER, zmode(ConversationMode.VAULT, beforeOurs))
            awaitIdle(vm)

            assertEquals("the older crossed switch must not replace our newer one", ConversationMode.OPEN, phone.mode())
            val resent = zmodesPublished(phone).map { ZMSGProtocol.parseModeChange(it)?.mode }
            val queued = phone.real.getPendingModeSyncs()[PEER]?.modeName
            assertTrue(
                "our newer mode must be sent to the contact again so both phones end in it (M3); relayed $resent, queued $queued",
                resent.contains(ConversationMode.OPEN) || queued == ConversationMode.OPEN.name,
            )
            assertEquals("a crossed switch spends nothing", 0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `M3 the same crossed switch from the contact, when it is the newer one, is adopted on our phone too`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, exchangeComplete = true, ironwood = true)
            phone.relaysUp()
            val vm = phone.viewModel()
            vm.changeConversationMode(PEER, ConversationMode.OPEN)
            awaitIdle(vm)

            invoke(vm, "handleModeControl", PEER, zmode(ConversationMode.VAULT, System.currentTimeMillis() + 60_000L))
            awaitIdle(vm)

            assertEquals("the newer crossed switch wins on both phones", ConversationMode.VAULT, phone.mode())
        }

    // ── M2: never Shielded toward a phone that cannot read it ────────────────────────────────────────────────────────

    @Test
    fun `M2 guard - a ZMODE Shielded from a contact with no Ironwood capability on record leaves the mode as it is`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, exchangeComplete = true, ironwood = null)
            val vm = phone.viewModel()

            invoke(vm, "handleModeControl", PEER, Frozen2124.createModeChange(ConversationMode.VAULT, System.currentTimeMillis(), PEER))
            awaitIdle(vm)

            assertEquals("Shielded was adopted from a contact not known to run 2.13 (M2)", ConversationMode.TUNNEL, phone.mode())
            assertEquals(0, phone.seam.proposalRequests.get())
        }

    @Test
    fun `M2 guard - a ZMODE Shielded from a contact whose rumors carry no Ironwood tag (2_12_4) leaves the mode as it is`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, exchangeComplete = true, ironwood = false)
            val vm = phone.viewModel()

            invoke(vm, "handleModeControl", PEER, Frozen2124.createModeChange(ConversationMode.VAULT, System.currentTimeMillis(), PEER))
            awaitIdle(vm)

            assertEquals(
                "Shielded was adopted from a 2.12.4 contact, who cannot read Shielded messages (M2)",
                ConversationMode.TUNNEL,
                phone.mode(),
            )
        }

    /** compat-2124 rule 1: what a 2.12.4 receiver does with the ZMODE a 2.13.0 requester sends (its own prompt, as before). */
    @Test
    fun `Frozen2124 guard - a 2_12_4 receiver still reads the 2_13 switch to Shielded and still asks its user`() {
        val wire = ZMSGProtocol.createModeChange(ConversationMode.VAULT, 1_790_000_000_000L, PEER)
        val parsed = Frozen2124.parseModeChange(wire)

        assertEquals("2.12.4 must still parse the 2.13 ZMODE (wire unchanged)", ConversationMode.VAULT, parsed?.first)
        assertTrue(
            "2.12.4 asks its user (Accept/Keep) for a switch to Vault; it never adopts it",
            Frozen2124.promptsPaidSwitch(parsed!!.first, holdsPeerRelayKey = true, explicit = false, currentMode = ConversationMode.TUNNEL),
        )
    }

    // ── M1: nothing is paid without the price on screen ─────────────────────────────────────────────────────────────

    @Test
    fun `M1 the first send after the contact moved the chat to Shielded asks once with the per-message price`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, exchangeComplete = true, ironwood = true)
            phone.relaysUp()
            val vm = phone.viewModel()
            invoke(vm, "handleModeControl", PEER, zmode(ConversationMode.VAULT, System.currentTimeMillis()))
            awaitIdle(vm)
            phone.published.clear()

            vm.sendMessage(PEER, "synthetic first message after the switch")
            awaitIdle(vm)

            val asked = state(vm)
            assertTrue(
                "the receiver's first send after the contact moved the chat to Shielded must ask with the price first, was $asked",
                asked is SendMessageState.NeedsPaidActionConfirmation,
            )
            asked as SendMessageState.NeedsPaidActionConfirmation
            assertEquals("the question quotes the one on-chain message", 1, asked.confirmation.onChainMessages)
            assertTrue("the question names a price", asked.confirmation.totalCost.value > 0L)
            assertEquals("nothing is paid before the answer", 0, phone.seam.proposalRequests.get())
            assertTrue("nothing is sent over the relays instead", phone.published.isEmpty())

            vm.confirmPaidAction()
            awaitIdle(vm)
            assertEquals("Pay sends the message on chain, once", 1, phone.seam.proposalRequests.get())

            vm.sendMessage(PEER, "synthetic second message")
            awaitIdle(vm)
            assertFalse("the price question is asked once, not on every send", state(vm) is SendMessageState.NeedsPaidActionConfirmation)
            assertEquals(2, phone.seam.proposalRequests.get())
        }

    /**
     * M1 guard (FABLE_PLAN_CRITIQUE M1 RED): standing consent granted, an Open chat with no key exchange, the contact
     * moves it to Shielded, the user opens the chat: zero sends, zero spend. Green at 669cf19e1 only because Shielded is
     * never adopted there; it must stay green once it is.
     */
    @Test
    fun `M1 guard - an adopted Shielded mode pays nothing by itself when the chat is opened under the standing consent`() =
        runBlocking {
            val phone = phone(ConversationMode.OPEN, exchangeComplete = false, ourKey = false, ironwood = true, consent = true)
            phone.relaysUp()
            val vm = phone.viewModel()

            invoke(vm, "handleModeControl", PEER, zmode(ConversationMode.VAULT, System.currentTimeMillis()))
            awaitIdle(vm)
            openChat(vm, phone)
            tick(vm)
            awaitIdle(vm)

            assertEquals("an adopted mode paid for something by itself (M1)", 0, phone.seam.proposalRequests.get())
            assertEquals(0, phone.seam.submits.get())
        }

    /** Control for the guard above: the same fixture, a Tunnel chat whose key exchange is not done, pays on a chat open. */
    @Test
    fun `M1 control - the same fixture pays a Tunnel chat's key exchange on a chat open`() =
        runBlocking {
            val phone = phone(ConversationMode.TUNNEL, exchangeComplete = false, ourKey = true, ironwood = true, consent = true)
            val vm = phone.viewModel()

            openChat(vm, phone)
            awaitIdle(vm)

            assertEquals("fixture: a Tunnel chat open under the standing consent pays its KEX", 1, phone.seam.proposalRequests.get())
        }

    // ---------------------------------------------------------------- fixture

    private fun phone(
        mode: ConversationMode,
        exchangeComplete: Boolean,
        ourKey: Boolean = exchangeComplete,
        ironwood: Boolean?,
        consent: Boolean = true,
    ): RealModeStorePhone =
        RealModeStorePhone(
            PEER,
            mode,
            consent = consent,
            exchangeComplete = exchangeComplete,
            ourKey = ourKey,
            relayKey = PEER_RELAY_KEY,
        ).also { p ->
            if (ironwood != null) p.real.recordPeerIronwoodCap(PEER, ironwood, System.currentTimeMillis() / 1000 - 60)
        }

    /** The contact's ZMODE as 2.13.0 builds it (the caller authenticated it; this is the handler boundary). */
    private fun zmode(
        mode: ConversationMode,
        since: Long,
    ): String = ZMSGProtocol.createModeChange(mode, since, PEER)

    private fun zmodesPublished(phone: RealModeStorePhone): List<String> =
        phone.published.map { it.first }.filter { ZMSGProtocol.isModeChange(it) }

    /** What AndroidChat's chat-open effect calls for this chat (AndroidChat.kt, LaunchedEffect(peerAddress, ...)). */
    private fun openChat(
        vm: ChatViewModel,
        phone: RealModeStorePhone,
    ) {
        vm.askAgainForHandshakeConsent()
        vm.retryKexAckIfResponder(PEER)
        vm.retryOwedKeyExchange(PEER)
        vm.repairUnconfirmedRelayIdentity(PEER)
        if (phone.mode() != ConversationMode.VAULT) vm.ensureNostrBootstrapSent(PEER)
    }

    /** The per-block retries (a new block arrives while the chat is open). */
    private fun tick(vm: ChatViewModel) {
        for (name in listOf("retryPendingTunnelZboots", "retryOwedKeyExchanges")) {
            val method =
                ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 1 } ?: continue
            method.isAccessible = true
            runCatching { method.invoke(vm, HandshakeHarness.START_HEIGHT + 1) }
        }
    }

    /**
     * ZChat 2.12.4's ZMODE wire and its receive rule, copied from v2.12.4 (commit a1fb4136f):
     *  - [createModeChange], [parseModeChange]: ui-lib/.../chat/model/ZMSGSpecialMessages.kt lines 482-499
     *    (ZMSGConstants.Prefixes.MODE_CHANGE = "ZMODE|", ZMSGConstants.kt line 62);
     *  - [promptsPaidSwitch]: the adopt and prompt rule of ChatViewModel.handleModeControl, v2.12.4 lines 1683-1697;
     *  - the address hash: ZMSGProtocol.generateAddressHash, v2.12.4 lines 144-149.
     */
    private object Frozen2124 {
        private const val MODE_CHANGE = "ZMODE|"

        private fun generateAddressHash(address: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(address.toByteArray())
            return hashBytes.take(8).joinToString("") { "%02x".format(it) }
        }

        fun createModeChange(
            mode: ConversationMode,
            sinceMillis: Long,
            senderAddress: String,
        ): String {
            val hash = generateAddressHash(senderAddress)
            return "${MODE_CHANGE}v1|${mode.name}|$sinceMillis|$hash"
        }

        /** (mode, since); v2.12.4's ParsedModeChange without the hash. */
        fun parseModeChange(memo: String): Pair<ConversationMode, Long>? {
            if (!memo.startsWith(MODE_CHANGE)) return null
            val parts = memo.removePrefix(MODE_CHANGE).split("|")
            if (parts.size < 4 || parts[0] != "v1") return null
            val mode = runCatching { ConversationMode.valueOf(parts[1]) }.getOrNull() ?: return null
            val sinceMillis = parts[2].toLongOrNull() ?: return null
            if (sinceMillis <= 0) return null
            return mode to sinceMillis
        }

        fun promptsPaidSwitch(
            newMode: ConversationMode,
            holdsPeerRelayKey: Boolean,
            explicit: Boolean,
            currentMode: ConversationMode,
        ): Boolean {
            val adopt = newMode != ConversationMode.VAULT && holdsPeerRelayKey && !explicit
            return !adopt && newMode == ConversationMode.VAULT && currentMode != newMode
        }
    }
}
