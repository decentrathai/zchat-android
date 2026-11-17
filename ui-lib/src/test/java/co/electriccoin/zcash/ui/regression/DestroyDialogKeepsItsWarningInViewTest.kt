package co.electriccoin.zcash.ui.regression

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ZC-1226: on a short screen (360 dp, 1.3x font, 449 dp tall) the final Destroy dialog shows its red DESTROY ALL button
 * with the warning body mostly below the fold, and no sign that it scrolls. Wanted: the dialog reads its scroll state
 * (canScrollForward / maxValue) to show a hint or to keep the button off until the body has been read.
 */
class DestroyDialogKeepsItsWarningInViewTest {
    @Test
    fun `the final destroy dialog reacts to its scroll position`() {
        val src = PolSourceFiles.main("screen/chat/view/ChatListView.kt").readText()
        val start = src.indexOf("// Final Destroy Confirmation Dialog")
        assertTrue(start >= 0, "marker comment moved; update this pin")
        val end = src.indexOf("chat_destroy_final_confirm", start)
        val dialog = src.substring(start, end)
        assertTrue(
            "canScrollForward" in dialog || "scrollState.maxValue" in dialog || "scroll.maxValue" in dialog,
            "the final Destroy dialog scrolls (verticalScroll) but never reads canScrollForward/maxValue: no hint, button always on"
        )
    }
}
