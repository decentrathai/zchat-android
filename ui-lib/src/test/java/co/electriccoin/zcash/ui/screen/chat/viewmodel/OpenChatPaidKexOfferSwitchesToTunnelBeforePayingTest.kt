package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1017 (register, medium, money; decision under W-57 of 15 Oct) — a paid key exchange that arrives in a chat this
 * phone holds as Open can never complete, and the sender's payment is wasted.
 *
 * On the phone (T6b Fold run, M3): the HONOR paid 34,000 zatoshi for its first Shielded message; the Fold, whose chat
 * with it was Open, held the key exchange for Accept ("Key exchange in this chat — tap Accept"). In an Open chat that
 * banner offers no way out that W-57 allows: its Accept pays the answer while the chat is Open, and nothing else
 * completes the exchange.
 *
 * Wanted (decided): in an Open chat the held key exchange is offered as a Tunnel chat: "Accept and pay" switches the chat
 * to Tunnel BEFORE the answer's proposal is built (so no Open chat ever pays), and pays exactly one answer for the
 * quoted amount; "Keep Open" pays nothing and keeps the chat Open (the hold stays, so nothing pays later by itself);
 * with too little balance Accept pays nothing and the chat stays Open; the offer survives a restart and goes once the
 * answer is confirmed (mined). A chat that is not Open keeps the earlier Accept banner. The answer is the same KEXACK as
 * before (no wire change), so a 2.12.4 phone on either side behaves as before.
 *
 * The phone is [LedgerWorld]'s (the send-attempt ledger, the synthetic wallet the history pass reads), with a store
 * overlay that keeps the chat mode, the Accept hold and the offer record. The new entry points are reached by name, so
 * this file compiles on a build without them. Every address, key and transaction id is synthetic.
 */
