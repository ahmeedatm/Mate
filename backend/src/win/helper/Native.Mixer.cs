using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;

namespace Companion
{
    public static partial class Native
    {
        // --- Mélangeur (onglet Son du deck) : sortie générale, micro et une tranche par appli qui a une session audio

        /** Nom lisible d'un processus (appli du menu Démarrer, sinon description du programme), gardé par numéro de processus. */
        static readonly Dictionary<uint, string> mixerNames = new Dictionary<uint, string>();

        static string MixerName(uint pid, string process)
        {
            string name;
            if (mixerNames.TryGetValue(pid, out name)) return name;
            string exe = ExePath(pid);
            name = StartAppOf(null, exe, StartMatches());
            if (name == null && exe != null)
            {
                try { var vi = FileVersionInfo.GetVersionInfo(exe); name = vi.FileDescription; if (string.IsNullOrWhiteSpace(name)) name = vi.ProductName; }
                catch (Exception) { }
                if (string.IsNullOrWhiteSpace(name)) name = Path.GetFileNameWithoutExtension(exe);
            }
            if (mixerNames.Count > 200) mixerNames.Clear();
            return mixerNames[pid] = string.IsNullOrWhiteSpace(name) ? process : name;
        }

        static float EndpointPeak(int flow)
        {
            var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
            IMMDevice device;
            if (enumerator.GetDefaultAudioEndpoint(flow, 0, out device) != 0 || device == null) return 0;
            Guid iid = typeof(IAudioMeterInformation).GUID;
            object o;
            float peak;
            if (device.Activate(ref iid, 23, IntPtr.Zero, out o) != 0 || ((IAudioMeterInformation)o).GetPeakValue(out peak) != 0) return 0;
            return peak;
        }

        static Dictionary<string, object> Channel(string id, string name, float? volume, bool muted, float peak)
        {
            var c = new Dictionary<string, object>();
            c["id"] = id;
            c["name"] = name;
            c["volume"] = volume.HasValue ? (object)(int)Math.Round(volume.Value * 100) : null;
            c["muted"] = muted;
            c["level"] = (int)Math.Round(peak * 100);
            return c;
        }

        /**
         * Lit le mélangeur ; avec `id` ("master", "mic", ou le nom simplifié du processus d'une appli, "system" pour les
         * sons de Windows), applique d'abord `volume` (0-100) et `muted` à cette tranche (à toutes les sessions de l'appli).
         * Applis : une tranche par processus, sessions expirées ignorées, triées par nom.
         */
        static object Mixer(Dictionary<string, object> req)
        {
            Guid ctx = Guid.Empty;
            string target = Str(req, "id");
            bool hasVolume = Has(req, "volume"), hasMuted = Has(req, "muted");
            float wanted = Math.Max(0, Math.Min(100, Int(req, "volume", 0))) / 100f;

            var res = new Dictionary<string, object>();
            for (int flow = 0; flow < 2; flow++)
            {
                string id = flow == 0 ? "master" : "mic";
                var endpoint = Endpoint(flow);
                float level = 0;
                bool muted = flow == 1;
                float? volume = null;
                if (endpoint != null)
                {
                    if (target == id && hasVolume) endpoint.SetMasterVolumeLevelScalar(wanted, ref ctx);
                    if (target == id && hasMuted) endpoint.SetMute(Convert.ToBoolean(req["muted"]), ref ctx);
                    if (endpoint.GetMasterVolumeLevelScalar(out level) == 0) volume = level;
                    endpoint.GetMute(out muted);
                }
                res[id] = Channel(id, id, volume, muted, endpoint != null ? EndpointPeak(flow) : 0);
            }

            var processNames = new Dictionary<uint, string>();
            var apps = new Dictionary<string, Dictionary<string, object>>();
            int state;
            foreach (var session in AudioSessions(0))
            {
                string process = SessionProcess(session, processNames, out state);
                if (state == 2) continue;
                bool system = process.Length == 0;
                string id = system ? "system" : process;
                var volume = session as ISimpleAudioVolume;
                if (volume != null && target == id)
                {
                    if (hasVolume) volume.SetMasterVolume(wanted, ref ctx);
                    if (hasMuted) volume.SetMute(Convert.ToBoolean(req["muted"]), ref ctx);
                }
                float peak = 0, level = 0;
                bool muted = false;
                var meter = session as IAudioMeterInformation;
                if (meter != null) meter.GetPeakValue(out peak);

                Dictionary<string, object> channel;
                if (apps.TryGetValue(id, out channel))
                {
                    channel["level"] = Math.Max((int)channel["level"], (int)Math.Round(peak * 100));
                    continue;
                }
                if (volume != null)
                {
                    volume.GetMasterVolume(out level);
                    volume.GetMute(out muted);
                }
                uint pid;
                string name = system ? "" : session.GetProcessId(out pid) == 0 ? MixerName(pid, process) : process;
                apps[id] = Channel(id, name, volume != null ? (float?)level : null, muted, peak);
            }
            res["apps"] = apps.Values.OrderBy(c => (string)c["id"] == "system" ? 1 : 0).ThenBy(c => (string)c["name"], StringComparer.OrdinalIgnoreCase).ToList();
            return res;
        }
    }
}
