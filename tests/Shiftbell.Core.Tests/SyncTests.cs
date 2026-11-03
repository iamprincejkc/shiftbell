using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public sealed class SyncTests
{
    // Test vectors from docs/sync.md, computed independently with Node's crypto. The phone app checks the same ones.
    private const string Code = "SB1-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA";

    /// <summary>A 0.3 ack: names the preset by kind only.</summary>
    private const string LegacyAck =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2YHvGyPMAjZ8ga4cSN7ETChqskIlJZb-3Ies9nK-7Kep-hKijdeNyscZtLGxtTBqlKfzsNCEhQFasIqmDSZx9FxJyQVrSu154V8NZgLkFbkDHkdFtyvL1jCusZomvPb";

    private const string Ack =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2YHvGyPMAjZ8ga4cSN7ETChqskIlJNW7mVRq8Hav-nF7_QTySZDN2Nfc8PGxtTBqlKfzsNCEhQFHN4_w3SNx8N2NToB5mjqp5R3Q9hfiB-T1ngoL-kNtqj0KAqWvCBp1kfRmDJ4wbwlwXzMYualGo9Q";

    private const string Test =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAGmAf6mKBeQre-ECgaVVGFTiNi55QlN4Y7ntd-5ba8aLc47QfimsaYSUSJcKFiJPRoEKb08BEEhYEE8EsgCyNnJVmPyoF-2vsp5Z2OttZ5hqW1nstN_Uf8va5LEyOpHI6xQjBKrr-a9ePZoLF_-PYG7J9o6fAjk-6MOhGSY2cxSBmWPZiGswUT130JaSE_2wV";

    private const string Definition =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2kN7C2Pd0PT91DncU0wTmXa8sYVh98KvDUQq5qM6bqKuOlIkSleJCoONPLUxczCr1qZ09BeHUNRWNAn2SLGmcN-J3tDqje-_9d2NcRKwETJiWonL4Zao670KAqAvD5rmV_QJK2bKI7YY5XWuaqcXPA-p7nKh0-6ZupbQ8_blTS9YhY5MA2aRQls74zxGUA4xfm7YfapvHQqejJqOcfcVFcQQCVXms27nx3nYqU28ihCSc_hDeQkGjpt3d6Ml2rtJB5182QuH3nr9f2rfpV-hWCGGJnCxkSiknlNgctTj_FyxHkD_bG44nuLweF814Yo0lKNlt4nAxEe3OtO4aJq8AWrSx9sBT_sTQPSJ70iXg73FPd0OQhRh0dDXnZHvwyFhf1SrzzWekSUEbkqSXacDEKWkUiB6Iozu9NP5uAmc8yyC8nn0aX-pe41NElUuqF-CUgTmCCKbybZoVc1G4gFSAtgeQnRWp0Id7jTXK9IFtdQ7jc";

    private static readonly DateOnly Saturday = new(2026, 10, 31);
    private static readonly byte[] Nonce = Enumerable.Range(0xa0, 12).Select(b => (byte)b).ToArray();
    private static readonly SyncKeys Keys = SyncKeys.FromCode(Code)!;

    private static readonly Alarm Standup = new()
    {
        Id = "standup0", Name = "Standup", RingAt = new TimeOnly(10, 0), Days = Alarm.Weekdays, Strict = false,
        Link = "https://meet.example.test/standup", ArmedAt = new DateTime(2026, 11, 3, 9, 15, 0), UpdatedAt = new DateTime(2026, 11, 3, 9, 15, 0),
    };

    private static DateTime At(int h, int m, int s = 0) => Saturday.ToDateTime(new TimeOnly(h, m, s));

    [Fact]
    public void Keys_match_the_shared_test_vectors()
    {
        Assert.Equal("d572d7e8d50d215efff4140d0edf1166", Keys.Channel);
        Assert.Equal("CHg7lE2AcanFZHsuv2ZWMbXINZJeL7O4Nx0mAzzmHO0", Keys.Token);
    }

    [Fact]
    public void An_ack_matches_the_shared_test_vector_byte_for_byte()
    {
        var ack = SyncEvent.Ack(Alarm.TimeIn(), Saturday, At(7, 46, 5));

        Assert.Equal(Ack, SyncCrypto.Seal(ack, Keys, Nonce));
        Assert.Equal(ack, SyncCrypto.Open(Ack, Keys));
    }

    [Fact]
    public void A_test_matches_the_shared_test_vector_byte_for_byte()
    {
        var test = SyncEvent.Test(Alarm.TimeOut(), At(13, 30), "0123456789abcdef") with { From = "phone" };

        Assert.Equal(Test, SyncCrypto.Seal(test, Keys, Nonce));
        Assert.Equal(test, SyncCrypto.Open(Test, Keys));
    }

    [Fact]
    public void An_alarm_definition_matches_the_shared_test_vector_byte_for_byte()
    {
        Assert.Equal(Definition, SyncCrypto.Seal(SyncEvent.Changed(Standup), Keys, Nonce));
        Assert.True(SyncCrypto.Open(Definition, Keys)!.Def!.ToAlarm()!.SameAs(Standup.Normalized()));
    }

    [Fact]
    public void A_0_3_ack_still_reads_as_the_time_in_preset()
    {
        var state = new ShiftbellState();

        Assert.True(SyncMerge.Apply(state, SyncCrypto.Open(LegacyAck, Keys)!));
        Assert.Equal(At(7, 46, 5), state.Find(Saturday)!.Find(Alarm.TimeInId)!.AcknowledgedAt);
    }

    [Fact]
    public void A_damaged_or_foreign_event_is_skipped()
    {
        var other = SyncKeys.FromCode(SyncKeys.NewCode())!;

        Assert.Null(SyncCrypto.Open(Ack[..^2] + "AA", Keys));
        Assert.Null(SyncCrypto.Open(Ack, other));
        Assert.Null(SyncCrypto.Open("not base64!", Keys));
        Assert.Null(SyncCrypto.Open("", Keys));
    }

    [Fact]
    public void Codes_are_checked_and_copy_paste_whitespace_ignored()
    {
        Assert.NotNull(SyncKeys.FromCode(SyncKeys.NewCode()));
        Assert.Equal(Code, SyncKeys.FromCode($" {Code[..20]}\n{Code[20..]} ")!.Code);
        Assert.Null(SyncKeys.FromCode("SB1-short"));
        Assert.Null(SyncKeys.FromCode("SB2-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"));
        Assert.Null(SyncKeys.FromCode(null));
    }

    [Fact]
    public void An_ack_from_the_phone_silences_the_alarm_here()
    {
        var state = new ShiftbellState { ArmedSince = At(0, 0) };
        var settings = new ShiftbellSettings().Normalized().With(Alarm.TimeIn() with { Days = [DayOfWeek.Saturday] });

        Assert.True(SyncMerge.Apply(state, SyncEvent.Ack(Alarm.TimeIn(), Saturday, At(7, 46))));

        Assert.Null(AlarmPlanner.DueNow(settings, state, At(7, 47)));
    }

    [Fact]
    public void Acks_keep_the_earliest_and_replays_change_nothing()
    {
        var state = new ShiftbellState();
        SyncMerge.Apply(state, SyncEvent.Ack(Standup, Saturday, At(10, 5)));

        Assert.True(SyncMerge.Apply(state, SyncEvent.Ack(Standup, Saturday, At(10, 1))));
        Assert.False(SyncMerge.Apply(state, SyncEvent.Ack(Standup, Saturday, At(10, 5))));
        Assert.Equal(At(10, 1), state.Find(Saturday)!.Find("standup0")!.AcknowledgedAt);
    }

    [Fact]
    public void Snoozes_keep_the_latest_and_never_undo_an_ack()
    {
        var state = new ShiftbellState();
        var timeIn = Alarm.TimeIn();
        SyncMerge.Apply(state, SyncEvent.Snoozed(timeIn, Saturday, At(7, 45), At(7, 50), 1));
        SyncMerge.Apply(state, SyncEvent.Snoozed(timeIn, Saturday, At(7, 51), At(7, 56), 2));
        Assert.False(SyncMerge.Apply(state, SyncEvent.Snoozed(timeIn, Saturday, At(7, 45), At(7, 50), 1)));

        var record = state.Find(Saturday)!.Find(Alarm.TimeInId)!;
        Assert.Equal((At(7, 56), 2), (record.SnoozedUntil!.Value, record.Snoozes));

        SyncMerge.Apply(state, SyncEvent.Ack(timeIn, Saturday, At(7, 57)));
        Assert.False(SyncMerge.Apply(state, SyncEvent.Snoozed(timeIn, Saturday, At(7, 58), At(8, 3), 3)));
        Assert.Null(record.SnoozedUntil);
    }

    [Fact]
    public void The_latest_day_off_change_wins_in_any_order()
    {
        var on = SyncEvent.DayOff(Saturday, At(6, 0), off: true);
        var undo = SyncEvent.DayOff(Saturday, At(6, 5), off: false);

        var forward = new ShiftbellState();
        SyncMerge.Apply(forward, on);
        SyncMerge.Apply(forward, undo);
        var backward = new ShiftbellState();
        SyncMerge.Apply(backward, undo);
        SyncMerge.Apply(backward, on);

        Assert.False(forward.Find(Saturday)!.DayOff);
        Assert.False(backward.Find(Saturday)!.DayOff);
    }

    [Fact]
    public void An_added_alarm_arrives_and_the_newest_edit_wins_in_any_order()
    {
        var settings = new ShiftbellSettings().Normalized();
        var older = SyncEvent.Changed(Standup);
        var newer = SyncEvent.Changed(Standup with { RingAt = new TimeOnly(10, 30), UpdatedAt = Standup.UpdatedAt.AddMinutes(5) });

        var forward = SyncMerge.Apply(SyncMerge.Apply(settings, older), newer);
        var backward = SyncMerge.Apply(SyncMerge.Apply(settings, newer), older);

        Assert.Equal(new TimeOnly(10, 30), forward.Find("standup0")!.RingAt);
        Assert.Equal(new TimeOnly(10, 30), backward.Find("standup0")!.RingAt);
        Assert.Same(forward, SyncMerge.Apply(forward, newer));
    }

    [Fact]
    public void A_deletion_reaches_the_other_device_and_an_untouched_preset_loses_to_a_real_edit()
    {
        var settings = new ShiftbellSettings().Normalized().With(Standup);

        var deleted = SyncMerge.Apply(settings, SyncEvent.Changed(Standup with { Deleted = true, UpdatedAt = Standup.UpdatedAt.AddHours(1) }));
        Assert.Null(deleted.Find("standup0"));

        var moved = SyncMerge.Apply(settings, SyncEvent.Changed(Alarm.TimeIn() with { RingAt = new TimeOnly(7, 30), UpdatedAt = At(9, 0) }));
        Assert.Equal(new TimeOnly(7, 30), moved.Find(Alarm.TimeInId)!.RingAt);
    }

    [Fact]
    public void Only_a_recent_test_from_the_other_device_rings_here()
    {
        var test = SyncEvent.Test(Alarm.TimeIn(), At(13, 30), "abc") with { From = "phone" };

        Assert.True(test.IsFreshTestFrom("phone", At(13, 31)));
        Assert.False(test.IsFreshTestFrom("phone", At(13, 33)));
        Assert.False(test.IsFreshTestFrom("pc", At(13, 31)));
        Assert.False((test with { Id = null }).IsFreshTestFrom("phone", At(13, 31)));
    }

    [Fact]
    public void Tests_never_touch_the_state()
    {
        var state = new ShiftbellState();

        Assert.False(SyncMerge.Apply(state, SyncEvent.Test(Alarm.TimeIn(), At(13, 30), "abc")));
        Assert.False(SyncMerge.Apply(state, SyncEvent.TestEnd("abc", At(13, 31))));
        Assert.Empty(state.Days);
    }

    [Fact]
    public void Malformed_events_change_nothing()
    {
        var state = new ShiftbellState();
        var settings = new ShiftbellSettings().Normalized();

        Assert.False(SyncMerge.Apply(state, new SyncEvent { Type = "ack", Kind = "TimeIn", Date = "31/10/2026", At = "2026-10-31T07:46:00" }));
        Assert.False(SyncMerge.Apply(state, new SyncEvent { Type = "ack", Kind = "Lunch", Date = "2026-10-31", At = "2026-10-31T07:46:00" }));
        Assert.False(SyncMerge.Apply(state, new SyncEvent { Type = "explode", Date = "2026-10-31", At = "2026-10-31T07:46:00" }));
        Assert.Same(settings, SyncMerge.Apply(settings, new SyncEvent { Type = "alarm", Def = new AlarmWire { Id = "x", RingAt = "25:99", UpdatedAt = "now" } }));
    }
}
