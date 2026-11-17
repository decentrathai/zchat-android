package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Round 8 MA1, design test 3 (MEMBER_ANNOUNCE_DESIGN.md v2 §3, §4, §6.0) — on the announcer (the group admin), which
 * members get a number, and which (recipient, subject) pairs still owe an announce.
 *
 * - Only an ACTIVE member activated by an authenticated step (`AUTH`), with a secure link and a memo that fits, is
 *   numbered; INVITED, `UNAUTH`, no-secure-link and too-big rows are not.
 * - Subjects are deduplicated by the stored E2E key; a pair whose recipient and subject share a key is never owed.
 * - The debt rule: a pair is owed only when the current op differs from the op last delivered to it (CHAIN_SENT,
 *   CHAIN_PENDING, MAYBE_SENT or RELAY_FINAL). A new n with the same op owes nothing.
 * - A ledger entry matches when (rKey or rAddr) and (sKey or sAddr) match, so an alias change or a key reset of the
 *   recipient never turns a paid pair into an owed one.
 * - No A after an R unless the member was activated again after that R (a re-invite and a new join).
 * - `casAnnounceLedger`: RELAY_* never overwrites CHAIN_* (or MAYBE_SENT) for the same op, and CHAIN_PENDING is
 *   written only while the pair is owed.
 *
 * All addresses, keys and group ids are synthetic.
 */
class AnnounceOwedPairsTest {
    private val api = AnnounceApi

    private val keys =
        mutableMapOf(
            ADMIN to "key-admin",
            M1 to "key-m1",
            M2 to "key-m2",
            M3 to "key-m3",
            M3_OTHER_FORM to "key-m3",
            U1 to "key-u1",
            I1 to "key-i1",
            NL to "key-nl",
            BIG to "key-big",
        )
    private val canon: (String) -> String = { it }
    private val isSelf: (String) -> Boolean = { it == ADMIN }
    private val keyOf: (String) -> String? = { keys[it] }
    private val secure: (String) -> Boolean = { it != NL }

    private fun row(
        address: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        isAdmin: Boolean = false
    ) = GroupMember(address = address, joinedAt = Instant.ofEpochMilli(1_000L), status = status, isAdmin = isAdmin)

    private val roster =
        listOf(
            row(ADMIN, isAdmin = true),
            row(M1),
            row(M2),
            row(M3),
            row(M3_OTHER_FORM),
            row(U1),
            row(I1, MemberStatus.INVITED),
            row(NL),
            row(BIG),
        )

    private val auth = MaEntry(activatedAtMs = 10, activatedBy = MaBy.AUTH)
    private val start =
        MaState(
            members =
                mapOf(
                    M1 to auth,
                    M2 to auth,
                    M3 to auth,
                    M3_OTHER_FORM to auth,
                    U1 to MaEntry(activatedAtMs = 10, activatedBy = MaBy.UNAUTH),
                    I1 to auth,
                    NL to auth,
                    BIG to auth,
                ),
        )

    private fun number(
        state: MaState,
        roster: List<GroupMember> = this.roster,
        counter: Long = 0,
        now: Long = NOW,
    ) = api.assignAnnounceNumbers(GID, state, roster, counter, now, 0, EPOCH, canon, isSelf, keyOf, secure)

    private fun owed(
        state: MaState,
        ledger: List<MaLedger> = emptyList(),
        roster: List<GroupMember> = this.roster,
    ) = api.owedMemberAnnounces(state, roster, ledger, canon, isSelf, keyOf, secure)

    private fun hash(address: String) = api.announceKeyHash(keys.getValue(address))

    private fun entry(
        r: String,
        s: String,
        op: MaOp,
        state: MaLedgerState,
        at: Long = 1,
    ) = MaLedger(hash(r), r, hash(s), s, op, state, emptyList(), at)

    private fun pairsOf(owed: List<MaPair>) = owed.map { it.recipient to it.subject }.toSet()

