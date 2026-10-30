// Icône du compagnon dans la zone de notification : un clic ouvre la page locale, le menu (clic droit) montre l'état de
// la tablette, lu sur GET /local/status, et « Ouvrir les réglages de la tablette ». Visage Mate monochrome
// (docs/ui-ux/da/icone-visage-mono.svg), blanc ou noir selon le thème de la barre des tâches.

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

namespace Mate
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
            var tip = "Mate · " + state.Text;
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

        static GraphicsPath RoundedRect(float x, float y, float w, float h, float r)
        {
            var path = new GraphicsPath();
            path.AddArc(x, y, 2 * r, 2 * r, 180, 90);
            path.AddArc(x + w - 2 * r, y, 2 * r, 2 * r, 270, 90);
            path.AddArc(x + w - 2 * r, y + h - 2 * r, 2 * r, 2 * r, 0, 90);
            path.AddArc(x, y + h - 2 * r, 2 * r, 2 * r, 90, 90);
            path.CloseFigure();
            return path;
        }

        // Visage du SVG (repère du groupe de icone-visage.svg : x 7..200, y 0..166,4), centré, ~86 % de la largeur.
        static Icon Draw(Color color)
        {
            var size = SystemInformation.SmallIconSize.Width;
            using (var bitmap = new Bitmap(size, size))
            using (var g = Graphics.FromImage(bitmap))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                float k = size * 0.86f / 193f;
                g.TranslateTransform((size - 193 * k) / 2 - 7 * k, (size - 166.4f * k) / 2);
                g.ScaleTransform(k, k);
                using (var brush = new SolidBrush(color))
                using (var pen = new Pen(color, 4) { LineJoin = LineJoin.Round })
                {
                    foreach (var x in new[] { 47.5f, 137.5f })
                        using (var eye = RoundedRect(x, 0, 22, 43.5f, 4))
                            g.FillPath(brush, eye);
                    using (var mouth = new GraphicsPath())
                    {
                        mouth.AddPolygon(new[]
                        {
                            new PointF(9, 72), new PointF(27.5f, 72), new PointF(27.5f, 104), new PointF(103.5f, 142.5f),
                            new PointF(179.5f, 104), new PointF(179.5f, 72), new PointF(198, 72), new PointF(198, 116),
                            new PointF(103.5f, 164.4f), new PointF(9, 116)
                        });
                        g.FillPath(brush, mouth);
                        g.DrawPath(pen, mouth);
                    }
                }
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
