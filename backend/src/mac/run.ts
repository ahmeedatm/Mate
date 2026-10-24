import { execFile } from "node:child_process";

/** Lance une commande sans shell (pas d'injection possible via les arguments). */
export function run(cmd: string, args: string[], timeoutMs = 10_000): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: timeoutMs }, (err, stdout, stderr) => {
      if (err) reject(new Error((stderr || err.message).trim()));
      else resolve(stdout.trim());
    });
  });
}

/** AppleScript avec arguments passés via `on run argv`, jamais concaténés dans le script. */
export function osascript(script: string, args: string[] = [], language: "AppleScript" | "JavaScript" = "AppleScript") {
  return run("osascript", ["-l", language, "-e", script, ...args]).catch((err: Error) => {
    // -25211 / -1719 / 1002 : l'appli qui a lancé le backend (node pour le service, sinon Terminal, VS Code…)
    // n'a pas l'accès Accessibilité.
    if (/-25211|-1719|\(1002\)/.test(err.message)) {
      throw new Error(
        "Accès Accessibilité refusé : autorise « node » (service automatique) ou le terminal du backend dans Réglages Système > Confidentialité > Accessibilité",
      );
    }
    throw err;
  });
}
