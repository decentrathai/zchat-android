package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-23 (ZC-020, ZC-124; SPEC D1, D2, D4, D6) — the one pure decision behind "may the answer to a key request be paid
 * on-chain": never for a member who says it holds the current key, never a second time for the same member and key
 * (whatever else is true: there is no "may pay again" in 2.13.0), never without a unit of the daily budget.
 *
 * The object is reached by name: it does not exist before the fix.
 */
class GroupKeyGrantPolicyTest {
    private val policy: Any by lazy {
        val type =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.GroupKeyGrantPolicy") }.getOrNull()
                ?: throw AssertionError("there is no GroupKeyGrantPolicy: the paid route of a key request is decided nowhere")
        requireNotNull(type.getDeclaredField("INSTANCE").get(null))
    }

    /** "MayPay" or "RelayOnly(<reason>)". */
    private fun decide(
        have: Int,
        current: Int,
        paid: Int,
        budget: Boolean,
    ): String =
        requireNotNull(
            policy.javaClass.methods
                .single { it.name == "decide" }
                .invoke(policy, have, current, paid, budget),
        ).toString()

    private fun grantKey(
        group: String,
        requester: String,
        epoch: Int,
        canonicalize: (String) -> String,
    ): String =
        policy.javaClass.methods
            .single { it.name == "grantKey" && it.parameterCount == 4 }
            .invoke(policy, group, requester, epoch, canonicalize) as String

    @Test
    fun `a member behind the current key with no paid answer and budget left may be paid for`() {
        assertEquals("MayPay", decide(have = 0, current = 3, paid = 0, budget = true))
        assertEquals("MayPay", decide(have = 2, current = 3, paid = 0, budget = true))
    }

    @Test
    fun `a member who holds the current key is relay only whatever else is true`() {
        for (paid in 0..2) {
            for (budget in listOf(true, false)) {
                assertTrue(decide(have = 3, current = 3, paid = paid, budget = budget).startsWith("RelayOnly"))
                assertTrue(decide(have = 4, current = 3, paid = paid, budget = budget).startsWith("RelayOnly"))
            }
        }
        assertEquals("RelayOnly(reason=HOLDS_CURRENT_KEY)", decide(have = 3, current = 3, paid = 0, budget = true))
    }

    @Test
    fun `a key already paid for once is relay only whatever the budget says`() {
        for (paid in 1..3) {
            assertEquals("RelayOnly(reason=ALREADY_PAID)", decide(have = 0, current = 3, paid = paid, budget = true))
            assertEquals("RelayOnly(reason=ALREADY_PAID)", decide(have = 0, current = 3, paid = paid, budget = false))
        }
    }

    @Test
    fun `no budget is relay only`() {
        assertEquals("RelayOnly(reason=BUDGET_EXHAUSTED)", decide(have = 0, current = 3, paid = 0, budget = false))
    }

    @Test
    fun `the decision has no ledger input and no way to pay again`() {
        val decide = policy.javaClass.methods.single { it.name == "decide" }
        assertEquals(
            "decide takes more than the two key numbers, the count and the budget",
            listOf(Int::class.java, Int::class.java, Int::class.java, Boolean::class.java),
            decide.parameterTypes.toList(),
        )
        val routes =
            decide.returnType.declaredClasses
                .map { it.simpleName }
                .toSet()
        assertEquals("the routes are not exactly relay-only and may-pay", setOf("RelayOnly", "MayPay"), routes)
    }

    @Test
    fun `the grant key is one per group, canonical member and key number`() {
        val canonical: (String) -> String = { if (it == "usyntheticoldspelling") "usyntheticmember" else it }
        val key = grantKey("zgrp_a", "usyntheticmember", 3, canonical)
        assertEquals("zgrp_a|usyntheticmember|3", key)
        assertEquals("two spellings of one member are two keys", key, grantKey("zgrp_a", "usyntheticoldspelling", 3, canonical))
        assertNotEquals(key, grantKey("zgrp_a", "usyntheticmember", 4, canonical))
        assertNotEquals(key, grantKey("zgrp_b", "usyntheticmember", 3, canonical))
        assertNotEquals(key, grantKey("zgrp_a", "usyntheticother", 3, canonical))
    }
}
