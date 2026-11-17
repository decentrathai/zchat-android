package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ZC-1925 (Fable review of 3fc413a7c, now medium): AndroidCallRingPlayer wakes the screen with ACQUIRE_CAUSES_WAKEUP,
 * which an app targeting 34+ may use only with android.permission.TURN_SCREEN_ON; the manifest does not declare it.
 * Its log says "screen woken=true" when only the wake lock was acquired, which reads as proof that the screen turned on.
 *
 * Wanted: ui-lib's manifest declares TURN_SCREEN_ON, and the log says only that the wake lock was acquired.
 */
class Zc1925ScreenWakeDeclaresTurnScreenOnTest {
    @Test
    fun `the manifest declares TURN_SCREEN_ON for the screen wake`() {
        val manifest = ChatResourceText.source(MANIFEST)
        assertTrue(
            "<uses-permission android:name=\"android.permission.TURN_SCREEN_ON\" />" in manifest,
            "ZC-1925: ACQUIRE_CAUSES_WAKEUP needs TURN_SCREEN_ON on target 34+",
        )
    }

    @Test
    fun `the log does not claim the screen turned on`() {
        val player = ChatResourceText.source(RING_PLAYER)
        assertTrue("ACQUIRE_CAUSES_WAKEUP" in player, "the wake lock stays")
        assertTrue("screen woken=" !in player, "ZC-1925: a held wake lock is not proof that the screen turned on")
        assertTrue("wake lock acquired=" in player, "ZC-1925: log what is known")
    }

    private companion object {
        const val MANIFEST = "ui-lib/src/main/AndroidManifest.xml"
        const val RING_PLAYER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/AndroidCallRingPlayer.kt"
    }
}
