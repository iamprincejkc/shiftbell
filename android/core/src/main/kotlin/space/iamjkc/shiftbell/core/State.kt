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
    /** Each alarm's record for the day, by alarm id. */
    val alarms: Map<String, AlarmRecord> = emptyMap(),
    // 0.3 records, moved into [alarms] by [ShiftbellState.migrate] and never written again.
    val timeIn: AlarmRecord? = null,
    val timeOut: AlarmRecord? = null,
) {
    fun of(alarmId: String): AlarmRecord? = alarms[alarmId]

    fun with(alarmId: String, record: AlarmRecord): DayLog = copy(alarms = alarms + (alarmId to record))
}

/**
 * The persisted record of acknowledgements. Alarms are decided from this plus the clock, never from an
 * in-memory timer, so a reboot, a killed process or a clock change can never lose an alarm.
 */
@Serializable
data class ShiftbellState(
    /**
     * Alarms scheduled before this moment never ring. Set on first run, so installing at 10:00 does not fire a
     * "late" alarm at once. Starting the app does not move it, which is what lets a missed alarm catch up after the
     * phone was off. Each alarm also has its own [Alarm.armedAt] for edits.
     */
    @Serializable(LocalDateTimeSerializer::class) val armedSince: LocalDateTime,
    val days: List<DayLog> = emptyList(),
    /** The relay's clock (ms) at the last successful read; the next read starts a little before it. */
    val syncSince: Long = 0,
    /** Local changes not yet accepted by the relay. Kept on disk, so a confirm made offline still reaches the PC. */
    val outbox: List<SyncEvent> = emptyList(),
    /** Whether every alarm definition has been sent since pairing; until then the PC may not have them. */
    val alarmsPublished: Boolean = false,
) {
    fun find(date: LocalDate): DayLog? = days.firstOrNull { it.date == date }

    fun acknowledge(alarmId: String, date: LocalDate, now: LocalDateTime): ShiftbellState =
        update(date) { day -> day.with(alarmId, (day.of(alarmId) ?: AlarmRecord()).copy(acknowledgedAt = now, snoozedUntil = null)) }

    fun snooze(alarmId: String, date: LocalDate, until: LocalDateTime): ShiftbellState =
        update(date) { day ->
            val record = day.of(alarmId) ?: AlarmRecord()
            day.with(alarmId, record.copy(snoozedUntil = until, snoozes = record.snoozes + 1))
        }

    fun setDayOff(date: LocalDate, off: Boolean, at: LocalDateTime): ShiftbellState =
        update(date) { it.copy(dayOff = off, dayOffChangedAt = at) }

    fun prune(today: LocalDate): ShiftbellState =
        copy(days = days.filter { it.date >= today.minusDays(KEEP_DAYS) && it.date <= today.plusDays(KEEP_DAYS) })

    /** Moves 0.3's fixed time-in and time-out records into the per-alarm map. */
    fun migrate(): ShiftbellState = copy(
        days = days.map { day ->
            var alarms = day.alarms
            day.timeIn?.let { if (Alarm.TIME_IN_ID !in alarms) alarms = alarms + (Alarm.TIME_IN_ID to it) }
            day.timeOut?.let { if (Alarm.TIME_OUT_ID !in alarms) alarms = alarms + (Alarm.TIME_OUT_ID to it) }
            day.copy(alarms = alarms, timeIn = null, timeOut = null)
        },
    )

    internal fun update(date: LocalDate, change: (DayLog) -> DayLog): ShiftbellState {
        val day = change(find(date) ?: DayLog(date))
        return copy(days = days.filter { it.date != date } + day)
    }

    companion object {
        const val KEEP_DAYS = 90L
    }
}