    @Test
    fun `numbering skips INVITED, UNAUTH, no-secure-link and too-big rows and the admin itself`() {
        val numbered = number(start)
        val announced =
            numbered.state.members
                .filterValues { it.op != null }
                .keys
        assertEquals(setOf(M1, M2, M3), announced)
        assertEquals("one number per subject, from the wall clock up", NOW + 2, numbered.counter)
        assertEquals(
            listOf(NOW, NOW + 1, NOW + 2),
            listOf(M1, M2, M3).map {
                numbered.state.members
                    .getValue(it)
                    .n
            }
        )
        for (s in listOf(M1, M2, M3)) {
            val e = numbered.state.members.getValue(s)
            assertEquals(MaOp.A, e.op)
            assertEquals(EPOCH, e.epoch)
            assertEquals("the announced form is the row's own form", s, e.annForm)
        }
    }

    @Test
    fun `subjects are deduplicated by the stored key`() {
        val numbered = number(start)
        assertNull("M3's second address form is the same key: never a second subject", numbered.state.members[M3_OTHER_FORM]?.op)
        val subjects = owed(numbered.state).map { it.subject }.toSet()
        assertEquals(setOf(M1, M2, M3), subjects)
    }

    @Test
    fun `every eligible recipient is owed every other subject, and never a subject with its own key`() {
        val owed = owed(number(start).state)
        val expected =
            setOf(
                M2 to M1,
                M3 to M1,
                BIG to M1,
                M1 to M2,
                M3 to M2,
                BIG to M2,
                M1 to M3,
                M2 to M3,
                BIG to M3,
            )
        assertEquals(expected, pairsOf(owed))
        assertTrue(owed.none { it.rKey == it.sKey })
        assertTrue(owed.none { it.recipient == M3_OTHER_FORM })
        assertTrue("INVITED, UNAUTH and no-secure-link rows are never told", owed.none { it.recipient in setOf(I1, U1, NL, ADMIN) })
        assertTrue(owed.all { it.op == MaOp.A && it.epoch == EPOCH })
        val p = owed.single { it.recipient == M2 && it.subject == M1 }
        assertEquals(MaPair(M2, M2, hash(M2), M1, M1, hash(M1), MaOp.A, NOW, EPOCH), p)
    }

    @Test
    fun `a pair whose last delivered op is the current op is not owed, whatever its n`() {
        val numbered = number(start).state
        for (delivered in listOf(
            MaLedgerState.CHAIN_SENT,
            MaLedgerState.CHAIN_PENDING,
            MaLedgerState.MAYBE_SENT,
            MaLedgerState.RELAY_FINAL
        )) {
            val ledger = listOf(entry(M2, M1, MaOp.A, delivered))
            assertFalse("$delivered", pairsOf(owed(numbered, ledger)).contains(M2 to M1))
        }
        for (notDelivered in listOf(MaLedgerState.RELAY_UNCONFIRMED)) {
            assertTrue("$notDelivered", pairsOf(owed(numbered, listOf(entry(M2, M1, MaOp.A, notDelivered)))).contains(M2 to M1))
        }
        assertFalse(
            "a too-big pair is never owed",
            pairsOf(owed(numbered, listOf(entry(M2, M1, MaOp.A, MaLedgerState.TOO_BIG)))).contains(M2 to M1),
        )
    }

    @Test
    fun `a new n with the same op is not owed on-chain`() {
        val numbered = number(start).state
        val ledger = listOf(entry(M2, M1, MaOp.A, MaLedgerState.CHAIN_SENT))
        // A re-send with a new number (T5, T7, a restore) keeps the op: the pair stays paid.
        val bumped =
            numbered.copy(members = numbered.members + (M1 to numbered.members.getValue(M1).copy(n = NOW + 50_000)))
        assertFalse(pairsOf(owed(bumped, ledger)).contains(M2 to M1))
        // Numbering again with nothing changed issues no new number.
        val again = number(numbered, counter = NOW + 2, now = NOW + 60_000)
        assertEquals(numbered, again.state)
        assertEquals(NOW + 2, again.counter)
    }

