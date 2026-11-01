// Processus résident du backend sous Windows (lancé par src/win/helper.ts) : exécute une requête JSON par ligne
// lue sur stdin. Chaque réponse est une ligne "@@{...}" sur stdout (le préfixe permet d'ignorer toute autre sortie).
// Les opérations d'UI Automation sont ici, le reste du pilotage dans Native*.cs, la lecture en cours dans Media.cs.
// Exe compilé d'avance, sans PowerShell (les antivirus s'en méfient).

using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Automation;
using Microsoft.Win32;

namespace Mate
{
    static class Program
    {
        // STA : presse-papiers (secours de la page du navigateur).
        [STAThread]
        static int Main()
        {
            var utf8 = new UTF8Encoding(false);
            var stdin = new StreamReader(Console.OpenStandardInput(), utf8);
            var stdout = new StreamWriter(Console.OpenStandardOutput(), utf8) { AutoFlush = true };
            try
            {
                Native.Init();
            }
            catch (Exception e)
            {
                stdout.WriteLine("@@" + Native.Json.Serialize(new Dictionary<string, object> { { "ok", false }, { "fatal", true }, { "error", e.Message } }));
                return 1;
            }
            stdout.WriteLine("@@{\"ok\":true,\"result\":\"ready\"}");
            string line;
            while ((line = stdin.ReadLine()) != null)
            {
                if (line.Trim().Length == 0) continue;
                stdout.WriteLine("@@" + Native.Handle(line));
            }
            return 0;
        }

        /** Opérations d'UI Automation et de lecture en cours, appelées par Native.Dispatch pour celles qu'il ne connaît pas. */
        internal static object Dispatch(string op, Dictionary<string, object> req)
        {
            switch (op)
            {
                case "browser": return BrowserPage();
                case "press": return Press(req);
                case "voice": return Voice(req);
                case "dnd": return SetDnd(req);
                case "awake": return Native.KeepAwake(Bool(req, "on"));
                case "media": return Media.Invoke(req);
                default: throw new ArgumentException("Opération inconnue : " + op);
            }
        }

        // --- Lecture des requêtes

        internal static bool Bool(Dictionary<string, object> d, string key)
        {
            object v;
            return d.TryGetValue(key, out v) && v != null && Convert.ToBoolean(v);
        }

        static IEnumerable<object> List(Dictionary<string, object> d, string key)
        {
            object v;
            var list = d.TryGetValue(key, out v) ? v as IEnumerable : null;
            return list == null || v is string ? Enumerable.Empty<object>() : list.Cast<object>();
        }

        static bool Match(string text, string pattern)
        {
            return Regex.IsMatch(text, pattern, RegexOptions.IgnoreCase);
        }

        static string TypeName(AutomationElement e)
        {
            return Regex.Replace(e.Current.ControlType.ProgrammaticName, "^ControlType\\.", "");
        }

        // --- Page ouverte dans le navigateur (bouton "Page de l'ordi" de la tablette) : barre d'adresse lue par
        // UI Automation. Fenêtres parcourues de la plus en avant à la plus en arrière : le navigateur utilisé en
        // dernier gagne, même derrière un jeu en plein écran.

        static readonly Dictionary<string, string> Browsers = new Dictionary<string, string>
        {
            { "chrome", "Chrome" }, { "msedge", "Edge" }, { "opera", "Opera" }, { "brave", "Brave" }, { "vivaldi", "Vivaldi" }, { "firefox", "Firefox" },
        };

