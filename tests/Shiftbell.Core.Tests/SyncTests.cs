using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public sealed class SyncTests
{
    // Test vectors from docs/sync.md, computed independently with Node's crypto. The phone app checks the same ones.
    private const string Code = "SB1-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA";
    private const string Data =
        "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2YHvGyPMAjZ8ga4cSN7ETChqskIlJZb-3Ies9nK-7Kep-hKijdeNyscZtLGxtTBqlKfzsNCEhQFasIqmDSZx9FxJyQVrSu154V8NZgLkFbkDHkdFtyvL1jCusZomvPb";

    private static readonly DateOnly Saturday = new(2026, 10, 31);
    private static readonly byte[] Nonce = Enumerable.Range(0xa0, 12).Select(b => (byte)b).ToArray();

    private static DateTime At(int h, int m, int s = 0) => Saturday.ToDateTime(new TimeOnly(h, m, s));

    [Fact]
    public void Keys_match_the_shared_test_vectors()
    {
        var keys = SyncKeys.FromCode(Code)!;

        Assert.Equal("d572d7e8d50d215efff4140d0edf1166", keys.Channel);
        Assert.Equal("CHg7lE2AcanFZHsuv2ZWMbXINZJeL7O4Nx0mAzzmHO0", keys.Token);
    }

    [Fact]
    public void Sealing_matches_the_shared_test_vector_byte_for_byte()
    {
        var keys = SyncKeys.FromCode(Code)!;

        Assert.Equal(Data, SyncCrypto.Seal(SyncEvent.Ack(AlarmKind.TimeIn, Saturday, At(7, 46, 5)), keys, Nonce));
    }

    [Fact]
    public void Opening_reads_the_shared_test_vector()
    {
        var e = SyncCrypto.Open(Data, SyncKeys.FromCode(Code)!);

        Assert.Equal(SyncEvent.Ack(AlarmKind.TimeIn, Saturday, At(7, 46, 5)), e);
    }

    [Fact]
    public void A_damaged_or_foreign_event_is_skipped()
    {
        var keys = SyncKeys.FromCode(Code)!;
        var other = SyncKeys.FromCode(SyncKeys.NewCode())!;

        Assert.Null(SyncCrypto.Open(Data[..^2] + "AA", keys));
        Assert.Null(SyncCrypto.Open(Data, other));
        Assert.Null(SyncCrypto.Open("not base64!", keys));
        Assert.Null(SyncCrypto.Open("", keys));
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
        var settings = new ShiftbellSettings { WorkDays = [DayOfWeek.Saturday] };

        Assert.True(SyncMerge.Apply(state, SyncEvent.Ack(AlarmKind.TimeIn, Saturday, At(7, 46))));

        Assert.Null(AlarmPlanner.DueNow(settings, state, At(7, 47)));
    }

    [Fact]
    public void Acks_keep_the_earliest_and_replays_change_nothing()
    {
        var state = new ShiftbellState();
        SyncMerge.Apply(state, SyncEvent.Ack(AlarmKind.TimeIn, Saturday, At(7, 50)));

        Assert.True(SyncMerge.Apply(state, SyncEvent.Ack(AlarmKind.TimeIn, Saturday, At(7, 46))));
        Assert.False(SyncMerge.Apply(state, SyncEvent.Ack(AlarmKind.TimeIn, Saturday, At(7, 50))));
        Assert.Equal(At(7, 46), state.Find(Saturday)!.TimeIn.AcknowledgedAt);
    }

    [Fact]
    public void Snoozes_keep_the_latest_and_never_undo_an_ack()
    {
        var state = new ShiftbellState();
        SyncMerge.Apply(state, SyncEvent.Snoozed(AlarmKind.TimeIn, Saturday, At(7, 45), At(7, 50), 1));
        SyncMerge.Apply(state, SyncEvent.Snoozed(AlarmKind.TimeIn, Saturday, At(7, 51), At(7, 56), 2));
        Assert.False(SyncMerge.Apply(state, SyncEvent.Snoozed(AlarmKind.TimeIn, Saturday, At(7, 45), At(7, 50), 1)));

        Assert.Equal(At(7, 56), state.Find(Saturday)!.TimeIn.SnoozedUntil);
        Assert.Equal(2, state.Find(Saturday)!.TimeIn.Snoozes);

        SyncMerge.Apply(state, SyncEvent.Ack(AlarmKind.TimeIn, Saturday, At(7, 57)));
        Assert.False(SyncMerge.Apply(state, SyncEvent.Snoozed(AlarmKind.TimeIn, Saturday, At(7, 58), At(8, 3), 3)));
        Assert.Null(state.Find(Saturday)!.TimeIn.SnoozedUntil);
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
    public void Only_a_recent_test_from_the_other_device_rings_here()
    {
        var test = SyncEvent.Test(AlarmKind.TimeIn, At(13, 30), "abc") with { From = "phone" };

        Assert.True(test.IsFreshTestFrom("phone", At(13, 31)));
        Assert.False(test.IsFreshTestFrom("phone", At(13, 33)));
        Assert.False(test.IsFreshTestFrom("pc", At(13, 31)));
        Assert.False((test with { Id = null }).IsFreshTestFrom("phone", At(13, 31)));
    }

    [Fact]
    public void Tests_never_touch_the_state()
    {
        var state = new ShiftbellState();

        Assert.False(SyncMerge.Apply(state, SyncEvent.Test(AlarmKind.TimeIn, At(13, 30), "abc")));
        Assert.False(SyncMerge.Apply(state, SyncEvent.TestEnd("abc", At(13, 31))));
        Assert.Empty(state.Days);
    }

    [Fact]
    public void Test_events_match_the_shared_test_vector_byte_for_byte()
    {
        const string testData =
            "oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAGmAf6mKBeQre-ECgaVVGFTiNi55QlN4Y63ZI7NnC6bKYuO9XljRCJjYcK4SFiNTJuFKZ0cRfDhcZDcNJkzOVztF-NTgV53uz7oV8NdhZgBiR2X4qNfxOsrn_IE6OsnJshlWfPLXqLIfZaYWE5iHff6HpZo9xjESzYFG5GjE";
        var keys = SyncKeys.FromCode(Code)!;
        var test = SyncEvent.Test(AlarmKind.TimeOut, At(13, 30), "0123456789abcdef") with { From = "phone" };

        Assert.Equal(testData, SyncCrypto.Seal(test, keys, Nonce));
        Assert.Equal(test, SyncCrypto.Open(testData, keys));
    }

    [Fact]
    public void Malformed_events_change_nothing()
    {
        var state = new ShiftbellState();

        Assert.False(SyncMerge.Apply(state, new SyncEvent { Type = "ack", Kind = "TimeIn", Date = "31/10/2026", At = "2026-10-31T07:46:00" }));
        Assert.False(SyncMerge.Apply(state, new SyncEvent { Type = "ack", Kind = "Lunch", Date = "2026-10-31", At = "2026-10-31T07:46:00" }));
        Assert.False(SyncMerge.Apply(state, new SyncEvent { Type = "explode", Date = "2026-10-31", At = "2026-10-31T07:46:00" }));
    }
}
