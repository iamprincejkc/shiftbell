package space.iamjkc.shiftbell.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class SyncTest {
    // Test vectors from docs/sync.md, computed independently with Node's crypto. The PC app checks the same ones.
    private val code = "SB1-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
    private val data =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2YHvGyPMAjZ8ga4cSN7ETChqskIlJZb-3Ies9nK-7Kep-hKijdeNyscZtLGxtTBqlKfzsNCEhQFasIqmDSZx9FxJyQVrSu154V8NZgLkFbkDHkdFtyvL1jCusZomvPb"
    private val testData =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAGmAf6mKBeQre-ECgaVVGFTiNi55QlN4Y63ZI7NnC6bKYuO9XljRCJjYcK4SFiNTJuFKZ0cRfDhcZDcNJkzOVztF-NTgV53uz7oV8NdhZgBiR2X4qNfxOsrn_IE6OsnJshlWfPLXqLIfZaYWE5iHff6HpZo9xjESzYFG5GjE"
    private val saturday = LocalDate.of(2026, 10, 31)
    private val nonce = ByteArray(12) { (0xa0 + it).toByte() }

    private fun at(h: Int, m: Int, s: Int = 0): LocalDateTime = saturday.atTime(h, m, s)

    @Test
    fun `keys match the shared test vectors`() {
        val keys = SyncKeys.fromCode(code)!!

        assertEquals("d572d7e8d50d215efff4140d0edf1166", keys.channel)
        assertEquals("CHg7lE2AcanFZHsuv2ZWMbXINZJeL7O4Nx0mAzzmHO0", keys.token)
    }

    @Test
    fun `sealing matches the shared test vector byte for byte`() {
        val event = SyncEvent.ack(AlarmKind.TimeIn, saturday, at(7, 46, 5)).copy(from = "pc")

        assertEquals(data, SyncCrypto.seal(event, SyncKeys.fromCode(code)!!, nonce))
    }

    @Test
    fun `opening reads what the pc wrote`() {
        val event = SyncCrypto.open(data, SyncKeys.fromCode(code)!!)

        assertEquals(SyncEvent.ack(AlarmKind.TimeIn, saturday, at(7, 46, 5)).copy(from = "pc"), event)
    }

    @Test
    fun `a damaged or foreign event is skipped`() {
        val keys = SyncKeys.fromCode(code)!!

        assertNull(SyncCrypto.open(data.dropLast(2) + "AA", keys))
        assertNull(SyncCrypto.open(data, SyncKeys.fromCode(SyncKeys.newCode())!!))
        assertNull(SyncCrypto.open("not base64!", keys))
        assertNull(SyncCrypto.open("", keys))
    }

    @Test
    fun `codes are checked and copy paste whitespace ignored`() {
        assertNotNull(SyncKeys.fromCode(SyncKeys.newCode()))
        assertEquals(code, SyncKeys.fromCode(" ${code.take(20)}\n${code.drop(20)} ")!!.code)
        assertNull(SyncKeys.fromCode("SB1-short"))
        assertNull(SyncKeys.fromCode("SB2-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"))
        assertNull(SyncKeys.fromCode(null))
    }

    @Test
    fun `an ack from the pc silences the alarm here`() {
        val settings = ShiftbellSettings(workDays = listOf(DayOfWeek.SATURDAY))
        val ringing = AlarmPlanner.dueNow(settings, ShiftbellState(armedSince = at(0, 0)), at(7, 46))!!

        val state = SyncMerge.apply(ShiftbellState(armedSince = at(0, 0)), SyncEvent.ack(AlarmKind.TimeIn, saturday, at(7, 46)))

        assertFalse(AlarmPlanner.stillDue(settings, state, ringing, at(7, 47)))
    }

    @Test
    fun `acks keep the earliest and replays change nothing`() {
        var state = ShiftbellState(armedSince = at(0, 0))
        state = SyncMerge.apply(state, SyncEvent.ack(AlarmKind.TimeIn, saturday, at(7, 50)))
        state = SyncMerge.apply(state, SyncEvent.ack(AlarmKind.TimeIn, saturday, at(7, 46)))

        assertSame(state, SyncMerge.apply(state, SyncEvent.ack(AlarmKind.TimeIn, saturday, at(7, 50))))
        assertEquals(at(7, 46), state.find(saturday)!!.timeIn.acknowledgedAt)
    }

    @Test
    fun `snoozes keep the latest and never undo an ack`() {
        var state = ShiftbellState(armedSince = at(0, 0))
        state = SyncMerge.apply(state, SyncEvent.snoozed(AlarmKind.TimeIn, saturday, at(7, 45), at(7, 50), 1))
        state = SyncMerge.apply(state, SyncEvent.snoozed(AlarmKind.TimeIn, saturday, at(7, 51), at(7, 56), 2))
        assertSame(state, SyncMerge.apply(state, SyncEvent.snoozed(AlarmKind.TimeIn, saturday, at(7, 45), at(7, 50), 1)))
        assertEquals(at(7, 56), state.find(saturday)!!.timeIn.snoozedUntil)
        assertEquals(2, state.find(saturday)!!.timeIn.snoozes)

        state = SyncMerge.apply(state, SyncEvent.ack(AlarmKind.TimeIn, saturday, at(7, 57)))
        assertSame(state, SyncMerge.apply(state, SyncEvent.snoozed(AlarmKind.TimeIn, saturday, at(7, 58), at(8, 3), 3)))
        assertNull(state.find(saturday)!!.timeIn.snoozedUntil)
    }

    @Test
    fun `the latest day off change wins in any order`() {
        val on = SyncEvent.dayOff(saturday, at(6, 0), off = true)
        val undo = SyncEvent.dayOff(saturday, at(6, 5), off = false)
        val start = ShiftbellState(armedSince = at(0, 0))

        val forward = SyncMerge.apply(SyncMerge.apply(start, on), undo)
        val backward = SyncMerge.apply(SyncMerge.apply(start, undo), on)

        assertFalse(forward.find(saturday)!!.dayOff)
        assertFalse(backward.find(saturday)!!.dayOff)
    }

    @Test
    fun `only a recent test from the other device rings here`() {
        val test = SyncEvent.test(AlarmKind.TimeIn, at(13, 30), "abc").copy(from = "pc")

        assertTrue(test.isFreshTestFrom("pc", at(13, 31)))
        assertFalse(test.isFreshTestFrom("pc", at(13, 33)))
        assertFalse(test.isFreshTestFrom("phone", at(13, 31)))
        assertFalse(test.copy(id = null).isFreshTestFrom("pc", at(13, 31)))
    }

    @Test
    fun `tests never touch the state`() {
        val state = ShiftbellState(armedSince = at(0, 0))

        assertSame(state, SyncMerge.apply(state, SyncEvent.test(AlarmKind.TimeIn, at(13, 30), "abc")))
        assertSame(state, SyncMerge.apply(state, SyncEvent.testEnd("abc", at(13, 31))))
    }

    @Test
    fun `test events match the shared test vector byte for byte`() {
        val test = SyncEvent.test(AlarmKind.TimeOut, at(13, 30), "0123456789abcdef")

        assertEquals(testData, SyncCrypto.seal(test, SyncKeys.fromCode(code)!!, nonce))
        assertEquals(test, SyncCrypto.open(testData, SyncKeys.fromCode(code)!!))
    }

    @Test
    fun `malformed events change nothing`() {
        val state = ShiftbellState(armedSince = at(0, 0))

        assertSame(state, SyncMerge.apply(state, SyncEvent(type = "ack", kind = "TimeIn", date = "31/10/2026", at = "2026-10-31T07:46:00")))
        assertSame(state, SyncMerge.apply(state, SyncEvent(type = "ack", kind = "Lunch", date = "2026-10-31", at = "2026-10-31T07:46:00")))
        assertSame(state, SyncMerge.apply(state, SyncEvent(type = "explode", date = "2026-10-31", at = "2026-10-31T07:46:00")))
    }
}
