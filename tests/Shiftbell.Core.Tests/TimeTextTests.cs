using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public class TimeTextTests
{
    [Theory]
    [InlineData("7:45", 7, 45)]
    [InlineData("07:45", 7, 45)]
    [InlineData("7:45 AM", 7, 45)]
    [InlineData("7:45am", 7, 45)]
    [InlineData("7:45 a.m.", 7, 45)]
    [InlineData("17:00", 17, 0)]
    [InlineData("5:00 PM", 17, 0)]
    [InlineData("5pm", 17, 0)]
    [InlineData(" 5  PM ", 17, 0)]
    [InlineData("12:30 AM", 0, 30)]
    [InlineData("12 PM", 12, 0)]
    public void Reads_common_ways_of_writing_a_time(string text, int hour, int minute)
    {
        Assert.True(TimeText.TryParse(text, out var time));
        Assert.Equal(new TimeOnly(hour, minute), time);
    }

    [Theory]
    [InlineData("")]
    [InlineData("soon")]
    [InlineData("25:00")]
    [InlineData("7:75")]
    [InlineData(null)]
    public void Rejects_what_is_not_a_time(string? text)
    {
        Assert.False(TimeText.TryParse(text, out _));
    }

    [Fact]
    public void Formats_as_twelve_hour_clock()
    {
        Assert.Equal("5:00 PM", TimeText.Format(new TimeOnly(17, 0)));
    }
}
