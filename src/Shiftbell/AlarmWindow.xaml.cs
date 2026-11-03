using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>
/// One monitor's alarm surface. The window cannot be closed by the user (Alt+F4 and WM_CLOSE are refused);
/// only its <see cref="AlarmSession"/> closes it, after a confirmation or a snooze.
/// </summary>
internal sealed partial class AlarmWindow : Window
{
    private readonly AlarmSession _session;
    private bool _allowClose;

    public AlarmWindow(AlarmSession session, bool primary)
    {
        InitializeComponent();
        _session = session;
        IsPrimary = primary;

        Dither.Background = NoiseBrush.Value;
        Controls.Visibility = primary ? Visibility.Visible : Visibility.Collapsed;
        SecondaryNote.Visibility = primary ? Visibility.Collapsed : Visibility.Visible;
        Loaded += (_, _) =>
        {
            ((Storyboard)Resources["Pulse"]).Begin(this, true);
            if (IsPrimary)
            {
                FocusConfirm();
            }
        };
    }

    public bool IsPrimary { get; }

    public IntPtr Handle => new WindowInteropHelper(this).EnsureHandle();

    public bool IsDocked => DockLayout.Visibility == Visibility.Visible;

    public void Apply(AlarmCopy copy, string snooze, bool canSnooze, bool hasAttendanceLink, bool requireTyping, string dockCountdown)
    {
        var (accent, glow) = copy.Tone switch
        {
            AlarmTone.Late => (Color.FromRgb(0xFF, 0x5A, 0x5F), Color.FromRgb(0x33, 0x12, 0x15)),
            AlarmTone.TimeOut => (Color.FromRgb(0x6C, 0xA8, 0xFF), Color.FromRgb(0x10, 0x1C, 0x33)),
            AlarmTone.Custom => (Color.FromRgb(0xB1, 0x8C, 0xFF), Color.FromRgb(0x1E, 0x15, 0x33)),
            _ => (Color.FromRgb(0xF5, 0xA6, 0x23), Color.FromRgb(0x2A, 0x21, 0x10)),
        };
        SetBrush("Accent", accent);
        SetBrush("AccentInk", copy.Tone == AlarmTone.TimeIn ? Color.FromRgb(0x1A, 0x13, 0x06) : Color.FromRgb(0x08, 0x0B, 0x12));
        GlowStop.Color = glow;

        KindTag.Text = copy.Tag;
        DockTag.Text = copy.Tag;
        Headline.Text = copy.Headline;
        Detail.Text = copy.Detail;
        Clock.Text = copy.Clock;
        AskRun.Text = $"{copy.Ask} Type ";
        PhraseRun.Text = copy.Phrase;
        ConfirmLabel.Text = copy.ConfirmLabel;
        DockConfirmLabel.Text = copy.ConfirmLabel;
        DockTitle.Text = requireTyping ? $"{copy.Ask} Type “{copy.Phrase}”" : $"{copy.Ask} Confirm below";
        DockClock.Text = dockCountdown;

        SnoozeLabel.Text = snooze;
        SnoozeButton.IsEnabled = canSnooze;
        OpenAttendanceButton.Visibility = hasAttendanceLink ? Visibility.Visible : Visibility.Collapsed;
        OpenLabel.Text = copy.OpenLabel;
        // One primary button per screen: Open attendance if there is a link, else Confirm if no typing is
        // needed (it is enabled from the start), else "Let me do it now" while Confirm waits for the phrase.
        StepAsideButton.Style = (Style)Resources[!hasAttendanceLink && requireTyping ? "BigButton" : "GhostButton"];
        // With a link and no typing, Dismiss is a way out, not the main action: the link stays the one filled button.
        ConfirmButton.Style = (Style)Resources[hasAttendanceLink && !requireTyping ? "GhostButton" : "BigButton"];

        // Without typing there is nothing to frame, so the card's chrome goes and only the button remains.
        ConfirmCard.Background = requireTyping ? new SolidColorBrush(Color.FromRgb(0x14, 0x18, 0x20)) : Brushes.Transparent;
        ConfirmCard.BorderThickness = requireTyping ? new Thickness(1) : default;
        ConfirmCard.Padding = requireTyping ? new Thickness(24, 20, 24, 20) : default;

        var typingVisibility = requireTyping ? Visibility.Visible : Visibility.Collapsed;
        ConfirmPrompt.Visibility = typingVisibility;
        ConfirmBox.Visibility = typingVisibility;
        DockConfirmBox.Visibility = typingVisibility;
        Grid.SetColumn(ConfirmButton, requireTyping ? 1 : 0);
        Grid.SetColumnSpan(ConfirmButton, requireTyping ? 1 : 2);
        ConfirmButton.Margin = requireTyping ? new Thickness(12, 0, 0, 0) : default;
        ConfirmButton.HorizontalAlignment = requireTyping ? HorizontalAlignment.Stretch : HorizontalAlignment.Center;
        UpdateConfirmEnabled(requireTyping);
    }

    /// <summary>Covers <paramref name="pixels"/> exactly, in physical pixels, above every normal window.</summary>
    public void CoverFull(System.Drawing.Rectangle pixels)
    {
        FullLayout.Visibility = Visibility.Visible;
        DockLayout.Visibility = Visibility.Collapsed;
        Native.SetWindowPos(Handle, Native.HwndTopmost, pixels.X, pixels.Y, pixels.Width, pixels.Height, Native.SwpShowWindow);
        FocusConfirm();
    }

