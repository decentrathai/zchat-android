package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-1276: the decision between a build's submit and its time limit has exactly one winner, the limit stays inside the hour
 * and beyond a legitimate build, and the new reason reads the same in both languages. The whole send path is pinned by
 * StalledSendNeverStaysSendingTest.
 */
class SendBuildDeadlineTest {
    @Test
    fun `a build past its limit never submits`() {
        val deadline = SendBuildDeadline()
        assertTrue(deadline.expire())
        assertTrue(deadline.expired)
        assertFalse("a settled build was allowed to submit", deadline.claimSubmit())
        assertTrue(deadline.expired)
    }

    @Test
    fun `a build whose submit was claimed is never settled as not sent`() {
        val deadline = SendBuildDeadline()
        assertTrue(deadline.claimSubmit())
        assertFalse("a send that may have gone out was settled as not sent", deadline.expire())
        assertFalse(deadline.expired)
        assertTrue("a second attempt of the same run lost its claim", deadline.claimSubmit())
    }

    @Test
    fun `the limit is over the five minute queue wait and well inside the hour`() {
        assertTrue(SEND_BUILD_LIMIT_MS > 5L * 60L * 1_000L)
        assertTrue(SEND_BUILD_LIMIT_MS <= 30L * 60L * 1_000L)
    }

    @Test
    fun `the reason is mapped, and says the same in both languages`() {
        val key = "chat_send_failed_build_too_long"
        val english = ChatResourceText.strings("values").getValue(key)
        val spanish = ChatResourceText.strings("values-es").getValue(key)
        assertEquals(ChatResourceText.stringId(key), ChatSendCopy.localizedResource(ChatSendCopy.FAILED_BUILD_TOO_LONG))
        assertEquals("the stored reason is not the English line", ChatSendCopy.FAILED_BUILD_TOO_LONG, english)
        assertNotEquals("the Spanish line is the English one", english, spanish)
        assertTrue(english, english.contains("nothing was paid") && english.contains("Tap Retry"))
        assertTrue(spanish, spanish.contains("no se pagó nada") && spanish.contains("Toca Reintentar"))
    }
}
