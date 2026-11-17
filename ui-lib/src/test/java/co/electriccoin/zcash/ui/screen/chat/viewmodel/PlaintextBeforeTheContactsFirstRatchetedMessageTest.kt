package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageAuthentication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * ZC-1910 (owner test of release APK 17a900ce0411 at 65f4f1323, 16 Nov 2026; register high: a wrong trust warning
 * undermines real ones) — "Sender not verified — this message was not signed with your contact's secure key" on a
 * fresh HONOR ↔ Seeker Shielded chat, right after "Secure connection established".
 *
 * Code read: the chip shows on an incoming plaintext ("Zcash-only") body mined above the chat's session height (its
 * earliest trusted mined KEXACK; MessageAuthentication.markPreSession). The contact's app sends plaintext until it has
 * processed our KEXACK (sender side: zcashLayerOnly / appLayerE2EPending in ChatViewModel), so a message it built before
 * that, but that was mined after our KEXACK, is flagged although it could not have been signed. A false positive.
 *
 * Wanted (client-side, no wire change), through the history pass's own step (ChatViewModel.markPreSessionRows):
 * - a contact's plaintext row mined above the session height and at or below the height of the contact's FIRST
 *   ratcheted (E2E1) row in this chat, or with no E2E1 row from them yet, has no "Sender not verified" chip;
 * - a contact's plaintext row mined after their first E2E1 row keeps the chip (the real downgrade case), also for a
 *   contact whose E2E1 rows are old history (a 2.12.4-era session): as before;
 * - rows at or below the session height stay pre-session, unmined rows and chats with no trusted session height stay
 *   as before, our own rows never carry the chip, and the payment-request chip is unchanged (a ZREQ is always plain).
 *
 * Heights are synthetic and mirror the order on the phones; every address and conversation id is synthetic.
 */
class PlaintextBeforeTheContactsFirstRatchetedMessageTest {
    private companion object {
        const val PEER = "usyntheticzc1910plaintext00001"
        const val CONV = "zc1910conv"
        const val KEXACK_HEIGHT = 3_600_000L
        const val SIGHTING = "co.electriccoin.zcash.ui.screen.chat.viewmodel.KexAckSighting"
    }

    private fun row(
        id: String,
        height: Long?,
        ratcheted: Boolean,
        outgoing: Boolean = false,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = "synthetic $id",
        timestamp = Instant.ofEpochSecond(1_700_000_000L),
        isOutgoing = outgoing,
        peerAddress = PEER,
        minedHeight = height,
        authenticated = outgoing || ratcheted,
    )

