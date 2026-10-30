// Pilotage de Windows pour le compagnon : compilé dans MateHelper.exe (npm run build:helper), appelé ligne par
// ligne par src/win/helper.ts. Écrit en C# 7.3. Classe partielle : ici la lecture des requêtes et l'aiguillage,
// un fichier Native.<thème>.cs par thème (Apps, Icons, Windows, OpenWindows, Keys, Audio, AppAudio, System, Perf,
// Clipboard, Processes).
//
// Requête : {"op": "...", ...} ; réponse : {"ok": true, "result": ...} ou {"ok": false, "error": "..."}.

using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Web.Script.Serialization;

namespace Mate
{
    public static partial class Native
    {
        internal static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

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
                case "apps": return Apps();
                case "launch": Process.Start("explorer.exe", "\"shell:AppsFolder\\" + Str(req, "id") + "\""); return true;
                case "open": Open(Str(req, "target")); return true;
                case "icon": return AppIcon(Str(req, "id"), Str(req, "out"), Int(req, "size", 96));
                case "windows": return WindowsOp(req);
                case "allwindows": return AllWindows(req);
                case "keys": PressKeys(IntList(req, "mods"), Int(req, "vk", 0), Str(req, "ch")); return true;
                case "text": TypeText(Str(req, "text")); return true;
                case "audio": return Audio(req);
                case "appaudio": return AppAudio(req);
                case "mixer": return Mixer(req);
                case "perf": return Perf(req);
                case "cpu": return Cpu();
                case "system": SystemCommand(Str(req, "command")); return true;
                case "locked": return SessionLocked();
                case "pointer": return Pointer(req);
                case "alert": Alert(Str(req, "title"), Str(req, "text")); return true;
                case "clipget": return ClipboardGet();
                case "clipseq": return ClipboardSequence();
                case "clipset": ClipboardSetText(Str(req, "text")); return true;
                case "clipimage": ClipboardSetImage(Str(req, "path")); return true;
                case "front": return Front();
                case "ancestors": return Ancestors(req);
                case "raise": return Raise(req);
                default: return Program.Dispatch(op, req);
            }
        }

        // --- Lecture des requêtes

        internal static string Str(Dictionary<string, object> d, string key)
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
    }
}
