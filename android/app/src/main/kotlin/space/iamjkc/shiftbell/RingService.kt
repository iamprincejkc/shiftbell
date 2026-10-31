package space.iamjkc.shiftbell

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import space.iamjkc.shiftbell.core.AlarmCopy
import space.iamjkc.shiftbell.core.AlarmKind
import space.iamjkc.shiftbell.core.AlarmPlanner
import space.iamjkc.shiftbell.core.DueAlarm
import space.iamjkc.shiftbell.core.RingLoudness
import space.iamjkc.shiftbell.core.SyncEvent
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Future

/**
 * Rings one alarm until it is confirmed or snoozed: the tone on the alarm volume, vibration, a full-screen
 * notification and the alarm screen. Silent mode cannot stop any of it, and a swipe cannot dismiss it.
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tone = AlarmTone()
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringingSince: LocalDateTime? = null
    private var savedAlarmVolume: Int? = null
    private var ticks = 0
    private var syncing: Future<*>? = null

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST -> {
                val kind = AlarmKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: AlarmKind.TimeIn.name)
                val fromPc = intent.getStringExtra(EXTRA_TEST_ID)
                val id = fromPc ?: SyncEvent.newTestId()
                PhoneSync.markTestSeen(id)
                // Pressed here: the PC rings it too. Rung because the PC pressed it: nothing to send back.
                if (fromPc == null && Ringing.session.value == null) PhoneSync.send(this, SyncEvent.test(kind, LocalDateTime.now(), id))
                startRinging(testAlarm(kind), isTest = true, testId = id)
            }
            ACTION_END_ELSEWHERE -> stopEverything()
            ACTION_CONFIRM -> finish(confirmed = true)
            ACTION_SNOOZE -> finish(confirmed = false)
            ACTION_STEP_ASIDE -> stepAside()
            // ACTION_RING, or null when Android restarts the service after killing it mid-ring.
            else -> {
                val now = LocalDateTime.now()
                val due = AlarmPlanner.dueNow(Store.settings(this), Store.state(this, now), now)
                when {
                    due != null -> startRinging(due, isTest = false)
                    Ringing.session.value == null -> {
                        // Nothing due after all (the window closed in between). Android still requires the
                        // service to go foreground once it was started that way, so do that and leave.
                        startForegroundCompat(placeholder())
                        stopEverything()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startRinging(alarm: DueAlarm, isTest: Boolean, testId: String? = null) {
        val running = Ringing.session.value
        if (running != null && !(running.isTest && !isTest)) {
            // Already ringing. Only a real alarm may replace a test one.
            startInForeground(running)
            return
        }

        val session = RingSession(alarm, Store.settings(this), isTest, testId = testId)
        Ringing.set(session)
        startInForeground(session)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Shiftbell:ring")
                .apply { acquire(WAKE_LOCK_LIMIT.toMillis()) }
        }

        raiseAlarmVolume(session)
        soundOn(session)
        showAlarmScreen()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun onTick() {
        val session = Ringing.session.value ?: return stopSelf()
        val now = LocalDateTime.now()
        ticks++

        // Confirmed, snoozed or made a day off on the PC: stop ringing here too. Not recorded again: sync already did.
        if (!session.isTest && !AlarmPlanner.stillDue(session.settings, Store.state(this, now), session.alarm, now)) {
            return stopEverything()
        }
        if (ticks % SYNC_EVERY_TICKS == 0 && syncing?.isDone != false && PhoneSync.paired(this)) {
            syncing = PhoneSync.start(this)
        }

        val docked = session.dockedUntil
        if (docked != null) {
            if (now >= docked) {
                // Three minutes are up and it is still not confirmed: take the screen back.
                Ringing.set(session.copy(dockedUntil = null))
                soundOn(session)
                showAlarmScreen()
            }
            startInForeground(Ringing.session.value ?: session)
            return
        }

        ringingSince?.let { tone.setGain(RingLoudness.gain(Duration.between(it, now))) }
        // Swiped home or switched apps: bring the alarm back every few seconds, like the PC version takes focus back.
        if (!Ringing.screenShowing && ticks % 5 == 0) showAlarmScreen()
        if (ticks % 30 == 0) startInForeground(session)
    }

    private fun stepAside() {
        val session = Ringing.session.value ?: return
        Ringing.set(session.copy(dockedUntil = LocalDateTime.now().plus(STEP_ASIDE)))
        soundOff()
        startInForeground(Ringing.session.value ?: session)
    }

    private fun finish(confirmed: Boolean) {
        val session = Ringing.session.value ?: return stopEverything()
        val alarm = session.alarm
        if (!confirmed && alarm.snoozesLeft <= 0) return
        session.testId?.let { PhoneSync.send(this, SyncEvent.testEnd(it, LocalDateTime.now())) }

        if (!session.isTest) {
            val now = LocalDateTime.now()
            if (confirmed) {
                Store.updateState(this) { it.acknowledge(alarm.kind, alarm.date, now) }
                PhoneSync.send(this, SyncEvent.ack(alarm.kind, alarm.date, now))
            } else {
                val until = now.plusMinutes(session.settings.snoozeMinutes.toLong())
                Store.updateState(this) { it.snooze(alarm.kind, alarm.date, until) }
                val used = Store.state(this).find(alarm.date)?.of(alarm.kind)?.snoozes ?: 1
                PhoneSync.send(this, SyncEvent.snoozed(alarm.kind, alarm.date, now, until, used))
            }
        }

        stopEverything()
    }

    private fun stopEverything() {
        Ringing.set(null)
        handler.removeCallbacks(tick)
        soundOff()
        restoreAlarmVolume()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        soundOff()
        restoreAlarmVolume()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun soundOn(session: RingSession) {
        ringingSince = LocalDateTime.now()
        tone.start(RingLoudness.gain(Duration.ZERO))
        if (session.settings.vibrate) {
            val pattern = longArrayOf(0, 500, 300, 500, 1_200)
            vibrator().vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        }
    }

    private fun soundOff() {
        ringingSince = null
        tone.stop()
        vibrator().cancel()
    }

    private fun vibrator() = getSystemService(VibratorManager::class.java).defaultVibrator

    /** Only lifts a turned-down alarm volume to the floor; never touches the ringer, media or a louder setting. */
    private fun raiseAlarmVolume(session: RingSession) {
        if (savedAlarmVolume != null) return
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val floor = (max * session.settings.minimumAlarmVolumePercent + 99) / 100
        if (current < floor) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0) }
                .onSuccess { savedAlarmVolume = current }
        }
    }

    private fun restoreAlarmVolume() {
        val saved = savedAlarmVolume ?: return
        savedAlarmVolume = null
        runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
    }

    /**
     * Opens the alarm screen. When the phone is locked or asleep, the full-screen notification already does this.
     * While the phone is in use Android only allows it with "Display over other apps"; without that the
     * notification stays pinned at the top and the tone keeps going.
     */
    private fun showAlarmScreen() {
        if (Ringing.screenShowing) return
        if (Settings.canDrawOverlays(this)) {
            runCatching { startActivity(alarmScreenIntent(this)) }
        }
    }

    private fun startInForeground(session: RingSession) = startForegroundCompat(notification(session))

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun placeholder(): Notification =
        Notification.Builder(this, ShiftbellApp.CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Checking alarms")
            .build()

    private fun notification(session: RingSession): Notification {
        val now = LocalDateTime.now()
        val copy = AlarmCopy.of(session.alarm, session.settings, now)
        val screen = PendingIntent.getActivity(
            this, 1, alarmScreenIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val docked = session.dockedUntil
        val text = if (docked != null) {
            val left = Duration.between(now, docked).coerceAtLeast(Duration.ZERO)
            "${copy.ask} Open this and type “${copy.phrase}”. Rings again in ${left.toMinutes()}:${"%02d".format(left.toSecondsPart())}."
        } else {
            copy.detail
        }

        return Notification.Builder(this, ShiftbellApp.CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_bell)
            .setColor(ContextCompat.getColor(this, R.color.amber))
            .setContentTitle(if (session.isTest) "Test: ${copy.headline}" else copy.headline)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 7

        /** While ringing, ask the PC every 3 s whether it was confirmed there. */
        private const val SYNC_EVERY_TICKS = 3
        private const val ACTION_RING = "space.iamjkc.shiftbell.RING"
        private const val ACTION_TEST = "space.iamjkc.shiftbell.TEST"
        private const val ACTION_CONFIRM = "space.iamjkc.shiftbell.CONFIRM"
        private const val ACTION_SNOOZE = "space.iamjkc.shiftbell.SNOOZE"
        private const val ACTION_STEP_ASIDE = "space.iamjkc.shiftbell.STEP_ASIDE"
        private const val ACTION_END_ELSEWHERE = "space.iamjkc.shiftbell.END_ELSEWHERE"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TEST_ID = "testId"

        /** How long "Let me do it now" keeps the alarm out of the way before it takes the screen back. */
        val STEP_ASIDE: Duration = Duration.ofMinutes(3)
        private val WAKE_LOCK_LIMIT: Duration = Duration.ofHours(1)

        /** Starts ringing if an alarm is due now. Safe to call from anywhere, any number of times. */
        fun ringIfDue(context: Context) {
            if (Ringing.session.value != null) return
            val now = LocalDateTime.now()
            if (AlarmPlanner.dueNow(Store.settings(context), Store.state(context, now), now) != null) {
                send(context, ACTION_RING)
            }
        }

        fun test(context: Context, kind: AlarmKind) =
            send(context, ACTION_TEST) { putExtra(EXTRA_KIND, kind.name) }

        /** Rings a test the PC started. May be refused when the app is in the background; that only skips a test. */
        fun testFromPc(context: Context, kind: AlarmKind, id: String) {
            runCatching { send(context, ACTION_TEST) { putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_TEST_ID, id) } }
        }

        /** The test ringing here was stopped on the PC. */
        fun endElsewhere(context: Context) = tell(context, ACTION_END_ELSEWHERE)

        // These come from the alarm screen, which is in the foreground, to a service that is already running.
        fun confirm(context: Context) = tell(context, ACTION_CONFIRM)

        fun snooze(context: Context) = tell(context, ACTION_SNOOZE)

        fun stepAside(context: Context) = tell(context, ACTION_STEP_ASIDE)

        /** Starts the service in the foreground: it must then call startForeground, which every path does. */
        private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, RingService::class.java).setAction(action).apply(extras)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun tell(context: Context, action: String) {
            context.startService(Intent(context, RingService::class.java).setAction(action))
        }

        fun alarmScreenIntent(context: Context): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /** A test alarm rings as if it were due now, with no deadline (so it never says "late"), and is never recorded. */
        private fun testAlarm(kind: AlarmKind): DueAlarm = DueAlarm(
            kind = kind,
            date = java.time.LocalDate.now(),
            scheduledAt = LocalDateTime.now(),
            deadline = null,
            snoozesLeft = 2,
        )
    }
}
