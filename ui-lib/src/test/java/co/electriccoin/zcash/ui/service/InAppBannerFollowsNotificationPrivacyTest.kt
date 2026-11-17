package co.electriccoin.zcash.ui.service

import co.electriccoin.zcash.ui.common.notification.InAppNotification
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 8 D9 (DECISIONS_NIGHT §11, p8-11ce796a05d3) — the in-app banner ignores the notification privacy setting.
 *
 * With the app in the foreground, postIncomingChatNotification shows the in-app banner with the contact's name and
 * the message text whatever NotificationPrivacy says; only the system notification (app in the background) follows
 * the setting. A user who chose "Sender only" or "New message" still gets the text on screen.
 *
 * Wanted: one pure mapping, the top-level inAppChatBanner(privacy, senderLabel, messagePreview, peerAddress) in
 * SyncForegroundService.kt, which the foreground branch uses:
 * - FULL_PREVIEW: the name and the text;
 * - SENDER_ONLY: the name and the existing fallback text ("Open ZCHAT to read");
 * - NEW_MESSAGE: the existing generic title ("New ZCHAT message") and the fallback text;
 * - SILENT: no banner.
 * The mapping is reached by name, so this file compiles before it exists. Names and addresses are synthetic.
 */
class InAppBannerFollowsNotificationPrivacyTest {
    @Test
    fun full_preview_shows_the_name_and_the_text() {
        assertEquals(InAppNotification(NAME, TEXT, PEER), banner(NotificationPrivacy.FULL_PREVIEW))
    }

    @Test
    fun sender_only_shows_the_name_and_the_fallback_text() {
        assertEquals(InAppNotification(NAME, FALLBACK, PEER), banner(NotificationPrivacy.SENDER_ONLY))
    }

    @Test
    fun new_message_shows_the_generic_title_and_the_fallback_text() {
        assertEquals(InAppNotification(GENERIC_TITLE, FALLBACK, PEER), banner(NotificationPrivacy.NEW_MESSAGE))
    }

    @Test
    fun silent_shows_no_banner() {
        assertNull(banner(NotificationPrivacy.SILENT))
    }

    @Test
    fun the_foreground_branch_uses_the_mapping() {
        val src = File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt").readText()
        val foreground = src.substringAfter("if (isInForeground) {").substringBefore("return\n")
        assertTrue("inAppChatBanner(" in foreground, "the foreground branch does not build its banner with inAppChatBanner")
        assertTrue("messagePreview = truncatedMessage" !in foreground, "the foreground banner still shows the text directly")
    }

    private fun banner(privacy: NotificationPrivacy): InAppNotification? {
        val method =
            Class
                .forName("co.electriccoin.zcash.ui.service.SyncForegroundServiceKt")
                .methods
                .firstOrNull { it.name.startsWith("inAppChatBanner") && it.parameterCount == 4 }
        assertNotNull(method, "SyncForegroundService.kt has no inAppChatBanner(privacy, senderLabel, messagePreview, peerAddress)")
        return method.invoke(null, privacy, NAME, TEXT, PEER) as InAppNotification?
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        return assertNotNull(dir, "repo root not found above the test working dir")
    }

    private companion object {
        const val NAME = "Synthetic Contact"
        const val TEXT = "synthetic message text"
        const val PEER = "usyntheticd9bannerpeer000001"

        // The strings the system notification already uses for these levels.
        const val FALLBACK = "Open ZCHAT to read"
        const val GENERIC_TITLE = "New ZCHAT message"
    }
}
