using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Companion
{
    public static partial class Native
    {
        // --- Son (Core Audio) : volume et sourdine de la sortie, sourdine du micro

        [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioEndpointVolume
        {
            [PreserveSig] int RegisterControlChangeNotify(IntPtr notify);
            [PreserveSig] int UnregisterControlChangeNotify(IntPtr notify);
            [PreserveSig] int GetChannelCount(out uint count);
            [PreserveSig] int SetMasterVolumeLevel(float db, ref Guid ctx);
            [PreserveSig] int SetMasterVolumeLevelScalar(float level, ref Guid ctx);
            [PreserveSig] int GetMasterVolumeLevel(out float db);
            [PreserveSig] int GetMasterVolumeLevelScalar(out float level);
            [PreserveSig] int SetChannelVolumeLevel(uint channel, float db, ref Guid ctx);
            [PreserveSig] int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid ctx);
            [PreserveSig] int GetChannelVolumeLevel(uint channel, out float db);
            [PreserveSig] int GetChannelVolumeLevelScalar(uint channel, out float level);
            [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid ctx);
            [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
        }

        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDevice
        {
            [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        }

        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceEnumerator
        {
            [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
            [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        }

        [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
        class MMDeviceEnumerator { }

        /** flow 0 : sortie (haut-parleurs), 1 : entrée (micro). null : aucun appareil. */
        static IAudioEndpointVolume Endpoint(int flow)
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            IMMDevice device;
            if (enumerator.GetDefaultAudioEndpoint(flow, 0, out device) != 0 || device == null) return null;
            Guid iid = typeof(IAudioEndpointVolume).GUID;
            object o;
            if (device.Activate(ref iid, 23, IntPtr.Zero, out o) != 0) return null; // CLSCTX_ALL
            return (IAudioEndpointVolume)o;
        }

        static object Audio(Dictionary<string, object> req)
        {
            Guid ctx = Guid.Empty;
            var output = Endpoint(0);
            var input = Endpoint(1);
            if (output != null)
            {
                if (Has(req, "output")) output.SetMasterVolumeLevelScalar(Math.Max(0, Math.Min(100, Int(req, "output", 0))) / 100f, ref ctx);
                if (Has(req, "muted")) output.SetMute(Convert.ToBoolean(req["muted"]), ref ctx);
            }
            if (input != null && Has(req, "micMuted")) input.SetMute(Convert.ToBoolean(req["micMuted"]), ref ctx);

            var res = new Dictionary<string, object>();
            float level = 0;
            bool muted = false;
            if (output != null && output.GetMasterVolumeLevelScalar(out level) == 0)
            {
                output.GetMute(out muted);
                res["output"] = (int)Math.Round(level * 100);
            }
            else res["output"] = null;
            res["muted"] = muted;
            float mic = 0;
            bool micMuted = true;
            if (input != null)
            {
                input.GetMasterVolumeLevelScalar(out mic);
                input.GetMute(out micMuted);
            }
            res["input"] = (int)Math.Round(mic * 100);
            res["micMuted"] = micMuted;
            return res;
        }
    }
}
