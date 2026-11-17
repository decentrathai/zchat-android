package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 3 report 4 item 10 — the price quoted for a first message can be one chunk short.
 *
 * The first message to a new contact goes out as a ZMSG v4 INIT, whose first memo chunk embeds the
 * sender's FULL unified address inline; a real mainnet address is 178–213 bytes, so that chunk's budget
 * is much smaller than the legacy v3 one. The cost prompt sizes the send with the v3 counter
 * (calculateChunkCount, 340-byte first chunk) while the on-chain builder packs with the v4 one, so a long
 * first message is quoted one memo output — one dust output and one ZIP 317 action — short of what
 * actually leaves the wallet. The user confirms a number that is not the number they pay.
 *
 * The prompt must quote the count the builder really packs, sized from the user's own address.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessagePriceCountsEveryChunkTest {
    private val sendScopes = SendHarnessScopes()

    /** A real mainnet unified address is 178–213 bytes; the longest is the worst case for the INIT budget. */
    private val realisticSelfAddress = "u1" + "s".repeat(211)

    /**
     * Long enough that the v3 sizer (340-byte first chunk) and the v4 sizer (512 minus the header and the
     * 213-byte address) disagree: 800 characters fits two v3 chunks but needs three v4 ones.
     */
    private val longFirstMessage = "z".repeat(800)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** The oracle: the memos the on-chain builder really produces for this first message. */
    private fun chunksTheBuilderPacks(): Int =
        ZMSGProtocol.createChunkedV4InitMessages("TESTCONV", realisticSelfAddress, longFirstMessage).size

    @Test
    fun `the two sizers really do disagree for this message`() {
        assertTrue(
            "the fixture no longer exercises the undercount: v3 " +
                "${ZMSGProtocol.calculateChunkCount(longFirstMessage, true)} vs builder ${chunksTheBuilderPacks()}",
            chunksTheBuilderPacks() > ZMSGProtocol.calculateChunkCount(longFirstMessage, true),
        )
    }

    @Test
    fun `the first-message prompt quotes every chunk the send pays for`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticfirstmessageprice1", ConversationMode.VAULT, haveOurKey = false)
            val vm = f.viewModel()
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(realisticSelfAddress))

            vm.sendMessage(f.peer, longFirstMessage)
            awaitIdle(vm)

            val chunks = chunksTheBuilderPacks()
            // Round 10b R10B-1: plus our ZBOOT, which goes on-chain after the contact's KEXACK (no relay key here).
            val expected =
                onChainSendMinimumCost() +
                    onChainSendMinimumCost(chunks, CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT) +
                    onChainSendMinimumCost()

            assertEquals(
                "the prompt must quote the KEX plus a $chunks-chunk message, not a shorter one",
                expected.value,
                quotedTotal(vm),
            )
        }

    /** The prompt is a floor, not an estimate: it may never be below what the send costs. */
    @Test
    fun `the quoted total is never below the real cost of the packed message`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticfirstmessageprice2", ConversationMode.VAULT, haveOurKey = false)
            val vm = f.viewModel()
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(realisticSelfAddress))

            vm.sendMessage(f.peer, longFirstMessage)
            awaitIdle(vm)

            val floor =
                onChainSendMinimumCost() +
                    onChainSendMinimumCost(
                        chunksTheBuilderPacks(),
                        CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT,
                    )

            assertTrue(
                "quoted ${quotedTotal(vm)} is below the ${floor.value} the send really costs",
                quotedTotal(vm) >= floor.value,
            )
        }

    /** Control: a one-chunk first message is unaffected — both sizers agree, so the quote must not move. */
    @Test
    fun `control - a short first message is quoted exactly as before`() =
        runBlocking {
            val f = PaidChatFixture("usyntheticfirstmessageprice3", ConversationMode.VAULT, haveOurKey = false)
            val vm = f.viewModel()
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(realisticSelfAddress))

            vm.sendMessage(f.peer, "hello")
            awaitIdle(vm)

            // Round 10b R10B-1: plus our ZBOOT, which goes on-chain after the contact's KEXACK (no relay key here).
            val expected =
                onChainSendMinimumCost() +
                    onChainSendMinimumCost(1, CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT) +
                    onChainSendMinimumCost()

            assertEquals(expected.value, quotedTotal(vm))
        }

    private fun quotedTotal(vm: ChatViewModel): Long {
        val state = HandshakeHarness.sendMessageState(vm)
        assertEquals(
            "the user must be asked before a first message is paid for, was $state",
            "NeedsPaidActionConfirmation",
            state.javaClass.simpleName,
        )
        val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
        val total = confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation) as Zatoshi
        return total.value
    }
}
