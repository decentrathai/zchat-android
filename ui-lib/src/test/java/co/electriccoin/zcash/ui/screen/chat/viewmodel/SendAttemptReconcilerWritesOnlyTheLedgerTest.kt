package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 R20 (e) (ZC-112) — source scan: the ledger's history pass writes only the ledger and its two chat-side
 * visibility effects.
 *
 * It writes no marker an automatic sender reads, no owed flag, no allowance and no paid record; and it takes no lock
 * but its own mutex, the wallet-scope lock of the one conditioned commit, and the held-reply lock of the Join record's
 * one write.
 */
class SendAttemptReconcilerWritesOnlyTheLedgerTest {
    private val pass = LedgerSources.region("F8-LEDGER-PASS")

    @Test
    fun `the pass names no marker, owed flag, allowance or paid-record write`() {
        for (forbidden in listOf(
            "setOwnBootSent",
            "setSentNostrBootPubkey",
            "setSentKexAckPubkey",
            "clearSentKexAckIfStill",
            "kexAckedKeys",
            "setKexOwed",
            "kexOwedPeers",
            "setZbootQuoted",
            "restoreQuotedZbootAsOwed",
            "recordZbootQuote",
            "withdrawZbootQuote",
            "setHandshakePaidRecord",
            "PaidSetupRecord",
            "setGroupKeyRequestGranted",
            "recordKeyOwed",
            "pendingKeyEpoch",
            "setConsentedToHandshakeSpend",
        )) {
            assertTrue("the ledger pass must never write `$forbidden`", !pass.contains(forbidden))
        }
    }

    @Test
    fun `its only writes outside the ledger are the Join record and one note`() {
        val writes =
            Regex("""zchatPreferences\.(set|add|commit|remove|clear|save|delete|store|mark|restore)\w*\(""")
                .findAll(pass)
                .map { it.value }
                .toList()
        assertEquals("the pass writes the store only through its two effects", emptyList<String>(), writes)
        assertEquals("one Join-record write", 1, Regex("""\bwriteGroupInviteNeedsJoin\(""").findAll(pass).count())
        assertEquals("one note write", 1, Regex("""\bemitSystemNote\(""").findAll(pass).count())
    }

    @Test
    fun `it takes its own mutex, the wallet-scope lock for the commit and the held-reply lock for one effect, and no other`() {
        assertEquals("its own mutex, once", 1, Regex("""sendAttemptLedgerMutex\.withLock""").findAll(pass).count())
        assertEquals("no other mutex", 1, Regex("""\.withLock\b""").findAll(pass).count())
        val synchronizedOn = Regex("""synchronized\(([^)]*)\)""").findAll(pass).map { it.groupValues[1] }.toSet()
        assertEquals("only the wallet-scope lock", setOf("ZchatPreferences.walletScopeLock"), synchronizedOn)
        assertEquals("the held-reply lock, for the Join record's one write", 1, Regex("""\bwithHeldAcceptsLock \{""").findAll(pass).count())
        assertTrue("never the group lock", !pass.contains("withGroupLock"))
    }
}
