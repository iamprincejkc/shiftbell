package space.iamjkc.shiftbell.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** An alarm that should be ringing right now. [date] is the work day an acknowledgement is recorded against. */
data class DueAlarm(
    val kind: AlarmKind,
    val date: LocalDate,
    val scheduledAt: LocalDateTime,
    val deadline: LocalDateTime?,
    val snoozesLeft: Int,
)

data class UpcomingAlarm(val kind: AlarmKind, val at: LocalDateTime)

/** Pure decisions about alarms: settings + state + the time in, answer out. No clocks, no I/O. */
object AlarmPlanner {
    /** A missed alarm still rings this long after its time even with catch-up set to 0 hours. */
    private val MINIMUM_GRACE: Duration = Duration.ofMinutes(2)

    /** The alarm that should ring now, earliest first, or null. */
    fun dueNow(settings: ShiftbellSettings, state: ShiftbellState, now: LocalDateTime): DueAlarm? {
        val today = now.toLocalDate()
        // Yesterday too, so an alarm late in the evening can still catch up after midnight.
        return listOf(today.minusDays(1), today)
            .flatMap { date -> AlarmKind.entries.mapNotNull { evaluate(settings, state, it, date, now) } }
            .minByOrNull { it.scheduledAt }
    }

    /** False once the ringing alarm was confirmed, snoozed or made a day off elsewhere - in practice, on the PC. */
    fun stillDue(settings: ShiftbellSettings, state: ShiftbellState, alarm: DueAlarm, now: LocalDateTime): Boolean =
        evaluate(settings, state, alarm.kind, alarm.date, now) != null

    private fun evaluate(
        settings: ShiftbellSettings,
        state: ShiftbellState,
        kind: AlarmKind,
        date: LocalDate,
        now: LocalDateTime,
    ): DueAlarm? {
        val alarm = settings.of(kind)
        if (!alarm.enabled || date.dayOfWeek !in settings.workDays) return null

        val scheduled = date.atTime(alarm.ringAt)
        if (scheduled < state.armedSince) return null

        val day = state.find(date)
        if (day?.dayOff == true) return null

        val record = day?.of(kind)
        if (record?.acknowledgedAt != null) return null

        val snoozed = record?.snoozedUntil
        val ringFrom = if (snoozed != null && snoozed > scheduled) snoozed else scheduled
        val grace = maxOf(Duration.ofHours(settings.catchUpHours.toLong()), MINIMUM_GRACE)
        if (now < ringFrom || now > ringFrom + grace) return null

        return DueAlarm(
            kind = kind,
            date = date,
            scheduledAt = scheduled,
            deadline = alarm.deadline?.let { date.atTime(it) },
            snoozesLeft = maxOf(0, settings.maxSnoozes - (record?.snoozes ?: 0)),
        )
    }

    /**
     * The next moment an alarm will start ringing after [now], snooze ends included, looking two weeks ahead.
     * This is what the phone's alarm clock is set to.
     */
    fun next(settings: ShiftbellSettings, state: ShiftbellState, now: LocalDateTime): UpcomingAlarm? {
        val today = now.toLocalDate()
        for (offset in 0L..14L) {
            val date = today.plusDays(offset)
            val day = state.find(date)
            if (date.dayOfWeek !in settings.workDays || day?.dayOff == true) continue

            val next = AlarmKind.entries
                .filter { settings.of(it).enabled && day?.of(it)?.acknowledgedAt == null }
                .map { UpcomingAlarm(it, day?.of(it)?.snoozedUntil ?: date.atTime(settings.of(it).ringAt)) }
                .filter { it.at > now && it.at >= state.armedSince }
                .minByOrNull { it.at }
            if (next != null) return next
        }

        return null
    }
}
