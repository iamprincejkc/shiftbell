using System.Security.Cryptography;
using System.Text.Json.Serialization;

namespace Shiftbell.Core;

/// <summary>Decides the alarm's wording and colour. Time in and time out speak about the attendance punch.</summary>
public enum AlarmRole
{
    TimeIn,
    TimeOut,
    Custom,
}

/// <summary>
/// One alarm in the list: the two presets (time in, time out) or one the owner added, like a standup.
/// docs/decisions/0002-alarm-list.md describes every field and how the list syncs.
/// </summary>
public sealed record Alarm
{
    public const string TimeInId = "time-in";
    public const string TimeOutId = "time-out";

    /// <summary>Before any real edit; a preset built from 0.3 settings loses to any change made on another device.</summary>
    public static readonly DateTime NeverEdited = new(2000, 1, 1);

    public string Id { get; init; } = "";

    public AlarmRole Role { get; init; } = AlarmRole.Custom;

    public string Name { get; init; } = "";

    public bool Enabled { get; init; } = true;

    public TimeOnly RingAt { get; init; }

    /// <summary>When it is due; after this it says "late". Never earlier than <see cref="RingAt"/>.</summary>
    public TimeOnly? Deadline { get; init; }

    /// <summary>Weekdays it repeats on. Ignored when <see cref="Date"/> is set.</summary>
    public IReadOnlyList<DayOfWeek> Days { get; init; } = [];

    /// <summary>A one-off alarm's date.</summary>
    public DateOnly? Date { get; init; }

    /// <summary>True: stopped by typing "done". False: one Dismiss button.</summary>
    public bool Strict { get; init; } = true;

    /// <summary>What the alarm's button opens: the attendance page, a meeting link. Empty hides the button.</summary>
    public string Link { get; init; } = "";

    /// <summary>When its time, days or date last changed. Occurrences scheduled before this never ring.</summary>
    public DateTime ArmedAt { get; init; }

    /// <summary>When anything about it last changed, on any device. The newest wins a sync.</summary>
    public DateTime UpdatedAt { get; init; }

    /// <summary>Deleted, kept as a tombstone so the deletion reaches the other devices.</summary>
    public bool Deleted { get; init; }

    public bool RingsOn(DateOnly date) => Date is { } once ? once == date : Days.Contains(date.DayOfWeek);

    public static string NewId() => Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(8));

    public static readonly IReadOnlyList<DayOfWeek> Weekdays =
        [DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday, DayOfWeek.Friday];

    public static Alarm TimeIn() => new()
    {
        Id = TimeInId, Role = AlarmRole.TimeIn, Name = "Time in", RingAt = new TimeOnly(7, 45), Deadline = new TimeOnly(8, 0),
        Days = Weekdays, UpdatedAt = NeverEdited,
    };

    public static Alarm TimeOut() => new()
    {
        Id = TimeOutId, Role = AlarmRole.TimeOut, Name = "Time out", RingAt = new TimeOnly(17, 0), Days = Weekdays, UpdatedAt = NeverEdited,
    };

    /// <summary>True when the change from <paramref name="before"/> moves when it rings, so it must re-arm.</summary>
    public bool ScheduleDiffers(Alarm before) =>
        RingAt != before.RingAt || Date != before.Date || !Days.Order().SequenceEqual(before.Days.Order()) || Enabled != before.Enabled;

    /// <summary>Same alarm, field for field (records compare lists by reference).</summary>
    public bool SameAs(Alarm other) =>
        this with { Days = other.Days } == other && Days.Order().SequenceEqual(other.Days.Order());

    public Alarm Normalized()
    {
        var name = (Name ?? "").Trim();
        if (name.Length == 0)
        {
            name = Role switch { AlarmRole.TimeIn => "Time in", AlarmRole.TimeOut => "Time out", _ => "Alarm" };
        }

        return this with
        {
            Name = name.Length > 60 ? name[..60] : name,
            Deadline = Deadline is { } d && d < RingAt ? RingAt : Deadline,
            Days = (Days ?? []).Distinct().Order().ToArray(),
            Link = (Link ?? "").Trim(),
        };
    }
}

public sealed record ShiftbellSettings
{
    /// <summary>Every alarm, deleted ones included as tombstones. Null only in 0.3 files, which <see cref="Normalized"/> migrates.</summary>
    public IReadOnlyList<Alarm>? Alarms { get; init; }

