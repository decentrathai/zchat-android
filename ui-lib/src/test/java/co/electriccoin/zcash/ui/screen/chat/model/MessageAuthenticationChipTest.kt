package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.Ciphertext
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.CiphertextWireFormat
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Instant

/**
 * Round 10b R10B-2 (F-R9C-3) — "Sender not verified" on the app's own genuine messages.
 *
 * ChatDetailView shows the chip when `isE2EReady && !message.authenticated && !isOutgoing`. `authenticated` is true
 * only for a ratcheted E2E1 body, and isE2EReady is the chat's CURRENT state, so once a session exists every earlier
 * plaintext memo gets the chip, including memos that could not have been signed when they were sent: on the two-phone
 * check the release's first message was mined at 3495576, before the contact's KEXACK at 3495581, and two old
 * payments before that; all three read "Sender not verified".
 *
 * Wanted:
 * - the session height of a chat is the height of its earliest mined KEXACK (either direction), known only when that
 *   earliest record is trusted (our own, or the contact's signed with the key we hold for them); unknown otherwise;
 * - a message mined at or below the session height is pre-session, and a plaintext pre-session message has no chip;
 * - a plaintext message above the session height, and any plaintext message when the height is unknown, keeps the
 *   chip (no security regression); a ratcheted body never has one;
 * - an unauthenticated incoming payment request (ZREQ is sent as a plain on-chain memo even in an E2E-ready chat, so
 *   anyone who knows the sender hash can forge one) gets its own chip that says why that matters;
 * - the chip texts are string resources in English and Spanish, and ChatDetailView reads the rule and the resources,
 *   no longer the hard-coded English UNVERIFIED_SENDER_CHIP.
 *
 * The new functions are reached by name, so this file compiles before they exist and fails on its own assertions.
 * Heights mirror the device check; all texts are synthetic.
 */
class MessageAuthenticationChipTest {
    private companion object {
        const val FIRST_MESSAGE_HEIGHT = 3_495_576L
        const val KEXACK_HEIGHT = 3_495_581L
        const val LATER_HEIGHT = 3_495_700L
        const val OLD_PAYMENT_HEIGHT = 3_400_000L
    }

    private val ratchetedBlob: String =
        CiphertextWireFormat.serialize(Ciphertext(direction = 0x00, counter = 3L, bytes = ByteArray(48) { it.toByte() }))

    private val auth: Any get() = MessageAuthentication

    private fun method(
        name: String,
        count: Int
    ): Method =
        MessageAuthentication::class.java.methods.firstOrNull { it.name == name && it.parameterCount == count }
            ?: run {
                fail("MessageAuthentication.$name ($count arguments) is missing")
                error("unreachable")
            }