    /** The history pass's step over [rows], with our own KEXACK mined at [kexAck] (null: no KEXACK seen). */
    private fun pass(
        rows: List<ChatMessage>,
        kexAck: Long? = KEXACK_HEIGHT,
    ): Map<String, Boolean> {
        val vm = allocate()
        val prefs =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, m, args ->
                when (m.name) {
                    "getPeerByConversationId" -> if (args?.get(0) == CONV) PEER else null
                    "getE2EPeerPublicKey" -> null
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "Zc1910Prefs"
                    else -> null
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", prefs)
        val sightingClass = Class.forName(SIGHTING)
        val constructor = sightingClass.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
        val sightings = listOfNotNull(kexAck?.let { constructor.newInstance(CONV, it, null, true) })
        val byPeer: MutableMap<String, MutableList<ChatMessage>> = mutableMapOf(PEER to rows.toMutableList())
        val step =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "markPreSessionRows" && it.parameterCount == 2 }
                .apply { isAccessible = true }
        try {
            step.invoke(vm, sightings, byPeer)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        return byPeer.getValue(PEER).associate { m ->
            m.id to
                (
                    MessageAuthentication.unverifiedChip(
                        isOutgoing = m.isOutgoing,
                        authenticated = m.authenticated,
                        isE2EReady = true,
                        preSession = m.preSession,
                        isPaymentRequest = false,
                    ) != null
                )
        }
    }

    private fun allocate(): ChatViewModel {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
    }

    @Test
    fun `a contact's plaintext built before it processed our KEXACK but mined after it has no chip`() {
        // The phones: our KEXACK at H; the contact's plaintext first message mined at H+2, its first E2E1 reply at H+6.
        val chips =
            pass(
                listOf(
                    row("plain-first", KEXACK_HEIGHT + 2, ratcheted = false),
                    row("plain-same-block", KEXACK_HEIGHT + 6, ratcheted = false),
                    row("first-e2e1", KEXACK_HEIGHT + 6, ratcheted = true),
                ),
            )

        assertEquals(mapOf("plain-first" to false, "plain-same-block" to false, "first-e2e1" to false), chips)
    }

    @Test
    fun `with no ratcheted message from the contact yet, its plaintext after the session has no chip`() {
        val chips =
            pass(
                listOf(
                    row("plain-1", KEXACK_HEIGHT + 1, ratcheted = false),
                    row("plain-2", KEXACK_HEIGHT + 30, ratcheted = false),
                ),
            )

        assertEquals(mapOf("plain-1" to false, "plain-2" to false), chips)
    }

    @Test
    fun `a plaintext message after the contact's first ratcheted one keeps the chip - the real downgrade`() {
        val chips =
            pass(
                listOf(
                    row("plain-early", KEXACK_HEIGHT + 1, ratcheted = false),
                    row("first-e2e1", KEXACK_HEIGHT + 4, ratcheted = true),
                    row("downgrade", KEXACK_HEIGHT + 5, ratcheted = false),
                    row("later-e2e1", KEXACK_HEIGHT + 9, ratcheted = true),
                    row("downgrade-later", KEXACK_HEIGHT + 20, ratcheted = false),
                ),
            )

        assertEquals(
            mapOf(
                "plain-early" to false,
                "first-e2e1" to false,
                "downgrade" to true,
                "later-e2e1" to false,
                "downgrade-later" to true,
            ),
            chips,
        )
    }

    @Test
    fun `control - a contact whose ratcheted messages are old history keeps the chip on a new plaintext, as before`() {
        // A 2.12.4-era session: E2E1 rows long before; then a plaintext row after the session height.
        val chips =
            pass(
                listOf(
                    row("old-e2e1", KEXACK_HEIGHT - 50_000 + 1, ratcheted = true),
                    row("plain-after", KEXACK_HEIGHT + 3, ratcheted = false),
                ),
                kexAck = KEXACK_HEIGHT - 50_000,
            )

        assertEquals(mapOf("old-e2e1" to false, "plain-after" to true), chips)
    }

    @Test
    fun `control - pre-session rows, unmined rows, own rows and a chat with no session stay as before`() {
        val rows =
            listOf(
                row("pre", KEXACK_HEIGHT - 1, ratcheted = false),
                row("unmined", null, ratcheted = false),
                row("own-plain", KEXACK_HEIGHT + 2, ratcheted = false, outgoing = true),
                row("e2e1", KEXACK_HEIGHT + 3, ratcheted = true),
                row("after", KEXACK_HEIGHT + 4, ratcheted = false),
            )
        assertEquals(
            mapOf("pre" to false, "unmined" to true, "own-plain" to false, "e2e1" to false, "after" to true),
            pass(rows),
        )
        // No trusted KEXACK: the chat has no session height; every incoming plaintext row keeps the chip, as before.
        assertEquals(
            mapOf("pre" to true, "unmined" to true, "own-plain" to false, "e2e1" to false, "after" to true),
            pass(rows, kexAck = null),
        )
    }

    @Test
    fun `a plain payment request before the contact's first ratcheted message loses only the sender chip and keeps its own`() {
        val marked = pass(listOf(row("zreq", KEXACK_HEIGHT + 2, ratcheted = false)))
        assertEquals(mapOf("zreq" to false), marked)
        assertEquals(
            co.electriccoin.zcash.ui.screen.chat.model.UnverifiedChip.PAYMENT_REQUEST,
            MessageAuthentication.unverifiedChip(
                isOutgoing = false,
                authenticated = false,
                isE2EReady = true,
                preSession = true,
                isPaymentRequest = true,
            ),
        )
        assertNull(MessageAuthentication.unverifiedChip(false, true, true, false, true))
    }
}
