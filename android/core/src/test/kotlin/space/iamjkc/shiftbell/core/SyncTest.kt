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
import java.time.LocalTime

class SyncTest {
    // Test vectors from docs/sync.md, computed independently with Node's crypto. The PC app checks the same ones.
    private val code = "SB1-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"

    /** A 0.3 ack: names the preset by kind only. */
    private val legacyAck =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2YHvGyPMAjZ8ga4cSN7ETChqskIlJZb-3Ies9nK-7Kep-hKijdeNyscZtLGxtTBqlKfzsNCEhQFasIqmDSZx9FxJyQVrSu154V8NZgLkFbkDHkdFtyvL1jCusZomvPb"
    private val ack =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2YHvGyPMAjZ8ga4cSN7ETChqskIlJNW7mVRq8Hav-nF7_QTySZDN2Nfc8PGxtTBqlKfzsNCEhQFHN4_w3SNx8N2NToB5mjqp5R3Q9hfiB-T1ngoL-kNtqj0KAqWvCBp1kfRmDJ4wbwlwXzMYualGo9Q"
    private val test =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAGmAf6mKBeQre-ECgaVVGFTiNi55QlN4Y7ntd-5ba8aLc47QfimsaYSUSJcKFiJPRoEKb08BEEhYEE8EsgCyNnJVmPyoF-2vsp5Z2OttZ5hqW1nstN_Uf8va5LEyOpHI6xQjBKrr-a9ePZoLF_-PYG7J9o6fAjk-6MOhGSY2cxSBmWPZiGswUT130JaSE_2wV"
    private val definition =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2kN7C2Pd0PT91DncU0wTmXa8sYVh98KvDUQq5qM6bqKuOlIkSleJCoONPLUxczCr1qZ09BeHUNRWNAn2SLGmcN-J3tDqje-_9d2NcRKwETJiWonL4Zao670KAqAvD5rmV_QJK2bKI7YY5XWuaqcXPA-p7nKh0-6ZupbQ8_blTS9YhY5MA2aRQls74zxGUA4xfm7YfapvHQqejJqOcfcVFcQQCVXms27nx3nYqU28ihCSc_hDeQkGjpt3d6Ml2rtJB5182QuH3nr9f2rfpV-hWCGGJnCxkSiknlNgctTj_FyxHkD_bG44nuLweF814Yo0lKNlt4nAxEe3OtO4aJq8AWrSx9sBT_sTQPSJ70iXg73FPd0OQhRh0dDXnZHvwyFhf1SrzzWekSUEbkqSXacDEKWkUiB6Iozu9NP5uAmc8yyC8nn0aX-pe41NElUuqF-CUgTmCCKbybZoVc1G4gFSAtgeQnRWp0Id7jTXK9IFtdQ7jc"

    private val saturday = LocalDate.of(2026, 10, 31)
    private val nonce = ByteArray(12) { (0xa0 + it).toByte() }
    private val keys = SyncKeys.fromCode(code)!!
    private val standup = Alarm(
        id = "standup0", name = "Standup", ringAt = LocalTime.of(10, 0), days = Alarm.WEEKDAYS, strict = false,
        link = "https://meet.example.test/standup", armedAt = LocalDateTime.of(2026, 11, 3, 9, 15), updatedAt = LocalDateTime.of(2026, 11, 3, 9, 15),
    )

    private fun at(h: Int, m: Int, s: Int = 0): LocalDateTime = saturday.atTime(h, m, s)

    @Test
    fun `keys match the shared test vectors`() {
        assertEquals("d572d7e8d50d215efff4140d0edf1166", keys.channel)
        assertEquals("CHg7lE2AcanFZHsuv2ZWMbXINZJeL7O4Nx0mAzzmHO0", keys.token)
    }

    @Test
    fun `an ack matches the shared test vector byte for byte`() {
        val event = SyncEvent.ack(Alarm.timeIn(), saturday, at(7, 46, 5)).copy(from = "pc")

        assertEquals(ack, SyncCrypto.seal(event, keys, nonce))
        assertEquals(event, SyncCrypto.open(ack, keys))
    }

