package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ExtraSecurityMenuState
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 (owner W-136/W-137), unit U7 chat screen: what the chat screens must wire, pinned as source assertions in the
 * style of DeclinedRequestsAreWiredIntoTheScreensTest (the Compose layout itself is a phone check in the QA repeat).
 *
 * Rows: Q2-22, Q2-26, Q2-28, Q2-30 (guard, green today), Q2-31, Q2-33, Q2-39, Q2-40, Q2-41, Q2-42, Q2-44, Q2-47, Q2-54,
 * Q2-68, Q2-98, Q2-106 (FIX_LIST.md under qa2/). Pure Compose layout rows are [no-red] and listed in the commit.
 */
class Qa2ChatScreenWiringTest {
    private val chat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"

    private val detail by lazy { ChatResourceText.source("$chat/view/ChatDetailView.kt") }
    private val dialogs by lazy { ChatResourceText.source("$chat/view/ChatDialogs.kt") }
    private val list by lazy { ChatResourceText.source("$chat/view/ChatListView.kt") }
    private val cached by lazy { ChatResourceText.source("$chat/view/CachedChatDetailView.kt") }
    private val listCopy by lazy { ChatResourceText.source("$chat/view/ChatListCopy.kt") }
    private val paidDialog by lazy { ChatResourceText.source("$chat/view/PaidActionConfirmDialog.kt") }

