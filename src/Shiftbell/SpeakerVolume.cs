using System.Runtime.InteropServices;

namespace Shiftbell;

/// <summary>
/// Unmutes the default speakers and raises them to a floor while an alarm rings, then puts them back.
/// A muted laptop is the most common reason an alarm "did not go off".
/// </summary>
internal sealed class SpeakerVolume
{
    private bool _saved;
    private bool _wasMuted;
    private float _previousLevel;

    public void Raise(int minimumPercent)
    {
        if (minimumPercent <= 0)
        {
            return;
        }

        try
        {
            var endpoint = Open();
            var context = Guid.Empty;
            Marshal.ThrowExceptionForHR(endpoint.GetMute(out _wasMuted));
            Marshal.ThrowExceptionForHR(endpoint.GetMasterVolumeLevelScalar(out _previousLevel));
            _saved = true;

            var floor = minimumPercent / 100f;
            if (_previousLevel < floor)
            {
                Marshal.ThrowExceptionForHR(endpoint.SetMasterVolumeLevelScalar(floor, ref context));
            }

            if (_wasMuted)
            {
                Marshal.ThrowExceptionForHR(endpoint.SetMute(false, ref context));
            }
        }
        catch (COMException)
        {
            // No audio device (or the service is down). The alarm is still on screen; never fail over sound.
            _saved = false;
        }
    }

    public void Restore()
    {
        if (!_saved)
        {
            return;
        }

        _saved = false;
        try
        {
            var endpoint = Open();
            var context = Guid.Empty;
            endpoint.SetMasterVolumeLevelScalar(_previousLevel, ref context);
            endpoint.SetMute(_wasMuted, ref context);
        }
        catch (COMException)
        {
            // The device went away while ringing; nothing to restore.
        }
    }

    private static IAudioEndpointVolume Open()
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(DataFlowRender, RoleMultimedia, out var device));
        var iid = typeof(IAudioEndpointVolume).GUID;
        Marshal.ThrowExceptionForHR(device.Activate(ref iid, ClsCtxAll, IntPtr.Zero, out var volume));
        return (IAudioEndpointVolume)volume;
    }

    private const int DataFlowRender = 0;
    private const int RoleMultimedia = 1;
    private const int ClsCtxAll = 0x17;

    [ComImport]
    [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator;

    [ComImport]
    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig]
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);

        [PreserveSig]
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [ComImport]
    [Guid("D666063F-1587-4E43-81F1-B948E807363F")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig]
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport]
    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        [PreserveSig]
        int RegisterControlChangeNotify(IntPtr notify);

        [PreserveSig]
        int UnregisterControlChangeNotify(IntPtr notify);

        [PreserveSig]
        int GetChannelCount(out uint count);

        [PreserveSig]
        int SetMasterVolumeLevel(float levelDb, ref Guid context);

        [PreserveSig]
        int SetMasterVolumeLevelScalar(float level, ref Guid context);

        [PreserveSig]
        int GetMasterVolumeLevel(out float levelDb);

        [PreserveSig]
        int GetMasterVolumeLevelScalar(out float level);

        [PreserveSig]
        int SetChannelVolumeLevel(uint channel, float levelDb, ref Guid context);

        [PreserveSig]
        int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid context);

        [PreserveSig]
        int GetChannelVolumeLevel(uint channel, out float levelDb);

        [PreserveSig]
        int GetChannelVolumeLevelScalar(uint channel, out float level);

        [PreserveSig]
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);

        [PreserveSig]
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
