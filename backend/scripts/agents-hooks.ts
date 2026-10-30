import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { hooksBlock, mergeAgentHooks, withoutAgentHooks, withoutStatusLine, withStatusLine, type Settings } from "../src/agents-hooks.ts";
import { hookSecret } from "../src/agents-secret.ts";
import { config } from "../src/config.ts";

/**
 * Hooks HTTP de Claude Code pour les agents de la tablette.
 *
 *   npm run agents:hooks                      affiche le bloc à coller dans settings.json
 *   npm run agents:hooks -- --install         le fusionne dans ~/.claude/settings.json (sauvegarde avant)
 *   npm run agents:hooks -- --remove          retire les entrées du compagnon
 *   --statusline      avec --install : enveloppe aussi la statusline (quota 5 h / 7 j), sa sortie ne change pas
 *   --file <chemin>   autre settings.json ; --port <n>, --hold <s>
 *
 * Le secret et le port viennent des données de CET ordinateur : pour un compagnon installé par paquet,
 * lancer avec son dossier de données : Mac `DATA_DIR="$HOME/Library/Application Support/UnoMate" PORT=3000 npm run agents:hooks`,
 * Windows (Git Bash) `DATA_DIR="$LOCALAPPDATA/UnoMate/data" PORT=3000 npm run agents:hooks`.
 * Windows : Claude Code lance la commande de la statusline par Git Bash (vérifié pour les hooks `command`), qui a `curl` ;
 * l'enveloppe est la même que sur Mac. Les hooks du compagnon sont de type `http` : aucun shell.
 */

const args = process.argv.slice(2);
const flag = (name: string) => args.includes(name);
const value = (name: string) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);

const port = Number(value("--port") ?? config.port);
const holdS = Number(value("--hold") ?? process.env.AGENTS_HOLD_S ?? 90);
const block = hooksBlock({ port, secret: hookSecret(), holdS });
const file = value("--file") ?? path.join(process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), ".claude"), "settings.json");

function load(): Settings {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8")) as Settings;
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === "ENOENT") return {};
    throw new Error(`${file} n'est pas un JSON lisible : ${(e as Error).message}`);
  }
}

function save(next: Settings) {
  const stamp = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
  if (fs.existsSync(file)) {
    const backup = `${file}.bak-agents-${stamp}`;
    fs.copyFileSync(file, backup);
    console.log(`Sauvegarde : ${backup}`);
  } else {
    fs.mkdirSync(path.dirname(file), { recursive: true });
  }
  const tmp = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, JSON.stringify(next, null, 2) + "\n");
  fs.renameSync(tmp, file);
}

if (flag("--install")) {
  const merged = mergeAgentHooks(load(), block);
  save(flag("--statusline") ? withStatusLine(merged, { port, secret: hookSecret() }) : merged);
  console.log(`Hooks installés dans ${file}. Les sessions Claude déjà ouvertes les prennent au prochain démarrage.`);
} else if (flag("--remove")) {
  save(withoutStatusLine(withoutAgentHooks(load())));
  console.log(`Hooks du compagnon retirés de ${file}.`);
} else {
  console.log(JSON.stringify(block, null, 2));
  console.error(`\nÀ fusionner dans ${file} (ou : npm run agents:hooks -- --install). Secret lu dans ${config.dataDir}.`);
}
