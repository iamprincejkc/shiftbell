using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>
/// Alarms, General and Devices. Each change is saved as it is made (an alarm when its editor is saved, a general
/// value when its box loses focus), so there is no Save/Cancel bar and closing the window never loses anything.
/// </summary>
internal sealed partial class SettingsWindow : Window
{
    /// <summary>Trial runs (SHIFTBELL_HOME set) never register at startup; that is intended, so nothing is shown.</summary>
    public const string RegistrationSkipped = "skipped for this trial run";

    private ShiftbellSettings _settings;
    private string _syncCode = "";

    public SettingsWindow(ShiftbellSettings settings, ShiftbellState state, string? registrationProblem)
    {
        InitializeComponent();
        _settings = settings;
        ShowAlarms(settings, state, DateTime.Now);
        ShowGeneral(settings);
        ShowRegistration(registrationProblem);
    }

    /// <summary>An alarm added, edited, switched on or off, or deleted (as a tombstone).</summary>
    public event Action<Alarm>? AlarmSaved;

    /// <summary>Snooze, catch-up and volume, carried on a settings object.</summary>
    public event Action<ShiftbellSettings>? GeneralSaved;

    /// <summary>Raised with the alarm to ring now as a test, exactly as it is in its editor.</summary>
    public event Action<Alarm>? TestRequested;

    /// <summary>Raised with a new pairing code, or "" to unpair.</summary>
    public event Action<string>? PairingChanged;

    private void OnNav(object sender, RoutedEventArgs e)
    {
        if (AlarmsPage is null)
        {
            return; // Raised once during InitializeComponent, before the pages exist.
        }

        AlarmsPage.Visibility = NavAlarms.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        GeneralPage.Visibility = NavGeneral.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        DevicesPage.Visibility = NavDevices.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
    }

    // ----- Alarms -----

    public void ShowAlarms(ShiftbellSettings settings, ShiftbellState state, DateTime now)
    {
        _settings = settings;
        var next = AlarmPlanner.Next(settings, state, now);
        NextTime.Text = next is null ? "None" : AlarmCopy.Time(next.At);
        NextDetail.Text = next is null
            ? "No alarm in the next two weeks."
            : $"{next.Alarm.Name}, {Day(next.At, now)}" + (next.Alarm.Deadline is { } due ? $"\ndue by {AlarmCopy.Time(due)}" : "");

        AlarmList.Children.Clear();
        foreach (var alarm in settings.Active)
        {
            AlarmList.Children.Add(Row(alarm));
        }
    }

