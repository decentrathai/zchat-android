package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.lang.reflect.Method
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ES-A (fix-all 2.13.0, train T4c; Spanish fold spec §1.1, §3.4; T4 phone finding 2, W-11): the chat list's row
 * time says "Yesterday" in English on a Spanish phone, and an older date is always "MMM d" (English order: "sept 3"
 * on a Spanish phone), in the JVM default locale rather than the language the app shows.
 *
 * What it must be: a pure function `chatListTimestamp(ts, now, locale, yesterday, monthDayPattern)` in
 * ChatListCopy.kt. Today stays "HH:mm" (a localized short time would turn an en-US "15:45" into "3:45 PM" and ignore
 * the phone's 24-hour setting); one calendar day back is the given word; under a week the short weekday in the given
 * locale; older the given month-day pattern in the given locale. The pattern is a parameter: unit tests run with
 * `isReturnDefaultValues = true`, where DateFormat.getBestDateTimePattern returns null, so the composable resolves it
 * from the phone and passes it in. Reached by reflection, so this file compiles before the fix.
 */
class ChatListDatesFollowLanguageTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val now = ZonedDateTime.of(2026, 9, 13, 15, 45, 0, 0, zone)
    private val es = Locale.forLanguageTag("es-ES")

    private val fn: Method? by lazy {
        runCatching {
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.view.ChatListCopyKt")
                .getDeclaredMethod(
                    "chatListTimestamp",
                    Instant::class.java,
                    ZonedDateTime::class.java,
                    Locale::class.java,
                    String::class.java,
                    String::class.java,
                ).also { it.isAccessible = true }
        }.getOrNull()
    }

    private fun stamp(
        ts: ZonedDateTime,
        locale: Locale,
        yesterday: String,
        monthDay: String
    ): String {
        val f = assertNotNull(fn, "ChatListCopyKt.chatListTimestamp(Instant, ZonedDateTime, Locale, String, String) is missing")
        return f.invoke(null, ts.toInstant(), now, locale, yesterday, monthDay) as String
    }

    @Test
    fun yesterday_is_the_given_word() {
        // 22:30 the day before is "yesterday" by the calendar, though under 24 hours have passed.
        assertEquals("Ayer", stamp(now.minusDays(1).withHour(22).withMinute(30), es, "Ayer", "d MMM"))
        assertEquals("Yesterday", stamp(now.minusDays(1), Locale.US, "Yesterday", "MMM d"))
    }

    @Test
    fun an_older_date_uses_the_given_pattern_and_locale() {
        val tenDaysBack = now.minusDays(10)
        assertEquals("3 sept", stamp(tenDaysBack, es, "Ayer", "d MMM"))
        assertEquals("Sep 3", stamp(tenDaysBack, Locale.US, "Yesterday", "MMM d"))
    }

    @Test
    fun the_weekday_follows_the_given_locale_and_the_time_stays_24_hour() {
        // 10 Sep 2026 is a Thursday.
        assertEquals("jue", stamp(now.minusDays(3), es, "Ayer", "d MMM"))
        assertEquals("Thu", stamp(now.minusDays(3), Locale.US, "Yesterday", "MMM d"))
        assertEquals("15:45", stamp(now.minusMinutes(0), Locale.US, "Yesterday", "MMM d"))
        assertEquals("09:05", stamp(now.withHour(9).withMinute(5), es, "Ayer", "d MMM"))
    }

    @Test
    fun the_list_resolves_the_month_day_pattern_from_the_phone() {
        val src =
            KotlinUiLiterals
                .lex(ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatListView.kt"))
        val code = src.blanked
        val literals = src.literals.map { it.text }
        assertTrue("getBestDateTimePattern(" in code, "ChatListView must ask the phone for its month-day order")
        assertTrue("MMMd" in literals, "the skeleton handed to getBestDateTimePattern must be \"MMMd\"")
        assertTrue("rememberDesiredFormatLocale()" in code, "the date must follow the language the app shows")
        assertTrue("chatListTimestamp(" in code, "the rows must use chatListTimestamp")
        assertFalse("MMM d" in literals, "the English-order pattern \"MMM d\" is still in ChatListView")
        assertFalse("fun formatTimestamp(" in code, "the English formatTimestamp is still in ChatListView")
    }
}
