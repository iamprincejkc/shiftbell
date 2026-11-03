namespace Shiftbell.Core;

/// <summary>An alarm that should be ringing right now.</summary>
/// <param name="Date">The day it belongs to, which is what an acknowledgement is recorded against.</param>
public sealed record DueAlarm(Alarm Alarm, DateOnly Date, DateTime ScheduledAt, DateTime? Deadline, int SnoozesLeft)
{
    public string Id => Alarm.Id;
}

public sealed record UpcomingAlarm(Alarm Alarm, DateTime At);

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

        // Yesterday too, so an alarm late in the evening can still catch up after midnight.
        return new[] { today.AddDays(-1), today }
            .SelectMany(date => settings.Active.Select(alarm => Evaluate(settings, state, alarm, date, now)))
            .OfType<DueAlarm>()
            .OrderBy(due => due.ScheduledAt)
            .FirstOrDefault();
    }

    /// <summary>
    /// False once the ringing alarm was confirmed, snoozed, deleted, turned off or made a day off by anything
    /// other than this alarm screen - in practice, another device through sync.
    /// </summary>
    public static bool StillDue(ShiftbellSettings settings, ShiftbellState state, DueAlarm alarm, DateTime now) =>
        settings.Find(alarm.Id) is { } current && Evaluate(settings, state, current, alarm.Date, now) is not null;

    private static DueAlarm? Evaluate(ShiftbellSettings settings, ShiftbellState state, Alarm alarm, DateOnly date, DateTime now)
    {
        if (!alarm.Enabled || !alarm.RingsOn(date))
        {
            return null;
        }

        var scheduled = date.ToDateTime(alarm.RingAt);
        if (scheduled < state.ArmedSince || scheduled < alarm.ArmedAt)
        {
            return null;
        }

        var day = state.Find(date);
        if (day is { DayOff: true })
        {
            return null;
        }

        var record = day?.Find(alarm.Id);
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
        return new DueAlarm(alarm, date, scheduled, deadline, snoozesLeft);
    }

    /// <summary>The next moment an alarm will start ringing after <paramref name="now"/>, snooze ends included, two weeks ahead.</summary>
    public static UpcomingAlarm? Next(ShiftbellSettings settings, ShiftbellState state, DateTime now)
    {
        var today = DateOnly.FromDateTime(now);
        for (var offset = 0; offset <= 14; offset++)
        {
            var date = today.AddDays(offset);
            var day = state.Find(date);
            if (day is { DayOff: true })
            {
                continue;
            }

            var next = settings.Active
                .Where(alarm => alarm.Enabled && alarm.RingsOn(date) && day?.Find(alarm.Id)?.AcknowledgedAt is null)
                .Select(alarm => new UpcomingAlarm(alarm, day?.Find(alarm.Id)?.SnoozedUntil ?? date.ToDateTime(alarm.RingAt)))
                .Where(c => c.At > now && c.At >= state.ArmedSince && c.At >= c.Alarm.ArmedAt)
                .OrderBy(c => c.At)
                .FirstOrDefault();
            if (next is not null)
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

    /// <summary>
    /// Every distinct time an alarm rings, for registering wake-from-sleep triggers. One-off alarms are included by
    /// time of day: a wake that finds nothing due is harmless.
    /// </summary>
    public static IEnumerable<TimeOnly> RingTimes(ShiftbellSettings settings) =>
        settings.Active.Where(a => a.Enabled).Select(a => a.RingAt).Distinct().Order();
}
