package space.iamjkc.shiftbell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CodecAndLoudnessTest {
    private val now = LocalDateTime.of(2026, 10, 28, 0, 30)
    private val defaults = ShiftbellSettings().normalized()

    @Test
    fun `settings and state round trip`() {
        val standup = Alarm(
            id = "standup0", name = "Standup", ringAt = LocalTime.of(10, 0), days = listOf(DayOfWeek.SATURDAY),
            strict = false, link = "https://meet.example.test/standup", updatedAt = now,
        )
        val settings = defaults.with(standup)
        val day = LocalDate.of(2026, 10, 28)
        val state = ShiftbellState(armedSince = now).acknowledge(Alarm.TIME_OUT_ID, day, day.atTime(17, 2))

        assertEquals(settings, Codec.decodeSettings(Codec.encodeSettings(settings)))
        assertEquals(state, Codec.decodeState(Codec.encodeState(state), now.plusDays(3)))
    }

    @Test
    fun `a 0_3 settings and state file loads into the alarm list`() {
        val settings = Codec.decodeSettings(
            """{"timeIn":{"enabled":true,"ringAt":"06:50","deadline":"07:00"},"timeOut":{"enabled":true,"ringAt":"16:00"},
               "workDays":["MONDAY","TUESDAY"],"attendanceLink":"https://hr.example.test/punch","requireTypedConfirmation":true}""",
        )
        val state = Codec.decodeState(
            """{"armedSince":"2026-10-01T00:00","days":[{"date":"2026-10-27",
               "timeIn":{"acknowledgedAt":"2026-10-27T07:46"},"timeOut":{"snoozes":1}}]}""",
            now,
        )

        assertEquals(LocalTime.of(6, 50), settings.find(Alarm.TIME_IN_ID)!!.ringAt)
        assertEquals("https://hr.example.test/punch", settings.find(Alarm.TIME_OUT_ID)!!.link)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), settings.find(Alarm.TIME_OUT_ID)!!.days)
        val day = state.find(LocalDate.of(2026, 10, 27))!!
        assertEquals(LocalDateTime.of(2026, 10, 27, 7, 46), day.of(Alarm.TIME_IN_ID)!!.acknowledgedAt)
        assertEquals(1, day.of(Alarm.TIME_OUT_ID)!!.snoozes)
        assertFalse(Codec.encodeSettings(settings).contains("workDays"))
        assertFalse(Codec.encodeSettings(settings).contains("\"active\""))
    }

    @Test
    fun `missing or broken files fall back to defaults`() {
        assertEquals(defaults, Codec.decodeSettings(null))
        assertEquals(defaults, Codec.decodeSettings("{ not json"))
        assertEquals(defaults, Codec.decodeSettings("""{"timeIn":{"ringAt":"soonish"}}"""))
        assertEquals(ShiftbellState(armedSince = now), Codec.decodeState("[]", now))
    }

    @Test
    fun `no attendance link is built in`() {
        assertTrue(Codec.decodeSettings("{}").active.all { it.link.isEmpty() })
    }

    @Test
    fun `a deadline before the ring time is pulled up to it`() {
        val text = """{"alarms":[{"id":"time-in","role":"TimeIn","ringAt":"08:00","deadline":"07:30","days":["MONDAY"]}]}"""

        assertEquals(LocalTime.of(8, 0), Codec.decodeSettings(text).find(Alarm.TIME_IN_ID)!!.deadline)
    }

    @Test
    fun `the tone starts soft and climbs to the chosen alarm volume`() {
        val gains = listOf(0L, 14, 15, 30, 59, 60, 600).map { RingLoudness.gain(Duration.ofSeconds(it)) }

        assertTrue(gains.first() <= 0.15f)
        assertEquals(gains.sorted(), gains)
        assertEquals(1.0f, gains.last())
    }
}
