package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER

/**
 * The shared_prefs folder of one phone for the chat-copy harness (ChatStoreRestoreOutageRetryTest, and the F10
 * chat-copy back-off tests), with a settable clock.
 *
 * [tick] moves the clock on by one millisecond on every read, so each kept copy gets its own name (what the
 * harness has always done). F0 (fix-all 2.13.0): [advance] moves it on by a whole span, so a test can walk "the
 * next launch", "24 hours later" or "30 days later", and [now] can be set back to model a phone clock that went
 * back.
 */
internal class ChatCopyPhoneFiles {
    val byName = LinkedHashMap<String, RecordingPrefs>()
    var now = 100L

    fun file(name: String): SharedPreferences = byName.getOrPut(name) { RecordingPrefs(allowWrites = true) }

    fun entries(name: String): Map<String, Any?> = byName[name]?.entries.orEmpty()

    fun listing(): List<String> = byName.filterValues { it.entries.isNotEmpty() }.keys.map { "$it.xml" }

    fun copies(): List<String> = byName.keys.filter { it.contains(UNREADABLE_STORE_COPY_MARKER) }

    fun tick(): Long = ++now

    /** Moves the phone clock on by [millis] (never back: set [now] for that). */
    fun advance(millis: Long) {
        require(millis >= 0) { "advance() only moves the clock forward; set now to move it back" }
        now += millis
    }
}
