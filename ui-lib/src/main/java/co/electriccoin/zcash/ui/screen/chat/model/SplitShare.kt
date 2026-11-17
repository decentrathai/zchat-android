package co.electriccoin.zcash.ui.screen.chat.model

/**
 * KI-3 — one person's share of a bill split in Request Payment's "Split a bill": [share] zatoshi is what the request
 * asks this contact for, the total divided by the number of people (the user included), rounded down to the zatoshi, so
 * the request never asks for more than a fair share. [remainder] is the zatoshi the rounding left out (0 to people − 1);
 * the dialog shows it when it is above 0. A request never moves the user's money: the payer's own Pay confirm shows the
 * exact amount before anything is paid.
 */
data class SplitShare(
    val share: Long,
    val remainder: Long
) {
    companion object {
        /** The fewest people a bill is split between: the user and this contact. */
        const val MIN_PEOPLE = 2

        /** The most people the counter offers. */
        const val MAX_PEOPLE = 20

        /**
         * The share of [totalZatoshi] split between [people], or null when there is nothing to ask for: a total that is
         * not above 0, a count outside [MIN_PEOPLE]..[MAX_PEOPLE], or a share below 1 zatoshi.
         */
        fun of(
            totalZatoshi: Long,
            people: Int
        ): SplitShare? {
            if (totalZatoshi <= 0L || people !in MIN_PEOPLE..MAX_PEOPLE) return null
            val share = totalZatoshi / people
            if (share < 1L) return null
            return SplitShare(share = share, remainder = totalZatoshi % people)
        }
    }
}
