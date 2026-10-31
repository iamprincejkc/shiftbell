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
    private val timeIn = DueAlarm(AlarmKind.TimeIn, day, day.atTime(7, 45), day.atTime(8, 0), 2)
    private val timeOut = DueAlarm(AlarmKind.TimeOut, day, day.atTime(17, 0), null, 2)

    @Test
    fun `time in before the deadline says how long is left`() {
        val copy = AlarmCopy.of(timeIn, settings, day.atTime(7, 47))

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
        val copy = AlarmCopy.of(timeIn, settings, day.atTime(9, 12))

        assertEquals(AlarmTone.Late, copy.tone)
        assertEquals("You’re late to time in", copy.headline)
        assertEquals("It was due at 8:00 AM. Log in to your attendance system now.", copy.detail)
        assertEquals("9:12 AM · 1 h 12 min late", copy.clock)
    }

    @Test
    fun `time out names the end of the shift and how long it has rung`() {
        val copy = AlarmCopy.of(timeOut, settings, day.atTime(17, 3))

        assertEquals("Your shift ended at 5:00 PM. Log out of your attendance system before you leave.", copy.detail)
        assertEquals("5:03 PM · ringing for 3 min", copy.clock)
        assertEquals("done", copy.phrase)
        assertEquals("Timed out?", copy.ask)
    }

    @Test
    fun `snooze label counts down to none`() {
        assertEquals("Snooze 5 min · 2 left", AlarmCopy.snooze(settings, timeIn))
        assertEquals("No snoozes left", AlarmCopy.snooze(settings, timeIn.copy(snoozesLeft = 0)))
    }

    @Test
    fun `the phrase ignores case and spacing but not words`() {
        assertTrue(AlarmCopy.matches("  DONE ", AlarmKind.TimeIn))
        assertTrue(AlarmCopy.matches("done", AlarmKind.TimeOut))
        assertFalse(AlarmCopy.matches("don", AlarmKind.TimeIn))
        assertFalse(AlarmCopy.matches("timed in", AlarmKind.TimeIn))
    }

    @Test
    fun `spans round up`() {
        assertEquals("under 1 min", AlarmCopy.span(Duration.ZERO))
        assertEquals("1 min", AlarmCopy.span(Duration.ofSeconds(1)))
        assertEquals("2 h", AlarmCopy.span(Duration.ofHours(2)))
        assertEquals("1 h 5 min", AlarmCopy.span(Duration.ofMinutes(65)))
    }
}
