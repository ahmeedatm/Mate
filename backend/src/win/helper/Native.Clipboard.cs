using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace Companion
{
    public static partial class Native
    {
        // --- Presse-papiers partagé avec la tablette (GET/POST /system/clipboard, voir src/win/index.ts).
        // Lecture par l'API Win32, en une seule ouverture du presse-papiers : marqueur sensible d'abord (gestionnaire
        // de mots de passe), sans jamais lire le texte dans ce cas ; puis texte, image, vide, reste. Le contenu
        // n'apparaît jamais dans un message d'erreur.

        [DllImport("user32.dll", SetLastError = true)] static extern bool OpenClipboard(IntPtr owner);
        [DllImport("user32.dll")] static extern bool CloseClipboard();
        [DllImport("user32.dll")] static extern bool IsClipboardFormatAvailable(uint format);
        [DllImport("user32.dll")] static extern IntPtr GetClipboardData(uint format);
        [DllImport("user32.dll")] static extern uint EnumClipboardFormats(uint format);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "GetClipboardFormatNameW")]
        static extern int GetClipboardFormatName(uint format, StringBuilder name, int max);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "RegisterClipboardFormatW")] static extern uint RegisterClipboardFormat(string name);
        [DllImport("user32.dll")] static extern IntPtr GetProcessWindowStation();
        [DllImport("user32.dll", EntryPoint = "GetUserObjectInformationW")]
        static extern bool GetUserObjectInformation(IntPtr obj, int index, ref UserObjectFlags info, int length, out int needed);
        [DllImport("kernel32.dll")] static extern IntPtr GlobalLock(IntPtr mem);
        [DllImport("kernel32.dll")] static extern bool GlobalUnlock(IntPtr mem);
        [DllImport("kernel32.dll")] static extern UIntPtr GlobalSize(IntPtr mem);

        [StructLayout(LayoutKind.Sequential)]
        struct UserObjectFlags { public int Inherit; public int Reserved; public int Flags; }

        const uint CF_BITMAP = 2, CF_DIB = 8, CF_UNICODETEXT = 13, CF_DIBV5 = 17;
        // Assez pour que la route voie que le texte dépasse 100 000 caractères (elle le coupe et le signale), sans
        // envoyer un document entier sur la ligne de réponse.
        const int MaxClipboardText = 100001;
        // Formats posés par les gestionnaires de mots de passe : leur seule présence suffit.
        static readonly string[] SensitiveFormats = { "ExcludeClipboardContentFromMonitorProcessing", "Clipboard Viewer Ignore" };
        // Formats internes d'OLE, seuls restes d'un presse-papiers vidé par OleSetClipboard (Clipboard.Clear) : pas un contenu.
        static readonly string[] OleBookkeeping = { "DataObject", "Ole Private Data" };

        static Dictionary<string, object> ClipboardKind(string kind)
        {
            return new Dictionary<string, object> { { "kind", kind } };
        }

        /** Service en session 0, station sans bureau visible : pas de presse-papiers de l'utilisateur (503 côté compagnon). */
        static void EnsureClipboardSession()
        {
            bool available;
            using (var self = Process.GetCurrentProcess()) available = self.SessionId != 0;
            if (available)
            {
                var station = GetProcessWindowStation();
                var flags = new UserObjectFlags();
                int needed;
                available = station != IntPtr.Zero
                    && GetUserObjectInformation(station, 1 /* UOI_FLAGS */, ref flags, Marshal.SizeOf(flags), out needed)
                    && (flags.Flags & 1 /* WSF_VISIBLE */) != 0;
            }
            // Mot repéré par src/win/index.ts (CLIPBOARD_UNAVAILABLE de driver-common.ts) : à garder tel quel.
            if (!available) throw new Exception("Presse-papiers indisponible : aucune session de bureau (CLIPBOARD_UNAVAILABLE)");
        }

        static void OpenClipboardRetrying()
        {
            // Le presse-papiers peut être tenu un instant par une autre appli.
            for (int i = 0; ; i++)
            {
                if (OpenClipboard(IntPtr.Zero)) return;
                int error = Marshal.GetLastWin32Error();
                if (i >= 9) throw new Exception("Presse-papiers occupé par une autre application (erreur " + error + ")");
                Thread.Sleep(60);
            }
        }

        static object ClipboardGet()
        {
            EnsureClipboardSession();
            OpenClipboardRetrying();
            try
            {
                if (HasSensitiveMarker()) return ClipboardKind("protected");
                var text = ReadClipboardText();
                if (!string.IsNullOrEmpty(text)) return new Dictionary<string, object> { { "kind", "text" }, { "text", text } };
                if (IsClipboardFormatAvailable(CF_DIB) || IsClipboardFormatAvailable(CF_DIBV5) || IsClipboardFormatAvailable(CF_BITMAP)) return ClipboardKind("image");
                // Texte vide (comme sur le Mac) ou rien du tout : vide ; sinon liste de fichiers, etc.
                if (text != null || OnlyOleBookkeeping()) return ClipboardKind("empty");
                return ClipboardKind("other");
            }
            finally { CloseClipboard(); }
        }

        /** À appeler presse-papiers ouvert. Valeur de CanIncludeInClipboardHistory illisible : traitée comme sensible. */
        static bool HasSensitiveMarker()
        {
            foreach (var name in SensitiveFormats)
            {
                uint format = RegisterClipboardFormat(name);
                if (format != 0 && IsClipboardFormatAvailable(format)) return true;
            }
            uint history = RegisterClipboardFormat("CanIncludeInClipboardHistory");
            if (history == 0 || !IsClipboardFormatAvailable(history)) return false;
            // DWORD : 0 = à garder hors de l'historique (mot de passe), 1 = autorisé.
            var data = GetClipboardData(history);
            if (data == IntPtr.Zero || GlobalSize(data).ToUInt64() < 4) return true;
            var p = GlobalLock(data);
            if (p == IntPtr.Zero) return true;
            try { return Marshal.ReadInt32(p) == 0; }
            finally { GlobalUnlock(data); }
        }

        /** Aucun format, ou seulement ceux d'OLE. Presse-papiers ouvert. */
        static bool OnlyOleBookkeeping()
        {
            var name = new StringBuilder(64);
            uint format = 0;
            while ((format = EnumClipboardFormats(format)) != 0)
            {
                name.Length = 0;
                if (GetClipboardFormatName(format, name, name.Capacity) == 0 || Array.IndexOf(OleBookkeeping, name.ToString()) < 0) return false;
            }
            return true;
        }

        /** Texte Unicode (au plus MaxClipboardText caractères) ; null s'il n'y en a pas ou s'il est illisible. Presse-papiers ouvert. */
        static string ReadClipboardText()
        {
            if (!IsClipboardFormatAvailable(CF_UNICODETEXT)) return null;
            var data = GetClipboardData(CF_UNICODETEXT);
            if (data == IntPtr.Zero) return null;
            var p = GlobalLock(data);
            if (p == IntPtr.Zero) return null;
            try
            {
                // Jusqu'au zéro final, sans sortir du bloc mémoire.
                long max = Math.Min((long)(GlobalSize(data).ToUInt64() / 2), MaxClipboardText);
                int length = 0;
                while (length < max && Marshal.ReadInt16(p, length * 2) != 0) length++;
                return Marshal.PtrToStringUni(p, length);
            }
            finally { GlobalUnlock(data); }
        }

        static void ClipboardSetText(string text)
        {
            if (string.IsNullOrEmpty(text)) throw new ArgumentException("Texte à copier manquant");
            EnsureClipboardSession();
            SetClipboard(text);
        }

        /** Image d'un fichier (jpg, png) copiée comme image, redressée selon son orientation EXIF (photos de la tablette). */
        static void ClipboardSetImage(string path)
        {
            if (string.IsNullOrEmpty(path)) throw new ArgumentException("Chemin de l'image manquant");
            EnsureClipboardSession();
            Bitmap bitmap;
            try
            {
                // Copie en Bitmap : Image.FromFile garde le fichier ouvert tant que l'image vit.
                using (var image = Image.FromFile(path))
                {
                    bitmap = new Bitmap(image);
                    var turn = ExifRotation(image);
                    if (turn != RotateFlipType.RotateNoneFlipNone) bitmap.RotateFlip(turn);
                }
            }
            catch (OutOfMemoryException) { throw new Exception("Image illisible"); } // GDI+ : format non reconnu
            using (bitmap)
            {
                for (int i = 0; ; i++)
                {
                    try { Clipboard.SetImage(bitmap); return; }
                    catch (ExternalException) { if (i >= 5) throw; Thread.Sleep(60); }
                }
            }
        }

        static RotateFlipType ExifRotation(Image image)
        {
            if (Array.IndexOf(image.PropertyIdList, 0x0112) < 0) return RotateFlipType.RotateNoneFlipNone;
            var value = image.GetPropertyItem(0x0112).Value;
            if (value == null || value.Length < 2) return RotateFlipType.RotateNoneFlipNone;
            switch (BitConverter.ToUInt16(value, 0))
            {
                case 2: return RotateFlipType.RotateNoneFlipX;
                case 3: return RotateFlipType.Rotate180FlipNone;
                case 4: return RotateFlipType.Rotate180FlipX;
                case 5: return RotateFlipType.Rotate90FlipX;
                case 6: return RotateFlipType.Rotate90FlipNone;
                case 7: return RotateFlipType.Rotate270FlipX;
                case 8: return RotateFlipType.Rotate270FlipNone;
                default: return RotateFlipType.RotateNoneFlipNone;
            }
        }
    }
}
