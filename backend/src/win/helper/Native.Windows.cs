using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Management;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace Mate
{
    public static partial class Native
    {
        // --- Fenêtres et écrans

        [StructLayout(LayoutKind.Sequential)]
        struct RECT
        {
            public int Left, Top, Right, Bottom;
            public int W { get { return Right - Left; } }
            public int H { get { return Bottom - Top; } }
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct MONITORINFOEX
        {
            public int cbSize;
            public RECT rcMonitor;
            public RECT rcWork;
            public uint dwFlags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szDevice;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct DISPLAY_DEVICE
        {
            public int cb;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceString;
            public int StateFlags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceID;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceKey;
        }

        delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
        delegate bool MonitorEnumProc(IntPtr hMonitor, IntPtr hdc, IntPtr rect, IntPtr data);

        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
        [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr value);
        [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc cb, IntPtr lParam);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
        [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
        [DllImport("user32.dll")] static extern bool IsZoomed(IntPtr h);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int max);
        [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int max);
        [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint cmd);
        [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int index);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
        [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr h);
        [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a, uint b, bool attach);
        [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
        [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
        [DllImport("user32.dll")] static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr clip, MonitorEnumProc cb, IntPtr data);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFOEX info);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern bool EnumDisplayDevices(string device, uint index, ref DISPLAY_DEVICE dd, uint flags);
        [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out int value, int size);
        [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out RECT value, int size);
        [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder s, ref int size);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
        [DllImport("shell32.dll")] static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid id, uint flags, IntPtr token, out IntPtr path);

        class ScreenInfo
        {
            public IntPtr Handle;
            public int Id;
            public string Name;
            public bool Main;
            public RECT Frame;
            public RECT Work;
        }

        class Win
        {
            public IntPtr H;
            public string Title;
            public uint Pid;
        }

        static List<ScreenInfo> Screens()
        {
            var list = new List<ScreenInfo>();
            var names = MonitorNames();
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, delegate (IntPtr hm, IntPtr dc, IntPtr r, IntPtr d)
            {
                var mi = new MONITORINFOEX();
                mi.cbSize = Marshal.SizeOf(typeof(MONITORINFOEX));
                if (!GetMonitorInfo(hm, ref mi)) return true;
                var s = new ScreenInfo();
                s.Handle = hm;
                s.Frame = mi.rcMonitor;
                s.Work = mi.rcWork;
                s.Main = (mi.dwFlags & 1) != 0;
                var m = Regex.Match(mi.szDevice ?? "", "(\\d+)$");
                s.Id = m.Success ? int.Parse(m.Groups[1].Value) : list.Count + 1;
                s.Name = MonitorName(mi.szDevice, names);
                list.Add(s);
                return true;
            }, IntPtr.Zero);
            return list;
        }

        /** Noms des moniteurs (EDID, ex. "BenQ GW2480") par identifiant d'instance, lus dans WMI. */
        static Dictionary<string, string> MonitorNames()
        {
            var names = new Dictionary<string, string>();
            try
            {
                using (var searcher = new ManagementObjectSearcher("root\\wmi", "SELECT InstanceName, UserFriendlyName FROM WmiMonitorID"))
                {
                    foreach (ManagementObject mo in searcher.Get())
                    {
                        var chars = mo["UserFriendlyName"] as ushort[];
                        string instance = Convert.ToString(mo["InstanceName"]);
                        if (chars == null || instance == null) continue;
                        var sb = new StringBuilder();
                        foreach (ushort c in chars) { if (c == 0) break; sb.Append((char)c); }
                        if (sb.Length > 0) names[Regex.Replace(instance.ToLowerInvariant(), "_\\d+$", "")] = sb.ToString().Trim();
                    }
                }
            }
            catch (Exception) { }
            return names;
        }

        static string MonitorName(string device, Dictionary<string, string> names)
        {
            var dd = new DISPLAY_DEVICE();
            dd.cb = Marshal.SizeOf(typeof(DISPLAY_DEVICE));
            // EDD_GET_DEVICE_INTERFACE_NAME : DeviceID = \\?\DISPLAY#BNQ7F4D#5&2b1f&0&UID4353#{…}
            if (EnumDisplayDevices(device, 0, ref dd, 1) && dd.DeviceID != null)
            {
                var parts = dd.DeviceID.Split('#');
                if (parts.Length >= 3)
                {
                    string key = ("display\\" + parts[1] + "\\" + parts[2]).ToLowerInvariant();
                    string name;
                    if (names.TryGetValue(key, out name)) return name;
                }
                if (!string.IsNullOrEmpty(dd.DeviceString) && !Regex.IsMatch(dd.DeviceString, "(?i)generic|g[ée]n[ée]rique|pnp")) return dd.DeviceString;
            }
            return "Écran";
        }

        static ScreenInfo ScreenOf(IntPtr h, List<ScreenInfo> screens)
        {
            IntPtr hm = MonitorFromWindow(h, 2);
            return screens.FirstOrDefault(s => s.Handle == hm) ?? screens.FirstOrDefault(s => s.Main) ?? screens[0];
        }

        /** Fenêtres principales visibles, de la plus en avant à la plus en arrière (ordre de EnumWindows). */
        static List<Win> TopWindows()
        {
            var list = new List<Win>();
            EnumWindows(delegate (IntPtr h, IntPtr l)
            {
                if (!IsWindowVisible(h) || GetWindow(h, 4) != IntPtr.Zero) return true; // GW_OWNER : boîtes de dialogue
                if ((GetWindowLong(h, -20) & 0x80) != 0) return true; // WS_EX_TOOLWINDOW
                int cloaked;
                // Masquée par le système : autre bureau virtuel, appli du Store en arrière-plan.
                if (DwmGetWindowAttribute(h, 14, out cloaked, 4) == 0 && cloaked != 0) return true;
                int len = GetWindowTextLength(h);
                if (len == 0) return true;
                var cls = new StringBuilder(256);
                GetClassName(h, cls, 256);
                string c = cls.ToString();
                if (c == "Progman" || c == "WorkerW" || c == "Shell_TrayWnd" || c == "Shell_SecondaryTrayWnd") return true;
                var title = new StringBuilder(len + 1);
                GetWindowText(h, title, title.Capacity);
                uint pid;
                GetWindowThreadProcessId(h, out pid);
                var w = new Win();
                w.H = h;
                w.Title = title.ToString();
                w.Pid = pid;
                list.Add(w);
                return true;
            }, IntPtr.Zero);
            return list;
        }

        // Identifiant d'appli (AppUserModelID) d'une fenêtre : celui du menu Démarrer pour les applis du Store,
        // l'Explorateur, et les applis qui le déclarent.

        [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IPropertyStore
        {
            [PreserveSig] int GetCount(out uint count);
            [PreserveSig] int GetAt(uint index, out PROPERTYKEY key);
            [PreserveSig] int GetValue(ref PROPERTYKEY key, out PROPVARIANT value);
        }

        [StructLayout(LayoutKind.Sequential, Pack = 4)]
        struct PROPERTYKEY { public Guid fmtid; public uint pid; }

        // 24 octets comme en 64 bits ; seuls vt et le pointeur de chaîne servent.
        [StructLayout(LayoutKind.Explicit)]
        struct PROPVARIANT
        {
            [FieldOffset(0)] public ushort vt;
            [FieldOffset(8)] public IntPtr p;
            [FieldOffset(16)] public long pad;
        }

        [DllImport("shell32.dll")]
        static extern int SHGetPropertyStoreForWindow(IntPtr h, ref Guid iid, [MarshalAs(UnmanagedType.Interface)] out IPropertyStore store);

        [DllImport("ole32.dll")]
        static extern int PropVariantClear(ref PROPVARIANT value);

        static string Aumid(IntPtr h)
        {
            IPropertyStore store = null;
            try
            {
                Guid iid = typeof(IPropertyStore).GUID;
                if (SHGetPropertyStoreForWindow(h, ref iid, out store) != 0 || store == null) return null;
                var key = new PROPERTYKEY();
                key.fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3");
                key.pid = 5;
                PROPVARIANT v;
                if (store.GetValue(ref key, out v) != 0) return null;
                string s = v.vt == 31 ? Marshal.PtrToStringUni(v.p) : null; // VT_LPWSTR
                PropVariantClear(ref v);
                return s;
            }
            catch (Exception) { return null; }
            finally { if (store != null) Marshal.ReleaseComObject(store); }
        }

        static string ExePath(uint pid)
        {
            IntPtr h = OpenProcess(0x1000, false, pid); // PROCESS_QUERY_LIMITED_INFORMATION
            if (h == IntPtr.Zero) return null;
            try
            {
                var sb = new StringBuilder(1024);
                int size = sb.Capacity;
                return QueryFullProcessImageName(h, 0, sb, ref size) ? sb.ToString() : null;
            }
            finally { CloseHandle(h); }
        }

        static string Norm(string s)
        {
            if (s == null) return "";
            s = Regex.Replace(s, "\\(.*?\\)", "");
            return Regex.Replace(s.ToLowerInvariant(), "[^\\p{L}\\p{N}]", "");
        }

        /** Ce qui permet de reconnaître les fenêtres d'une appli du menu Démarrer. */
        class AppMatch
        {
            public string Aumid;
            public string Exe;
            public string Folder;
            public string Package;
            public string Publisher;
            public List<string> Names = new List<string>();
        }

        static AppMatch MatchFor(string id, List<string> names)
        {
            var m = new AppMatch();
            m.Aumid = id;
            foreach (var n in names) { string k = Norm(n); if (k.Length > 0) m.Names.Add(k); }
            if (string.IsNullOrEmpty(id)) return m;
            int bang = id.IndexOf('!');
            if (bang > 0)
            {
                // Appli empaquetée (Store, nouveau Teams) : Famille_Editeur!App, installée dans WindowsApps\Famille_version_archi__Editeur.
                string family = id.Substring(0, bang);
                int us = family.LastIndexOf('_');
                if (us > 0) { m.Package = family.Substring(0, us).ToLowerInvariant(); m.Publisher = family.Substring(us + 1).ToLowerInvariant(); }
            }
            else
            {
                // Appli classique : {dossier connu}\chemin\appli.exe, ou chemin complet.
                var km = Regex.Match(id, "^(\\{[0-9A-Fa-f-]{36}\\})\\\\(.+\\.exe)$");
                if (km.Success)
                {
                    IntPtr p;
                    if (SHGetKnownFolderPath(new Guid(km.Groups[1].Value), 0, IntPtr.Zero, out p) == 0)
                    {
                        m.Exe = Path.Combine(Marshal.PtrToStringUni(p), km.Groups[2].Value);
                        Marshal.FreeCoTaskMem(p);
                    }
                }
                else if (Regex.IsMatch(id, "^[A-Za-z]:\\\\.+\\.exe$")) m.Exe = id;
                else
                {
                    // Raccourci qui déclare son propre identifiant (Dofus 1 = com.dofus.d1elauncher, Ankama Launcher) :
                    // ses fenêtres n'ont pas cet identifiant, on les reconnaît au programme visé par le raccourci.
                    string target = LinkTarget(id);
                    if (target != null && target.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) m.Exe = target;
                    // Appli installée par Squirrel (Discord, Slack…) : le raccourci vise un lanceur (Discord\Discord.exe ou
                    // Update.exe), la fenêtre appartient à Discord\app-1.0.x\Discord.exe. Tout le dossier compte.
                    string dir = m.Exe != null ? Path.GetDirectoryName(m.Exe) : null;
                    if (dir != null && File.Exists(Path.Combine(dir, "Update.exe"))) m.Folder = dir.TrimEnd('\\') + "\\";
                }
            }
            return m;
        }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        static extern int SHGetPropertyStoreFromParsingName(string path, IntPtr bindCtx, int flags, ref Guid iid, [MarshalAs(UnmanagedType.Interface)] out IPropertyStore store);

        /** Programme visé par le raccourci d'une entrée du menu Démarrer (System.Link.TargetParsingPath), null si aucun. */
        static string LinkTarget(string id)
        {
            IPropertyStore store = null;
            try
            {
                Guid iid = typeof(IPropertyStore).GUID;
                if (SHGetPropertyStoreFromParsingName("shell:AppsFolder\\" + id, IntPtr.Zero, 0, ref iid, out store) != 0 || store == null) return null;
                var key = new PROPERTYKEY();
                key.fmtid = new Guid("B9B4B3FC-2B51-4A42-B5D8-324146AFCF25");
                key.pid = 2;
                PROPVARIANT v;
                if (store.GetValue(ref key, out v) != 0) return null;
                string s = v.vt == 31 ? Marshal.PtrToStringUni(v.p) : null; // VT_LPWSTR
                PropVariantClear(ref v);
                return string.IsNullOrEmpty(s) ? null : Environment.ExpandEnvironmentVariables(s);
            }
            catch (Exception) { return null; }
            finally { if (store != null) Marshal.ReleaseComObject(store); }
        }

        static bool IsMatch(Win w, AppMatch m, Dictionary<uint, string[]> procs)
        {
            string aumid = Aumid(w.H);
            if (aumid != null && m.Aumid != null && string.Equals(aumid, m.Aumid, StringComparison.OrdinalIgnoreCase)) return true;
            string[] info;
            if (!procs.TryGetValue(w.Pid, out info))
            {
                string exe = ExePath(w.Pid);
                string desc = null, product = null;
                if (exe != null)
                {
                    try
                    {
                        var vi = FileVersionInfo.GetVersionInfo(exe);
                        desc = vi.FileDescription;
                        product = vi.ProductName;
                    }
                    catch (Exception) { }
                }
                info = new string[] { exe, desc, product };
                procs[w.Pid] = info;
            }
            string path = info[0];
            if (path == null) return false;
            string lower = path.ToLowerInvariant();
            if (m.Exe != null && string.Equals(path, m.Exe, StringComparison.OrdinalIgnoreCase)) return true;
            if (m.Folder != null && path.StartsWith(m.Folder, StringComparison.OrdinalIgnoreCase)) return true;
            if (m.Package != null && lower.Contains("\\windowsapps\\" + m.Package + "_") && lower.Contains("__" + m.Publisher)) return true;
            // Une autre appli empaquetée ou au chemin connu : pas de repli sur le nom.
            if (m.Package != null || m.Exe != null) return false;
            string d = Norm(info[1]), p = Norm(info[2]), b = Norm(Path.GetFileNameWithoutExtension(path));
            foreach (var n in m.Names)
            {
                if (n == d || n == p || n == b) return true;
                // "Outlook" pour "Microsoft Outlook".
                if (n.Length >= 4 && d.Length > n.Length && d.EndsWith(n)) return true;
            }
            return false;
        }

        static object WindowsOp(Dictionary<string, object> req)
        {
            string action = Str(req, "action") ?? "list";
            var screens = Screens();
            var match = MatchFor(Str(req, "id"), StrList(req, "names"));
            var procs = new Dictionary<uint, string[]>();
            Func<List<Win>> mine = delegate { return TopWindows().Where(w => IsMatch(w, match, procs)).ToList(); };
            List<Win> wins = Has(req, "id") || match.Names.Count > 0 ? mine() : new List<Win>();

            if (action != "list" && wins.Count > 0)
            {
                List<Win> targets = wins;
                if (Has(req, "hwnd"))
                {
                    long hwnd = Convert.ToInt64(req["hwnd"]);
                    targets = wins.Where(w => w.H.ToInt64() == hwnd).ToList();
                    if (targets.Count == 0) throw new Exception("Fenêtre introuvable");
                }
                if (action == "place")
                {
                    int display = Int(req, "display", -1);
                    string layout = Str(req, "layout") ?? "keep";
                    foreach (var w in targets) Place(w.H, screens, display, layout);
                }
                // Fenêtres dans l'ordre de l'écran (la plus récente d'abord) : une visible plutôt qu'une réduite oubliée.
                Focus((targets.FirstOrDefault(w => !IsIconic(w.H)) ?? targets[0]).H);
                Thread.Sleep(150);
                wins = mine();
            }

            var res = new Dictionary<string, object>();
            res["running"] = wins.Count > 0;
            res["windows"] = wins.Select(w => (object)Describe(w, screens)).ToList();
            res["displays"] = DisplaysOf(screens);
            return res;
        }

        static Dictionary<string, object> Describe(Win w, List<ScreenInfo> screens)
        {
            var d = new Dictionary<string, object>();
            d["index"] = w.H.ToInt64();
            d["title"] = w.Title;
            d["minimized"] = IsIconic(w.H);
            d["fullscreen"] = false;
            d["display"] = ScreenOf(w.H, screens).Id;
            d["elsewhere"] = false;
            d["front"] = w.H == GetForegroundWindow();
            d["maximized"] = IsZoomed(w.H);
            return d;
        }

        static List<object> DisplaysOf(List<ScreenInfo> screens)
        {
            var displays = new List<object>();
            foreach (var s in screens)
            {
                var d = new Dictionary<string, object>();
                d["id"] = s.Id;
                d["name"] = s.Name;
                d["builtin"] = false;
                d["main"] = s.Main;
                var f = new Dictionary<string, object>();
                f["x"] = s.Frame.Left;
                f["y"] = s.Frame.Top;
                f["w"] = s.Frame.W;
                f["h"] = s.Frame.H;
                d["frame"] = f;
                displays.Add(d);
            }
            return displays;
        }
    }
}
