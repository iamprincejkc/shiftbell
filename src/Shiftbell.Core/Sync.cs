using System.Buffers.Text;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Shiftbell.Core;

/// <summary>
/// One thing that happened on one device, sent to the other. The wire format is in docs/sync.md and is
/// shared with the phone app, so every field is a plain string or number in a fixed format.
/// </summary>
public sealed record SyncEvent
{
    public int V { get; init; } = 1;

    /// <summary><c>ack</c>, <c>snooze</c>, <c>dayoff</c>, <c>alarm</c>, <c>test</c> or <c>testend</c>.</summary>
    public string Type { get; init; } = "";

    /// <summary>0.3's <c>TimeIn</c> / <c>TimeOut</c>; still written for the presets so a 0.3 device understands.</summary>
    public string? Kind { get; init; }

    /// <summary>The alarm's id, for <c>ack</c>, <c>snooze</c> and <c>test</c>.</summary>
    public string? Alarm { get; init; }

    public string Date { get; init; } = "";

    public string At { get; init; } = "";

    public string? Until { get; init; }

    public int? Snoozes { get; init; }

    public bool? Off { get; init; }

    /// <summary><c>test</c> and <c>testend</c> only: which test alarm, so stopping it on one device stops that one elsewhere.</summary>
    public string? Id { get; init; }

    /// <summary><c>alarm</c> only: the whole definition, added, edited or deleted.</summary>
    public AlarmWire? Def { get; init; }

    public string From { get; init; } = "pc";

    /// <summary>A test alarm older than this when it arrives is not rung: it was over long ago.</summary>
    public static readonly TimeSpan TestFreshFor = TimeSpan.FromMinutes(2);

    public const string DateFormat = "yyyy-MM-dd";
    public const string TimeFormat = "yyyy-MM-dd'T'HH:mm:ss";

    public static SyncEvent Ack(Alarm alarm, DateOnly date, DateTime at) => new()
    {
        Type = "ack", Kind = LegacyKind(alarm), Alarm = alarm.Id, Date = Format(date), At = Format(at),
    };

    public static SyncEvent Snoozed(Alarm alarm, DateOnly date, DateTime at, DateTime until, int snoozes) => new()
    {
        Type = "snooze", Kind = LegacyKind(alarm), Alarm = alarm.Id, Date = Format(date), At = Format(at), Until = Format(until), Snoozes = snoozes,
    };

    /// <summary>An alarm was added, edited or deleted here.</summary>
    public static SyncEvent Changed(Alarm alarm) => new()
    {
        Type = "alarm", Date = Format(DateOnly.FromDateTime(alarm.UpdatedAt)), At = Format(alarm.UpdatedAt), Def = AlarmWire.From(alarm),
    };

    /// <summary>The alarm an event is about: its id, or for a 0.3 event, the preset its kind names. Not sent.</summary>
    [JsonIgnore]
    public string? AlarmId => Alarm ?? Kind switch
    {
        "TimeIn" => Shiftbell.Core.Alarm.TimeInId,
        "TimeOut" => Shiftbell.Core.Alarm.TimeOutId,
        _ => null,
    };

    private static string? LegacyKind(Alarm alarm) => alarm.Id switch
    {
        Shiftbell.Core.Alarm.TimeInId => "TimeIn",
        Shiftbell.Core.Alarm.TimeOutId => "TimeOut",
        _ => null,
    };

    public static SyncEvent DayOff(DateOnly date, DateTime at, bool off) => new()
    {
        Type = "dayoff", Date = Format(date), At = Format(at), Off = off,
    };

    /// <summary>A test alarm started here; the other device rings it too. Never recorded in the state.</summary>
    public static SyncEvent Test(Alarm alarm, DateTime at, string id) => new()
    {
        Type = "test", Kind = LegacyKind(alarm), Alarm = alarm.Id, Date = Format(DateOnly.FromDateTime(at)), At = Format(at), Id = id,
    };

    /// <summary>The test alarm with this id was stopped here, so stop it there too.</summary>
    public static SyncEvent TestEnd(string id, DateTime at) => new()
    {
        Type = "testend", Date = Format(DateOnly.FromDateTime(at)), At = Format(at), Id = id,
    };

    public static string NewTestId() => Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(8));

    /// <summary>A test from the other device that should ring here now.</summary>
    public bool IsFreshTestFrom(string otherDevice, DateTime now) =>
        Type == "test" && From == otherDevice && Id is { Length: > 0 }
        && DateTime.TryParseExact(At, TimeFormat, CultureInfo.InvariantCulture, DateTimeStyles.None, out var at)
        && now - at <= TestFreshFor && at - now <= TestFreshFor;

    public static string Format(DateOnly value) => value.ToString(DateFormat, CultureInfo.InvariantCulture);

    public static string Format(DateTime value) => value.ToString(TimeFormat, CultureInfo.InvariantCulture);
}

