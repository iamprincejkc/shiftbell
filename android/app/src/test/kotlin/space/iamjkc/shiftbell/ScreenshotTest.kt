package space.iamjkc.shiftbell

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.iamjkc.shiftbell.core.Alarm
import space.iamjkc.shiftbell.core.DueAlarm
import space.iamjkc.shiftbell.core.ShiftbellSettings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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

    private val settings = ShiftbellSettings().normalized()

    // A Wednesday, so every screenshot shows the default schedule.
    private val day = LocalDate.of(2026, 10, 28)
    private val link = "https://hr.example.com/attendance"

    private fun session(alarm: Alarm, deadline: LocalTime?, snoozesLeft: Int = 2) =
        RingSession(DueAlarm(alarm, day, day.atTime(alarm.ringAt), deadline?.let { day.atTime(it) }, snoozesLeft), settings, isTest = false)

    private fun render(session: RingSession, at: LocalDateTime, name: String) {
        compose.setContent {
            AlarmScreen(session, onConfirm = {}, onSnooze = {}, onStepAside = {}, onOpenAttendance = {}, clock = { at })
        }
        compose.onRoot().captureRoboImage("../../docs/screenshots/phone/$name.png")
    }

    @Test
    fun timeIn() = render(session(Alarm.timeIn().copy(link = link), LocalTime.of(8, 0)), day.atTime(7, 47), "alarm-time-in")

    @Test
    fun late() = render(session(Alarm.timeIn().copy(link = link), LocalTime.of(8, 0), snoozesLeft = 0), day.atTime(8, 14), "alarm-late")

    @Test
    fun custom() = render(session(STANDUP, null), day.atTime(10, 1), "alarm-custom")
}

private val STANDUP = Alarm(
    id = "standup0", name = "Standup", ringAt = LocalTime.of(10, 0), days = Alarm.WEEKDAYS, strict = false,
    link = "https://meet.example.com/standup", updatedAt = LocalDateTime.of(2026, 10, 28, 9, 0),
)

/** Home, the alarm editor and Settings, as they open with time in, time out and a standup. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class HomeScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun seed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.saveSettings(context, ShiftbellSettings().normalized().with(STANDUP))
        compose.activityRule.scenario.recreate()
    }

    @Test
    fun home() {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../../docs/screenshots/phone/home.png")
    }

    @Test
    fun editor() {
        compose.onNodeWithText("Standup · Mon–Fri").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../../docs/screenshots/phone/editor.png")
    }

    @Test
    fun settings() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("../../docs/screenshots/phone/settings.png")
    }
}
