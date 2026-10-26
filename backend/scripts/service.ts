import { execFile, spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { backendRoot as backend, dataDir, packaged as bundled, userFolder } from "../src/paths.ts";

/**
 * Lancement automatique du backend à l'ouverture de session, sans terminal :
 * - macOS : agent launchd (~/Library/LaunchAgents), relancé s'il s'arrête ;
 * - Windows : entrée « Exécuter » de la session (HKCU\…\Run), lanceur sans fenêtre qui relance node
 *   (src/win/UnowhyCompanion.exe : pas de PowerShell caché, que les antivirus bloquent).
 *
 *   npm run service:install     installe et démarre
 *   npm run service:stop        arrête (pour lancer `npm run dev` à la place)
 *   npm run service:start       redémarre
 *   npm run service:status      état
 *   npm run service:uninstall   retire le lancement automatique
 *
 * Instance de test à côté du vrai compagnon (CI, jobs install-mac et install-windows) : COMPANION_LABEL (autre agent
 * launchd, qui garde PORT, HOST, DATA_DIR, DOCS_DIR et COMPANION_HEADLESS ; sous Windows, autre valeur de la clé Run),
 * COMPANION_LOG_DIR, et COMPANION_HEADLESS=1 : ni fenêtre, ni page ouverte, ni raccourci sur le Bureau.
 */

const port = Number(process.env.PORT ?? 3000);
const LABEL = process.env.COMPANION_LABEL || "com.unowhy.companion";
const headless = process.env.COMPANION_HEADLESS === "1";
const RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
// Instance de test (job install-windows) : sa propre valeur dans la clé Run, celle du vrai compagnon n'est pas touchée.
const RUN_VALUE = process.env.COMPANION_LABEL || "UnowhyCompanion";
const isMac = process.platform === "darwin";
const isWindows = process.platform === "win32";

const plist = path.join(os.homedir(), "Library", "LaunchAgents", `${LABEL}.plist`);
const logDir = process.env.COMPANION_LOG_DIR
  ? path.resolve(process.env.COMPANION_LOG_DIR)
  : isWindows
    ? path.join(process.env.LOCALAPPDATA ?? os.homedir(), "UnowhyCompanion")
    : path.join(os.homedir(), "Library", "Logs", "UnowhyCompanion");
const pidFile = path.join(dataDir, "service.pid");
// Boucle lancée avant les données hors du dossier du paquet (ou en dev) : PID noté dans backend/.data.
const pidFiles = [...new Set([pidFile, path.join(backend, ".data", "service.pid")])];

// Paquet client (scripts/package.ts) : ce script et le serveur sont regroupés dans dist/, avec un node embarqué.
// Arguments de node : un seul processus (pas de surveillance des fichiers comme `npm run dev`).
const NODE_ARGS = bundled
  ? ["--env-file-if-exists=.env", "dist/server.mjs"]
  : ["--env-file-if-exists=.env", "--import", "tsx", "src/server.ts"];

function sh(cmd: string, args: string[]): Promise<{ ok: boolean; out: string }> {
  return new Promise((resolve) => {
    execFile(cmd, args, { windowsHide: true }, (err, stdout, stderr) => resolve({ ok: !err, out: `${stdout}${stderr}`.trim() }));
  });
}

/** Chemin de node qui survit aux mises à jour (lien Homebrew plutôt que le dossier versionné). */
function nodePath(): string {
  if (isMac) {
    for (const p of ["/opt/homebrew/bin/node", "/usr/local/bin/node"]) {
      if (fs.existsSync(p) && fs.realpathSync(p) === fs.realpathSync(process.execPath)) return p;
    }
  }
  return process.execPath;
}

async function health(): Promise<{ name: string; os: string } | undefined> {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(1_500) });
    return res.ok ? ((await res.json()) as { name: string; os: string }) : undefined;
  } catch {
    return undefined;
  }
}

async function waitHealthy(seconds: number) {
  for (let i = 0; i < seconds * 2; i++) {
    const h = await health();
    if (h) return h;
    await new Promise((r) => setTimeout(r, 500));
  }
  return undefined;
}

const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// --- macOS

const uid = () => String(process.getuid?.() ?? "");

async function macStart() {
  return sh("launchctl", ["bootstrap", `gui/${uid()}`, plist]);
}

async function macStop() {
  return sh("launchctl", ["bootout", `gui/${uid()}/${LABEL}`]);
}

