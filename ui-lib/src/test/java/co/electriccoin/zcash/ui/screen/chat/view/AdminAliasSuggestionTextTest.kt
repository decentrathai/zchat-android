package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.AdminCandidateSource
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * ZC-953 reverted (orchestrator's decision of 14 Oct 2026, security first; KI-25 D3; skill app-texts) — the admin check
 * card has no text and no branch for a chat suggested from an address alias, in any language: any contact can plant an
 * alias with a signed group-accept, so the card never offers one (`AdminCheckNeverSuggestsAnAliasKeyTest`).
 * The texts for the invitation and the admin's address stay as they were.
 *
 * At `9170225e4` (the suggestion) every case fails: the key `group_admin_candidate_alias` exists in English and
 * Spanish, the card shows it for the source `ALIAS`, and the source exists.
 */
class AdminAliasSuggestionTextTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun there_is_no_alias_suggestion_text_in_english_or_spanish() {
        assertNull(en[KEY], "English still has $KEY")
        assertNull(es[KEY], "Spanish still has $KEY")
    }

    @Test
    fun the_card_has_no_branch_for_a_chat_from_an_alias() {
        val card = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupSettingsView.kt")
        assertFalse("AdminCandidateSource.ALIAS" in card, "the card still has a branch for a suggestion from an alias")
        assertFalse("R.string.$KEY" in card, "the card still shows $KEY")
    }

    @Test
    fun a_candidate_comes_only_from_the_invitation_or_the_admins_address() {
        assertEquals(listOf("INVITATION", "ADDRESS"), AdminCandidateSource.values().map { it.name })
    }

    private companion object {
        const val KEY = "group_admin_candidate_alias"
    }
}
