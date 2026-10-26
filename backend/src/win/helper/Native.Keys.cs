using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace Companion
{
    public static partial class Native
    {
        // --- Clavier

        [DllImport("user32.dll")] internal static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
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
    }
}
