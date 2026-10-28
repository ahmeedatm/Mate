using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Companion
{
    public static partial class Native
    {
        // --- Pavé tactile : souris synthétique (SendInput)

        const uint InputMouse = 0;
        const uint MouseMove = 0x0001, LeftDown = 0x0002, LeftUp = 0x0004, RightDown = 0x0008, RightUp = 0x0010;
        const uint MouseWheel = 0x0800, MouseHWheel = 0x1000;
        const int WheelDelta = 120;

        // Disposition x64 : INPUT = 40 octets (type sur 4, remplissage, union alignée sur 8) ; x86 : 28.
        [StructLayout(LayoutKind.Sequential)]
        struct MouseInput
        {
            public int dx;
            public int dy;
            public int mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Explicit)]
        struct InputUnion
        {
            [FieldOffset(0)] public MouseInput mi;
            // Le clavier est la plus grande variante de l'union : elle fixe la taille de INPUT, comme dans l'API Windows.
            [FieldOffset(0)] public long reserved0;
            [FieldOffset(8)] public long reserved1;
            [FieldOffset(16)] public long reserved2;
            [FieldOffset(24)] public long reserved3;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct Input
        {
            public uint type;
            public InputUnion u;
        }

        [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint count, Input[] inputs, int size);

        static void SendMouse(int dx, int dy, int data, uint flags)
        {
            var input = new Input { type = InputMouse };
            input.u.mi = new MouseInput { dx = dx, dy = dy, mouseData = data, dwFlags = flags };
            if (SendInput(1, new[] { input }, Marshal.SizeOf(typeof(Input))) != 1)
                throw new Exception("SendInput refusé (code " + Marshal.GetLastWin32Error() + ")");
        }

        /**
         * Pavé tactile : {kind:"move",dx,dy} (pixels, relatif : Windows y applique son accélération du pointeur, la tablette
         * a déjà sa propre courbe), {kind:"scroll",sx,sy} (crans de molette ; sy > 0 vers le haut et sx > 0 vers la droite,
         * comme WHEEL_DELTA), {kind:"zoom",steps} (Ctrl + molette, steps > 0 = agrandir), {kind:"button",button:"left"|"right",action:"click"|"down"|"up"}.
         */
        static bool Pointer(Dictionary<string, object> req)
        {
            switch (Str(req, "kind"))
            {
                case "move":
                {
                    int dx = Int(req, "dx", 0), dy = Int(req, "dy", 0);
                    if (dx != 0 || dy != 0) SendMouse(dx, dy, 0, MouseMove);
                    return true;
                }
                case "scroll":
                {
                    int sx = Int(req, "sx", 0), sy = Int(req, "sy", 0);
                    if (sy != 0) SendMouse(0, 0, sy * WheelDelta, MouseWheel);
                    if (sx != 0) SendMouse(0, 0, sx * WheelDelta, MouseHWheel);
                    return true;
                }
                case "button":
                {
                    string button = Str(req, "button"), action = Str(req, "action");
                    if (button != "left" && button != "right") throw new Exception("Bouton inconnu : " + button);
                    if (action != "click" && action != "down" && action != "up") throw new Exception("Action inconnue : " + action);
                    uint down = button == "left" ? LeftDown : RightDown, up = button == "left" ? LeftUp : RightUp;
                    if (action != "up") SendMouse(0, 0, 0, down);
                    if (action != "down") SendMouse(0, 0, 0, up);
                    return true;
                }
                case "zoom":
                {
                    // Ctrl enfoncé, `steps` crans de molette (positif = agrandir), Ctrl relâché même en cas d'échec.
                    int steps = Int(req, "steps", 0);
                    if (Math.Abs(steps) > 20) throw new Exception("Zoom hors bornes : " + steps);
                    if (steps == 0) return true;
                    keybd_event(0x11, 0, 0, UIntPtr.Zero);
                    try
                    {
                        for (int i = 0; i < Math.Abs(steps); i++) SendMouse(0, 0, Math.Sign(steps) * WheelDelta, MouseWheel);
                    }
                    finally { keybd_event(0x11, 0, 2, UIntPtr.Zero); }
                    return true;
                }
                default:
                    throw new Exception("Opération pointer inconnue : " + Str(req, "kind"));
            }
        }
    }
}
