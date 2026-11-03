using System.Diagnostics;
using System.Windows.Threading;
using Shiftbell.Core;
using Screen = System.Windows.Forms.Screen;

namespace Shiftbell;

internal enum AlarmOutcome
{
    Confirmed,
    Snoozed,

    /// <summary>Confirmed, snoozed or made a day off on the phone; already recorded by sync.</summary>
    Elsewhere,
}

/// <summary>
/// One ringing alarm: a window on every monitor, the looping tone and the volume floor.
/// It ends only through <see cref="Confirm"/> or <see cref="Snooze"/>.
/// </summary>
internal sealed class AlarmSession : IDisposable
{
    /// <summary>How long "Let me do it now" keeps the alarm docked before it takes the screen again.</summary>
    public static readonly TimeSpan StepAsideFor = TimeSpan.FromMinutes(3);

    private readonly ShiftbellSettings _settings;
    private readonly Func<DateTime> _now;
    private readonly List<AlarmWindow> _windows = [];
    private readonly AlarmSound _sound = new();
    private readonly SpeakerVolume _volume = new();
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromSeconds(1) };
    private DateTime? _dockedUntil;
    private int _ticks;
    private bool _ended;

    public AlarmSession(DueAlarm alarm, ShiftbellSettings settings, bool isTest, Func<DateTime> now)
    {
        Alarm = alarm;
        _settings = settings;
        IsTest = isTest;
        _now = now;
        _tick.Tick += (_, _) => OnTick();
    }

    public event Action<AlarmSession, AlarmOutcome>? Ended;

    public DueAlarm Alarm { get; }

    public string Id => Alarm.Id;

    public bool IsTest { get; }

    /// <summary>For a test alarm: its sync id, shared with the phone so stopping it on either stops both.</summary>
    public string? TestId { get; init; }

    public bool RequireTyping => Alarm.Alarm.Strict;

    private AlarmWindow Primary => _windows.First(w => w.IsPrimary);

    public void Start()
    {
        var screens = Screen.AllScreens;
        foreach (var screen in screens)
        {
            var window = new AlarmWindow(this, primary: screen.Primary || screens.Length == 1);
            _windows.Add(window);
            window.Show();
            window.CoverFull(screen.Bounds);
        }

        Refresh();
        _volume.Raise(_settings.MinimumVolumePercent);
        _sound.Start();
        Native.ForceForeground(Primary.Handle);
        _tick.Start();
    }

    public void Confirm() => End(AlarmOutcome.Confirmed);

    public void EndElsewhere() => End(AlarmOutcome.Elsewhere);

    /// <summary>Releases the tone. <see cref="End"/> already does this; it is here for the analyzers and for safety.</summary>
    public void Dispose() => _sound.Dispose();

    public void Snooze()
    {
        if (Alarm.SnoozesLeft > 0)
        {
            End(AlarmOutcome.Snoozed);
        }
    }

    public void StepAside()
    {
        if (_ended)
        {
            return;
        }

        _dockedUntil = _now() + StepAsideFor;
        _sound.Stop();
        foreach (var window in _windows.Where(w => !w.IsPrimary))
        {
            window.Hide();
        }

        Primary.Dock(ScreenOf(Primary).WorkingArea);
        Refresh();
    }

    public void OpenAttendance()
    {
        var link = Alarm.Alarm.Link;
        if (link.Length == 0)
        {
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo(link) { UseShellExecute = true })?.Dispose();
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            // A broken link must not trap the user under a full-screen alarm; step aside so they can punch by hand.
        }

        StepAside();
    }

    private void TakeScreenBack()
    {
        _dockedUntil = null;
        foreach (var window in _windows)
        {
            window.Show();
            window.CoverFull(ScreenOf(window).Bounds);
        }

        _sound.Start();
        Native.ForceForeground(Primary.Handle);
        Refresh();
    }

    private void OnTick()
    {
        if (_dockedUntil is { } until && _now() >= until)
        {
            TakeScreenBack();
        }

        Refresh();
        _sound.Tick();
        _ticks++;
        foreach (var window in _windows.Where(w => w.IsVisible))
        {
            window.KeepOnTop();
        }

        // Full screen means full attention: take focus back every few seconds if something grabbed it.
        if (_dockedUntil is null && _ticks % 3 == 0)
        {
            Native.ForceForeground(Primary.Handle);
        }
    }

    private void Refresh()
    {
        var now = _now();
        var copy = AlarmCopy.For(Alarm, now);
        var dock = _dockedUntil is { } until ? $"· full screen again in {Countdown(until - now)}" : "";
        foreach (var window in _windows)
        {
            window.Apply(copy, AlarmCopy.Snooze(_settings, Alarm), Alarm.SnoozesLeft > 0, Alarm.Alarm.Link.Length > 0, RequireTyping, dock);
        }
    }

    private void End(AlarmOutcome outcome)
    {
        if (_ended)
        {
            return;
        }

        _ended = true;
        _tick.Stop();
        _sound.Dispose();
        _volume.Restore();
        foreach (var window in _windows)
        {
            window.CloseForGood();
        }

        Ended?.Invoke(this, outcome);
    }

    private static Screen ScreenOf(AlarmWindow window) => Screen.FromHandle(window.Handle);

    private static string Countdown(TimeSpan left) =>
        left <= TimeSpan.Zero ? "0:00" : $"{(int)left.TotalMinutes}:{left.Seconds:00}";
}