        static object BrowserPage()
        {
            var isEdit = new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit);
            var firefoxBar = new PropertyCondition(AutomationElement.AutomationIdProperty, "urlbar-input");
            foreach (AutomationElement window in AutomationElement.RootElement.FindAll(TreeScope.Children, Condition.TrueCondition))
            {
                string processName;
                try { processName = Process.GetProcessById(window.Current.ProcessId).ProcessName.ToLowerInvariant(); }
                catch (Exception) { continue; }
                string browser;
                if (!Browsers.TryGetValue(processName, out browser)) continue;
                // Premier champ dont la valeur ressemble à une adresse : Chrome n'en a qu'un, Opera GX en a deux (le
                // premier vide), Firefox nomme le sien urlbar-input.
                foreach (AutomationElement field in window.FindAll(TreeScope.Descendants, browser == "Firefox" ? firefoxBar : isEdit))
                {
                    object pattern;
                    if (!field.TryGetCurrentPattern(ValuePattern.Pattern, out pattern)) continue;
                    var value = (((ValuePattern)pattern).Current.Value ?? "").Trim();
                    if (Match(value, "^\\S+\\.\\S+$") || Match(value, "^[a-z]+://\\S+$"))
                        return new Dictionary<string, object> { { "found", true }, { "url", value }, { "title", window.Current.Name ?? "" }, { "browser", browser } };
                }
            }
            // Secours : Opera et Chrome coupent parfois leur accessibilité (fenêtre vide pour UI Automation). Le lien
            // copié dans le navigateur (Ctrl+L puis Ctrl+C) fait alors l'affaire.
            // Jamais le contenu dans la réponse (ce peut être un mot de passe) : seulement son état.
            var copied = "";
            var clipboard = "empty";
            try
            {
                copied = (System.Windows.Forms.Clipboard.GetText() ?? "").Trim();
                if (copied.Length > 0) clipboard = "not-url";
            }
            catch (Exception e) { clipboard = "error: " + e.Message; }
            if (Match(copied, "^https?://\\S+$"))
                return new Dictionary<string, object> { { "found", true }, { "url", copied }, { "title", "" }, { "browser", "clipboard" } };
            return new Dictionary<string, object> { { "found", false }, { "clipboard", clipboard } };
        }

        // --- Boutons d'une fenêtre (UI Automation) : le bouton Jouer d'un launcher de jeu, par son nom.
        // "list" : noms des éléments cliquables (lecture seule) ; "invoke" : actionne le premier dont le nom est dans
        // names, par son motif Invoke, sinon par un clic de souris au centre (les launchers Electron n'exposent pas
        // toujours Invoke).

        [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
        [DllImport("user32.dll")] static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);

        static readonly string[] PressTypes = { "Button", "Hyperlink", "MenuItem", "ListItem", "Text", "Group", "Custom" };

        static IEnumerable<AutomationElement> WindowRoots(Dictionary<string, object> req)
        {
            foreach (var h in List(req, "windows"))
            {
                AutomationElement root = null;
                try { root = AutomationElement.FromHandle(new IntPtr(Convert.ToInt64(h))); }
                catch (ElementNotAvailableException) { }
                if (root != null) yield return root;
            }
        }

        static object Press(Dictionary<string, object> req)
        {
            var wanted = new HashSet<string>(List(req, "names").Select(n => Convert.ToString(n).Trim().ToLowerInvariant()));
            var detail = Bool(req, "detail");
            var listing = Native.Str(req, "action") == "list";
            var seen = new List<string>();
            var total = 0;
            AutomationElement hidden = null;
            foreach (var root in WindowRoots(req))
            {
                foreach (AutomationElement e in root.FindAll(TreeScope.Descendants, Condition.TrueCondition))
                {
                    total++;
                    var name = (e.Current.Name ?? "").Trim();
                    // Diagnostic : tous les éléments avec leur classe (Chromium y met la classe HTML).
                    if (detail)
                    {
                        var cls = e.Current.ClassName ?? "";
                        if ((name.Length > 0 || cls.Length > 0) && seen.Count < 3000) seen.Add(TypeName(e) + " : " + name + " : " + cls);
                        continue;
                    }
                    if (name.Length == 0 || name.Length > 40) continue;
                    var type = TypeName(e);
                    if (!PressTypes.Contains(type, StringComparer.OrdinalIgnoreCase)) continue;
                    if (listing)
                    {
                        if (seen.Count < 1500) seen.Add(type + " : " + name);
                        continue;
                    }
                    if (!wanted.Contains(name.ToLowerInvariant())) continue;
                    if (!e.Current.IsEnabled) continue;
                    // Hors écran (Discord réduit ou dans la zone de notification) : gardé en secours, actionné
                    // seulement par un motif d'UI Automation, jamais par un clic.
                    if (e.Current.IsOffscreen)
                    {
                        if (hidden == null) hidden = e;
                        continue;
                    }
                    var done = Act(e, name);
                    if (done != null) return done;
                    var r = e.Current.BoundingRectangle;
                    if (r.Width <= 0 || r.Height <= 0) continue;
                    SetCursorPos((int)(r.X + r.Width / 2), (int)(r.Y + r.Height / 2));
                    mouse_event(0x0002, 0, 0, 0, UIntPtr.Zero);
                    mouse_event(0x0004, 0, 0, 0, UIntPtr.Zero);
                    return new Dictionary<string, object> { { "pressed", name }, { "how", "click" } };
                }
            }
            if (listing || detail) return new Dictionary<string, object> { { "items", seen }, { "total", total } };
            if (hidden != null) return Act(hidden, (hidden.Current.Name ?? "").Trim()) ?? new Dictionary<string, object> { { "pressed", null } };
            return new Dictionary<string, object> { { "pressed", null } };
        }

