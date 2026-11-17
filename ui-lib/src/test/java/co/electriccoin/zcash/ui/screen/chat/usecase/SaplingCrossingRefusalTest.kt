package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ProposingSynchronizerProvider
import co.electriccoin.zcash.ui.common.datasource.RequestProposalBytes
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.datasource.Zip321Payment
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.readSweepShape
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeSwapQuote
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * R2-2 (c), plan step p2-089b7b0c7ee2 — a Sapling-funded payment to someone else crosses pools in public.
 *
 * R2-1 (d) made every proposal funnel refuse a proposal the SDK funded from the sealed Orchard pool, because
 * that crossing publishes its amount (ZIP 318). SDK 3.0.0 prefers [Ironwood, Orchard, Sapling] and funds a
 * send from the first pool whose notes alone cover it, so a wallet whose Ironwood cannot cover the amount is
 * funded from Sapling. Sapling value paying an Ironwood (or any non-Sapling) output leaves the Sapling pool,
 * and the transaction's Sapling value balance shows that amount on chain. At 17197c357 nothing reads the
 * input pool for Sapling: every funnel passes the proposal on, and the user is never told.
 *
 * Wanted: every funnel refuses such a proposal with a typed refusal that says why (English and Spanish on the
 * Send screen and in the chat). A Sapling-to-Sapling payment does not cross pools and passes. The refusal
 * type is reached by name so this file compiles before it exists.
 */
class SaplingCrossingRefusalTest {
    private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

