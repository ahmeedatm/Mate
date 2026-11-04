using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace Mate
{
    public static partial class Native
    {
        // --- Système

        [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
        [DllImport("user32.dll")] static extern bool LockWorkStation();
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern IntPtr SendMessageTimeout(IntPtr h, uint msg, IntPtr w, string l, uint flags, uint timeout, out IntPtr result);
        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);
        [DllImport("wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr memory);

        [DllImport("kernel32.dll")] static extern uint SetThreadExecutionState(uint flags);

        /** Écran gardé allumé tant que le helper vit (ES_CONTINUOUS | ES_DISPLAY_REQUIRED), sur le fil unique qui traite les requêtes. */
        internal static object KeepAwake(bool on)
        {
            SetThreadExecutionState(on ? 0x80000002u : 0x80000000u);
            return new Dictionary<string, object> { { "on", on } };
        }

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
                    using (var bmp = CaptureScreen(SystemInformation.VirtualScreen)) Clipboard.SetImage(bmp);
                    return;
                default:
                    throw new ArgumentException("Commande système inconnue : " + command);
            }
        }

        static Bitmap CaptureScreen(Rectangle area)
        {
            var bmp = new Bitmap(area.Width, area.Height);
            try
            {
                using (var g = Graphics.FromImage(bmp)) g.CopyFromScreen(area.Left, area.Top, 0, 0, area.Size);
                return bmp;
            }
            catch
            {
                bmp.Dispose();
                throw;
            }
        }

        /**
         * Aperçu de l'écran principal pour la tablette : JPEG (qualité 60) d'au plus `width` px de large, rendu en base64,
         * tout en mémoire, rien n'est écrit sur le disque.
         */
        static object ScreenPreview(int width)
        {
            var primary = Screen.PrimaryScreen;
            if (primary == null) throw new InvalidOperationException("Aucun écran à lire");
            using (var full = CaptureScreen(primary.Bounds))
            {
                double scale = Math.Min(1.0, (double)Math.Max(1, width) / full.Width);
                int w = Math.Max(1, (int)Math.Round(full.Width * scale)), h = Math.Max(1, (int)Math.Round(full.Height * scale));
                using (var small = new Bitmap(w, h))
                using (var jpeg = new MemoryStream())
                {
                    using (var g = Graphics.FromImage(small))
                    {
                        g.InterpolationMode = InterpolationMode.HighQualityBilinear;
                        g.DrawImage(full, 0, 0, w, h);
                    }
                    var codec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
                    using (var options = new EncoderParameters(1))
                    {
                        options.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 60L);
                        small.Save(jpeg, codec, options);
                    }
                    return Convert.ToBase64String(jpeg.ToArray());
                }
            }
        }

        /**
         * Session du helper verrouillée (écran de verrouillage) ou inactive (un autre utilisateur a pris l'écran). Lue par
         * WTSQuerySessionInformation(WTSSessionInfoEx) : WTSINFOEXW = Level (4 octets) puis WTSINFOEX_LEVEL1_W aligné sur
         * 8 (il contient des LARGE_INTEGER) : SessionId à 8, SessionState à 12 (0 = WTSActive), SessionFlags à 16
         * (0 = WTS_SESSIONSTATE_LOCK, 1 = UNLOCK, -1 = inconnu). Null : état illisible.
         */
        static object SessionLocked()
        {
            IntPtr buffer;
            int bytes;
            if (!WTSQuerySessionInformation(IntPtr.Zero, Process.GetCurrentProcess().SessionId, 25, out buffer, out bytes))
                return new Dictionary<string, object> { { "locked", null } };
            try
            {
                if (bytes < 20 || Marshal.ReadInt32(buffer, 0) != 1) return new Dictionary<string, object> { { "locked", null } };
                int state = Marshal.ReadInt32(buffer, 12), flags = Marshal.ReadInt32(buffer, 16);
                object locked = state != 0 || flags == 0 ? (object)true : flags == 1 ? (object)false : null;
                return new Dictionary<string, object> { { "locked", locked } };
            }
            finally
            {
                WTSFreeMemory(buffer);
            }
        }

        /** Notification Windows (bulle de la zone de notification), sans bloquer les requêtes suivantes. */
        static void Notify(string title, string text)
        {
            var t = new Thread(delegate ()
            {
                NotifyIcon icon = null;
                System.Windows.Forms.Timer timer = null;
                Icon own = null;
                try
                {
                    icon = new NotifyIcon();
                    timer = new System.Windows.Forms.Timer();
                    try { own = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
                    icon.Icon = own ?? SystemIcons.Information;
                    icon.Visible = true;
                    icon.ShowBalloonTip(10000, title, text, ToolTipIcon.Info);
                    // L'icône est retirée après 10 s : la boucle de messages la garde vivante jusque-là.
                    timer.Interval = 10000;
                    timer.Tick += delegate { Application.ExitThread(); };
                    timer.Start();
                    Application.Run();
                }
                catch (Exception e)
                {
                    // Une exception non rattrapée d'un fil arrêterait tout le pilote : la notification échoue seule, dans stderr.
                    Console.Error.WriteLine("notify : " + e.Message);
                }
                finally
                {
                    if (timer != null) timer.Dispose();
                    if (icon != null)
                    {
                        icon.Visible = false;
                        icon.Dispose();
                    }
                    if (own != null) own.Dispose();
                }
            });
            t.IsBackground = true;
            t.SetApartmentState(ApartmentState.STA);
            t.Start();
        }
    }
}
