// Lance le backend du compagnon sous Windows et le relance s'il s'arrête (POST /companion/restart, plantage).
// Entrée « Exécuter » de la session (voir scripts/service.ts). Programme sans fenêtre : pas de PowerShell caché,
// que les antivirus prennent pour un logiciel malveillant.
//
// UnowhyCompanion.exe --node <node.exe> --backend <dossier backend> --logs <dossier> --data <dossier>

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace Companion
{
    static class Launcher
    {
        static int Main(string[] argv)
        {
            var args = new Dictionary<string, string>();
            for (int i = 0; i + 1 < argv.Length; i += 2) args[argv[i].TrimStart('-')] = argv[i + 1];
            string node, backend, logs, data;
            if (!args.TryGetValue("node", out node) || !args.TryGetValue("backend", out backend) || !args.TryGetValue("logs", out logs)) return 2;
            if (!args.TryGetValue("data", out data)) data = Path.Combine(backend, ".data");
            Directory.CreateDirectory(logs);
            Directory.CreateDirectory(data);
            // PID de cette boucle : `npm run service:stop` l'arrête avec node (taskkill /T).
            File.WriteAllText(Path.Combine(data, "service.pid"), Process.GetCurrentProcess().Id.ToString());

            // Paquet client : serveur regroupé dans dist\server.mjs ; sinon les sources, par tsx.
            var entry = File.Exists(Path.Combine(backend, "dist", "server.mjs"))
                ? "--env-file-if-exists=.env dist/server.mjs"
                : "--env-file-if-exists=.env --import tsx src/server.ts";

            while (true)
            {
                try { Run(node, entry, backend, logs); }
                catch (Exception e) { File.AppendAllText(Path.Combine(logs, "backend-erreurs.log"), DateTime.Now + " " + e.Message + Environment.NewLine); }
                Thread.Sleep(10000);
            }
        }

        static void Run(string node, string entry, string backend, string logs)
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
            var mark = System.Text.Encoding.UTF8.GetBytes("--- démarrage " + DateTime.Now + Environment.NewLine);
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
