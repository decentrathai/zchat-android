package co.electriccoin.zcash.ui.screen.chat.model

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.crypto.QuantumShieldStatus

/**
 * ZC-1476 + ZC-1477 + ZC-445 + ZC-321: the ⋮ entry for Extra Security (Post-Quantum). It is never hidden: before the key
 * exchange it is shown disabled with the reason, a PENDING setup can be continued (the same QR again) or cancelled, and
 * an active one still shows this phone's code, so the other phone can scan it and see its six digits. Setup stays a
 * mutual in-person scan; nothing here changes the protocol ("Show my code" reuses the stored secret through
 * ChatViewModel.initiateQuantumShield).
 */
enum class ExtraSecurityMenuState(
    @StringRes val labelRes: Int,
    val enabled: Boolean,
    val showsMyCode: Boolean,
    val offersCancel: Boolean,
) {
    ENABLE(R.string.chat_menu_extra_security, enabled = true, showsMyCode = false, offersCancel = false),
    CONTINUE(R.string.chat_menu_extra_security_continue, enabled = true, showsMyCode = true, offersCancel = true),
    ON(R.string.chat_menu_extra_security_on, enabled = true, showsMyCode = true, offersCancel = false),
    NEEDS_KEY_EXCHANGE(R.string.chat_menu_extra_security_needs_kex, enabled = false, showsMyCode = false, offersCancel = false),
}

/** The entry for a chat: PENDING and ACTIVE whatever the key-exchange state, NONE by whether E2E is ready. */
fun extraSecurityMenuState(
    isE2EReady: Boolean,
    status: QuantumShieldStatus,
): ExtraSecurityMenuState =
    when (status) {
        QuantumShieldStatus.ACTIVE -> ExtraSecurityMenuState.ON
        QuantumShieldStatus.PENDING -> ExtraSecurityMenuState.CONTINUE
        QuantumShieldStatus.NONE ->
            if (isE2EReady) ExtraSecurityMenuState.ENABLE else ExtraSecurityMenuState.NEEDS_KEY_EXCHANGE
    }
