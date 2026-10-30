using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace UnoMate
{
    public static partial class Native
    {
        // --- Agents Claude : retrouver la fenêtre d'une session (src/agents-desktop.ts). Un processus n'a pas de fenêtre à lui
        // (claude.exe tourne dans un terminal) : on remonte ses parents jusqu'au premier qui en a une (terminal, éditeur,
        // Claude Desktop). Appelé à chaque relecture des agents : léger, une photo des processus et une des fenêtres.

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct PROCESSENTRY32
        {
            public uint dwSize;
            public uint cntUsage;
            public uint th32ProcessID;
            public IntPtr th32DefaultHeapID;
            public uint th32ModuleID;
            public uint cntThreads;
            public uint th32ParentProcessID;
            public int pcPriClassBase;
            public uint dwFlags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szExeFile;
        }

        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint pid);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32FirstW(IntPtr snap, ref PROCESSENTRY32 e);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32NextW(IntPtr snap, ref PROCESSENTRY32 e);
        [DllImport("kernel32.dll")] static extern bool GetProcessTimes(IntPtr h, out long creation, out long exit, out long kernel, out long user);
        [DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
        [DllImport("kernel32.dll")] static extern bool AttachConsole(uint pid);
        [DllImport("kernel32.dll")] static extern bool FreeConsole();
        [DllImport("kernel32.dll")] static extern IntPtr GetConsoleWindow();
        [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);

        /** Session de Windows et services : au-delà, plus rien n'est le terminal de la session (l'Explorateur a ses propres fenêtres). */
        static readonly HashSet<string> ChainStops = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            "explorer.exe", "sihost.exe", "svchost.exe", "services.exe", "wininit.exe", "winlogon.exe", "userinit.exe",
            "runtimebroker.exe", "taskhostw.exe", "smss.exe", "csrss.exe", "system", "[system process]",
        };

        /** Date de création d'un processus (null si illisible) : un parent créé après l'enfant est un numéro réutilisé. */
        static long? Created(uint pid)
        {
            IntPtr h = OpenProcess(0x1000, false, pid); // PROCESS_QUERY_LIMITED_INFORMATION
            if (h == IntPtr.Zero) return null;
            try
            {
                long c, e, k, u;
                return GetProcessTimes(h, out c, out e, out k, out u) ? c : (long?)null;
            }
            finally { CloseHandle(h); }
        }

        static string ClassOf(IntPtr h)
        {
            var cls = new StringBuilder(256);
            GetClassName(h, cls, 256);
            return cls.ToString();
        }

        static Dictionary<string, object> WindowInfo(IntPtr h, uint pid, string title)
        {
            var d = new Dictionary<string, object>();
            d["hwnd"] = h.ToInt64();
            d["pid"] = (long)pid;
            d["exe"] = ExePath(pid) ?? "";
            d["title"] = title ?? "";
            d["cls"] = h == IntPtr.Zero ? "" : ClassOf(h);
            return d;
        }

        /**
         * Fenêtre du terminal qui affiche la console d'un processus : la fenêtre de la console classique (conhost), ou, pour
         * un terminal ConPTY (Windows Terminal, y compris quand Windows lui confie une console ouverte depuis le menu
         * Démarrer), la fenêtre propriétaire de sa pseudo-fenêtre de console. Zéro si la console est cachée (Claude Desktop
         * lance claude.exe sur une console invisible) ou si le terminal ne déclare pas de propriétaire.
         * Le pilote se détache de sa propre console (ses entrées et sorties sont des tubes) et ignore Ctrl-C pendant qu'il
         * est rattaché à celle de la session, quelques millisecondes.
         */
        static IntPtr ConsoleWindowOf(uint pid)
        {
            SetConsoleCtrlHandler(IntPtr.Zero, true);
            FreeConsole();
            if (!AttachConsole(pid)) return IntPtr.Zero;
            try
            {
                IntPtr h = GetConsoleWindow();
                if (h == IntPtr.Zero) return h;
                // La pseudo-fenêtre d'une console ConPTY se dit visible : seule sa classe la distingue.
                if (ClassOf(h) != "PseudoConsoleWindow") return IsWindowVisible(h) ? h : IntPtr.Zero;
                IntPtr owner = GetWindow(h, 4); // GW_OWNER
                return owner != IntPtr.Zero && IsWindowVisible(owner) ? owner : IntPtr.Zero;
            }
            finally { FreeConsole(); }
        }

        /** Fenêtre au premier plan : programme, titre, classe (sert aux alertes et à vérifier une fenêtre avant d'y taper). */
        static object Front()
        {
            IntPtr h = GetForegroundWindow();
            uint pid = 0;
            if (h != IntPtr.Zero) GetWindowThreadProcessId(h, out pid);
            string title = "";
            if (h != IntPtr.Zero)
            {
                var sb = new StringBuilder(GetWindowTextLength(h) + 1);
                GetWindowText(h, sb, sb.Capacity);
                title = sb.ToString();
            }
            return WindowInfo(h, pid, title);
        }

        /**
         * Parents d'un processus, lui compris : [{pid, ppid, exe, hwnd, title, cls}], hwnd = sa fenêtre principale visible
         * la plus en avant (0 sinon). Arrêt à la session de Windows (`ChainStops`), à un numéro réutilisé ou après 16 niveaux.
         * `fallback` : fenêtre la plus en avant d'un des programmes `hosts` (noms d'exe), pour amener devant quand la chaîne
         * n'a aucune fenêtre. `console` : fenêtre du terminal qui affiche la console du processus (`ConsoleWindowOf`), ou null.
         */
        static object Ancestors(Dictionary<string, object> req)
        {
            uint start = (uint)Int(req, "pid", 0);
            if (start == 0) throw new Exception("pid manquant");
            var parents = new Dictionary<uint, uint>();
            var names = new Dictionary<uint, string>();
            IntPtr snap = CreateToolhelp32Snapshot(0x2, 0); // TH32CS_SNAPPROCESS
            if (snap == new IntPtr(-1)) throw new Exception("Liste des processus illisible");
            try
            {
                var e = new PROCESSENTRY32();
                e.dwSize = (uint)Marshal.SizeOf(typeof(PROCESSENTRY32));
                for (bool ok = Process32FirstW(snap, ref e); ok; ok = Process32NextW(snap, ref e))
                {
                    parents[e.th32ProcessID] = e.th32ParentProcessID;
                    names[e.th32ProcessID] = e.szExeFile ?? "";
                }
            }
            finally { CloseHandle(snap); }

            var windows = TopWindows();
            var chain = new List<object>();
            var seen = new HashSet<uint>();
            uint pid = start;
            long? childCreated = null;
            while (pid != 0 && parents.ContainsKey(pid) && seen.Add(pid) && chain.Count < 16)
            {
                string name = names[pid];
                if (ChainStops.Contains(name)) break;
                long? created = Created(pid);
                if (childCreated.HasValue && created.HasValue && created.Value > childCreated.Value) break;
                Win w = windows.Find(x => x.Pid == pid);
                var d = WindowInfo(w != null ? w.H : IntPtr.Zero, pid, w != null ? w.Title : "");
                if ((string)d["exe"] == "") d["exe"] = name;
                d["ppid"] = (long)parents[pid];
                chain.Add(d);
                childCreated = created ?? childCreated;
                pid = parents[pid];
            }

            Dictionary<string, object> fallback = null;
            var hosts = new HashSet<string>(StrList(req, "hosts"), StringComparer.OrdinalIgnoreCase);
            if (hosts.Count > 0)
                foreach (var w in windows)
                {
                    string exe = ExePath(w.Pid);
                    if (exe != null && hosts.Contains(Path.GetFileName(exe))) { fallback = WindowInfo(w.H, w.Pid, w.Title); break; }
                }

            IntPtr con = ConsoleWindowOf(start);
            Dictionary<string, object> console = null;
            if (con != IntPtr.Zero)
            {
                uint cpid;
                GetWindowThreadProcessId(con, out cpid);
                var t = new StringBuilder(GetWindowTextLength(con) + 1);
                GetWindowText(con, t, t.Capacity);
                console = WindowInfo(con, cpid, t.ToString());
            }

            var res = new Dictionary<string, object>();
            res["console"] = console;
            res["chain"] = chain;
            res["fallback"] = fallback;
            return res;
        }

        /** Amène une fenêtre devant (même astuce que la vue « Ouvertes ») et rend la fenêtre de devant obtenue. */
        static object Raise(Dictionary<string, object> req)
        {
            var h = new IntPtr(Has(req, "hwnd") ? Convert.ToInt64(req["hwnd"]) : 0);
            if (h == IntPtr.Zero || !IsWindow(h) || !IsWindowVisible(h)) throw new Exception("Fenêtre introuvable");
            Focus(h);
            Thread.Sleep(150);
            return Front();
        }
    }
}