async function macInstall() {
  const node = nodePath();
  fs.mkdirSync(path.dirname(plist), { recursive: true });
  fs.mkdirSync(logDir, { recursive: true });
  const args = [node, ...NODE_ARGS].map((a) => `    <string>${xml(a)}</string>`).join("\n");
  // PATH : osascript, open, mdfind… sont dans /usr/bin ; node peut être ailleurs (Homebrew, nvm).
  const envPath = [path.dirname(node), "/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"].join(":");
  // Instance de test seulement : le vrai compagnon relit PORT, HOST… dans .env à chaque démarrage.
  const passed = process.env.COMPANION_LABEL ? ["PORT", "HOST", "DATA_DIR", "DOCS_DIR", "COMPANION_HEADLESS"] : [];
  const env = [["PATH", envPath], ...passed.filter((k) => process.env[k]).map((k) => [k, process.env[k] as string])]
    .map(([k, v]) => `    <key>${k}</key>\n    <string>${xml(v)}</string>`)
    .join("\n");
  fs.writeFileSync(
    plist,
    `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
${args}
  </array>
  <key>WorkingDirectory</key>
  <string>${xml(backend)}</string>
  <key>EnvironmentVariables</key>
  <dict>
${env}
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>10</integer>
  <key>ProcessType</key>
  <string>Interactive</string>
  <key>StandardOutPath</key>
  <string>${xml(path.join(logDir, "backend.log"))}</string>
  <key>StandardErrorPath</key>
  <string>${xml(path.join(logDir, "backend.log"))}</string>
</dict>
</plist>
`,
  );
  await macStop();
  const started = await macStart();
  if (!started.ok) throw new Error(`launchctl : ${started.out}`);
  return node;
}

// --- Windows

const LAUNCHER = "UnowhyCompanion.exe";

