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

    /// <summary>Each alarm's record for the day, by alarm id.</summary>
    public Dictionary<string, AlarmRecord> Alarms { get; set; } = [];

    // 0.3 records, moved into Alarms by ShiftbellState.Migrate and never written again.
    public AlarmRecord? TimeIn { get; set; }

    public AlarmRecord? TimeOut { get; set; }

    public AlarmRecord? Find(string alarmId) => Alarms.GetValueOrDefault(alarmId);

    public AlarmRecord Record(string alarmId)
    {
        if (!Alarms.TryGetValue(alarmId, out var record))
        {
            record = new AlarmRecord();
            Alarms[alarmId] = record;
        }

        return record;
    }
}

/// <summary>
/// The persisted record of acknowledgements. Alarms are decided from this plus the clock, never from
/// an in-memory timer, so a crash, restart, sleep or clock change can never lose an alarm.
/// </summary>
public sealed class ShiftbellState
{
    public const int KeepDays = 90;

    /// <summary>
    /// Alarms scheduled before this moment never ring. Set on first run, so installing at 10:00 does not fire a
    /// "late" alarm at once. Starting the app does not move it, which is what lets a missed alarm catch up after sleep.
    /// Each alarm also has its own <see cref="Alarm.ArmedAt"/> for edits.
    /// </summary>
    public DateTime ArmedSince { get; set; }

    public List<DayLog> Days { get; set; } = [];

    /// <summary>The relay's clock (ms) at the last successful read; the next read starts a little before it.</summary>
    public long SyncSince { get; set; }

    /// <summary>Local changes not yet accepted by the relay. Kept on disk, so a confirm made offline still reaches the phone.</summary>
    public List<SyncEvent> Outbox { get; set; } = [];

    /// <summary>Whether every alarm definition has been sent since pairing; until then the other device may not have them.</summary>
    public bool AlarmsPublished { get; set; }

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

    public void Acknowledge(string alarmId, DateOnly date, DateTime now)
    {
        var record = Day(date).Record(alarmId);
        record.AcknowledgedAt = now;
        record.SnoozedUntil = null;
    }

    public void Snooze(string alarmId, DateOnly date, DateTime until)
    {
        var record = Day(date).Record(alarmId);
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

    /// <summary>Moves 0.3's fixed time-in and time-out records into the per-alarm map.</summary>
    public ShiftbellState Migrate()
    {
        foreach (var day in Days)
        {
            day.Alarms ??= [];
            if (day.TimeIn is { } timeIn)
            {
                day.Alarms.TryAdd(Alarm.TimeInId, timeIn);
            }

            if (day.TimeOut is { } timeOut)
            {
                day.Alarms.TryAdd(Alarm.TimeOutId, timeOut);
            }

            day.TimeIn = null;
            day.TimeOut = null;
        }

        Outbox ??= [];
        return this;
    }
}
