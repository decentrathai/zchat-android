package co.electriccoin.zcash.ui.common.tex

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 W2, §1.2 "Send again"): a plain Wallet send proven never mined is offered
 * "Send again" once. After the user used it, the detail never offers it again for that send, also after a restart.
 *
 * At 3df251d7f nothing records that "Send again" was used, and the checker has no `markSendAgainUsed`: every visit to
 * the detail of a dead send offers a new payment again, and two of them can both be mined.
 *
 * Wanted: `TexSendMonitor.markSendAgainUsed(txId)` writes the SHA-256 of the txid (never the txid) into the recovery
 * store and hides "Send again" for that send at once; a new monitor over the same store (a restart) keeps it hidden.
 * A mined send keeps Zashi's repeat "Send again" whatever was marked. All txids are synthetic.
 */
class PlainSendAgainOnlyOnceTest {
    private companion object {
        const val E = 3_000L
        const val PAYEE = "u1fixtureplainpayeenotreal0002"
        val P: String = txid(0xC3)
        val M: String = txid(0xC7)
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
        tip: Long,
    ): Any? {
        wallet.tip = tip
        TexApi.pass(monitor)
        return TexSameBlockFixture.check(monitor)
    }

    @Test
    fun send_again_on_a_proven_dead_send_is_offered_once_and_stays_used_after_a_restart() =
        runBlocking {
            val store = TexApi.memoryStore()
            val wallet = FakeTexWallet().apply { transactions = listOf(plain(P, null), plain(M, E - 30)) }
            val first = TexApi.monitor(wallet, onTor(), idle = { true }, store = store)
            passAt(first, wallet, E + 10)
            val proven = passAt(first, wallet, E + 11)
            assertTrue(TexSameBlockFixture.sendAgainAllowed(P, proven), "fixture: the dead send is offered Send again")

            TexApi.callSuspend(first, "markSendAgainUsed", P)
            assertFalse(
                TexSameBlockFixture.sendAgainAllowed(P, TexSameBlockFixture.check(first)),
                "Send again is still offered right after it was used",
            )

            val restarted = TexApi.monitor(wallet, onTor(), idle = { true }, store = store)
            passAt(restarted, wallet, E + 12)
            val afterRestart = passAt(restarted, wallet, E + 13)
            assertFalse(TexSameBlockFixture.sendAgainAllowed(P, afterRestart), "Send again came back after a restart")
            assertTrue(TexSameBlockFixture.sendAgainAllowed(M, afterRestart), "a mined send lost its repeat Send again")

            TexApi.callSuspend(restarted, "markSendAgainUsed", M)
            assertTrue(
                TexSameBlockFixture.sendAgainAllowed(M, passAt(restarted, wallet, E + 14)),
                "a mined send can be repeated any number of times",
            )

            @Suppress("UNCHECKED_CAST")
            val hashes = TexApi.call(store, "sendAgainUsedHashes") as Set<String>
            assertTrue(TexApi.recordKey(P) in hashes, "the store does not hold the SHA-256 of the used send")
            assertFalse(P in hashes, "the store holds a raw txid")
            assertTrue(hashes.all { it.matches(Regex("[0-9a-f]{64}")) }, "the store holds something other than hashes")
            assertTrue(TexApi.storeWrites(store).none { P in it.toString() }, "a recovery record names the txid")
        }
}
