package space.iamjkc.shiftbell.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

/** What happened to one alarm on one day. */
@Serializable
data class AlarmRecord(
    @Serializable(LocalDateTimeSerializer::class) val acknowledgedAt: LocalDateTime? = null,
    @Serializable(LocalDateTimeSerializer::class) val snoozedUntil: LocalDateTime? = null,
    val snoozes: Int = 0,
)

@Serializable
data class DayLog(
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    /** Holiday or leave: no alarm rings on this day. */
    val dayOff: Boolean = false,
    /** When [dayOff] last changed, on any device. The latest change wins a sync. */
    @Serializable(LocalDateTimeSerializer::class) val dayOffChangedAt: LocalDateTime? = null,
    val timeIn: AlarmRecord = AlarmRecord(),
    val timeOut: AlarmRecord = AlarmRecord(),
) {
    fun of(kind: AlarmKind): AlarmRecord = if (kind == AlarmKind.TimeIn) timeIn else timeOut

    fun with(kind: AlarmKind, record: AlarmRecord): DayLog =
        if (kind == AlarmKind.TimeIn) copy(timeIn = record) else copy(timeOut = record)
}

/**
 * The persisted record of acknowledgements. Alarms are decided from this plus the clock, never from an
 * in-memory timer, so a reboot, a killed process or a clock change can never lose an alarm.
 */
@Serializable
data class ShiftbellState(
    /**
     * Alarms scheduled before this moment never ring. Set on first run and whenever ring times change,
     * so installing at 10:00 or moving the time-in earlier does not fire a "late" alarm at once.
     * Starting the app does not move it, which is what lets a missed alarm catch up after the phone was off.
     */
    @Serializable(LocalDateTimeSerializer::class) val armedSince: LocalDateTime,
    val days: List<DayLog> = emptyList(),
    /** The relay's clock (ms) at the last successful read; the next read starts a little before it. */
    val syncSince: Long = 0,
    /** Local changes not yet accepted by the relay. Kept on disk, so a confirm made offline still reaches the PC. */
    val outbox: List<SyncEvent> = emptyList(),
) {
    fun find(date: LocalDate): DayLog? = days.firstOrNull { it.date == date }

    fun acknowledge(kind: AlarmKind, date: LocalDate, now: LocalDateTime): ShiftbellState =
        update(date) { it.with(kind, it.of(kind).copy(acknowledgedAt = now, snoozedUntil = null)) }

    fun snooze(kind: AlarmKind, date: LocalDate, until: LocalDateTime): ShiftbellState =
        update(date) { it.with(kind, it.of(kind).let { r -> r.copy(snoozedUntil = until, snoozes = r.snoozes + 1) }) }

    fun setDayOff(date: LocalDate, off: Boolean, at: LocalDateTime): ShiftbellState =
        update(date) { it.copy(dayOff = off, dayOffChangedAt = at) }

    fun prune(today: LocalDate): ShiftbellState =
        copy(days = days.filter { it.date >= today.minusDays(KEEP_DAYS) && it.date <= today.plusDays(KEEP_DAYS) })

    private fun update(date: LocalDate, change: (DayLog) -> DayLog): ShiftbellState {
        val day = change(find(date) ?: DayLog(date))
        return copy(days = days.filter { it.date != date } + day)
    }

    companion object {
        const val KEEP_DAYS = 90L
    }
}
