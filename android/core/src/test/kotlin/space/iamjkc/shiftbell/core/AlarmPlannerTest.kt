package space.iamjkc.shiftbell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AlarmPlannerTest {
    // 2026-10-28 is a Wednesday.
    private val wednesday = LocalDate.of(2026, 10, 28)
    private val settings = ShiftbellSettings().normalized()
    private val armed = ShiftbellState(armedSince = LocalDateTime.of(2026, 10, 1, 0, 0))

    private fun at(date: LocalDate, h: Int, m: Int): LocalDateTime = date.atTime(h, m)

    private val standup = Alarm(
        id = "standup0", name = "Standup", ringAt = LocalTime.of(10, 0), days = Alarm.WEEKDAYS, strict = false,
        link = "https://meet.example.test/standup", updatedAt = LocalDateTime.of(2026, 10, 28, 6, 0),
    )

    @Test
    fun `time in rings at its time on a work day`() {
        val due = AlarmPlanner.dueNow(settings, armed, at(wednesday, 7, 45))

        assertNotNull(due)
        assertEquals(Alarm.TIME_IN_ID, due!!.id)
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
        assertEquals(Alarm.TIME_IN_ID, AlarmPlanner.dueNow(settings, armed, at(wednesday, 7, 55))?.id)
    }

    @Test
    fun `catch up gives up after the catch up hours`() {
        assertNotNull(AlarmPlanner.dueNow(settings, armed, at(wednesday, 11, 45)))
        assertNull(AlarmPlanner.dueNow(settings, armed, at(wednesday, 11, 46)))
    }

    @Test
    fun `an acknowledged alarm stays quiet`() {
        val state = armed.acknowledge(Alarm.TIME_IN_ID, wednesday, at(wednesday, 7, 46))

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
        val state = armed.snooze(Alarm.TIME_IN_ID, wednesday, at(wednesday, 7, 50))

        assertNull(AlarmPlanner.dueNow(settings, state, at(wednesday, 7, 49)))
        assertEquals(1, AlarmPlanner.dueNow(settings, state, at(wednesday, 7, 50))?.snoozesLeft)
        assertEquals(at(wednesday, 7, 50), AlarmPlanner.next(settings, state, at(wednesday, 7, 46))?.at)
    }

    @Test
    fun `a day off silences every alarm`() {
        val state = armed.setDayOff(wednesday, true, at(wednesday, 6, 0))
        val withStandup = settings.with(standup)

        assertNull(AlarmPlanner.dueNow(withStandup, state, at(wednesday, 7, 45)))
        assertNull(AlarmPlanner.dueNow(withStandup, state, at(wednesday, 10, 0)))
        assertEquals(at(wednesday.plusDays(1), 7, 45), AlarmPlanner.next(withStandup, state, at(wednesday, 6, 0))?.at)
    }

    @Test
    fun `last night's time out still catches up after midnight`() {
        val late = settings.copy(catchUpHours = 12)

        val due = AlarmPlanner.dueNow(late, armed, at(wednesday.plusDays(1), 0, 30))

        assertEquals(Alarm.TIME_OUT_ID, due?.id)
        assertEquals(wednesday, due?.date)
    }

    @Test
    fun `next goes time in, then time out, then skips the weekend`() {
        assertEquals(at(wednesday, 7, 45), AlarmPlanner.next(settings, armed, at(wednesday, 6, 0))?.at)

        val timedIn = armed.acknowledge(Alarm.TIME_IN_ID, wednesday, at(wednesday, 7, 46))
        assertEquals(at(wednesday, 17, 0), AlarmPlanner.next(settings, timedIn, at(wednesday, 7, 46))?.at)

        val friday = LocalDate.of(2026, 10, 30)
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals(at(monday, 7, 45), AlarmPlanner.next(settings, armed, at(friday, 17, 1))?.at)
    }

    @Test
    fun `an added alarm rings on its days and is confirmed on its own`() {
        val withStandup = settings.with(standup)
        var state = armed.acknowledge(Alarm.TIME_IN_ID, wednesday, at(wednesday, 7, 50))

        assertEquals("standup0", AlarmPlanner.dueNow(withStandup, state, at(wednesday, 10, 0))?.id)
        state = state.acknowledge("standup0", wednesday, at(wednesday, 10, 1))
        assertNull(AlarmPlanner.dueNow(withStandup, state, at(wednesday, 10, 2)))
        assertEquals(Alarm.TIME_OUT_ID, AlarmPlanner.dueNow(withStandup, state, at(wednesday, 17, 0))?.id)
    }

    @Test
    fun `a one-off alarm rings only on its date even on a weekend`() {
        val saturday = LocalDate.of(2026, 10, 31)
        val call = standup.copy(id = "call0000", name = "Client call", date = saturday, ringAt = LocalTime.of(14, 30))
        val withCall = settings.with(call)

        assertEquals("call0000", AlarmPlanner.dueNow(withCall, armed, at(saturday, 14, 30))?.id)
        assertNull(AlarmPlanner.dueNow(withCall, armed, at(saturday.plusDays(7), 14, 30)))
        assertNull(AlarmPlanner.dueNow(withCall, armed, at(wednesday, 14, 30)))
    }

    @Test
    fun `a deleted alarm never rings and stops one that is ringing`() {
        val withStandup = settings.with(standup)
        val state = armed.acknowledge(Alarm.TIME_IN_ID, wednesday, at(wednesday, 7, 50))
        val ringing = AlarmPlanner.dueNow(withStandup, state, at(wednesday, 10, 0))!!

        val deleted = withStandup.with(standup.copy(deleted = true))

        assertNull(AlarmPlanner.dueNow(deleted, state, at(wednesday, 10, 0)))
        assertFalse(AlarmPlanner.stillDue(deleted, state, ringing, at(wednesday, 10, 1)))
        assertNull(deleted.active.firstOrNull { it.id == "standup0" })
    }

    @Test
    fun `moving an alarm earlier than now does not ring it late`() {
        val moved = settings.with(standup.copy(ringAt = LocalTime.of(10, 15), armedAt = at(wednesday, 10, 30)))
        var state = armed.acknowledge(Alarm.TIME_IN_ID, wednesday, at(wednesday, 7, 50))

        assertNull(AlarmPlanner.dueNow(moved, state, at(wednesday, 10, 31)))
        state = state.acknowledge(Alarm.TIME_IN_ID, wednesday.plusDays(1), at(wednesday.plusDays(1), 7, 50))
        assertEquals("standup0", AlarmPlanner.dueNow(moved, state, at(wednesday.plusDays(1), 10, 15))?.id)
    }

    @Test
    fun `disabled alarms never ring or get scheduled`() {
        val off = settings.with(settings.find(Alarm.TIME_IN_ID)!!.copy(enabled = false))

        assertNull(AlarmPlanner.dueNow(off, armed, at(wednesday, 7, 45)))
        assertEquals(Alarm.TIME_OUT_ID, AlarmPlanner.next(off, armed, at(wednesday, 6, 0))?.alarm?.id)
    }

    @Test
    fun `old settings become the two presets and an empty list stays empty`() {
        val old = ShiftbellSettings(
            timeIn = AlarmSettings(ringAt = LocalTime.of(6, 50), deadline = LocalTime.of(7, 0)),
            timeOut = AlarmSettings(enabled = false, ringAt = LocalTime.of(16, 0)),
            workDays = listOf(DayOfWeek.SATURDAY),
            attendanceLink = "https://hr.example.test/punch",
            requireTypedConfirmation = false,
        ).normalized()

        val timeIn = old.find(Alarm.TIME_IN_ID)!!
        assertEquals(LocalTime.of(6, 50), timeIn.ringAt)
        assertEquals("https://hr.example.test/punch", timeIn.link)
        assertFalse(timeIn.strict)
        assertEquals(listOf(DayOfWeek.SATURDAY), timeIn.days)
        assertFalse(old.find(Alarm.TIME_OUT_ID)!!.enabled)
        assertNull(old.timeIn)

        assertEquals(emptyList<Alarm>(), ShiftbellSettings(alarms = emptyList()).normalized().active)
    }
}