/// <summary>The channel id, access token and key, all derived from the pairing secret (HKDF-SHA256).</summary>
public sealed class SyncKeys
{
    private const string Prefix = "SB1-";

    private SyncKeys(string code, string channel, string token, byte[] key)
    {
        Code = code;
        Channel = channel;
        Token = token;
        Key = key;
    }

    public string Code { get; }

    public string Channel { get; }

    public string Token { get; }

    internal byte[] Key { get; }

    public static string NewCode() => Prefix + Base64Url.EncodeToString(RandomNumberGenerator.GetBytes(32));

    /// <summary>Null for anything that is not a v1 pairing code. Spaces and line breaks from copy-paste are ignored.</summary>
    public static SyncKeys? FromCode(string? code)
    {
        var text = new string((code ?? "").Where(c => !char.IsWhiteSpace(c)).ToArray());
        if (!text.StartsWith(Prefix, StringComparison.Ordinal))
        {
            return null;
        }

        byte[] secret;
        try
        {
            secret = Base64Url.DecodeFromChars(text.AsSpan(Prefix.Length));
        }
        catch (FormatException)
        {
            return null;
        }

        if (secret.Length != 32)
        {
            return null;
        }

        byte[] Derive(string info, int length) =>
            HKDF.DeriveKey(HashAlgorithmName.SHA256, secret, length, salt: [], info: Encoding.UTF8.GetBytes(info));

        return new SyncKeys(
            text,
            Convert.ToHexStringLower(Derive("shiftbell/v1/channel", 16)),
            Base64Url.EncodeToString(Derive("shiftbell/v1/token", 32)),
            Derive("shiftbell/v1/key", 32));
    }
}

/// <summary>AES-256-GCM with the channel bound in as associated data, so the relay cannot move events between channels.</summary>
public static class SyncCrypto
{
    private const int NonceBytes = 12;
    private const int TagBytes = 16;

    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static string Seal(SyncEvent e, SyncKeys keys) => Seal(e, keys, RandomNumberGenerator.GetBytes(NonceBytes));

    internal static string Seal(SyncEvent e, SyncKeys keys, byte[] nonce)
    {
        var plain = JsonSerializer.SerializeToUtf8Bytes(e, Json);
        var output = new byte[NonceBytes + plain.Length + TagBytes];
        nonce.CopyTo(output, 0);
        using var aes = new AesGcm(keys.Key, TagBytes);
        aes.Encrypt(
            nonce,
            plain,
            output.AsSpan(NonceBytes, plain.Length),
            output.AsSpan(NonceBytes + plain.Length, TagBytes),
            Aad(keys));
        return Base64Url.EncodeToString(output);
    }

    /// <summary>Null for anything that does not decrypt and parse: a damaged or foreign event is skipped, never fatal.</summary>
    public static SyncEvent? Open(string data, SyncKeys keys)
    {
        try
        {
            var bytes = Base64Url.DecodeFromChars(data);
            if (bytes.Length < NonceBytes + TagBytes)
            {
                return null;
            }

            var plain = new byte[bytes.Length - NonceBytes - TagBytes];
            using var aes = new AesGcm(keys.Key, TagBytes);
            aes.Decrypt(bytes.AsSpan(0, NonceBytes), bytes.AsSpan(NonceBytes, plain.Length), bytes.AsSpan(bytes.Length - TagBytes), plain, Aad(keys));
            var e = JsonSerializer.Deserialize<SyncEvent>(plain, Json);
            return e is { V: 1 } ? e : null;
        }
        catch (Exception ex) when (ex is FormatException or CryptographicException or JsonException)
        {
            return null;
        }
    }

    private static byte[] Aad(SyncKeys keys) => Encoding.UTF8.GetBytes("shiftbell/v1/" + keys.Channel);
}

/// <summary>
/// An alarm definition as it travels between devices: plain strings in fixed formats, the same field names
/// and order as the phone app, so both sides read each other byte for byte (docs/decisions/0002-alarm-list.md).
/// </summary>
public sealed record AlarmWire
{
    public string Id { get; init; } = "";

    public string Role { get; init; } = nameof(AlarmRole.Custom);

    public string Name { get; init; } = "";

    public bool Enabled { get; init; } = true;

    /// <summary><c>HH:mm</c>.</summary>
    public string RingAt { get; init; } = "";

    public string? Deadline { get; init; }

    public IReadOnlyList<string> Days { get; init; } = [];

    /// <summary><c>yyyy-MM-dd</c> for a one-off alarm.</summary>
    public string? Date { get; init; }

    public bool Strict { get; init; } = true;

    public string Link { get; init; } = "";

    public string ArmedAt { get; init; } = "";

    public string UpdatedAt { get; init; } = "";

    public bool Deleted { get; init; }

    private const string Clock = "HH:mm";

