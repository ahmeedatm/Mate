using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;

namespace Mate
{
    public static partial class Native
    {
        // --- Widget Écrans : réglages de chaque moniteur. Luminosité, contraste, netteté, gains rouge / vert / bleu et
        // préréglage de couleur passent par DDC/CI (dxva2, codes VCP de la norme MCCS) ; résolution et fréquence par le
        // système (EnumDisplaySettings, ChangeDisplaySettingsEx).

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct PHYSICAL_MONITOR
        {
            public IntPtr Handle;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string Description;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct DEVMODE
        {
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
            public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
            public int dmFields;
            public int dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput;
            public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
            public short dmLogPixels;
            public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
            public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
        }

        [DllImport("dxva2.dll")] static extern bool GetNumberOfPhysicalMonitorsFromHMONITOR(IntPtr hMonitor, out uint count);
        [DllImport("dxva2.dll")] static extern bool GetPhysicalMonitorsFromHMONITOR(IntPtr hMonitor, uint count, [Out] PHYSICAL_MONITOR[] monitors);
        [DllImport("dxva2.dll")] static extern bool DestroyPhysicalMonitors(uint count, PHYSICAL_MONITOR[] monitors);
        [DllImport("dxva2.dll")] static extern bool GetVCPFeatureAndVCPFeatureReply(IntPtr monitor, byte code, IntPtr type, out uint current, out uint max);
        [DllImport("dxva2.dll")] static extern bool SetVCPFeature(IntPtr monitor, byte code, uint value);
        [DllImport("dxva2.dll")] static extern bool GetCapabilitiesStringLength(IntPtr monitor, out uint length);
        [DllImport("dxva2.dll")] static extern bool CapabilitiesRequestAndCapabilitiesReply(IntPtr monitor, StringBuilder caps, uint length);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplaySettings(string device, int mode, ref DEVMODE dm);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string device, ref DEVMODE dm, IntPtr hwnd, uint flags, IntPtr param);

        const byte VcpPreset = 0x14;

        /** Réglages continus lus et écrits par DDC/CI, par nom envoyé au backend. */
        static readonly Dictionary<string, byte> VcpControls = new Dictionary<string, byte>
        {
            { "brightness", 0x10 }, { "contrast", 0x12 }, { "sharpness", 0x87 }, { "red", 0x16 }, { "green", 0x18 }, { "blue", 0x1A },
        };

        /**
         * Codes VCP annoncés par chaque moniteur (chaîne de capacités, lente : une à deux secondes), gardés pour la vie du
         * helper ; null : le moniteur ne l'a pas donnée, chaque code est alors essayé.
         */
        static readonly Dictionary<string, Dictionary<byte, List<uint>>> CapsCache = new Dictionary<string, Dictionary<byte, List<uint>>>();

        static object Monitors()
        {
            return Screens().Select(s =>
            {
                var device = DeviceOf(s.Id);
                var m = new Dictionary<string, object>
                {
                    { "id", device }, { "name", s.Name }, { "primary", s.Main },
                };
                AddModes(m, device);
                WithPhysical(s.Handle, h => AddDdc(m, h, device));
                if (!m.ContainsKey("ddc")) m["ddc"] = false;
                return m;
            }).ToList();
        }

        static object SetMonitor(Dictionary<string, object> req)
        {
            var device = Str(req, "id");
            var screen = Screens().FirstOrDefault(s => DeviceOf(s.Id) == device);
            if (screen == null) throw new ArgumentException("Écran introuvable : il a peut-être été débranché.");
            if (Has(req, "width"))
            {
                var dm = NewDevMode();
                if (!EnumDisplaySettings(device, -1, ref dm)) throw new InvalidOperationException("Mode de l'écran illisible.");
                dm.dmPelsWidth = Int(req, "width", dm.dmPelsWidth);
                dm.dmPelsHeight = Int(req, "height", dm.dmPelsHeight);
                dm.dmDisplayFrequency = Int(req, "hz", dm.dmDisplayFrequency);
                dm.dmFields = 0x80000 | 0x100000 | 0x400000;
                // CDS_UPDATEREGISTRY : gardé après un redémarrage, comme depuis les paramètres d'affichage.
                if (ChangeDisplaySettingsEx(device, ref dm, IntPtr.Zero, 1, IntPtr.Zero) != 0)
                    throw new InvalidOperationException("L'écran a refusé ce mode.");
            }
            var control = Str(req, "control");
            if (control != null)
            {
                byte code;
                if (control == "preset") code = VcpPreset;
                else if (!VcpControls.TryGetValue(control, out code)) throw new ArgumentException("Réglage inconnu : " + control);
                var value = (uint)Int(req, "value", 0);
                var done = false;
                WithPhysical(screen.Handle, h => { done = SetVCPFeature(h, code, value); return done; });
                if (!done) throw new InvalidOperationException("L'écran ne répond pas en DDC/CI.");
            }
            return Monitors();
        }

        static string DeviceOf(int id)
        {
            return "\\\\.\\DISPLAY" + id;
        }

