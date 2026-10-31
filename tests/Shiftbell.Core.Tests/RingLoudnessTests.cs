using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public sealed class RingLoudnessTests
{
    private static readonly int[] Seconds = [0, 14, 15, 30, 59, 60, 600];

    [Fact]
    public void It_starts_soft()
    {
        Assert.Equal(0, RingLoudness.StepAt(TimeSpan.Zero));
        Assert.True(RingLoudness.Gain(0) <= 0.10);
    }

    [Fact]
    public void It_climbs_while_nobody_answers_and_stops_climbing()
    {
        var gains = Seconds
            .Select(s => RingLoudness.Gain(RingLoudness.StepAt(TimeSpan.FromSeconds(s))))
            .ToArray();

        for (var i = 1; i < gains.Length; i++)
        {
            Assert.True(gains[i] >= gains[i - 1]);
        }

        Assert.True(gains[^1] > gains[0]);
        Assert.Equal(RingLoudness.StepCount - 1, RingLoudness.StepAt(TimeSpan.FromHours(1)));
    }

    [Fact]
    public void It_never_plays_near_full_scale()
    {
        for (var step = 0; step < RingLoudness.StepCount; step++)
        {
            Assert.True(RingLoudness.Gain(step) <= 0.6);
        }
    }
}
