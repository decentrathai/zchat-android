package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.PendingSendEstimate
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 10a R10A-2 (F-R9P-3, Seeker run of cf72159ad) — an upload never says it is on the Zcash network.
 *
 * At cf72159ad the pending line under an outgoing bubble was picked by the row's id prefix only: "tunnel-wait-" got the
 * Tunnel label, "nostr-out-" a hard-coded English "Sending…", and every other id fell to
 * PendingSendEstimate.labelRes, «Sending on the Zcash network — usually a few minutes». The optimistic rows of a
 * photo, a file and a voice note ("img-pending-", "file-pending-", "audio-pending-") fell into that on-chain branch,
 * so a file said it was on the Zcash network for its whole upload, in every mode, while nothing was on the chain.
 *
 * Wanted: one pure choice, PendingSendEstimate.rowLabelRes(messageId, txId, elapsedSeconds), that says
 * chat_pending_upload for an upload row, keeps the Tunnel and on-chain labels, and gives the relay send a localized
 * chat_pending_relay. The screen uses it.
 */
class UploadRowSaysUploadingTest {
    private fun rowLabelRes(): Method =
        try {
            PendingSendEstimate::class.java.getMethod(
                "rowLabelRes",
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
            )
        } catch (e: NoSuchMethodException) {
            throw AssertionError(
                "PendingSendEstimate has no rowLabelRes(messageId, txId, elapsedSeconds): the pending line of an upload " +
                    "row is still chosen by id prefix in the screen and says «Sending on the Zcash network»",
                e,
            )
        }

    private fun label(
        messageId: String,
        txId: String?,
        elapsedSeconds: Long,
    ): Int =
        try {
            rowLabelRes().invoke(PendingSendEstimate, messageId, txId, elapsedSeconds) as Int
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun id(name: String): Int = ChatResourceText.stringId(name) ?: fail("no string resource $name")

    @Test
    fun an_upload_row_says_it_is_uploading_never_that_it_is_on_the_zcash_network() {
        val uploading = id("chat_pending_upload")
        for (rowId in listOf("file-pending-1", "img-pending-1", "audio-pending-1")) {
            for (elapsed in listOf(5L, 400L)) {
                val got = label(rowId, null, elapsed)
                assertTrue(
                    got != R.string.chat_pending_onchain && got != R.string.chat_pending_onchain_slow,
                    "$rowId after ${elapsed}s says it is on the Zcash network (${ChatResourceText.stringName(got)})",
                )
                assertEquals(
                    uploading,
                    got,
                    "$rowId after ${elapsed}s: ${ChatResourceText.stringName(got)} instead of chat_pending_upload",
                )
            }
        }
    }

    @Test
    fun the_on_chain_tunnel_and_relay_rows_keep_their_own_labels() {
        assertEquals(R.string.chat_pending_onchain, label("pending_abc", null, 5L), "an on-chain send")
        assertEquals(R.string.chat_pending_onchain_slow, label("pending_abc", "tx1", 400L), "a slow on-chain send")
        assertEquals(R.string.chat_pending_tunnel, label("tunnel-wait-x", null, 5L), "a Tunnel message waiting")
        assertEquals(id("chat_pending_relay"), label("nostr-out-x", null, 5L), "a relay send")
    }

    @Test
    fun the_upload_and_relay_labels_have_english_and_spanish_text() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals("Uploading the attachment…", en["chat_pending_upload"])
        assertEquals("Subiendo el archivo adjunto…", es["chat_pending_upload"])
        assertEquals("Sending over the relays…", en["chat_pending_relay"])
        assertEquals("Enviando por los relays…", es["chat_pending_relay"])
    }

    @Test
    fun the_screen_uses_the_one_choice_and_no_english_relay_literal() {
        val source =
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")
        assertTrue("rowLabelRes(" in source, "ChatDetailView does not pick the pending line with rowLabelRes")
        assertFalse("isNostrPending -> \"Sending…\"" in source, "ChatDetailView still shows the English relay \"Sending…\"")
    }
}