    @Test
    fun `a test matches the shared test vector byte for byte`() {
        val event = SyncEvent.test(Alarm.timeOut(), at(13, 30), "0123456789abcdef")

        assertEquals(test, SyncCrypto.seal(event, keys, nonce))
        assertEquals(event, SyncCrypto.open(test, keys))
    }

    @Test
    fun `an alarm definition matches the shared test vector byte for byte`() {
        val event = SyncEvent.changed(standup).copy(from = "pc")

        assertEquals(definition, SyncCrypto.seal(event, keys, nonce))
        assertEquals(standup.normalized(), SyncCrypto.open(definition, keys)!!.def!!.toAlarm())
    }

    @Test
    fun `a 0_3 ack still reads as the time in preset`() {
        val state = SyncMerge.apply(ShiftbellState(armedSince = at(0, 0)), SyncCrypto.open(legacyAck, keys)!!)

        assertEquals(at(7, 46, 5), state.find(saturday)!!.of(Alarm.TIME_IN_ID)!!.acknowledgedAt)
    }

    @Test
    fun `a damaged or foreign event is skipped`() {
        assertNull(SyncCrypto.open(ack.dropLast(2) + "AA", keys))
        assertNull(SyncCrypto.open(ack, SyncKeys.fromCode(SyncKeys.newCode())!!))
        assertNull(SyncCrypto.open("not base64!", keys))
        assertNull(SyncCrypto.open("", keys))
    }

    @Test
    fun `codes are checked and copy paste whitespace ignored`() {
        assertNotNull(SyncKeys.fromCode(SyncKeys.newCode()))
        assertEquals(code, SyncKeys.fromCode(" ${code.take(20)}\n${code.drop(20)} ")!!.code)
        assertNull(SyncKeys.fromCode("SB1-short"))
        assertNull(SyncKeys.fromCode(null))
    }

    @Test
    fun `an ack from the pc silences the alarm here`() {
        val settings = ShiftbellSettings().normalized().with(Alarm.timeIn().copy(days = listOf(DayOfWeek.SATURDAY)))
        val ringing = AlarmPlanner.dueNow(settings, ShiftbellState(armedSince = at(0, 0)), at(7, 46))!!

        val state = SyncMerge.apply(ShiftbellState(armedSince = at(0, 0)), SyncEvent.ack(Alarm.timeIn(), saturday, at(7, 46)))

        assertFalse(AlarmPlanner.stillDue(settings, state, ringing, at(7, 47)))
    }

    @Test
    fun `acks keep the earliest and replays change nothing`() {
        var state = ShiftbellState(armedSince = at(0, 0))
        state = SyncMerge.apply(state, SyncEvent.ack(standup, saturday, at(10, 5)))
        state = SyncMerge.apply(state, SyncEvent.ack(standup, saturday, at(10, 1)))

        assertSame(state, SyncMerge.apply(state, SyncEvent.ack(standup, saturday, at(10, 5))))
        assertEquals(at(10, 1), state.find(saturday)!!.of("standup0")!!.acknowledgedAt)
    }

    @Test
    fun `snoozes keep the latest and never undo an ack`() {
        val timeIn = Alarm.timeIn()
        var state = ShiftbellState(armedSince = at(0, 0))
        state = SyncMerge.apply(state, SyncEvent.snoozed(timeIn, saturday, at(7, 45), at(7, 50), 1))
        state = SyncMerge.apply(state, SyncEvent.snoozed(timeIn, saturday, at(7, 51), at(7, 56), 2))
        assertSame(state, SyncMerge.apply(state, SyncEvent.snoozed(timeIn, saturday, at(7, 45), at(7, 50), 1)))
        assertEquals(at(7, 56), state.find(saturday)!!.of(Alarm.TIME_IN_ID)!!.snoozedUntil)

        state = SyncMerge.apply(state, SyncEvent.ack(timeIn, saturday, at(7, 57)))
        assertSame(state, SyncMerge.apply(state, SyncEvent.snoozed(timeIn, saturday, at(7, 58), at(8, 3), 3)))
        assertNull(state.find(saturday)!!.of(Alarm.TIME_IN_ID)!!.snoozedUntil)
    }

