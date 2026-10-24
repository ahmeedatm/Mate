// Pilotage de Windows pour le compagnon : compilé une fois par helper.ps1 (Add-Type, Windows PowerShell 5.1),
// appelé ligne par ligne par src/win/helper.ts. C# 5 seulement (compilateur du .NET Framework) : pas
// d'interpolation de chaînes, pas de ?., pas de membres "=>".
//
// Requête : {"op": "...", ...} ; réponse : {"ok": true, "result": ...} ou {"ok": false, "error": "..."}.

using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Management;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

namespace Companion
{
    public static class Native
    {
        static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

        public static void Init()
        {
            // Coordonnées en pixels réels sur tous les écrans, même à 125 % ou 150 % d'échelle.
            try { SetProcessDpiAwarenessContext(new IntPtr(-4)); }
            catch (Exception) { try { SetProcessDPIAware(); } catch (Exception) { } }
        }

        public static string Handle(string line)
        {
            var res = new Dictionary<string, object>();
            try
            {
                var req = Json.Deserialize<Dictionary<string, object>>(line);
                res["result"] = Dispatch(Str(req, "op"), req);
                res["ok"] = true;
            }
            catch (Exception e)
            {
                while (e.InnerException != null) e = e.InnerException;
                res["ok"] = false;
                res["error"] = e.Message;
            }
            return Json.Serialize(res);
        }

        static object Dispatch(string op, Dictionary<string, object> req)
        {
            switch (op)
            {
                case "ping": return true;
                case "apps": return Apps();
                case "launch": Process.Start("explorer.exe", "\"shell:AppsFolder\\" + Str(req, "id") + "\""); return true;
                case "open": Open(Str(req, "target")); return true;
                case "icon": return AppIcon(Str(req, "id"), Str(req, "out"), Int(req, "size", 96));
                case "windows": return WindowsOp(req);
                case "keys": PressKeys(IntList(req, "mods"), Int(req, "vk", 0), Str(req, "ch")); return true;
                case "text": TypeText(Str(req, "text")); return true;
                case "audio": return Audio(req);
                case "appaudio": return AppAudio(req);
                case "system": SystemCommand(Str(req, "command")); return true;
                case "alert": Alert(Str(req, "title"), Str(req, "text")); return true;
                default: throw new ArgumentException("Opération inconnue : " + op);
            }
        }

        // --- Lecture des requêtes

        static string Str(Dictionary<string, object> d, string key)
        {
            object v;
            return d.TryGetValue(key, out v) && v != null ? Convert.ToString(v) : null;
        }

        static int Int(Dictionary<string, object> d, string key, int fallback)
        {
            object v;
            return d.TryGetValue(key, out v) && v != null ? Convert.ToInt32(v) : fallback;
        }

        static bool Has(Dictionary<string, object> d, string key)
        {
            object v;
            return d.TryGetValue(key, out v) && v != null;
        }

        static List<int> IntList(Dictionary<string, object> d, string key)
        {
            var list = new List<int>();
            object v;
            if (d.TryGetValue(key, out v) && v is IEnumerable) foreach (var x in (IEnumerable)v) list.Add(Convert.ToInt32(x));
            return list;
        }

        static List<string> StrList(Dictionary<string, object> d, string key)
        {
            var list = new List<string>();
            object v;
            if (d.TryGetValue(key, out v) && v is IEnumerable && !(v is string)) foreach (var x in (IEnumerable)v) if (x != null) list.Add(Convert.ToString(x));
            return list;
        }

        // --- Applis : dossier virtuel "Applications" (celui du menu Démarrer, applis classiques et du Store)

        static List<object> Apps()
        {
            var list = new List<object>();
            object shell = Activator.CreateInstance(Type.GetTypeFromProgID("Shell.Application"));
            try
            {
                object folder = Call(shell, "NameSpace", "shell:::{4234d49b-0245-4df3-b780-3893943456e1}");
                object items = Call(folder, "Items");
                int count = Convert.ToInt32(Get(items, "Count"));
                for (int i = 0; i < count; i++)
                {
                    object item = Call(items, "Item", i);
                    if (item == null) continue;
                    var d = new Dictionary<string, object>();
                    d["name"] = Convert.ToString(Get(item, "Name"));
                    d["id"] = Convert.ToString(Get(item, "Path"));
                    list.Add(d);
                    Marshal.ReleaseComObject(item);
                }
            }
            finally { Marshal.ReleaseComObject(shell); }
            return list;
        }