        // Bouton (Invoke) ou interrupteur (Toggle : micro et sourdine de Discord, sans le mettre devant) ; null sinon.
        static Dictionary<string, object> Act(AutomationElement e, string name)
        {
            object pattern;
            if (e.TryGetCurrentPattern(InvokePattern.Pattern, out pattern))
            {
                ((InvokePattern)pattern).Invoke();
                return new Dictionary<string, object> { { "pressed", name }, { "how", "invoke" } };
            }
            if (e.TryGetCurrentPattern(TogglePattern.Pattern, out pattern))
            {
                ((TogglePattern)pattern).Toggle();
                return new Dictionary<string, object> { { "pressed", name }, { "how", "toggle" } };
            }
            return null;
        }

        // --- État vocal de Discord (UI Automation : Chromium expose son interface une fois l'accessibilité demandée).
        // Panneau du bas à gauche : "Voix connectée" + lien du salon/appel, boutons micro et sourdine ; vue de l'appel :
        // "Déconnecter" puis un bouton par participant. Noms en français ou en anglais.

        static object ToggleState(AutomationElement e)
        {
            object pattern;
            if (e.TryGetCurrentPattern(TogglePattern.Pattern, out pattern))
                return ((TogglePattern)pattern).Current.ToggleState == System.Windows.Automation.ToggleState.On;
            return null;
        }

        static object Voice(Dictionary<string, object> req)
        {
            var state = new Dictionary<string, object>
            {
                { "found", false }, { "connected", false }, { "streaming", false }, { "channel", null },
                { "people", new List<string>() }, { "muted", null }, { "deafened", null }, { "mic", null }, { "deaf", null },
            };
            var people = new List<string>();
            foreach (var root in WindowRoots(req))
            {
                var phase = "start";
                var self = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                var tiles = new List<string>();
                string tile = null;
                foreach (AutomationElement e in root.FindAll(TreeScope.Descendants, Condition.TrueCondition))
                {
                    var name = (e.Current.Name ?? "").Trim();
                    if (name.Length == 0) continue;
                    var type = TypeName(e);
                    var button = type == "Button";
                    // Partage d'écran en cours : bouton "Arrêter de streamer" (panneau vocal ou vue d'appel).
                    if (button && Match(name, "^(Arr.ter de streamer|Stop Streaming)$")) state["streaming"] = true;
                    switch (phase)
                    {
                        case "start":
                            if (type == "Group" && Match(name, "^(Statut et param|User (status|area|settings))")) { phase = "panel"; state["found"] = true; }
                            break;
                        case "panel":
                            if (Match(name, "Voix connect|Voice connected")) { state["connected"] = true; phase = "channel"; }
                            else if (type == "Text") self.Add(name);
                            else if (button && Match(name, "^(Rendre muet|R.activer|Mute|Unmute)")) { state["mic"] = name; state["muted"] = ToggleState(e); }
                            // Nom exact : en sourdine, « Soundboard est désactivé lorsqu'il est mis en sourdine » passe avant.
                            else if (button && Match(name, "^(Mettre en sourdine|Ne plus mettre en sourdine|Deafen|Undeafen)")) { state["deaf"] = name; state["deafened"] = ToggleState(e); phase = "call"; }
                            break;
                        case "channel":
                            if (type == "Hyperlink") { state["channel"] = name; phase = "panel"; }
                            break;
                        case "call":
                            if (button && Match(name, "^(D.connecter|Disconnect)$")) phase = "people";
                            break;
                        case "people":
                            Match m;
                            if (type == "Group") phase = "done";
                            // Salon de serveur : une vignette par personne, "Vignette d'appel, pseudo", puis l'image au nom
                            // affiché (secours si aucun autre nom).
                            else if (button && (m = Regex.Match(name, "^(Vignette d.appel|Call tile), (.+)$", RegexOptions.IgnoreCase)).Success)
                            {
                                if (tile != null) tiles.Add(tile);
                                tile = self.Contains(m.Groups[2].Value) ? null : m.Groups[2].Value;
                            }
                            else if (type == "Image" && tile != null) { tiles.Add(self.Contains(name) ? tile : name); tile = null; }
                            // Boutons de la vue d'un stream ou d'un salon vide (lecteur réduit, plein écran, inviter,
                            // activité) : pas des personnes.
                            else if (button && !self.Contains(name) && !Match(name, "^(Passer en lecteur|Plein .cran|Pop ?out|Full ?screen|Inviter|Invite|Choisis une Activit|Choose an Activity)")) people.Add(name);
                            break;
                    }
                    if (phase == "done") break;
                }
                if ((bool)state["found"])
                {
                    if (tile != null) tiles.Add(tile);
                    state["people"] = people.Count > 0 ? people : tiles;
                    break;
                }
            }
            return state;
        }

