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
        menu.Items.Add("Test time-in alarm", null, (_, _) => TestClicked?.Invoke(AlarmKind.TimeIn));
        menu.Items.Add("Test time-out alarm", null, (_, _) => TestClicked?.Invoke(AlarmKind.TimeOut));
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

    public event Action<AlarmKind>? TestClicked;

    public event Action? DayOffClicked;

    public event Action? ExitClicked;

    public void Update(ShiftbellSettings settings, ShiftbellState state, DateTime now, bool ringing)
    {
        var day = state.Find(DateOnly.FromDateTime(now));
        var next = AlarmPlanner.Next(settings, state, now);
        var nextText = ringing ? "Ringing now"
            : next is null ? "No alarm in the next two weeks"
            : $"Next: {Name(next.Kind)} · {When(next.At, now)}";
        var todayText = day is { DayOff: true }
            ? "Today: day off"
            : $"Today: in {Punch(day?.TimeIn)} · out {Punch(day?.TimeOut)}";

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

    private static string Name(AlarmKind kind) => kind == AlarmKind.TimeIn ? "time in" : "time out";

    private static string Punch(AlarmRecord? record) => record?.AcknowledgedAt is { } at ? AlarmCopy.Time(at) : "–";

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
