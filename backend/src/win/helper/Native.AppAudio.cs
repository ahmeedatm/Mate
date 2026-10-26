using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;

namespace Companion
{
    public static partial class Native
    {
        // --- Son d'une appli (mélangeur de volume) : volume, sourdine et niveau sonore de ses sessions audio

        [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceCollection
        {
            [PreserveSig] int GetCount(out uint count);
            [PreserveSig] int Item(uint index, out IMMDevice device);
        }

        [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioSessionManager2
        {
            [PreserveSig] int GetAudioSessionControl(IntPtr sessionGuid, uint flags, out IntPtr control);
            [PreserveSig] int GetSimpleAudioVolume(IntPtr sessionGuid, uint flags, out IntPtr volume);
            [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator sessions);
        }

        [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioSessionEnumerator
        {
            [PreserveSig] int GetCount(out int count);
            [PreserveSig] int GetSession(int index, out IAudioSessionControl2 session);
        }

        /** Méthodes d'IAudioSessionControl puis d'IAudioSessionControl2, dans l'ordre (seule GetProcessId sert). */
        [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioSessionControl2
        {
            [PreserveSig] int GetState(out int state);
            [PreserveSig] int GetDisplayName(out IntPtr name);
            [PreserveSig] int SetDisplayName(IntPtr name, ref Guid ctx);
            [PreserveSig] int GetIconPath(out IntPtr path);
            [PreserveSig] int SetIconPath(IntPtr path, ref Guid ctx);
            [PreserveSig] int GetGroupingParam(out Guid group);
            [PreserveSig] int SetGroupingParam(ref Guid group, ref Guid ctx);
            [PreserveSig] int RegisterAudioSessionNotification(IntPtr client);
            [PreserveSig] int UnregisterAudioSessionNotification(IntPtr client);
            [PreserveSig] int GetSessionIdentifier(out IntPtr id);
            [PreserveSig] int GetSessionInstanceIdentifier(out IntPtr id);
            [PreserveSig] int GetProcessId(out uint pid);
        }

        [ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface ISimpleAudioVolume
        {
            [PreserveSig] int SetMasterVolume(float level, ref Guid ctx);
            [PreserveSig] int GetMasterVolume(out float level);
            [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid ctx);
            [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
        }

        [ComImport, Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioMeterInformation
        {
            [PreserveSig] int GetPeakValue(out float peak);
        }

        /**
         * Sessions audio de tous les appareils actifs (flow 0 : sorties, une appli peut jouer ailleurs que sur la
         * sortie par défaut ; 1 : micros).
         */
        static IEnumerable<IAudioSessionControl2> AudioSessions(int flow)
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            IMMDeviceCollection devices;
            if (enumerator.EnumAudioEndpoints(flow, 1, out devices) != 0 || devices == null) yield break; // DEVICE_STATE_ACTIVE
            uint count;
            if (devices.GetCount(out count) != 0) yield break;
            Guid iid = typeof(IAudioSessionManager2).GUID;
            for (uint i = 0; i < count; i++)
            {
                IMMDevice device;
                if (devices.Item(i, out device) != 0 || device == null) continue;
                object o;
                if (device.Activate(ref iid, 23, IntPtr.Zero, out o) != 0) continue;
                IAudioSessionEnumerator sessions;
                if (((IAudioSessionManager2)o).GetSessionEnumerator(out sessions) != 0 || sessions == null) continue;
                int n;
                if (sessions.GetCount(out n) != 0) continue;
                for (int j = 0; j < n; j++)
                {
                    IAudioSessionControl2 session;
                    if (sessions.GetSession(j, out session) == 0 && session != null) yield return session;
                }
            }
        }

        static string Simple(string s)
        {
            return Regex.Replace((s ?? "").ToLowerInvariant(), "[^a-z0-9]", "");
        }

        /** Nom simplifié du processus d'une session ("discord"), "" pour les sons système ; `state` : 0 inactive, 1 active, 2 expirée. */
        static string SessionProcess(IAudioSessionControl2 session, Dictionary<uint, string> cache, out int state)
        {
            uint pid;
            if (session.GetState(out state) != 0) state = 2;
            if (session.GetProcessId(out pid) != 0 || pid == 0) return "";
            string name;
            if (!cache.TryGetValue(pid, out name))
            {
                try { name = Simple(Process.GetProcessById((int)pid).ProcessName); }
                catch (Exception) { name = ""; }
                cache[pid] = name;
            }
            return name;
        }

        /**
         * Sessions des processus dont le nom contient l'un des `names` ("Discord" : Discord.exe, DiscordPTB.exe).
         * Applique `volume` (0-100) et `muted` à toutes, puis renvoie l'état de la première et le niveau le plus fort.
         * `capturing` : l'appli enregistre le micro en ce moment, c'est-à-dire qu'elle est en appel.
         */
        static object AppAudio(Dictionary<string, object> req)
        {
            var wanted = StrList(req, "names").Select(Simple).Where(n => n.Length >= 3).ToList();
            Guid ctx = Guid.Empty;
            var processNames = new Dictionary<uint, string>();
            bool found = false, muted = false, capturing = false;
            float level = 0, peak = 0;
            int state;
            foreach (var session in AudioSessions(0))
            {
                string name = SessionProcess(session, processNames, out state);
                if (state == 2 || name.Length == 0 || !wanted.Any(w => name.Contains(w))) continue;

                var volume = session as ISimpleAudioVolume;
                if (volume != null)
                {
                    if (Has(req, "volume")) volume.SetMasterVolume(Math.Max(0, Math.Min(100, Int(req, "volume", 0))) / 100f, ref ctx);
                    if (Has(req, "muted")) volume.SetMute(Convert.ToBoolean(req["muted"]), ref ctx);
                    if (!found)
                    {
                        volume.GetMasterVolume(out level);
                        volume.GetMute(out muted);
                    }
                }
                var meter = session as IAudioMeterInformation;
                float p;
                if (meter != null && meter.GetPeakValue(out p) == 0) peak = Math.Max(peak, p);
                found = true;
            }
            foreach (var session in AudioSessions(1))
            {
                string name = SessionProcess(session, processNames, out state);
                if (state == 1 && name.Length > 0 && wanted.Any(w => name.Contains(w))) capturing = true;
            }
            var res = new Dictionary<string, object>();
            res["running"] = found;
            res["capturing"] = capturing;
            res["volume"] = found ? (object)(int)Math.Round(level * 100) : null;
            res["muted"] = muted;
            res["level"] = (int)Math.Round(peak * 100);
            return res;
        }
    }
}
