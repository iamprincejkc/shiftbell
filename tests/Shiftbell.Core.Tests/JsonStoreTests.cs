using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public sealed class JsonStoreTests : IDisposable
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "shiftbell-tests-" + Guid.NewGuid().ToString("N"));

    public void Dispose() => Directory.Delete(_folder, recursive: true);

    [Fact]
    public void First_load_arms_from_now_and_writes_the_file()
    {
        var store = new JsonStore(_folder);
        var now = new DateTime(2026, 10, 27, 10, 0, 0);

        var state = store.LoadState(now);

        Assert.Equal(now, state.ArmedSince);
        Assert.True(File.Exists(store.StatePath));
        Assert.Equal(now, store.LoadState(now.AddHours(5)).ArmedSince);
    }

    [Fact]
    public void Settings_and_state_round_trip()
    {
        var store = new JsonStore(_folder);
        var settings = new ShiftbellSettings
        {
            TimeIn = new AlarmSettings { RingAt = new TimeOnly(6, 50), Deadline = new TimeOnly(7, 0) },
            AttendanceLink = "https://hr.example.test/punch",
            WorkDays = [DayOfWeek.Saturday],
        };
        store.SaveSettings(settings);

        var state = new ShiftbellState { ArmedSince = new DateTime(2026, 10, 1) };
        state.Acknowledge(AlarmKind.TimeOut, new DateOnly(2026, 10, 27), new DateTime(2026, 10, 27, 17, 2, 0));
        store.SaveState(state);

        var loaded = store.LoadSettings();
        Assert.Equal(new TimeOnly(6, 50), loaded.TimeIn.RingAt);
        Assert.Equal("https://hr.example.test/punch", loaded.AttendanceLink);
        Assert.Equal([DayOfWeek.Saturday], loaded.WorkDays);
        Assert.Equal(new DateTime(2026, 10, 27, 17, 2, 0), store.LoadState(DateTime.Now).Find(new DateOnly(2026, 10, 27))!.TimeOut.AcknowledgedAt);
    }

    [Fact]
    public void No_attendance_link_is_built_in_and_a_saved_one_survives()
    {
        var store = new JsonStore(_folder);

        Assert.Equal("", store.LoadSettings().AttendanceLink);

        store.SaveSettings(new ShiftbellSettings { AttendanceLink = "https://hr.example.test/punch" });
        Assert.Equal("https://hr.example.test/punch", store.LoadSettings().AttendanceLink);
    }

    [Fact]
    public void A_corrupt_settings_file_falls_back_to_defaults_and_is_kept_aside()
    {
        var store = new JsonStore(_folder);
        File.WriteAllText(store.SettingsPath, "{ not json");

        var settings = store.LoadSettings();

        Assert.Equal(new TimeOnly(7, 45), settings.TimeIn.RingAt);
        Assert.True(File.Exists(store.SettingsPath + ".corrupt"));
    }
}
