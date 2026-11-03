package space.iamjkc.shiftbell.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** An alarm that should be ringing right now. [date] is the day an acknowledgement is recorded against. */
data class DueAlarm(
    val alarm: Alarm,
    val date: LocalDate,
    val scheduledAt: LocalDateTime,
    val deadline: LocalDateTime?,
    val snoozesLeft: Int,
) {
    val id: String get() = alarm.id
}

data class UpcomingAlarm(val alarm: Alarm, val at: LocalDateTime)

/** Pure decisions about alarms: settings + state + the time in, answer out. No clocks, no I/O. */
object AlarmPlanner {
    /** A missed alarm still rings this long after its time even with catch-up set to 0 hours. */
    private val MINIMUM_GRACE: Duration = Duration.ofMinutes(2)

    /** The alarm that should ring now, earliest first, or null. */
    fun dueNow(settings: ShiftbellSettings, state: ShiftbellState, now: LocalDateTime): DueAlarm? {
        val today = now.toLocalDate()
        // Yesterday too, so an alarm late in the evening can still catch up after midnight.
        return listOf(today.minusDays(1), today)
            .flatMap { date -> settings.active.mapNotNull { evaluate(settings, state, it, date, now) } }
            .minByOrNull { it.scheduledAt }
    }

    /**
     * False once the ringing alarm was confirmed, snoozed, deleted, turned off or made a day off elsewhere -
     * in practice, on the PC through sync.
     */
    fun stillDue(settings: ShiftbellSettings, state: ShiftbellState, alarm: DueAlarm, now: LocalDateTime): Boolean =
        settings.find(alarm.id)?.let { evaluate(settings, state, it, alarm.date, now) } != null

    private fun evaluate(settings: ShiftbellSettings, state: ShiftbellState, alarm: Alarm, date: LocalDate, now: LocalDateTime): DueAlarm? {
        if (!alarm.enabled || !alarm.ringsOn(date)) return null

        val scheduled = date.atTime(alarm.ringAt)
        if (scheduled < state.armedSince || scheduled < alarm.armedAt) return null

        val day = state.find(date)
        if (day?.dayOff == true) return null

        val record = day?.of(alarm.id)
        if (record?.acknowledgedAt != null) return null

        val snoozed = record?.snoozedUntil
        val ringFrom = if (snoozed != null && snoozed > scheduled) snoozed else scheduled
        val grace = maxOf(Duration.ofHours(settings.catchUpHours.toLong()), MINIMUM_GRACE)
        if (now < ringFrom || now > ringFrom + grace) return null

        return DueAlarm(
            alarm = alarm,
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
            if (day?.dayOff == true) continue

            val next = settings.active
                .filter { it.enabled && it.ringsOn(date) && day?.of(it.id)?.acknowledgedAt == null }
                .map { UpcomingAlarm(it, day?.of(it.id)?.snoozedUntil ?: date.atTime(it.ringAt)) }
                .filter { it.at > now && it.at >= state.armedSince && it.at >= it.alarm.armedAt }
                .minByOrNull { it.at }
            if (next != null) return next
        }

        return null
    }
}
