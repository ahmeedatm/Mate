// Compagnon Unowhy sous Windows : un seul exe, sans installation. Le runtime (node, serveur, pilote, config) est un zip
// ajouté en fin de fichier (scripts/package.ts). Au lancement, l'exe l'extrait dans %LOCALAPPDATA%\UnowhyCompanion\runtime
// (chemin stable, l'exe lui-même n'est jamais verrouillé par node), lance le serveur, le relance s'il s'arrête, et
// ouvre le panneau (page locale). Rien n'est inscrit dans Windows : le lancement au démarrage se coche dans le panneau.
// Programme sans fenêtre : pas de PowerShell caché, que les antivirus prennent pour un logiciel malveillant.
//
//   UnowhyCompanion.exe [--demarrage]          appli portable ; --demarrage : ouverture de session, sans ouvrir la page
//   UnowhyCompanion.exe --node <node.exe> --backend <dossier backend> --logs <dossier> --data <dossier>
//                                              lancé depuis le dépôt par scripts/service.ts : relance seulement
//
// Sortie du serveur : 75 = quitter (panneau), 76 = quitter et effacer le runtime (« tout oublier »), autre = relancer.
// COMPANION_HEADLESS=1 (qa/install-windows.ps1) : ni page ni boîte de message.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace Companion
{
    static class Launcher
    {
        const int QuitCode = 75;
        const int ForgetCode = 76;
        const string Magic = "UNOWHYZ1";

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern int MessageBox(IntPtr window, string text, string caption, uint type);

        static bool Headless { get { return Environment.GetEnvironmentVariable("COMPANION_HEADLESS") == "1"; } }

        static int Port
        {
            get
            {
                int port;
                return int.TryParse(Environment.GetEnvironmentVariable("PORT"), out port) ? port : 3000;
            }
        }

        static void Alert(string text)
        {
            if (!Headless) MessageBox(IntPtr.Zero, text, "Compagnon Unowhy", 0x10);
        }

        static int Main(string[] argv)
        {
            var startup = Array.IndexOf(argv, "--demarrage") >= 0;
            var args = new Dictionary<string, string>();
            for (int i = 0; i + 1 < argv.Length; i += 2) args[argv[i].TrimStart('-')] = argv[i + 1];
            string node, backend, logs, data;
            if (args.TryGetValue("node", out node) && args.TryGetValue("backend", out backend) && args.TryGetValue("logs", out logs))
            {
                if (!args.TryGetValue("data", out data)) data = Path.Combine(backend, ".data");
                return Supervise(node, backend, logs, data, null);
            }
            return Portable(startup);
        }

        // --- Appli portable

        static int Portable(bool startup)
        {
            var self = Assembly.GetExecutingAssembly().Location;
            var root = Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA") ?? Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UnowhyCompanion");
            var runtime = Environment.GetEnvironmentVariable("COMPANION_RUNTIME_DIR");
            if (string.IsNullOrEmpty(runtime)) runtime = Path.Combine(root, "runtime");
            var data = Path.Combine(root, "data");
            TryDelete(self + ".old");

            string stamp;
            var zip = OpenPayload(self, out stamp);
            if (zip == null)
            {
                Alert("Cet exécutable ne contient pas le compagnon : télécharge « Compagnon-Unowhy-Windows.exe » depuis la tablette.");
                return 2;
            }

            var installed = Path.Combine(runtime, "runtime.stamp");
            var current = File.Exists(installed) ? File.ReadAllText(installed).Trim() : null;
            if (Health())
            {
                if (current == stamp)
                {
                    if (!startup) OpenPage();
                    return 0;
                }
                if (!TakeOver(data))
                {
                    Alert("Un compagnon tourne déjà sur le port " + Port + " (autre version, ou lancé depuis le dépôt) : arrête-le, puis rouvre l'appli.");
                    return 1;
                }
            }

            if (current != stamp || !File.Exists(Path.Combine(runtime, "node", "node.exe")))
            {
                try { Extract(zip, runtime, stamp); }
                catch (Exception e)
                {
                    Alert("Le compagnon n'a pas pu être préparé dans " + runtime + " : " + e.Message);
                    return 1;
                }
            }
            zip.Dispose();

            Environment.SetEnvironmentVariable("COMPANION_APP", self);
            if (!startup && !Headless) new Thread(OpenPageWhenReady) { IsBackground = true }.Start();
            var logDir = Environment.GetEnvironmentVariable("COMPANION_LOG_DIR");
            return Supervise(Path.Combine(runtime, "node", "node.exe"), runtime, string.IsNullOrEmpty(logDir) ? root : logDir, data, runtime);
        }

        /// <summary>Zip du runtime ajouté à l'exe : [zip][taille du zip, 8 octets][UNOWHYZ1]. Null sans runtime.</summary>
        static ZipArchive OpenPayload(string exe, out string stamp)
        {
            stamp = null;
            using (var file = new FileStream(exe, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            {
                if (file.Length < 16) return null;
                var trailer = new byte[16];
                file.Seek(-16, SeekOrigin.End);
                file.Read(trailer, 0, 16);
                if (Encoding.ASCII.GetString(trailer, 8, 8) != Magic) return null;
                var length = BitConverter.ToInt64(trailer, 0);
                if (length <= 0 || length > file.Length - 16) return null;
                var bytes = new byte[length];
                file.Seek(-16 - length, SeekOrigin.End);
                int done = 0;
                while (done < bytes.Length)
                {
                    int n = file.Read(bytes, done, bytes.Length - done);
                    if (n <= 0) return null;
                    done += n;
                }
                var zip = new ZipArchive(new MemoryStream(bytes), ZipArchiveMode.Read);
                var entry = zip.GetEntry("runtime.stamp");
                if (entry == null) return null;
                using (var reader = new StreamReader(entry.Open())) stamp = reader.ReadToEnd().Trim();
                return zip;
            }
        }

        /// <summary>Fichiers du zip dans le dossier du runtime ; .env et .cache gardés ; le repère est écrit en dernier.</summary>
        static void Extract(ZipArchive zip, string runtime, string stamp)
        {
            Directory.CreateDirectory(runtime);
            var dist = Path.Combine(runtime, "dist");
            if (Directory.Exists(dist)) Directory.Delete(dist, true);
            var full = Path.GetFullPath(runtime).TrimEnd('\\') + "\\";
            foreach (var entry in zip.Entries)
            {
                if (entry.FullName.EndsWith("/") || entry.FullName == "runtime.stamp") continue;
                var dest = Path.GetFullPath(Path.Combine(runtime, entry.FullName.Replace('/', '\\')));
                if (!dest.StartsWith(full, StringComparison.OrdinalIgnoreCase)) continue;
                Directory.CreateDirectory(Path.GetDirectoryName(dest));
                entry.ExtractToFile(dest, true);
            }
            File.WriteAllText(Path.Combine(runtime, "runtime.stamp"), stamp);
        }

        /// <summary>
        /// Un autre compagnon occupe le port : on lui demande de quitter (panneau d'une autre version) ; sinon c'est une
        /// ancienne installation, dont la boucle (UnowhyCompanion.exe du dossier Programs) est arrêtée pour la session.
        /// Un compagnon lancé depuis le dépôt n'est jamais touché.
        /// </summary>
        static bool TakeOver(string data)
        {
            Http("POST", "/local/quit", "{}");
            if (WaitDown(15)) return true;
            try
            {
                var pidFile = Path.Combine(data, "service.pid");
                int pid;
                if (File.Exists(pidFile) && int.TryParse(File.ReadAllText(pidFile).Trim(), out pid))
                {
                    var process = Process.GetProcessById(pid);
                    var exe = process.MainModule.FileName;
                    var installed = Path.Combine(Path.GetDirectoryName(exe), "..", "..", "node", "node.exe");
                    if (process.ProcessName == "UnowhyCompanion" && File.Exists(installed) && pid != Process.GetCurrentProcess().Id)
                    {
                        var kill = Process.Start(new ProcessStartInfo("taskkill", "/F /T /PID " + pid) { CreateNoWindow = true, UseShellExecute = false });
                        kill.WaitForExit();
                    }
                }
            }
            catch (Exception) { }
            return WaitDown(10);
        }

        static bool WaitDown(int seconds)
        {
            for (int i = 0; i < seconds * 2; i++)
            {
                if (!Health()) return true;
                Thread.Sleep(500);
            }
            return !Health();
        }

        static bool Http(string method, string path, string body)
        {
            try
            {
                var request = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + Port + path);
                request.Method = method;
                request.Timeout = 3000;
                request.Proxy = null;
                if (body != null)
                {
                    var bytes = Encoding.UTF8.GetBytes(body);
                    request.ContentType = "application/json";
                    request.ContentLength = bytes.Length;
                    using (var stream = request.GetRequestStream()) stream.Write(bytes, 0, bytes.Length);
                }
                using (var response = (HttpWebResponse)request.GetResponse()) return (int)response.StatusCode < 300;
            }
            catch (Exception) { return false; }
        }

        static bool Health() { return Http("GET", "/health", null); }

        static void OpenPage()
        {
            if (Headless) return;
            try { Process.Start(new ProcessStartInfo("http://127.0.0.1:" + Port + "/") { UseShellExecute = true }); }
            catch (Exception) { }
        }

        static void OpenPageWhenReady()
        {
            for (int i = 0; i < 120; i++)
            {
                if (Health()) { OpenPage(); return; }
                Thread.Sleep(500);
            }
        }

        static void TryDelete(string file)
        {
            try { File.Delete(file); } catch (Exception) { }
        }

        // --- Boucle de relance

        /// <summary>Lance le serveur et le relance s'il s'arrête. `runtime` non nul : appli portable (effacé après « tout oublier »).</summary>
        static int Supervise(string node, string backend, string logs, string data, string runtime)
        {
            Directory.CreateDirectory(logs);
            Directory.CreateDirectory(data);
            // PID de cette boucle : `npm run service:stop` ou le prochain lanceur l'arrête avec node (taskkill /T).
            File.WriteAllText(Path.Combine(data, "service.pid"), Process.GetCurrentProcess().Id.ToString());

            // Paquet client : serveur regroupé dans dist\server.mjs ; sinon les sources, par tsx.
            var entry = File.Exists(Path.Combine(backend, "dist", "server.mjs"))
                ? "--env-file-if-exists=.env dist/server.mjs"
                : "--env-file-if-exists=.env --import tsx src/server.ts";

            while (true)
            {
                int code = -1;
                try { code = Run(node, entry, backend, logs); }
                catch (Exception e) { File.AppendAllText(Path.Combine(logs, "backend-erreurs.log"), DateTime.Now + " " + e.Message + Environment.NewLine); }
                if (code == QuitCode || code == ForgetCode)
                {
                    TryDelete(Path.Combine(data, "service.pid"));
                    if (code == ForgetCode && runtime != null) { try { Directory.Delete(runtime, true); } catch (Exception) { } }
                    return 0;
                }
                Thread.Sleep(10000);
            }
        }

        static int Run(string node, string entry, string backend, string logs)
        {
            var info = new ProcessStartInfo(node, entry)
            {
                WorkingDirectory = backend,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using (var process = Process.Start(info))
            using (var output = OpenLog(logs, "backend.log"))
            using (var errors = OpenLog(logs, "backend-erreurs.log"))
            {
                var copies = new[]
                {
                    Copy(process.StandardOutput.BaseStream, output),
                    Copy(process.StandardError.BaseStream, errors),
                };
                process.WaitForExit();
                foreach (var copy in copies) copy.Join();
                return process.ExitCode;
            }
        }

        // Journal gardé d'une relance à l'autre (la cause d'un plantage reste lisible) : au-delà de 5 Mo, l'ancien passe en .1.
        static FileStream OpenLog(string logs, string name)
        {
            var path = Path.Combine(logs, name);
            if (File.Exists(path) && new FileInfo(path).Length > 5 * 1024 * 1024)
            {
                File.Delete(path + ".1");
                File.Move(path, path + ".1");
            }
            var stream = new FileStream(path, FileMode.Append, FileAccess.Write, FileShare.Read);
            var mark = Encoding.UTF8.GetBytes("--- démarrage " + DateTime.Now + Environment.NewLine);
            stream.Write(mark, 0, mark.Length);
            stream.Flush();
            return stream;
        }

        // Journal écrit au fil de l'eau (lisible pendant que le compagnon tourne).
        static Thread Copy(Stream from, FileStream to)
        {
            var thread = new Thread(() =>
            {
                var buffer = new byte[4096];
                int n;
                while ((n = from.Read(buffer, 0, buffer.Length)) > 0)
                {
                    to.Write(buffer, 0, n);
                    to.Flush();
                }
            }) { IsBackground = true };
            thread.Start();
            return thread;
        }
    }
}
