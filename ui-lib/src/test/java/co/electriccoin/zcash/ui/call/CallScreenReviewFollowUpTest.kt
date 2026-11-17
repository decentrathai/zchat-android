package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.call.CallSourceBlocks.NOTIFICATIONS
import co.electriccoin.zcash.ui.call.CallSourceBlocks.OVERLAY
import co.electriccoin.zcash.ui.call.CallSourceBlocks.SERVICE
import co.electriccoin.zcash.ui.call.CallSourceBlocks.block
import co.electriccoin.zcash.ui.call.CallSourceBlocks.source
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 U2-calls review follow-up (W-137; independent review of f5044d684): ZC-1657, ZC-1659, ZC-1599.
 *
 *  - ZC-1657 / ZC-1659 (review finding 1, Normal): ApplicationStateProviderImpl starts at "in front" and its flow is
 *    distinctUntilChanged, so a process the sync alarm starts in the background reads `true` first and, when the user
 *    then opens the app, never emits again until the first ON_STOP. The one-time importance check on the first value
 *    leaves callScreenInFront false for that whole stay, so the system card shows beside the in-app ring screen and a
 *    call the user watched ring out raises "Missed call". Wanted: the service asks the process importance
 *    (processIsInFront) at each decision point, the incoming card and the missed-call notice, not only on the first
 *    foreground value.
 *  - ZC-1599 (review finding 3): FIX_LIST Q2-75 asks for captions, not only content descriptions: a call button
 *    shows its label under the icon, the audio-output button too.
 *  - ZC-1659 (review finding 7): the missed-call intent is filterEquals to the chat deep-link intent (same component
 *    and flags, no action), so a colliding request code would swap two deep links under FLAG_UPDATE_CURRENT. Wanted:
 *    its own action string.
 *  - ZC-1599 (review finding 5): the Spanish "%1$s, seleccionado" says "Auriculares, seleccionado" for a plural noun
 *    and "Altavoz, seleccionado" only by luck of gender; the wording must not depend on the route name's gender.
 * Not testable here: the pill's time of day (finding 3, ChatDetailView.kt, wave 2) and the call-log search path
 * (finding 4, ChatDetailView.kt, wave 2).
 */
class CallScreenReviewFollowUpTest {
    @Test
    fun `the incoming card asks the process importance at every decision, not only on the first value`() {
        val service = source(SERVICE)
        val from = service.indexOf("combine(voiceCalls.state, callScreenInFront)")
        if (from < 0) fail("the incoming-card combine is gone from SyncForegroundService")
        val to = service.indexOf("callNotifications.cancel()", from)
        val region = service.substring(from, to)
        assertTrue(
            "processIsInFront()" in region,
            "the card decision trusts callScreenInFront alone: a process started in the background and then opened " +
                "stays 'not in front' until the first ON_STOP, so the card shows beside our ring screen",
        )
    }

    @Test
    fun `the missed-call notice asks the process importance before it posts`() {
        val body = block(source(SERVICE), "notifyMissedCall", indent = "    ")
        assertTrue(
            "processIsInFront()" in body,
            "notifyMissedCall trusts callScreenInFront alone: a call the user watched ring out raises Missed call",
        )
    }

    @Test
    fun `a call button shows its label under the icon`() {
        val button = block(source(OVERLAY), "CallButton")
        assertTrue("Text(" in button, "CallButton draws an unlabeled circle: FIX_LIST Q2-75 asks for captions")
    }

    @Test
    fun `the audio-output button shows the current route name under the icon`() {
        val button = block(source(OVERLAY), "AudioOutputButton")
        val beforeMenu = button.substringBefore("DropdownMenu(")
        assertTrue("Text(" in beforeMenu, "the audio-output button draws an unlabeled circle: FIX_LIST Q2-75 asks for captions")
    }

    @Test
    fun `the missed-call tap intent carries its own action`() {
        val show = block(source(NOTIFICATIONS), "showMissed", indent = "    ")
        assertTrue(
            ".setAction(" in show,
            "the missed-call intent equals the chat deep-link intent (filterEquals): give it its own action string",
        )
    }

    @Test
    fun `the Spanish selected-route line does not depend on the route name's gender or number`() {
        val es = ChatResourceText.strings("values-es")["call_route_selected"] ?: fail("call_route_selected must be in values-es")
        assertTrue("%1\$s" in es, "the route name placeholder is missing: $es")
        assertFalse(
            Regex("%1[$]s\\W*seleccionad[oa]s?\\s*\$", RegexOption.IGNORE_CASE).containsMatchIn(es),
            "'$es' makes 'seleccionado' agree with the route name (Auriculares is plural): put the word first, as 'Seleccionado: %1\$s'",
        )
    }
}
