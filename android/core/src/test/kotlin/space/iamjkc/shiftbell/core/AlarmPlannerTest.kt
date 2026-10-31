package space.iamjkc.shiftbell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AlarmPlannerTest {
    // 2026-10-28 is a Wednesday.
    private val wednesday = LocalDate.of(2026, 10, 28)
    private val settings = ShiftbellSettings()
    private val armed = ShiftbellState(armedSince = LocalDateTime.of(2026, 10, 1, 0, 0))

    private fun at(date: LocalDate, h: Int, m: Int): LocalDateTime = date.atTime(h, m)

    @Test
    fun `time in rings at its time on a work day`() {
        val due = AlarmPlanner.dueNow(settings, armed, at(wednesday, 7, 45))

        assertNotNull(due)
        assertEquals(AlarmKind.TimeIn, due!!.kind)
        assertEquals(at(wednesday, 8, 0), due.deadline)
        assertEquals(2, due.snoozesLeft)
    }

    @Test
    fun `nothing rings before the time or on a weekend`() {
        assertNull(AlarmPlanner.dueNow(settings, armed, at(wednesday, 7, 44)))
        assertNull(AlarmPlanner.dueNow(settings, armed, at(LocalDate.of(2026, 10, 31), 7, 45)))
    }

    @Test
    fun `a phone switched on at 7 55 still rings the 7 45 alarm`() {
        assertEquals(AlarmKind.TimeIn, AlarmPlanner.dueNow(settings, armed, at(wednesday, 7, 55))?.kind)
    }

    @Test
    fun `catch up gives up after the catch up hours`() {
        assertNotNull(AlarmPlanner.dueNow(settings, armed, at(wednesday, 11, 45)))
        assertNull(AlarmPlanner.dueNow(settings, armed, at(wednesday, 11, 46)))
    }

    @Test
    fun `an acknowledged alarm stays quiet`() {
        val state = armed.acknowledge(AlarmKind.TimeIn, wednesday, at(wednesday, 7, 46))

        assertNull(AlarmPlanner.dueNow(settings, state, at(wednesday, 7, 50)))
    }

    @Test
    fun `installing after the ring time does not fire a late alarm`() {
        val state = ShiftbellState(armedSince = at(wednesday, 10, 0))

        assertNull(AlarmPlanner.dueNow(settings, state, at(wednesday, 10, 1)))
        assertEquals(at(wednesday, 17, 0), AlarmPlanner.next(settings, state, at(wednesday, 10, 1))?.at)
    }

    @Test
    fun `a snooze silences until it ends and uses up a snooze`() {
        val state = armed.snooze(AlarmKind.TimeIn, wednesday, at(wednesday, 7, 50))

        assertNull(AlarmPlanner.dueNow(settings, state, at(wednesday, 7, 49)))
        assertEquals(1, AlarmPlanner.dueNow(settings, state, at(wednesday, 7, 50))?.snoozesLeft)
        assertEquals(at(wednesday, 7, 50), AlarmPlanner.next(settings, state, at(wednesday, 7, 46))?.at)
    }

    @Test
    fun `a day off silences both alarms`() {
        val state = armed.setDayOff(wednesday, true, at(wednesday, 6, 0))

        assertNull(AlarmPlanner.dueNow(settings, state, at(wednesday, 7, 45)))
        assertNull(AlarmPlanner.dueNow(settings, state, at(wednesday, 17, 0)))
        assertEquals(at(wednesday.plusDays(1), 7, 45), AlarmPlanner.next(settings, state, at(wednesday, 6, 0))?.at)
    }

    @Test
    fun `last night's time out still catches up after midnight`() {
        val late = settings.copy(catchUpHours = 12)

        val due = AlarmPlanner.dueNow(late, armed, at(wednesday.plusDays(1), 0, 30))

        assertEquals(AlarmKind.TimeOut, due?.kind)
        assertEquals(wednesday, due?.date)
    }

    @Test
    fun `next goes time in, then time out, then skips the weekend`() {
        assertEquals(at(wednesday, 7, 45), AlarmPlanner.next(settings, armed, at(wednesday, 6, 0))?.at)

        val timedIn = armed.acknowledge(AlarmKind.TimeIn, wednesday, at(wednesday, 7, 46))
        assertEquals(at(wednesday, 17, 0), AlarmPlanner.next(settings, timedIn, at(wednesday, 7, 46))?.at)

        val friday = LocalDate.of(2026, 10, 30)
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals(at(monday, 7, 45), AlarmPlanner.next(settings, armed, at(friday, 17, 1))?.at)
    }

    @Test
    fun `disabled alarms never ring or get scheduled`() {
        val off = settings.copy(timeIn = settings.timeIn.copy(enabled = false))

        assertNull(AlarmPlanner.dueNow(off, armed, at(wednesday, 7, 45)))
        assertEquals(AlarmKind.TimeOut, AlarmPlanner.next(off, armed, at(wednesday, 6, 0))?.kind)
    }
}
