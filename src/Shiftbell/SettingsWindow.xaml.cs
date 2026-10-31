using System.Globalization;
using System.Windows;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>
/// Edits settings in memory; nothing is written until Save. Opening and closing it changes no file.
/// </summary>
internal sealed partial class SettingsWindow : Window
{
    private static readonly DayOfWeek[] WeekOrder =
    [
        DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday,
        DayOfWeek.Friday, DayOfWeek.Saturday, DayOfWeek.Sunday,
    ];

    private readonly Dictionary<DayOfWeek, ToggleButton> _days = [];

    public SettingsWindow(ShiftbellSettings settings, string? registrationProblem)
    {
        InitializeComponent();

        // Fits its content, but never taller than the screen: past that the form scrolls and the buttons stay put.
        MaxHeight = SystemParameters.WorkArea.Height;
        // Sized to its content once; after that the size is the user's, and the form scrolls inside it.
        ContentRendered += (_, _) => SizeToContent = SizeToContent.Manual;
        foreach (var day in WeekOrder)
        {
            var toggle = new ToggleButton { Content = day.ToString()[..3], Style = (Style)Resources["Day"] };
            _days[day] = toggle;
            DaysPanel.Children.Add(toggle);
        }

        Show(settings);
        ShowRegistration(registrationProblem);
    }

    /// <summary>Raised with the new settings when the user saves.</summary>
    public event Action<ShiftbellSettings>? Saved;

    /// <summary>Raised with the alarm the user wants to try, using what is on screen now.</summary>
    public event Action<AlarmKind, ShiftbellSettings>? TestRequested;

    /// <summary>Raised with a new pairing code, or "" to unpair. Applies at once, unlike the rest of the form.</summary>
    public event Action<string>? PairingChanged;

    private string _syncCode = "";

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

    private void Show(ShiftbellSettings s)
    {
        TimeInEnabled.IsChecked = s.TimeIn.Enabled;
        TimeInRing.Text = TimeText.Format(s.TimeIn.RingAt);
        TimeInDeadline.Text = s.TimeIn.Deadline is { } d ? TimeText.Format(d) : "";
        TimeOutEnabled.IsChecked = s.TimeOut.Enabled;
        TimeOutRing.Text = TimeText.Format(s.TimeOut.RingAt);
        foreach (var (day, toggle) in _days)
        {
            toggle.IsChecked = s.WorkDays.Contains(day);
        }

        AttendanceLink.Text = s.AttendanceLink;
        SnoozeMinutes.Text = s.SnoozeMinutes.ToString(CultureInfo.InvariantCulture);
        MaxSnoozes.Text = s.MaxSnoozes.ToString(CultureInfo.InvariantCulture);
        CatchUpHours.Text = s.CatchUpHours.ToString(CultureInfo.InvariantCulture);
        MinimumVolume.Text = s.MinimumVolumePercent.ToString(CultureInfo.InvariantCulture);
        RequireTyping.IsChecked = s.RequireTypedConfirmation;
    }

    /// <summary>Trial runs (SHIFTBELL_HOME set) never register at startup; that is intended, so nothing is shown.</summary>
    public const string RegistrationSkipped = "skipped for this trial run";

    public void ShowRegistration(string? problem)
    {
        StatusBorder.Visibility = problem == RegistrationSkipped ? Visibility.Collapsed : Visibility.Visible;
        StatusBorder.Background = new SolidColorBrush(problem is null ? Color.FromRgb(0x1F, 0x7A, 0x4D) : Color.FromRgb(0x9A, 0x5B, 0x00));
        StatusText.Text = problem is null
            ? "Starts when you sign in, comes back within 15 minutes if it is closed, and wakes the PC from sleep 2 minutes before each alarm."
            : $"Starts when you sign in, but the wake-and-restart task could not be set up: {problem}";
    }

    /// <summary>Reads the form. Returns null and shows why when something is not valid.</summary>
    private ShiftbellSettings? Read()
    {
        var errors = new List<string>();

        TimeOnly Time(string text, string what)
        {
            if (TimeText.TryParse(text, out var time))
            {
                return time;
            }

            errors.Add($"{what}: “{text}” is not a time. Try 7:45 AM or 17:00.");
            return default;
        }

        int Number(string text, string what, int min, int max)
        {
            if (int.TryParse(text.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var value) && value >= min && value <= max)
            {
                return value;
            }

            errors.Add($"{what} must be a whole number from {min} to {max}.");
            return min;
        }

        var inRing = Time(TimeInRing.Text, "Time in, ring at");
        TimeOnly? inDeadline = string.IsNullOrWhiteSpace(TimeInDeadline.Text) ? null : Time(TimeInDeadline.Text, "Time in by");
        var outRing = Time(TimeOutRing.Text, "Time out, ring at");
        if (inDeadline is { } d && d < inRing)
        {
            errors.Add("Time in by must be at or after the ring time.");
        }

        var days = WeekOrder.Where(day => _days[day].IsChecked == true).ToArray();
        if (days.Length == 0)
        {
            errors.Add("Pick at least one work day.");
        }

        var settings = new ShiftbellSettings
        {
            TimeIn = new AlarmSettings { Enabled = TimeInEnabled.IsChecked == true, RingAt = inRing, Deadline = inDeadline },
            TimeOut = new AlarmSettings { Enabled = TimeOutEnabled.IsChecked == true, RingAt = outRing },
            WorkDays = days,
            AttendanceLink = AttendanceLink.Text.Trim(),
            SnoozeMinutes = Number(SnoozeMinutes.Text, "Snooze length", 1, 60),
            MaxSnoozes = Number(MaxSnoozes.Text, "Snoozes allowed", 0, 10),
            CatchUpHours = Number(CatchUpHours.Text, "Missed alarm hours", 0, 12),
            MinimumVolumePercent = Number(MinimumVolume.Text, "Speaker volume", 0, 100),
            RequireTypedConfirmation = RequireTyping.IsChecked == true,
            SyncCode = _syncCode,
        };

        ErrorText.Text = string.Join(Environment.NewLine, errors);
        ErrorText.Visibility = errors.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        return errors.Count > 0 ? null : settings.Normalized();
    }

    private void OnSave(object sender, RoutedEventArgs e)
    {
        if (Read() is { } settings)
        {
            Saved?.Invoke(settings);
            Close();
        }
    }

    private void OnCancel(object sender, RoutedEventArgs e) => Close();

    private void OnTestIn(object sender, RoutedEventArgs e) => Test(AlarmKind.TimeIn);

    private void OnTestOut(object sender, RoutedEventArgs e) => Test(AlarmKind.TimeOut);

    private void Test(AlarmKind kind)
    {
        if (Read() is { } settings)
        {
            TestRequested?.Invoke(kind, settings);
        }
    }
}
