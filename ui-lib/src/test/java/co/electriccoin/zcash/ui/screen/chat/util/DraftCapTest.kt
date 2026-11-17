package co.electriccoin.zcash.ui.screen.chat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B9, SHARED-TEXT-CAP (found by audit, PIN audit RECOMMENDATION section 4.2).
 *
 * The chat composer caps typed and pasted text at 5,000 characters, but only in its onValueChange. Text shared
 * into ZChat from another app is stored as the chat's draft whole (ChatViewModel.saveDraft) and the composer
 * starts from that draft unchecked, so one tap on Send could send a message of up to 1,000 memo parts and burn
 * a large fee. The share path, the stored draft and the composer must all use ONE cap.
 */
class DraftCapTest {
    @Test
    fun `a shared text longer than the composer limit is trimmed to the limit`() {
        assertEquals(5000, capDraft("x".repeat(200_000)).length)
    }

    @Test
    fun `the trimmed text is the start of what was shared`() {
        val shared = "a".repeat(4_999) + "bcdef"
        assertEquals("a".repeat(4_999) + "b", capDraft(shared))
    }

    @Test
    fun `a short draft is unchanged`() {
        assertEquals("hola", capDraft("hola"))
    }

    @Test
    fun `a draft of exactly the limit is unchanged`() {
        val atLimit = "y".repeat(5000)
        assertEquals(atLimit, capDraft(atLimit))
    }

    @Test
    fun `an empty draft stays empty`() {
        assertEquals("", capDraft(""))
    }

    @Test
    fun `the cap is the composer's 5000 characters`() {
        assertEquals(5000, MAX_MESSAGE_INPUT_CHARS)
    }

    /**
     * The composer and the share path must read the same constant: the composer file may not keep a private
     * copy of the limit, and it must start from the capped draft rather than the stored one.
     */
    @Test
    fun `the composer uses the one shared cap and starts from the capped draft`() {
        val composer = source("view/ChatDetailView.kt")
        assertFalse(
            "ChatDetailView declares its own MAX_MESSAGE_INPUT_CHARS; the composer and the share path must share one",
            Regex("""const\s+val\s+MAX_MESSAGE_INPUT_CHARS""").containsMatchIn(composer),
        )
        assertFalse(
            "the composer starts from the stored draft unchecked (conversation.draft ?: \"\")",
            composer.contains("mutableStateOf(conversation.draft ?: \"\")"),
        )
        assertTrue(
            "the composer's initial text must be the capped draft",
            composer.contains("mutableStateOf(conversation.draft?.let(::capDraft) ?: \"\")"),
        )
    }

    // ------------------------------------------------------------------------------------------ ZC-135

    /*
     * ZC-135 (T7b triage 13 Oct, SMALL work order; a stored draft is cut and overwritten): at 828ed7c0a the composer
     * starts from the capped draft, says nothing about the cut, and its autosave (and the flush when the screen
     * closes) then writes the cut text over the longer stored draft before the user has touched it.
     * Wanted: the composer tells the user once, with the existing notice chat_composer_trimmed, that the draft was
     * cut; the cut text is not saved over the stored draft until the user changes it. The helpers are reached by
     * name so this file compiles before they exist; the screen is read as source (a Composable cannot run here).
     */

    @Test
    fun `a stored draft longer than the limit is reported as cut, a shorter one is not`() {
        assertTrue("a draft of 5001 characters is not reported as cut", draftWasCut("z".repeat(5001)))
        assertFalse(draftWasCut("z".repeat(5000)))
        assertFalse(draftWasCut(null))
    }

    @Test
    fun `the cut that the restore made of a longer stored draft is not saved over it`() {
        val stored = "q".repeat(7_000)

        assertFalse(
            "the autosave writes the cut text over the longer stored draft before the user edited it",
            draftNeedsSaving(capDraft(stored), stored),
        )
    }

    @Test
    fun `an edit of the cut draft, an emptied box and an ordinary change are saved`() {
        val stored = "q".repeat(7_000)

        assertTrue("an edit of the cut draft is not saved", draftNeedsSaving(capDraft(stored).dropLast(1) + "!", stored))
        assertTrue("an emptied box is not saved", draftNeedsSaving("", stored))
        assertTrue("an ordinary change is not saved", draftNeedsSaving("hola!", "hola"))
        assertFalse("an unchanged draft is saved again", draftNeedsSaving("hola", "hola"))
        assertFalse("an empty box over no draft is saved", draftNeedsSaving("", null))
    }

    @Test
    fun `the composer says once that the stored draft was cut and saves only what the user changed`() {
        val composer = source("view/ChatDetailView.kt")

        assertTrue(
            "the composer never tells the user that the stored draft was cut",
            Regex("""draftWasCut\(conversation\.draft\)[\s\S]{0,400}R\.string\.chat_composer_trimmed""").containsMatchIn(composer),
        )
        assertEquals(
            "the autosave and the flush on leaving do not both ask draftNeedsSaving",
            2,
            Regex("""draftNeedsSaving\(""").findAll(composer).count(),
        )
        assertFalse(
            "the autosave still saves every text that differs from the stored draft",
            composer.contains("if (messageText != (conversation.draft ?: \"\")) {"),
        )
    }

    private fun draftWasCut(stored: String?): Boolean = helper("draftWasCut", String::class.java).invoke(null, stored) as Boolean

    private fun draftNeedsSaving(
        current: String,
        stored: String?,
    ): Boolean = helper("draftNeedsSaving", String::class.java, String::class.java).invoke(null, current, stored) as Boolean

    private fun helper(
        name: String,
        vararg types: Class<*>,
    ): java.lang.reflect.Method =
        try {
            Class.forName("co.electriccoin.zcash.ui.screen.chat.util.DraftCapKt").getDeclaredMethod(name, *types)
        } catch (e: NoSuchMethodException) {
            throw AssertionError("DraftCap.kt has no $name: the composer has no rule for a cut draft ($e)")
        }

    private fun source(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        val root = dir ?: throw AssertionError("repo root not found")
        return File(root, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/$path").readText()
    }
}
