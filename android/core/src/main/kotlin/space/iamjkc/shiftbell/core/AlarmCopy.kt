package space.iamjkc.shiftbell.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAccessor
import java.util.Locale
import kotlin.math.ceil

enum class AlarmTone { TimeIn, TimeOut, Custom, Late }

/**
 * Every word the alarm screen shows, decided in one place so it can be tested. Same words as the PC app.
 * [ask] is the question before the phrase ("Timed in?", "Ready?"); [openLabel] is the link button's text.
 */
data class AlarmCopy(
    val tone: AlarmTone,
    val tag: String,
    val headline: String,
    val detail: String,
    val clock: String,
    val phrase: String,
    val confirmLabel: String,
    val ask: String,
    val openLabel: String,
) {
    companion object {
        private const val ATTENDANCE = "your attendance system"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private val ONE_OFF: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

        fun of(due: DueAlarm, now: LocalDateTime): AlarmCopy {
            val alarm = due.alarm
            val phrase = ShiftbellSettings.CONFIRMATION_PHRASE
            val clock = clockLine(due, now)
            val deadline = due.deadline
            val late = deadline != null && now > deadline

            if (alarm.role == AlarmRole.Custom) {
                val detail = when {
                    deadline != null && late -> "It was due at ${time(deadline)}."
                    deadline != null -> "Due by ${time(deadline)}."
                    else -> "Set for ${time(alarm.ringAt)}."
                }
                return AlarmCopy(
                    if (late) AlarmTone.Late else AlarmTone.Custom,
                    alarm.name.uppercase(),
                    if (late) "${alarm.name} is late" else alarm.name,
                    detail,
                    clock,
                    phrase,
                    if (alarm.strict) "Done" else "Dismiss",
                    "Ready?",
                    "Open link",
                )
            }

            val timeIn = alarm.role == AlarmRole.TimeIn
            val tag = if (timeIn) "TIME IN" else "TIME OUT"
            val confirm = if (timeIn) "I’ve timed in" else "I’ve timed out"
            val ask = if (timeIn) "Timed in?" else "Timed out?"
            val verb = if (timeIn) "Log in to" else "Log out of"

            if (deadline != null && late) {
                return AlarmCopy(
                    AlarmTone.Late,
                    tag,
                    if (timeIn) "You’re late to time in" else "You’re late to time out",
                    "It was due at ${time(deadline)}. $verb $ATTENDANCE now.",
                    clock,
                    phrase,
                    confirm,
                    ask,
                    "Open attendance",
                )
            }

            val detail = when {
                deadline != null && timeIn -> "$verb $ATTENDANCE before ${time(deadline)}."
                deadline != null -> "$verb $ATTENDANCE before ${time(deadline)}, then you can go."
                timeIn -> "Your shift starts at ${time(alarm.ringAt)}. $verb $ATTENDANCE."
                else -> "Your shift ended at ${time(alarm.ringAt)}. $verb $ATTENDANCE before you leave."
            }
            return AlarmCopy(
                if (timeIn) AlarmTone.TimeIn else AlarmTone.TimeOut,
                tag,
                if (timeIn) "Time in now" else "Time out now",
                detail,
                clock,
                phrase,
                confirm,
                ask,
                "Open attendance",
            )
        }

        private fun clockLine(due: DueAlarm, now: LocalDateTime): String {
            val clock = time(now)
            val deadline = due.deadline
            if (deadline != null) {
                return if (now > deadline) {
                    "$clock · ${span(Duration.between(deadline, now))} late"
                } else {
                    "$clock · ${span(Duration.between(now, deadline))} left"
                }
            }
            val ringing = Duration.between(due.scheduledAt, now)
            return if (ringing >= Duration.ofMinutes(1)) "$clock · ringing for ${span(ringing)}" else clock
        }

        fun snooze(settings: ShiftbellSettings, alarm: DueAlarm): String =
            if (alarm.snoozesLeft > 0) "Snooze ${settings.snoozeMinutes} min · ${alarm.snoozesLeft} left" else "No snoozes left"

        /** Case and surrounding spaces do not matter: " DONE " counts. */
        fun matches(typed: String): Boolean = typed.trim().equals(ShiftbellSettings.CONFIRMATION_PHRASE, ignoreCase = true)

        fun time(value: TemporalAccessor): String = TIME.format(value)

        /** "Mon–Fri", "Every day", "Weekends", "Mon, Wed", or a one-off "Fri Nov 6". */
        fun repeat(alarm: Alarm): String {
            alarm.date?.let { return ONE_OFF.format(it) }
            val days = alarm.days.toSet()
            return when {
                days.size == 7 -> "Every day"
                days == Alarm.WEEKDAYS.toSet() -> "Mon–Fri"
                days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
                days.isEmpty() -> "Never"
                else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.US) }
            }
        }

        /** "under 1 min", "14 min", "1 h 5 min". Rounded up, so "1 min left" never means zero. */
        fun span(span: Duration): String {
            val minutes = ceil(maxOf(0.0, span.toMillis() / 60_000.0)).toInt()
            return when {
                minutes < 1 -> "under 1 min"
                minutes < 60 -> "$minutes min"
                minutes % 60 == 0 -> "${minutes / 60} h"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }
    }
}