    @Test
    fun `an alias change or a key reset of the recipient does not re-owe a CHAIN_SENT pair`() {
        val numbered = number(start).state
        val ledger = listOf(entry(M2, M1, MaOp.A, MaLedgerState.CHAIN_SENT))

        // Alias change: M2's row now carries another address form; the stored key is unchanged.
        keys[M2_ALIAS] = keys.getValue(M2)
        val aliased = roster.map { if (it.address == M2) it.copy(address = M2_ALIAS) else it }
        val aliasState = numbered.copy(members = numbered.members + (M2_ALIAS to auth))
        assertFalse(pairsOf(owed(aliasState, ledger, aliased)).contains(M2_ALIAS to M1))

        // Key reset: same address, new stored key.
        keys[M2] = "key-m2-after-reset"
        assertFalse(pairsOf(owed(numbered, ledger)).contains(M2 to M1))
    }

    @Test
    fun `an R is owed to the remaining members and to the member itself, even over a paid A`() {
        val numbered = number(start)
        val kicked = roster.map { if (it.address == M1) it.copy(status = MemberStatus.LEFT) else it }
        val renumbered = number(numbered.state, kicked, counter = numbered.counter, now = NOW + 10)
        val m1 = renumbered.state.members.getValue(M1)
        assertEquals(MaOp.R, m1.op)
        assertEquals(NOW + 10, m1.n)
        assertEquals("the form announced before is the form removed", M1, m1.annForm)

        val ledger = listOf(entry(M2, M1, MaOp.A, MaLedgerState.CHAIN_SENT))
        val owed = owed(renumbered.state, ledger, kicked).filter { it.subject == M1 }
        assertEquals(setOf(M2, M3, BIG, M1), owed.map { it.recipient }.toSet())
        assertTrue(owed.all { it.op == MaOp.R })
        assertTrue(
            "M1 is no longer told about anyone",
            owed(renumbered.state, ledger, kicked).none { it.recipient == M1 && it.subject != M1 }
        )
    }

    @Test
    fun `no A after an R without a new activation after that R`() {
        val numbered = number(start)
        val kicked = roster.map { if (it.address == M1) it.copy(status = MemberStatus.LEFT) else it }
        val removed = number(numbered.state, kicked, counter = numbered.counter, now = NOW + 10)
        val rN =
            removed.state.members
                .getValue(M1)
                .n

        // M1's row is ACTIVE again, but the only activation on record is older than the R (a replayed old accept).
        val stale = number(removed.state, roster, counter = removed.counter, now = NOW + 20)
        assertEquals(
            MaOp.R,
            stale.state.members
                .getValue(M1)
                .op
        )
        assertEquals(
            rN,
            stale.state.members
                .getValue(M1)
                .n
        )

        // A re-invite and a new signed join after the R: the member is announced again.
        val rejoined =
            removed.state.copy(
                members =
                    removed.state.members + (
                        M1 to
                            removed.state.members
                                .getValue(M1)
                                .copy(activatedAtMs = rN + 1_000)
                    ),
            )
        val back = number(rejoined, roster, counter = removed.counter, now = NOW + 30)
        assertEquals(
            MaOp.A,
            back.state.members
                .getValue(M1)
                .op
        )
        assertTrue(
            back.state.members
                .getValue(M1)
                .n > rN
        )
    }

    // ── the ledger compare-and-set ───────────────────────────────────────────────────────────────────────────

    private val pair = MaPair(M2, M2, "hash-m2", M1, M1, "hash-m1", MaOp.A, NOW, EPOCH)

    private fun led(
        op: MaOp,
        state: MaLedgerState,
        at: Long = 1
    ) = MaLedger("hash-m2", M2, "hash-m1", M1, op, state, emptyList(), at)

    @Test
    fun `RELAY never overwrites CHAIN or MAYBE_SENT for the same op`() {
        for (chain in listOf(MaLedgerState.CHAIN_PENDING, MaLedgerState.CHAIN_SENT, MaLedgerState.MAYBE_SENT)) {
            val current = led(MaOp.A, chain)
            for (relay in listOf(MaLedgerState.RELAY_FINAL, MaLedgerState.RELAY_UNCONFIRMED)) {
                assertNull("$relay over $chain", api.casAnnounceLedger(listOf(current), pair, current, led(MaOp.A, relay, at = 2)))
            }
        }
    }

