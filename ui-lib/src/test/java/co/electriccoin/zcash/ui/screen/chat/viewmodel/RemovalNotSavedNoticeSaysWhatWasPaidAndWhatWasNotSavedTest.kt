package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ZC-791 sentence, as the tests expect it on the screen. */
internal object RemovalNotSavedNotice {
    const val KEY = "group_removal_not_saved"
    const val EN =
        "ZChat couldn't save this removal on this phone, so the member is still in the group here and wasn't told. " +
            "New-key messages already sent to the other members stay sent, and their fees stay paid. Free up space or " +
            "restart your phone, then remove the member again; ZChat asks before it pays anything."
    const val ES =
        "ZChat no pudo guardar en este teléfono que quitaste a este miembro, así que sigue en el grupo aquí y no se le " +
            "avisó. Los mensajes con la nueva clave del grupo que ya se enviaron a los demás miembros siguen enviados, y " +
            "sus comisiones siguen pagadas. Libera espacio o reinicia el teléfono y vuelve a quitar al miembro; ZChat " +
            "pregunta antes de pagar nada."
}

/**
 * ZC-791 (review of 12 Oct, read of 048d0296e) — a member removal whose roster write was refused AFTER the key
 * messages went out showed the notice of a paused paid step: "…it paused a paid chat step to make sure you are never
 * charged twice". In that state the key messages were already sent (and the on-chain ones paid), the member is still
 * in the group on this phone and was not told, and removing the member again sends a new key at its own price. The
 * state gets its own sentence, English and Spanish, and every part of it must be true:
 *
 * - what was not saved, and what that means here (the member is still in the group on this phone, and was not told);
 * - what was paid (key messages already sent stay sent and paid: no refund is promised, no second charge by itself);
 * - what to do (free up space or restart, then remove the member again; the app asks before it pays).
 *
 * It never says "never charged twice", "paused", "try again" or "retrying automatically": removing again is a new,
 * separately priced action, and nothing retries by itself (CreatorRemovalStopsWhenCombinedSaveFailsTest pins that).
 * The view-model side (which result the branch shows, the floor it adopts) is in that class and in
 * CreatorRemovalKeepsTheKeyItSentTest.
 */
class RemovalNotSavedNoticeSaysWhatWasPaidAndWhatWasNotSavedTest {
    @Test
    fun `the notice exists in English and Spanish, word for word`() {
        assertEquals(RemovalNotSavedNotice.EN, ChatResourceText.strings("values")[RemovalNotSavedNotice.KEY])
        assertEquals(RemovalNotSavedNotice.ES, ChatResourceText.strings("values-es")[RemovalNotSavedNotice.KEY])
    }

    @Test
    fun `the group banner finds the notice as a resource, so a Spanish phone shows it in Spanish`() {
        val id =
            runCatching { R.string::class.java.getField(RemovalNotSavedNotice.KEY).getInt(null) }.getOrNull()
                ?: throw AssertionError("R.string.${RemovalNotSavedNotice.KEY} does not exist")
        assertEquals(id, ChatSendCopy.localizedResource(RemovalNotSavedNotice.EN))
    }

    @Test
    fun `the notice promises nothing the code does not do, in either language`() {
        val en = ChatResourceText.strings("values")[RemovalNotSavedNotice.KEY].orEmpty()
        val es = ChatResourceText.strings("values-es")[RemovalNotSavedNotice.KEY].orEmpty()
        assertTrue("the English notice is missing", en.isNotBlank())
        assertTrue("the Spanish notice is missing", es.isNotBlank())
        listOf("never charged twice", "charged twice", "paused", "try again", "retry", "refund", "nothing was paid").forEach {
            assertFalse("the English notice says '$it'", en.contains(it, ignoreCase = true))
        }
        listOf("cobre dos veces", "pausó", "inténtalo", "reintent", "reembols", "no se pagó nada").forEach {
            assertFalse("the Spanish notice says '$it'", es.contains(it, ignoreCase = true))
        }
        listOf("still in the group", "wasn't told", "stay paid", "remove the member again", "asks before it pays").forEach {
            assertTrue("the English notice no longer says '$it'", en.contains(it))
        }
        listOf("sigue en el grupo", "no se le avisó", "siguen pagadas", "vuelve a quitar al miembro", "pregunta antes de pagar").forEach {
            assertTrue("the Spanish notice no longer says '$it'", es.contains(it))
        }
    }

    @Test
    fun `control - the paused-step notice keeps its own text for the states where nothing was paid`() {
        assertEquals(R.string.chat_storage_paused_paid_step, ChatSendCopy.localizedResource(ChatSendCopy.STORAGE_PAUSED_PAID_STEP))
        assertFalse(ChatSendCopy.STORAGE_PAUSED_PAID_STEP == RemovalNotSavedNotice.EN)
    }
}