    /// <summary>Shrinks to a bar at the top centre of <paramref name="workArea"/>, without taking focus.</summary>
    public void Dock(System.Drawing.Rectangle workArea)
    {
        FullLayout.Visibility = Visibility.Collapsed;
        DockLayout.Visibility = Visibility.Visible;
        var scale = VisualTreeHelper.GetDpi(this).DpiScaleX;
        var width = (int)Math.Round(560 * scale);
        var height = (int)Math.Round(112 * scale);
        var x = workArea.X + (workArea.Width - width) / 2;
        var y = workArea.Y + (int)Math.Round(12 * scale);
        Native.SetWindowPos(Handle, Native.HwndTopmost, x, y, width, height, Native.SwpShowWindow | Native.SwpNoActivate);
    }

    /// <summary>Re-asserts top-most: another top-most window (or a full-screen app) may have covered it.</summary>
    public void KeepOnTop() =>
        Native.SetWindowPos(Handle, Native.HwndTopmost, 0, 0, 0, 0, Native.SwpNoMove | Native.SwpNoSize | Native.SwpNoActivate | Native.SwpShowWindow);

    /// <summary>The window's content, for <see cref="Previews"/> to render without the window ever being shown.</summary>
    public FrameworkElement PreviewRoot => (FrameworkElement)Content;

    public void ShowFullLayoutForPreview()
    {
        FullLayout.Visibility = Visibility.Visible;
        DockLayout.Visibility = Visibility.Collapsed;
    }

    public void ShowDockLayoutForPreview()
    {
        FullLayout.Visibility = Visibility.Collapsed;
        DockLayout.Visibility = Visibility.Visible;
    }

    public void CloseForGood()
    {
        _allowClose = true;
        Close();
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        // Alt+F4, the taskbar, WM_CLOSE from anywhere: refused. Confirming is the only way out.
        e.Cancel = !_allowClose;
        base.OnClosing(e);
    }

    protected override void OnStateChanged(EventArgs e)
    {
        // Win+D and Win+M minimise everything; put the alarm straight back.
        if (WindowState != WindowState.Normal)
        {
            WindowState = WindowState.Normal;
        }

        base.OnStateChanged(e);
    }

    private void FocusConfirm()
    {
        if (!IsPrimary)
        {
            return;
        }

        var box = IsDocked ? DockConfirmBox : ConfirmBox;
        if (box.Visibility == Visibility.Visible)
        {
            box.Focus();
            Keyboard.Focus(box);
        }
        else
        {
            (IsDocked ? DockConfirmButton : ConfirmButton).Focus();
        }
    }

    private void SetBrush(string key, Color color) => Resources[key] = new SolidColorBrush(color);

    /// <summary>
    /// A faint tiled grain over the glow. A dark radial gradient has only a few dozen 8-bit steps across a
    /// whole monitor, which shows as rings; one-level noise breaks the steps up so the eye sees a smooth fade.
    /// </summary>
    private static readonly Lazy<ImageBrush> NoiseBrush = new(() =>
    {
        const int size = 128;
        var pixels = new byte[size * size * 4];
        var random = new Random(7);
        for (var i = 0; i < pixels.Length; i += 4)
        {
            var light = random.Next(2) == 0;
            pixels[i] = pixels[i + 1] = pixels[i + 2] = light ? (byte)255 : (byte)0;
            pixels[i + 3] = (byte)random.Next(0, 7);
        }

        var bitmap = System.Windows.Media.Imaging.BitmapSource.Create(size, size, 96, 96, PixelFormats.Bgra32, null, pixels, size * 4);
        bitmap.Freeze();
        var brush = new ImageBrush(bitmap)
        {
            TileMode = TileMode.Tile,
            Viewport = new Rect(0, 0, size, size),
            ViewportUnits = BrushMappingMode.Absolute,
            Stretch = Stretch.None,
        };
        brush.Freeze();
        return brush;
    });

    private void UpdateConfirmEnabled(bool requireTyping)
    {
        var typed = IsDocked ? DockConfirmBox.Text : ConfirmBox.Text;
        var ok = !requireTyping || AlarmCopy.Matches(typed);
        ConfirmButton.IsEnabled = ok;
        DockConfirmButton.IsEnabled = ok;
    }

    private void OnConfirmTextChanged(object sender, TextChangedEventArgs e)
    {
        // Keep both boxes in step so switching between full and docked never loses what was typed.
        var source = (TextBox)sender;
        var other = source == ConfirmBox ? DockConfirmBox : ConfirmBox;
        if (other.Text != source.Text)
        {
            other.Text = source.Text;
        }

        UpdateConfirmEnabled(_session.RequireTyping);
    }

    private void OnConfirmKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && ConfirmButton.IsEnabled)
        {
            _session.Confirm();
        }
    }

    private void OnConfirm(object sender, RoutedEventArgs e) => _session.Confirm();

    private void OnSnooze(object sender, RoutedEventArgs e) => _session.Snooze();

    private void OnStepAside(object sender, RoutedEventArgs e) => _session.StepAside();

    private void OnOpenAttendance(object sender, RoutedEventArgs e) => _session.OpenAttendance();
}
