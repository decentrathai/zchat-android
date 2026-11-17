package co.electriccoin.zcash.ui.screen.ai

/**
 * ZC-1265: "Look for AI credit from before 2.13" and its note stand behind a small entry. They open by themselves only when
 * they matter: a parked key, or a notice of the access (the set AiTabView treats as no error). Opening sends nothing.
 */
object AiOldCreditRow {
    fun startsOpen(
        parkedAccounts: List<String>,
        notice: String?,
    ): Boolean =
        parkedAccounts.isNotEmpty() ||
            AiApiClient.SIGN_IN_TEXTS.any { (key, text) -> key.startsWith("ai_notice_") && text == notice } ||
            notice in AiApiClient.OTHER_ACCOUNT_TEXTS.values ||
            notice in AiApiClient.LINK_AGAIN_TEXTS.values
}
