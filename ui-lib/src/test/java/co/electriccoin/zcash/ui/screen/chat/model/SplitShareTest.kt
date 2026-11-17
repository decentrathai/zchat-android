package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KI-3 — Request Payment's "Split a bill" asks this contact for floor(total / people) zatoshi: never more than a fair
 * share, the rounding shown as a remainder, and nothing to ask for when the share would be below 1 zatoshi or the
 * inputs are out of bounds.
 */
class SplitShareTest {
    @Test
    fun an_even_split_has_no_remainder() {
        assertEquals(SplitShare(share = 10_000_000L, remainder = 0L), SplitShare.of(30_000_000L, 3))
        assertEquals(SplitShare(share = 100_000L, remainder = 0L), SplitShare.of(300_000L, 3))
    }

    @Test
    fun an_uneven_split_is_rounded_down_and_the_remainder_is_kept() {
        assertEquals(SplitShare(share = 33_333_333L, remainder = 2L), SplitShare.of(100_000_001L, 3))
        assertEquals(SplitShare(share = 1L, remainder = 1L), SplitShare.of(3L, 2))
    }

    @Test
    fun the_share_never_exceeds_a_fair_share_and_nothing_is_lost() {
        for (total in listOf(1L, 2L, 7L, 999L, 100_000_001L, 2_100_000_000_000_000L)) {
            for (people in SplitShare.MIN_PEOPLE..SplitShare.MAX_PEOPLE) {
                val split = SplitShare.of(total, people) ?: continue
                assertTrue(split.share * people <= total, "$total / $people asks for more than the total")
                assertEquals(total, split.share * people + split.remainder, "$total / $people")
                assertTrue(split.remainder in 0 until people, "$total / $people remainder ${split.remainder}")
            }
        }
    }

    @Test
    fun a_share_below_one_zatoshi_is_refused() {
        assertNull(SplitShare.of(1L, 2))
        assertNull(SplitShare.of(2L, 3))
        assertNull(SplitShare.of(19L, 20))
    }

    @Test
    fun people_outside_two_to_twenty_are_refused() {
        assertNull(SplitShare.of(30_000_000L, 1))
        assertNull(SplitShare.of(30_000_000L, 0))
        assertNull(SplitShare.of(30_000_000L, -3))
        assertNull(SplitShare.of(30_000_000L, 21))
        assertEquals(2, SplitShare.MIN_PEOPLE)
        assertEquals(20, SplitShare.MAX_PEOPLE)
    }

    @Test
    fun a_total_not_above_zero_is_refused() {
        assertNull(SplitShare.of(0L, 2))
        assertNull(SplitShare.of(-30_000_000L, 3))
    }
}
