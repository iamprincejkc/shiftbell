package space.iamjkc.shiftbell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import space.iamjkc.shiftbell.core.AlarmPlanner
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Keeps exactly one system alarm set: the next moment Shiftbell must ring. It uses
 * [AlarmManager.setAlarmClock], the call the clock app uses. That alarm is exact, wakes the phone from Doze,
 * and shows the alarm icon in the status bar. Silent mode and Do Not Disturb do not stop it firing.
 */
object Scheduler {
    fun reschedule(context: Context) {
        val now = LocalDateTime.now()
        val next = AlarmPlanner.next(Store.settings(context), Store.state(context, now), now)
        val manager = context.getSystemService(AlarmManager::class.java)
        val fire = fireIntent(context)
        if (next == null) {
            manager.cancel(fire)
            return
        }

        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (manager.canScheduleExactAlarms()) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp(context)), fire)
        } else {
            // Only if the permission was revoked by hand. Late by minutes beats never; the app warns about it.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    private fun fireIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
