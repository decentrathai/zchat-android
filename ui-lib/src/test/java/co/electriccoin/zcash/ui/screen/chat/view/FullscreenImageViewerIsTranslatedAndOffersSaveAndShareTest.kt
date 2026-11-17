package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1694 / Q2-66 (QA2 P3 polish; U8 review finding 1; FIX_LIST row Q2-66, owner W-137: fixed in 2.13.0): the full-screen
 * image viewer in ChatDetailView.kt (the `if (fullscreenImagePath != null)` dialog) has English-only texts on a Spanish
 * phone ("Fullscreen image", "Close", "Retry download", "Download failed ...", "Cannot preview this file") and offers Save
 * and Share only through the long-press menu of the bubble.
 *
 * Wanted:
 * 1. No English literal is left in that block: every text the viewer shows or describes is a string resource, EN + ES.
 * 2. The picture's description reads "Full-screen image" / "Imagen a pantalla completa" and the close button "Close" /
 *    "Cerrar" (FIX_LIST Q2-66).
 * 3. Save and Share icons sit beside the X, wired to the actions the bubble menu already has (saveFileToDownloads,
 *    shareFile), labelled with the existing Save / Share resources (EN "Save" / "Share", ES "Guardar" / "Compartir").
 *
 * Source and resource checks (the viewer is Compose; there is no Compose test host in this module). How the icons look
 * and what TalkBack reads: phone check in the QA repeat.
 */
class FullscreenImageViewerIsTranslatedAndOffersSaveAndShareTest {
    private val path = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val lexed by lazy { KotlinUiLiterals.lex(ChatResourceText.source(path)) }
    private val viewer by lazy { KotlinUiLiterals.block(lexed, "if (fullscreenImagePath != null) {") }
    private val viewerCode by lazy { lexed.blanked.substring(viewer.first, viewer.last + 1) }
    private val keys by lazy { Regex("""R\.string\.(\w+)""").findAll(viewerCode).map { it.groupValues[1] }.toSet() }

    @Test
    fun `the full-screen viewer shows no English literal`() {
        val offenders = KotlinUiLiterals.offenders(lexed, emptySet(), emptyList(), listOf(viewer))
        assertEquals(
            emptyList<String>(),
            offenders,
            "the full-screen image viewer (ChatDetailView.kt) still shows English literals; make each a resource, EN + ES",
        )
    }

    @Test
    fun `every string the viewer reads exists in English and in Spanish`() {
        assertTrue(keys.isNotEmpty(), "the viewer reads no string resource at all")
        for (key in keys) {
            assertNotNull(en[key], "values has no $key")
            assertNotNull(es[key], "values-es has no $key")
        }
    }

    @Test
    fun `the picture and the close button have their translated descriptions`() {
        val picture = keys.filter { en[it] == "Full-screen image" }
        assertTrue(picture.isNotEmpty(), "the viewer reads no resource whose English text is \"Full-screen image\"")
        assertTrue(picture.any { es[it] == "Imagen a pantalla completa" }, "the Spanish text must be \"Imagen a pantalla completa\"")
        val close = keys.filter { en[it] == "Close" }
        assertTrue(close.isNotEmpty(), "the viewer reads no resource whose English text is \"Close\"")
        assertTrue(close.any { es[it] == "Cerrar" }, "the Spanish text must be \"Cerrar\"")
    }

    @Test
    fun `Save and Share sit in the viewer beside the close button and use the bubble menu's actions`() {
        assertTrue("saveFileToDownloads(" in viewerCode, "the viewer has no Save (saveFileToDownloads): it is only in the long-press menu")
        assertTrue("shareFile(" in viewerCode, "the viewer has no Share (shareFile): it is only in the long-press menu")
        val save = keys.filter { en[it] == "Save" }
        val share = keys.filter { en[it] == "Share" }
        assertTrue(save.any { es[it] == "Guardar" }, "the Save icon is not labelled Save / Guardar")
        assertTrue(share.any { es[it] == "Compartir" }, "the Share icon is not labelled Share / Compartir")
        assertFalse(
            "Close" in lexed.literals.filter { it.offset in viewer }.map { it.text },
            "the viewer still describes its close button with the English literal \"Close\"",
        )
    }
}
