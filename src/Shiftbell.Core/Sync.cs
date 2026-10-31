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

    /// <summary><c>ack</c>, <c>snooze</c> or <c>dayoff</c>.</summary>
    public string Type { get; init; } = "";

    public string? Kind { get; init; }

    public string Date { get; init; } = "";

    public string At { get; init; } = "";

    public string? Until { get; init; }

    public int? Snoozes { get; init; }

    public bool? Off { get; init; }

    /// <summary><c>test</c> and <c>testend</c> only: which test alarm, so stopping it on one device stops that one elsewhere.</summary>
    public string? Id { get; init; }

    public string From { get; init; } = "pc";

    /// <summary>A test alarm older than this when it arrives is not rung: it was over long ago.</summary>
    public static readonly TimeSpan TestFreshFor = TimeSpan.FromMinutes(2);

    public const string DateFormat = "yyyy-MM-dd";
    public const string TimeFormat = "yyyy-MM-dd'T'HH:mm:ss";

    public static SyncEvent Ack(AlarmKind kind, DateOnly date, DateTime at) => new()
    {
        Type = "ack", Kind = kind.ToString(), Date = Format(date), At = Format(at),
    };

    public static SyncEvent Snoozed(AlarmKind kind, DateOnly date, DateTime at, DateTime until, int snoozes) => new()
    {
        Type = "snooze", Kind = kind.ToString(), Date = Format(date), At = Format(at), Until = Format(until), Snoozes = snoozes,
    };

    public static SyncEvent DayOff(DateOnly date, DateTime at, bool off) => new()
    {
        Type = "dayoff", Date = Format(date), At = Format(at), Off = off,
    };

    /// <summary>A test alarm started here; the other device rings it too. Never recorded in the state.</summary>
    public static SyncEvent Test(AlarmKind kind, DateTime at, string id) => new()
    {
        Type = "test", Kind = kind.ToString(), Date = Format(DateOnly.FromDateTime(at)), At = Format(at), Id = id,
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

/// <summary>Folds an event into the state. Idempotent and order-independent, so replays and overlaps are harmless.</summary>
public static class SyncMerge
{
    /// <summary>True when the state changed.</summary>
    public static bool Apply(ShiftbellState state, SyncEvent e)
    {
        if (!DateOnly.TryParseExact(e.Date, SyncEvent.DateFormat, CultureInfo.InvariantCulture, DateTimeStyles.None, out var date)
            || !TryTime(e.At, out var at))
        {
            return false;
        }

        switch (e.Type)
        {
            case "ack" when Enum.TryParse<AlarmKind>(e.Kind, out var kind):
            {
                var record = state.Day(date).For(kind);
                if (record.AcknowledgedAt is { } existing && existing <= at)
                {
                    return false;
                }

                record.AcknowledgedAt = at;
                record.SnoozedUntil = null;
                return true;
            }

            case "snooze" when Enum.TryParse<AlarmKind>(e.Kind, out var kind) && TryTime(e.Until, out var until):
            {
                var record = state.Day(date).For(kind);
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

    private static bool TryTime(string? text, out DateTime value) =>
        DateTime.TryParseExact(text, SyncEvent.TimeFormat, CultureInfo.InvariantCulture, DateTimeStyles.None, out value);
}
