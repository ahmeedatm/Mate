using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
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
        [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "MessageBoxW")] static extern int ShowMessage(IntPtr h, string text, string caption, uint type);
        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);
        [DllImport("wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr memory);

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