    private Border Row(Alarm alarm)
    {
        var bar = new Border { Width = 4, Height = 40, CornerRadius = new CornerRadius(0, 3, 3, 0), Background = new SolidColorBrush(Accent(alarm.Role)), VerticalAlignment = VerticalAlignment.Center };

        var time = new TextBlock { FontSize = 20, FontWeight = FontWeights.SemiBold };
        time.Inlines.Add(new System.Windows.Documents.Run(AlarmCopy.Time(alarm.RingAt)));
        if (alarm.Deadline is { } due)
        {
            time.Inlines.Add(new System.Windows.Documents.Run($"   due {AlarmCopy.Time(due)}") { FontSize = 12, FontWeight = FontWeights.Normal, Foreground = (Brush)FindResource("TextFillColorSecondaryBrush") });
        }

        var text = new StackPanel { Margin = new Thickness(14, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(time);
        text.Children.Add(new TextBlock { Text = alarm.Name });
        text.Children.Add(new TextBlock { Text = Summary(alarm), FontSize = 12, Foreground = (Brush)FindResource("TextFillColorSecondaryBrush"), TextTrimming = TextTrimming.CharacterEllipsis });

        var toggle = new ToggleButton { Style = (Style)Resources["Switch"], IsChecked = alarm.Enabled, VerticalAlignment = VerticalAlignment.Center, ToolTip = alarm.Enabled ? "Turn off" : "Turn on" };
        toggle.Click += (_, e) =>
        {
            e.Handled = true;
            AlarmSaved?.Invoke(alarm with { Enabled = toggle.IsChecked == true });
        };

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(bar);
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        Grid.SetColumn(toggle, 2);
        grid.Children.Add(toggle);

        var row = new Border { Style = (Style)Resources["Card"], Padding = new Thickness(0, 12, 16, 12), Child = grid, Cursor = Cursors.Hand, Opacity = alarm.Enabled ? 1 : 0.55 };
        row.MouseLeftButtonUp += (_, _) => Edit(alarm, isNew: false);
        AutomationPropertiesHelper.Name(row, $"{alarm.Name}, {AlarmCopy.Time(alarm.RingAt)}");
        return row;
    }

    /// <summary>"Mon–Fri · type done · Open attendance".</summary>
    private static string Summary(Alarm alarm)
    {
        var parts = new List<string> { AlarmCopy.Repeat(alarm), alarm.Strict ? "type done" : "Dismiss" };
        if (alarm.Link.Length > 0)
        {
            parts.Add(alarm.Role == AlarmRole.Custom ? "link" : "attendance link");
        }

        return string.Join(" · ", parts);
    }

    private static Color Accent(AlarmRole role) => role switch
    {
        AlarmRole.TimeIn => Color.FromRgb(0xF5, 0xA6, 0x23),
        AlarmRole.TimeOut => Color.FromRgb(0x6C, 0xA8, 0xFF),
        _ => Color.FromRgb(0xB1, 0x8C, 0xFF),
    };

    private static string Day(DateTime at, DateTime now) =>
        (DateOnly.FromDateTime(at).DayNumber - DateOnly.FromDateTime(now).DayNumber) switch
        {
            0 => "today",
            1 => "tomorrow",
            _ => at.ToString("dddd", CultureInfo.InvariantCulture),
        };

    private void OnAdd(object sender, RoutedEventArgs e)
    {
        var now = DateTime.Now;
        var fresh = new Alarm { Id = Alarm.NewId(), Name = "", RingAt = new TimeOnly(now.Hour, 0).AddHours(1), Days = Alarm.Weekdays, Strict = false };
        Edit(fresh, isNew: true);
    }

    private void Edit(Alarm alarm, bool isNew)
    {
        var editor = new AlarmEditorWindow(alarm, isNew) { Owner = this };
        editor.TestRequested += a => TestRequested?.Invoke(a);
        if (editor.ShowDialog() == true && editor.Result is { } result)
        {
            AlarmSaved?.Invoke(result);
        }
    }

    // ----- General -----

    private void ShowGeneral(ShiftbellSettings s)
    {
        SnoozeMinutes.Text = s.SnoozeMinutes.ToString(CultureInfo.InvariantCulture);
        MaxSnoozes.Text = s.MaxSnoozes.ToString(CultureInfo.InvariantCulture);
        CatchUpHours.Text = s.CatchUpHours.ToString(CultureInfo.InvariantCulture);
        MinimumVolume.Text = s.MinimumVolumePercent.ToString(CultureInfo.InvariantCulture);
    }

    private void OnGeneralChanged(object sender, RoutedEventArgs e)
    {
        var errors = new List<string>();

        int Number(TextBox box, string what, int min, int max)
        {
            if (int.TryParse(box.Text.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var value) && value >= min && value <= max)
            {
                return value;
            }

            errors.Add($"{what} must be a whole number from {min} to {max}.");
            return -1;
        }

        var general = _settings with
        {
            SnoozeMinutes = Number(SnoozeMinutes, "Snooze length", 1, 60),
            MaxSnoozes = Number(MaxSnoozes, "Snoozes", 0, 10),
            CatchUpHours = Number(CatchUpHours, "Missed alarms", 0, 12),
            MinimumVolumePercent = Number(MinimumVolume, "Lowest speaker volume", 0, 100),
        };

        GeneralError.Text = string.Join(Environment.NewLine, errors);
        GeneralError.Visibility = errors.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        if (errors.Count == 0 && general != _settings)
        {
            _settings = general;
            GeneralSaved?.Invoke(general);
        }
    }

    public void ShowRegistration(string? problem)
    {
        StatusBorder.Visibility = problem == RegistrationSkipped ? Visibility.Collapsed : Visibility.Visible;
        StatusIcon.Text = problem is null ? "" : "";
        StatusIcon.Foreground = new SolidColorBrush(problem is null ? Color.FromRgb(0x3F, 0xB2, 0x7F) : Color.FromRgb(0xF5, 0xA6, 0x23));
        StatusText.Text = problem is null
            ? "Starts when you sign in, comes back within 15 minutes if it is closed, and wakes the PC from sleep 2 minutes before each alarm."
            : $"Starts when you sign in, but the wake-and-restart task could not be set up: {problem}";
    }

    // ----- Devices -----

    /// <summary>Shows the pairing state. The QR code appears only after Pair is pressed, never on open.</summary>
    public void ShowSync(string code, string status)
    {
        _syncCode = code;
        SyncStatusText.Text = status;
        var paired = code.Length > 0;
        PairButton.Content = paired ? "Show pairing code" : "Pair a phone";
        UnpairButton.Visibility = paired ? Visibility.Visible : Visibility.Collapsed;
        if (!paired)
        {
            PairPanel.Visibility = Visibility.Collapsed;
        }
        else if (PairPanel.Visibility == Visibility.Visible)
        {
            ShowCode(code);
        }
    }

    private void ShowCode(string code)
    {
        PairCode.Text = code;
        PairQr.Source = PairingQr.Render(code);
        PairPanel.Visibility = Visibility.Visible;
        Dispatcher.BeginInvoke(() => PairPanel.BringIntoView(), System.Windows.Threading.DispatcherPriority.Loaded);
    }

    private void OnPair(object sender, RoutedEventArgs e)
    {
        if (_syncCode.Length == 0)
        {
            PairingChanged?.Invoke(SyncKeys.NewCode());
        }

        ShowCode(_syncCode);
    }

    private void OnUnpair(object sender, RoutedEventArgs e)
    {
        var answer = MessageBox.Show(
            this,
            "Unpair your phone?\n\nThe PC and the phone will ring and be confirmed separately again. To pair later you scan a new code.",
            "Shiftbell",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Question,
            MessageBoxResult.Cancel);
        if (answer == MessageBoxResult.OK)
        {
            PairingChanged?.Invoke("");
        }
    }

    private void OnCopyCode(object sender, RoutedEventArgs e) => Clipboard.SetText(PairCode.Text);
}

/// <summary>Names a code-built element for screen readers and UI Automation.</summary>
internal static class AutomationPropertiesHelper
{
    public static void Name(DependencyObject element, string name) => System.Windows.Automation.AutomationProperties.SetName(element, name);
}