    @Test
    fun `CHAIN_PENDING is written only while the pair is owed`() {
        val pending = led(MaOp.A, MaLedgerState.CHAIN_PENDING, at = 2)
        assertNotNull("absent: owed", api.casAnnounceLedger(emptyList(), pair, null, pending))
        val unconfirmed = led(MaOp.A, MaLedgerState.RELAY_UNCONFIRMED)
        assertNotNull("relay unconfirmed: owed", api.casAnnounceLedger(listOf(unconfirmed), pair, unconfirmed, pending))
        for (delivered in listOf(
            MaLedgerState.RELAY_FINAL,
            MaLedgerState.CHAIN_SENT,
            MaLedgerState.MAYBE_SENT,
            MaLedgerState.CHAIN_PENDING
        )) {
            val current = led(MaOp.A, delivered)
            assertNull("$delivered: not owed", api.casAnnounceLedger(listOf(current), pair, current, pending))
        }
        val oldOp = led(MaOp.R, MaLedgerState.CHAIN_SENT)
        assertNotNull("the op changed: owed again", api.casAnnounceLedger(listOf(oldOp), pair, oldOp, pending))
    }

    @Test
    fun `a compare-and-set against a stale expectation is refused and one entry per pair is kept`() {
        val current = led(MaOp.A, MaLedgerState.RELAY_UNCONFIRMED)
        assertNull(api.casAnnounceLedger(listOf(current), pair, null, led(MaOp.A, MaLedgerState.RELAY_FINAL)))
        assertNull(api.casAnnounceLedger(emptyList(), pair, current, led(MaOp.A, MaLedgerState.RELAY_FINAL)))

        val other = MaLedger("hash-m3", M3, "hash-m1", M1, MaOp.A, MaLedgerState.CHAIN_SENT, listOf("tx-1"), 1)
        val sent = led(MaOp.A, MaLedgerState.CHAIN_SENT, at = 3).copy(txids = listOf("tx-2"))
        val pendingNow = led(MaOp.A, MaLedgerState.CHAIN_PENDING, at = 2)
        val next = api.casAnnounceLedger(listOf(other, pendingNow), pair, pendingNow, sent)
        assertEquals(setOf(other, sent), next?.toSet())

        // A proven non-send puts the pair back to owed (the entry is removed), only against the pending entry.
        assertEquals(listOf(other), api.casAnnounceLedger(listOf(other, pendingNow), pair, pendingNow, null))
        assertNull(api.casAnnounceLedger(listOf(other, sent), pair, pendingNow, null))
    }

    @Test
    fun `a ledger entry matches by key or by address on each side`() {
        val byKeys = MaLedger("hash-m2", "u1someOtherFormOfM2", "hash-m1", "u1someOtherFormOfM1", MaOp.A, MaLedgerState.CHAIN_SENT)
        assertNull(
            "matched by keys: not owed",
            api.casAnnounceLedger(listOf(byKeys), pair, byKeys, led(MaOp.A, MaLedgerState.CHAIN_PENDING))
        )
        val byAddresses = MaLedger("old-key-m2", M2, "old-key-m1", M1, MaOp.A, MaLedgerState.CHAIN_SENT)
        assertNull(
            "matched by addresses: not owed",
            api.casAnnounceLedger(listOf(byAddresses), pair, byAddresses, led(MaOp.A, MaLedgerState.CHAIN_PENDING)),
        )
        val stranger = MaLedger("hash-x", "u1x", "hash-m1", M1, MaOp.A, MaLedgerState.CHAIN_SENT)
        assertNotNull(api.casAnnounceLedger(listOf(stranger), pair, null, led(MaOp.A, MaLedgerState.CHAIN_PENDING)))
    }

    private companion object {
        const val GID = "zgrp_0123456789abcdef01234567"
        const val NOW = 1_790_000_000_000L
        const val EPOCH = 3
        val ADMIN = "u1" + "a".repeat(60)
        val M1 = "u1" + "m".repeat(60)
        val M2 = "u1" + "n".repeat(60)
        val M2_ALIAS = "u1" + "p".repeat(60)
        val M3 = "u1" + "o".repeat(60)
        val M3_OTHER_FORM = "u1" + "r".repeat(60)
        val U1 = "u1" + "u".repeat(60)
        val I1 = "u1" + "i".repeat(60)
        val NL = "u1" + "l".repeat(60)
        val BIG = "u1" + "b".repeat(318)
    }
}