/** Ligne de commande du lanceur, la même dans la clé Run et au démarrage par l'installeur. */
function windowsCommand(node: string) {
  const exe = path.join(backend, "src", "win", LAUNCHER);
  const args = ["--node", node, "--backend", backend, "--logs", logDir, "--data", dataDir];
  const quote = (a: string) => (/[\s"]/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a);
  return [exe, ...args].map(quote).join(" ");
}

/**
 * Lance la boucle comme à l'ouverture de session : `start` de cmd, qui la détache vraiment de ce processus.
 * `start` prend le premier argument entre guillemets pour un titre : d'où le titre vide.
 */
function winStart(node: string) {
  spawn("cmd.exe", ["/d", "/s", "/c", `"start "" /min ${windowsCommand(node)}"`], {
    detached: true,
    stdio: "ignore",
    windowsHide: true,
    windowsVerbatimArguments: true,
  }).unref();
}

/**
 * Arrête la boucle notée dans service.pid, seulement si c'est bien le lanceur (ou le PowerShell des anciennes
 * installations, à retirer quand il n'en reste plus) : le numéro a pu être redonné à un autre programme.
 */
async function winStop() {
  for (const file of pidFiles) {
    const pid = fs.existsSync(file) ? fs.readFileSync(file, "utf8").trim() : "";
    if (/^\d+$/.test(pid)) {
      for (const image of [LAUNCHER, "powershell.exe"])
        await sh("taskkill", ["/F", "/T", "/FI", `PID eq ${pid}`, "/FI", `IMAGENAME eq ${image}`]);
    }
    fs.rmSync(file, { force: true });
  }
}

async function winInstall() {
  const node = nodePath();
  fs.mkdirSync(logDir, { recursive: true });
  const command = windowsCommand(node);
  const added = await sh("reg", ["add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", command, "/f"]);
  if (!added.ok) throw new Error(`Registre : ${added.out}`);
  await winStop();
  winStart(node);
  return node;
}

// --- Raccourcis vers la page de l'ordinateur (adresse, code d'appairage, agenda)

const SHORTCUT = "Compagnon Unowhy";

function shortcutFiles(): string[] {
  const home = os.homedir();
  if (isMac) return [path.join(home, "Desktop", `${SHORTCUT}.webloc`)];
  const programs = path.join(
    process.env.APPDATA ?? path.join(home, "AppData", "Roaming"),
    "Microsoft",
    "Windows",
    "Start Menu",
    "Programs",
  );
  return [
    path.join(userFolder("Desktop", path.join(home, "Desktop")), `${SHORTCUT}.url`),
    path.join(userFolder("Programs", programs), `${SHORTCUT}.url`),
  ];
}

/** Bureau (et menu Démarrer sous Windows) ; un échec ne bloque pas l'installation. */
function addShortcuts(page: string) {
  // Icône du compagnon (assets/compagnon.svg, rendue en .ico multi-tailles) ; sinon celle du navigateur.
  const icon = path.join(backend, "assets", "compagnon.ico");
  const body = isMac
    ? `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict><key>URL</key><string>${xml(page)}</string></dict></plist>\n`
    : `[InternetShortcut]\r\nURL=${page}\r\n` + (fs.existsSync(icon) ? `IconFile=${icon}\r\nIconIndex=0\r\n` : "");
  for (const file of shortcutFiles()) {
    try {
      fs.writeFileSync(file, body);
    } catch {
      // Dossier protégé ou absent : la page reste à l'adresse affichée.
    }
  }
}

// --- Commandes

async function install() {
  if (!isMac && !isWindows) throw new Error("Lancement automatique prévu pour macOS et Windows seulement");
  const running = isMac ? (await sh("launchctl", ["print", `gui/${uid()}/${LABEL}`])).ok : pidFiles.some((f) => fs.existsSync(f));
  if (!running && (await health())) {
    throw new Error(`Un compagnon tourne déjà sur le port ${port} (npm run dev ?) : l'arrêter (Ctrl+C) puis relancer l'installation.`);
  }
  const node = isMac ? await macInstall() : await winInstall();
  console.log("Lancement automatique installé, démarrage…");
  const h = await waitHealthy(isWindows ? 60 : 20);
  if (!h) {
    console.log(`Le compagnon ne répond pas encore. Journal : ${logDir}`);
    process.exitCode = 1;
    return;
  }
  console.log(`\n✓ Compagnon prêt sur « ${h.name} ». Il démarrera tout seul à chaque ouverture de session.\n`);
  console.log("Sur la tablette : Réglages > Ordinateurs > Ajouter, puis saisir le code affiché ici.");
  if (headless) return;
  if (isMac) {
    console.log(
      `\nPour les raccourcis, macros et fenêtres, macOS demande l'accès Accessibilité pour node :\n` +
        `  Réglages Système > Confidentialité et sécurité > Accessibilité > + > ${node}\n` +
        `  (le Finder s'ouvre sur node : le glisser dans la liste, puis l'activer)`,
    );
    await sh("open", ["x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"]);
    await sh("open", ["-R", node]);
  } else {
    console.log(
      "\nSi la tablette ne trouve pas l'ordinateur : le WiFi de l'ordinateur doit être en profil « Privé »\n" +
        "(le pare-feu n'est ouvert que pour les réseaux privés).",
    );
  }
  const page = `http://127.0.0.1:${port}/`;
  addShortcuts(page);
  await sh(isMac ? "open" : "explorer.exe", [page]);
  console.log(`\nPage d'aide (adresse, code d'appairage, agenda) : ${page}`);
  console.log(`Pour la rouvrir : raccourci « ${SHORTCUT} » sur le Bureau${isWindows ? " et dans le menu Démarrer" : ""}.`);
}

async function uninstall() {
  if (isMac) {
    await macStop();
    fs.rmSync(plist, { force: true });
  } else if (isWindows) {
    await winStop();
    await sh("reg", ["delete", RUN_KEY, "/v", RUN_VALUE, "/f"]);
  }
  if (!headless) for (const file of shortcutFiles()) fs.rmSync(file, { force: true });
  console.log("Lancement automatique retiré.");
}

async function stop() {
  if (isMac) await macStop();
  else if (isWindows) await winStop();
  console.log("Compagnon arrêté (il redémarrera à la prochaine ouverture de session, ou avec npm run service:start).");
}

async function start() {
  if (isMac) {
    if (!fs.existsSync(plist)) throw new Error("Service non installé : npm run service:install");
    await macStop();
    await macStart();
  } else if (isWindows) {
    await winStop();
    winStart(nodePath());
  }
  const h = await waitHealthy(isWindows ? 60 : 20);
  console.log(h ? `Compagnon démarré sur « ${h.name} ».` : `Le compagnon ne répond pas. Journal : ${logDir}`);
}

async function status() {
  const installed = isMac ? fs.existsSync(plist) : isWindows ? (await sh("reg", ["query", RUN_KEY, "/v", RUN_VALUE])).ok : false;
  const h = await health();
  console.log(`Lancement automatique : ${installed ? "installé" : "non installé"}`);
  console.log(`Compagnon : ${h ? `en marche sur « ${h.name} » (port ${port})` : "arrêté"}`);
  console.log(`Journal : ${logDir}`);
}

const commands: Record<string, () => Promise<void>> = { install, uninstall, start, stop, status };
const command = commands[process.argv[2] ?? ""];
if (!command) {
  console.log("Usage : service.ts install | uninstall | start | stop | status");
  process.exit(1);
}
command().catch((e: Error) => {
  console.error(e.message);
  process.exit(1);
});
