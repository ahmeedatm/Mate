using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace Mate
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

        /** Modificateurs (codes VK) et touche d'une combinaison, par code VK ou par caractère (disposition active, AZERTY compris). */
        static int ResolveKeys(List<int> mods, int vk, string ch, out List<int> all)
        {
            all = new List<int>(mods);
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
            return vk;
        }

        /** Combinaison : modificateurs (codes VK) + touche, enfoncés puis relâchés. */
        static void PressKeys(List<int> mods, int vk, string ch)
        {
            List<int> all;
            vk = ResolveKeys(mods, vk, ch, out all);
            foreach (int m in all) Key(m, false);
            Key(vk, false);
            Key(vk, true);
            for (int i = all.Count - 1; i >= 0; i--) Key(all[i], true);
        }

        /** Tient (down) ou lâche une combinaison : modificateurs puis touche, relâchés dans l'ordre inverse. */
        static void HoldKeys(List<int> mods, int vk, string ch, bool down)
        {
            List<int> all;
            vk = ResolveKeys(mods, vk, ch, out all);
            if (down)
            {
                foreach (int m in all) Key(m, false);
                Key(vk, false);
                return;
            }
            Key(vk, true);
            for (int i = all.Count - 1; i >= 0; i--) Key(all[i], true);
        }

        /** Colle un texte dans l'appli au premier plan, puis remet l'ancien contenu du presse-papiers. */
        /**
         * Colle un texte puis remet le presse-papiers d'avant, tous formats compris (image, marques des gestionnaires de
         * mots de passe). Le texte collé est exclu des historiques (Win+V, celui du compagnon) ; une copie faite pendant le
         * collage n'est pas écrasée.
         */
        static void TypeText(string text)
        {
            DataObject previous = null;
            try { previous = Snapshot(Clipboard.GetDataObject()); } catch (Exception) { }
            var temp = new DataObject();
            temp.SetText(text);
            temp.SetData("ExcludeClipboardContentFromMonitorProcessing", new MemoryStream(new byte[0]));
            SetClipboard(temp);
            uint ours = GetClipboardSequenceNumber();
            Thread.Sleep(50);
            PressKeys(new List<int> { 0x11 }, 0x56, null); // Ctrl+V
            Thread.Sleep(400);
            if (GetClipboardSequenceNumber() != ours) return;
            try { if (previous == null) Clipboard.Clear(); else SetClipboard(previous); } catch (Exception) { }
        }

        /** Copie de chaque format lisible ; null si rien n'a pu être lu (presse-papiers vide). */
        static DataObject Snapshot(IDataObject data)
        {
            if (data == null) return null;
            var copy = new DataObject();
            bool any = false;
            foreach (var format in data.GetFormats(false))
            {
                try
                {
                    var value = data.GetData(format, false);
                    if (value == null) continue;
                    copy.SetData(format, false, value);
                    any = true;
                }
                catch (Exception) { }
            }
            return any ? copy : null;
        }

        static void SetClipboard(string text)
        {
            var data = new DataObject();
            data.SetText(text);
            SetClipboard(data);
        }

        static void SetClipboard(IDataObject data)
        {
            // Le presse-papiers peut être tenu un instant par une autre appli.
            for (int i = 0; ; i++)
            {
                try { Clipboard.SetDataObject(data, true); return; }
                catch (ExternalException) { if (i >= 5) throw; Thread.Sleep(60); }
            }
        }
    }
}
