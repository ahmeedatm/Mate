using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using LibreHardwareMonitor.Hardware;

namespace Mate.Capteurs
{
    /**
     * Écrit la température du processeur dans %LOCALAPPDATA%\Mate\capteurs\cpu-temp.json, toutes les 2 s, pour le
     * compagnon (src/win/capteurs.ts) qui ne peut pas lire le pilote noyau sans droits administrateur. Une seule
     * instance. Capteur illisible : le fichier n'est pas réécrit (le compagnon le juge périmé et n'affiche rien).
     */
    static class Program
    {
        const int PeriodMs = 2000;
        // Sonde globale d'abord (Intel : package ; AMD : Tctl/Tdie), sinon la plus chaude des sondes de cœur.
        static readonly string[] Preferred = { "CPU Package", "Core (Tctl/Tdie)", "Core Max", "CPU Total", "Package" };

        static int Main()
        {
            bool created;
            using (new Mutex(true, "Mate.Capteurs", out created))
            {
                if (!created) return 0;
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Mate", "capteurs");
                Directory.CreateDirectory(dir);
                var file = Path.Combine(dir, "cpu-temp.json");
                var computer = new Computer { IsCpuEnabled = true };
                try { computer.Open(); }
                catch (Exception e) { Console.Error.WriteLine("Pilote illisible : " + e.Message); return 1; }
                while (true)
                {
                    var temp = Read(computer);
                    if (temp != null) Write(file, temp.Value);
                    Thread.Sleep(PeriodMs);
                }
            }
        }

        static double? Read(Computer computer)
        {
            try
            {
                var temps = new List<ISensor>();
                foreach (var hardware in computer.Hardware.Where(h => h.HardwareType == HardwareType.Cpu))
                {
                    hardware.Update();
                    temps.AddRange(hardware.Sensors.Where(s => s.SensorType == SensorType.Temperature && s.Value.HasValue && s.Value.Value > 0));
                }
                foreach (var name in Preferred)
                {
                    var sensor = temps.FirstOrDefault(s => s.Name == name);
                    if (sensor != null) return sensor.Value;
                }
                return temps.Count > 0 ? temps.Max(s => s.Value) : null;
            }
            catch (Exception) { return null; }
        }

        static void Write(string file, double celsius)
        {
            var at = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
            var json = "{\"cpuTempC\":" + celsius.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture) + ",\"at\":" + at + "}";
            // Écriture dans un fichier voisin puis remplacement : le compagnon ne lit jamais un fichier à moitié écrit.
            var tmp = file + ".tmp";
            File.WriteAllText(tmp, json, new UTF8Encoding(false));
            if (File.Exists(file)) File.Replace(tmp, file, null); else File.Move(tmp, file);
        }
    }
}
