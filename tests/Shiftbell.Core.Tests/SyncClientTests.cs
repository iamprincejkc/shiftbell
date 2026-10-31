using System.Net;
using System.Text;
using Shiftbell.Core;

namespace Shiftbell.Core.Tests;

public sealed class SyncClientTests
{
    private static readonly SyncKeys Keys = SyncKeys.FromCode("SB1-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA")!;
    private static readonly Uri Relay = new("https://relay.example.test/api/shiftbell/");

    private sealed class Fake(Func<HttpRequestMessage, HttpResponseMessage> answer) : HttpMessageHandler
    {
        public List<(HttpRequestMessage Request, string Body)> Seen { get; } = [];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
            Seen.Add((request, body));
            return answer(request);
        }
    }

    private static HttpResponseMessage Json(string json, HttpStatusCode status = HttpStatusCode.OK) =>
        new(status) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    [Fact]
    public async Task A_push_posts_the_sealed_event_to_the_channel_with_the_token()
    {
        var fake = new Fake(_ => Json("""{"id":"x"}""", HttpStatusCode.Created));
        var e = SyncEvent.Ack(AlarmKind.TimeOut, new DateOnly(2026, 10, 31), new DateTime(2026, 10, 31, 17, 2, 0));

        Assert.True(await new SyncClient(new HttpClient(fake), Relay).Push(Keys, e, CancellationToken.None));

        var (request, body) = Assert.Single(fake.Seen);
        Assert.Equal(HttpMethod.Post, request.Method);
        Assert.Equal($"https://relay.example.test/api/shiftbell/{Keys.Channel}", request.RequestUri!.ToString());
        Assert.Equal($"Bearer {Keys.Token}", request.Headers.Authorization!.ToString());
        var data = System.Text.Json.JsonDocument.Parse(body).RootElement.GetProperty("data").GetString()!;
        Assert.Equal(e, SyncCrypto.Open(data, Keys));
    }

    [Fact]
    public async Task A_pull_reads_since_and_skips_what_does_not_decrypt()
    {
        var good = SyncCrypto.Seal(SyncEvent.DayOff(new DateOnly(2026, 11, 2), new DateTime(2026, 10, 31, 9, 0, 0), true), Keys);
        var fake = new Fake(_ => Json($$"""{"events":[{"id":"a","data":"{{good}}"},{"id":"b","data":"garbage"}],"now":1793421910321}"""));

        var pulled = await new SyncClient(new HttpClient(fake), Relay).Pull(Keys, 42, CancellationToken.None);

        Assert.NotNull(pulled);
        Assert.Equal("dayoff", Assert.Single(pulled.Events).Type);
        Assert.Equal(1793421910321, pulled.ServerNow);
        Assert.EndsWith($"{Keys.Channel}?since=42", fake.Seen[0].Request.RequestUri!.ToString());
    }

    [Fact]
    public async Task Server_and_network_failures_are_answers_not_exceptions()
    {
        var down = new SyncClient(new HttpClient(new Fake(_ => throw new HttpRequestException("offline"))), Relay);
        var refused = new SyncClient(new HttpClient(new Fake(_ => Json("{}", HttpStatusCode.Forbidden))), Relay);
        var e = SyncEvent.DayOff(new DateOnly(2026, 11, 2), DateTime.Now, true);

        Assert.Null(await down.Pull(Keys, 0, CancellationToken.None));
        Assert.False(await down.Push(Keys, e, CancellationToken.None));
        Assert.Null(await refused.Pull(Keys, 0, CancellationToken.None));
        Assert.False(await refused.Push(Keys, e, CancellationToken.None));
    }
}
