package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.RequestProposalBytes.Step
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.model.Zip321Totals
import co.electriccoin.zcash.ui.common.model.readSweepShape
import co.electriccoin.zcash.ui.common.model.readZip321Totals
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * R2-1 (b), plan step p2-dabd323c2530: a scanned payment request pays exactly the one payment the
 * review screen shows.
 *
 * The review screen shows the first payment org.zecdev.zip321 read from the URI. The SDK builds the
 * proposal from the same URI with its own ZIP 321 parser (proposeFulfillingPaymentUri), and nothing
 * compared the two: if the parsers disagree on a crafted URI, the SDK can pay more payments, or a
 * larger total, than the screen showed. The payments the SDK will build are in each proposal step's
 * transaction request (ProposalStep field 1), read with ProposalWireReader. On the scan path
 * (allowMultiPayment = false) a proposal whose payment count is not 1, or whose total is not the amount
 * shown, is refused before it can be signed, for Zashi and Keystone accounts alike. The chat path's own
 * trusted two-payment URI (message + platform fee, allowMultiPayment = true) is a control and stays
 * accepted. A TEX payment is proposed as two steps (the first pays nothing) and stays accepted.
 *
 * org.zecdev.zip321 cannot load on the Java 17 test JVM, so what it read is given through the
 * ProposalDataSourceImpl.readZip321 seam. All addresses are synthetic.
 */
class ScannedRequestProposalMatchTest {
    private val shown = Zip321Payment(recipient = PEER, amount = Zatoshi(100_000), memo = "")

    private fun source(
        sdk: ProposingSynchronizerProvider,
        read: List<Zip321Payment> = listOf(shown)
    ) = ProposalDataSourceImpl(sdk).apply { readZip321 = { read } }

    private suspend fun accounts(): List<WalletAccount> = listOf(zashi(), keystone())

    // --- The mismatch (RED on the parent) ---

    @Test
    fun a_proposal_that_pays_a_second_hidden_payment_is_refused_before_signing() =
        runTest {
            val sdk =
                ProposingSynchronizerProvider(
                    RequestProposalBytes.proposal(
                        Step("zcash:?address=$PEER&amount=0.001&address.1=$HIDDEN&amount.1=0.5", payments = 2),
                    ),
                )
            for (account in accounts()) {
                assertFailsWith<TransactionProposalNotCreatedException>("${account.javaClass.simpleName}") {
                    source(sdk).createZip321Proposal(account, URI, allowMultiPayment = false)
                }
            }
        }

