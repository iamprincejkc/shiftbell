package space.iamjkc.shiftbell

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.iamjkc.shiftbell.core.AlarmKind
import space.iamjkc.shiftbell.core.DueAlarm
import space.iamjkc.shiftbell.core.ShiftbellSettings
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The phone screenshots in the README, drawn on the JVM by Robolectric. Not a behaviour test.
 * Record with `gradlew :app:recordRoborazziDebug`; files land in docs/screenshots/phone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val settings = ShiftbellSettings(attendanceLink = "https://hr.example.com/attendance")

    // A Wednesday, so every screenshot shows the default schedule.
    private val day = LocalDate.of(2026, 10, 28)

    private fun session(kind: AlarmKind, snoozesLeft: Int = 2): RingSession {
        val ringAt = if (kind == AlarmKind.TimeIn) day.atTime(7, 45) else day.atTime(17, 0)
        val deadline = if (kind == AlarmKind.TimeIn) day.atTime(8, 0) else null
        return RingSession(DueAlarm(kind, day, ringAt, deadline, snoozesLeft), settings, isTest = false)
    }

    private fun render(session: RingSession, at: LocalDateTime, name: String) {
        compose.setContent {
            AlarmScreen(session, onConfirm = {}, onSnooze = {}, onStepAside = {}, onOpenAttendance = {}, clock = { at })
        }
        compose.onRoot().captureRoboImage("../../docs/screenshots/phone/$name.png")
    }

    @Test
    fun timeIn() = render(session(AlarmKind.TimeIn), day.atTime(7, 47), "alarm-time-in")

    @Test
    fun late() = render(session(AlarmKind.TimeIn, snoozesLeft = 0), day.atTime(8, 14), "alarm-late")

    @Test
    fun timeOut() = render(session(AlarmKind.TimeOut), day.atTime(17, 3), "alarm-time-out")
}

/** The home screen as it opens on a fresh install. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class HomeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun home() {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../../docs/screenshots/phone/home.png")
    }
}
