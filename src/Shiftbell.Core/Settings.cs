namespace Shiftbell.Core;

public enum AlarmKind
{
    TimeIn,
    TimeOut,
}

/// <summary>One daily alarm: when it rings, and the time by which the punch must be done.</summary>
public sealed record AlarmSettings
{
    public bool Enabled { get; init; } = true;

    public TimeOnly RingAt { get; init; }

    /// <summary>When the punch is due. Null means "no deadline" (time-out). Never earlier than <see cref="RingAt"/>.</summary>
    public TimeOnly? Deadline { get; init; }
}

public sealed record ShiftbellSettings
{
    public AlarmSettings TimeIn { get; init; } = new() { RingAt = new TimeOnly(7, 45), Deadline = new TimeOnly(8, 0) };

    public AlarmSettings TimeOut { get; init; } = new() { RingAt = new TimeOnly(17, 0) };

    public IReadOnlyList<DayOfWeek> WorkDays { get; init; } =
        [DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday, DayOfWeek.Friday];

    /// <summary>A URL or program the alarm's "Open attendance" button launches. Empty hides the button.</summary>
    public string AttendanceLink { get; init; } = DefaultAttendanceLink;

    /// <summary>
    /// None built in: the attendance page is the user's own (an employer portal), set once in Settings and
    /// kept in their settings file, never in the published source.
    /// </summary>
    public const string DefaultAttendanceLink = "";

    public int SnoozeMinutes { get; init; } = 5;

    public int MaxSnoozes { get; init; } = 2;

    /// <summary>How long after its ring time a missed alarm (PC asleep or off) still rings once you are back.</summary>
    public int CatchUpHours { get; init; } = 4;

    /// <summary>Dismissing needs the confirmation phrase typed, so the alarm cannot be clicked away unread.</summary>
    public bool RequireTypedConfirmation { get; init; } = true;

    /// <summary>
    /// While ringing, unmute and raise the speakers to at least this. 0 leaves the volume alone.
    /// Kept low on purpose: the tone itself starts soft and climbs (<see cref="RingLoudness"/>), so the floor only has
    /// to rescue a muted or near-silent PC, not make the alarm loud. 70% with a full-scale tone hurt through headphones.
    /// </summary>
    public int MinimumVolumePercent { get; init; } = 25;

    /// <summary>The pairing code shared with the phone (docs/sync.md). Empty means this PC does not sync.</summary>
    public string SyncCode { get; init; } = "";

    public AlarmSettings For(AlarmKind kind) => kind == AlarmKind.TimeIn ? TimeIn : TimeOut;

    /// <summary>Clamps every value into the range the app can honour, so a hand-edited file cannot break the planner.</summary>
    public ShiftbellSettings Normalized() => this with
    {
        TimeIn = NormalizeAlarm(TimeIn),
        TimeOut = NormalizeAlarm(TimeOut),
        WorkDays = (WorkDays ?? []).Distinct().Order().ToArray(),
        AttendanceLink = (AttendanceLink ?? "").Trim(),
        SnoozeMinutes = Math.Clamp(SnoozeMinutes, 1, 60),
        MaxSnoozes = Math.Clamp(MaxSnoozes, 0, 10),
        CatchUpHours = Math.Clamp(CatchUpHours, 0, 12),
        MinimumVolumePercent = Math.Clamp(MinimumVolumePercent, 0, 100),
        SyncCode = SyncKeys.FromCode(SyncCode)?.Code ?? "",
    };

    private static AlarmSettings NormalizeAlarm(AlarmSettings? alarm)
    {
        alarm ??= new AlarmSettings();
        return alarm.Deadline is { } deadline && deadline < alarm.RingAt ? alarm with { Deadline = alarm.RingAt } : alarm;
    }

    /// <summary>What you type to stop either alarm. The owner chose "done" (2026-10-31); the screen still asks "Timed in?".</summary>
    public static string ConfirmationPhrase(AlarmKind kind) => "done";
}