    public int SnoozeMinutes { get; init; } = 5;

    public int MaxSnoozes { get; init; } = 2;

    /// <summary>How long after its ring time a missed alarm (PC asleep or off) still rings once you are back.</summary>
    public int CatchUpHours { get; init; } = 4;

    /// <summary>
    /// While ringing, unmute and raise the speakers to at least this. 0 leaves the volume alone.
    /// Kept low on purpose: the tone itself starts soft and climbs (<see cref="RingLoudness"/>), so the floor only has
    /// to rescue a muted or near-silent PC, not make the alarm loud. 70% with a full-scale tone hurt through headphones.
    /// </summary>
    public int MinimumVolumePercent { get; init; } = 25;

    /// <summary>The pairing code shared with the phone (docs/sync.md). Empty means this PC does not sync.</summary>
    public string SyncCode { get; init; } = "";

    // 0.3 fields, read once to build the presets and never written again.
    public AlarmSettings? TimeIn { get; init; }

    public AlarmSettings? TimeOut { get; init; }

    public IReadOnlyList<DayOfWeek>? WorkDays { get; init; }

    public string? AttendanceLink { get; init; }

    public bool? RequireTypedConfirmation { get; init; }

    /// <summary>The alarms that exist, in the order they ring. Derived, so never saved.</summary>
    [JsonIgnore]
    public IEnumerable<Alarm> Active =>
        (Alarms ?? []).Where(a => !a.Deleted).OrderBy(a => a.RingAt).ThenBy(a => a.Name, StringComparer.CurrentCultureIgnoreCase);

    public Alarm? Find(string id) => (Alarms ?? []).FirstOrDefault(a => a.Id == id && !a.Deleted);

    /// <summary>Adds or replaces one alarm by id.</summary>
    public ShiftbellSettings With(Alarm alarm) =>
        this with { Alarms = (Alarms ?? []).Where(a => a.Id != alarm.Id).Append(alarm.Normalized()).ToArray() };

    /// <summary>Clamps every value the app relies on and migrates 0.3 settings, so a hand-edited file cannot break the planner.</summary>
    public ShiftbellSettings Normalized() => this with
    {
        Alarms = (Alarms ?? Migrate()).Where(a => a.Id.Length > 0).GroupBy(a => a.Id).Select(g => g.Last().Normalized()).ToArray(),
        TimeIn = null,
        TimeOut = null,
        WorkDays = null,
        AttendanceLink = null,
        RequireTypedConfirmation = null,
        SnoozeMinutes = Math.Clamp(SnoozeMinutes, 1, 60),
        MaxSnoozes = Math.Clamp(MaxSnoozes, 0, 10),
        CatchUpHours = Math.Clamp(CatchUpHours, 0, 12),
        MinimumVolumePercent = Math.Clamp(MinimumVolumePercent, 0, 100),
        SyncCode = SyncKeys.FromCode(SyncCode)?.Code ?? "",
    };

    /// <summary>The two presets, from 0.3's fields when there are any, otherwise the defaults.</summary>
    private Alarm[] Migrate()
    {
        var days = WorkDays is { Count: > 0 } workDays ? workDays : Alarm.Weekdays;
        var link = AttendanceLink ?? "";
        var strict = RequireTypedConfirmation ?? true;
        var timeIn = Alarm.TimeIn() with { Days = days, Link = link, Strict = strict };
        var timeOut = Alarm.TimeOut() with { Days = days, Link = link, Strict = strict };
        if (TimeIn is { } oldIn)
        {
            timeIn = timeIn with { Enabled = oldIn.Enabled, RingAt = oldIn.RingAt, Deadline = oldIn.Deadline };
        }

        if (TimeOut is { } oldOut)
        {
            timeOut = timeOut with { Enabled = oldOut.Enabled, RingAt = oldOut.RingAt, Deadline = oldOut.Deadline };
        }

        return [timeIn, timeOut];
    }

    /// <summary>What you type to stop a strict alarm. The owner chose "done" (2026-10-31); the screen still asks "Timed in?".</summary>
    public const string ConfirmationPhrase = "done";
}

/// <summary>0.3's shape of the time-in and time-out alarms, kept only to read old settings files.</summary>
public sealed record AlarmSettings
{
    public bool Enabled { get; init; } = true;

    public TimeOnly RingAt { get; init; }

    public TimeOnly? Deadline { get; init; }
}
