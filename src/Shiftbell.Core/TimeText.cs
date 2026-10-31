using System.Globalization;

namespace Shiftbell.Core;

/// <summary>Reads the times people actually type: "7:45", "7:45 am", "07:45", "17:00", "5pm", "5 PM".</summary>
public static class TimeText
{
    private static readonly string[] Formats =
    [
        "h:mm tt", "h:mmtt", "hh:mm tt", "hh:mmtt", "h tt", "htt", "hh tt", "hhtt",
        // "%H", not "H": a one-letter format is read as a standard specifier and TryParseExact throws on it.
        "H:mm", "HH:mm", "%H",
    ];

    public static bool TryParse(string? text, out TimeOnly time)
    {
        var cleaned = (text ?? "").Trim().ToUpperInvariant().Replace(".", "", StringComparison.Ordinal);
        cleaned = string.Join(' ', cleaned.Split(' ', StringSplitOptions.RemoveEmptyEntries));
        return TimeOnly.TryParseExact(cleaned, Formats, CultureInfo.InvariantCulture, DateTimeStyles.None, out time);
    }

    public static string Format(TimeOnly time) => AlarmCopy.Time(time);
}
