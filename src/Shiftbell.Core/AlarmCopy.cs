using System.Globalization;

namespace Shiftbell.Core;

public enum AlarmTone
{
    TimeIn,
    TimeOut,
    Custom,
    Late,
}

/// <summary>Every word the alarm screen shows, decided in one place so it can be tested. The phone app mirrors it.</summary>
/// <param name="Ask">The question before the phrase: "Timed in?", "Ready?".</param>
/// <param name="OpenLabel">The link button: "Open attendance" for the punches, "Open link" otherwise.</param>
public sealed record AlarmCopy(
    AlarmTone Tone, string Tag, string Headline, string Detail, string Clock, string Phrase, string ConfirmLabel, string Ask, string OpenLabel)
{
    private const string Attendance = "your attendance system";

    public static AlarmCopy For(DueAlarm due, DateTime now)
    {
        var alarm = due.Alarm;
        var phrase = ShiftbellSettings.ConfirmationPhrase;
        var clock = Time(now);
        var late = due.Deadline is { } deadline && now > deadline;

        if (alarm.Role == AlarmRole.Custom)
        {
            var tag = alarm.Name.ToUpper(CultureInfo.CurrentCulture);
            var detail = due.Deadline switch
            {
                { } d when late => $"It was due at {Time(d)}.",
                { } d => $"Due by {Time(d)}.",
                _ => $"Set for {Time(alarm.RingAt)}.",
            };
            return new AlarmCopy(
                late ? AlarmTone.Late : AlarmTone.Custom,
                tag,
                late ? $"{alarm.Name} is late" : alarm.Name,
                detail,
                ClockLine(due, now, clock),
                phrase,
                alarm.Strict ? "Done" : "Dismiss",
                "Ready?",
                "Open link");
        }

        var timeIn = alarm.Role == AlarmRole.TimeIn;
        var punchTag = timeIn ? "TIME IN" : "TIME OUT";
        var confirm = timeIn ? "I’ve timed in" : "I’ve timed out";
        var ask = timeIn ? "Timed in?" : "Timed out?";
        var verb = timeIn ? "Log in to" : "Log out of";

        if (late)
        {
            return new AlarmCopy(
                AlarmTone.Late,
                punchTag,
                timeIn ? "You’re late to time in" : "You’re late to time out",
                $"It was due at {Time(due.Deadline!.Value)}. {verb} {Attendance} now.",
                ClockLine(due, now, clock),
                phrase,
                confirm,
                ask,
                "Open attendance");
        }

        var tone = timeIn ? AlarmTone.TimeIn : AlarmTone.TimeOut;
        var headline = timeIn ? "Time in now" : "Time out now";
        string punchDetail;
        if (due.Deadline is { } due2)
        {
            punchDetail = timeIn ? $"{verb} {Attendance} before {Time(due2)}." : $"{verb} {Attendance} before {Time(due2)}, then you can go.";
        }
        else
        {
            punchDetail = timeIn
                ? $"Your shift starts at {Time(alarm.RingAt)}. {verb} {Attendance}."
                : $"Your shift ended at {Time(alarm.RingAt)}. {verb} {Attendance} before you leave.";
        }

        return new AlarmCopy(tone, punchTag, headline, punchDetail, ClockLine(due, now, clock), phrase, confirm, ask, "Open attendance");
    }

    private static string ClockLine(DueAlarm due, DateTime now, string clock)
    {
        if (due.Deadline is { } deadline)
        {
            return now > deadline ? $"{clock} · {Span(now - deadline)} late" : $"{clock} · {Span(deadline - now)} left";
        }

        var ringing = now - due.ScheduledAt;
        return ringing >= TimeSpan.FromMinutes(1) ? $"{clock} · ringing for {Span(ringing)}" : clock;
    }

    public static string Snooze(ShiftbellSettings settings, DueAlarm alarm) =>
        alarm.SnoozesLeft > 0
            ? $"Snooze {settings.SnoozeMinutes} min · {alarm.SnoozesLeft} left"
            : "No snoozes left";

    /// <summary>Case and surrounding spaces do not matter: " DONE " counts.</summary>
    public static bool Matches(string typed) =>
        string.Equals((typed ?? "").Trim(), ShiftbellSettings.ConfirmationPhrase, StringComparison.OrdinalIgnoreCase);

    public static string Time(DateTime value) => value.ToString("h:mm tt", CultureInfo.InvariantCulture);

    public static string Time(TimeOnly value) => value.ToString("h:mm tt", CultureInfo.InvariantCulture);

    /// <summary>"Mon–Fri", "Every day", "Weekends", "Mon, Wed", or a one-off "Thu Nov 6".</summary>
    public static string Repeat(Alarm alarm)
    {
        if (alarm.Date is { } date)
        {
            return date.ToString("ddd MMM d", CultureInfo.InvariantCulture);
        }

        var days = alarm.Days.ToHashSet();
        if (days.Count == 7)
        {
            return "Every day";
        }

        if (days.SetEquals(Alarm.Weekdays))
        {
            return "Mon–Fri";
        }

        if (days.SetEquals([DayOfWeek.Saturday, DayOfWeek.Sunday]))
        {
            return "Weekends";
        }

        if (days.Count == 0)
        {
            return "Never";
        }

        var week = new[] { DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday, DayOfWeek.Friday, DayOfWeek.Saturday, DayOfWeek.Sunday };
        return string.Join(", ", week.Where(days.Contains).Select(d => d.ToString()[..3]));
    }

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
}
