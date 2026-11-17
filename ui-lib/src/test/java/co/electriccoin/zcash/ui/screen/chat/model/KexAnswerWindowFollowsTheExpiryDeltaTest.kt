package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1554: the window between our own key exchange and an answer to it is our transaction's expiry delta at the
 * answer's height: 40 blocks before NU7, 120 from NU7 on (the fixed 40 dropped real post-NU7 answers as unasked).
 */
class KexAnswerWindowFollowsTheExpiryDeltaTest {
    private val nu7 = ChainTiming.MAINNET_NU7_HEIGHT
    private val main = ChainTiming.MAINNET

    @Test
    fun `before NU7 our exchange may be mined up to 40 blocks after the answer`() {
        val answer = nu7 - 1_000
        assertTrue(KexAnswerWindow.ownKexCovers(answer + 40, answer, main))
        assertFalse(KexAnswerWindow.ownKexCovers(answer + 41, answer, main))
        assertFalse(KexAnswerWindow.ownKexWaitIsOver(answer, answer + 40, main))
        assertTrue(KexAnswerWindow.ownKexWaitIsOver(answer, answer + 41, main))
    }

    @Test
    fun `from NU7 on our exchange may be mined up to 120 blocks after the answer`() {
        val answer = nu7 + 1_000
        assertTrue(KexAnswerWindow.ownKexCovers(answer + 41, answer, main))
        assertTrue(KexAnswerWindow.ownKexCovers(answer + 120, answer, main))
        assertFalse(KexAnswerWindow.ownKexCovers(answer + 121, answer, main))
        assertFalse(KexAnswerWindow.ownKexWaitIsOver(answer, answer + 41, main))
        assertFalse(KexAnswerWindow.ownKexWaitIsOver(answer, answer + 120, main))
        assertTrue(KexAnswerWindow.ownKexWaitIsOver(answer, answer + 121, main))
    }

    @Test
    fun `an exchange mined before the answer is always covered`() {
        assertTrue(KexAnswerWindow.ownKexCovers(nu7 + 5, nu7 + 10, main))
        assertTrue(KexAnswerWindow.ownKexCovers(nu7 - 50, nu7 - 10, main))
    }
}
