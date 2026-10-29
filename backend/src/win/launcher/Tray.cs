// Icône du compagnon dans la zone de notification : un clic ouvre la page locale, le menu (clic droit) montre l'état de
// la tablette, lu sur GET /local/status, et « Ouvrir les réglages de la tablette ». Icône Cadran monochrome
// (docs/ui-ux/da/icone-mono.svg), blanche ou noire selon le thème de la barre des tâches.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Net;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

namespace UnoMate
{
    sealed class Tray : ApplicationContext
    {
        readonly string page;
        readonly NotifyIcon icon = new NotifyIcon();
        readonly ToolStripMenuItem state = new ToolStripMenuItem { Enabled = false };
        readonly ToolStripMenuItem battery = new ToolStripMenuItem { Enabled = false, Visible = false };
        readonly ToolStripMenuItem settings = new ToolStripMenuItem("Ouvrir les réglages de la tablette");
        readonly Timer timer = new Timer { Interval = 3000 };
        bool light;
        bool busy;

        public Tray(int port)
        {
            page = "http://127.0.0.1:" + port + "/";
            var open = new ToolStripMenuItem("Ouvrir la page du compagnon");
            open.Click += (s, e) => Open(page);
            settings.Click += (s, e) => Open(page + "?section=tablette");
            var menu = new ContextMenuStrip();
            menu.Items.AddRange(new ToolStripItem[] { state, battery, new ToolStripSeparator(), settings, open });
            icon.ContextMenuStrip = menu;
            icon.MouseUp += (s, e) => { if (e.Button == MouseButtons.Left) Open(page); };
            Render(null);
            icon.Visible = true;
            timer.Tick += (s, e) => Refresh();
            timer.Start();
            Refresh();
            Application.ApplicationExit += (s, e) => { icon.Visible = false; icon.Dispose(); };
        }

        static void Open(string url)
        {
            try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
            catch (Exception) { }
        }

        async void Refresh()
        {
            if (busy) return;
            busy = true;
            Dictionary<string, object> status = null;
            try
            {
                using (var client = new WebClient { Encoding = Encoding.UTF8 })
                {
                    var json = await client.DownloadStringTaskAsync(page + "local/status");
                    status = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(json);
                }
            }
            catch (Exception) { }
            busy = false;
            Render(status);
        }

        static object Get(Dictionary<string, object> d, string key)
        {
            object v;
            return d != null && d.TryGetValue(key, out v) ? v : null;
        }

        void Render(Dictionary<string, object> status)
        {
            var connected = Get(status, "tablet") is bool && (bool)Get(status, "tablet");
            var info = Get(status, "tabletInfo") as Dictionary<string, object>;
            var name = (Get(info, "name") as string) ?? "tablette";
            state.Text = connected ? "Tablette connectée · " + name : "Tablette non connectée";
            var b = Get(info, "battery") as Dictionary<string, object>;
            battery.Visible = connected && b != null && Get(b, "level") != null;
            if (battery.Visible) battery.Text = "Batterie " + Get(b, "level") + " %" + (Get(b, "charging") is bool && (bool)Get(b, "charging") ? " (en charge)" : "");
            settings.Enabled = connected;
            // Info-bulle : 63 caractères au plus.
            var tip = "UnoMate · " + state.Text;
            icon.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
            var nowLight = TaskbarIsLight();
            if (icon.Icon == null || nowLight != light)
            {
                light = nowLight;
                var old = icon.Icon;
                icon.Icon = Draw(light ? Color.Black : Color.White);
                if (old != null) DestroyIcon(old.Handle);
            }
        }

        // Thème de la barre des tâches (clair : icône noire). Clé absente : sombre.
        static bool TaskbarIsLight()
        {
            try
            {
                var v = Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "SystemUsesLightTheme", 0);
                return v is int && (int)v == 1;
            }
            catch (Exception) { return false; }
        }

        [System.Runtime.InteropServices.DllImport("user32.dll")]
        static extern bool DestroyIcon(IntPtr handle);

        // Repère 24 × 24 du SVG.
        static Icon Draw(Color color)
        {
            var size = SystemInformation.SmallIconSize.Width;
            using (var bitmap = new Bitmap(size, size))
            using (var g = Graphics.FromImage(bitmap))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                float k = size / 24f;
                Action<float, float, int, float> line = (x1, x2, alpha, width) =>
                {
                    using (var pen = new Pen(Color.FromArgb(alpha, color), width * k) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                        g.DrawLine(pen, x1 * k, 12 * k, x2 * k, 12 * k);
                };
                line(2.5f, 13.5f, 255, 2.2f);
                line(13.5f, 21.5f, 102, 2.2f);
                using (var pen = new Pen(Color.FromArgb(140, color), 1.4f * k))
                    g.DrawEllipse(pen, (13.5f - 4.3f) * k, (12 - 4.3f) * k, 8.6f * k, 8.6f * k);
                using (var brush = new SolidBrush(color))
                    g.FillEllipse(brush, (13.5f - 2.3f) * k, (12 - 2.3f) * k, 4.6f * k, 4.6f * k);
                var handle = bitmap.GetHicon();
                using (var temporary = Icon.FromHandle(handle))
                {
                    var copy = (Icon)temporary.Clone();
                    DestroyIcon(handle);
                    return copy;
                }
            }
        }
    }
}
