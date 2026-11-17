package co.electriccoin.zcash.ui.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-1956 (GPT review of the ZC-1951 fix): both Answer PendingIntents of the incoming-call card use a fixed request code
 * and action, and the call id travels only in an extra. Android matches PendingIntents by Intent.filterEquals (action,
 * data, type, component, categories), not by extras, so FLAG_UPDATE_CURRENT rewrites the Answer of a card that is still
 * shown to the next call's id: a tap on call A's Answer then answers call B.
 *
 * Wanted: a pure `CallLockScreen.answerIdentity(callId)` gives each call its own data URI, null for a missing id, and
 * both Answer intents (the accept broadcast and the call-screen intent) set it as their data.
 */
class Zc1956AnswerPendingIntentIsPerCallTest {
    private val callA = "a1".repeat(16)
    private val callB = "b2".repeat(16)

    private fun identity(callId: String?): String? = CallSourceBlocks.objectCall(POLICY, "answerIdentity", callId) as String?

    @Test
    fun `each call gets its own Answer identity`() {
        val a = identity(callA)
        val b = identity(callB)
        assertTrue(a != null && callA in a, "ZC-1956: A's identity names A")
        assertNotEquals(a, b, "ZC-1956: A's and B's Answer must not be the same PendingIntent")
        assertEquals(a, identity(callA), "ZC-1956: stable for one call")
        assertNull(identity(null), "ZC-1956: no id, no identity")
    }

    @Test
    fun `both Answer intents carry the call's identity as their data`() {
        val notifications = CallSourceBlocks.source(CallSourceBlocks.NOTIFICATIONS)
        val uses = Regex("""CallLockScreen\.answerIdentity\(""").findAll(notifications).count()
        assertTrue(uses >= 2, "ZC-1956: the accept broadcast and the call-screen Answer both use answerIdentity ($uses)")
        assertTrue("setData(" in notifications || ".data =" in notifications, "ZC-1956: the identity is the intent's data")
    }

    private companion object {
        const val POLICY = "co.electriccoin.zcash.ui.call.CallLockScreen"
    }
}
