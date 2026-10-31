namespace Shiftbell.Core;

/// <summary>What happened to one alarm on one day.</summary>
public sealed class AlarmRecord
{
    public DateTime? AcknowledgedAt { get; set; }

    public DateTime? SnoozedUntil { get; set; }

    public int Snoozes { get; set; }
}

public sealed class DayLog
{
    public DateOnly Date { get; set; }

    /// <summary>Holiday or leave: no alarm rings on this day.</summary>
    public bool DayOff { get; set; }

    /// <summary>When <see cref="DayOff"/> last changed, on any device. The latest change wins a sync.</summary>
    public DateTime? DayOffChangedAt { get; set; }

    public AlarmRecord TimeIn { get; set; } = new();

    public AlarmRecord TimeOut { get; set; } = new();

    public AlarmRecord For(AlarmKind kind) => kind == AlarmKind.TimeIn ? TimeIn : TimeOut;
}

/// <summary>
/// The persisted record of acknowledgements. Alarms are decided from this plus the clock, never from
/// an in-memory timer, so a crash, restart, sleep or clock change can never lose an alarm.
/// </summary>
public sealed class ShiftbellState
{
    public const int KeepDays = 90;

    /// <summary>
    /// Alarms scheduled before this moment never ring. Set on first run and whenever settings are saved,
    /// so installing at 10:00 or moving the time-in earlier does not fire a "late" alarm at once.
    /// Starting the app does not move it, which is what lets a missed alarm catch up after sleep.
    /// </summary>
    public DateTime ArmedSince { get; set; }

    public List<DayLog> Days { get; set; } = [];

    /// <summary>The relay's clock (ms) at the last successful read; the next read starts a little before it.</summary>
    public long SyncSince { get; set; }

    /// <summary>Local changes not yet accepted by the relay. Kept on disk, so a confirm made offline still reaches the phone.</summary>
    public List<SyncEvent> Outbox { get; set; } = [];

    public DayLog? Find(DateOnly date) => Days.Find(d => d.Date == date);

    public DayLog Day(DateOnly date)
    {
        var day = Find(date);
        if (day is null)
        {
            day = new DayLog { Date = date };
            Days.Add(day);
        }

        return day;
    }

    public void Acknowledge(AlarmKind kind, DateOnly date, DateTime now)
    {
        var record = Day(date).For(kind);
        record.AcknowledgedAt = now;
        record.SnoozedUntil = null;
    }

    public void Snooze(AlarmKind kind, DateOnly date, DateTime until)
    {
        var record = Day(date).For(kind);
        record.SnoozedUntil = until;
        record.Snoozes++;
    }

    public void SetDayOff(DateOnly date, bool off, DateTime at)
    {
        var day = Day(date);
        day.DayOff = off;
        day.DayOffChangedAt = at;
    }

    public void Prune(DateOnly today) => Days.RemoveAll(d => d.Date < today.AddDays(-KeepDays) || d.Date > today.AddDays(KeepDays));
}
