package space.iamjkc.shiftbell

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Asks the PC first (a confirm there a moment ago stops this alarm), then rings whatever is still due and
 * sets the next system alarm. The read is capped, so no network delays the alarm by more than that.
 */
private fun BroadcastReceiver.checkAndRing(context: Context) {
    val pending = goAsync()
    Thread {
        try {
            if (PhoneSync.paired(context)) PhoneSync.roundWithin(context, PRE_RING_SYNC_MS)
            RingService.ringIfDue(context)
            Scheduler.reschedule(context)
        } finally {
            pending.finish()
        }
    }.start()
}

/** Well inside the ~10 s a receiver may run, and short enough that nobody notices the alarm waiting. */
private const val PRE_RING_SYNC_MS = 4_000L

/** The system alarm fired: ring whatever is due, then set the next one. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = checkAndRing(context)
}

/**
 * Reboot, app update, clock or time-zone change: the system alarm may be gone or wrong, so set it again.
 * Also rings at once if an alarm was missed while the phone was off and is still within the catch-up window.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        checkAndRing(context)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
