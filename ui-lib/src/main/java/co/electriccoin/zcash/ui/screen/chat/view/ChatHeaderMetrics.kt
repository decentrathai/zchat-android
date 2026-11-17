package co.electriccoin.zcash.ui.screen.chat.view

/**
 * ZC-1755 — the chat header's bar height, from the lines it holds. The header texts carry their own line heights
 * (ChatDetailView: name 22 sp, address, hint and offline texts 16 sp, mode chip 14 sp); without them each text takes the
 * 28 sp line height of the app bar's title style, and three lines (name, chip row, nickname hint) do not fit the 64 dp bar.
 *
 * The bar holds the name line, the chip row (the lock's 24 dp box, taller only when its text is at a very large font) and,
 * on an unnamed chat, the nickname hint: one line up to a font scale of 1.5, two from there, where "Set nickname" no longer
 * fits beside the avatar. Never below the 64 dp default; 8 dp of air on top of the lines.
 */
private const val NAME_LINE_SP = 22f
private const val HINT_LINE_SP = 16f
private const val CHIP_TEXT_LINE_SP = 16f
private const val CHIP_ROW_MIN_DP = 24f
private const val DEFAULT_BAR_DP = 64f
private const val AIR_DP = 8f
private const val ONE_LINE_HINT_UP_TO_SCALE = 1.5f

fun chatHeaderBarHeightDp(
    fontScale: Float,
    hasHint: Boolean,
): Float {
    val chipRow = maxOf(CHIP_ROW_MIN_DP, CHIP_TEXT_LINE_SP * fontScale)
    val hintLines =
        when {
            !hasHint -> 0
            fontScale > ONE_LINE_HINT_UP_TO_SCALE -> 2
            else -> 1
        }
    val lines = (NAME_LINE_SP + hintLines * HINT_LINE_SP) * fontScale + chipRow
    return maxOf(DEFAULT_BAR_DP, lines + AIR_DP)
}
