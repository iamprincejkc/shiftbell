using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public class AlarmCopyTests
{
    private static readonly DateOnly Day = new(2026, 10, 27);
    private static readonly ShiftbellSettings Defaults = new ShiftbellSettings().Normalized();

    private static DateTime At(int hour, int minute, int second = 0) => Day.ToDateTime(new TimeOnly(hour, minute, second));

    private static DueAlarm TimeIn => new(Alarm.TimeIn(), Day, At(7, 45), At(8, 0), 2);

    private static DueAlarm TimeOut => new(Alarm.TimeOut(), Day, At(17, 0), null, 2);

    [Fact]
    public void Time_in_before_the_deadline_says_what_to_do_and_how_long_is_left()
    {
        var copy = AlarmCopy.For(TimeIn, At(7, 45));

        Assert.Equal(AlarmTone.TimeIn, copy.Tone);
        Assert.Equal("Time in now", copy.Headline);
        Assert.Equal("Log in to your attendance system before 8:00 AM.", copy.Detail);
        Assert.Equal("7:45 AM · 15 min left", copy.Clock);
        Assert.Equal("done", copy.Phrase);
        Assert.Equal("Timed in?", copy.Ask);
        Assert.Equal("I’ve timed in", copy.ConfirmLabel);
    }

    [Fact]
    public void Time_in_after_the_deadline_turns_late()
    {
        var copy = AlarmCopy.For(TimeIn, At(9, 12));

        Assert.Equal(AlarmTone.Late, copy.Tone);
        Assert.Equal("You’re late to time in", copy.Headline);
        Assert.Equal("It was due at 8:00 AM. Log in to your attendance system now.", copy.Detail);
        Assert.Equal("9:12 AM · 1 h 12 min late", copy.Clock);
    }

    [Fact]
    public void Time_out_without_a_deadline_counts_how_long_it_has_been_ringing()
    {
        Assert.Equal("5:00 PM", AlarmCopy.For(TimeOut, At(17, 0, 30)).Clock);

        var copy = AlarmCopy.For(TimeOut, At(17, 3));
        Assert.Equal(AlarmTone.TimeOut, copy.Tone);
        Assert.Equal("Time out now", copy.Headline);
        Assert.Equal("Your shift ended at 5:00 PM. Log out of your attendance system before you leave.", copy.Detail);
        Assert.Equal("5:03 PM · ringing for 3 min", copy.Clock);
        Assert.Equal("done", copy.Phrase);
        Assert.Equal("Timed out?", copy.Ask);
    }

    [Fact]
    public void An_added_alarm_speaks_its_own_name_and_its_stopping_style()
    {
        var standup = new Alarm { Id = "standup0", Name = "Standup", RingAt = new TimeOnly(10, 0), Strict = false };

        var copy = AlarmCopy.For(new DueAlarm(standup, Day, At(10, 0), null, 2), At(10, 0));

        Assert.Equal(AlarmTone.Custom, copy.Tone);
        Assert.Equal(("STANDUP", "Standup", "Set for 10:00 AM."), (copy.Tag, copy.Headline, copy.Detail));
        Assert.Equal(("Dismiss", "Open link", "Ready?"), (copy.ConfirmLabel, copy.OpenLabel, copy.Ask));
    }

    [Fact]
    public void An_added_alarm_with_a_deadline_counts_down_then_turns_late()
    {
        var report = new Alarm { Id = "report00", Name = "Send report", RingAt = new TimeOnly(15, 0), Deadline = new TimeOnly(15, 30) };
        var due = new DueAlarm(report, Day, At(15, 0), At(15, 30), 2);

        Assert.Equal(("Due by 3:30 PM.", "3:10 PM · 20 min left", "Done"), (AlarmCopy.For(due, At(15, 10)).Detail, AlarmCopy.For(due, At(15, 10)).Clock, AlarmCopy.For(due, At(15, 10)).ConfirmLabel));
        var late = AlarmCopy.For(due, At(15, 40));
        Assert.Equal((AlarmTone.Late, "Send report is late", "It was due at 3:30 PM."), (late.Tone, late.Headline, late.Detail));
    }

    [Theory]
    [InlineData(new[] { DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday, DayOfWeek.Friday }, "Mon–Fri")]
    [InlineData(new[] { DayOfWeek.Saturday, DayOfWeek.Sunday }, "Weekends")]
    [InlineData(new[] { DayOfWeek.Wednesday, DayOfWeek.Monday }, "Mon, Wed")]
    [InlineData(new DayOfWeek[0], "Never")]
    public void Repeat_reads_like_a_clock_app(DayOfWeek[] days, string expected)
    {
        Assert.Equal(expected, AlarmCopy.Repeat(new Alarm { Days = days }));
        Assert.Equal("Fri Nov 6", AlarmCopy.Repeat(new Alarm { Days = days, Date = new DateOnly(2026, 11, 6) }));
    }

    [Theory]
    [InlineData("done", true)]
    [InlineData("  DONE ", true)]
    [InlineData("don", false)]
    [InlineData("timed in", false)]
    [InlineData("", false)]
    public void The_phrase_must_be_typed_but_spacing_and_case_do_not_matter(string typed, bool matches)
    {
        Assert.Equal(matches, AlarmCopy.Matches(typed));
    }

    [Fact]
    public void Snooze_label_counts_down_to_none()
    {
        Assert.Equal("Snooze 5 min · 2 left", AlarmCopy.Snooze(Defaults, TimeIn));
        Assert.Equal("No snoozes left", AlarmCopy.Snooze(Defaults, TimeIn with { SnoozesLeft = 0 }));
    }

    [Theory]
    [InlineData(0, "under 1 min")]
    [InlineData(1, "1 min")]
    [InlineData(59, "59 min")]
    [InlineData(60, "1 h")]
    [InlineData(65, "1 h 5 min")]
    public void Spans_read_naturally(int minutes, string expected)
    {
        Assert.Equal(expected, AlarmCopy.Span(TimeSpan.FromMinutes(minutes)));
    }

    [Fact]
    public void Spans_round_up_so_one_minute_left_is_never_zero()
    {
        Assert.Equal("1 min", AlarmCopy.Span(TimeSpan.FromSeconds(5)));
    }
}
