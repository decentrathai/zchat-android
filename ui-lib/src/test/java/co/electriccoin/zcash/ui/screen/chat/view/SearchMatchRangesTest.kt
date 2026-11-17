package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the in-chat search highlighter.
 *
 * The shipped highlighter located matches in `text.lowercase()` and then sliced the ORIGINAL
 * string with those indices. That is only safe while lowercasing preserves length, and it does
 * not: Turkish dotted capital I (U+0130) lowercases to TWO characters, `i` + combining dot above
 * (U+0307). Past the first such character the two index spaces drift, and `substring(idx, idx +
 * len)` eventually runs past the end of the original string and throws
 * `StringIndexOutOfBoundsException` — crashing the chat screen for any user whose keyboard can
 * produce one, which is every Turkish keyboard.
 *
 * [searchMatchRanges] exists so this is testable at all: the logic used to live inside a private
 * `@Composable`, where it could only be exercised by driving a device.
 *
 * The invariant every case here asserts: returned ranges index the ORIGINAL string, so slicing it
 * with them can never go out of bounds.
 */
class SearchMatchRangesTest {
    /** Slicing with the returned ranges must be safe for any input — this is the crash itself. */
    private fun assertSliceable(text: String, query: String) {
        val ranges = searchMatchRanges(text, query)
        for (r in ranges) {
            assertTrue(
                r.first >= 0 && r.last < text.length,
                "range $r escapes text of length ${text.length} for query '$query'",
            )
            text.substring(r.first, r.last + 1) // must not throw
        }
    }

    @Test
    fun turkish_dotted_capital_i_does_not_escape_the_string() {
        // "İ".lowercase() is two chars, so the old lowercased-index approach drifted by one per
        // occurrence and eventually sliced past the end.
        val text = "İstanbul İzmir İnegöl"
        assertSliceable(text, "i")
        assertSliceable(text, "İ")
        assertSliceable(text, "İz")
    }

    @Test
    fun many_length_changing_characters_still_stay_in_bounds() {
        // Worst case for drift: the whole string is length-changing under lowercase().
        val text = "İİİİİİİİİİ"
        assertSliceable(text, "İ")
        assertEquals(10, searchMatchRanges(text, "İ").size)
    }

    @Test
    fun german_sharp_s_uppercase_is_length_changing_too() {
        // "ß".uppercase() is "SS"; the mirror of the Turkish case for anyone who folds the query.
        assertSliceable("Straße Ausßen", "ß")
        assertSliceable("STRASSE", "ss")
    }

    @Test
    fun matches_are_case_insensitive_and_index_the_original() {
        val text = "Hello HELLO hello"
        val ranges = searchMatchRanges(text, "hello")
        assertEquals(3, ranges.size)
        assertEquals(listOf("Hello", "HELLO", "hello"), ranges.map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun ranges_are_ascending_and_non_overlapping() {
        val text = "aaaa"
        val ranges = searchMatchRanges(text, "aa")
        assertEquals(listOf(0..1, 2..3), ranges)
        for (i in 1 until ranges.size) {
            assertTrue(ranges[i].first > ranges[i - 1].last, "ranges must not overlap")
        }
    }

    @Test
    fun blank_query_matches_nothing_rather_than_looping_forever() {
        // An empty needle makes indexOf return `start` every time; without the guard the caller
        // spins forever building an infinite annotated string.
        assertEquals(emptyList(), searchMatchRanges("anything", ""))
    }

    @Test
    fun no_match_returns_empty() {
        assertEquals(emptyList(), searchMatchRanges("hello world", "zzz"))
        assertEquals(emptyList(), searchMatchRanges("", "a"))
    }

    @Test
    fun match_at_the_very_end_is_in_bounds() {
        val text = "find me here"
        val ranges = searchMatchRanges(text, "here")
        assertEquals(1, ranges.size)
        assertEquals(text.length - 1, ranges[0].last)
        assertSliceable(text, "here")
    }

    @Test
    fun emoji_and_surrogate_pairs_do_not_break_slicing() {
        // Surrogate pairs make char indices and code points disagree; slicing must still be safe.
        val text = "hello 👋 world 👋"
        assertSliceable(text, "👋")
        assertSliceable(text, "world")
        assertEquals(2, searchMatchRanges(text, "👋").size)
    }
}
