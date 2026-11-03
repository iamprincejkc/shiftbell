using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>
/// The resident process. Every second it asks <see cref="AlarmPlanner"/> whether an alarm is due, from the
/// persisted state and the wall clock, so nothing depends on a timer surviving sleep, a clock change or a restart.
/// </summary>
[System.Diagnostics.CodeAnalysis.SuppressMessage("Design", "CA1001", Justification = "Lives as long as the process; the tray and channel are disposed in OnExit.")]
internal sealed partial class App : Application
{
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromSeconds(1) };
    private CommandChannel? _channel;
    private JsonStore _store = null!;
    private ShiftbellSettings _settings = null!;
    private ShiftbellState _state = null!;
    private Tray? _tray;
    private AlarmSession? _session;
    private SettingsWindow? _settingsWindow;
    private string? _registrationProblem;
    private Native.ExecutionState _executionState;
    private DateOnly _prunedFor;
    private Syncer _syncer = null!;
    private readonly HashSet<string> _seenTests = [];

    /// <summary>When a due alarm started waiting for a last sync read, so a confirm on the phone can still stop it.</summary>
    private DateTime? _ringHeldSince;

    /// <summary>A due alarm waits at most this long for that read. No network must never mean no alarm.</summary>
    private static readonly TimeSpan RingHoldLimit = TimeSpan.FromSeconds(4);

    /// <summary>Trial runs set SHIFTBELL_HOME; those must never point the real startup task at a dev build.</summary>
    private static bool IsTrialRun => !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("SHIFTBELL_HOME"));

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        if (e.Args is ["--render-previews", var folder])
        {
            Previews.Render(folder);
            Shutdown();
            return;
        }

        var command = CommandFor(e.Args);

        if (command == "unregister")
        {
            StartupRegistration.Unregister();
            Shutdown();
            return;
        }

        _channel = CommandChannel.TryBecomePrimary();
        if (_channel is null || command == "exit")
        {
            // "--exit" with nothing running must not start up (and re-register) just to quit again.
            if (_channel is null)
            {
                CommandChannel.Send(command);
            }

            Shutdown();
            return;
        }

        _store = JsonStore.Default();
        var firstRun = !_store.SettingsExist;
        _settings = _store.LoadSettings();
        // Always written back: a 0.3 file is migrated to the alarm list here, once, rather than on every load.
        _store.SaveSettings(_settings);

        _state = _store.LoadState(DateTime.Now);
        _syncer = new Syncer(Dispatcher, () => _state, () => _store.SaveState(_state), () => _settings, ReplaceSettings);
        _syncer.Configure(_settings.SyncCode);
        _syncer.RoundFinished += (alarmsChanged, pulled) =>
        {
            _settingsWindow?.ShowSync(_settings.SyncCode, SyncStatus());
            if (alarmsChanged)
            {
                // An alarm added or edited on the phone: new ring times need new wake-up triggers.
                RegisterStartup();
                _settingsWindow?.ShowAlarms(_settings, _state, DateTime.Now);
            }

            HandleSyncedTests(pulled);
            Tick();
        };
        PublishAlarms();

        _tray = new Tray();
        _tray.SettingsClicked += ShowSettings;
        _tray.DayOffClicked += ToggleDayOff;
        _tray.ExitClicked += ConfirmExit;

        _channel.Listen(line => Dispatcher.BeginInvoke(() => Handle(line)));
        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        SystemEvents.TimeChanged += OnTimeChanged;
        _tick.Tick += (_, _) => Tick();
        _tick.Start();

        RegisterStartup();
        Tick();
        if (firstRun || command == "settings")
        {
            ShowSettings();
        }
        else
        {
            Handle(command);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        SystemEvents.TimeChanged -= OnTimeChanged;
        Native.SetThreadExecutionState(Native.ExecutionState.Continuous);
        _tray?.Dispose();
        _channel?.Dispose();
        base.OnExit(e);
    }

    private static string CommandFor(string[] args) => args.FirstOrDefault()?.ToLowerInvariant() switch
    {
        "--background" => "check",
        "--test-in" => "test-in",
        "--test-out" => "test-out",
        "--unregister" => "unregister",
        "--exit" => "exit",
        _ => "settings",
    };

    private void Handle(string command)
    {
        switch (command)
        {
            case "settings":
                ShowSettings();
                break;
            case "test-in":
                Test(_settings.Find(Alarm.TimeInId) ?? Alarm.TimeIn());
                break;
            case "test-out":
                Test(_settings.Find(Alarm.TimeOutId) ?? Alarm.TimeOut());
                break;
            case "exit":
                Shutdown();
                break;
            default:
                Tick();
                break;
        }
    }

    private void OnPowerModeChanged(object? sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Resume)
        {
            Dispatcher.BeginInvoke(Tick);
        }
    }

    private void OnTimeChanged(object? sender, EventArgs e) => Dispatcher.BeginInvoke(Tick);

    private void Tick()
    {
        var now = DateTime.Now;
        var today = DateOnly.FromDateTime(now);
        if (_prunedFor != today)
        {
            _prunedFor = today;
            _state.Prune(today);
            _store.SaveState(_state);
        }

        // Confirmed, snoozed or made a day off on the phone: stop ringing here too.
        if (_session is { IsTest: false } ringing && !AlarmPlanner.StillDue(_settings, _state, ringing.Alarm, now))
        {
            ringing.EndElsewhere();
            return;
        }

        var due = _session is null ? AlarmPlanner.DueNow(_settings, _state, now) : null;
        if (due is null)
        {
            _ringHeldSince = null;
        }
        else if (!WaitForSync(now))
        {
            _ringHeldSince = null;
            StartSession(due, isTest: false, _settings);
        }

        SyncTick(now);
        UpdateExecutionState(now);
        _tray?.Update(_settings, _state, now, ringing: _session is { IsTest: false });
    }

    /// <summary>
    /// True while a due alarm should hold off for a sync read, so a "timed in" typed on the phone a moment ago
    /// stops it before it rings. Only when the last read is stale, and never longer than <see cref="RingHoldLimit"/>.
    /// </summary>
    private bool WaitForSync(DateTime now)
    {
        if (!_syncer.Enabled || _syncer.LastSuccess is { } last && now - last < TimeSpan.FromSeconds(20))
        {
            return false;
        }

        if (_ringHeldSince is null)
        {
            _ringHeldSince = now;
            _syncer.Kick();
            return true;
        }

        return _syncer.Busy && now - _ringHeldSince < RingHoldLimit;
    }

    /// <summary>Reads from the phone every minute, and every 3 seconds while ringing so a confirm there stops this.</summary>
    private void SyncTick(DateTime now)
    {
        var every = _session is not null ? TimeSpan.FromSeconds(3) : TimeSpan.FromMinutes(1);
        if (_syncer.Enabled && !_syncer.Busy && (_syncer.LastStarted is not { } started || now - started >= every))
        {
            _syncer.Kick();
        }
    }

    private string SyncStatus()
    {
        if (!_syncer.Enabled)
        {
            return "Not paired. Pair your phone so confirming on one stops both.";
        }

        return _syncer.LastSuccess is { } last
            ? $"Paired with your phone. Last synced {AlarmCopy.Time(last)}."
            : "Paired, but the sync server has not answered yet. Alarms still ring on their own.";
    }

    /// <summary>
    /// Keeps idle sleep away from 10 minutes before an alarm until it is confirmed, and the screen on while it
    /// rings. Lid-close and the power button still win; the wake task covers those.
    /// </summary>
    private void UpdateExecutionState(DateTime now)
    {
        var wanted = Native.ExecutionState.Continuous;
        if (_session is not null)
        {
            wanted |= Native.ExecutionState.SystemRequired | Native.ExecutionState.DisplayRequired;
        }
        else if (AlarmPlanner.ShouldStayAwake(_settings, _state, now))
        {
            wanted |= Native.ExecutionState.SystemRequired;
        }

        if (wanted != _executionState)
        {
            _executionState = wanted;
            Native.SetThreadExecutionState(wanted);
        }
    }

    /// <summary>
    /// A test pressed on the phone rings here too; one stopped there stops here. Ids are remembered so the
    /// overlapping reads never ring the same test twice.
    /// </summary>
    private void HandleSyncedTests(IReadOnlyList<SyncEvent> pulled)
    {
        var now = DateTime.Now;
        var ended = pulled.Where(e => e is { Type: "testend", Id.Length: > 0 }).Select(e => e.Id!).ToHashSet();
        if (_session is { IsTest: true, TestId: { } running } && ended.Contains(running))
        {
            _session.EndElsewhere();
        }

        foreach (var e in pulled)
        {
            if (e.IsFreshTestFrom("phone", now) && _seenTests.Add(e.Id!) && !ended.Contains(e.Id!) && _session is null)
            {
                // The same alarm if this PC has it, otherwise a plain stand-in so the test still rings.
                var alarm = e.AlarmId is { } id ? _settings.Find(id) : null;
                Test(alarm ?? new Alarm { Id = "test", Name = "Test alarm", RingAt = TimeOnly.FromDateTime(now) }, e.Id);
            }
        }
    }

    private void StartSession(DueAlarm due, bool isTest, ShiftbellSettings settings, string? testId = null)
    {
        _session = new AlarmSession(due, settings, isTest, () => DateTime.Now) { TestId = testId };
        _session.Ended += OnSessionEnded;
        _session.Start();
        UpdateExecutionState(DateTime.Now);
    }

    private void OnSessionEnded(AlarmSession session, AlarmOutcome outcome)
    {
        _session = null;
        session.Dispose();
        if (session is { IsTest: true, TestId: { } testId } && outcome != AlarmOutcome.Elsewhere)
        {
            _syncer.Send(SyncEvent.TestEnd(testId, DateTime.Now));
        }

        if (!session.IsTest && outcome != AlarmOutcome.Elsewhere)
        {
            var now = DateTime.Now;
            var date = session.Alarm.Date;
            if (outcome == AlarmOutcome.Confirmed)
            {
                _state.Acknowledge(session.Id, date, now);
                _store.SaveState(_state);
                _syncer.Send(SyncEvent.Ack(session.Alarm.Alarm, date, now));
            }
            else
            {
                var until = now.AddMinutes(_settings.SnoozeMinutes);
                _state.Snooze(session.Id, date, until);
                _store.SaveState(_state);
                _syncer.Send(SyncEvent.Snoozed(session.Alarm.Alarm, date, now, until, _state.Day(date).Record(session.Id).Snoozes));
            }
        }

        Tick();
    }

    /// <summary>
    /// Rings a test alarm. Pressed here (no <paramref name="fromPhone"/> id), the phone is told to ring it too;
    /// rung because the phone pressed it, it carries the phone's id so stopping it here stops it there.
    /// </summary>
    private void Test(Alarm alarm, string? fromPhone = null)
    {
        if (_session is not null)
        {
            return;
        }

        var now = DateTime.Now;
        var date = DateOnly.FromDateTime(now);
        var deadline = alarm.Deadline is { } d ? date.ToDateTime(d) : (DateTime?)null;
        var id = fromPhone ?? SyncEvent.NewTestId();
        _seenTests.Add(id);
        if (fromPhone is null)
        {
            _syncer.Send(SyncEvent.Test(alarm, now, id));
        }

        // A test after the alarm's deadline would only ever say "late"; it shows the normal screen instead.
        var testDeadline = deadline is { } due && due > now ? deadline : null;
        StartSession(new DueAlarm(alarm, date, now, testDeadline, _settings.MaxSnoozes), isTest: true, _settings, id);
    }

    private void ToggleDayOff()
    {
        var now = DateTime.Now;
        var today = DateOnly.FromDateTime(now);
        var off = !(_state.Find(today)?.DayOff ?? false);
        _state.SetDayOff(today, off, now);
        _store.SaveState(_state);
        _syncer.Send(SyncEvent.DayOff(today, now, off));
        Tick();
    }

    private void ShowSettings()
    {
        if (_settingsWindow is not null)
        {
            _settingsWindow.Activate();
            return;
        }

        _settingsWindow = new SettingsWindow(_settings, _state, _registrationProblem);
        _settingsWindow.ShowSync(_settings.SyncCode, SyncStatus());
        _settingsWindow.AlarmSaved += SaveAlarm;
        _settingsWindow.GeneralSaved += SaveGeneral;
        _settingsWindow.PairingChanged += SavePairing;
        _settingsWindow.TestRequested += alarm => Test(alarm);
        _settingsWindow.Closed += (_, _) => _settingsWindow = null;
        _settingsWindow.Show();
        _settingsWindow.Activate();
    }

    /// <summary>
    /// Adds, edits, turns on or off, or deletes (a tombstone) one alarm, and sends it to the phone. Moving when it
    /// rings re-arms it, so a time pulled earlier than now does not fire a "late" alarm the moment it is saved.
    /// </summary>
    private void SaveAlarm(Alarm edited)
    {
        var now = DateTime.Now;
        var before = (_settings.Alarms ?? []).FirstOrDefault(a => a.Id == edited.Id);
        var armedAt = before is null || edited.ScheduleDiffers(before) ? now : before.ArmedAt;
        var alarm = (edited with { UpdatedAt = now, ArmedAt = armedAt }).Normalized();
        ReplaceSettings(_settings.With(alarm));
        _syncer.Send(SyncEvent.Changed(alarm));
        _settingsWindow?.ShowAlarms(_settings, _state, now);
        RegisterStartup();
        Tick();
    }

    /// <summary>Snooze, catch-up and volume. These stay per device and are not synced.</summary>
    private void SaveGeneral(ShiftbellSettings general)
    {
        ReplaceSettings(_settings with
        {
            SnoozeMinutes = general.SnoozeMinutes,
            MaxSnoozes = general.MaxSnoozes,
            CatchUpHours = general.CatchUpHours,
            MinimumVolumePercent = general.MinimumVolumePercent,
        });
        Tick();
    }

    private void ReplaceSettings(ShiftbellSettings settings)
    {
        _settings = settings.Normalized();
        _store.SaveSettings(_settings);
    }

    /// <summary>
    /// Sends every alarm definition once after pairing or upgrading, so the other device learns the alarms that
    /// were made before they were linked. The newest edit wins on both sides, so sending more than once is harmless.
    /// </summary>
    private void PublishAlarms()
    {
        if (!_syncer.Enabled || _state.AlarmsPublished)
        {
            return;
        }

        foreach (var alarm in _settings.Alarms ?? [])
        {
            _state.Outbox.Add(SyncEvent.Changed(alarm));
        }

        _state.AlarmsPublished = true;
        _store.SaveState(_state);
        _syncer.Kick();
    }

    /// <summary>Pairing applies at once, not on Save: the phone may already be scanning the code.</summary>
    private void SavePairing(string code)
    {
        _settings = _settings with { SyncCode = code };
        _store.SaveSettings(_settings);
        _state.Outbox.Clear();
        _state.SyncSince = 0;
        _state.AlarmsPublished = false;
        _store.SaveState(_state);
        _syncer.Configure(code);
        _settingsWindow?.ShowSync(code, SyncStatus());
        PublishAlarms();
        _syncer.Kick();
    }

    private void RegisterStartup()
    {
        if (IsTrialRun)
        {
            _registrationProblem = SettingsWindow.RegistrationSkipped;
            return;
        }

        var settings = _settings;
        Task.Run(() =>
        {
            string? problem;
            try
            {
                problem = StartupRegistration.Register(settings);
            }
            catch (Exception ex) when (ex is UnauthorizedAccessException or System.IO.IOException or System.ComponentModel.Win32Exception)
            {
                problem = ex.Message;
            }

            Dispatcher.BeginInvoke(() =>
            {
                _registrationProblem = problem;
                _settingsWindow?.ShowRegistration(problem);
            });
        });
    }

    private void ConfirmExit()
    {
        var answer = MessageBox.Show(
            "Exit Shiftbell?\n\nNo alarm rings until it starts again, which happens at sign-in or within 15 minutes.",
            "Shiftbell",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Warning,
            MessageBoxResult.Cancel);
        if (answer == MessageBoxResult.OK)
        {
            Shutdown();
        }
    }
}
