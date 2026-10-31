package space.iamjkc.shiftbell.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalTime

enum class AlarmKind { TimeIn, TimeOut }

/** One daily alarm: when it rings, and the time by which the punch must be done. */
@Serializable
data class AlarmSettings(
    val enabled: Boolean = true,
    @Serializable(LocalTimeSerializer::class) val ringAt: LocalTime,
    /** When the punch is due. Null means "no deadline" (time-out). Never earlier than [ringAt]. */
    @Serializable(LocalTimeSerializer::class) val deadline: LocalTime? = null,
)

@Serializable
data class ShiftbellSettings(
    val timeIn: AlarmSettings = AlarmSettings(ringAt = LocalTime.of(7, 45), deadline = LocalTime.of(8, 0)),
    val timeOut: AlarmSettings = AlarmSettings(ringAt = LocalTime.of(17, 0)),
    val workDays: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    ),
    /** The page the alarm's "Open attendance" button opens. Empty hides the button. */
    val attendanceLink: String = DEFAULT_ATTENDANCE_LINK,
    val snoozeMinutes: Int = 5,
    val maxSnoozes: Int = 2,
    /** How long after its ring time a missed alarm (phone off) still rings once the phone is back. */
    val catchUpHours: Int = 4,
    /** Dismissing needs the confirmation phrase typed, so the alarm cannot be swiped away unread. */
    val requireTypedConfirmation: Boolean = true,
    /**
     * While ringing, raise the phone's alarm volume to at least this share of its maximum, then put it back.
     * 0 leaves it alone. It only rescues a turned-down alarm volume; loudness comes from [RingLoudness].
     */
    val minimumAlarmVolumePercent: Int = 30,
    /** Vibrate as well as ring. Vibration also works with the phone on silent. */
    val vibrate: Boolean = true,
    /** The pairing code shared with the PC (docs/sync.md). Empty means this phone does not sync. */
    val syncCode: String = "",
) {
    fun of(kind: AlarmKind): AlarmSettings = if (kind == AlarmKind.TimeIn) timeIn else timeOut

    /** Clamps every value into the range the app can honour, so a bad file cannot break the planner. */
    fun normalized(): ShiftbellSettings = copy(
        timeIn = normalize(timeIn),
        timeOut = normalize(timeOut),
        workDays = workDays.distinct().sorted(),
        attendanceLink = attendanceLink.trim(),
        snoozeMinutes = snoozeMinutes.coerceIn(1, 60),
        maxSnoozes = maxSnoozes.coerceIn(0, 10),
        catchUpHours = catchUpHours.coerceIn(0, 12),
        minimumAlarmVolumePercent = minimumAlarmVolumePercent.coerceIn(0, 100),
        syncCode = SyncKeys.fromCode(syncCode)?.code ?: "",
    )

    companion object {
        /**
         * None built in: the attendance page is the user's own (an employer portal), set once in the app and
         * kept in its settings file, never in the published source.
         */
        const val DEFAULT_ATTENDANCE_LINK = ""

        /** What you type to stop either alarm. The owner chose "done" (2026-10-31); the screen still asks "Timed in?". */
        @Suppress("UNUSED_PARAMETER")
        fun confirmationPhrase(kind: AlarmKind): String = "done"

        private fun normalize(alarm: AlarmSettings): AlarmSettings {
            val deadline = alarm.deadline
            return if (deadline != null && deadline < alarm.ringAt) alarm.copy(deadline = alarm.ringAt) else alarm
        }
    }
}
