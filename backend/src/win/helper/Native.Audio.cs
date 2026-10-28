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

        /**
         * Tous les micros actifs : couper « le micro » de l'ordinateur les coupe tous, car une appli (Discord, Teams)
         * peut utiliser un autre micro que celui par défaut (casque, webcam, périphérique de communication).
         */
        static List<IAudioEndpointVolume> Mics()
        {
            var mics = new List<IAudioEndpointVolume>();
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            IMMDeviceCollection devices;
            if (enumerator.EnumAudioEndpoints(1, 1, out devices) != 0 || devices == null) return mics; // DEVICE_STATE_ACTIVE
            uint count;
            if (devices.GetCount(out count) != 0) return mics;
            Guid iid = typeof(IAudioEndpointVolume).GUID;
            for (uint i = 0; i < count; i++)
            {
                IMMDevice device;
                object o;
                if (devices.Item(i, out device) == 0 && device != null && device.Activate(ref iid, 23, IntPtr.Zero, out o) == 0)
                    mics.Add((IAudioEndpointVolume)o);
            }
            return mics;
        }

        static void SetMicsMuted(bool mute)
        {
            Guid ctx = Guid.Empty;
            foreach (var mic in Mics()) mic.SetMute(mute, ref ctx);
        }

        /** Coupé seulement si tous les micros actifs le sont (aucun micro : coupé). */
        static bool MicsMuted()
        {
            foreach (var mic in Mics())
            {
                bool muted;
                if (mic.GetMute(out muted) == 0 && !muted) return false;
            }
            return true;
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
            if (Has(req, "micMuted")) SetMicsMuted(Convert.ToBoolean(req["micMuted"]));

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
            if (input != null) input.GetMasterVolumeLevelScalar(out mic);
            res["input"] = (int)Math.Round(mic * 100);
            res["micMuted"] = MicsMuted();
            return res;
        }
    }
}
