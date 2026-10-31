package space.iamjkc.shiftbell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CodecAndLoudnessTest {
    private val now = LocalDateTime.of(2026, 10, 28, 0, 30)

    @Test
    fun `settings and state round trip`() {
        val settings = ShiftbellSettings(
            timeIn = AlarmSettings(ringAt = LocalTime.of(6, 50), deadline = LocalTime.of(7, 0)),
            workDays = listOf(DayOfWeek.SATURDAY),
            attendanceLink = "",
        )
        val day = LocalDate.of(2026, 10, 28)
        val state = ShiftbellState(armedSince = now).acknowledge(AlarmKind.TimeOut, day, day.atTime(17, 2))

        assertEquals(settings, Codec.decodeSettings(Codec.encodeSettings(settings)))
        assertEquals(state, Codec.decodeState(Codec.encodeState(state), now.plusDays(3)))
    }

    @Test
    fun `missing or broken files fall back to defaults`() {
        assertEquals(ShiftbellSettings(), Codec.decodeSettings(null))
        assertEquals(ShiftbellSettings(), Codec.decodeSettings("{ not json"))
        assertEquals(ShiftbellSettings(), Codec.decodeSettings("""{"timeIn":{"ringAt":"soonish"}}"""))
        assertEquals(ShiftbellState(armedSince = now), Codec.decodeState("[]", now))
    }

    @Test
    fun `no attendance link is built in and a saved one survives`() {
        assertEquals("", Codec.decodeSettings("{}").attendanceLink)
        val saved = ShiftbellSettings(attendanceLink = "https://hr.example.test/punch")
        assertEquals("https://hr.example.test/punch", Codec.decodeSettings(Codec.encodeSettings(saved)).attendanceLink)
    }

    @Test
    fun `a deadline before the ring time is pulled up to it`() {
        val text = """{"timeIn":{"ringAt":"08:00","deadline":"07:30"}}"""

        assertEquals(LocalTime.of(8, 0), Codec.decodeSettings(text).timeIn.deadline)
    }

    @Test
    fun `the tone starts soft and climbs to the chosen alarm volume`() {
        val gains = listOf(0L, 14, 15, 30, 59, 60, 600).map { RingLoudness.gain(Duration.ofSeconds(it)) }

        assertTrue(gains.first() <= 0.15f)
        assertEquals(gains.sorted(), gains)
        assertEquals(1.0f, gains.last())
    }
}
