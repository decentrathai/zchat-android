package co.electriccoin.zcash.ui.screen.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import co.electriccoin.zcash.ui.R

/**
 * Swap the client's fixed English sentences ([AiApiClient.SERVICE_UNAVAILABLE_MESSAGE],
 * [AiApiClient.TOPUP_PAUSED_MESSAGE] and [AiApiClient.NETWORK_UNREACHABLE_MESSAGE]) and the tab's own one for a store
 * that cannot be opened ([AiTabVM.AI_STORE_UNAVAILABLE_MESSAGE], ZC-733-A) for their localized text. Anything else passes through unchanged, including a prefix such as "Could not load models: "
 * around one of them.
 */
fun localizeAiMessage(
    text: String,
    serviceUnavailable: String,
    topupsPaused: String,
    networkUnreachable: String = AiApiClient.NETWORK_UNREACHABLE_MESSAGE,
    storeUnavailable: String = AiTabVM.AI_STORE_UNAVAILABLE_MESSAGE,
): String =
    text
        .replace(AiApiClient.SERVICE_UNAVAILABLE_MESSAGE, serviceUnavailable)
        .replace(AiApiClient.TOPUP_PAUSED_MESSAGE, topupsPaused)
        .replace(AiApiClient.NETWORK_UNREACHABLE_MESSAGE, networkUnreachable)
        .replace(AiTabVM.AI_STORE_UNAVAILABLE_MESSAGE, storeUnavailable)

/** AI-KEY-S: swaps each sentence of [AiApiClient.SIGN_IN_TEXTS] for the text of its key in [localized]. */
fun localizeAiMessage(
    text: String,
    localized: Map<String, String>,
): String =
    (AiApiClient.SIGN_IN_TEXTS + AiApiClient.OTHER_ACCOUNT_TEXTS + AiApiClient.LINK_AGAIN_TEXTS)
        .entries
        .fold(text) { swapped, (key, sentence) ->
            localized[key]?.let { swapped.replace(sentence, it) } ?: swapped
        }

@Composable
fun localizedAiMessage(text: String): String =
    localizeAiMessage(
        localizeAiMessage(
            text = text,
            serviceUnavailable = stringResource(R.string.ai_service_unavailable),
            topupsPaused = stringResource(R.string.ai_topups_paused),
            networkUnreachable = stringResource(R.string.ai_network_unreachable),
            storeUnavailable = stringResource(R.string.ai_store_unavailable),
        ).replace(AiTabVM.WALLET_CHANGED_MESSAGE, stringResource(R.string.ai_wallet_changed)),
        mapOf(
            "ai_signin_wallet_not_ready" to stringResource(R.string.ai_signin_wallet_not_ready),
            "ai_signin_server_not_ready" to stringResource(R.string.ai_signin_server_not_ready),
            "ai_signin_too_many" to stringResource(R.string.ai_signin_too_many),
            "ai_signup_limit" to stringResource(R.string.ai_signup_limit),
            "ai_signin_refused" to stringResource(R.string.ai_signin_refused),
            "ai_signin_elsewhere" to stringResource(R.string.ai_signin_elsewhere),
            "ai_notice_other_wallet_dropped" to stringResource(R.string.ai_notice_other_wallet_dropped),
            "ai_notice_key_parked" to stringResource(R.string.ai_notice_key_parked),
            "ai_notice_new_account_after_refusal" to stringResource(R.string.ai_notice_new_account_after_refusal),
            "ai_notice_link_lost" to stringResource(R.string.ai_notice_link_lost),
            "ai_notice_trial_account_left" to stringResource(R.string.ai_notice_trial_account_left),
            "ai_notice_two_paid_accounts" to stringResource(R.string.ai_notice_two_paid_accounts),
            "ai_notice_parked_key_dead" to stringResource(R.string.ai_notice_parked_key_dead),
            "ai_notice_parked_key_opened" to stringResource(R.string.ai_notice_parked_key_opened),
            "ai_old_credit_none" to stringResource(R.string.ai_old_credit_none),
            "ai_old_credit_opened" to stringResource(R.string.ai_old_credit_opened),
            "ai_old_credit_needs_check" to stringResource(R.string.ai_old_credit_needs_check),
            "ai_old_credit_two_accounts" to stringResource(R.string.ai_old_credit_two_accounts),
            "ai_topup_not_linked" to stringResource(R.string.ai_topup_not_linked),
            "ai_link_again_done" to stringResource(R.string.ai_link_again_done),
            "ai_link_again_not_done" to stringResource(R.string.ai_link_again_not_done),
        ),
    )
