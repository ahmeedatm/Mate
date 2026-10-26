using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading;

namespace Companion
{
    public static partial class Native
    {
        // --- Vue « Ouvertes » : toutes les fenêtres, chacune avec l'appli du menu Démarrer qui l'a ouverte.

        /** Appli du menu Démarrer (nom) et de quoi reconnaître ses fenêtres ; relu toutes les 5 minutes. */
        static List<KeyValuePair<string, AppMatch>> startMatches;
        static DateTime startMatchesAt;

        static List<KeyValuePair<string, AppMatch>> StartMatches()
        {
            if (startMatches != null && DateTime.UtcNow - startMatchesAt < TimeSpan.FromMinutes(5)) return startMatches;
            var list = new List<KeyValuePair<string, AppMatch>>();
            foreach (Dictionary<string, object> a in Apps())
            {
                string name = Convert.ToString(a["name"]), id = Convert.ToString(a["id"]);
                // Pas les liens web ni les désinstalleurs (même filtre que win/index.ts).
                if (name.Length == 0 || id.Length == 0 || Regex.IsMatch(id, "^https?:", RegexOptions.IgnoreCase)) continue;
                if (Regex.IsMatch(name, "uninstall|désinstall|readme|lisez-moi|documentation|release notes|notes de version|^aide|^help", RegexOptions.IgnoreCase)) continue;
                list.Add(new KeyValuePair<string, AppMatch>(name, MatchFor(id, new List<string>())));
            }
            startMatches = list;
            startMatchesAt = DateTime.UtcNow;
            return list;
        }

        /**
         * Nom de l'appli d'une fenêtre : d'abord par identifiant (une appli web installée depuis un navigateur a le sien,
         * distinct du navigateur), puis par programme, dossier Squirrel ou paquet ; null si elle n'est pas dans le menu Démarrer.
         */
        static string StartAppOf(string aumid, string exe, List<KeyValuePair<string, AppMatch>> apps)
        {
            if (aumid != null) foreach (var a in apps) if (string.Equals(a.Value.Aumid, aumid, StringComparison.OrdinalIgnoreCase)) return a.Key;
            if (exe == null) return null;
            string lower = exe.ToLowerInvariant();
            foreach (var a in apps) if (a.Value.Exe != null && string.Equals(a.Value.Exe, exe, StringComparison.OrdinalIgnoreCase)) return a.Key;
            foreach (var a in apps) if (a.Value.Folder != null && exe.StartsWith(a.Value.Folder, StringComparison.OrdinalIgnoreCase)) return a.Key;
            foreach (var a in apps)
                if (a.Value.Package != null && lower.Contains("\\windowsapps\\" + a.Value.Package + "_") && lower.Contains("__" + a.Value.Publisher)) return a.Key;
            return null;
        }

        /**
         * Liste (action "list") ou pilote une fenêtre désignée par son handle (`hwnd`) : "focus", "place", "minimize"
         * (réduire), "zoom" (agrandir, ou rendre sa taille à une fenêtre agrandie) ou "close" (comme sa croix : WM_CLOSE,
         * l'appli peut demander d'enregistrer).
         * Chaque fenêtre porte `app` (nom de l'appli du menu Démarrer, ou description du programme) et `exe`.
         */
        static object AllWindows(Dictionary<string, object> req)
        {
            string action = Str(req, "action") ?? "list";
            var screens = Screens();
            if (action != "list")
            {
                long hwnd = Has(req, "hwnd") ? Convert.ToInt64(req["hwnd"]) : 0;
                var target = TopWindows().FirstOrDefault(w => w.H.ToInt64() == hwnd);
                if (target == null) throw new Exception("Fenêtre introuvable");
                if (action == "minimize") ShowWindow(target.H, 6); // SW_MINIMIZE
                else if (action == "close") { PostMessage(target.H, 0x0010, IntPtr.Zero, IntPtr.Zero); Thread.Sleep(350); } // WM_CLOSE
                else
                {
                    if (action == "place") Place(target.H, screens, Int(req, "display", -1), Str(req, "layout") ?? "keep");
                    // SW_RESTORE si agrandie (ou réduite), sinon SW_MAXIMIZE.
                    if (action == "zoom") ShowWindow(target.H, IsZoomed(target.H) ? 9 : 3);
                    Focus(target.H);
                }
                Thread.Sleep(150);
            }
            var apps = StartMatches();
            var names = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            var windows = new List<object>();
            foreach (var w in TopWindows())
            {
                string exe = ExePath(w.Pid);
                string aumid = Aumid(w.H);
                string key = aumid ?? exe ?? "";
                string name;
                if (!names.TryGetValue(key, out name))
                {
                    name = StartAppOf(aumid, exe, apps);
                    if (name == null && exe != null)
                    {
                        try { var vi = FileVersionInfo.GetVersionInfo(exe); name = vi.FileDescription; if (string.IsNullOrWhiteSpace(name)) name = vi.ProductName; }
                        catch (Exception) { }
                        if (string.IsNullOrWhiteSpace(name)) name = Path.GetFileNameWithoutExtension(exe);
                    }
                    names[key] = name = name ?? w.Title;
                }
                var d = Describe(w, screens);
                d["app"] = name;
                d["exe"] = exe ?? "";
                windows.Add(d);
            }
            var res = new Dictionary<string, object>();
            res["windows"] = windows;
            res["displays"] = DisplaysOf(screens);
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
    }
}
