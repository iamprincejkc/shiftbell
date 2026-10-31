using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public class AlarmPlannerTests
{
    // Tuesday 2026-10-27. Defaults: time in rings 07:45 (deadline 08:00), time out 17:00, Mon-Fri.
    private static readonly DateOnly Tuesday = new(2026, 10, 27);
    private static readonly ShiftbellSettings Defaults = new ShiftbellSettings().Normalized();

    private static DateTime At(int hour, int minute, DateOnly? date = null) => (date ?? Tuesday).ToDateTime(new TimeOnly(hour, minute));

    private static ShiftbellState ArmedLastWeek() => new() { ArmedSince = At(0, 0, Tuesday.AddDays(-7)) };

    [Fact]
    public void Nothing_rings_before_the_time()
    {
        Assert.Null(AlarmPlanner.DueNow(Defaults, ArmedLastWeek(), At(7, 44)));
    }

    [Fact]
    public void Time_in_rings_at_its_time_with_its_deadline()
    {
        var due = AlarmPlanner.DueNow(Defaults, ArmedLastWeek(), At(7, 45));

        Assert.NotNull(due);
        Assert.Equal(AlarmKind.TimeIn, due.Kind);
        Assert.Equal(Tuesday, due.Date);
        Assert.Equal(At(8, 0), due.Deadline);
        Assert.Equal(2, due.SnoozesLeft);
    }

    [Fact]
    public void An_acknowledged_alarm_stays_quiet_and_the_next_one_still_rings()
    {
        var state = ArmedLastWeek();
        state.Acknowledge(AlarmKind.TimeIn, Tuesday, At(7, 50));

        Assert.Null(AlarmPlanner.DueNow(Defaults, state, At(7, 55)));
        Assert.Equal(AlarmKind.TimeOut, AlarmPlanner.DueNow(Defaults, state, At(17, 0))?.Kind);
    }

    [Fact]
    public void A_missed_alarm_catches_up_when_the_pc_comes_back_within_the_window()
    {
        // PC asleep from 07:00 to 09:30: the time-in alarm must still ring, now marked late.
        var due = AlarmPlanner.DueNow(Defaults, ArmedLastWeek(), At(9, 30));

        Assert.Equal(AlarmKind.TimeIn, due?.Kind);
        Assert.True(At(9, 30) > due!.Deadline);
    }

    [Fact]
    public void A_missed_alarm_gives_up_after_the_catch_up_window()
    {
        Assert.Null(AlarmPlanner.DueNow(Defaults, ArmedLastWeek(), At(11, 46)));
    }

    [Fact]
    public void Catch_up_zero_still_allows_a_short_grace_so_a_slow_tick_does_not_lose_the_alarm()
    {
        var settings = Defaults with { CatchUpHours = 0 };

        Assert.NotNull(AlarmPlanner.DueNow(settings, ArmedLastWeek(), At(7, 46)));
        Assert.Null(AlarmPlanner.DueNow(settings, ArmedLastWeek(), At(7, 48)));
    }

    [Fact]
    public void A_snoozed_alarm_rings_again_when_the_snooze_ends_with_one_fewer_snooze_left()
    {
        var state = ArmedLastWeek();
        state.Snooze(AlarmKind.TimeIn, Tuesday, At(7, 50));

        Assert.Null(AlarmPlanner.DueNow(Defaults, state, At(7, 49)));
        Assert.Equal(1, AlarmPlanner.DueNow(Defaults, state, At(7, 50))?.SnoozesLeft);
    }

    [Fact]
    public void Snoozes_left_never_goes_negative()
    {
        var state = ArmedLastWeek();
        for (var i = 0; i < 5; i++)
        {
            state.Snooze(AlarmKind.TimeIn, Tuesday, At(7, 50));
        }

        Assert.Equal(0, AlarmPlanner.DueNow(Defaults, state, At(7, 50))?.SnoozesLeft);
    }

    [Fact]
    public void Weekends_and_days_off_are_silent()
    {
        var saturday = new DateOnly(2026, 10, 31);
        Assert.Null(AlarmPlanner.DueNow(Defaults, ArmedLastWeek(), At(7, 45, saturday)));

        var state = ArmedLastWeek();
        state.SetDayOff(Tuesday, true, Tuesday.ToDateTime(new TimeOnly(6, 0)));
        Assert.Null(AlarmPlanner.DueNow(Defaults, state, At(7, 45)));
    }

    [Fact]
    public void Installing_after_the_ring_time_does_not_fire_a_late_alarm_at_once()
    {
        var state = new ShiftbellState { ArmedSince = At(10, 0) };

        Assert.Null(AlarmPlanner.DueNow(Defaults, state, At(10, 0)));
        Assert.Equal(AlarmKind.TimeOut, AlarmPlanner.DueNow(Defaults, state, At(17, 0))?.Kind);
    }

    [Fact]
    public void A_disabled_alarm_never_rings()
    {
        var settings = Defaults with { TimeOut = Defaults.TimeOut with { Enabled = false } };
        var state = ArmedLastWeek();
        state.Acknowledge(AlarmKind.TimeIn, Tuesday, At(7, 50));

        Assert.Null(AlarmPlanner.DueNow(settings, state, At(17, 0)));
    }

    [Fact]
    public void The_earliest_unacknowledged_alarm_rings_first()
    {
        // Ring times one minute apart, both missed: time in first.
        var settings = Defaults with
        {
            TimeIn = Defaults.TimeIn with { RingAt = new TimeOnly(9, 0), Deadline = null },
            TimeOut = Defaults.TimeOut with { RingAt = new TimeOnly(9, 1) },
        };

        Assert.Equal(AlarmKind.TimeIn, AlarmPlanner.DueNow(settings, ArmedLastWeek(), At(9, 5))?.Kind);
    }

    [Fact]
    public void A_late_evening_alarm_still_catches_up_after_midnight_against_its_own_day()
    {
        var settings = Defaults with { TimeOut = Defaults.TimeOut with { RingAt = new TimeOnly(23, 30) } };
        var state = ArmedLastWeek();
        state.Acknowledge(AlarmKind.TimeIn, Tuesday, At(7, 50));

        var due = AlarmPlanner.DueNow(settings, state, At(0, 30, Tuesday.AddDays(1)));

        Assert.Equal(AlarmKind.TimeOut, due?.Kind);
        Assert.Equal(Tuesday, due!.Date);
    }

    [Fact]
    public void Next_skips_to_monday_from_friday_evening()
    {
        var friday = new DateOnly(2026, 10, 30);
        var state = ArmedLastWeek();
        state.Acknowledge(AlarmKind.TimeIn, friday, At(7, 50, friday));
        state.Acknowledge(AlarmKind.TimeOut, friday, At(17, 1, friday));

        var next = AlarmPlanner.Next(Defaults, state, At(17, 5, friday));

        Assert.Equal(new UpcomingAlarm(AlarmKind.TimeIn, At(7, 45, new DateOnly(2026, 11, 2))), next);
    }

    [Fact]
    public void Next_reports_the_snooze_end_for_a_snoozed_alarm()
    {
        var state = ArmedLastWeek();
        state.Snooze(AlarmKind.TimeIn, Tuesday, At(7, 50));

        Assert.Equal(new UpcomingAlarm(AlarmKind.TimeIn, At(7, 50)), AlarmPlanner.Next(Defaults, state, At(7, 46)));
    }

    [Fact]
    public void Stays_awake_only_shortly_before_or_during_an_alarm()
    {
        var state = ArmedLastWeek();

        Assert.False(AlarmPlanner.ShouldStayAwake(Defaults, state, At(7, 30)));
        Assert.True(AlarmPlanner.ShouldStayAwake(Defaults, state, At(7, 36)));
        Assert.True(AlarmPlanner.ShouldStayAwake(Defaults, state, At(7, 50)));
    }

    [Fact]
    public void Normalized_clamps_bad_values_and_pulls_a_deadline_up_to_the_ring_time()
    {
        var bad = new ShiftbellSettings
        {
            TimeIn = new AlarmSettings { RingAt = new TimeOnly(8, 0), Deadline = new TimeOnly(7, 0) },
            SnoozeMinutes = 0,
            MaxSnoozes = -3,
            CatchUpHours = 99,
            MinimumVolumePercent = 400,
            WorkDays = [DayOfWeek.Friday, DayOfWeek.Monday, DayOfWeek.Friday],
        }.Normalized();

        Assert.Equal(new TimeOnly(8, 0), bad.TimeIn.Deadline);
        Assert.Equal(1, bad.SnoozeMinutes);
        Assert.Equal(0, bad.MaxSnoozes);
        Assert.Equal(12, bad.CatchUpHours);
        Assert.Equal(100, bad.MinimumVolumePercent);
        Assert.Equal([DayOfWeek.Monday, DayOfWeek.Friday], bad.WorkDays);
    }
}