    @Test
    fun a_proposal_whose_total_is_not_the_amount_shown_is_refused_before_signing() =
        runTest {
            val sdk = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("zcash:$PEER?amount=0.01", payments = 1)))
            for (account in accounts()) {
                assertFailsWith<TransactionProposalNotCreatedException>("${account.javaClass.simpleName}") {
                    source(sdk).createZip321Proposal(account, URI, allowMultiPayment = false)
                }
            }
        }

    @Test
    fun a_proposal_with_no_readable_request_is_refused() =
        runTest {
            val sdk = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("", payments = 0)))
            assertFailsWith<TransactionProposalNotCreatedException> {
                source(sdk).createZip321Proposal(zashi(), URI, allowMultiPayment = false)
            }
        }

    // --- Controls ---

    @Test
    fun a_proposal_that_pays_exactly_what_was_shown_is_accepted() =
        runTest {
            val proposal = RequestProposalBytes.proposal(Step("zcash:$PEER?amount=0.001", payments = 1))
            for (account in accounts()) {
                val result = source(ProposingSynchronizerProvider(proposal)).createZip321Proposal(account, URI, false)
                assertSame(proposal, result.proposal)
                assertEquals(Zatoshi(100_000), result.amount)
            }
        }

    @Test
    fun a_tex_payment_proposed_in_two_steps_is_accepted() =
        runTest {
            val proposal =
                RequestProposalBytes.proposal(
                    Step("", payments = 0),
                    Step("zcash:$PEER?amount=0.001", payments = 1, inputPool = 0, inputValue = 0),
                )
            val result = source(ProposingSynchronizerProvider(proposal)).createZip321Proposal(zashi(), URI, false)
            assertSame(proposal, result.proposal)
        }

    @Test
    fun the_chat_trusted_two_payment_uri_with_its_platform_fee_is_still_accepted() =
        runTest {
            val chat = listOf(shown, Zip321Payment(recipient = PLATFORM, amount = Zatoshi(1_000), memo = ""))
            val proposal =
                RequestProposalBytes.proposal(
                    Step("zcash:?address=$PEER&amount=0.001&address.1=$PLATFORM&amount.1=0.00001", payments = 2),
                )
            val result =
                source(ProposingSynchronizerProvider(proposal), read = chat)
                    .createZip321Proposal(zashi(), URI, allowMultiPayment = true)
            assertSame(proposal, result.proposal)
            assertEquals(Zatoshi(100_000), result.amount, "the review line is the message payment")
        }

    @Test
    fun a_scanned_uri_with_two_payments_is_still_refused_before_the_sdk_builds_anything() =
        runTest {
            val sdk = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("zcash:$PEER?amount=0.001", 1)))
            val two = listOf(shown, Zip321Payment(HIDDEN, Zatoshi(50_000_000), ""))
            assertFailsWith<TransactionProposalNotCreatedException> {
                source(sdk, read = two).createZip321Proposal(zashi(), URI, allowMultiPayment = false)
            }
            assertEquals(emptyList(), sdk.calls)
        }

    // --- The recipient (R3-1 item 2; RED on 370009487) ---

    /**
     * The count and total match, but the SDK's proposal pays someone other than the recipient the review
     * screen shows. Refused before signing, for Zashi and Keystone accounts alike.
     */
    @Test
    fun a_proposal_that_pays_another_recipient_is_refused_before_signing() =
        runTest {
            val sdk = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("zcash:$HIDDEN?amount=0.001", 1)))
            for (account in accounts()) {
                assertFailsWith<TransactionProposalNotCreatedException>("${account.javaClass.simpleName}") {
                    source(sdk).createZip321Proposal(account, URI, allowMultiPayment = false)
                }
            }
        }

    /** One character changed: the text is almost the shown address, and it is not a valid address. */
    @Test
    fun a_proposal_that_pays_a_look_alike_of_the_shown_recipient_is_refused() =
        runTest {
            val lookAlike = SyntheticUnifiedAddress.mainnetBadChecksum(PEER_SEED)
            val sdk = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("zcash:$lookAlike?amount=0.001", 1)))
            assertFailsWith<TransactionProposalNotCreatedException> {
                source(sdk).createZip321Proposal(zashi(), URI, allowMultiPayment = false)
            }
        }

    /** Addresses are decoded, not compared as raw text; text that does not decode is refused (fail closed). */
    @Test
    fun a_recipient_that_does_not_decode_is_refused_even_when_the_text_is_identical() =
        runTest {
            val raw = "usynthetic-peer"
            val sdk = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("zcash:$raw?amount=0.001", 1)))
            assertFailsWith<TransactionProposalNotCreatedException> {
                source(sdk, read = listOf(shown.copy(recipient = raw))).createZip321Proposal(zashi(), URI, false)
            }
        }

    /** Bech32 forbids mixed case, so a mixed-case recipient is not an address. */
    @Test
    fun a_mixed_case_recipient_is_refused() =
        runTest {
            val mixed = PEER.replaceFirstChar { it.uppercaseChar() }
            val sdk = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("zcash:$PEER?amount=0.001", 1)))
            assertFailsWith<TransactionProposalNotCreatedException> {
                source(sdk, read = listOf(shown.copy(recipient = mixed))).createZip321Proposal(zashi(), URI, false)
            }
        }

    /** Control: an upper-case QR (Bech32 allows all upper case) is the same recipient as the SDK's lower case. */
    @Test
    fun an_upper_case_scanned_address_is_the_same_recipient() =
        runTest {
            val proposal = RequestProposalBytes.proposal(Step("zcash:$PEER?amount=0.001", 1))
            val upper = listOf(shown.copy(recipient = PEER.uppercase()))
            for (account in accounts()) {
                val result = source(ProposingSynchronizerProvider(proposal), read = upper).createZip321Proposal(account, URI, false)
                assertSame(proposal, result.proposal)
            }
        }

    /** Control: Sapling (Bech32) and transparent (Base58Check) recipients are matched too. */
    @Test
    fun sapling_and_transparent_recipients_that_match_are_accepted() =
        runTest {
            for (address in listOf(SAPLING_PEER, TRANSPARENT_PEER)) {
                val proposal = RequestProposalBytes.proposal(Step("zcash:$address?amount=0.001", 1))
                val result =
                    source(ProposingSynchronizerProvider(proposal), read = listOf(shown.copy(recipient = address)))
                        .createZip321Proposal(zashi(), URI, false)
                assertSame(proposal, result.proposal, address.take(4))
            }
        }

    @Test
    fun sapling_and_transparent_recipients_that_differ_are_refused() =
        runTest {
            val pairs = listOf(SAPLING_PEER to SAPLING_HIDDEN, TRANSPARENT_PEER to TRANSPARENT_HIDDEN, TRANSPARENT_PEER to PEER)
            for ((shownAddress, paid) in pairs) {
                val sdk = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("zcash:$paid?amount=0.001", 1)))
                assertFailsWith<TransactionProposalNotCreatedException>(shownAddress.take(4) + " -> " + paid.take(4)) {
                    source(sdk, read = listOf(shown.copy(recipient = shownAddress))).createZip321Proposal(zashi(), URI, false)
                }
            }
        }

    /** The multi-payment form the SDK writes is read for its recipient too. */
    @Test
    fun a_recipient_in_the_address_parameter_form_is_matched() =
        runTest {
            val ok = RequestProposalBytes.proposal(Step("zcash:?address=$PEER&amount=0.001", 1))
            assertSame(ok, source(ProposingSynchronizerProvider(ok)).createZip321Proposal(zashi(), URI, false).proposal)
            val other = ProposingSynchronizerProvider(RequestProposalBytes.proposal(Step("zcash:?address=$HIDDEN&amount=0.001", 1)))
            assertFailsWith<TransactionProposalNotCreatedException> {
                source(other).createZip321Proposal(zashi(), URI, false)
            }
        }

    /** A TEX payment pays its recipient in the second step; a second step paying someone else is refused. */
    @Test
    fun a_tex_payment_whose_second_step_pays_another_recipient_is_refused() =
        runTest {
            val sdk =
                ProposingSynchronizerProvider(
                    RequestProposalBytes.proposal(
                        Step("", payments = 0),
                        Step("zcash:$HIDDEN?amount=0.001", payments = 1, inputPool = 0, inputValue = 0),
                    ),
                )
            assertFailsWith<TransactionProposalNotCreatedException> {
                source(sdk).createZip321Proposal(zashi(), URI, false)
            }
        }

    // --- The request reader ---

    @Test
    fun the_request_reader_reads_the_forms_the_sdk_writes() {
        assertEquals(Zip321Totals(0, 0), readZip321Totals("zcash:"))
        assertEquals(Zip321Totals(1, 150_000_000), readZip321Totals("zcash:$PEER?amount=1.5"))
        assertEquals(Zip321Totals(1, 1), readZip321Totals("zcash:$PEER?amount=0.00000001&memo=AA&label=a%20b"))
        assertEquals(
            Zip321Totals(2, 100_050_000),
            readZip321Totals("zcash:?address=$PEER&amount=1&address.1=$HIDDEN&amount.1=0.0005&memo.1=AA"),
        )
    }

    @Test
    fun the_request_reader_refuses_what_it_cannot_read_one_way() {
        listOf(
            "bitcoin:$PEER?amount=1",
            "zcash:$PEER?amount=1&amount=2",
            "zcash:$PEER?address=$HIDDEN",
            "zcash:?amount.1=1",
            "zcash:$PEER?amount=1.123456789",
            "zcash:$PEER?amount=-1",
            "zcash:$PEER?amount=1e3",
            "zcash:?address.x=$PEER",
            "zcash:$PEER?amount",
        ).forEach { assertNull(readZip321Totals(it), it) }
    }

    @Test
    fun the_wire_reader_carries_each_step_request() {
        val bytes =
            RequestProposalBytes.bytes(
                listOf(Step("", 0), Step("zcash:$PEER?amount=0.001", 1)),
                fee = 10_000,
            )
        assertEquals(listOf("zcash:$PEER?amount=0.001"), readSweepShape(bytes)?.transactionRequests)
        assertEquals(false, readSweepShape(bytes).toString().contains(PEER), "requests stay out of the log line")
    }

    // --- Fixtures ---

    private fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

    private suspend fun zashi() =
        ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(0), balance(5_000_000)),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )

    private suspend fun keystone() =
        KeystoneAccount(
            sdkAccount = AccountFixture.new(),
            unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-keystone"), balance(0), balance(5_000_000)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-keystone"), Zatoshi(0)),
            isSelected = true,
        )

    private companion object {
        // R3-1: the recipient is decoded now, so the fixtures are checksum-valid synthetic addresses.
        const val PEER_SEED = 1
        val PEER = SyntheticUnifiedAddress.mainnet(PEER_SEED)
        val HIDDEN = SyntheticUnifiedAddress.mainnet(2)
        val PLATFORM = SyntheticUnifiedAddress.mainnet(3)
        val URI = "zcash:$PEER?amount=0.001"
        val SAPLING_PEER = SyntheticAddress.bech32("zs", 4)
        val SAPLING_HIDDEN = SyntheticAddress.bech32("zs", 5)
        val TRANSPARENT_PEER = SyntheticAddress.transparent(6)
        val TRANSPARENT_HIDDEN = SyntheticAddress.transparent(7)
    }

    /** Synthetic Sapling (Bech32) and transparent (Base58Check) addresses; random payloads, no real receiver. */
    private object SyntheticAddress {
        private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

        fun bech32(
            hrp: String,
            seed: Int
        ): String {
            val data = fiveBit(Random(seed).nextBytes(43))
            val mod = polymod(hrpExpand(hrp) + data + IntArray(6)) xor 1
            val checksum = IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
            return hrp + "1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
        }

        /** A mainnet P2PKH-shaped ("t1…") Base58Check string. */
        fun transparent(seed: Int): String {
            val payload = byteArrayOf(0x1C, 0xB8.toByte()) + Random(seed).nextBytes(20)
            val sha = MessageDigest.getInstance("SHA-256")
            val check = sha.digest(sha.digest(payload)).copyOf(4)
            val all = payload + check
            var n = BigInteger(1, all)
            val out = StringBuilder()
            val base = BigInteger.valueOf(58)
            while (n.signum() > 0) {
                val (q, r) = n.divideAndRemainder(base)
                out.append(BASE58[r.toInt()])
                n = q
            }
            all.takeWhile { it.toInt() == 0 }.forEach { _ -> out.append('1') }
            return out.reverse().toString()
        }

        private fun fiveBit(bytes: ByteArray): IntArray {
            val out = mutableListOf<Int>()
            var acc = 0
            var bits = 0
            for (b in bytes) {
                acc = (acc shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    out += (acc ushr bits) and 31
                }
            }
            if (bits > 0) out += (acc shl (5 - bits)) and 31
            return out.toIntArray()
        }

        private fun hrpExpand(hrp: String): IntArray = (hrp.map { it.code ushr 5 } + 0 + hrp.map { it.code and 31 }).toIntArray()

        private fun polymod(values: IntArray): Int {
            var chk = 1
            for (v in values) {
                val top = chk ushr 25
                chk = ((chk and 0x1ffffff) shl 5) xor v
                for (i in 0 until 5) {
                    if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
                }
            }
            return chk
        }
    }
}
