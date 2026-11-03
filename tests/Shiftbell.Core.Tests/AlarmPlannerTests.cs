using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public class AlarmPlannerTests
{
    // Tuesday 2026-10-27. Defaults: time in rings 07:45 (deadline 08:00), time out 17:00, Mon-Fri.
    private static readonly DateOnly Tuesday = new(2026, 10, 27);
    private static readonly ShiftbellSettings Defaults = new ShiftbellSettings().Normalized();

    private static DateTime At(int hour, int minute, DateOnly? date = null) => (date ?? Tuesday).ToDateTime(new TimeOnly(hour, minute));

    private static ShiftbellState ArmedLastWeek() => new() { ArmedSince = At(0, 0, Tuesday.AddDays(-7)) };

    private static ShiftbellSettings Edit(string id, Func<Alarm, Alarm> change) => Defaults.With(change(Defaults.Find(id)!));

    private static Alarm Standup => new()
    {
        Id = "standup0", Name = "Standup", RingAt = new TimeOnly(10, 0), Days = Alarm.Weekdays, Strict = false,
        Link = "https://meet.example.test/standup", UpdatedAt = At(6, 0),
    };

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
        Assert.Equal(Alarm.TimeInId, due.Id);
        Assert.Equal(Tuesday, due.Date);
        Assert.Equal(At(8, 0), due.Deadline);
        Assert.Equal(2, due.SnoozesLeft);
    }

    [Fact]
    public void An_acknowledged_alarm_stays_quiet_and_the_next_one_still_rings()
    {
        var state = ArmedLastWeek();
        state.Acknowledge(Alarm.TimeInId, Tuesday, At(7, 50));

        Assert.Null(AlarmPlanner.DueNow(Defaults, state, At(7, 55)));
        Assert.Equal(Alarm.TimeOutId, AlarmPlanner.DueNow(Defaults, state, At(17, 0))?.Id);
    }

    [Fact]
    public void A_missed_alarm_catches_up_when_the_pc_comes_back_within_the_window()
    {
        // PC asleep from 07:00 to 09:30: the time-in alarm must still ring, now marked late.
        var due = AlarmPlanner.DueNow(Defaults, ArmedLastWeek(), At(9, 30));

        Assert.Equal(Alarm.TimeInId, due?.Id);
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
        state.Snooze(Alarm.TimeInId, Tuesday, At(7, 50));

        Assert.Null(AlarmPlanner.DueNow(Defaults, state, At(7, 49)));
        Assert.Equal(1, AlarmPlanner.DueNow(Defaults, state, At(7, 50))?.SnoozesLeft);
    }

    [Fact]
    public void Snoozes_left_never_goes_negative()
    {
        var state = ArmedLastWeek();
        for (var i = 0; i < 5; i++)
        {
            state.Snooze(Alarm.TimeInId, Tuesday, At(7, 50));
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
        Assert.Equal(Alarm.TimeOutId, AlarmPlanner.DueNow(Defaults, state, At(17, 0))?.Id);
    }

    [Fact]
    public void A_disabled_alarm_never_rings()
    {
        var settings = Edit(Alarm.TimeOutId, a => a with { Enabled = false });
        var state = ArmedLastWeek();
        state.Acknowledge(Alarm.TimeInId, Tuesday, At(7, 50));

        Assert.Null(AlarmPlanner.DueNow(settings, state, At(17, 0)));
    }

    [Fact]
    public void The_earliest_unacknowledged_alarm_rings_first()
    {
        // Ring times one minute apart, both missed: time in first.
        var settings = Edit(Alarm.TimeInId, a => a with { RingAt = new TimeOnly(9, 0), Deadline = null })
            .With(Defaults.Find(Alarm.TimeOutId)! with { RingAt = new TimeOnly(9, 1) });

        Assert.Equal(Alarm.TimeInId, AlarmPlanner.DueNow(settings, ArmedLastWeek(), At(9, 5))?.Id);
    }

    [Fact]
    public void A_late_evening_alarm_still_catches_up_after_midnight_against_its_own_day()
    {
        var settings = Edit(Alarm.TimeOutId, a => a with { RingAt = new TimeOnly(23, 30) });
        var state = ArmedLastWeek();
        state.Acknowledge(Alarm.TimeInId, Tuesday, At(7, 50));

        var due = AlarmPlanner.DueNow(settings, state, At(0, 30, Tuesday.AddDays(1)));

        Assert.Equal(Alarm.TimeOutId, due?.Id);
        Assert.Equal(Tuesday, due!.Date);
    }

    [Fact]
    public void An_added_alarm_rings_on_its_days_and_is_confirmed_on_its_own()
    {
        var settings = Defaults.With(Standup);
        var state = ArmedLastWeek();
        state.Acknowledge(Alarm.TimeInId, Tuesday, At(7, 50));

        Assert.Equal("standup0", AlarmPlanner.DueNow(settings, state, At(10, 0))?.Id);

        state.Acknowledge("standup0", Tuesday, At(10, 1));
        Assert.Null(AlarmPlanner.DueNow(settings, state, At(10, 2)));
        Assert.Equal(Alarm.TimeOutId, AlarmPlanner.DueNow(settings, state, At(17, 0))?.Id);
    }

    [Fact]
    public void A_one_off_alarm_rings_only_on_its_date_even_on_a_weekend()
    {
        var saturday = new DateOnly(2026, 10, 31);
        var call = Standup with { Id = "call0000", Name = "Client call", Date = saturday, RingAt = new TimeOnly(14, 30) };
        var settings = Defaults.With(call);

        Assert.Equal("call0000", AlarmPlanner.DueNow(settings, ArmedLastWeek(), At(14, 30, saturday))?.Id);
        Assert.Null(AlarmPlanner.DueNow(settings, ArmedLastWeek(), At(14, 30, saturday.AddDays(7))));
        Assert.Null(AlarmPlanner.DueNow(settings, ArmedLastWeek(), At(14, 30)));
    }

    [Fact]
    public void A_deleted_alarm_never_rings_and_stops_one_that_is_ringing()
    {
        var settings = Defaults.With(Standup);
        var state = ArmedLastWeek();
        state.Acknowledge(Alarm.TimeInId, Tuesday, At(7, 50));
        var ringing = AlarmPlanner.DueNow(settings, state, At(10, 0))!;

        var deleted = settings.With(Standup with { Deleted = true });

        Assert.Null(AlarmPlanner.DueNow(deleted, state, At(10, 0)));
        Assert.False(AlarmPlanner.StillDue(deleted, state, ringing, At(10, 1)));
        Assert.DoesNotContain(deleted.Active, a => a.Id == "standup0");
    }

    [Fact]
    public void Moving_an_alarm_earlier_than_now_does_not_ring_it_late()
    {
        // At 10:30 the standup is moved to 10:15: it must wait for tomorrow, not fire as missed.
        var settings = Defaults.With(Standup with { RingAt = new TimeOnly(10, 15), ArmedAt = At(10, 30) });
        var state = ArmedLastWeek();
        state.Acknowledge(Alarm.TimeInId, Tuesday, At(7, 50));

        Assert.Null(AlarmPlanner.DueNow(settings, state, At(10, 31)));
        state.Acknowledge(Alarm.TimeInId, Tuesday.AddDays(1), At(7, 50, Tuesday.AddDays(1)));
        Assert.Equal("standup0", AlarmPlanner.DueNow(settings, state, At(10, 15, Tuesday.AddDays(1)))?.Id);
    }

    [Fact]
    public void Next_skips_to_monday_from_friday_evening()
    {
        var friday = new DateOnly(2026, 10, 30);
        var state = ArmedLastWeek();
        state.Acknowledge(Alarm.TimeInId, friday, At(7, 50, friday));
        state.Acknowledge(Alarm.TimeOutId, friday, At(17, 1, friday));

        var next = AlarmPlanner.Next(Defaults, state, At(17, 5, friday));

        Assert.Equal(Alarm.TimeInId, next?.Alarm.Id);
        Assert.Equal(At(7, 45, new DateOnly(2026, 11, 2)), next?.At);
    }

    [Fact]
    public void Next_reports_the_snooze_end_for_a_snoozed_alarm()
    {
        var state = ArmedLastWeek();
        state.Snooze(Alarm.TimeInId, Tuesday, At(7, 50));

        Assert.Equal(At(7, 50), AlarmPlanner.Next(Defaults, state, At(7, 46))?.At);
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
            Alarms = [Alarm.TimeIn() with { RingAt = new TimeOnly(8, 0), Deadline = new TimeOnly(7, 0), Days = [DayOfWeek.Friday, DayOfWeek.Monday, DayOfWeek.Friday] }],
            SnoozeMinutes = 0,
            MaxSnoozes = -3,
            CatchUpHours = 99,
            MinimumVolumePercent = 400,
        }.Normalized();

        var timeIn = bad.Find(Alarm.TimeInId)!;
        Assert.Equal(new TimeOnly(8, 0), timeIn.Deadline);
        Assert.Equal([DayOfWeek.Monday, DayOfWeek.Friday], timeIn.Days);
        Assert.Equal(1, bad.SnoozeMinutes);
        Assert.Equal(0, bad.MaxSnoozes);
        Assert.Equal(12, bad.CatchUpHours);
        Assert.Equal(100, bad.MinimumVolumePercent);
    }

    [Fact]
    public void Old_settings_become_the_two_presets_and_an_empty_list_stays_empty()
    {
        var old = new ShiftbellSettings
        {
            TimeIn = new AlarmSettings { RingAt = new TimeOnly(6, 50), Deadline = new TimeOnly(7, 0) },
            TimeOut = new AlarmSettings { Enabled = false, RingAt = new TimeOnly(16, 0) },
            WorkDays = [DayOfWeek.Saturday],
            AttendanceLink = "https://hr.example.test/punch",
            RequireTypedConfirmation = false,
        }.Normalized();

        var timeIn = old.Find(Alarm.TimeInId)!;
        Assert.Equal((new TimeOnly(6, 50), new TimeOnly(7, 0), "https://hr.example.test/punch", false), (timeIn.RingAt, timeIn.Deadline, timeIn.Link, timeIn.Strict));
        Assert.Equal([DayOfWeek.Saturday], timeIn.Days);
        Assert.False(old.Find(Alarm.TimeOutId)!.Enabled);
        Assert.Equal(Alarm.NeverEdited, timeIn.UpdatedAt);
        Assert.Null(old.TimeIn);

        Assert.Empty(new ShiftbellSettings { Alarms = [] }.Normalized().Active);
    }
}
