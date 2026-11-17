package co.electriccoin.zcash.ui.screen.chat.view

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Body of the empty chat list's "What makes it private?" dialog in English. The dialog shows the resource
 * chat_list_privacy_body (the same text, and a Spanish one); JVM tests pin this English against the transports and
 * against the resource.
 */
internal fun emptyChatPrivacyBody(): String =
    "Shielded messages travel inside Zcash transactions. Open and Tunnel messages are end-to-end encrypted " +
        "and pass through three Nostr relays, one of which ZChat runs. Relays can't read them, but they can " +
        "see your chat key, your IP address, when you're online, and which chat keys you send to. Relays " +
        "may keep logs."

private const val DAYS_IN_A_WEEK = 7L

/**
 * ES-A (fix-all 2.13.0, train T4c): the chat list's row time, in the language the app shows.
 *
 * Calendar days are counted in [now]'s zone (the phone's), not elapsed 24-hour periods, so a message from 22:30
 * yesterday reads [yesterday] at 09:00. Today stays "HH:mm" (a localized short time would turn an en-US "15:45" into
 * "3:45 PM" and ignore the phone's 24-hour setting); under a week the short weekday in [locale]; older
 * [monthDayPattern] in [locale]. The caller resolves the pattern from the phone (DateFormat.getBestDateTimePattern
 * with "MMMd"), so this stays a pure JVM function.
 */
@JvmOverloads
internal fun chatListTimestamp(
    ts: Instant,
    now: ZonedDateTime,
    locale: Locale,
    yesterday: String,
    monthDayPattern: String,
    // QA2 Q2-47: the phone's 12/24-hour setting (DateFormat.is24HourFormat); true keeps "HH:mm".
    is24Hour: Boolean = true,
): String {
    val zone = now.zone
    val at = ts.atZone(zone)
    val daysBetween = ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())
    return when {
        daysBetween == 0L -> clockTime(at, locale, is24Hour)
        daysBetween == 1L -> yesterday
        daysBetween < DAYS_IN_A_WEEK -> DateTimeFormatter.ofPattern("EEE", locale).format(at)
        else -> DateTimeFormatter.ofPattern(monthDayPattern, locale).format(at)
    }
}

/**
 * QA2 Q2-47: a clock time as the phone's 12/24-hour setting wants it: "HH:mm" when [is24Hour], else the short time of
 * [locale] ("3:45 PM"). Used by the chat list, the chat and its cached view.
 */
internal fun clockTime(
    at: ZonedDateTime,
    locale: Locale,
    is24Hour: Boolean,
): String =
    if (is24Hour) {
        DateTimeFormatter.ofPattern("HH:mm").format(at)
    } else {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
    }
