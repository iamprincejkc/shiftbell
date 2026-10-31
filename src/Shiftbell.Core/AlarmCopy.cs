using System.Globalization;

namespace Shiftbell.Core;

public enum AlarmTone
{
    TimeIn,
    TimeOut,
    Late,
}

/// <summary>Every word the alarm screen shows, decided in one place so it can be tested.</summary>
public sealed record AlarmCopy(AlarmTone Tone, string Tag, string Headline, string Detail, string Clock, string Phrase, string ConfirmLabel, string Ask)
{
    private const string Attendance = "your attendance system";

    public static AlarmCopy For(DueAlarm alarm, ShiftbellSettings settings, DateTime now)
    {
        var timeIn = alarm.Kind == AlarmKind.TimeIn;
        var phrase = ShiftbellSettings.ConfirmationPhrase(alarm.Kind);
        var tag = timeIn ? "TIME IN" : "TIME OUT";
        var confirm = timeIn ? "I’ve timed in" : "I’ve timed out";
        var ask = timeIn ? "Timed in?" : "Timed out?";
        var verb = timeIn ? "Log in to" : "Log out of";
        var clock = Time(now);

        if (alarm.Deadline is { } deadline && now > deadline)
        {
            return new AlarmCopy(
                AlarmTone.Late,
                tag,
                timeIn ? "You’re late to time in" : "You’re late to time out",
                $"It was due at {Time(deadline)}. {verb} {Attendance} now.",
                $"{clock} · {Span(now - deadline)} late",
                phrase,
                confirm,
                ask);
        }

        if (alarm.Deadline is { } due)
        {
            var detail = timeIn ? $"{verb} {Attendance} before {Time(due)}." : $"{verb} {Attendance} before {Time(due)}, then you can go.";
            return new AlarmCopy(
                timeIn ? AlarmTone.TimeIn : AlarmTone.TimeOut,
                tag,
                timeIn ? "Time in now" : "Time out now",
                detail,
                $"{clock} · {Span(due - now)} left",
                phrase,
                confirm,
                ask);
        }

        var ringing = now - alarm.ScheduledAt;
        return new AlarmCopy(
            timeIn ? AlarmTone.TimeIn : AlarmTone.TimeOut,
            tag,
            timeIn ? "Time in now" : "Time out now",
            timeIn
                ? $"Your shift starts at {Time(settings.TimeIn.RingAt)}. {verb} {Attendance}."
                : $"Your shift ended at {Time(settings.TimeOut.RingAt)}. {verb} {Attendance} before you leave.",
            ringing >= TimeSpan.FromMinutes(1) ? $"{clock} · ringing for {Span(ringing)}" : clock,
            phrase,
            confirm,
            ask);
    }

    public static string Snooze(ShiftbellSettings settings, DueAlarm alarm) =>
        alarm.SnoozesLeft > 0
            ? $"Snooze {settings.SnoozeMinutes} min · {alarm.SnoozesLeft} left"
            : "No snoozes left";

    public static bool Matches(string typed, AlarmKind kind) =>
        string.Equals(Collapse(typed), ShiftbellSettings.ConfirmationPhrase(kind), StringComparison.OrdinalIgnoreCase);

    public static string Time(DateTime value) => value.ToString("h:mm tt", CultureInfo.InvariantCulture);

    public static string Time(TimeOnly value) => value.ToString("h:mm tt", CultureInfo.InvariantCulture);

    /// <summary>"under 1 min", "14 min", "1 h 5 min". Rounded up, so "1 min left" never means zero.</summary>
    public static string Span(TimeSpan span)
    {
        var minutes = (int)Math.Ceiling(Math.Max(0, span.TotalMinutes));
        if (minutes < 1)
        {
            return "under 1 min";
        }

        return minutes < 60 ? $"{minutes} min" : minutes % 60 == 0 ? $"{minutes / 60} h" : $"{minutes / 60} h {minutes % 60} min";
    }

    private static string Collapse(string text) => string.Join(' ', (text ?? "").Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
}
