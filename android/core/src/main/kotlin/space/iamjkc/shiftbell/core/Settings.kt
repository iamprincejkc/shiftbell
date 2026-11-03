package space.iamjkc.shiftbell.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Decides the alarm's wording and colour. Time in and time out speak about the attendance punch. */
enum class AlarmRole { TimeIn, TimeOut, Custom }

/**
 * One alarm in the list: the two presets (time in, time out) or one the owner added, like a standup.
 * docs/decisions/0002-alarm-list.md describes every field and how the list syncs; the PC app mirrors it.
 */
@Serializable
data class Alarm(
    val id: String,
    val role: AlarmRole = AlarmRole.Custom,
    val name: String = "",
    val enabled: Boolean = true,
    @Serializable(LocalTimeSerializer::class) val ringAt: LocalTime,
    /** When it is due; after this it says "late". Never earlier than [ringAt]. */
    @Serializable(LocalTimeSerializer::class) val deadline: LocalTime? = null,
    /** Weekdays it repeats on. Ignored when [date] is set. */
    val days: List<DayOfWeek> = emptyList(),
    /** A one-off alarm's date. */
    @Serializable(LocalDateSerializer::class) val date: LocalDate? = null,
    /** True: stopped by typing "done". False: one Dismiss button. */
    val strict: Boolean = true,
    /** What the alarm's button opens: the attendance page, a meeting link. Empty hides the button. */
    val link: String = "",
    /** When its time, days or date last changed. Occurrences scheduled before this never ring. */
    @Serializable(LocalDateTimeSerializer::class) val armedAt: LocalDateTime = NEVER_ARMED,
    /** When anything about it last changed, on any device. The newest wins a sync. */
    @Serializable(LocalDateTimeSerializer::class) val updatedAt: LocalDateTime = NEVER_EDITED,
    /** Deleted, kept as a tombstone so the deletion reaches the other devices. */
    val deleted: Boolean = false,
) {
    fun ringsOn(date: LocalDate): Boolean = this.date?.let { it == date } ?: (date.dayOfWeek in days)

    /** True when the change from [before] moves when it rings, so it must re-arm. */
    fun scheduleDiffers(before: Alarm): Boolean =
        ringAt != before.ringAt || date != before.date || days.sorted() != before.days.sorted() || enabled != before.enabled

    fun normalized(): Alarm {
        val trimmed = name.trim().ifEmpty {
            when (role) {
                AlarmRole.TimeIn -> "Time in"
                AlarmRole.TimeOut -> "Time out"
                AlarmRole.Custom -> "Alarm"
            }
        }
        return copy(
            name = trimmed.take(60),
            deadline = deadline?.let { if (it < ringAt) ringAt else it },
            days = days.distinct().sorted(),
            link = link.trim(),
        )
    }

    companion object {
        const val TIME_IN_ID = "time-in"
        const val TIME_OUT_ID = "time-out"

        /** The PC's DateTime.MinValue, so an unarmed alarm reads the same on both sides of a sync. */
        val NEVER_ARMED: LocalDateTime = LocalDateTime.of(1, 1, 1, 0, 0)

        /** Before any real edit; a preset built from 0.3 settings loses to any change made on another device. */
        val NEVER_EDITED: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)

        val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        fun newId(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun timeIn() = Alarm(
            id = TIME_IN_ID, role = AlarmRole.TimeIn, name = "Time in", ringAt = LocalTime.of(7, 45), deadline = LocalTime.of(8, 0), days = WEEKDAYS,
        )

        fun timeOut() = Alarm(id = TIME_OUT_ID, role = AlarmRole.TimeOut, name = "Time out", ringAt = LocalTime.of(17, 0), days = WEEKDAYS)
    }
}