        static object Call(object o, string name, params object[] args)
        {
            return o.GetType().InvokeMember(name, BindingFlags.InvokeMethod, null, o, args);
        }

        static object Get(object o, string name)
        {
            return o.GetType().InvokeMember(name, BindingFlags.GetProperty, null, o, null);
        }

        static void Open(string target)
        {
            var psi = new ProcessStartInfo(target);
            psi.UseShellExecute = true;
            Process.Start(psi);
        }

        // --- Icônes : image du Shell (celle du menu Démarrer), transparence comprise

        [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IShellItemImageFactory
        {
            [PreserveSig] int GetImage(SIZE size, int flags, out IntPtr phbm);
        }

        [StructLayout(LayoutKind.Sequential)]
        struct SIZE { public int cx; public int cy; }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
        static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
            [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory factory);

        [DllImport("gdi32.dll")]
        static extern bool DeleteObject(IntPtr h);

        static bool AppIcon(string id, string outPath, int size)
        {
            IShellItemImageFactory factory;
            SHCreateItemFromParsingName("shell:AppsFolder\\" + id, IntPtr.Zero, typeof(IShellItemImageFactory).GUID, out factory);
            IntPtr hbmp;
            var sz = new SIZE();
            sz.cx = size;
            sz.cy = size;
            // SIIGBF_BIGGERSIZEOK | SIIGBF_ICONONLY
            int hr = factory.GetImage(sz, 0x1 | 0x4, out hbmp);
            Marshal.ReleaseComObject(factory);
            if (hr != 0 || hbmp == IntPtr.Zero) return false;
            try
            {
                using (Bitmap bmp = WithAlpha(hbmp)) bmp.Save(outPath, ImageFormat.Png);
                return true;
            }
            finally { DeleteObject(hbmp); }
        }

        /** Image.FromHbitmap perd la transparence : on recopie les octets (alpha prémultiplié) dans un bitmap PArgb. */
        static Bitmap WithAlpha(IntPtr hbmp)
        {
            using (Bitmap src = Image.FromHbitmap(hbmp))
            {
                int w = src.Width, h = src.Height;
                var rect = new Rectangle(0, 0, w, h);
                var px = new int[w * h];
                BitmapData sd = src.LockBits(rect, ImageLockMode.ReadOnly, src.PixelFormat);
                try
                {
                    for (int y = 0; y < h; y++) Marshal.Copy(new IntPtr(sd.Scan0.ToInt64() + (long)y * sd.Stride), px, y * w, w);
                }
                finally { src.UnlockBits(sd); }
                bool transparent = false, opaque = false;
                foreach (int p in px)
                {
                    int a = (p >> 24) & 0xff;
                    if (a < 255) transparent = true;
                    if (a > 0) opaque = true;
                }
                // Aucun octet alpha utile : icône ancienne sans transparence.
                if (!(transparent && opaque)) return new Bitmap(src);
                var dst = new Bitmap(w, h, PixelFormat.Format32bppPArgb);
                BitmapData dd = dst.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppPArgb);
                try
                {
                    for (int y = 0; y < h; y++) Marshal.Copy(px, y * w, new IntPtr(dd.Scan0.ToInt64() + (long)y * dd.Stride), w);
                }
                finally { dst.UnlockBits(dd); }
                return dst;
            }
        }

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
                Focus(targets[0].H);
                Thread.Sleep(150);
                wins = mine();
            }

