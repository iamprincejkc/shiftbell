using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Security;
using System.Text;
using Microsoft.Win32;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>
/// The safety net around the in-process scheduler, so an alarm never depends on the app having stayed alive:
/// a Run key starts it at sign-in, and a scheduled task restarts it every 15 minutes if it died and wakes
/// the PC from sleep shortly before each alarm. Every trigger just launches the exe with --background,
/// which hands over to the running instance (or becomes it) and checks for due alarms.
/// </summary>
internal static class StartupRegistration
{
    public const string TaskName = "Shiftbell";
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private static readonly TimeSpan WakeLead = TimeSpan.FromMinutes(2);

    public static string ExePath => Environment.ProcessPath ?? throw new InvalidOperationException("No process path.");

    /// <summary>Writes both registrations. Returns null on success, or what failed.</summary>
    public static string? Register(ShiftbellSettings settings)
    {
        using (var run = Registry.CurrentUser.CreateSubKey(RunKeyPath))
        {
            run.SetValue(TaskName, $"\"{ExePath}\" --background");
        }

        var xmlPath = Path.Combine(Path.GetTempPath(), $"shiftbell-task-{Environment.ProcessId}.xml");
        try
        {
            File.WriteAllText(xmlPath, BuildTaskXml(settings, DateTime.Now), Encoding.Unicode);
            var (exit, output) = RunSchtasks($"/Create /TN \"{TaskName}\" /XML \"{xmlPath}\" /F");
            return exit == 0 ? null : $"Task Scheduler refused the task ({exit}): {output.Trim()}";
        }
        finally
        {
            File.Delete(xmlPath);
        }
    }

    public static void Unregister()
    {
        using (var run = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true))
        {
            run?.DeleteValue(TaskName, throwOnMissingValue: false);
        }

        RunSchtasks($"/Delete /TN \"{TaskName}\" /F");
    }

    public static bool IsRegistered() => RunSchtasks($"/Query /TN \"{TaskName}\"").Exit == 0;

    internal static string BuildTaskXml(ShiftbellSettings settings, DateTime now)
    {
        var user = SecurityElement.Escape($"{Environment.UserDomainName}\\{Environment.UserName}");
        var exe = SecurityElement.Escape(ExePath);
        var today = now.Date;

        var days = new StringBuilder();
        foreach (var day in settings.WorkDays)
        {
            days.Append('<').Append(day).Append(" />");
        }

        var triggers = new StringBuilder();

        // Watchdog: relaunch within 15 minutes if the app was closed or crashed.
        triggers.Append(CultureInfo.InvariantCulture, $"""
            <TimeTrigger>
              <Repetition><Interval>PT15M</Interval><StopAtDurationEnd>false</StopAtDurationEnd></Repetition>
              <StartBoundary>{today:yyyy-MM-ddTHH:mm:ss}</StartBoundary>
              <Enabled>true</Enabled>
            </TimeTrigger>
            """);

        // Wake-from-sleep, two minutes before each ring time, on work days only.
        if (settings.WorkDays.Count > 0)
        {
            foreach (var ring in AlarmPlanner.RingTimes(settings))
            {
                var wake = today + ring.ToTimeSpan() - WakeLead;
                triggers.Append(CultureInfo.InvariantCulture, $"""
                    <CalendarTrigger>
                      <StartBoundary>{wake:yyyy-MM-ddTHH:mm:ss}</StartBoundary>
                      <Enabled>true</Enabled>
                      <ScheduleByWeek><DaysOfWeek>{days}</DaysOfWeek><WeeksInterval>1</WeeksInterval></ScheduleByWeek>
                    </CalendarTrigger>
                    """);
            }
        }

        return $"""
            <?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <RegistrationInfo>
                <Author>{user}</Author>
                <Description>Keeps Shiftbell running and wakes the PC shortly before each time-in and time-out alarm.</Description>
              </RegistrationInfo>
              <Triggers>{triggers}</Triggers>
              <Principals>
                <Principal id="Author">
                  <UserId>{user}</UserId>
                  <LogonType>InteractiveToken</LogonType>
                  <RunLevel>LeastPrivilege</RunLevel>
                </Principal>
              </Principals>
              <Settings>
                <MultipleInstancesPolicy>Parallel</MultipleInstancesPolicy>
                <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                <AllowHardTerminate>true</AllowHardTerminate>
                <StartWhenAvailable>true</StartWhenAvailable>
                <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
                <IdleSettings><StopOnIdleEnd>false</StopOnIdleEnd><RestartOnIdle>false</RestartOnIdle></IdleSettings>
                <AllowStartOnDemand>true</AllowStartOnDemand>
                <Enabled>true</Enabled>
                <Hidden>false</Hidden>
                <RunOnlyIfIdle>false</RunOnlyIfIdle>
                <WakeToRun>true</WakeToRun>
                <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                <Priority>5</Priority>
              </Settings>
              <Actions Context="Author">
                <Exec><Command>{exe}</Command><Arguments>--background</Arguments></Exec>
              </Actions>
            </Task>
            """;
    }

    private static (int Exit, string Output) RunSchtasks(string arguments)
    {
        var info = new ProcessStartInfo("schtasks.exe", arguments)
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        using var process = Process.Start(info)!;
        var output = process.StandardOutput.ReadToEnd() + process.StandardError.ReadToEnd();
        process.WaitForExit();
        return (process.ExitCode, output);
    }
}
