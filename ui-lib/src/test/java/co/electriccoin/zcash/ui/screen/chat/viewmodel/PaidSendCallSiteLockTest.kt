package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * R2-2 (b), plan step p2-2546b8c8a490 — every place the chat can pay on-chain is known and gated.
 *
 * Modelled on SweepCallSiteLockTest. It scans ui-lib's main Kotlin for calls to the two paid funnels,
 * `createChunkedMessageProposal(` (one on-chain chat send) and `sendGroupMemoWithRetry(` (one group memo,
 * free over Nostr or paid on-chain), and names the class member function each call sits in. Every such
 * call must be on the allowlist with one of two tags:
 *
 * - [USER_TAP_WITH_COST]: a user tap, and the user sees what it costs before it pays (the message-cost
 *   notice and the compose or composer cost line, a payment amount the user typed or accepted, or a
 *   NeedsPaidActionConfirmation prompt with the on-chain message count and the total);
 * - [AUTOMATIC_SPEND_CONSENT]: no tap asked for it; it pays only under the handshake-spend consent.
 *
 * A new caller, or one more call in a listed function, fails this test until it is gated and listed.
 * At b1f45ab32 there are 24 calls (25 lines with the funnel's own declaration); the unused read-receipt
 * sender (ChatViewModel.sendReadReceipt) paid on-chain with neither, and is gone.
 */
class PaidSendCallSiteLockTest {
    @Test
    fun every_paid_send_call_site_is_listed_and_gated() {
        val found = callSites()
        val unlisted = found.filterKeys { it !in ALLOWLIST.keys }
        val miscounted =
            found.filter { (site, count) -> ALLOWLIST[site] != null && ALLOWLIST.getValue(site).count != count }
        val gone = ALLOWLIST.keys - found.keys
        assertEquals(
            emptyMap(),
            unlisted,
            "paid send call sites with no gate on record (file#function to calls); gate them and list them",
        )
        assertEquals(emptyMap(), miscounted, "a listed function gained or lost a paid call; review its gate")
        assertEquals(emptySet(), gone, "listed call sites no longer exist; drop them from the allowlist")
    }

    @Test
    fun every_listed_site_has_a_known_tag() {
        val tags = ALLOWLIST.values.map { it.tag }.toSet()
        assertEquals(emptySet(), tags - setOf(USER_TAP_WITH_COST, AUTOMATIC_SPEND_CONSENT))
    }

    private data class Site(
        val count: Int,
        val tag: String,
        val why: String,
    )

    private fun callSites(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        mainJava
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                var member: String? = null
                file.readLines().forEach { line ->
                    MEMBER_FUN.find(line)?.let { member = it.groupValues[1] }
                    val code = line.trim()
                    val isComment = code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")
                    val isDeclaration = Regex("\\bfun\\s+(createChunkedMessageProposal|sendGroupMemoWithRetry)\\(").containsMatchIn(line)
                    if (!isComment && !isDeclaration && NEEDLES.any { line.contains(it) }) {
                        val key = "${file.name}#${member ?: "?"}"
                        result[key] = (result[key] ?: 0) + 1
                    }
                }
            }
        return result
    }

    private companion object {
        const val USER_TAP_WITH_COST = "user-tap-with-cost"
        const val AUTOMATIC_SPEND_CONSENT = "automatic-spend-consent"

        val NEEDLES = listOf("createChunkedMessageProposal(", "sendGroupMemoWithRetry(")

        /** A class member function: exactly four spaces of indent (local functions are indented deeper). */
        val MEMBER_FUN =
            Regex(
                "^ {4}(?:(?:private|internal|public|protected|override|suspend|inline|operator)\\s+)*fun\\s+" +
                    "(?:<[^>]*>\\s*)?`?(\\w+)`?\\s*\\(",
            )

        const val CHAT = "ChatViewModel.kt"
        const val GROUP = "GroupViewModel.kt"
        const val COMPOSE = "ZchatComposeVM.kt"

        val ALLOWLIST: Map<String, Site> =
            mapOf(
                "$CHAT#setDisappearingTtl" to
                    Site(1, USER_TAP_WITH_COST, "Shielded timer control: NeedsPaidActionConfirmation (1 message)"),
                "$CHAT#changeConversationMode" to
                    Site(1, USER_TAP_WITH_COST, "Shielded mode control with no relay: NeedsPaidActionConfirmation"),
                "$CHAT#sendMemoWithBlockRetry" to
                    Site(
                        1,
                        AUTOMATIC_SPEND_CONSENT,
                        "funnel of sendAutomaticHandshakeMemo (consent before every attempt; strangers held for " +
                            "Accept) and sendUserMemoWithBlockRetry (the time-lock sends, a user tap under the " +
                            "message-cost notice; Round 8 D7: the status broadcast, NeedsPaidActionConfirmation " +
                            "with 1 per Shielded contact in setUserStatus)",
                    ),
                "$CHAT#doSendMessage" to
                    Site(1, USER_TAP_WITH_COST, "message send: cost notice; first message to a new contact asks"),
                "$CHAT#sendPayment" to Site(1, USER_TAP_WITH_COST, "the user typed the amount"),
                "$CHAT#sendPaymentRequest" to
                    Site(1, USER_TAP_WITH_COST, "request send: cost notice; a Retry that would go on-chain asks"),
                "$CHAT#fulfilPaymentRequestToOutcome" to Site(1, USER_TAP_WITH_COST, "the user pays the shown request"),
                "$CHAT#sendReply" to Site(1, USER_TAP_WITH_COST, "reply send: cost notice, as a message"),
                "$CHAT#sendReaction" to Site(1, USER_TAP_WITH_COST, "Shielded reaction: NeedsPaidActionConfirmation"),
                "$CHAT#unlockPaymentMessage" to Site(1, USER_TAP_WITH_COST, "the user pays the shown unlock amount"),
                "$CHAT#unlockConditionalMessage" to Site(1, USER_TAP_WITH_COST, "the user pays the shown unlock amount"),
                "$GROUP#createGroup" to Site(1, USER_TAP_WITH_COST, "invites: NeedsPaidActionConfirmation"),
                "$GROUP#resendInvite" to Site(1, USER_TAP_WITH_COST, "invite: NeedsPaidActionConfirmation"),
                "$GROUP#addMemberToGroup" to Site(1, USER_TAP_WITH_COST, "invite: NeedsPaidActionConfirmation"),
                "$GROUP#sendGroupMemoWithRetry" to
                    Site(1, USER_TAP_WITH_COST, "the group funnel itself; every caller is listed with its own gate"),
                "$GROUP#rotateAndNotify" to
                    Site(1, USER_TAP_WITH_COST, "kick / rotate: NeedsPaidActionConfirmation before rotateAndNotify"),
                // Round 8 P6 (PHONE_TRIAGE F5b).
                "$GROUP#sendRemovalNotice" to
                    Site(1, USER_TAP_WITH_COST, "kick: the removal notice is in the kick's quote; paid only when that quote counted it"),
                "$GROUP#retryPendingGroupKeysInternal" to
                    Site(
                        2,
                        AUTOMATIC_SPEND_CONSENT,
                        "automatic (group open): handshake consent; the sync-keys tap asks first; the kick / " +
                            "rotate follow-up re-sends only what the confirmed action already quoted",
                    ),
                "$GROUP#requestGroupKeySync" to Site(1, USER_TAP_WITH_COST, "key request: NeedsPaidActionConfirmation"),
                // GR-OPEN G2 (fold-specs/groups SPEC §2.2, review D): the sealed admin controls of an all-Open group. Both
                // pass mayPayOnChain = { false }: over the relays only, never built or paid on-chain, and the Keystone
                // refusal applies. sendSealedKey is reached from the kick / rotate tap, the automatic owed-key retry and
                // the re-send before a group message; the sealed removal notice from the kick tap.
                "$GROUP#sendSealedKey" to
                    Site(1, AUTOMATIC_SPEND_CONSENT, "sealed new key: mayPayOnChain = { false }, relays only, never paid"),
                "$GROUP#sendSealedRemovalNotice" to
                    Site(1, USER_TAP_WITH_COST, "kick: sealed removal notice, mayPayOnChain = { false }, relays only, never paid"),
                // KI-25 pair 3 (R17, ZC-598): the key request of an Open group that cannot confirm its admin passes
                // mayPayOnChain = { false }: over the relays only, never built or paid on-chain, and the Keystone refusal
                // applies. Reached only from the sync-keys tap (requestGroupKeySync) while the admin is not confirmed.
                "$GROUP#requestKeyOverRelaysOnly" to
                    Site(1, USER_TAP_WITH_COST, "sync-keys tap, Open group: mayPayOnChain = { false }, relays only, never paid"),
                // R5-1: the fan-out moved out of sendGroupMessage, which now only quotes and asks, into
                // sendQuotedGroupMessage, which is handed the frozen quote and pays only for the members it
                // covered. The call itself is unchanged and still gated by a user tap with a cost on screen.
                "$GROUP#sendQuotedGroupMessage" to
                    Site(
                        1,
                        USER_TAP_WITH_COST,
                        "group message: the composer states what this message costs on-chain, and the first " +
                            "message to a paying set the user has not already consented to raises " +
                            "NeedsPaidActionConfirmation (GROUP_MESSAGE)",
                    ),
                // F8-G (ZC-112 part 2): Resend on a member's "Message not delivered" badge in Group Settings.
                "$GROUP#sendMessageCopies" to
                    Site(1, USER_TAP_WITH_COST, "one member's copy again: NeedsPaidActionConfirmation (GROUP_MESSAGE, one member)"),
                "$GROUP#leaveGroup" to Site(1, USER_TAP_WITH_COST, "GROUP_LEAVE: NeedsPaidActionConfirmation"),
                // Round 8 MA3 (member announce): an on-chain GP, paid only for a pair the admin's quote counted (the
                // GROUP_INTRODUCE tap, or the removal's GROUP_REMOVE_MEMBER quote), with an explicit mayPayOnChain.
                "$GROUP#payAnnouncePair" to
                    Site(1, USER_TAP_WITH_COST, "GROUP_INTRODUCE / GROUP_REMOVE_MEMBER: NeedsPaidActionConfirmation, counted in pairs"),
                "$COMPOSE#doSendMessage" to
                    Site(
                        1,
                        USER_TAP_WITH_COST,
                        "New Chat: the exact price question (PaidActionConfirmDialog, one transaction) before this call, " +
                            "which carries the quote; the message-cost notice comes first for a wallet that never accepted it",
                    ),
            )

        val mainJava: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java") }
                .firstOrNull { it.resolve("co/electriccoin/zcash/ui/screen/chat").isDirectory }
                ?: fail("could not locate ui-lib/src/main/java from ${start.path}")
        }
    }
}