    public static AlarmWire From(Alarm alarm) => new()
    {
        Id = alarm.Id,
        Role = alarm.Role.ToString(),
        Name = alarm.Name,
        Enabled = alarm.Enabled,
        RingAt = alarm.RingAt.ToString(Clock, CultureInfo.InvariantCulture),
        Deadline = alarm.Deadline?.ToString(Clock, CultureInfo.InvariantCulture),
        Days = alarm.Days.Select(d => d.ToString()).ToArray(),
        Date = alarm.Date is { } date ? SyncEvent.Format(date) : null,
        Strict = alarm.Strict,
        Link = alarm.Link,
        ArmedAt = SyncEvent.Format(alarm.ArmedAt),
        UpdatedAt = SyncEvent.Format(alarm.UpdatedAt),
        Deleted = alarm.Deleted,
    };

    /// <summary>Null for a definition that does not parse; it is skipped, never fatal.</summary>
    public Alarm? ToAlarm()
    {
        var invariant = CultureInfo.InvariantCulture;
        if (Id.Length == 0
            || !Enum.TryParse<AlarmRole>(Role, out var role)
            || !TimeOnly.TryParseExact(RingAt, Clock, invariant, DateTimeStyles.None, out var ringAt)
            || !DateTime.TryParseExact(UpdatedAt, SyncEvent.TimeFormat, invariant, DateTimeStyles.None, out var updatedAt))
        {
            return null;
        }

        TimeOnly? deadline = TimeOnly.TryParseExact(Deadline, Clock, invariant, DateTimeStyles.None, out var d) ? d : null;
        DateOnly? date = DateOnly.TryParseExact(Date, SyncEvent.DateFormat, invariant, DateTimeStyles.None, out var on) ? on : null;
        DateTime.TryParseExact(ArmedAt, SyncEvent.TimeFormat, invariant, DateTimeStyles.None, out var armedAt);
        var days = (Days ?? []).Select(day => Enum.TryParse<DayOfWeek>(day, out var parsed) ? parsed : (DayOfWeek?)null).OfType<DayOfWeek>().ToArray();
        return new Alarm
        {
            Id = Id, Role = role, Name = Name ?? "", Enabled = Enabled, RingAt = ringAt, Deadline = deadline, Days = days, Date = date,
            Strict = Strict, Link = Link ?? "", ArmedAt = armedAt, UpdatedAt = updatedAt, Deleted = Deleted,
        }.Normalized();
    }
}

/// <summary>Folds events into the state and settings. Idempotent and order-independent, so replays and overlaps are harmless.</summary>
public static class SyncMerge
{
    /// <summary>Applies an ack, snooze or day off to the state. True when it changed.</summary>
    public static bool Apply(ShiftbellState state, SyncEvent e)
    {
        if (!DateOnly.TryParseExact(e.Date, SyncEvent.DateFormat, CultureInfo.InvariantCulture, DateTimeStyles.None, out var date)
            || !TryTime(e.At, out var at))
        {
            return false;
        }

        switch (e.Type)
        {
            case "ack" when e.AlarmId is { } id:
            {
                var record = state.Day(date).Record(id);
                if (record.AcknowledgedAt is { } existing && existing <= at)
                {
                    return false;
                }

                record.AcknowledgedAt = at;
                record.SnoozedUntil = null;
                return true;
            }

            case "snooze" when e.AlarmId is { } id && TryTime(e.Until, out var until):
            {
                var record = state.Day(date).Record(id);
                if (record.AcknowledgedAt is not null)
                {
                    return false;
                }

                var changed = false;
                if (record.SnoozedUntil is not { } current || until > current)
                {
                    record.SnoozedUntil = until;
                    changed = true;
                }

                if (e.Snoozes is { } used && used > record.Snoozes)
                {
                    record.Snoozes = used;
                    changed = true;
                }

                return changed;
            }

            case "dayoff" when e.Off is { } off:
            {
                var day = state.Day(date);
                if (day.DayOffChangedAt is { } changedAt && changedAt >= at)
                {
                    return false;
                }

                day.DayOff = off;
                day.DayOffChangedAt = at;
                return true;
            }

            default:
                return false;
        }
    }

    /// <summary>Applies an <c>alarm</c> event to the list: the newest <see cref="Alarm.UpdatedAt"/> wins, ties keep what is there.</summary>
    public static ShiftbellSettings Apply(ShiftbellSettings settings, SyncEvent e)
    {
        if (e.Type != "alarm" || e.Def?.ToAlarm() is not { } incoming)
        {
            return settings;
        }

        var existing = (settings.Alarms ?? []).FirstOrDefault(a => a.Id == incoming.Id);
        return existing is not null && existing.UpdatedAt >= incoming.UpdatedAt ? settings : settings.With(incoming);
    }

    private static bool TryTime(string? text, out DateTime value) =>
        DateTime.TryParseExact(text, SyncEvent.TimeFormat, CultureInfo.InvariantCulture, DateTimeStyles.None, out value);
}
