using System.Windows;
using Shiftbell.Core;
using Forms = System.Windows.Forms;

namespace Shiftbell;

/// <summary>The notification-area icon: what rings next, today's punches, and the few actions that matter.</summary>
internal sealed class Tray : IDisposable
{
    private readonly Forms.NotifyIcon _icon;
    private readonly Forms.ToolStripMenuItem _next = new() { Enabled = false };
    private readonly Forms.ToolStripMenuItem _today = new() { Enabled = false };
    private readonly Forms.ToolStripMenuItem _dayOff = new("Day off today (no alarms)");
    private string _lastText = "";

    public Tray()
    {
        var menu = new Forms.ContextMenuStrip { ShowImageMargin = false };
        _dayOff.Click += (_, _) => DayOffClicked?.Invoke();
        menu.Items.Add(_next);
        menu.Items.Add(_today);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(_dayOff);
        menu.Items.Add("Settings…", null, (_, _) => SettingsClicked?.Invoke());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitClicked?.Invoke());

        using var stream = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/shiftbell.ico")).Stream;
        _icon = new Forms.NotifyIcon
        {
            Icon = new System.Drawing.Icon(stream, Forms.SystemInformation.SmallIconSize),
            ContextMenuStrip = menu,
            Text = "Shiftbell",
            Visible = true,
        };
        _icon.MouseDoubleClick += (_, _) => SettingsClicked?.Invoke();
    }

    public event Action? SettingsClicked;

    public event Action? DayOffClicked;

    public event Action? ExitClicked;

    public void Update(ShiftbellSettings settings, ShiftbellState state, DateTime now, bool ringing)
    {
        var day = state.Find(DateOnly.FromDateTime(now));
        var next = AlarmPlanner.Next(settings, state, now);
        var nextText = ringing ? "Ringing now"
            : next is null ? "No alarm in the next two weeks"
            : $"Next: {next.Alarm.Name} · {When(next.At, now)}";
        var todayText = day is { DayOff: true } ? "Today: day off" : Today(settings, day, now);

        var text = nextText + "|" + todayText + "|" + day?.DayOff;
        if (text == _lastText)
        {
            return;
        }

        _lastText = text;
        _next.Text = nextText;
        _today.Text = todayText;
        _dayOff.Checked = day?.DayOff ?? false;
        _icon.Text = Truncate($"Shiftbell · {nextText}", 127);
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Icon?.Dispose();
        _icon.ContextMenuStrip?.Dispose();
        _icon.Dispose();
    }

    /// <summary>"Today: time in 7:46 AM · 1 of 3 done": the punch time first, since that is what gets asked about.</summary>
    private static string Today(ShiftbellSettings settings, DayLog? day, DateTime now)
    {
        var date = DateOnly.FromDateTime(now);
        var alarms = settings.Active.Where(a => a.Enabled && a.RingsOn(date)).ToList();
        if (alarms.Count == 0)
        {
            return "Today: no alarms";
        }

        var done = alarms.Count(a => day?.Find(a.Id)?.AcknowledgedAt is not null);
        var punch = day?.Find(Alarm.TimeInId)?.AcknowledgedAt is { } timedIn ? $"time in {AlarmCopy.Time(timedIn)} · " : "";
        return $"Today: {punch}{done} of {alarms.Count} done";
    }

    private static string When(DateTime at, DateTime now)
    {
        var days = DateOnly.FromDateTime(at).DayNumber - DateOnly.FromDateTime(now).DayNumber;
        var prefix = days switch
        {
            0 => "today",
            1 => "tomorrow",
            _ => at.ToString("ddd", System.Globalization.CultureInfo.InvariantCulture),
        };
        return $"{prefix} {AlarmCopy.Time(at)}";
    }

    private static string Truncate(string text, int max) => text.Length <= max ? text : text[..max];
}
