package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageAuthentication
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * ZC-1943 reopened (Fable re-review of fix/2130-msg f58e91ef2, 16 Nov 2026; Important) — the expiry-window cap applied only
 * while the contact had no ratcheted row at all. Once one exists, every plaintext up to it stayed unmarked, however far
 * after our KEXACK; and a row counts as ratcheted from its "E2E1:" prefix alone (authenticated, never lowered when the
 * ratchet cannot open it), so a forger who mines one E2E1-prefixed garbage memo late in the chat unmarks every forged
 * plaintext before it.
 *
 * Wanted, through the history step (ChatViewModel.markPreSessionRows): a plaintext mined beyond session height + scan
 * margin + expiry window keeps "Sender not verified" also when a ratcheted (or merely E2E1-prefixed) row of the contact
 * is mined after it; inside the window the ZC-1910 exemption stays. Heights synthetic; MAINNET NU7 height 3,543,405.
 */
class ContactPlaintextWindowCapsTheRatchetBranchTest {
    private companion object {
        const val PEER = "usyntheticzc1943capratchet0001"
        const val CONV = "zc1943capconv"
        const val CONTACT_KEY = "synthetic-zc1943-contact-key"
        const val POST_NU7_SESSION = 3_600_000L
        const val PRE_NU7_SESSION = 3_500_000L
        const val FAR = 1_000L
        const val SIGHTING = "co.electriccoin.zcash.ui.screen.chat.viewmodel.KexAckSighting"
    }

    private class Ack(
        val height: Long,
        val own: Boolean,
    )

    private fun row(
        id: String,
        height: Long?,
        ratcheted: Boolean,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = "synthetic $id",
        timestamp = Instant.ofEpochSecond(1_700_000_000L),
        isOutgoing = false,
        peerAddress = PEER,
        minedHeight = height,
        authenticated = ratcheted,
    )

    /** The history pass's step over [rows] with the mined KEXACKs [acks]; each row id to whether it shows the chip. */
    private fun pass(
        rows: List<ChatMessage>,
        acks: List<Ack>,
    ): Map<String, Boolean> {
        val vm = allocate()
        val prefs =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, m, args ->
                when (m.name) {
                    "getPeerByConversationId" -> if (args?.get(0) == CONV) PEER else null
                    "getE2EPeerPublicKey" -> if (args?.get(0) == PEER) CONTACT_KEY else null
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "Zc1943CapPrefs"
                    else -> null
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", prefs)
        val constructor =
            Class
                .forName(SIGHTING)
                .declaredConstructors
                .single { it.parameterCount == 4 }
                .apply { isAccessible = true }
        // Our own KEXACK carries our key; the contact's is signed with the key we hold for them (trusted).
        val sightings = acks.map { constructor.newInstance(CONV, it.height, if (it.own) null else CONTACT_KEY, it.own) }
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
    fun `a plaintext beyond the window keeps the chip although a ratcheted row of the contact follows it`() {
        val chips =
            pass(
                listOf(
                    row("plain-early", POST_NU7_SESSION + 2, ratcheted = false),
                    row("plain-far", POST_NU7_SESSION + FAR, ratcheted = false),
                    row("late-e2e1", POST_NU7_SESSION + FAR + 100, ratcheted = true),
                ),
                listOf(Ack(POST_NU7_SESSION, own = true)),
            )

        assertEquals(mapOf("plain-early" to false, "plain-far" to true, "late-e2e1" to false), chips)
    }

    @Test
    fun `before NU7, a plaintext beyond the window keeps the chip although an E2E1-prefixed row follows it`() {
        val chips =
            pass(
                listOf(
                    row("plain-beyond", PRE_NU7_SESSION + 100, ratcheted = false),
                    row("forged-e2e1-prefix", PRE_NU7_SESSION + 400, ratcheted = true),
                ),
                listOf(Ack(PRE_NU7_SESSION, own = true)),
            )

        assertEquals(mapOf("plain-beyond" to true, "forged-e2e1-prefix" to false), chips)
    }
}
