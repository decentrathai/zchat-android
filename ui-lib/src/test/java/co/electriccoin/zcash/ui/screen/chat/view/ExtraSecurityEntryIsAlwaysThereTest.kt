package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1476 + ZC-1477 + ZC-445 + ZC-321 (owner findings 2 Nov, points 16 and 17; FIX_PLAN REVISION 1, B2; L5).
 *
 * Today the ⋮ entry "Enable Extra Security (Post-Quantum)" is drawn only when `conversation.isE2EReady` and the status
 * is NONE (ChatDetailView ~1020): one tap makes the setup PENDING and the entry disappears; it is absent before the key
 * exchange and never comes back for PENDING or ACTIVE. So the phone that scanned first (ACTIVE) can no longer show its
 * own QR and the other phone stays "Setting up" for good (ZC-445, ZC-1477), and a PENDING setup cannot be cancelled
 * (ZC-321). Setup is a mutual in-person scan: a phone shows its six digits only after it also scans the other phone's
 * code (no protocol change; Fable S8).
 *
 * Wanted, in `screen.chat.model` (file ExtraSecurityMenuState.kt):
 * `enum class ExtraSecurityMenuState(@StringRes val labelRes: Int, val enabled: Boolean, val showsMyCode: Boolean,
 * val offersCancel: Boolean) { ENABLE, CONTINUE, ON, NEEDS_KEY_EXCHANGE }` and
 * `fun extraSecurityMenuState(isE2EReady: Boolean, status: QuantumShieldStatus): ExtraSecurityMenuState`:
 * the entry is never hidden; NONE → ENABLE when E2E is ready, else NEEDS_KEY_EXCHANGE (shown, disabled, with the
 * reason); PENDING → CONTINUE (Show my code + Cancel); ACTIVE → ON (Show my code, turn off). "Show my code" reuses the
 * stored secret through initiateQuantumShield, which already does so at 669cf19e1 (QuantumShieldMismatchTest).
 * The scanner's code dialog says the next step (quantum_shield_setup_scan_next) and offers quantum_shield_code_show_mine.
 *
 * The new API is reached by name, so this file compiles before it exists.
 */
