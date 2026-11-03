using System.Windows;
using System.Windows.Controls.Primitives;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>Adds or changes one alarm. Nothing is saved until Save; Cancel and the close box leave it as it was.</summary>
internal sealed partial class AlarmEditorWindow : Window
{
    private static readonly DayOfWeek[] WeekOrder =
    [
        DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday,
        DayOfWeek.Friday, DayOfWeek.Saturday, DayOfWeek.Sunday,
    ];

    private readonly Alarm _original;
    private readonly Dictionary<DayOfWeek, ToggleButton> _days = [];

    public AlarmEditorWindow(Alarm alarm, bool isNew)
    {
        InitializeComponent();
        _original = alarm;
        Heading.Text = isNew ? "New alarm" : "Edit alarm";
        Title = isNew ? "New alarm" : alarm.Name;

        foreach (var day in WeekOrder)
        {
            var toggle = new ToggleButton { Content = day.ToString()[..2], Style = (Style)Resources["Day"], IsChecked = alarm.Days.Contains(day) };
            _days[day] = toggle;
            DaysPanel.Children.Add(toggle);
        }

        NameBox.Text = alarm.Name;
        TimeBox.Text = TimeText.Format(alarm.RingAt);
        DeadlineBox.Text = alarm.Deadline is { } d ? TimeText.Format(d) : "";
        RepeatOnce.IsChecked = alarm.Date is not null;
        RepeatDays.IsChecked = alarm.Date is null;
        OnceDate.SelectedDate = (alarm.Date ?? DateOnly.FromDateTime(DateTime.Today)).ToDateTime(TimeOnly.MinValue);
        StopTyping.IsChecked = alarm.Strict;
        StopDismiss.IsChecked = !alarm.Strict;
        LinkBox.Text = alarm.Link;

        // The two punches can be turned off but not deleted: they are what the app is for.
        DeleteButton.Visibility = !isNew && alarm.Role == AlarmRole.Custom ? Visibility.Visible : Visibility.Collapsed;
        Loaded += (_, _) => NameBox.Focus();
    }

    /// <summary>The saved alarm, or a tombstone after Delete.</summary>
    public Alarm? Result { get; private set; }

    public event Action<Alarm>? TestRequested;

    private void OnRepeatChanged(object sender, RoutedEventArgs e)
    {
        if (DaysPanel is null)
        {
            return;
        }

        var once = RepeatOnce.IsChecked == true;
        DaysPanel.Visibility = once ? Visibility.Collapsed : Visibility.Visible;
        OnceDate.Visibility = once ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>Reads the form. Returns null and shows why when something is not valid.</summary>
    private Alarm? Read()
    {
        var errors = new List<string>();

        if (!TimeText.TryParse(TimeBox.Text, out var ringAt))
        {
            errors.Add($"Time: “{TimeBox.Text}” is not a time. Try 7:45 AM or 17:00.");
        }

        TimeOnly? deadline = null;
        if (!string.IsNullOrWhiteSpace(DeadlineBox.Text))
        {
            if (TimeText.TryParse(DeadlineBox.Text, out var d))
            {
                deadline = d;
                if (d < ringAt)
                {
                    errors.Add("Due by must be at or after the alarm time.");
                }
            }
            else
            {
                errors.Add($"Due by: “{DeadlineBox.Text}” is not a time.");
            }
        }

        var once = RepeatOnce.IsChecked == true;
        DateOnly? date = once && OnceDate.SelectedDate is { } picked ? DateOnly.FromDateTime(picked) : null;
        var days = WeekOrder.Where(day => _days[day].IsChecked == true).ToArray();
        if (once && date is null)
        {
            errors.Add("Pick the date it rings on.");
        }
        else if (!once && days.Length == 0)
        {
            errors.Add("Pick at least one day, or choose Once.");
        }

        var link = LinkBox.Text.Trim();
        if (link.Length > 0 && !Uri.TryCreate(link, UriKind.Absolute, out _))
        {
            errors.Add("Link must be a full address, like https://meet.example.com/standup.");
        }

        ErrorText.Text = string.Join(Environment.NewLine, errors);
        ErrorText.Visibility = errors.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        if (errors.Count > 0)
        {
            return null;
        }

        return (_original with
        {
            Name = NameBox.Text,
            RingAt = ringAt,
            Deadline = deadline,
            Days = once ? _original.Days : days,
            Date = date,
            Strict = StopTyping.IsChecked == true,
            Link = link,
        }).Normalized();
    }

    private void OnSave(object sender, RoutedEventArgs e)
    {
        if (Read() is { } alarm)
        {
            Result = alarm;
            DialogResult = true;
        }
    }

    private void OnTest(object sender, RoutedEventArgs e)
    {
        if (Read() is { } alarm)
        {
            TestRequested?.Invoke(alarm);
        }
    }

    private void OnDelete(object sender, RoutedEventArgs e)
    {
        var answer = MessageBox.Show(this, $"Delete “{_original.Name}”?\n\nIt is removed from your linked devices too.", "Shiftbell",
            MessageBoxButton.OKCancel, MessageBoxImage.Question, MessageBoxResult.Cancel);
        if (answer == MessageBoxResult.OK)
        {
            Result = _original with { Deleted = true };
            DialogResult = true;
        }
    }
}
