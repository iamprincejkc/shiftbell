using System.IO;
using System.IO.Pipes;

namespace Shiftbell;

/// <summary>
/// One running instance per user. A second launch passes its command (show settings, check now, test)
/// to the first over a per-user named pipe and exits.
/// </summary>
internal sealed class CommandChannel : IDisposable
{
    private static readonly string Name = $"Shiftbell-{Environment.UserName}";

    private readonly Mutex _mutex;
    private readonly CancellationTokenSource _stop = new();

    private CommandChannel(Mutex mutex) => _mutex = mutex;

    /// <summary>Returns the channel if this process is the first instance, otherwise null.</summary>
    public static CommandChannel? TryBecomePrimary()
    {
        var mutex = new Mutex(initiallyOwned: true, $"Local\\{Name}", out var created);
        if (created)
        {
            return new CommandChannel(mutex);
        }

        mutex.Dispose();
        return null;
    }

    /// <summary>Delivers a command to the running instance. False if it could not be reached.</summary>
    public static bool Send(string command)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", Name, PipeDirection.Out);
            client.Connect(3000);
            using var writer = new StreamWriter(client);
            writer.WriteLine(command);
            return true;
        }
        catch (Exception ex) when (ex is TimeoutException or IOException)
        {
            return false;
        }
    }

    /// <summary>Listens until disposed, raising each command on the caller's thread via <paramref name="onCommand"/>.</summary>
    public void Listen(Action<string> onCommand)
    {
        var token = _stop.Token;
        _ = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await using var server = new NamedPipeServerStream(Name, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                    await server.WaitForConnectionAsync(token);
                    using var reader = new StreamReader(server);
                    var line = await reader.ReadLineAsync(token);
                    if (!string.IsNullOrWhiteSpace(line))
                    {
                        onCommand(line.Trim());
                    }
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                catch (IOException)
                {
                    // A client that hung up mid-line; keep listening.
                }
            }
        }, token);
    }

    public void Dispose()
    {
        _stop.Cancel();
        _stop.Dispose();
        _mutex.ReleaseMutex();
        _mutex.Dispose();
    }
}