            var windows = new List<object>();
            foreach (var w in wins)
            {
                var d = new Dictionary<string, object>();
                d["index"] = w.H.ToInt64();
                d["title"] = w.Title;
                d["minimized"] = IsIconic(w.H);
                d["fullscreen"] = false;
                d["display"] = ScreenOf(w.H, screens).Id;
                d["elsewhere"] = false;
                windows.Add(d);
            }
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
            var res = new Dictionary<string, object>();
            res["running"] = wins.Count > 0;
            res["windows"] = windows;
            res["displays"] = displays;
            return res;
        }

        /** Cadre visible (sans les bordures invisibles de Windows 10/11, qui laisseraient un jour entre deux moitiés). */
        static RECT Visual(IntPtr h)
        {
            RECT r;
            if (DwmGetWindowAttribute(h, 9, out r, Marshal.SizeOf(typeof(RECT))) == 0) return r; // DWMWA_EXTENDED_FRAME_BOUNDS
            GetWindowRect(h, out r);
            return r;
        }

        static void Move(IntPtr h, int x, int y, int w, int hgt)
        {
            RECT outer;
            GetWindowRect(h, out outer);
            RECT vis = Visual(h);
            int l = vis.Left - outer.Left, t = vis.Top - outer.Top, r = outer.Right - vis.Right, b = outer.Bottom - vis.Bottom;
            // Deux fois : en changeant d'écran (échelle différente), la fenêtre se redimensionne une première fois.
            for (int i = 0; i < 2; i++) SetWindowPos(h, IntPtr.Zero, x - l, y - t, w + l + r, hgt + t + b, 0x0014); // NOZORDER | NOACTIVATE
        }

        static string LayoutOf(RECT f, RECT v)
        {
            Func<int, int, bool> near = (a, b) => Math.Abs(a - b) <= 16;
            if (!near(f.Top, v.Top) || !near(f.H, v.H)) return "keep";
            if (near(f.Left, v.Left) && near(f.W, v.W)) return "max";
            if (near(f.Left, v.Left) && near(f.W, v.W / 2)) return "left";
            if (near(f.Right, v.Right) && near(f.W, v.W / 2)) return "right";
            return "keep";
        }

        static void Place(IntPtr h, List<ScreenInfo> screens, int display, string layout)
        {
            if (IsIconic(h)) ShowWindow(h, 9); // SW_RESTORE
            ScreenInfo from = ScreenOf(h, screens);
            ScreenInfo to = screens.FirstOrDefault(s => s.Id == display) ?? from;
            bool max = IsZoomed(h);
            if (layout == "keep")
            {
                if (to == from) return;
                layout = max ? "max" : LayoutOf(Visual(h), from.Work);
            }
            if (max) ShowWindow(h, 9);
            RECT v = to.Work;
            RECT cur = Visual(h);
            if (layout == "max")
            {
                // Posée sur l'écran cible, puis agrandie (vraie fenêtre agrandie, pas seulement un grand cadre).
                int w = Math.Min(cur.W, v.W), hh = Math.Min(cur.H, v.H);
                Move(h, v.Left + (v.W - w) / 2, v.Top + (v.H - hh) / 2, w, hh);
                ShowWindow(h, 3); // SW_MAXIMIZE
                return;
            }
            if (layout == "left") { Move(h, v.Left, v.Top, v.W / 2, v.H); return; }
            if (layout == "right") { Move(h, v.Left + v.W - v.W / 2, v.Top, v.W / 2, v.H); return; }
            RECT f = from.Work;
            int width = Math.Min(cur.W, v.W), height = Math.Min(cur.H, v.H);
            int x = v.Left + (int)((cur.Left - f.Left) / (double)Math.Max(1, f.W) * v.W);
            int y = v.Top + (int)((cur.Top - f.Top) / (double)Math.Max(1, f.H) * v.H);
            x = Math.Max(v.Left, Math.Min(x, v.Right - width));
            y = Math.Max(v.Top, Math.Min(y, v.Bottom - height));
            Move(h, x, y, width, height);
        }

        /** Windows refuse le premier plan à un processus d'arrière-plan : on s'attache au fil actif, puis l'astuce de la touche Alt. */
        static void Focus(IntPtr h)
        {
            if (IsIconic(h)) ShowWindow(h, 9);
            IntPtr fg = GetForegroundWindow();
            uint ignored;
            uint fgThread = GetWindowThreadProcessId(fg, out ignored);
            uint me = GetCurrentThreadId();
            bool attached = fgThread != 0 && fgThread != me && AttachThreadInput(me, fgThread, true);
            try
            {
                BringWindowToTop(h);
                SetForegroundWindow(h);
            }
            finally { if (attached) AttachThreadInput(me, fgThread, false); }
            if (GetForegroundWindow() != h)
            {
                keybd_event(0x12, 0, 0, UIntPtr.Zero);
                keybd_event(0x12, 0, 2, UIntPtr.Zero);
                SetForegroundWindow(h);
            }
        }

        // --- Clavier

        [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
        [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint type);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern short VkKeyScanEx(char ch, IntPtr hkl);
        [DllImport("user32.dll")] static extern IntPtr GetKeyboardLayout(uint thread);

        static bool Extended(int vk)
        {
            return (vk >= 0x21 && vk <= 0x2E) || vk == 0x5B || vk == 0x5C || (vk >= 0xA6 && vk <= 0xB7) || vk == 0x6F || vk == 0x90;
        }

        static void Key(int vk, bool up)
        {
            uint flags = (up ? 2u : 0u) | (Extended(vk) ? 1u : 0u);
            keybd_event((byte)vk, (byte)MapVirtualKey((uint)vk, 0), flags, UIntPtr.Zero);
            Thread.Sleep(8);
        }

        /** Combinaison : modificateurs (codes VK) + touche, par code VK ou par caractère (cherché dans la disposition active, AZERTY compris). */
        static void PressKeys(List<int> mods, int vk, string ch)
        {
            var all = new List<int>(mods);
            if (!string.IsNullOrEmpty(ch))
            {
                uint ignored;
                IntPtr layout = GetKeyboardLayout(GetWindowThreadProcessId(GetForegroundWindow(), out ignored));
                short r = VkKeyScanEx(ch[0], layout);
                if (r == -1) throw new Exception("Touche absente du clavier : " + ch);
                vk = r & 0xff;
                int state = (r >> 8) & 0xff;
                if ((state & 1) != 0 && !all.Contains(0x10)) all.Add(0x10);
                if ((state & 2) != 0 && !all.Contains(0x11)) all.Add(0x11);
                if ((state & 4) != 0 && !all.Contains(0x12)) all.Add(0x12);
            }
            if (vk <= 0) throw new Exception("Touche manquante");
            foreach (int m in all) Key(m, false);
            Key(vk, false);
            Key(vk, true);
            for (int i = all.Count - 1; i >= 0; i--) Key(all[i], true);
        }

        /** Colle un texte dans l'appli au premier plan, puis remet l'ancien contenu du presse-papiers. */
        static void TypeText(string text)
        {
            string previous = null;
            try { if (Clipboard.ContainsText()) previous = Clipboard.GetText(); } catch (Exception) { }
            SetClipboard(text);
            Thread.Sleep(50);
            PressKeys(new List<int> { 0x11 }, 0x56, null); // Ctrl+V
            Thread.Sleep(400);
            try { if (string.IsNullOrEmpty(previous)) Clipboard.Clear(); else SetClipboard(previous); } catch (Exception) { }
        }

        static void SetClipboard(string text)
        {
            // Le presse-papiers peut être tenu un instant par une autre appli.
            for (int i = 0; ; i++)
            {
                try { Clipboard.SetText(text); return; }
                catch (ExternalException) { if (i >= 5) throw; Thread.Sleep(60); }
            }
        }

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

        // --- Système

        [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
        [DllImport("user32.dll")] static extern bool LockWorkStation();
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern IntPtr SendMessageTimeout(IntPtr h, uint msg, IntPtr w, string l, uint flags, uint timeout, out IntPtr result);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "MessageBoxW")] static extern int ShowMessage(IntPtr h, string text, string caption, uint type);

        static void SystemCommand(string command)
        {
            switch (command)
            {
                case "sleep-display":
                    PostMessage(new IntPtr(0xFFFF), 0x0112, new IntPtr(0xF170), new IntPtr(2)); // WM_SYSCOMMAND, SC_MONITORPOWER, éteint
                    return;
                case "lock":
                    LockWorkStation();
                    return;
                case "toggle-dark-mode":
                    using (var key = Registry.CurrentUser.CreateSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"))
                    {
                        int light = Convert.ToInt32(key.GetValue("AppsUseLightTheme", 1));
                        int next = light == 0 ? 1 : 0;
                        key.SetValue("AppsUseLightTheme", next, RegistryValueKind.DWord);
                        key.SetValue("SystemUsesLightTheme", next, RegistryValueKind.DWord);
                    }
                    IntPtr ignored;
                    SendMessageTimeout(new IntPtr(0xFFFF), 0x001A, IntPtr.Zero, "ImmersiveColorSet", 0x2, 200, out ignored); // WM_SETTINGCHANGE
                    return;
                case "screenshot-clipboard":
                    Rectangle area = SystemInformation.VirtualScreen;
                    using (var bmp = new Bitmap(area.Width, area.Height))
                    {
                        using (var g = Graphics.FromImage(bmp)) g.CopyFromScreen(area.Left, area.Top, 0, 0, area.Size);
                        Clipboard.SetImage(bmp);
                    }
                    return;
                default:
                    throw new ArgumentException("Commande système inconnue : " + command);
            }
        }

        /** Boîte de message au premier plan, sans bloquer les requêtes suivantes. */
        static void Alert(string title, string text)
        {
            var t = new Thread(delegate () { ShowMessage(IntPtr.Zero, text, title, 0x40 | 0x10000 | 0x40000); }); // INFORMATION | SETFOREGROUND | TOPMOST
            t.IsBackground = true;
            t.SetApartmentState(ApartmentState.STA);
            t.Start();
        }
    }
}
