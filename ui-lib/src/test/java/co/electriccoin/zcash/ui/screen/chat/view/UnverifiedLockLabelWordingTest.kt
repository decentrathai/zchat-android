package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ZC-1715 (QA2 P3 polish; U7 second follow-up review, finding 2): `chat_a11y_encryption_unverified`, the TalkBack label
 * of the chat header's amber lock while the contact is not verified (ChatDetailView.kt, U7 follow-up nit 3), has no
 * wording test. Qa2ChatScreenCopyTest pins the other two lock labels (`chat_a11y_encryption_closed`, `..._pending`).
 *
 * Pinned here, EN + ES: the label keeps saying the chat is end-to-end encrypted (the lock is never "open", every mode is
 * encrypted) and adds that the contact is not verified. It is not the plain "closed" label, carries no placeholder, and
 * the screen reads it. The Spanish line is an agent draft (app-texts rule 9).
 *
 * A verification test: green at the base commit.
 */
class UnverifiedLockLabelWordingTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val key = "chat_a11y_encryption_unverified"

    @Test
    fun `the unverified lock label says encrypted and not verified, in English and Spanish`() {
        assertEquals("End-to-end encrypted, contact not verified", en[key])
        assertEquals("Cifrado de extremo a extremo, contacto sin verificar", es[key])
    }

    @Test
    fun `it is neither the plain encrypted label nor an untranslated copy, and has no placeholder`() {
        assertNotEquals(en["chat_a11y_encryption_closed"], en[key], "the unverified label is the verified one")
        assertNotEquals(es["chat_a11y_encryption_closed"], es[key], "the Spanish unverified label is the verified one")
        assertNotEquals(en[key], es[key], "the Spanish label is the English text")
        assertTrue('%' !in en.getValue(key) && '%' !in es.getValue(key), "the label carries a placeholder")
    }

    @Test
    fun `the lock never says open or closed in either language`() {
        for (text in listOf(en.getValue(key), es.getValue(key))) {
            val lower = text.lowercase()
            for (word in listOf("open", "closed", "abierto", "cerrado", "locked", "bloqueado")) {
                assertTrue(word !in lower, "the lock label says \"$word\": $text (the lock reads as encrypted, Q2-28)")
            }
        }
    }

    @Test
    fun `the chat header reads the label only for a closed lock of an unverified contact`() {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        val use = src.indexOf("R.string.chat_a11y_encryption_unverified")
        assertTrue(use >= 0, "ChatDetailView.kt never reads $key")
        val before = src.substring((use - 400).coerceAtLeast(0), use)
        assertTrue(
            "lockState == E2ELockState.CLOSED && headerUnverified" in before,
            "the label is not conditioned on a closed lock and an unverified contact: $before",
        )
    }
}
