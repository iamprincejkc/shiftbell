using System.Diagnostics;
using System.IO;
using System.Media;
using Shiftbell.Core;

namespace Shiftbell;

/// <summary>
/// A looping alarm tone synthesised in memory. It plays through the normal audio output, not as a
/// notification sound, so Do Not Disturb and per-app notification settings cannot silence it.
/// It starts soft and gets louder the longer it rings (<see cref="RingLoudness"/>); call <see cref="Tick"/> every second.
/// </summary>
internal sealed class AlarmSound : IDisposable
{
    private const int SampleRate = 22050;

    private readonly (MemoryStream Wave, SoundPlayer Player)[] _steps;
    private readonly Stopwatch _ringing = new();
    private int _step = -1;

    public AlarmSound()
    {
        _steps = new (MemoryStream, SoundPlayer)[RingLoudness.StepCount];
        for (var i = 0; i < _steps.Length; i++)
        {
            var wave = BuildWave(RingLoudness.Gain(i));
            var player = new SoundPlayer(wave);
            player.Load();
            _steps[i] = (wave, player);
        }
    }

    /// <summary>Starts (or restarts) from the softest step.</summary>
    public void Start()
    {
        _ringing.Restart();
        Play(0);
    }

    public void Tick()
    {
        if (_ringing.IsRunning)
        {
            Play(RingLoudness.StepAt(_ringing.Elapsed));
        }
    }

    public void Stop()
    {
        _ringing.Reset();
        _step = -1;
        foreach (var (_, player) in _steps)
        {
            player.Stop();
        }
    }

    public void Dispose()
    {
        Stop();
        foreach (var (wave, player) in _steps)
        {
            player.Dispose();
            wave.Dispose();
        }
    }

    private void Play(int step)
    {
        if (step == _step)
        {
            return;
        }

        _step = step;
        _steps[step].Player.PlayLooping();
    }

    /// <summary>Beep, beep, high beep, rest: about 1.6 s per loop, with short fades so no edge clicks.</summary>
    private static MemoryStream BuildWave(double gain)
    {
        (double Hz, double Seconds)[] pattern =
        [
            (880, 0.16), (0, 0.08), (880, 0.16), (0, 0.08), (1318.5, 0.34), (0, 0.78),
        ];

        var samples = new List<short>();
        foreach (var (hz, seconds) in pattern)
        {
            var count = (int)(seconds * SampleRate);
            var fade = Math.Min(count / 2, SampleRate / 200);
            for (var i = 0; i < count; i++)
            {
                if (hz == 0)
                {
                    samples.Add(0);
                    continue;
                }

                var envelope = Math.Min(1.0, Math.Min(i, count - 1 - i) / (double)Math.Max(1, fade));
                var t = i / (double)SampleRate;

                // A touch of the octave makes it cut through speech and music better than a pure sine.
                var value = Math.Sin(2 * Math.PI * hz * t) * 0.8 + Math.Sin(4 * Math.PI * hz * t) * 0.2;
                samples.Add((short)(value * envelope * short.MaxValue * gain));
            }
        }

        var stream = new MemoryStream();
        using (var writer = new BinaryWriter(stream, System.Text.Encoding.ASCII, leaveOpen: true))
        {
            var dataBytes = samples.Count * 2;
            writer.Write("RIFF"u8);
            writer.Write(36 + dataBytes);
            writer.Write("WAVE"u8);
            writer.Write("fmt "u8);
            writer.Write(16);
            writer.Write((short)1);
            writer.Write((short)1);
            writer.Write(SampleRate);
            writer.Write(SampleRate * 2);
            writer.Write((short)2);
            writer.Write((short)16);
            writer.Write("data"u8);
            writer.Write(dataBytes);
            foreach (var sample in samples)
            {
                writer.Write(sample);
            }
        }

        stream.Position = 0;
        return stream;
    }
}
