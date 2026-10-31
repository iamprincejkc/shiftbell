using System.Text.Json;
using System.Text.Json.Serialization;

namespace Shiftbell.Core;

/// <summary>Reads and writes settings.json and state.json. Writes are atomic: a crash mid-save keeps the old file.</summary>
public sealed class JsonStore
{
    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() },
    };

    public JsonStore(string folder)
    {
        Folder = folder;
        Directory.CreateDirectory(folder);
    }

    public string Folder { get; }

    public string SettingsPath => Path.Combine(Folder, "settings.json");

    public string StatePath => Path.Combine(Folder, "state.json");

    /// <summary>%APPDATA%\Shiftbell, or SHIFTBELL_HOME when set (tests and trial runs use that).</summary>
    public static JsonStore Default()
    {
        var overridden = Environment.GetEnvironmentVariable("SHIFTBELL_HOME");
        var folder = string.IsNullOrWhiteSpace(overridden)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Shiftbell")
            : overridden;
        return new JsonStore(folder);
    }

    public bool SettingsExist => File.Exists(SettingsPath);

    public ShiftbellSettings LoadSettings() => (Read<ShiftbellSettings>(SettingsPath) ?? new ShiftbellSettings()).Normalized();

    public void SaveSettings(ShiftbellSettings settings) => Write(SettingsPath, settings.Normalized());

    /// <summary>Loads state; on first run creates it armed from <paramref name="now"/>.</summary>
    public ShiftbellState LoadState(DateTime now)
    {
        var state = Read<ShiftbellState>(StatePath);
        if (state is null)
        {
            state = new ShiftbellState { ArmedSince = now };
            SaveState(state);
        }

        return state;
    }

    public void SaveState(ShiftbellState state) => Write(StatePath, state);

    private static T? Read<T>(string path)
        where T : class
    {
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            return JsonSerializer.Deserialize<T>(File.ReadAllText(path), Options);
        }
        catch (JsonException)
        {
            // A corrupt file must not stop the alarms. Keep it for inspection and start from defaults.
            File.Copy(path, path + ".corrupt", overwrite: true);
            return null;
        }
    }

    private static void Write<T>(string path, T value)
    {
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(value, Options));
        File.Move(temp, path, overwrite: true);
    }
}
