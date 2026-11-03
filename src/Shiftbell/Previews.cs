using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>
/// `Shiftbell.exe --render-previews &lt;folder&gt;` draws every alarm state to PNG without showing a window,
/// playing a sound or touching the volume, so the design can be reviewed on a machine someone is using.
/// </summary>
internal static class Previews
{
    public static void Render(string folder)
    {
        Directory.CreateDirectory(folder);
        var day = DateOnly.FromDateTime(DateTime.Now);
        DateTime At(int h, int m) => day.ToDateTime(new TimeOnly(h, m));

        var settings = new ShiftbellSettings().Normalized();
        const string link = "https://hr.example.com/attendance";
        var timeIn = new DueAlarm(Alarm.TimeIn() with { Link = link }, day, At(7, 45), At(8, 0), 2);
        var timeOut = new DueAlarm(Alarm.TimeOut() with { Link = link }, day, At(17, 0), null, 2);
        var standup = new DueAlarm(
            new Alarm { Id = "standup0", Name = "Standup", RingAt = new TimeOnly(10, 0), Days = Alarm.Weekdays, Strict = false, Link = "https://meet.example.com/standup" },
            day, At(10, 0), null, 2);

        RenderFull(folder, "full-time-in", timeIn, settings, At(7, 47), primary: true);
        RenderFull(folder, "full-time-in-no-link", timeIn with { Alarm = timeIn.Alarm with { Link = "" } }, settings, At(7, 47), primary: true);
        RenderFull(folder, "full-late", timeIn with { SnoozesLeft = 0 }, settings, At(8, 14), primary: true);
        RenderFull(folder, "full-time-out", timeOut, settings, At(17, 6), primary: true);
        RenderFull(folder, "full-custom", standup, settings, At(10, 1), primary: true);
        RenderFull(folder, "full-secondary", timeIn, settings, At(7, 47), primary: false);
        RenderFull(folder, "full-no-typing", timeOut with { Alarm = timeOut.Alarm with { Strict = false } }, settings, At(17, 0), primary: true);
        RenderDock(folder, "dock-time-in", timeIn, settings, At(7, 48));
    }

    private static void RenderFull(string folder, string name, DueAlarm alarm, ShiftbellSettings settings, DateTime now, bool primary)
    {
        var window = Build(alarm, settings, now, primary, "");
        window.ShowFullLayoutForPreview();
        Save(window.PreviewRoot, 1920, 1080, Path.Combine(folder, name + ".png"));
    }

    private static void RenderDock(string folder, string name, DueAlarm alarm, ShiftbellSettings settings, DateTime now)
    {
        var window = Build(alarm, settings, now, primary: true, "· full screen again in 2:41");
        window.ShowDockLayoutForPreview();
        Save(window.PreviewRoot, 560, 112, Path.Combine(folder, name + ".png"));
    }

    private static AlarmWindow Build(DueAlarm alarm, ShiftbellSettings settings, DateTime now, bool primary, string dock)
    {
        var session = new AlarmSession(alarm, settings, isTest: true, () => now);
        var window = new AlarmWindow(session, primary);
        window.Apply(AlarmCopy.For(alarm, now), AlarmCopy.Snooze(settings, alarm), alarm.SnoozesLeft > 0,
            alarm.Alarm.Link.Length > 0, alarm.Alarm.Strict, dock);
        return window;
    }

    private static void Save(FrameworkElement root, int width, int height, string path)
    {
        root.Measure(new Size(width, height));
        root.Arrange(new Rect(0, 0, width, height));
        root.UpdateLayout();

        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        var background = new DrawingVisual();
        using (var dc = background.RenderOpen())
        {
            dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(0x0D, 0x10, 0x15)), null, new Rect(0, 0, width, height));
        }

        bitmap.Render(background);
        bitmap.Render(root);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(path);
        encoder.Save(file);
    }
}
