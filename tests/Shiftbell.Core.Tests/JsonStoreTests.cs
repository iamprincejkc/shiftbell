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
        var standup = new Alarm
        {
            Id = "standup0", Name = "Standup", RingAt = new TimeOnly(10, 0), Days = [DayOfWeek.Saturday], Strict = false,
            Link = "https://meet.example.test/standup", UpdatedAt = new DateTime(2026, 10, 27, 9, 0, 0),
        };
        store.SaveSettings(new ShiftbellSettings().Normalized().With(standup));

        var state = new ShiftbellState { ArmedSince = new DateTime(2026, 10, 1) };
        state.Acknowledge(Alarm.TimeOutId, new DateOnly(2026, 10, 27), new DateTime(2026, 10, 27, 17, 2, 0));
        store.SaveState(state);

        var loaded = store.LoadSettings().Find("standup0")!;
        Assert.True(loaded.SameAs(standup.Normalized()));
        Assert.Equal(new DateTime(2026, 10, 27, 17, 2, 0), store.LoadState(DateTime.Now).Find(new DateOnly(2026, 10, 27))!.Find(Alarm.TimeOutId)!.AcknowledgedAt);
    }

    [Fact]
    public void A_0_3_settings_and_state_file_loads_into_the_alarm_list()
    {
        var store = new JsonStore(_folder);
        File.WriteAllText(store.SettingsPath, """
            { "timeIn": { "enabled": true, "ringAt": "06:50:00", "deadline": "07:00:00" },
              "timeOut": { "enabled": true, "ringAt": "16:00:00", "deadline": null },
              "workDays": ["Monday", "Tuesday"], "attendanceLink": "https://hr.example.test/punch",
              "requireTypedConfirmation": true, "minimumVolumePercent": 25, "syncCode": "" }
            """);
        File.WriteAllText(store.StatePath, """
            { "armedSince": "2026-10-01T00:00:00", "days": [ { "date": "2026-10-27", "dayOff": false,
              "timeIn": { "acknowledgedAt": "2026-10-27T07:46:00", "snoozedUntil": null, "snoozes": 0 },
              "timeOut": { "acknowledgedAt": null, "snoozedUntil": null, "snoozes": 1 } } ] }
            """);

        var settings = store.LoadSettings();
        var state = store.LoadState(DateTime.Now);

        Assert.Equal(new TimeOnly(6, 50), settings.Find(Alarm.TimeInId)!.RingAt);
        Assert.Equal("https://hr.example.test/punch", settings.Find(Alarm.TimeOutId)!.Link);
        Assert.Equal([DayOfWeek.Monday, DayOfWeek.Tuesday], settings.Find(Alarm.TimeOutId)!.Days);
        Assert.Equal(new DateTime(2026, 10, 27, 7, 46, 0), state.Find(new DateOnly(2026, 10, 27))!.Find(Alarm.TimeInId)!.AcknowledgedAt);
        Assert.Equal(1, state.Find(new DateOnly(2026, 10, 27))!.Find(Alarm.TimeOutId)!.Snoozes);

        store.SaveSettings(settings);
        Assert.DoesNotContain("workDays", File.ReadAllText(store.SettingsPath), StringComparison.Ordinal);
        Assert.DoesNotContain("\"active\"", File.ReadAllText(store.SettingsPath), StringComparison.Ordinal);
    }

    [Fact]
    public void No_attendance_link_is_built_in()
    {
        Assert.All(new JsonStore(_folder).LoadSettings().Active, a => Assert.Equal("", a.Link));
    }

    [Fact]
    public void A_corrupt_settings_file_falls_back_to_defaults_and_is_kept_aside()
    {
        var store = new JsonStore(_folder);
        File.WriteAllText(store.SettingsPath, "{ not json");

        var settings = store.LoadSettings();

        Assert.Equal(new TimeOnly(7, 45), settings.Find(Alarm.TimeInId)!.RingAt);
        Assert.True(File.Exists(store.SettingsPath + ".corrupt"));
    }
}
