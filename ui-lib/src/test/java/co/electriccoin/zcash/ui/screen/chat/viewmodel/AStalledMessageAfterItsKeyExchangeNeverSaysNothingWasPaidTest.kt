package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ZC-1310 (money-path: a wrong money status is a defect): a first message pays our key exchange, then its own build stalls.
 * ZC-1276's limit marks the row with "took too long to prepare, and nothing was paid for it", while the wallet has just
 * paid that chat's key exchange. Must hold: whatever the row and the notice say, in either language, does not say that
 * nothing was paid, on the first run, on a Retry that stalls too, and when the total limit ends the row.
 *
 * Real ChatViewModel send over [FundsConfirmingChat]; only the message's builds are held, recognised as the builds after the
 * first submit. A text that names the message alone passes, one constant or two. Every address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AStalledMessageAfterItsKeyExchangeNeverSaysNothingWasPaidTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    private companion object {
        const val PEER = "usyntheticzc1310stalledmsg01"
        const val TYPED = "synthetic first words behind a paid key exchange"
        const val SETTLE_MS = FundsConfirmingChat.SETTLE_MS
        const val MINUTE_MS = 60_000L

        /** Above ZC-1276's limit for one build (15 minutes). */
        const val STALL_MS = 16L * MINUTE_MS

        /** Below that limit, so only the total (60 minutes) can end the row. */
        const val SLOW_BUILD_MS = 14L * MINUTE_MS
        const val LIMIT_MS = 61L * MINUTE_MS
        val NOTHING_PAID = listOf("nothing was paid", "no se pagó nada", "nada fue pagado")
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        ChatViewModel::class.java
            .getDeclaredField("heldGroupAcceptScope")
            .apply { isAccessible = true }
            .set(null, sendScopes.heldAcceptScope)
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
    }

    @Test
    fun `a message whose build stalls after its key exchange was paid does not say nothing was paid`() =
        runBlocking {
            val (world, chat, builds) = started()
            try {
                val id = kexPaidAndMessageBuilding(world, chat, builds, run = 0)
                stall()
                assertSettled(chat, id)
                assertTrueText(world, chat, id)
            } finally {
                builds.releaseAll()
            }
        }

    @Test
    fun `a Retry that stalls again after the earlier run paid the key exchange does not say nothing was paid`() =
        runBlocking {
            val (world, chat, builds) = started()
            try {
                val id = kexPaidAndMessageBuilding(world, chat, builds, run = 0)
                stall()
                assertSettled(chat, id)

                assertTrue("Retry was refused for a row shown as not sent", chat.retryMessage(PEER, id))
                assertTrue(
                    "fixture sanity: the Retry's build did not start",
                    PaymentHarness.eventually(SETTLE_MS) { builds.entered.size > 1 },
                )
                assertEquals("fixture sanity: the Retry paid the key exchange again", 1, world.seam.submits.get())
                stall()
                assertSettled(chat, id)
                assertTrueText(world, chat, id)
            } finally {
                builds.releaseAll()
            }
        }

    @Test
    fun `a message the total limit ends after its key exchange was paid does not say nothing was paid`() =
        runBlocking {
            val (world, chat, builds) = started()
            try {
                val id = kexPaidAndMessageBuilding(world, chat, builds, run = 0)
                var elapsed = 0L
                var run = 0
                while (elapsed <= LIMIT_MS && !isFailed(chat, id)) {
                    assertTrue(
                        "fixture sanity: run ${run + 1} did not start",
                        PaymentHarness.eventually(SETTLE_MS) { builds.entered.size > run },
                    )
                    scheduler.advanceTimeBy(SLOW_BUILD_MS)
                    scheduler.runCurrent()
                    elapsed += SLOW_BUILD_MS
                    if (elapsed > LIMIT_MS || isFailed(chat, id)) break
                    builds.entered[run].complete(HandshakeHarness.stillConfirming())
                    PaymentHarness.eventually(SETTLE_MS) {
                        PaymentHarness.sendMessageState(chat) is SendMessageState.Success || isFailed(chat, id)
                    }
                    world.block(chat)
                    run++
                }
                assertSettled(chat, id)
                assertTrueText(world, chat, id)
            } finally {
                builds.releaseAll()
            }
        }

    private data class Started(
        val world: FundsConfirmingChat,
        val chat: ChatViewModel,
        val builds: MessageBuildsHeld,
    )

    private suspend fun started(): Started {
        val world = FundsConfirmingChat(PEER).apply { plan = "Fundable" }
        val chat = world.screen()
        val builds = MessageBuildsHeld(world.funds, world.seam)
        PaymentHarness.setField(chat, "createChunkedMessageProposal", useCase(builds))
        return Started(world, chat, builds)
    }

    /** The first message is sent and accepted; its key exchange is paid; its message's build is open. Returns the row id. */
    private suspend fun kexPaidAndMessageBuilding(
        world: FundsConfirmingChat,
        chat: ChatViewModel,
        builds: MessageBuildsHeld,
        run: Int,
    ): String {
        world.chat.typeAndSend(chat, TYPED)
        world.question(chat)
        chat.confirmPaidAction()
        assertTrue(
            "fixture sanity: the message's build did not start after the key exchange (paid ${world.seam.submits.get()})",
            PaymentHarness.eventually(SETTLE_MS) { builds.entered.size > run },
        )
        assertEquals("fixture sanity: the key exchange is the one payment so far", 1, world.seam.submits.get())
        val row = world.row()
        assertNotNull("fixture sanity: no row on disk", row)
        return row!!.id
    }

    private fun stall() {
        scheduler.advanceTimeBy(STALL_MS)
        scheduler.runCurrent()
    }

    private fun isFailed(
        chat: ChatViewModel,
        id: String,
    ): Boolean = PaymentHarness.pendingMessages(chat).firstOrNull { it.id == id }?.status == MessageStatus.FAILED

    private suspend fun assertSettled(
        chat: ChatViewModel,
        id: String,
    ) {
        assertTrue("fixture sanity: the row did not settle as not sent", PaymentHarness.eventually(SETTLE_MS) { isFailed(chat, id) })
    }

    private fun assertTrueText(
        world: FundsConfirmingChat,
        chat: ChatViewModel,
        id: String,
    ) {
        assertEquals("fixture sanity: exactly the key exchange was paid", 1, world.seam.submits.get())
        val shown = PaymentHarness.pendingMessages(chat).single { it.id == id }.failureReason
        val stored = world.row()?.failureReason
        val notice = FirstMessagePlanChat.error(chat)
        assertFalse("the row gives no reason", shown.isNullOrBlank())
        assertFalse("the settled row states nothing on disk", stored.isNullOrBlank())
        for ((where, text) in listOf("the row" to shown, "the stored row" to stored, "the notice" to notice)) {
            if (text == null) continue
            assertNoPromise("$where (English)", text)
            spanishOf(text)?.let { assertNoPromise("$where (Spanish)", it) }
        }
    }

    private fun assertNoPromise(
        where: String,
        text: String,
    ) {
        val lower = text.lowercase()
        assertFalse(
            "$where says nothing was paid, but the key exchange of this message was paid: \"$text\"",
            NOTHING_PAID.any { it in lower },
        )
    }

    /** The Spanish resource of a [ChatSendCopy] text, whichever constant the row holds; null when it has none. */
    private fun spanishOf(text: String): String? {
        val id = ChatSendCopy.localizedResource(text) ?: return null
        val name =
            R.string::class.java.fields
                .firstOrNull { it.getInt(null) == id }
                ?.name ?: return null
        val entry = Regex("<string name=\"$name\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        return File(ChatResourceText.repoRoot, "ui-lib/src/main/res/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile?.name == "values-es" }
            .firstNotNullOfOrNull { entry.find(it.readText())?.groupValues?.get(1) }
    }

    private suspend fun useCase(source: ProposalDataSource): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = source,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(sendScopes::own),
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
    }

    /** Every build after the first submit (the key exchange's) stays open until the test answers it. */
    private class MessageBuildsHeld(
        private val inner: FundsConfirmingChat.MeteredFunds,
        private val seam: HandshakeSeam,
    ) : ProposalDataSource by inner {
        val entered = CopyOnWriteArrayList<CompletableDeferred<Exception?>>()

        @Volatile private var released = false

        fun releaseAll() {
            released = true
            entered.forEach { it.complete(null) }
        }

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            if (seam.submits.get() >= 1 && !released) {
                val answer = CompletableDeferred<Exception?>()
                entered += answer
                if (released) answer.complete(null)
                answer.await()?.let { throw it }
            }
            return inner.createZip321Proposal(account, zip321Uri, allowMultiPayment)
        }
    }
}