        static DEVMODE NewDevMode()
        {
            var dm = new DEVMODE();
            dm.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE));
            return dm;
        }

        /** Mode actuel, puis les résolutions possibles (la plus grande d'abord) et leurs fréquences. */
        static void AddModes(Dictionary<string, object> m, string device)
        {
            var dm = NewDevMode();
            if (EnumDisplaySettings(device, -1, ref dm))
            {
                m["width"] = dm.dmPelsWidth;
                m["height"] = dm.dmPelsHeight;
                m["hz"] = dm.dmDisplayFrequency;
            }
            var modes = new Dictionary<string, SortedSet<int>>();
            var sizes = new List<int[]>();
            for (var i = 0; ; i++)
            {
                var mode = NewDevMode();
                if (!EnumDisplaySettings(device, i, ref mode)) break;
                if (mode.dmBitsPerPel < 32 || mode.dmDisplayFrequency <= 1) continue;
                var key = mode.dmPelsWidth + "x" + mode.dmPelsHeight;
                SortedSet<int> hz;
                if (!modes.TryGetValue(key, out hz))
                {
                    modes[key] = hz = new SortedSet<int>();
                    sizes.Add(new[] { mode.dmPelsWidth, mode.dmPelsHeight });
                }
                hz.Add(mode.dmDisplayFrequency);
            }
            m["modes"] = sizes
                .OrderByDescending(s => (long)s[0] * s[1])
                .Take(8)
                .Select(s => new Dictionary<string, object>
                {
                    { "width", s[0] }, { "height", s[1] }, { "hz", modes[s[0] + "x" + s[1]].Reverse().ToList() },
                })
                .ToList();
        }

        /** Appelle `use` sur chaque moniteur physique de l'écran jusqu'à ce qu'il rende vrai, puis libère les poignées. */
        static void WithPhysical(IntPtr hMonitor, Func<IntPtr, bool> use)
        {
            uint count;
            if (!GetNumberOfPhysicalMonitorsFromHMONITOR(hMonitor, out count) || count == 0) return;
            var list = new PHYSICAL_MONITOR[count];
            if (!GetPhysicalMonitorsFromHMONITOR(hMonitor, count, list)) return;
            try
            {
                foreach (var p in list) if (use(p.Handle)) return;
            }
            finally
            {
                DestroyPhysicalMonitors(count, list);
            }
        }

        static bool AddDdc(Dictionary<string, object> m, IntPtr h, string device)
        {
            var caps = Capabilities(h, device);
            var ddc = false;
            foreach (var c in VcpControls)
            {
                if (caps != null && !caps.ContainsKey(c.Value)) continue;
                uint current, max;
                if (!GetVCPFeatureAndVCPFeatureReply(h, c.Value, IntPtr.Zero, out current, out max) || max == 0) continue;
                ddc = true;
                m[c.Key] = new Dictionary<string, object> { { "value", current }, { "max", max } };
            }
            uint preset, presetMax;
            if ((caps == null || caps.ContainsKey(VcpPreset)) && GetVCPFeatureAndVCPFeatureReply(h, VcpPreset, IntPtr.Zero, out preset, out presetMax))
            {
                ddc = true;
                List<uint> options;
                if (caps == null || !caps.TryGetValue(VcpPreset, out options)) options = new List<uint>();
                m["preset"] = new Dictionary<string, object> { { "value", preset & 0xFF }, { "options", options } };
            }
            m["ddc"] = ddc;
            return ddc;
        }

        static Dictionary<byte, List<uint>> Capabilities(IntPtr h, string device)
        {
            Dictionary<byte, List<uint>> caps;
            if (CapsCache.TryGetValue(device, out caps)) return caps;
            uint length;
            if (GetCapabilitiesStringLength(h, out length) && length > 0)
            {
                var sb = new StringBuilder((int)length);
                if (CapabilitiesRequestAndCapabilitiesReply(h, sb, length)) caps = ParseVcp(sb.ToString());
            }
            CapsCache[device] = caps;
            return caps;
        }

        /** Codes de la section vcp(...) d'une chaîne de capacités MCCS, avec leurs valeurs permises : « vcp(10 12 14(05 08 0B)) ». */
        internal static Dictionary<byte, List<uint>> ParseVcp(string caps)
        {
            var start = caps.IndexOf("vcp(", StringComparison.OrdinalIgnoreCase);
            if (start < 0) return null;
            var result = new Dictionary<byte, List<uint>>();
            var i = start + 4;
            var depth = 1;
            byte? last = null;
            while (i < caps.Length && depth > 0)
            {
                var ch = caps[i];
                if (ch == '(') { depth++; i++; continue; }
                if (ch == ')') { depth--; i++; continue; }
                var match = Regex.Match(caps.Substring(i), "^[0-9A-Fa-f]{2}");
                if (!match.Success) { i++; continue; }
                var value = Convert.ToUInt32(match.Value, 16);
                if (depth == 1)
                {
                    last = (byte)value;
                    result[last.Value] = new List<uint>();
                }
                else if (depth == 2 && last.HasValue) result[last.Value].Add(value);
                i += 2;
            }
            return result;
        }
    }
}