    private fun call(
        name: String,
        vararg args: Any?
    ): Any? =
        try {
            method(name, args.size).invoke(auth, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    /** The session height from the trusted and the untrusted KEXACK heights of one chat. */
    private fun sessionHeight(
        trusted: List<Long>,
        untrusted: List<Long> = emptyList()
    ): Long? = call("sessionHeight", trusted, untrusted) as Long?

    private fun preSession(
        minedHeight: Long?,
        sessionHeight: Long?
    ): Boolean = call("isPreSession", minedHeight, sessionHeight) as Boolean

    private fun showsChip(
        isOutgoing: Boolean = false,
        authenticated: Boolean,
        isE2EReady: Boolean = true,
        preSession: Boolean,
    ): Boolean = call("showsUnverifiedChip", isOutgoing, authenticated, isE2EReady, preSession) as Boolean

    /** The chip kind's name (SENDER / PAYMENT_REQUEST), or null for no chip. */
    private fun chip(
        authenticated: Boolean,
        preSession: Boolean,
        isPaymentRequest: Boolean,
        isOutgoing: Boolean = false,
        isE2EReady: Boolean = true,
    ): String? = call("unverifiedChip", isOutgoing, authenticated, isE2EReady, preSession, isPaymentRequest)?.toString()

    private fun plaintext(rawMemoBody: String) = MessageAuthentication.isBodyAuthenticated(isOutgoing = false, rawMemoBody = rawMemoBody)

    @Test
    fun `a plaintext message mined at or below the session height has no chip`() {
        val session = sessionHeight(trusted = listOf(KEXACK_HEIGHT, LATER_HEIGHT))
        assertEquals("the session height is the earliest KEXACK", KEXACK_HEIGHT, session)
        assertFalse("fixture: the first message is plaintext", plaintext("synthetic first message"))

        for (height in listOf(OLD_PAYMENT_HEIGHT, FIRST_MESSAGE_HEIGHT, KEXACK_HEIGHT)) {
            val pre = preSession(height, session)
            assertTrue("a message mined at $height (session $session) is not pre-session", pre)
            assertFalse(
                "a plaintext message mined at $height, before the session existed, reads Sender not verified",
                showsChip(authenticated = false, preSession = pre),
            )
            assertNull(chip(authenticated = false, preSession = pre, isPaymentRequest = false))
        }
    }

    @Test
    fun `a plaintext message mined above the session height keeps the chip`() {
        val session = sessionHeight(trusted = listOf(KEXACK_HEIGHT))
        val pre = preSession(KEXACK_HEIGHT + 1, session)

        assertFalse(pre)
        assertTrue(showsChip(authenticated = false, preSession = pre))
        assertEquals("SENDER", chip(authenticated = false, preSession = pre, isPaymentRequest = false))
    }

    @Test
    fun `a ratcheted body never has a chip`() {
        val session = sessionHeight(trusted = listOf(KEXACK_HEIGHT))
        val authenticated = MessageAuthentication.isBodyAuthenticated(isOutgoing = false, rawMemoBody = ratchetedBlob)
        assertTrue("fixture: the ratcheted body is authenticated", authenticated)

        for (height in listOf(FIRST_MESSAGE_HEIGHT, LATER_HEIGHT)) {
            val pre = preSession(height, session)
            assertFalse(showsChip(authenticated = true, preSession = pre))
            assertNull(chip(authenticated = true, preSession = pre, isPaymentRequest = false))
            assertNull(chip(authenticated = true, preSession = pre, isPaymentRequest = true))
        }
    }

    @Test
    fun `with the session height unknown a plaintext message keeps the chip, as today`() {
        val unknown = sessionHeight(trusted = emptyList())
        assertNull("no KEXACK: the session height is known", unknown)
        // The earliest KEXACK is the contact's, signed with a key we do not hold for them (a rotation, or a forgery):
        // the height is unknown, never a later trusted one.
        assertNull(
            "an untrusted earliest KEXACK was skipped for a later trusted one",
            sessionHeight(trusted = listOf(LATER_HEIGHT), untrusted = listOf(KEXACK_HEIGHT)),
        )
        assertEquals(
            "an untrusted KEXACK above the trusted one changed the height",
            KEXACK_HEIGHT,
            sessionHeight(trusted = listOf(KEXACK_HEIGHT), untrusted = listOf(LATER_HEIGHT)),
        )

        for (height in listOf<Long?>(FIRST_MESSAGE_HEIGHT, LATER_HEIGHT, null)) {
            val pre = preSession(height, unknown)
            assertFalse(pre)
            assertTrue(showsChip(authenticated = false, preSession = pre))
        }
        // An unmined message is never pre-session.
        assertFalse(preSession(null, KEXACK_HEIGHT))
    }

    @Test
    fun `a plaintext payment request above the session height gets the payment-request chip`() {
        val session = sessionHeight(trusted = listOf(KEXACK_HEIGHT))
        assertTrue("fixture: ZREQ is a payment request", ZMSGProtocol.isPaymentRequest("ZREQ|10000|0123456789abcdef|synthetic"))
        val pre = preSession(LATER_HEIGHT, session)

        assertEquals("PAYMENT_REQUEST", chip(authenticated = false, preSession = pre, isPaymentRequest = true))
    }

    @Test
    fun `no chip on our own messages or before the chat has a session`() {
        assertFalse(showsChip(isOutgoing = true, authenticated = false, preSession = false))
        assertFalse(showsChip(isE2EReady = false, authenticated = false, preSession = false))
        assertNull(chip(authenticated = false, preSession = false, isPaymentRequest = true, isOutgoing = true))
        assertNull(chip(authenticated = false, preSession = false, isPaymentRequest = true, isE2EReady = false))
    }

    @Test
    fun `the history pass marks the messages mined at or below the session height`() {
        fun row(
            id: String,
            height: Long?
        ) = ChatMessage(
            id = id,
            txId = null,
            text = "synthetic $id",
            timestamp = Instant.ofEpochSecond(1_700_000_000L),
            isOutgoing = false,
            peerAddress = "usyntheticr10b2peer000000001",
            minedHeight = height,
            authenticated = false,
        )
        val rows =
            listOf(row("old", OLD_PAYMENT_HEIGHT), row("first", FIRST_MESSAGE_HEIGHT), row("after", LATER_HEIGHT), row("pending", null))

        @Suppress("UNCHECKED_CAST")
        val marked = call("markPreSession", rows, KEXACK_HEIGHT) as List<ChatMessage>
        val flags = marked.associate { it.id to (ChatMessage::class.java.getMethod("getPreSession").invoke(it) as Boolean) }

        assertEquals(mapOf("old" to true, "first" to true, "after" to false, "pending" to false), flags)
        @Suppress("UNCHECKED_CAST")
        val unknown = call("markPreSession", rows, null) as List<ChatMessage>
        assertTrue(unknown.none { ChatMessage::class.java.getMethod("getPreSession").invoke(it) as Boolean })
    }

    @Test
    fun `the chip texts are resources in English and Spanish`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals("Sender not verified: this message wasn't signed with your contact's secure key.", en["chat_unverified_sender"])
        assertEquals("Remitente no verificado: este mensaje no se firmó con la clave segura de tu contacto.", es["chat_unverified_sender"])
        assertEquals(
            "This payment request came as a plain on-chain memo, not signed with your contact's secure key. " +
                "Check it with your contact before you pay.",
            en["chat_unverified_payment_request"],
        )
        assertEquals(
            "Esta solicitud de pago llegó como un memo simple en la cadena, sin la firma de la clave segura de tu contacto. " +
                "Confírmala con tu contacto antes de pagar.",
            es["chat_unverified_payment_request"],
        )
    }

    @Test
    fun `ChatDetailView takes the chip from the rule and the resources, not the hard-coded English`() {
        val source = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt")

        assertTrue("ChatDetailView does not ask MessageAuthentication.unverifiedChip", "MessageAuthentication.unverifiedChip(" in source)
        assertTrue("ChatDetailView does not pass the message's preSession", "preSession = message.preSession" in source)
        assertTrue("ChatDetailView does not show chat_unverified_sender", "R.string.chat_unverified_sender" in source)
        assertTrue("ChatDetailView does not show chat_unverified_payment_request", "R.string.chat_unverified_payment_request" in source)
        assertFalse("ChatDetailView still reads the hard-coded English chip", "UNVERIFIED_SENDER_CHIP" in source)
        assertFalse("ChatDetailView still has its own chip rule", "isE2EReady && !message.authenticated && !isOutgoing" in source)
    }
}
