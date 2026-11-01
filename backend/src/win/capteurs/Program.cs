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
     * Écrit la température, la puissance (watts) et le ventilateur du processeur, et la puissance de la carte
     * graphique dans %LOCALAPPDATA%\Mate\capteurs\cpu-temp.json, toutes les 2 s, pour le compagnon (src/win/capteurs.ts)
     * qui ne peut pas lire le pilote noyau sans droits administrateur. Une valeur illisible est omise du fichier ;
     * rien de lisible : le fichier n'est pas réécrit (le compagnon le juge périmé et n'affiche rien). Une seule instance.
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
                var computer = new Computer { IsCpuEnabled = true, IsGpuEnabled = true, IsMotherboardEnabled = true };
                try { computer.Open(); }
                catch (Exception e) { Console.Error.WriteLine("Pilote illisible : " + e.Message); return 1; }
                while (true)
                {
                    var reading = Read(computer);
                    if (reading.Count > 0) Write(file, reading);
                    Thread.Sleep(PeriodMs);
                }
            }
        }

        // Champ du fichier → valeur lue ; un capteur illisible n'a pas d'entrée.
        static Dictionary<string, double> Read(Computer computer)
        {
            var reading = new Dictionary<string, double>();
            try
            {
                foreach (var hardware in computer.Hardware) hardware.Update();
                var temp = CpuTemperature(computer);
                if (temp != null) reading["cpuTempC"] = temp.Value;
                var watts = CpuPackagePower(computer);
                if (watts != null) reading["cpuWatts"] = watts.Value;
                var fan = CpuFan(computer);
                if (fan != null) reading["cpuFanRpm"] = fan.Value;
                var gpu = GpuPower(computer);
                if (gpu != null) reading["gpuWatts"] = gpu.Value;
            }
            catch (Exception) { }
            return reading;
        }

        static double? CpuTemperature(Computer computer)
        {
            var temps = new List<ISensor>();
            foreach (var hardware in computer.Hardware.Where(h => h.HardwareType == HardwareType.Cpu))
                temps.AddRange(hardware.Sensors.Where(s => s.SensorType == SensorType.Temperature && s.Value.HasValue && s.Value.Value > 0));
            foreach (var name in Preferred)
            {
                var sensor = temps.FirstOrDefault(s => s.Name == name);
                if (sensor != null) return sensor.Value;
            }
            return temps.Count > 0 ? temps.Max(s => s.Value) : null;
        }

        // Puissance totale du processeur : « CPU Package » (Intel) ou « Package » (AMD Ryzen, nom à vérifier sur un vrai PC).
        static double? CpuPackagePower(Computer computer)
        {
            foreach (var hardware in computer.Hardware.Where(h => h.HardwareType == HardwareType.Cpu))
            {
                var sensor = hardware.Sensors.FirstOrDefault(s => s.SensorType == SensorType.Power && (s.Name == "CPU Package" || s.Name == "Package") && s.Value.HasValue && s.Value.Value > 0);
                if (sensor != null) return sensor.Value;
            }
            return null;
        }

        // Ventilateur de la carte mère (puce Super I/O) nommé « CPU Fan » ou « CPU » ; sans lui, rien (pas de ventilateur deviné).
        static double? CpuFan(Computer computer)
        {
            foreach (var board in computer.Hardware.Where(h => h.HardwareType == HardwareType.Motherboard))
            {
                foreach (var chip in board.SubHardware)
                {
                    chip.Update();
                    var sensor = chip.Sensors.FirstOrDefault(s => s.SensorType == SensorType.Fan && (s.Name == "CPU Fan" || s.Name == "CPU") && s.Value.HasValue && s.Value.Value > 0);
                    if (sensor != null) return sensor.Value;
                }
            }
            return null;
        }

        // Puissance de la première carte graphique (dédiée avant intégrée) qui expose un capteur « Power ».
        static double? GpuPower(Computer computer)
        {
            var types = new[] { HardwareType.GpuNvidia, HardwareType.GpuAmd, HardwareType.GpuIntel };
            foreach (var hardware in computer.Hardware.Where(h => types.Contains(h.HardwareType)).OrderBy(h => h.HardwareType == HardwareType.GpuIntel ? 1 : 0))
            {
                var sensor = hardware.Sensors.FirstOrDefault(s => s.SensorType == SensorType.Power && s.Value.HasValue && s.Value.Value > 0);
                if (sensor != null) return sensor.Value;
            }
            return null;
        }

        static void Write(string file, Dictionary<string, double> reading)
        {
            var at = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
            var fields = reading.Select(kv => "\"" + kv.Key + "\":" + kv.Value.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture));
            var json = "{" + string.Join(",", fields) + ",\"at\":" + at + "}";
            // Écriture dans un fichier voisin puis remplacement : le compagnon ne lit jamais un fichier à moitié écrit.
            var tmp = file + ".tmp";
            File.WriteAllText(tmp, json, new UTF8Encoding(false));
            if (File.Exists(file)) File.Replace(tmp, file, null); else File.Move(tmp, file);
        }
    }
}
