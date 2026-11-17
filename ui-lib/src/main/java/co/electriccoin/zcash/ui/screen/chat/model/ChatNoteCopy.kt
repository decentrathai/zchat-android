package co.electriccoin.zcash.ui.screen.chat.model

import android.content.Context
import androidx.annotation.StringRes

/**
 * The words of an in-chat system note ("You switched this chat to …"), in the phone's language.
 *
 * R5-4 (round 4 report 3): those notes are WRITTEN to the chat store, so their words are chosen when the
 * note happens, not when it is drawn — which means the view model needs the app's strings. It takes this
 * one-method resolver rather than a [Context]: the view model's JVM harnesses allocate it without calling
 * the constructor and cannot fake an Android [Context] at all, but they can hand it a lambda.
 *
 * A note written before this change, or on a phone that was in another language at the time, keeps the
 * words it was written with.
 */
fun interface ChatNoteCopy {
    fun get(
        @StringRes id: Int,
        vararg args: Any
    ): String

    companion object {
        fun from(context: Context): ChatNoteCopy =
            ChatNoteCopy { id, args -> context.getString(id, *args) }
    }
}