        // --- Ne pas déranger (Windows 11). Aucune voie invisible ne marche (vérifié sur 26H2) : l'état WNF des heures
        // calmes est ignoré, la session de concentration (FocusSessionManager) est réservée par Microsoft, et le
        // réglage du CloudStore n'est que le reflet de l'état (écrit, il n'est pas appliqué). On bascule donc
        // l'interrupteur du centre de notifications (Win+N, AutomationId DoNotDisturbButton, motif Toggle) et on le
        // referme : ~0,5 s à l'écran. Lecture sans rien ouvrir : le reflet du CloudStore (profil Unrestricted = désactivé).

        const string DndKey = @"Software\Microsoft\Windows\CurrentVersion\CloudStore\Store\DefaultAccount\Current\default$windows.data.donotdisturb.quiethourssettings\windows.data.donotdisturb.quiethourssettings";

        static bool? DndState()
        {
            byte[] data;
            using (var key = Registry.CurrentUser.OpenSubKey(DndKey))
                data = key == null ? null : key.GetValue("Data") as byte[];
            if (data == null) return null;
            var text = new string(data.Where(b => b >= 0x20 && b < 0x7f).Select(b => (char)b).ToArray());
            var m = Regex.Match(text, "QuietHoursProfile\\.([A-Za-z]+)");
            if (!m.Success) return null;
            return !string.Equals(m.Groups[1].Value, "Unrestricted", StringComparison.OrdinalIgnoreCase);
        }

        static void Chord(params byte[] vks)
        {
            foreach (var vk in vks) Native.keybd_event(vk, 0, 0, UIntPtr.Zero);
            for (int i = vks.Length - 1; i >= 0; i--) Native.keybd_event(vks[i], 0, 2, UIntPtr.Zero);
        }

        static object SetDnd(Dictionary<string, object> req)
        {
            var now = DndState();
            object on;
            if (!req.TryGetValue("on", out on) || on == null) return new Dictionary<string, object> { { "on", now } };
            var want = Convert.ToBoolean(on);
            if (now == want) return new Dictionary<string, object> { { "on", want } };
            var walker = TreeWalker.ControlViewWalker;
            var isButton = new PropertyCondition(AutomationElement.AutomationIdProperty, "DoNotDisturbButton");
            Chord(0x5B, 0x4E); // Win+N
            AutomationElement panel = null;
            AutomationElement found = null;
            var clock = Stopwatch.StartNew();
            // Le panneau prend le focus : on remonte de l'élément focalisé jusqu'à sa fenêtre.
            while (found == null && clock.ElapsedMilliseconds < 3000)
            {
                Thread.Sleep(100);
                try
                {
                    var w = AutomationElement.FocusedElement;
                    while (w != null)
                    {
                        var parent = walker.GetParent(w);
                        if (parent == null || parent.Equals(AutomationElement.RootElement)) break;
                        w = parent;
                    }
                    if (w != null && w.Current.ClassName == "Windows.UI.Core.CoreWindow")
                    {
                        panel = w;
                        found = w.FindFirst(TreeScope.Descendants, isButton);
                    }
                }
                catch (ElementNotAvailableException) { }
            }
            try
            {
                if (found == null) throw new Exception("Bouton Ne pas déranger introuvable dans le centre de notifications");
                var toggle = (TogglePattern)found.GetCurrentPattern(TogglePattern.Pattern);
                if ((toggle.Current.ToggleState == System.Windows.Automation.ToggleState.On) != want) toggle.Toggle();
            }
            finally
            {
                // Échap seulement si le panneau a bien pris le focus : sinon la touche irait à l'appli au premier plan (un jeu).
                if (panel != null) { Thread.Sleep(150); Chord(0x1B); }
            }
            return new Dictionary<string, object> { { "on", want } };
        }
    }
}