    private suspend fun account() =
        ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified =
                UnifiedInfo(
                    WalletAddress.Unified.new("usynthetic-self"),
                    balance(0),
                    balance(5_000),
                ),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(900_000)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )

    private suspend fun send() =
        ZecSend(destination = WalletAddress.Unified.new(PEER), amount = Zatoshi(100_000), memo = Memo(""), proposal = null)

    private fun funnel(
        inputPool: Int,
        paymentPool: Int
    ) = funnel(
        RequestProposalBytes.proposal(
            RequestProposalBytes.Step(
                "zcash:$PEER?amount=0.001",
                payments = 1,
                inputPool = inputPool,
                paymentPool = paymentPool,
            ),
        ),
    )

    private fun funnel(proposal: Proposal) =
        ProposalDataSourceImpl(ProposingSynchronizerProvider(proposal))
            .apply { readZip321 = { listOf(Zip321Payment(PEER, Zatoshi(100_000), "")) } }

    /**
     * A proposal the SDK reads but ProposalWireReader does not: a well-formed proposal followed by an unknown
     * field holding a uint64 above Long.MAX_VALUE (a new SDK field could look like this). Protobuf skips the
     * unknown field; the wire reader treats the value as malformed and returns null.
     */
    private fun unreadable(
        inputPool: Int,
        paymentPool: Int
    ): Proposal {
        val bytes =
            RequestProposalBytes.bytes(
                listOf(
                    RequestProposalBytes.Step(
                        "zcash:$PEER?amount=0.001",
                        payments = 1,
                        inputPool = inputPool,
                        paymentPool = paymentPool,
                    ),
                ),
                fee = 10_000,
            ) + SweepProposalBytes.message { varint(UNKNOWN_FIELD, -1L) }
        assertNull(readSweepShape(bytes), "fixture: the wire reader must not be able to read this proposal")
        return Proposal.fromByteArray(bytes).also {
            // Fixture self-check against the SDK's own reading of the bytes.
            check(it.transactionCount() == 1) { "fixture: ${it.transactionCount()} steps" }
            check(!it.usesOrchardInputs()) { "fixture: no Orchard input" }
        }
    }

    private fun funnels(account: WalletAccount): Map<String, suspend (ProposalDataSource) -> Any> =
        mapOf(
            "createProposal" to { it.createProposal(account, send()) },
            "createZip321Proposal" to { it.createZip321Proposal(account, "zcash:$PEER?amount=0.001", true) },
            "createExactInputProposal" to { it.createExactInputProposal(account, send(), Zc101FakeSwapQuote(Clock.System.now())) },
            "createExactOutputProposal" to { it.createExactOutputProposal(account, send(), Zc101FakeSwapQuote(Clock.System.now())) },
        )

    @Test
    fun every_funnel_refuses_a_sapling_funded_payment_into_another_pool() =
        runTest {
            val letThrough =
                funnels(account()).mapNotNull { (name, call) ->
                    val refusal = runCatching { call(funnel(SAPLING, IRONWOOD)) }.exceptionOrNull()
                    if (refusal?.javaClass?.simpleName == REFUSAL) null else "$name -> ${refusal ?: "a proposal"}"
                }
            assertEquals(emptyList(), letThrough, "a Sapling-funded payment into Ironwood must be refused by every funnel")
        }

    /** Control (passes before and after): Sapling paying Sapling crosses nothing. */
    @Test
    fun a_sapling_to_sapling_payment_passes_every_funnel() =
        runTest {
            val refused =
                funnels(account()).mapNotNull { (name, call) ->
                    runCatching { call(funnel(SAPLING, SAPLING)) }.exceptionOrNull()?.let { "$name -> $it" }
                }
            assertEquals(emptyList(), refused)
        }

    /** Control (passes before and after): an Ironwood-funded payment is untouched. */
    @Test
    fun an_ironwood_funded_payment_passes_every_funnel() =
        runTest {
            val refused =
                funnels(account()).mapNotNull { (name, call) ->
                    runCatching { call(funnel(IRONWOOD, IRONWOOD)) }.exceptionOrNull()?.let { "$name -> $it" }
                }
            assertEquals(emptyList(), refused)
        }

    /**
     * R3-1 (1) — fail closed. At 370009487 a proposal the wire reader cannot read skips the Sapling check,
     * so a Sapling-funded payment into Ironwood whose bytes the reader cannot read goes on to be signed. The
     * check cannot tell whether such a proposal crosses pools, so every funnel refuses it, whatever it spends
     * (as the Orchard sweep and the scanned-request check already do). The refusal is the generic "could not
     * be prepared" one: it does not claim a Sapling crossing it could not see.
     */
    @Test
    fun every_funnel_refuses_a_proposal_the_sapling_check_cannot_read() =
        runTest {
            for ((inputPool, paymentPool) in listOf(SAPLING to IRONWOOD, IRONWOOD to IRONWOOD, SAPLING to SAPLING)) {
                val letThrough =
                    funnels(account()).mapNotNull { (name, call) ->
                        val refusal = runCatching { call(funnel(unreadable(inputPool, paymentPool))) }.exceptionOrNull()
                        if (refusal is TransactionProposalNotCreatedException) null else "$name -> ${refusal ?: "a proposal"}"
                    }
                assertEquals(
                    emptyList(),
                    letThrough,
                    "an unreadable proposal ($inputPool -> $paymentPool) must be refused by every funnel",
                )
            }
        }

    @Test
    fun the_refusal_is_explained_in_english_and_spanish() {
        val refusal =
            Class
                .forName("co.electriccoin.zcash.ui.common.model.$REFUSAL")
                .getConstructor(Zatoshi::class.java)
                .newInstance(Zatoshi(900_000)) as Throwable
        val message = assertNotNull(refusal.message)
        assertTrue("Sapling" in message && "public" in message, message)
        // The chat shows it (not the generic fallback), and in the phone's language.
        assertEquals(message, refusal.toZchatUserMessage("fallback"))
        assertNotNull(ChatSendCopy.localizedResource(message), "no Spanish for the chat toast")
        // The Send screen maps it to its own reason, with English and Spanish text.
        val reason = SendStage.reasonFor(refusal)
        assertEquals("sapling_crossing", reason)
        val en = sendString("values", "send_dialog_error_reason_sapling_crossing")
        val es = sendString("values-es", "send_dialog_error_reason_sapling_crossing")
        assertTrue("Sapling" in en && "Sapling" in es)
        assertNotEquals(en, es)
    }

    private fun sendString(
        locale: String,
        name: String
    ): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        val f = File(assertNotNull(dir), "ui-lib/src/main/res/ui/send/$locale/strings.xml")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(f)
                .getElementsByTagName("string")
        return assertNotNull(
            (0 until nodes.length).map { nodes.item(it) as Element }.firstOrNull { it.getAttribute("name") == name }?.textContent,
            "$locale: $name missing",
        )
    }

    private companion object {
        const val PEER = "usynthetic-peer"
        const val SAPLING = 2
        const val IRONWOOD = 4
        const val REFUSAL = "SaplingCrossingException"
        const val UNKNOWN_FIELD = 99
    }
}