@Serializable
data class ShiftbellSettings(
    /** Every alarm, deleted ones included as tombstones. Null only in 0.3 files, which [normalized] migrates. */
    val alarms: List<Alarm>? = null,
    val snoozeMinutes: Int = 5,
    val maxSnoozes: Int = 2,
    /** How long after its ring time a missed alarm (phone off) still rings once the phone is back. */
    val catchUpHours: Int = 4,
    /**
     * While ringing, raise the phone's alarm volume to at least this share of its maximum, then put it back.
     * 0 leaves it alone. It only rescues a turned-down alarm volume; loudness comes from [RingLoudness].
     */
    val minimumAlarmVolumePercent: Int = 30,
    /** Vibrate as well as ring. Vibration also works with the phone on silent. */
    val vibrate: Boolean = true,
    /** The pairing code shared with the PC (docs/sync.md). Empty means this phone does not sync. */
    val syncCode: String = "",
    // 0.3 fields, read once to build the presets and never written again.
    val timeIn: AlarmSettings? = null,
    val timeOut: AlarmSettings? = null,
    val workDays: List<DayOfWeek>? = null,
    val attendanceLink: String? = null,
    val requireTypedConfirmation: Boolean? = null,
) {
    /** The alarms that exist, in the order they ring. */
    @Transient
    val active: List<Alarm> = alarms.orEmpty().filter { !it.deleted }.sortedWith(compareBy<Alarm> { it.ringAt }.thenBy { it.name.lowercase() })

    fun find(id: String): Alarm? = alarms.orEmpty().firstOrNull { it.id == id && !it.deleted }

    /** Adds or replaces one alarm by id. */
    fun with(alarm: Alarm): ShiftbellSettings = copy(alarms = alarms.orEmpty().filter { it.id != alarm.id } + alarm.normalized())

    /** Clamps every value the app relies on and migrates 0.3 settings, so a bad file cannot break the planner. */
    fun normalized(): ShiftbellSettings = copy(
        alarms = (alarms ?: migrate()).filter { it.id.isNotEmpty() }.associateBy { it.id }.values.map { it.normalized() },
        timeIn = null,
        timeOut = null,
        workDays = null,
        attendanceLink = null,
        requireTypedConfirmation = null,
        snoozeMinutes = snoozeMinutes.coerceIn(1, 60),
        maxSnoozes = maxSnoozes.coerceIn(0, 10),
        catchUpHours = catchUpHours.coerceIn(0, 12),
        minimumAlarmVolumePercent = minimumAlarmVolumePercent.coerceIn(0, 100),
        syncCode = SyncKeys.fromCode(syncCode)?.code ?: "",
    )

    /** The two presets, from 0.3's fields when there are any, otherwise the defaults. */
    private fun migrate(): List<Alarm> {
        val days = workDays?.takeIf { it.isNotEmpty() } ?: Alarm.WEEKDAYS
        val link = attendanceLink.orEmpty()
        val strict = requireTypedConfirmation ?: true
        var timeInAlarm = Alarm.timeIn().copy(days = days, link = link, strict = strict)
        var timeOutAlarm = Alarm.timeOut().copy(days = days, link = link, strict = strict)
        timeIn?.let { timeInAlarm = timeInAlarm.copy(enabled = it.enabled, ringAt = it.ringAt, deadline = it.deadline) }
        timeOut?.let { timeOutAlarm = timeOutAlarm.copy(enabled = it.enabled, ringAt = it.ringAt, deadline = it.deadline) }
        return listOf(timeInAlarm, timeOutAlarm)
    }

    companion object {
        /** What you type to stop a strict alarm. The owner chose "done" (2026-10-31); the screen still asks "Timed in?". */
        const val CONFIRMATION_PHRASE = "done"
    }
}

/** 0.3's shape of the time-in and time-out alarms, kept only to read old settings files. */
@Serializable
data class AlarmSettings(
    val enabled: Boolean = true,
    @Serializable(LocalTimeSerializer::class) val ringAt: LocalTime,
    @Serializable(LocalTimeSerializer::class) val deadline: LocalTime? = null,
)
