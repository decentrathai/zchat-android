package co.electriccoin.zcash.ui.screen.chat.crypto

import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.service.relayMessagePreview
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D3-1 (fix-all 2.13.0, train T5): the Quantum Shield signed claim, pure crypto (t5-specs SPECS §2.2, §2.3).
 *
 * The claim is the content of one relay DM:
 * `ZRCPT|zqs1|<state>|<commit>|<kc>|<seq>|<sig>` (exactly 7 '|'-separated parts), signed over
 * `zchat-qs-claim-v1|<signerPub>|<otherPub>|<state>|<commit>|<kc>|<seq>` with the per-chat E2E key.
 *
 * The new API ([QuantumShield.commitment], [QuantumShield.keyCheck], `QsClaim`) is reached by name through [Api], so
 * this file compiles before it exists. Goldens: `prep-20260928/d3/d3_golden.py`, an independent RFC 5869 script over
 * the same synthetic inputs as QuantumShieldConfirmationTest (PUB_A/PUB_B, SECRET_A/SECRET_B, and the bound/unbound
 * PSKs it pins). Synthetic keys only.
 */
class QuantumShieldClaimTest {
    private companion object {
        // The fixtures of QuantumShieldConfirmationTest / QuantumShieldMismatchTest (copied: they are private there).
        val PUB_A: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 3 + 1).toByte() })
        val PUB_B: String = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 5 + 2).toByte() })
        val SECRET_A = ByteArray(32) { it.toByte() }
        val SECRET_B = ByteArray(32) { (it + 100).toByte() }
        const val UNBOUND_GOLDEN = "af14c2e1bac457da14f581c41be17f1686aa0049b10463b3715c8a6c910eff88"
        const val BOUND_GOLDEN = "01e36d2f93263d86e83a9d96ffd9787f3f59fdf8c4c0fe6f93cc375f9d09825a"

        // d3_golden.py (re-run 29 and 30 Sep 2026).
        const val COMMIT_A_GOLDEN = "800cd05737249e32f1de06efd6c4709c"
        const val COMMIT_B_GOLDEN = "4c8adf66199c847ef5677f4130783f6e"
        const val KC_BOUND_GOLDEN = "85143d6729ec9227123a1c9cc0fa3cdd"
        const val KC_UNBOUND_GOLDEN = "c9813ee5272ab9f940aca56a0e08e976"

        const val CLAIM_CLASS = "co.electriccoin.zcash.ui.screen.chat.crypto.QsClaim"
        const val SEQ = 1_790_000_000_000L
    }

    /** The new API, by name. Unwraps InvocationTargetException so a thrown error shows as itself. */
    private object Api {
        private fun claimClass(): Class<*> = Class.forName(CLAIM_CLASS)

        private fun companion(): Any = checkNotNull(claimClass().getField("Companion").get(null))

        private fun invoke(
            target: Any,
            name: String,
            vararg args: Any?
        ): Any? {
            val method =
                target.javaClass.methods.singleOrNull { it.name == name && it.parameterCount == args.size }
                    ?: throw NoSuchMethodException("${target.javaClass.simpleName}.$name/${args.size}")
            return try {
                method.invoke(target, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        fun commitment(secret: ByteArray): String = invoke(QuantumShield, "commitment", secret) as String

        fun keyCheck(
            psk: ByteArray,
            bindingA: String,
            bindingB: String
        ): String = invoke(QuantumShield, "keyCheck", psk, bindingA, bindingB) as String

        /** An unsigned claim (sig ""): the data class constructor (state, commit, kc, seq, sig). */
        fun claim(
            state: String,
            commit: String,
            kc: String,
            seq: Long,
            sig: String = ""
        ): Any =
            claimClass()
                .getConstructor(
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    String::class.java
                ).newInstance(state, commit, kc, seq, sig)

        fun parse(line: String): Any? = invoke(companion(), "parse", line)

        fun nextSeq(
            last: Long,
            now: Long
        ): Long = invoke(companion(), "nextSeq", last, now) as Long

        fun encode(claim: Any): String = invoke(claim, "encode") as String

        fun signedString(
            claim: Any,
            signerPub: String,
            otherPub: String
        ): String = invoke(claim, "signedString", signerPub, otherPub) as String

        fun sign(
            claim: Any,
            privateKey: String,
            signerPub: String,
            otherPub: String
        ): Any = invoke(claim, "sign", privateKey, signerPub, otherPub)!!

        fun verify(
            claim: Any,
            signerPub: String,
            otherPub: String
        ): Boolean = invoke(claim, "verify", signerPub, otherPub) as Boolean

        fun field(
            claim: Any,
            name: String
        ): Any? = invoke(claim, "get" + name.replaceFirstChar { it.uppercase() })

        fun copy(
            claim: Any,
            state: String = field(claim, "state") as String,
            commit: String = field(claim, "commit") as String,
            kc: String = field(claim, "kc") as String,
            seq: Long = field(claim, "seq") as Long,
            sig: String = field(claim, "sig") as String
        ): Any = claim(state, commit, kc, seq, sig)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val boundPsk get() = unhex(BOUND_GOLDEN)
    private val unboundPsk get() = unhex(UNBOUND_GOLDEN)

    /** A well-formed signed line: state u, our commit, a key check, signed by fresh P-256 keys. */
    private fun validLine(): String {
        val a = E2EEncryption.generateKeyPair()
        val b = E2EEncryption.generateKeyPair()
        val claim = Api.claim("u", Api.commitment(SECRET_A), Api.keyCheck(boundPsk, a.publicKey, b.publicKey), SEQ)
        return Api.encode(Api.sign(claim, a.privateKey, a.publicKey, b.publicKey))
    }

    // ------------------------------------------------------------------ goldens

    @Test
    fun the_fixture_psks_are_the_ones_the_quantum_shield_derives() {
        // Ties the golden inputs to today's code: the kc goldens below are computed over exactly these PSKs.
        assertEquals(BOUND_GOLDEN, hex(QuantumShield.derivePSK(SECRET_A, SECRET_B, PUB_A, PUB_B)))
        assertEquals(UNBOUND_GOLDEN, hex(QuantumShield.derivePSK(SECRET_A, SECRET_B)))
    }

    @Test
    fun commitment_and_key_check_match_the_independent_goldens() {
        assertEquals(COMMIT_A_GOLDEN, Api.commitment(SECRET_A))
        assertEquals(COMMIT_B_GOLDEN, Api.commitment(SECRET_B))
        assertEquals(KC_BOUND_GOLDEN, Api.keyCheck(boundPsk, PUB_A, PUB_B))
        assertEquals(KC_BOUND_GOLDEN, Api.keyCheck(boundPsk, PUB_B, PUB_A), "both phones must get the same kc")
        assertEquals(KC_UNBOUND_GOLDEN, Api.keyCheck(unboundPsk, PUB_A, PUB_B))
    }

    @Test
    fun key_check_differs_for_another_psk_or_another_pair() {
        val kc = Api.keyCheck(boundPsk, PUB_A, PUB_B)
        assertNotEquals(kc, Api.keyCheck(unboundPsk, PUB_A, PUB_B))
        assertNotEquals(kc, Api.keyCheck(boundPsk, PUB_A, PUB_A))
        val pubC = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })
        assertNotEquals(kc, Api.keyCheck(boundPsk, PUB_A, pubC))
        // A key check is not the security code's input nor the PSK itself.
        assertFalse(hex(boundPsk).startsWith(kc))
    }

    @Test
    fun commitment_is_over_the_secret_bytes_so_padded_and_unpadded_forms_agree() {
        val padded = Base64.getDecoder().decode(QuantumShield.encodeSecret(SECRET_A, boundCapable = false))
        val unpadded = Base64.getDecoder().decode(QuantumShield.encodeSecret(SECRET_A, boundCapable = true))
        assertEquals(Api.commitment(padded), Api.commitment(unpadded))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(Api.commitment(SECRET_A)))
    }

    // ------------------------------------------------------------------ wire format

    @Test
    fun the_signed_string_puts_the_signer_first_and_covers_every_field() {
        val claim = Api.claim("u", COMMIT_A_GOLDEN, KC_BOUND_GOLDEN, 42L)
        assertEquals(
            "zchat-qs-claim-v1|$PUB_A|$PUB_B|u|$COMMIT_A_GOLDEN|$KC_BOUND_GOLDEN|42",
            Api.signedString(claim, PUB_A, PUB_B)
        )
        assertEquals(
            "zchat-qs-claim-v1|$PUB_B|$PUB_A|n|||7",
            Api.signedString(Api.claim("n", "", "", 7L), PUB_B, PUB_A)
        )
    }

    @Test
    fun encode_then_parse_round_trips_every_state() {
        val a = E2EEncryption.generateKeyPair()
        val b = E2EEncryption.generateKeyPair()
        val claims =
            listOf(
                Api.claim("n", "", "", SEQ),
                Api.claim("u", Api.commitment(SECRET_A), "", SEQ + 1),
                Api.claim("u", Api.commitment(SECRET_A), Api.keyCheck(boundPsk, a.publicKey, b.publicKey), SEQ + 2),
                Api.claim("p", Api.commitment(SECRET_B), Api.keyCheck(unboundPsk, a.publicKey, b.publicKey), SEQ + 3),
                Api.claim("p", Api.commitment(SECRET_B), "", 0L),
            )
        for (unsigned in claims) {
            val signed = Api.sign(unsigned, a.privateKey, a.publicKey, b.publicKey)
            val line = Api.encode(signed)
            assertTrue(line.startsWith("ZRCPT|zqs1|"), line)
            assertTrue(line.startsWith(ZMSGConstants.Prefixes.RECEIPT))
            assertEquals(7, line.split('|').size, line)
            val state = Api.field(signed, "state")
            val commit = Api.field(signed, "commit")
            val kc = Api.field(signed, "kc")
            val seq = Api.field(signed, "seq")
            val sig = Api.field(signed, "sig")
            assertEquals("ZRCPT|zqs1|$state|$commit|$kc|$seq|$sig", line)
            val parsed = assertNotNull(Api.parse(line), "a well-formed line must parse: state $state")
            assertEquals(signed, parsed)
            assertEquals(line, Api.encode(parsed))
            assertTrue(Api.verify(parsed, a.publicKey, b.publicKey))
        }
    }

    @Test
    fun the_parser_refuses_every_malformed_field() {
        val line = validLine()
        assertNotNull(Api.parse(line), "control: the valid line parses")
        val p = line.split('|')

        fun replaced(vararg changes: Pair<Int, String>): String {
            val parts = p.toMutableList()
            for ((index, value) in changes) parts[index] = value
            return parts.joinToString("|")
        }

        val hex32 = "0123456789abcdef0123456789abcdef"
        val bad =
            linkedMapOf(
                "6 parts" to p.dropLast(1).joinToString("|"),
                "8 parts" to "$line|x",
                "an extra empty part" to "$line|",
                "another prefix" to replaced(0 to "ZRCPX"),
                "another version" to replaced(1 to "zqs2"),
                "an unknown state" to replaced(2 to "x"),
                "an uppercase state" to replaced(2 to "U"),
                "an empty state" to replaced(2 to ""),
                "a 31-char commit" to replaced(3 to hex32.dropLast(1)),
                "a 33-char commit" to replaced(3 to hex32 + "0"),
                "an uppercase commit" to replaced(3 to hex32.uppercase()),
                "a non-hex commit" to replaced(3 to hex32.dropLast(1) + "g"),
                "a 31-char kc" to replaced(4 to hex32.dropLast(1)),
                "a 33-char kc" to replaced(4 to hex32 + "0"),
                "an uppercase kc" to replaced(4 to hex32.uppercase()),
                "a commit with state n" to replaced(2 to "n"),
                "an empty commit with state u" to replaced(3 to ""),
                "an empty commit with state p" to replaced(2 to "p", 3 to ""),
                "a 20-digit seq" to replaced(5 to "1".repeat(20)),
                "a 19-digit seq beyond Long" to replaced(5 to "9".repeat(19)),
                "a negative seq" to replaced(5 to "-5"),
                "a signed seq" to replaced(5 to "+5"),
                "an empty seq" to replaced(5 to ""),
                "a non-digit seq" to replaced(5 to "12a"),
                "a seq with a leading zero" to replaced(5 to "0$SEQ"),
                "a non-base64 sig" to replaced(6 to "***"),
                "a url-safe base64 sig" to replaced(6 to "ab-_"),
                "a 129-char sig" to replaced(6 to "A".repeat(129)),
                "a 132-char sig" to replaced(6 to "A".repeat(132)),
                "an empty sig" to replaced(6 to ""),
                "a sig with a line break" to replaced(6 to p[6].take(4) + "\n" + p[6].drop(4)),
                "a leading space" to " $line",
                "a trailing newline" to "$line\n",
                "a read receipt" to ZMSGProtocol.createReadReceipt("nmsg-target", "u1synthetic"),
                "only the prefix" to "ZRCPT|zqs1|",
            )
        for ((what, candidate) in bad) {
            assertNull(Api.parse(candidate), "the parser must refuse $what")
        }
    }

    // ------------------------------------------------------------------ sign / verify

    @Test
    fun a_signed_claim_verifies_only_for_its_signer_and_its_addressee() {
        val a = E2EEncryption.generateKeyPair()
        val b = E2EEncryption.generateKeyPair()
        val c = E2EEncryption.generateKeyPair()
        val unsigned = Api.claim("u", Api.commitment(SECRET_A), Api.keyCheck(boundPsk, a.publicKey, b.publicKey), SEQ)
        val signed = Api.sign(unsigned, a.privateKey, a.publicKey, b.publicKey)

        assertTrue(Api.verify(signed, a.publicKey, b.publicKey), "A's claim to B verifies with A's key")
        assertFalse(Api.verify(signed, c.publicKey, b.publicKey), "another signer")
        assertFalse(Api.verify(signed, b.publicKey, a.publicKey), "reflected: signer and other swapped")
        assertFalse(Api.verify(signed, a.publicKey, c.publicKey), "addressed to another chat key")
        assertFalse(Api.verify(unsigned, a.publicKey, b.publicKey), "an unsigned claim never verifies")

        // C signs a claim that names A as the signer: it does not verify with A's key.
        val forged = Api.sign(unsigned, c.privateKey, a.publicKey, b.publicKey)
        assertFalse(Api.verify(forged, a.publicKey, b.publicKey), "signed by C, named A")

        // Every single changed field breaks the signature.
        val changed =
            linkedMapOf(
                "state" to Api.copy(signed, state = "p"),
                "commit" to Api.copy(signed, commit = Api.commitment(SECRET_B)),
                "kc" to Api.copy(signed, kc = Api.keyCheck(unboundPsk, a.publicKey, b.publicKey)),
                "kc removed" to Api.copy(signed, kc = ""),
                "seq + 1" to Api.copy(signed, seq = SEQ + 1),
                "seq - 1" to Api.copy(signed, seq = SEQ - 1),
                "sig of another claim" to
                    Api.copy(
                        signed,
                        sig = Api.field(Api.sign(Api.claim("n", "", "", SEQ), a.privateKey, a.publicKey, b.publicKey), "sig") as String
                    ),
            )
        for ((what, claim) in changed) {
            assertFalse(Api.verify(claim, a.publicKey, b.publicKey), "a changed $what must not verify")
        }

        // The wire line of a changed claim parses and still fails verification.
        val tampered = Api.encode(signed).replace("|u|", "|p|")
        val parsed = assertNotNull(Api.parse(tampered))
        assertFalse(Api.verify(parsed, a.publicKey, b.publicKey))
    }

    @Test
    fun signing_twice_gives_two_signatures_that_both_verify() {
        // SHA256withECDSA is randomized: two lines of the same claim differ only in sig, and both verify.
        val a = E2EEncryption.generateKeyPair()
        val b = E2EEncryption.generateKeyPair()
        val unsigned = Api.claim("n", "", "", SEQ)
        val one = Api.sign(unsigned, a.privateKey, a.publicKey, b.publicKey)
        val two = Api.sign(unsigned, a.privateKey, a.publicKey, b.publicKey)
        assertTrue(Api.verify(one, a.publicKey, b.publicKey))
        assertTrue(Api.verify(two, a.publicKey, b.publicKey))
        assertTrue((Api.field(one, "sig") as String).length <= 128)
    }

    // ------------------------------------------------------------------ seq

    @Test
    fun next_seq_is_strictly_increasing_and_follows_the_clock() {
        assertEquals(6L, Api.nextSeq(5L, 3L))
        assertEquals(100L, Api.nextSeq(5L, 100L))
        assertEquals(6L, Api.nextSeq(5L, 5L))
        assertEquals(SEQ, Api.nextSeq(-1L, SEQ), "no stored seq yet")
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun the_line_never_carries_the_secret_or_the_psk() {
        val a = E2EEncryption.generateKeyPair()
        val b = E2EEncryption.generateKeyPair()
        val lines =
            listOf(
                Api.claim("u", Api.commitment(SECRET_A), Api.keyCheck(boundPsk, a.publicKey, b.publicKey), SEQ),
                Api.claim("p", Api.commitment(SECRET_A), Api.keyCheck(unboundPsk, a.publicKey, b.publicKey), SEQ),
            ).map { Api.encode(Api.sign(it, a.privateKey, a.publicKey, b.publicKey)) }
        val forbidden =
            listOf(SECRET_A, SECRET_B, boundPsk, unboundPsk).flatMap { bytes ->
                listOf(
                    Base64.getEncoder().encodeToString(bytes),
                    Base64.getEncoder().withoutPadding().encodeToString(bytes),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                    hex(bytes),
                    // Any 16-byte window of the value, in hex, is enough to rebuild half of it.
                    hex(bytes.copyOfRange(0, 16)),
                    hex(bytes.copyOfRange(16, 32)),
                )
            }
        for (line in lines) {
            for (value in forbidden) {
                assertFalse(line.lowercase().contains(value.lowercase()), "the claim line carries a secret or a PSK")
            }
        }
    }

    // ------------------------------------------------------------------ guards (fail at RED only for the missing encoder)

    @Test
    fun every_build_drops_the_claim_as_a_read_receipt_and_never_notifies_it() {
        // ZChat 2.12.4 drops a relay ZRCPT| before anything else (v2.12.4 ChatViewModel.kt:692,
        // `if (ZMSGProtocol.isReadReceipt(...)) return@collect`), and HEAD's relay path does the same; the relay
        // alert shows nothing for it (RelayMessageNotifier.isRelayControl). The claim keeps that prefix on purpose.
        val line = validLine()
        assertTrue(ZMSGProtocol.isReadReceipt(line))
        assertNull(relayMessagePreview(line))
    }
}