class ExtraSecurityEntryIsAlwaysThereTest {
    private val stateEnum: Class<*> by lazy {
        runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.ExtraSecurityMenuState") }.getOrElse {
            throw AssertionError("no enum co.electriccoin.zcash.ui.screen.chat.model.ExtraSecurityMenuState: the entry is still NONE-only")
        }
    }

    private fun state(
        isE2EReady: Boolean,
        status: QuantumShieldStatus,
    ): Enum<*> {
        val method =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.ExtraSecurityMenuStateKt") }
                .getOrNull()
                ?.methods
                ?.firstOrNull { it.name == "extraSecurityMenuState" && it.parameterCount == 2 }
        assertNotNull(method, "ExtraSecurityMenuState.kt has no extraSecurityMenuState(isE2EReady, status)")
        return assertNotNull(method.invoke(null, isE2EReady, status) as? Enum<*>, "the entry is hidden for ready=$isE2EReady $status")
    }

    private fun Enum<*>.flag(getter: String): Boolean = javaClass.getMethod(getter).invoke(this) as Boolean

    private fun Enum<*>.label(): Int = javaClass.getMethod("getLabelRes").invoke(this) as Int

    @Test
    fun the_menu_has_the_four_states() {
        assertEquals(
            listOf("ENABLE", "CONTINUE", "ON", "NEEDS_KEY_EXCHANGE"),
            stateEnum.enumConstants.orEmpty().map { (it as Enum<*>).name },
        )
    }

    @Test
    fun every_status_keeps_the_entry_with_its_own_label() {
        assertEquals("ENABLE", state(true, QuantumShieldStatus.NONE).name)
        assertEquals("NEEDS_KEY_EXCHANGE", state(false, QuantumShieldStatus.NONE).name, "before the key exchange: shown with the reason")
        for (ready in listOf(true, false)) {
            assertEquals("CONTINUE", state(ready, QuantumShieldStatus.PENDING).name, "PENDING (ready=$ready) must be resumable")
            assertEquals("ON", state(ready, QuantumShieldStatus.ACTIVE).name, "ACTIVE (ready=$ready) must stay reachable")
        }
    }

    @Test
    fun pending_and_on_show_my_code_and_pending_can_be_cancelled() {
        val pending = state(true, QuantumShieldStatus.PENDING)
        val on = state(true, QuantumShieldStatus.ACTIVE)
        assertTrue(pending.flag("getShowsMyCode"), "PENDING must offer Show my code (ZC-1477)")
        assertTrue(on.flag("getShowsMyCode"), "ACTIVE must offer Show my code, so the other phone can still scan it (ZC-445)")
        assertTrue(pending.flag("getOffersCancel"), "a PENDING setup must be cancellable (ZC-321)")
        assertFalse(on.flag("getOffersCancel"), "ACTIVE is turned off, not cancelled")
        val enable = state(true, QuantumShieldStatus.NONE)
        val needsKex = state(false, QuantumShieldStatus.NONE)
        assertTrue(enable.flag("getEnabled"))
        assertFalse(needsKex.flag("getEnabled"), "Extra Security cannot start before the key exchange")
        assertFalse(enable.flag("getShowsMyCode") || needsKex.flag("getShowsMyCode"), "NONE has no code to show yet")
    }

    @Test
    fun each_state_has_its_own_label_in_english_and_spanish() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val names =
            stateEnum.enumConstants.orEmpty().map { constant ->
                val id = (constant as Enum<*>).label()
                val name = assertNotNull(ChatResourceText.stringName(id), "${constant.name} has no string resource")
                val english = assertNotNull(en[name], "values has no $name")
                val spanish = assertNotNull(es[name], "values-es has no $name")
                assertNotEquals(english, spanish, "$name is not translated")
                name
            }
        assertEquals(names.size, names.toSet().size, "two states share a label: $names")
    }

    @Test
    fun the_scanners_code_dialog_tells_the_next_step_and_offers_show_my_code() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in listOf("quantum_shield_setup_scan_next", "quantum_shield_code_show_mine")) {
            val english = assertNotNull(en[key], "values has no $key")
            assertNotEquals(english, assertNotNull(es[key], "values-es has no $key"), "$key is not translated")
        }
        val chat = SourceScan.stripComments(ChatResourceText.source(ANDROID_CHAT))
        val at = chat.indexOf("R.string.quantum_shield_code_title")
        assertTrue(at >= 0, "the code dialog was not found")
        val dialog = enclosingCall(chat, chat.lastIndexOf("AlertDialog(", at))
        assertTrue("R.string.quantum_shield_setup_scan_next" in dialog, "the code dialog does not tell the next step")
        assertTrue("R.string.quantum_shield_code_show_mine" in dialog, "the code dialog has no Show my code")
    }

    @Test
    fun the_menu_entry_is_no_longer_none_only() {
        val view = SourceScan.stripComments(ChatResourceText.source(CDV))
        assertTrue("extraSecurityMenuState(" in view, "the ⋮ entry does not ask extraSecurityMenuState")
        assertFalse(
            Regex("""\.getOrDefault\(QuantumShieldStatus\.NONE\)\s*==\s*QuantumShieldStatus\.NONE""").containsMatchIn(view),
            "the ⋮ entry is still drawn only for NONE",
        )
    }

    /** The `AlertDialog(...)` call that starts at [start], up to its closing parenthesis. */
    private fun enclosingCall(
        source: String,
        start: Int,
    ): String {
        assertTrue(start >= 0, "the code dialog is not an AlertDialog")
        var depth = 0
        for (i in source.indexOf('(', start) until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(start, i + 1)
            }
        }
        throw AssertionError("unbalanced parentheses in the code dialog")
    }

    private companion object {
        const val CDV = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        const val ANDROID_CHAT = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
    }
}
