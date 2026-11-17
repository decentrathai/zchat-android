package co.electriccoin.zcash.ui.common.tex

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 W1; harm 5 of §1.1): the transaction detail offers "Send again" on a plain
 * Wallet send only when the chain shows it mined (Zashi's repeat payment) or proves it can never be mined.
 *
 * At 3df251d7f the detail's only gate is F3's texSendAgainAllowed(txId, check): true for every transaction the TEX
 * checker's last pass read and found in no TEX send. A plain send that is still unmined, and can still be mined, is
 * offered "Send again", which pays the same payee twice when the first one lands.
 *
 * Wanted: the checker's pass judges every plain send of the ZCHAT account with F4's rules (SendAttemptResolver and
 * SendAttemptProof): unmined and not proven dead reads as waiting, and "not sent" needs the scanned tip at or past the
 * expiry + 10 on two passes, the second at a strictly higher tip. A rewind starts the proof again. A mined send keeps
 * "Send again". The checker hands nothing to the server for a plain send. All txids and addresses are synthetic.
 */
class PlainSendAgainWaitsForTheChainTest {
    private companion object {
        const val E = 2_000L
        const val PAYEE = "u1fixtureplainpayeenotreal0001"

        /** An unmined plain send, a mined one, and one for the rewind (distinct in this class: the proof is per process). */
        val P: String = txid(0xB1)
        val M: String = txid(0xB5)
        val R: String = txid(0xB9)
        val N: String = txid(0xBD)
    }

    private fun onTor() = FakeTexTor().apply { on = true }

    private fun plain(
        txId: String,
        mined: Long?,
    ): Any =
        TexApi.facts(
            txId = txId,
            mined = mined,
            expiry = E,
            outputs = listOf(TexApi.output(PAYEE, isOwn = false, kind = "OTHER")),
        )

    private suspend fun passAt(
        monitor: Any,
        wallet: FakeTexWallet,
        tip: Long?,
    ): Any? {
        wallet.tip = tip
        TexApi.pass(monitor)
        return TexSameBlockFixture.check(monitor)
    }

    @Test
    fun an_unmined_plain_send_is_offered_again_only_once_proven_dead_on_two_rising_passes() =
        runBlocking {
            val wallet = FakeTexWallet().apply { transactions = listOf(plain(P, null), plain(M, E - 30)) }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            val steps = listOf(E - 5 to "before its expiry", E + 10 to "one pass at expiry + 10", E + 10 to "the same tip again")
            for ((tip, label) in steps) {
                val check = passAt(monitor, wallet, tip)
                assertFalse(TexSameBlockFixture.sendAgainAllowed(P, check), "Send again on a send that can still be mined ($label)")
                assertTrue(TexSameBlockFixture.sendAgainAllowed(M, check), "a mined send lost Send again ($label)")
            }
            val proven = passAt(monitor, wallet, E + 11)
            assertTrue(TexSameBlockFixture.sendAgainAllowed(P, proven), "a send proven dead on two rising passes gets no Send again")
            assertTrue(TexSameBlockFixture.sendAgainAllowed(M, proven), "a mined send lost Send again")

            assertEquals(emptyList(), wallet.resubmitted, "the checker handed a plain send to the server")
            assertTrue(wallet.calls.none { it in setOf("resubmit", "submitShield", "proposeShield", "lookup") }, "calls: ${wallet.calls}")
        }

    @Test
    fun a_rewind_between_the_passes_proves_nothing() =
        runBlocking {
            val wallet = FakeTexWallet().apply { transactions = listOf(plain(R, null)) }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            for (tip in listOf(E + 10, E + 9, E + 10)) {
                assertFalse(TexSameBlockFixture.sendAgainAllowed(R, passAt(monitor, wallet, tip)), "Send again after a rewind at $tip")
            }
        }

    @Test
    fun without_a_scanned_tip_nothing_is_offered_again() =
        runBlocking {
            val wallet = FakeTexWallet().apply { transactions = listOf(plain(N, null)) }
            val monitor = TexApi.monitor(wallet, onTor(), idle = { true })

            passAt(monitor, wallet, null)
            assertFalse(TexSameBlockFixture.sendAgainAllowed(N, passAt(monitor, wallet, null)), "Send again with no scanned tip")
        }
}
