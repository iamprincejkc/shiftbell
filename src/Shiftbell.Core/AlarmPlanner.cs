namespace Shiftbell.Core;

/// <summary>An alarm that should be ringing right now.</summary>
/// <param name="Date">The work day it belongs to, which is what an acknowledgement is recorded against.</param>
public sealed record DueAlarm(AlarmKind Kind, DateOnly Date, DateTime ScheduledAt, DateTime? Deadline, int SnoozesLeft);

public sealed record UpcomingAlarm(AlarmKind Kind, DateTime At);

/// <summary>Pure decisions about alarms: settings + state + the time in, answer out. No clocks, no I/O.</summary>
public static class AlarmPlanner
{
    /// <summary>How long before an alarm the PC is held awake, so idle sleep cannot swallow it.</summary>
    public static readonly TimeSpan StayAwakeLead = TimeSpan.FromMinutes(10);

    /// <summary>A missed alarm still rings this long after its time even with catch-up set to 0 hours.</summary>
    private static readonly TimeSpan MinimumGrace = TimeSpan.FromMinutes(2);

    /// <summary>The alarm that should ring now, earliest first, or null.</summary>
    public static DueAlarm? DueNow(ShiftbellSettings settings, ShiftbellState state, DateTime now)
    {
        var today = DateOnly.FromDateTime(now);
        DueAlarm? earliest = null;

        // Yesterday too, so an alarm late in the evening can still catch up after midnight.
        foreach (var date in new[] { today.AddDays(-1), today })
        {
            foreach (var kind in Enum.GetValues<AlarmKind>())
            {
                var due = Evaluate(settings, state, kind, date, now);
                if (due is not null && (earliest is null || due.ScheduledAt < earliest.ScheduledAt))
                {
                    earliest = due;
                }
            }
        }

        return earliest;
    }

    /// <summary>
    /// False once the ringing alarm was confirmed, snoozed or made a day off by anything other than this alarm
    /// screen - in practice, the phone through sync.
    /// </summary>
    public static bool StillDue(ShiftbellSettings settings, ShiftbellState state, DueAlarm alarm, DateTime now) =>
        Evaluate(settings, state, alarm.Kind, alarm.Date, now) is not null;

    private static DueAlarm? Evaluate(ShiftbellSettings settings, ShiftbellState state, AlarmKind kind, DateOnly date, DateTime now)
    {
        var alarm = settings.For(kind);
        if (!alarm.Enabled || !settings.WorkDays.Contains(date.DayOfWeek))
        {
            return null;
        }

        var scheduled = date.ToDateTime(alarm.RingAt);
        if (scheduled < state.ArmedSince)
        {
            return null;
        }

        var day = state.Find(date);
        if (day is { DayOff: true })
        {
            return null;
        }

        var record = day?.For(kind);
        if (record?.AcknowledgedAt is not null)
        {
            return null;
        }

        var ringFrom = record?.SnoozedUntil is { } snoozed && snoozed > scheduled ? snoozed : scheduled;
        var grace = TimeSpan.FromHours(settings.CatchUpHours);
        if (grace < MinimumGrace)
        {
            grace = MinimumGrace;
        }

        if (now < ringFrom || now > ringFrom + grace)
        {
            return null;
        }

        var deadline = alarm.Deadline is { } d ? date.ToDateTime(d) : (DateTime?)null;
        var snoozesLeft = Math.Max(0, settings.MaxSnoozes - (record?.Snoozes ?? 0));
        return new DueAlarm(kind, date, scheduled, deadline, snoozesLeft);
    }

    /// <summary>The next alarm that will ring after <paramref name="now"/>, looking two weeks ahead.</summary>
    public static UpcomingAlarm? Next(ShiftbellSettings settings, ShiftbellState state, DateTime now)
    {
        var today = DateOnly.FromDateTime(now);
        for (var offset = 0; offset <= 14; offset++)
        {
            var date = today.AddDays(offset);
            if (!settings.WorkDays.Contains(date.DayOfWeek) || state.Find(date) is { DayOff: true })
            {
                continue;
            }

            var candidates = Enum.GetValues<AlarmKind>()
                .Select(kind => (kind, alarm: settings.For(kind), record: state.Find(date)?.For(kind)))
                .Where(c => c.alarm.Enabled && c.record?.AcknowledgedAt is null)
                .Select(c => new UpcomingAlarm(c.kind, c.record?.SnoozedUntil ?? date.ToDateTime(c.alarm.RingAt)))
                .Where(c => c.At > now && c.At >= state.ArmedSince)
                .OrderBy(c => c.At);

            if (candidates.FirstOrDefault() is { } next)
            {
                return next;
            }
        }

        return null;
    }

    /// <summary>True when idle sleep must be held off: an alarm is ringing or one is about to.</summary>
    public static bool ShouldStayAwake(ShiftbellSettings settings, ShiftbellState state, DateTime now) =>
        DueNow(settings, state, now) is not null
        || Next(settings, state, now) is { } next && next.At - now <= StayAwakeLead;

    /// <summary>The ring times on each work day, for registering wake-from-sleep triggers.</summary>
    public static IEnumerable<TimeOnly> RingTimes(ShiftbellSettings settings) =>
        Enum.GetValues<AlarmKind>().Select(settings.For).Where(a => a.Enabled).Select(a => a.RingAt).Distinct().Order();
}