class OpenChatPaidKexOfferSwitchesToTunnelBeforePayingTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    /** The receiver's store: [LedgerWorld]'s, with the mode, the Accept hold and the offer record kept here (its disk). */
    private class Phone(
        val world: LedgerWorld,
    ) {
        val modes = ConcurrentHashMap<String, ConversationMode>()
        val explicit: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val hold = ConcurrentHashMap<String, String>()
        val offer = ConcurrentHashMap<String, String>()

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
                fun arg(i: Int) = args!![i]
                when (method.name) {
                    "getConversationMode" -> modes[arg(0) as String] ?: ConversationMode.VAULT
                    "getConversationModeOrNull" -> modes[arg(0) as String]
                    "setConversationMode" -> {
                        val mode = arg(1) as ConversationMode?
                        if (mode == null) modes.remove(arg(0) as String) else modes[arg(0) as String] = mode
                        world.timeline.add("prefs:setConversationMode:$mode")
                        null
                    }
                    "isConversationModeExplicit" -> arg(0) in explicit
                    "setConversationModeExplicit" -> {
                        explicit += arg(0) as String
                        null
                    }
                    "getInboundKexAwaitingAccept" -> hold[arg(0) as String]
                    "setInboundKexAwaitingAccept" -> {
                        val value = arg(1) as String?
                        if (value == null) hold.remove(arg(0) as String) else hold[arg(0) as String] = value
                        world.timeline.add("prefs:setInboundKexAwaitingAccept")
                        true
                    }
                    "getOpenChatKexOffer" -> offer[arg(0) as String]
                    "setOpenChatKexOffer" -> {
                        val value = arg(1) as String?
                        if (value == null) offer.remove(arg(0) as String) else offer[arg(0) as String] = value
                        true
                    }
                    else ->
                        try {
                            method.invoke(world.prefs.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences

        /** A screen of this phone (a second call after [LedgerWorld.restart] is the process after a restart). */
        suspend fun screen(): ChatViewModel =
            world.openVm().also { PaymentHarness.setField(it, "zchatPreferences", proxy) }
    }

    private fun funded() =
        Zc101FakeAccountDataSource(
            kotlinx.coroutines.runBlocking {
                syntheticZashiAccount().let {
                    it.copy(unified = it.unified.copy(ironwood = WalletBalance(Zatoshi(1_000_000L), Zatoshi(0), Zatoshi(0))))
                }
            },
        )

    /**
     * The state the contact's paid key exchange leaves on this phone in an Open chat: its key stored, our key and E2E for
     * it, the responder mark, and the Accept hold (handleKEXMessage after adoptKeyOffer). Returns the contact's key.
     */
    private fun heldKexInOpenChat(
        phone: Phone,
        mode: ConversationMode = ConversationMode.OPEN,
    ): String {
        val world = phone.world
        val contactKey = E2EEncryption.generateKeyPair().publicKey
        world.base.peerKeys[world.peer] = contactKey
        world.prefs.receivedKex[world.peer] = contactKey
        phone.modes[world.peer] = mode
        phone.hold[world.peer] = contactKey
        return contactKey
    }

    private fun call(
        vm: ChatViewModel,
        name: String,
        peer: String,
    ): Any? {
        val method =
            ChatViewModel::class.java.methods.firstOrNull { it.name == name && it.parameterCount == 1 }
                ?: throw AssertionError(
                    "ChatViewModel.$name(peer) does not exist: a paid key exchange held in an Open chat can only be answered " +
                        "by the earlier Accept, which pays while the chat is Open (W-57), so the sender's payment is wasted",
                )
        return try {
            method.invoke(vm, peer)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun stage(
        vm: ChatViewModel,
        peer: String,
    ): String = call(vm, "openChatTunnelOffer", peer).toString()

    private suspend fun accept(
        vm: ChatViewModel,
        peer: String,
    ) {
        call(vm, "acceptOpenChatTunnelOffer", peer)
        HandshakeHarness.awaitIdle(vm)
    }

    @Test
    fun `an Open chat's held key exchange is offered as a Tunnel chat`() =
        runBlocking {
            val phone = Phone(LedgerWorld(mode = ConversationMode.OPEN, accountSource = funded()))
            heldKexInOpenChat(phone)
            val vm = phone.screen()

            assertEquals("the held key exchange of an Open chat is not offered as a Tunnel chat", "OFFERED", stage(vm, phone.world.peer))
            assertEquals(
                "nothing is paid before the tap",
                0,
                phone.world.seam.proposals
                    .get(),
            )
        }

    @Test
    fun `Accept switches the chat to Tunnel before the answer's proposal is built and pays exactly one answer`() =
        runBlocking {
            val phone = Phone(LedgerWorld(mode = ConversationMode.OPEN, accountSource = funded()))
            val contactKey = heldKexInOpenChat(phone)
            val world = phone.world
            val vm = phone.screen()
            val proposalGate = CompletableDeferred<Unit>()
            world.seam.holdProposals = proposalGate
            val mark = world.timeline.size

            call(vm, "acceptOpenChatTunnelOffer", world.peer)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { world.seam.proposalEntered.await() }
            val modeAtProposal = phone.modes[world.peer]
            proposalGate.complete(Unit)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("the answer's proposal was built while the chat was still Open", ConversationMode.TUNNEL, modeAtProposal)
            assertEquals("Accept did not pay exactly one answer", 1, world.seam.proposals.get())
            assertEquals("the answer is for the key that waited", contactKey, world.prefs.sentKexAck[world.peer])
            assertEquals("the chat is not Tunnel after Accept", ConversationMode.TUNNEL, phone.modes[world.peer])
            assertNull("the Accept hold stayed after Accept", phone.hold[world.peer])
            // The ledger: the answer's attempt is written after the switch to Tunnel.
            val entry =
                world.ledger.one("KEXACK") ?: throw AssertionError("no KEXACK attempt in the send-attempt ledger: ${world.ledger.all()}")
            val after = world.timeline.drop(mark)
            val switched = after.indexOf("prefs:setConversationMode:TUNNEL")
            val firstLedgerWrite = after.indexOfFirst { it.startsWith("ledger:") }
            assertTrue("the chat was never switched to Tunnel: $after", switched >= 0)
            assertTrue(
                "the answer's ledger entry was written before the chat became Tunnel (paid in an Open chat): $after",
                firstLedgerWrite > switched,
            )
            assertEquals("LIVE", entry.state)
        }

    @Test
    fun `Keep Open pays nothing, keeps the chat Open and the hold, and nothing pays later by itself`() =
        runBlocking {
            val phone = Phone(LedgerWorld(mode = ConversationMode.OPEN, accountSource = funded()))
            val contactKey = heldKexInOpenChat(phone)
            val world = phone.world
            val vm = phone.screen()

            call(vm, "keepChatOpen", world.peer)
            HandshakeHarness.awaitIdle(vm)
            vm.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("Keep Open paid", 0, world.seam.proposals.get())
            assertEquals(ConversationMode.OPEN, phone.modes[world.peer])
            assertEquals("the hold that stops any automatic answer is gone", contactKey, phone.hold[world.peer])
            assertEquals("the offer is still shown after Keep Open", "NONE", stage(vm, world.peer))
        }

    @Test
    fun `with too little balance Accept pays nothing and the chat stays Open with the offer`() =
        runBlocking {
            // The synthetic account holds nothing spendable.
            val phone = Phone(LedgerWorld(mode = ConversationMode.OPEN))
            heldKexInOpenChat(phone)
            val world = phone.world
            val vm = phone.screen()

            call(vm, "acceptOpenChatTunnelOffer", world.peer)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("Accept paid with too little balance", 0, world.seam.proposals.get())
            assertEquals("the chat left Open although nothing could be paid", ConversationMode.OPEN, phone.modes[world.peer])
            assertEquals("OFFERED", stage(vm, world.peer))
        }

    @Test
    fun `the offer survives a restart, stays while the answer is unmined and goes once it is mined`() =
        runBlocking {
            val phone = Phone(LedgerWorld(mode = ConversationMode.OPEN, accountSource = funded()))
            heldKexInOpenChat(phone)
            val world = phone.world
            phone.screen()
            world.restart()
            val vm = phone.screen()
            assertEquals("the offer is gone after a restart", "OFFERED", stage(vm, world.peer))

            accept(vm, world.peer)
            world.restart()
            val vm2 = phone.screen()
            assertEquals("the offer went before the answer was confirmed", "PAYING", stage(vm2, world.peer))

            world.wallet.txs = listOf(world.chainTx(1, minedHeight = LedgerWorld.H + 2))
            world.pass(vm2, LedgerWorld.H + 4)
            assertEquals("fixture sanity: the answer is mined: ${world.ledger.all()}", "MINED", world.ledger.one("KEXACK")?.state)
            assertEquals("the offer stays after the answer was confirmed", "NONE", stage(vm2, world.peer))
            assertEquals(1, world.seam.proposals.get())
        }

    @Test
    fun `control - a chat that is not Open keeps the earlier Accept banner, not the Tunnel offer`() =
        runBlocking {
            val phone = Phone(LedgerWorld(mode = ConversationMode.TUNNEL, accountSource = funded()))
            heldKexInOpenChat(phone, mode = ConversationMode.TUNNEL)
            val vm = phone.screen()

            assertEquals("NONE", stage(vm, phone.world.peer))
            assertTrue("the earlier Accept banner is gone", vm.isInboundKexAwaitingAccept(phone.world.peer))
        }
}
