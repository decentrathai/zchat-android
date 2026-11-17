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
 * ZC-1943 (GPT and Fable cross-family reviews of fix/2130-msg 4efd9a54f, 16 Nov 2026; register high) — the ZC-1910 fix
 * drops "Sender not verified" on a contact's plaintext mined above the session height while the contact has sent no
 * ratcheted (E2E1) message, with no upper bound: a memo forged by anyone who knows the contact's address hash, mined days
 * later in a chat whose contact never sent an E2E1 row, shows no indication at all.
 *
 * Why the exemption exists: the contact's app sends plaintext until it has processed OUR KEXACK. So it holds only when
 * the session's earliest trusted KEXACK is our own (when the contact's KEXACK opened the session, its app already had the
 * session before it sent anything after it), and a plaintext it built before it saw our KEXACK is mined at most one
 * transaction expiry window (40 blocks before NU7, 120 from NU7 on) plus a short scan margin after our KEXACK.
 *
 * Wanted, through the history pass's own step (ChatViewModel.markPreSessionRows):
 * - with no ratcheted message from the contact, a plaintext mined just after our KEXACK has no chip, one mined beyond the
 *   expiry window plus margin keeps it (before NU7 and from NU7 on);
 * - when the contact's own (trusted) KEXACK is the earliest one, its plaintext after the session keeps the chip, also
 *   before its first E2E1 row;
 * - the bounded branch (before the contact's first E2E1 row, under our own KEXACK) stays as ZC-1910 made it.
 *
 * Heights are synthetic; MAINNET's NU7 height is 3,543,405 (ChainTiming). Every address, key and id is synthetic.
 */
class ContactPlaintextExemptionIsBoundedTest {
    private companion object {
        const val PEER = "usyntheticzc1943plaintext00001"
        const val CONV = "zc1943conv"
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
                    "toString" -> "Zc1943Prefs"
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
    fun `from NU7 on, a plaintext beyond the expiry window after our KEXACK keeps the chip`() {
        val chips =
            pass(
                listOf(
                    row("just-after", POST_NU7_SESSION + 30, ratcheted = false),
                    row("far-beyond", POST_NU7_SESSION + FAR, ratcheted = false),
                ),
                listOf(Ack(POST_NU7_SESSION, own = true)),
            )

        assertEquals(mapOf("just-after" to false, "far-beyond" to true), chips)
    }

    @Test
    fun `before NU7, a plaintext beyond the 40-block expiry window plus margin keeps the chip`() {
        val chips =
            pass(
                listOf(
                    row("inside", PRE_NU7_SESSION + 45, ratcheted = false),
                    row("beyond", PRE_NU7_SESSION + 100, ratcheted = false),
                ),
                listOf(Ack(PRE_NU7_SESSION, own = true)),
            )

        assertEquals(mapOf("inside" to false, "beyond" to true), chips)
    }

    @Test
    fun `when the contact's own KEXACK opened the session, its later plaintext keeps the chip`() {
        val chips =
            pass(
                listOf(
                    row("plain-no-e2e1", POST_NU7_SESSION + 2, ratcheted = false),
                ),
                listOf(Ack(POST_NU7_SESSION, own = false), Ack(POST_NU7_SESSION + 5, own = true)),
            )
        assertEquals(mapOf("plain-no-e2e1" to true), chips)

        val withE2e1 =
            pass(
                listOf(
                    row("plain-before-e2e1", POST_NU7_SESSION + 2, ratcheted = false),
                    row("first-e2e1", POST_NU7_SESSION + 6, ratcheted = true),
                ),
                listOf(Ack(POST_NU7_SESSION, own = false)),
            )
        assertEquals(mapOf("plain-before-e2e1" to true, "first-e2e1" to false), withE2e1)
    }

    @Test
    fun `control - under our own KEXACK the contact's plaintext before its first ratcheted message stays without a chip`() {
        val chips =
            pass(
                listOf(
                    row("plain-first", POST_NU7_SESSION + 2, ratcheted = false),
                    row("first-e2e1", POST_NU7_SESSION + 6, ratcheted = true),
                    row("downgrade", POST_NU7_SESSION + 7, ratcheted = false),
                ),
                listOf(Ack(POST_NU7_SESSION, own = true), Ack(POST_NU7_SESSION + 3, own = false)),
            )

        assertEquals(mapOf("plain-first" to false, "first-e2e1" to false, "downgrade" to true), chips)
    }
}
