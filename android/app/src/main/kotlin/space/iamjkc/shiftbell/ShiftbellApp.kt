package space.iamjkc.shiftbell

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.iamjkc.shiftbell.core.DueAlarm
import space.iamjkc.shiftbell.core.ShiftbellSettings
import java.time.LocalDateTime

class ShiftbellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The service plays its own tone and vibration, so the channel stays silent: no double sound.
        val channel = NotificationChannel(CHANNEL_RINGING, getString(R.string.channel_ringing), NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.channel_ringing_about)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RINGING = "ringing"
    }
}

/** The alarm that is ringing right now, shared by the service that rings it and the screen that shows it. */
data class RingSession(
    val alarm: DueAlarm,
    val settings: ShiftbellSettings,
    val isTest: Boolean,
    /** Set while "Let me do it now" has the alarm stepped aside; it comes back at this time. */
    val dockedUntil: LocalDateTime? = null,
    /** For a test alarm: its sync id, shared with the PC so stopping it on either stops both. */
    val testId: String? = null,
)

object Ringing {
    private val current = MutableStateFlow<RingSession?>(null)

    val session: StateFlow<RingSession?> = current.asStateFlow()

    /** True while the alarm screen is on top. The service brings it back when it is not. */
    @Volatile
    var screenShowing = false

    internal fun set(session: RingSession?) {
        current.value = session
    }
}
