import { execFile } from "node:child_process";

/**
 * Environnement en UTF-8 pour pbcopy / pbpaste : ils lisent et écrivent le texte selon LANG (man pbcopy), que le
 * service launchd ne définit pas (scripts/service.ts ne passe que PATH) ; sans lui, le texte passe en MacRoman et les
 * accents sont abîmés. LANG plutôt que LC_CTYPE : c'est la variable que documente le manuel de ces deux commandes.
 */
export const UTF8_ENV = { ...process.env, LANG: "en_US.UTF-8" };

/** Lance une commande sans shell (pas d'injection possible via les arguments). `env` : environnement de la commande (celui du serveur par défaut). */
export function run(cmd: string, args: string[], timeoutMs = 10_000, env?: NodeJS.ProcessEnv): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout: timeoutMs, env }, (err, stdout, stderr) => {
      // `technical` : texte brut de la commande, gardé dans le journal plutôt que montré sur la tablette (server.ts).
      if (err) reject(Object.assign(new Error((stderr || err.message).trim()), { technical: true }));
      else resolve(stdout.trim());
    });
  });
}

/**
 * AppleScript avec arguments passés via `on run argv`, jamais concaténés dans le script. `--` : un argument qui commence
 * par `-` (texte tapé, `-e…`) n'est jamais lu comme une option d'osascript, donc jamais comme un script de plus.
 */
export function osascript(
  script: string,
  args: string[] = [],
  language: "AppleScript" | "JavaScript" = "AppleScript",
  env?: NodeJS.ProcessEnv,
) {
  return run("osascript", ["-l", language, "-e", script, "--", ...args], 10_000, env).catch((err: Error) => {
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
