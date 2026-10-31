namespace Shiftbell.Core;

/// <summary>
/// How loud the tone is, as a share of full scale, for how long it has been ringing.
/// It starts soft so it never blasts the owner's ears, then climbs so an empty room still hears it.
/// </summary>
public static class RingLoudness
{
    private static readonly (TimeSpan After, double Gain)[] Steps =
    [
        (TimeSpan.Zero, 0.10),
        (TimeSpan.FromSeconds(15), 0.20),
        (TimeSpan.FromSeconds(30), 0.35),
        (TimeSpan.FromSeconds(60), 0.55),
    ];

    public static int StepCount => Steps.Length;

    public static double Gain(int step) => Steps[Math.Clamp(step, 0, Steps.Length - 1)].Gain;

    /// <summary>The step that applies after ringing for <paramref name="ringing"/>.</summary>
    public static int StepAt(TimeSpan ringing)
    {
        var step = 0;
        for (var i = 1; i < Steps.Length; i++)
        {
            if (ringing >= Steps[i].After)
            {
                step = i;
            }
        }

        return step;
    }
}
