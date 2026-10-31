using System.Net.Http;
using System.Windows.Threading;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>
/// Runs sync rounds with the phone: send the outbox, then read what the phone did and merge it.
/// The network work runs off the UI thread; the state is only ever touched on it, so the alarm loop never races it.
/// </summary>
internal sealed class Syncer(Dispatcher dispatcher, Func<ShiftbellState> state, Action save)
{
    /// <summary>Reads start this far before the last server time, so an event that landed out of order is not skipped.</summary>
    private const long OverlapMs = 120_000;

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(4) };
    private readonly SyncClient _client = new(Http, SyncClient.DefaultRelay);
    private SyncKeys? _keys;

    /// <summary>
    /// Raised on the UI thread after a round, with whether the merged state changed and every event read
    /// (test alarms live only in these: they are never part of the state).
    /// </summary>
    public event Action<bool, IReadOnlyList<SyncEvent>>? RoundFinished;

    public bool Enabled => _keys is not null;

    public bool Busy { get; private set; }

    public DateTime? LastStarted { get; private set; }

    public DateTime? LastSuccess { get; private set; }

    public void Configure(string code)
    {
        _keys = SyncKeys.FromCode(code);
        LastSuccess = null;
    }

    /// <summary>Queues a local change for the phone and sends it straight away.</summary>
    public void Send(SyncEvent e)
    {
        if (_keys is null)
        {
            return;
        }

        state().Outbox.Add(e);
        save();
        Kick();
    }

    /// <summary>Starts a round unless one is running.</summary>
    public void Kick()
    {
        if (_keys is not { } keys || Busy)
        {
            return;
        }

        Busy = true;
        LastStarted = DateTime.Now;
        var outbox = state().Outbox.ToList();
        var since = Math.Max(0, state().SyncSince - OverlapMs);
        Task.Run(async () =>
        {
            using var cancel = new CancellationTokenSource(TimeSpan.FromSeconds(8));
            var sent = new List<SyncEvent>();
            foreach (var e in outbox)
            {
                if (!await _client.Push(keys, e, cancel.Token).ConfigureAwait(false))
                {
                    break;
                }

                sent.Add(e);
            }

            var pulled = await _client.Pull(keys, since, cancel.Token).ConfigureAwait(false);
            await dispatcher.InvokeAsync(() => Finish(keys, sent, pulled));
        });
    }

    private void Finish(SyncKeys keys, List<SyncEvent> sent, SyncClient.Pulled? pulled)
    {
        Busy = false;
        if (!ReferenceEquals(keys, _keys))
        {
            return; // Unpaired or re-paired meanwhile: this round's answers belong to the old channel.
        }

        var current = state();
        var changed = sent.Count > 0 && current.Outbox.RemoveAll(e => sent.Contains(e)) > 0;
        var merged = false;
        if (pulled is not null)
        {
            foreach (var e in pulled.Events)
            {
                merged |= SyncMerge.Apply(current, e);
            }

            current.SyncSince = pulled.ServerNow;
            LastSuccess = DateTime.Now;
            changed = true;
        }

        if (changed || merged)
        {
            save();
        }

        RoundFinished?.Invoke(merged, pulled?.Events ?? []);
    }
}
