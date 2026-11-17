package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-733-B (store-opener design, revision 2a, section 3.6 item 6; class B1, its part beside the chat views, case 5):
 * where the unreadable contact names and pictures are said. Source, comments stripped: the notice stands in the chat
 * list's banner column and above the three lists of saved contacts (New Chat, the group member pickers, the share
 * picker); the contact dialogs of the chat list ask the `try…` methods and, on a refusal, say so and write neither the
 * contact book nor the wallet's own Contacts list; New Chat's Add contact says the same sentence.
 */
class UnreadableNamesAreSaidOnTheScreensTest {
    private fun source(relative: String): String = StoreOpenerApi.mainSource("ui/screen/$relative")

    @Test
    fun `the notice is in the chat list's banner column and above the three pickers of saved contacts`() {
        val list = source("chat/view/ChatListView.kt")
        val banners = list.substringAfter("SeedBackupReminderBanner(", "")
        assertTrue(
            "the chat list has no UnreadableStoreNotice among its banners",
            "UnreadableStoreNotice(" in banners.take(BANNER_SPAN),
        )
        val pickers =
            listOf(
                "chat/view/ZchatComposeView.kt",
                "chat/view/CreateGroupView.kt",
                "chat/view/GroupSettingsView.kt",
                "chat/share/AndroidSharePicker.kt",
            )
        for (file in pickers) {
            assertTrue("$file shows no UnreadableStoreNotice", "UnreadableStoreNotice(" in source(file))
        }
    }

    @Test
    fun `the contact dialogs ask the try methods and on a refusal write neither book`() {
        val chat = source("chat/AndroidChat.kt")
        for (call in listOf("tryAddContact(", "tryUpdateContactName(", "tryRemoveContact(")) {
            assertTrue("AndroidChat.kt does not call $call", call in chat)
        }
        for (old in listOf("contactBook.addContact(", "contactBook.updateContactName(", "contactBook.removeContact(")) {
            assertFalse("AndroidChat.kt still calls $old", old in chat)
        }
        val add =
            chat
                .substringAfter("tryAddContact(")
                .substringBefore("Toast.makeText(context, context.getString(R.string.chat_list_contact_added)")
        assertTrue(
            "the Add dialog writes the wallet's Contacts list without the book's answer",
            Regex("""if \(\w+\)""").containsMatchIn(add),
        )
        assertTrue("AndroidChat.kt never says chat_list_contact_not_saved", "R.string.chat_list_contact_not_saved" in chat)
        val compose = source("chat/view/ZchatComposeView.kt") + source("chat/viewmodel/ZchatComposeVM.kt")
        assertTrue("New Chat's Add contact never says chat_list_contact_not_saved", "chat_list_contact_not_saved" in compose)
        assertTrue("New Chat's Add contact does not ask tryAddContact", "tryAddContact(" in source("chat/viewmodel/ZchatComposeVM.kt"))
        val onboarding = source("onboarding/viewmodel/OnboardingGuideVM.kt")
        assertTrue("onboarding's Team contact does not ask tryAddContact", "tryAddContact(" in onboarding)
    }

    private companion object {
        const val BANNER_SPAN = 2_000
    }
}