    /** Every Kotlin file of the product module: where a new string key must be read from. */
    private val allMain: String by lazy {
        File(ChatResourceText.repoRoot, "ui-lib/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }

    private fun assertKeyIsRead(key: String) {
        assertTrue("R.string.$key" in allMain, "no Kotlin source reads R.string.$key: the new text never reaches a screen")
    }

    private fun between(
        source: String,
        from: String,
        to: String,
    ): String {
        val start = source.indexOf(from)
        assertTrue(start >= 0, "marker not found: $from")
        val end = source.indexOf(to, start + from.length)
        assertTrue(end > start, "marker not found after \"$from\": $to")
        return source.substring(start, end)
    }

    // ---- Q2-22 day separator ----

    private val itemsBlock by lazy {
        between(detail, "items(displayMessages.size, key = { displayMessages[it].id }) { index ->", "// Welcome ZEC suggestion")
    }

    private val separatorFunction by lazy {
        between(detail, "private fun DateSeparator(", "private fun WelcomeZecCard(")
    }

    @Test
    fun q2_22_the_day_chip_is_drawn_above_the_first_message_of_its_day() {
        // The list is reverseLayout: the children one item emits are stacked bottom-up, so a chip emitted before the
        // bubble lands UNDER the bubble (QA: the chip sat under the newest message). It must be emitted after the
        // message content, or inside one Column that fixes the order.
        val firstSeparator = itemsBlock.indexOf("DateSeparator(")
        assertTrue(firstSeparator >= 0, "the item no longer draws a DateSeparator")
        val firstBubbleOrNote =
            listOf("CallLogPill(", "SystemNotePill(", "MessageBubble(")
                .map { itemsBlock.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull() ?: Int.MAX_VALUE
        val wrapped = Regex("""Column\s*\(""").containsMatchIn(itemsBlock.substring(0, firstSeparator))
        assertTrue(
            firstSeparator > firstBubbleOrNote || wrapped,
            "the day chip is emitted before the message in a reverseLayout item, so it is drawn under it",
        )
    }

    @Test
    fun q2_22_the_day_chip_does_not_carry_the_live_block_height() {
        assertFalse("chat_date_today_block" in separatorFunction, "the chip still shows a block height that drifts on every visit")
        assertFalse("currentBlockHeight" in separatorFunction)
    }

    @Test
    fun q2_22_the_day_chip_is_12sp() {
        assertTrue("fontSize = 12.sp" in separatorFunction, "the chip text is not 12.sp")
        assertFalse("fontSize = 10.sp" in separatorFunction)
    }

    // ---- Q2-26 contact name dialog ----

    private val nicknameDialog by lazy { between(detail, "// Nickname Edit Dialog", "// Amount Picker Dialog") }

    @Test
    fun q2_26_remove_is_offered_only_when_there_is_a_nickname_to_remove() {
        val at = nicknameDialog.indexOf("R.string.contact_nickname_remove")
        assertTrue(at >= 0, "the dialog lost its Remove nickname action")
        val before = nicknameDialog.substring(maxOf(0, at - 400), at)
        assertTrue(
            "hasContactName" in before || "contactName" in before,
            "Remove is chosen by a blank field alone, so it shows beside \"Remove photo\" for a contact with no nickname",
        )
    }

    @Test
    fun q2_26_save_is_off_on_a_blank_field_when_there_is_nothing_to_remove() {
        val confirm = between(nicknameDialog, "confirmButton = {", "dismissButton = {")
        assertTrue(Regex("""enabled\s*=""").containsMatchIn(confirm), "the confirm action of the nickname dialog is always enabled")
    }

    // ---- Q2-28 lock ----

    @Test
    fun q2_28_the_open_chat_lock_dialog_reads_its_own_text() {
        assertKeyIsRead("chat_menu_e2e_subtitle_open")
        val lockDialog = between(detail, "if (showLockDialog) {", "if (showSafetyNumberDialog && safetyNumber != null) {")
        assertTrue(
            "ConversationMode.OPEN" in lockDialog || "chat_menu_e2e_subtitle_open" in lockDialog,
            "the lock dialog does not know the chat is Open",
        )
    }

    @Test
    fun q2_28_the_lock_tints_the_mode_chip_with_the_lock_state() {
        val chip = between(detail, "imageVector = modeChipIcon(conversationMode),", "if (chipExtraSecurity != QuantumShieldStatus.NONE)")
        assertTrue(
            "lockState" in chip || "colors.success" !in chip,
            "the chip is drawn green whatever the lock says (QA: the chip colour disagrees with the amber lock)",
        )
    }

    // ---- Q2-30 greyed rows are not clickable (guard: already true at the base) ----

    @Test
    fun q2_30_guard_extra_security_without_a_key_exchange_is_disabled() {
        assertEquals(false, ExtraSecurityMenuState.NEEDS_KEY_EXCHANGE.enabled)
        assertTrue("enabled = extraSecurityMenu.enabled" in detail)
    }

    @Test
    fun q2_30_guard_time_locked_is_disabled_outside_a_shielded_chat() {
        val timeLock = between(detail, "// Time-Locked Message option", "onLockClick()")
        assertTrue(Regex("""enabled\s*=\s*conversationMode\s*==\s*[\w.]*VAULT""").containsMatchIn(timeLock))
    }

    // ---- Q2-31 attachment menu ----

    private val featureMenu by lazy { between(detail, "// Time-Locked Message option", "Toast.makeText(") }

    @Test
    fun q2_31_the_attachment_rows_use_material_icons_not_emoji_or_three_identical_plus_glyphs() {
        assertFalse("\"☕\"" in featureMenu, "an emoji still stands beside the vector icons")
        assertFalse("\"💸\"" in featureMenu, "an emoji still stands beside the vector icons")
        assertTrue(
            Regex("""Icons\.Default\.Add\b""").findAll(featureMenu).count() == 0,
            "the Take Photo, Send Image, Send File rows still share the identical + glyph",
        )
        for (icon in listOf("CameraAlt", "Image", "AttachFile")) {
            assertTrue("Icons.Default.$icon" in featureMenu, "no $icon icon in the attachment rows")
        }
    }

    @Test
    fun q2_31_the_attachment_rows_carry_the_key_exchange_note() {
        assertKeyIsRead("chat_attachment_kex_row_note")
        assertTrue("chat_attachment_kex_row_note" in featureMenu, "the attachment rows do not show the note")
    }

    // ---- Q2-33 / Q2-98 header ----

    private val headerTitle by lazy { between(detail, "// Tap to edit nickname", "navigationIcon = {") }

    @Test
    fun q2_33_a_muted_chat_is_marked_in_the_top_bar() {
        assertTrue("chat_header_muted" in headerTitle, "the header shows no marker for a muted chat")
        assertTrue("conversation.isMuted" in headerTitle)
        assertTrue("NotificationsOff" in headerTitle, "the marker is the crossed-bell icon")
    }

    @Test
    fun q2_98_the_header_says_offline_while_the_wallet_is_offline() {
        assertTrue("chat_header_offline" in headerTitle, "the chat header never says Offline")
    }

    // ---- Q2-39 / Q2-40 offer banner ----

    private val offerBlock by lazy {
        between(detail, "val tunnelOffer = openChatTunnelOffer", "if (awaitingChatAccept &&")
    }

    @Test
    fun q2_39_the_collapsed_offer_banner_carries_the_quoted_price() {
        assertTrue(
            Regex("""R\.string\.chat_open_tunnel_offer_short\s*,\s*offerAmount""").containsMatchIn(offerBlock),
            "the collapsed Tunnel offer banner must show the quote (offerAmount), never a constant",
        )
    }

    @Test
    fun q2_40_the_offer_notice_tells_confirming_funds_from_not_enough_funds() {
        assertTrue("chat_open_tunnel_offer_confirming_funds" in offerBlock, "one text covers both 'confirming' and 'not enough'")
        assertTrue("chat_open_tunnel_offer_needs_funds" in offerBlock)
        assertKeyIsRead("chat_open_tunnel_offer_confirming_funds")
    }

    // ---- Q2-41 generic name ----

    @Test
    fun q2_41_system_notes_name_a_contact_without_a_nickname_as_your_contact() {
        val call = between(detail, "SystemNoteText.display(", "noteCopy)")
        assertFalse(
            "conversation.displayName" in call,
            "system notes are filled with the header's raw short address when no nickname exists",
        )
        assertKeyIsRead("chat_contact_fallback_name")
    }

    // ---- Q2-42 composer button ----

    @Test
    fun q2_42_the_priced_send_button_wraps_on_two_lines_instead_of_squeezing_the_input() {
        val label = between(detail, "R.string.chat_send_cost_label", "color = if (isZypherpunkMode)")
        assertTrue("maxLines = 2" in label, "the priced send label must wrap at word boundaries on two lines")
        assertTrue("fontSize = 12.sp" in label)
    }

    // ---- Q2-44 paid-action sheet ----

    private val confirmButton by lazy { between(paidDialog, "confirmButton = {", "dismissButton = {") }

    @Test
    fun q2_44_the_money_button_carries_the_amount_and_is_filled() {
        assertTrue(
            Regex("""paid_action_confirm\s*,""").containsMatchIn(confirmButton) &&
                "paid_action_confirm_exact" in confirmButton,
            "the Pay button must pass the quoted total, floor or exact",
        )
        assertTrue("totalCost" in confirmButton, "the button amount must be the quote's total")
        assertTrue(Regex("""(?<!Text)(?<!Filled)Button\(""").containsMatchIn(confirmButton), "the money button must be a filled Button")
        assertFalse("TextButton(onClick = onConfirm)" in confirmButton)
    }

    // ---- Q2-47 12 h / 24 h ----

    @Test
    fun q2_47_chat_timestamps_follow_the_phones_clock_setting() {
        assertTrue("is24Hour" in cached, "CachedChatDetailView still formats every time as HH:mm")
        assertTrue("is24HourFormat" in detail || "is24Hour" in detail, "ChatDetailView still formats every time as HH:mm")
        assertTrue("is24Hour" in listCopy, "chatListTimestamp still formats every time as HH:mm")
    }

    // ---- Q2-54 request card ----

    @Test
    fun q2_54_the_request_card_reads_its_texts_from_resources() {
        assertFalse(
            Regex("""text\s*=\s*"Pay \$\{""").containsMatchIn(dialogs),
            "the Pay button of a request is a hard-coded English literal",
        )
        assertTrue("R.string.chat_request_pay_button" in dialogs)
        assertTrue("R.string.chat_request_paid_receipt" in dialogs, "a paid request shows no amount paid or time")
    }

    // ---- Q2-68 privacy chip ----

    @Test
    fun q2_68_the_privacy_chip_is_12sp_at_full_alpha() {
        val chip =
            between(detail, "isNostr -> stringResource(R.string.chat_privacy_chip_nostr)", "AnimatedVisibility(visible = isExpanded)")
        assertTrue("fontSize = 12.sp" in chip, "the privacy chip text is not 12.sp")
        assertFalse("copy(alpha = 0.8f)" in chip, "the privacy chip text is still drawn at 0.8 alpha")
    }

    // ---- Q2-106 amber on light themes ----

    @Test
    fun q2_106_the_warning_banners_use_the_theme_warning_colour() {
        assertFalse("0xFFFFB300" in list, "the sync banner keeps a hard-coded amber (about 2:1 on light themes)")
        val amber = Regex("""Color\(0xFFFFB800\)""").findAll(detail).count()
        assertEquals(
            1,
            amber,
            "ChatDetailView keeps $amber hard-coded amber colours; only EXTRA_SECURITY_PENDING_COLOR (a badge) may stay",
        )
        assertTrue("EXTRA_SECURITY_PENDING_COLOR = Color(0xFFFFB800)" in detail)
        val sync = between(list, "if (isRestoringOrInitiating) {", "private fun EmptyConversationsView(")
        assertTrue("chatColors().warning" in sync)
        assertTrue("fontSize = 12.sp" in sync, "the sync banner text is not 12.sp")
    }

    // ---- Q2-87 Send Us Feedback (guard) ----

    @Test
    fun q2_87_guard_send_us_feedback_opens_the_feedback_form_not_a_generic_new_chat() {
        // QA (P3F step 22) saw a generic New Chat after "Send Us Feedback". In this tree the row opens the e-mail feedback
        // form (FeedbackArgs) whose own dialog says "Open e-mail app"; no route from More reaches ZchatCompose, so there is no
        // New Chat entry to put a Feedback instruction on. If a route to New Chat is added, this guard must be replaced by a
        // test for its instruction text (FIX_LIST Q2-87).
        val more = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/more/MoreVM.kt")
        assertTrue("private fun onSendUsFeedbackClick() = navigationRouter.forward(FeedbackArgs)" in more)
        assertFalse("ZchatCompose" in more)
    }

    // ---- Q2-53 stacked banners ----

    @Test
    fun q2_53_the_unverified_contact_state_is_folded_into_the_header_lock() {
        // Two stacked banners (key exchange + Unverified contact) took a third of the chat and stayed during calls. The
        // Unverified contact notice moves into the header lock's dialog, which already holds the safety-number action.
        val banners =
            Regex(
                """CollapsibleStatusBanner\((?:(?!CollapsibleStatusBanner\().)*?chat_unverified_contact_label""",
                RegexOption.DOT_MATCHES_ALL,
            )
        assertFalse(banners.containsMatchIn(detail), "Unverified contact is still a banner of its own")
        val lockDialog = between(detail, "if (showLockDialog) {", "if (showSafetyNumberDialog && safetyNumber != null) {")
        assertTrue("chat_unverified_contact_body" in lockDialog, "the lock dialog does not carry the Unverified contact text")
    }

    // ---- Q2-55 thread anchored at the bottom (guard) ----

    @Test
    fun q2_55_guard_the_thread_list_is_a_reverse_layout() {
        // The base already stacks the newest message at the composer (reverseLayout = true); the QA2 shots that showed the
        // thread at the top need a phone re-shoot on the current build before any layout change is made.
        assertTrue(Regex("""reverseLayout\s*=\s*true""").containsMatchIn(detail))
    }

    // ---- U7 review follow-up (WAVE2 #12) ----

    /** [source] without its `//` comment lines, so a pin cannot be satisfied by a comment. */
    private fun withoutComments(source: String): String = source.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    /** The text of the brace block that opens at the first '{' after [marker]. */
    private fun braceBlock(
        source: String,
        marker: String,
    ): String {
        val at = source.indexOf(marker)
        assertTrue(at >= 0, "marker not found: $marker")
        val open = source.indexOf('{', at)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces after $marker")
    }

    @Test
    fun review_2_the_open_chat_lock_text_follows_the_missing_safety_number_not_the_mode() {
        // An Open chat that had a key exchange earlier has a safety number and shows "Verify safety number"; its lock text must
        // not say "Open chats have no safety number" in the same dialog. The choice is made on the safety number / key state.
        val lockDialog = between(detail, "if (showLockDialog) {", "if (showSafetyNumberDialog && safetyNumber != null) {")
        val choice = withoutComments(between(lockDialog, "val lockSubtitle", "chat_menu_e2e_subtitle_open"))
        assertTrue(
            "safetyNumber == null" in choice || "!conversation.isE2EReady" in choice || "safetyNumber.isNullOrEmpty()" in choice,
            "the Open lock text is chosen on the mode, so a chat with a safety number says it has none: $choice",
        )
    }

    @Test
    fun review_3_an_unverified_chat_keeps_a_visible_cue_in_the_header() {
        // Q2-53 removed the Unverified banner; without a cue on the header the state is only found by tapping the lock.
        val lockAndStatus = withoutComments(between(headerTitle, "val lockState = e2eLockState(", "if (walletOffline)"))
        assertTrue(
            Regex("""(?i)unverified|!isVerified""").containsMatchIn(lockAndStatus),
            "the header draws the lock the same for a verified and an unverified chat",
        )
    }

    @Test
    fun review_12_the_offline_header_text_is_one_resource_without_a_concatenated_separator() {
        val offline = withoutComments(braceBlock(headerTitle, "if (walletOffline)"))
        assertTrue("R.string.chat_header_offline" in offline)
        assertFalse(
            Regex(""""[^"]*·[^"]*"\s*\+""").containsMatchIn(offline),
            "the separator is glued to the resource in Kotlin: $offline",
        )
    }

    @Test
    fun review_10_leaving_the_chat_either_keeps_the_preview_queue_or_deletes_the_share_inbox_copies_still_waiting() {
        // U7 follow-up nit 4 (QA2 P3): the name says what is pinned: a queue that survives, or copies that are deleted (the
        // fix drops the queue and deletes the copies); a picture is not kept either way, so it never said "not lost".
        // pendingImageSends was remember(peerAddress): leaving the chat dropped the queue, and the share-inbox copies of
        // shared pictures (already consumed from PendingShareStore) were never deleted. Either the queue survives (rememberSaveable)
        // or leaving the chat deletes every copy still waiting.
        val chatSource = withoutComments(ChatResourceText.source("$chat/AndroidChat.kt"))
        val declaration = chatSource.substringAfter("var pendingImageSends by").substringBefore("\n    val imageSendMutex")
        val saved = "rememberSaveable" in declaration
        var cleansUp = false
        var from = 0
        while (true) {
            val at = chatSource.indexOf("DisposableEffect(", from)
            if (at < 0) break
            val body = braceBlock(chatSource.substring(at), "DisposableEffect(")
            if ("onDispose" in body && "shareInboxFile" in body && "delete" in body) cleansUp = true
            from = at + 1
        }
        assertTrue(
            saved || cleansUp,
            "the preview queue is lost with the chat and its share-inbox copies are never deleted (no rememberSaveable, no " +
                "DisposableEffect deleting shareInboxFile)",
        )
    }

    @Test
    fun review_13_chat_sending_images_is_read_by_a_screen_or_gone() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        if ("chat_sending_images" in en || "chat_sending_images" in es) {
            assertKeyIsRead("chat_sending_images")
            assertTrue("chat_sending_images" in es && "chat_sending_images" in en, "chat_sending_images is not in both languages")
            assertTrue(es["chat_sending_images"] != en["chat_sending_images"], "chat_sending_images is not translated")
        }
    }

    // ---- keys the screens must read ----

    @Test
    fun the_new_keys_are_read_by_a_screen() {
        for (key in listOf(
            "message_requests_declined_hint",
            "chat_header_muted",
            "chat_header_offline",
            "chat_open_tunnel_offer_short",
            "chat_request_pay_button",
            "chat_request_paid_receipt",
            "paid_action_confirm_exact",
            "scan_contact_hint",
        )) {
            assertKeyIsRead(key)
        }
        assertNotNull(detail)
    }
}
