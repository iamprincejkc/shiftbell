package space.iamjkc.shiftbell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class AlarmCopyTest {
    private val day = LocalDate.of(2026, 10, 28)
    private val settings = ShiftbellSettings()
    private val timeIn = DueAlarm(Alarm.timeIn(), day, day.atTime(7, 45), day.atTime(8, 0), 2)
    private val timeOut = DueAlarm(Alarm.timeOut(), day, day.atTime(17, 0), null, 2)

    @Test
    fun `time in before the deadline says how long is left`() {
        val copy = AlarmCopy.of(timeIn, day.atTime(7, 47))

        assertEquals(AlarmTone.TimeIn, copy.tone)
        assertEquals("Time in now", copy.headline)
        assertEquals("Log in to your attendance system before 8:00 AM.", copy.detail)
        assertEquals("7:47 AM · 13 min left", copy.clock)
        assertEquals("done", copy.phrase)
        assertEquals("Timed in?", copy.ask)
        assertEquals("I’ve timed in", copy.confirmLabel)
    }

    @Test
    fun `after the deadline it says you are late`() {
        val copy = AlarmCopy.of(timeIn, day.atTime(9, 12))

        assertEquals(AlarmTone.Late, copy.tone)
        assertEquals("You’re late to time in", copy.headline)
        assertEquals("It was due at 8:00 AM. Log in to your attendance system now.", copy.detail)
        assertEquals("9:12 AM · 1 h 12 min late", copy.clock)
    }

    @Test
    fun `time out names the end of the shift and how long it has rung`() {
        val copy = AlarmCopy.of(timeOut, day.atTime(17, 3))

        assertEquals("Your shift ended at 5:00 PM. Log out of your attendance system before you leave.", copy.detail)
        assertEquals("5:03 PM · ringing for 3 min", copy.clock)
        assertEquals("done", copy.phrase)
        assertEquals("Timed out?", copy.ask)
    }

    @Test
    fun `an added alarm speaks its own name and its stopping style`() {
        val standup = Alarm(id = "standup0", name = "Standup", ringAt = java.time.LocalTime.of(10, 0), strict = false)

        val copy = AlarmCopy.of(DueAlarm(standup, day, day.atTime(10, 0), null, 2), day.atTime(10, 0))

        assertEquals(AlarmTone.Custom, copy.tone)
        assertEquals(listOf("STANDUP", "Standup", "Set for 10:00 AM."), listOf(copy.tag, copy.headline, copy.detail))
        assertEquals(listOf("Dismiss", "Open link", "Ready?"), listOf(copy.confirmLabel, copy.openLabel, copy.ask))
    }

    @Test
    fun `repeat reads like a clock app`() {
        fun repeat(vararg days: java.time.DayOfWeek) = AlarmCopy.repeat(Alarm(id = "x", ringAt = java.time.LocalTime.NOON, days = days.toList()))

        assertEquals("Mon–Fri", repeat(*Alarm.WEEKDAYS.toTypedArray()))
        assertEquals("Weekends", repeat(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY))
        assertEquals("Mon, Wed", repeat(java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.MONDAY))
        assertEquals("Fri Nov 6", AlarmCopy.repeat(Alarm(id = "x", ringAt = java.time.LocalTime.NOON, date = LocalDate.of(2026, 11, 6))))
    }

    @Test
    fun `snooze label counts down to none`() {
        assertEquals("Snooze 5 min · 2 left", AlarmCopy.snooze(settings, timeIn))
        assertEquals("No snoozes left", AlarmCopy.snooze(settings, timeIn.copy(snoozesLeft = 0)))
    }

    @Test
    fun `the phrase ignores case and spacing but not words`() {
        assertTrue(AlarmCopy.matches("  DONE "))
        assertTrue(AlarmCopy.matches("done"))
        assertFalse(AlarmCopy.matches("don"))
        assertFalse(AlarmCopy.matches("timed in"))
    }

    @Test
    fun `spans round up`() {
        assertEquals("under 1 min", AlarmCopy.span(Duration.ZERO))
        assertEquals("1 min", AlarmCopy.span(Duration.ofSeconds(1)))
        assertEquals("2 h", AlarmCopy.span(Duration.ofHours(2)))
        assertEquals("1 h 5 min", AlarmCopy.span(Duration.ofMinutes(65)))
    }
}