    @Test
    fun `the latest day off change wins in any order`() {
        val on = SyncEvent.dayOff(saturday, at(6, 0), off = true)
        val undo = SyncEvent.dayOff(saturday, at(6, 5), off = false)
        val start = ShiftbellState(armedSince = at(0, 0))

        assertFalse(SyncMerge.apply(SyncMerge.apply(start, on), undo).find(saturday)!!.dayOff)
        assertFalse(SyncMerge.apply(SyncMerge.apply(start, undo), on).find(saturday)!!.dayOff)
    }

    @Test
    fun `an added alarm arrives and the newest edit wins in any order`() {
        val settings = ShiftbellSettings().normalized()
        val older = SyncEvent.changed(standup)
        val newer = SyncEvent.changed(standup.copy(ringAt = LocalTime.of(10, 30), updatedAt = standup.updatedAt.plusMinutes(5)))

        val forward = SyncMerge.apply(SyncMerge.apply(settings, older), newer)
        val backward = SyncMerge.apply(SyncMerge.apply(settings, newer), older)

        assertEquals(LocalTime.of(10, 30), forward.find("standup0")!!.ringAt)
        assertEquals(LocalTime.of(10, 30), backward.find("standup0")!!.ringAt)
        assertSame(forward, SyncMerge.apply(forward, newer))
    }

    @Test
    fun `a deletion reaches this device and an untouched preset loses to a real edit`() {
        val settings = ShiftbellSettings().normalized().with(standup)

        val deleted = SyncMerge.apply(settings, SyncEvent.changed(standup.copy(deleted = true, updatedAt = standup.updatedAt.plusHours(1))))
        assertNull(deleted.find("standup0"))

        val moved = SyncMerge.apply(settings, SyncEvent.changed(Alarm.timeIn().copy(ringAt = LocalTime.of(7, 30), updatedAt = at(9, 0))))
        assertEquals(LocalTime.of(7, 30), moved.find(Alarm.TIME_IN_ID)!!.ringAt)
    }

    @Test
    fun `only a recent test from the other device rings here`() {
        val event = SyncEvent.test(Alarm.timeIn(), at(13, 30), "abc").copy(from = "pc")

        assertTrue(event.isFreshTestFrom("pc", at(13, 31)))
        assertFalse(event.isFreshTestFrom("pc", at(13, 33)))
        assertFalse(event.isFreshTestFrom("phone", at(13, 31)))
        assertFalse(event.copy(id = null).isFreshTestFrom("pc", at(13, 31)))
    }

    @Test
    fun `tests never touch the state`() {
        val state = ShiftbellState(armedSince = at(0, 0))

        assertSame(state, SyncMerge.apply(state, SyncEvent.test(Alarm.timeIn(), at(13, 30), "abc")))
        assertSame(state, SyncMerge.apply(state, SyncEvent.testEnd("abc", at(13, 31))))
    }

    @Test
    fun `malformed events change nothing`() {
        val state = ShiftbellState(armedSince = at(0, 0))
        val settings = ShiftbellSettings().normalized()

        assertSame(state, SyncMerge.apply(state, SyncEvent(type = "ack", kind = "TimeIn", date = "31/10/2026", at = "2026-10-31T07:46:00")))
        assertSame(state, SyncMerge.apply(state, SyncEvent(type = "ack", kind = "Lunch", date = "2026-10-31", at = "2026-10-31T07:46:00")))
        assertSame(settings, SyncMerge.apply(settings, SyncEvent(type = "alarm", date = "x", at = "y", def = AlarmWire(id = "x", ringAt = "25:99", updatedAt = "now"))))
    }
}
