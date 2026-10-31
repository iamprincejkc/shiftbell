using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace Shiftbell.Core;

/// <summary>Talks to the relay (docs/sync.md). Every failure is a null or false, never an exception: no network must never stop an alarm.</summary>
public sealed class SyncClient(HttpClient http, Uri relay)
{
    /// <summary>
    /// The owner's portfolio on Netlify, which holds the relay's storage (the Cloudflare Pages copy does not).
    /// SHIFTBELL_SYNC_URL points a trial run at a local dev server instead.
    /// </summary>
    public static Uri DefaultRelay =>
        new(Environment.GetEnvironmentVariable("SHIFTBELL_SYNC_URL") is { Length: > 0 } url ? url.TrimEnd('/') + "/" : "https://iamprincejkc.netlify.app/api/shiftbell/");

    public sealed record Pulled(IReadOnlyList<SyncEvent> Events, long ServerNow);

    public async Task<Pulled?> Pull(SyncKeys keys, long since, CancellationToken cancel)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(relay, $"{keys.Channel}?since={since}"));
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", keys.Token);
            using var response = await http.SendAsync(request, cancel).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                return null;
            }

            var body = await response.Content.ReadFromJsonAsync<PullBody>(cancel).ConfigureAwait(false);
            if (body?.Events is null)
            {
                return null;
            }

            var events = body.Events
                .Select(e => e.Data is { } data ? SyncCrypto.Open(data, keys) : null)
                .OfType<SyncEvent>()
                .ToList();
            return new Pulled(events, body.Now);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            return null;
        }
    }

    public async Task<bool> Push(SyncKeys keys, SyncEvent e, CancellationToken cancel)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(relay, keys.Channel))
            {
                Content = JsonContent.Create(new { data = SyncCrypto.Seal(e, keys) }),
            };
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", keys.Token);
            using var response = await http.SendAsync(request, cancel).ConfigureAwait(false);
            return response.IsSuccessStatusCode;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            return false;
        }
    }

    private sealed record PullBody(List<PullEvent>? Events, long Now);

    private sealed record PullEvent(string? Id, string? Data);
}
