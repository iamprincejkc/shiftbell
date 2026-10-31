package space.iamjkc.shiftbell.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale
import kotlin.math.ceil

enum class AlarmTone { TimeIn, TimeOut, Late }

/** Every word the alarm screen shows, decided in one place so it can be tested. Same words as the PC app. */
data class AlarmCopy(
    val tone: AlarmTone,
    val tag: String,
    val headline: String,
    val detail: String,
    val clock: String,
    val phrase: String,
    val confirmLabel: String,
    val ask: String,
) {
    companion object {
        private const val ATTENDANCE = "your attendance system"
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        fun of(alarm: DueAlarm, settings: ShiftbellSettings, now: LocalDateTime): AlarmCopy {
            val timeIn = alarm.kind == AlarmKind.TimeIn
            val phrase = ShiftbellSettings.confirmationPhrase(alarm.kind)
            val tag = if (timeIn) "TIME IN" else "TIME OUT"
            val confirm = if (timeIn) "I’ve timed in" else "I’ve timed out"
            val ask = if (timeIn) "Timed in?" else "Timed out?"
            val verb = if (timeIn) "Log in to" else "Log out of"
            val clock = time(now)
            val tone = if (timeIn) AlarmTone.TimeIn else AlarmTone.TimeOut
            val headline = if (timeIn) "Time in now" else "Time out now"
            val deadline = alarm.deadline

            if (deadline != null && now > deadline) {
                return AlarmCopy(
                    AlarmTone.Late,
                    tag,
                    if (timeIn) "You’re late to time in" else "You’re late to time out",
                    "It was due at ${time(deadline)}. $verb $ATTENDANCE now.",
                    "$clock · ${span(Duration.between(deadline, now))} late",
                    phrase,
                    confirm,
                    ask,
                )
            }

            if (deadline != null) {
                val detail = if (timeIn) {
                    "$verb $ATTENDANCE before ${time(deadline)}."
                } else {
                    "$verb $ATTENDANCE before ${time(deadline)}, then you can go."
                }
                return AlarmCopy(tone, tag, headline, detail, "$clock · ${span(Duration.between(now, deadline))} left", phrase, confirm, ask)
            }

            val ringing = Duration.between(alarm.scheduledAt, now)
            val detail = if (timeIn) {
                "Your shift starts at ${time(settings.timeIn.ringAt)}. $verb $ATTENDANCE."
            } else {
                "Your shift ended at ${time(settings.timeOut.ringAt)}. $verb $ATTENDANCE before you leave."
            }
            val ringingClock = if (ringing >= Duration.ofMinutes(1)) "$clock · ringing for ${span(ringing)}" else clock
            return AlarmCopy(tone, tag, headline, detail, ringingClock, phrase, confirm, ask)
        }

        fun snooze(settings: ShiftbellSettings, alarm: DueAlarm): String =
            if (alarm.snoozesLeft > 0) "Snooze ${settings.snoozeMinutes} min · ${alarm.snoozesLeft} left" else "No snoozes left"

        /** Case and surrounding spaces do not matter: " DONE " counts. */
        fun matches(typed: String, kind: AlarmKind): Boolean =
            typed.trim().split(Regex("\\s+")).joinToString(" ")
                .equals(ShiftbellSettings.confirmationPhrase(kind), ignoreCase = true)

        fun time(value: TemporalAccessor): String = TIME.format(value)

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
