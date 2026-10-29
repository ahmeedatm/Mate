using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace UnoMate
{
    public static partial class Native
    {
        // --- Icônes : image du Shell (celle du menu Démarrer), transparence comprise

        [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IShellItemImageFactory
        {
            [PreserveSig] int GetImage(SIZE size, int flags, out IntPtr phbm);
        }

        [StructLayout(LayoutKind.Sequential)]
        struct SIZE { public int cx; public int cy; }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
        static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
            [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory factory);

        [DllImport("gdi32.dll")]
        static extern bool DeleteObject(IntPtr h);

        static bool AppIcon(string id, string outPath, int size)
        {
            IShellItemImageFactory factory;
            SHCreateItemFromParsingName("shell:AppsFolder\\" + id, IntPtr.Zero, typeof(IShellItemImageFactory).GUID, out factory);
            IntPtr hbmp;
            var sz = new SIZE();
            sz.cx = size;
            sz.cy = size;
            // SIIGBF_BIGGERSIZEOK | SIIGBF_ICONONLY
            int hr = factory.GetImage(sz, 0x1 | 0x4, out hbmp);
            Marshal.ReleaseComObject(factory);
            if (hr != 0 || hbmp == IntPtr.Zero) return false;
            try
            {
                using (Bitmap bmp = WithAlpha(hbmp)) bmp.Save(outPath, ImageFormat.Png);
                return true;
            }
            finally { DeleteObject(hbmp); }
        }

        /** Image.FromHbitmap perd la transparence : on recopie les octets (alpha prémultiplié) dans un bitmap PArgb. */
        static Bitmap WithAlpha(IntPtr hbmp)
        {
            using (Bitmap src = Image.FromHbitmap(hbmp))
            {
                int w = src.Width, h = src.Height;
                var rect = new Rectangle(0, 0, w, h);
                var px = new int[w * h];
                BitmapData sd = src.LockBits(rect, ImageLockMode.ReadOnly, src.PixelFormat);
                try
                {
                    for (int y = 0; y < h; y++) Marshal.Copy(new IntPtr(sd.Scan0.ToInt64() + (long)y * sd.Stride), px, y * w, w);
                }
                finally { src.UnlockBits(sd); }
                bool transparent = false, opaque = false;
                foreach (int p in px)
                {
                    int a = (p >> 24) & 0xff;
                    if (a < 255) transparent = true;
                    if (a > 0) opaque = true;
                }
                // Aucun octet alpha utile : icône ancienne sans transparence.
                if (!(transparent && opaque)) return new Bitmap(src);
                var dst = new Bitmap(w, h, PixelFormat.Format32bppPArgb);
                BitmapData dd = dst.LockBits(rect, ImageLockMode.WriteOnly, PixelFormat.Format32bppPArgb);
                try
                {
                    for (int y = 0; y < h; y++) Marshal.Copy(px, y * w, new IntPtr(dd.Scan0.ToInt64() + (long)y * dd.Stride), w);
                }
                finally { dst.UnlockBits(dd); }
                return dst;
            }
        }
    }
}
