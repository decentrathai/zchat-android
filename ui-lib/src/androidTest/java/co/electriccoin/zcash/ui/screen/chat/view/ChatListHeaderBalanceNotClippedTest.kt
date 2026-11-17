package co.electriccoin.zcash.ui.screen.chat.view

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.filters.MediumTest
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.test.UiTestPrerequisites
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import org.junit.Rule
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 P11 (PHONE_TRIAGE ES-3) — the Chats header cut the balance to "0.0".
 *
 * The balance sat on the same line as the avatar and the "ZChat" wordmark, in a `Text(maxLines = 1)` that wraps
 * by default. Next to the five action icons of a 392dp phone the title slot is too narrow for "0.01320586 ZEC", so
 * the text broke after "0.0" and maxLines hid the rest: the header read as a zero balance.
 *
 * The header is rendered here the way ChatListView shows it: a Material 3 TopAppBar at 392dp and density 2.625, a
 * 32dp avatar in front of the title, and five 48dp action buttons (Search, New Chat, New Group, My Address, Menu).
 * For font scale 1.0 and 1.3, in English and Spanish, the balance must be one line at 12sp, not cut on the right,
 * say the whole figure in the phone's format, and stay left of the first action icon and inside the bar.
 */
class ChatListHeaderBalanceNotClippedTest : UiTestPrerequisites() {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @MediumTest
    fun english_at_font_scale_1_0_shows_the_whole_balance() = assertWholeBalance(Locale.US, 1.0f, "0.01320586 ZEC")

    @Test
    @MediumTest
    fun english_at_font_scale_1_3_shows_the_whole_balance() = assertWholeBalance(Locale.US, 1.3f, "0.01320586 ZEC")

    @Test
    @MediumTest
    fun spanish_at_font_scale_1_0_shows_the_whole_balance() =
        assertWholeBalance(Locale.forLanguageTag("es-ES"), 1.0f, "0,01320586 ZEC")

    @Test
    @MediumTest
    fun spanish_at_font_scale_1_3_shows_the_whole_balance() =
        assertWholeBalance(Locale.forLanguageTag("es-ES"), 1.3f, "0,01320586 ZEC")

    // The header stops growing at font scale 1.3 (MAX_HEADER_FONT_SCALE), so a larger setting keeps the 1.3 layout.
    @Test
    @MediumTest
    fun english_at_font_scale_2_0_shows_the_whole_balance() = assertWholeBalance(Locale.US, 2.0f, "0.01320586 ZEC")

    @Test
    @MediumTest
    fun spanish_at_font_scale_2_0_shows_the_whole_balance() =
        assertWholeBalance(Locale.forLanguageTag("es-ES"), 2.0f, "0,01320586 ZEC")

    @OptIn(ExperimentalMaterial3Api::class)
    private fun assertWholeBalance(
        locale: Locale,
        fontScale: Float,
        expected: String
    ) {
        composeTestRule.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    setLocale(locale)
                    this.fontScale = fontScale
                    screenWidthDp = SCREEN_WIDTH_DP
                    densityDpi = DENSITY_DPI
                }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(DENSITY, fontScale),
            ) {
                ZcashTheme {
                    Box(Modifier.width(SCREEN_WIDTH_DP.dp)) {
                        TopAppBar(
                            modifier = Modifier.testTag(BAR_TAG),
                            title = {
                                ChatListHeaderTitle(
                                    balance = Zatoshi(BALANCE_ZATOSHI),
                                    zecPriceUsd = ZEC_PRICE_USD,
                                    onOpenWallet = {},
                                    leading = { Box(Modifier.size(32.dp)) },
                                )
                            },
                            actions = {
                                repeat(ACTION_COUNT) { index ->
                                    IconButton(onClick = {}) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            modifier =
                                                Modifier
                                                    .size(22.dp)
                                                    .then(if (index == 0) Modifier.testTag(FIRST_ICON_TAG) else Modifier)
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val node = composeTestRule.onNodeWithTag(CHAT_LIST_HEADER_BALANCE_TAG).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        val getLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertNotNull(getLayout, "the balance is not a text node")
        getLayout(results)
        val layout = results.single()
        val where = "$locale at font scale $fontScale"

        assertEquals(expected, layout.layoutInput.text.text, "$where: the balance text")
        assertEquals(1, layout.lineCount, "$where: the balance wraps")
        assertFalse(layout.didOverflowWidth, "$where: the balance is cut on the right")
        assertEquals(12.sp, layout.layoutInput.style.fontSize, "$where: the balance is not at 12sp")

        val balance = node.boundsInRoot
        val firstIcon = composeTestRule.onNodeWithTag(FIRST_ICON_TAG).fetchSemanticsNode().boundsInRoot
        val bar = composeTestRule.onNodeWithTag(BAR_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(balance.right <= firstIcon.left, "$where: the balance runs under the first action icon")
        assertTrue(
            balance.top >= bar.top && balance.bottom <= bar.bottom,
            "$where: the balance is outside the bar ($balance in $bar)"
        )
    }

    private companion object {
        const val BALANCE_ZATOSHI = 1_320_586L
        const val ZEC_PRICE_USD = 42.15
        const val SCREEN_WIDTH_DP = 392
        const val DENSITY = 2.625f
        const val DENSITY_DPI = 420
        const val ACTION_COUNT = 5
        const val BAR_TAG = "p11_bar"
        const val FIRST_ICON_TAG = "p11_first_icon"
    }
}
