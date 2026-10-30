import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createDesktop, WINDOWS_HOSTS } from "./agents-desktop.ts";
import { focusWindow, frontApp, frontWindow, isMac, isWindows, processWindows, raiseWindow, sendKeys, typeText } from "./os.ts";
import { parseOrcaQuota, type ClaudeSession, type FocusTarget, type OrcaInfo, type Quota, type TranscriptInfo } from "./agents.ts";

/**
 * Ce que les agents Claude lisent sur l'ordinateur : `claude agents --json`, la CLI d'Orca, l'appli de devant.
 * Les chemins se cherchent dans le PATH puis dans les dossiers d'installation usuels : un service lancé au
 * démarrage de session n'a pas le PATH d'un terminal. `CLAUDE_BIN` et `ORCA_BIN` forcent un chemin (tests).
 * Windows : `claude.exe` (installeur natif, `%USERPROFILE%\.local\bin`) avant `claude.cmd` (npm, `%APPDATA%\npm`).
 */

/** Système de fichiers de Windows : le bit d'exécution n'y veut rien dire, on vérifie que le fichier existe. */
const onWindows = process.platform === "win32";

const exists = (file: string, windows = onWindows) => {
  try {
    fs.accessSync(file, windows ? fs.constants.F_OK : fs.constants.X_OK);
    return fs.statSync(file).isFile();
  } catch {
    return false;
  }
};

/** Premier de `names` trouvé dans le PATH puis dans `extra` ; `opts` sert aux tests (règles de Windows sur un autre système). */
export function findBinary(
  names: string[],
  envVar: string,
  extra: string[],
  opts: { windows?: boolean; pathVar?: string; delimiter?: string } = {},
): string | null {
  const windows = opts.windows ?? onWindows;
  const forced = process.env[envVar]?.trim();
  if (forced) return exists(forced, windows) ? forced : null;
  const dirs = [...(opts.pathVar ?? process.env.PATH ?? "").split(opts.delimiter ?? path.delimiter), ...extra].filter(Boolean);
  for (const name of names) {
    for (const dir of dirs) {
      const candidate = path.join(dir, name);
      if (exists(candidate, windows)) return candidate;
    }
  }
  return null;
}

const home = os.homedir();
const appData = process.env.APPDATA ?? path.join(home, "AppData", "Roaming");
const localAppData = process.env.LOCALAPPDATA ?? path.join(home, "AppData", "Local");

export const claudeBin = () =>
  onWindows
    ? findBinary(["claude.exe", "claude.cmd"], "CLAUDE_BIN", [
        path.join(home, ".local", "bin"),
        path.join(home, ".claude", "local"),
        path.join(appData, "npm"),
      ])
    : findBinary(["claude"], "CLAUDE_BIN", [
        path.join(home, ".claude", "local"),
        path.join(home, ".local", "bin"),
        "/opt/homebrew/bin",
        "/usr/local/bin",
      ]);
// Orca sous Windows : pas essayé (absent du PC de développement). Dossier d'installation par utilisateur des applis
// Electron (`%LOCALAPPDATA%\Programs\<appli>`) supposé ; sa CLI est d'abord cherchée dans le PATH.
export const orcaBin = () =>
  onWindows
    ? findBinary(["orca.exe", "orca.cmd"], "ORCA_BIN", [path.join(localAppData, "Programs", "orca", "resources", "bin")])
    : findBinary(["orca"], "ORCA_BIN", ["/Applications/Orca.app/Contents/Resources/bin", "/usr/local/bin", "/opt/homebrew/bin"]);

/** Les agents sont proposés sur Mac et Windows, quand Claude Code est installé. */
export const agentsAvailable = () => (isMac || isWindows) && claudeBin() !== null;

/** Arguments permis pour un `.cmd` : Node (≥ 20.12) ne le lance que par le shell, qui interpréterait le reste. */
const SHELL_SAFE = /^[\w:./=-]+$/;

function exec(bin: string, args: string[], timeoutMs: number, env?: NodeJS.ProcessEnv): Promise<string> {
  const viaShell = /\.(cmd|bat)$/i.test(bin);
  if (viaShell && !args.every((a) => SHELL_SAFE.test(a))) return Promise.reject(new Error("Argument refusé pour un script .cmd"));
  return new Promise((resolve, reject) => {
    execFile(
      viaShell ? `"${bin}"` : bin,
      args,
      { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024, env, shell: viaShell, windowsHide: true },
      (err, stdout) => (err ? reject(err) : resolve(stdout)),
    );
  });
}

/** Liste de dossiers d'une variable d'environnement : séparés par « ; » sous Windows (chemins en `C:\…`), « : » ailleurs. */
export function splitDirs(value: string | undefined, delimiter = path.delimiter): string[] {
  return (value ?? "")
    .split(delimiter)
    .map((d) => d.trim())
    .filter(Boolean);
}

/**
 * Dossiers de config de Claude Code à lire : chaque dossier a ses propres sessions. Celui du compagnon (par défaut
 * `~/.claude`), ceux des profils d'Orca (ses sessions tournent avec `CLAUDE_CONFIG_DIR` = `…/orca/claude-profiles/<id>/home`,
 * invisibles depuis `~/.claude` ; sous Windows, `%APPDATA%\orca` supposé comme pour toute appli Electron, pas vérifié), et
 * ceux de `AGENTS_CLAUDE_CONFIG_DIRS` (liste séparée par « : », « ; » sous Windows).
 */
export function claudeConfigDirs(): string[] {
  const dirs = [process.env.CLAUDE_CONFIG_DIR?.trim() || path.join(home, ".claude")];
  const profiles = onWindows
    ? path.join(appData, "orca", "claude-profiles")
    : path.join(home, "Library", "Application Support", "orca", "claude-profiles");
  try {
    for (const id of fs.readdirSync(profiles)) {
      const dir = path.join(profiles, id, "home");
      if (fs.existsSync(dir)) dirs.push(dir);
    }
  } catch {
    // Pas d'Orca sur cet ordinateur.
  }
  dirs.push(...splitDirs(process.env.AGENTS_CLAUDE_CONFIG_DIRS));
  const real = (d: string) => {
    try {
      return fs.realpathSync(d);
    } catch {
      return d;
    }
  };
  return [...new Map(dirs.map((d) => [real(d), d])).values()];
}

/** `claude agents --json` de chaque dossier de config, fusionnés ; null si aucun n'a répondu. */
export async function listClaude(): Promise<ClaudeSession[] | null> {
  const bin = claudeBin();
  if (!bin) return null;
  const lists = await Promise.all(
    claudeConfigDirs().map(async (dir) => {
      try {
        const list = JSON.parse(await exec(bin, ["agents", "--json"], 8_000, { ...process.env, CLAUDE_CONFIG_DIR: dir }));
        return Array.isArray(list) ? (list as ClaudeSession[]) : null;
      } catch {
        return null;
      }
    }),
  );
  if (lists.every((l) => l === null)) return null;
  const merged = new Map<string, ClaudeSession>();
  for (const e of lists.flatMap((l) => l ?? [])) if (e.sessionId && !merged.has(e.sessionId)) merged.set(e.sessionId, e);
  return [...merged.values()];
}

/** Orca fermé ou absent : on n'insiste pas avant 30 s. */
let orcaDownUntil = 0;

export async function listOrca(): Promise<OrcaInfo | null> {
  const bin = orcaBin();
  if (!bin || Date.now() < orcaDownUntil) return null;
  try {
    const [ps, terminals] = await Promise.all([
      exec(bin, ["worktree", "ps", "--json"], 6_000),
      exec(bin, ["terminal", "list", "--json"], 6_000).catch(() => "{}"),
    ]);
    const trees = (JSON.parse(ps).result?.worktrees ?? []) as Record<string, any>[];
    const handles = new Map<string, string>();
    for (const t of (JSON.parse(terminals).result?.terminals ?? []) as Record<string, any>[]) {
      if (typeof t.handle === "string" && t.tabId && t.leafId) handles.set(`${t.tabId}:${t.leafId}`, t.handle);
    }
    return {
      handles,
      worktrees: trees.map((w) => ({
        path: String(w.path ?? ""),
        branch: typeof w.branch === "string" ? w.branch.replace(/^refs\/heads\//, "") : null,
        repo: typeof w.repo === "string" ? w.repo : null,
        name: typeof w.displayName === "string" ? w.displayName : null,
        activityAt: typeof w.lastActivityAt === "number" ? w.lastActivityAt : null,
        ticket: ((w.linkedItems ?? []) as Record<string, any>[]).find((i) => typeof i.jiraIdentifier === "string")?.jiraIdentifier ?? null,
        mr:
          typeof w.linkedGitLabMR === "number"
            ? w.linkedGitLabMR
            : (((w.linkedItems ?? []) as Record<string, any>[]).filter((i) => i.provider === "gitlab" && i.type === "mr").at(-1)?.number ??
              null),
        agents: ((w.agents ?? []) as Record<string, any>[])
          .filter((a) => a.agentType === "claude")
          .map((a) => ({
            paneKey: String(a.paneKey ?? ""),
            state: typeof a.state === "string" ? a.state : null,
            monitoring: a.workingMode === "monitoring",
            toolName: typeof a.toolName === "string" ? a.toolName : null,
            lastMessage: typeof a.lastAssistantMessage === "string" ? a.lastAssistantMessage : null,
            structured: a.structuredHostOwned === true,
          })),
      })),
    };
  } catch {
    orcaDownUntil = Date.now() + 30_000;
    return null;
  }
}

/**
 * Applis où tourne Claude Code : terminaux, Orca, éditeurs. Devant l'une d'elles, l'utilisateur est « sur Claude ».
 * Mac : identifiants d'appli ; Windows : nom de l'exe en minuscules (`frontApp` le rend comme `bundleId`).
 */
const HOSTS = new Set([
  ...WINDOWS_HOSTS,
  "com.stablyai.orca",
  "com.apple.Terminal",
  "com.googlecode.iterm2",
  "com.mitchellh.ghostty",
  "com.github.wez.wezterm",
  "org.alacritty",
  "net.kovidgoyal.kitty",
  "dev.warp.Warp-Stable",
  "co.zeit.hyper",
  "com.microsoft.VSCode",
  "com.todesktop.230313mzl4w4u92",
  "dev.zed.Zed",
  "com.anthropic.claudefordesktop",
]);

export async function frontIsClaude(): Promise<boolean> {
  const front = await frontApp();
  return HOSTS.has(front.bundleId);
}

const HANDLE = /^term_[0-9a-zA-Z-]+$/;

/** Frappe brute dans le terminal d'une session Orca (sans Entrée). Un terminal disparu se dit en français. */
async function keys(handle: string, text: string, enter: boolean): Promise<void> {
  const bin = orcaBin();
  if (!bin || !HANDLE.test(handle)) throw new Error("Orca est introuvable");
  let out: string;
  try {
    out = await exec(bin, ["terminal", "send", "--terminal", handle, "--text", text, ...(enter ? ["--enter"] : []), "--json"], 10_000);
  } catch (e) {
    const detail = String((e as { stdout?: string }).stdout ?? "") + String((e as Error).message);
    throw new Error(
      /stale|not_found|no_terminal/i.test(detail)
        ? "Le terminal de cette session n'est plus joignable"
        : "Orca n'a pas pu écrire dans le terminal",
    );
  }
  if (JSON.parse(out).result?.send?.accepted !== true) throw new Error("Le terminal n'a pas accepté le message (menu ouvert ?)");
}

/**
 * Écrit une ligne dans le terminal d'une session Orca et l'envoie. La zone de saisie est d'abord vidée (Ctrl-U) : après une
 * interruption, Claude y remet le texte du tour interrompu, qui se mêlerait au message.
 */
export async function sendText(handle: string, text: string): Promise<void> {
  await keys(handle, "\u0015", false);
  await keys(handle, text, true);
}

/** Interrompt le tour en cours, comme Échap au clavier (Ctrl-C, deux fois de suite, quitterait Claude). */
export async function interruptSession(handle: string): Promise<void> {
  await keys(handle, "\u001b", false);
}

/**
 * Session devant dans Orca (son terminal, sinon son worktree et son onglet de chat), puis Orca devant. Si Orca ne
 * répond pas, Orca devant seulement. Windows : la fenêtre d'Orca par son nom d'appli (pas essayé, Orca absent du PC).
 */
export async function focusSession({ handle, worktree, tab }: FocusTarget): Promise<void> {
  const bin = orcaBin();
  if (bin && handle && HANDLE.test(handle)) await exec(bin, ["terminal", "switch", "--terminal", handle], 6_000).catch(() => {});
  else if (bin && worktree) await showOrcaChat(bin, worktree, tab).catch(() => {});
  const raise = isWindows ? (async () => focusWindow("Orca"))() : exec("open", ["-a", "Orca"], 6_000);
  await raise.catch(() => {
    throw new Error("Impossible d'amener Orca devant");
  });
}

/** Windows, sessions hors Orca : la fenêtre de leur terminal, trouvée et vérifiée par le pilote (src/agents-desktop.ts). */
export const desktopAgents = createDesktop({ processWindows, frontWindow, raiseWindow, typeText, sendKeys: (combo) => sendKeys(combo) });

const CHAT_TAB = /^agent-session:[\w-]+$/;

/**
 * Lance un script du client du runtime d'Orca, comme sa CLI : par l'exécutable d'Orca en mode Node. Mac seulement : sous
 * Windows l'emplacement du client n'est pas connu, chat et quota d'Orca y sont absents (quota : par la statusline).
 */
async function orcaRuntime(bin: string, script: string, env: NodeJS.ProcessEnv = {}): Promise<string | null> {
  if (onWindows) return null;
  const app = /^(.*?\.app)\//.exec(fs.realpathSync(bin))?.[1];
  if (!app) return null;
  return exec(path.join(app, "Contents", "MacOS", "Orca"), ["-e", script], 8_000, {
    ...process.env,
    ...env,
    ELECTRON_RUN_AS_NODE: "1",
    ORCA_CLIENT: path.join(app, "Contents", "Resources", "app.asar.unpacked", "out", "cli", "runtime-client.js"),
  });
}

/** La CLI d'Orca sait changer de terminal, pas de chat : on passe par son client du runtime (`worktree.activate`, `session.tabs.activate`). */
async function showOrcaChat(bin: string, worktree: string, tab: string | null): Promise<void> {
  if (!path.isAbsolute(worktree)) return;
  const script = `const { RuntimeClient } = require(process.env.ORCA_CLIENT);
const { worktree, tab } = JSON.parse(process.env.ORCA_FOCUS);
(async () => {
  const c = new RuntimeClient();
  const ok = (r) => { if (!r || r.ok === false) throw new Error("refus"); };
  ok(await c.call("worktree.activate", { worktree }));
  if (tab) ok(await c.call("session.tabs.activate", { worktree, tabId: tab }));
})().then(() => process.exit(0), () => process.exit(1));`;
  await orcaRuntime(bin, script, {
    ORCA_FOCUS: JSON.stringify({ worktree: `path:${worktree}`, tab: tab && CHAT_TAB.test(tab) ? tab : null }),
  });
}

/**
 * Message dans un chat Orca : la CLI n'y écrit pas, on passe par `agentSession.send` du client du runtime, avec le
 * message et son empreinte construits par le module d'Orca lui-même (`structured-agent-session-send-mutation.js`).
 * Interne à Orca : une mise à jour d'Orca peut le casser, et l'échec se dit alors en une phrase.
 */
export async function sendChat(session: string, text: string): Promise<void> {
  const bin = orcaBin();
  if (!bin || !/^[\w-]+$/.test(session)) throw new Error("Orca est introuvable");
  const script = `const path = require("path");
const { RuntimeClient } = require(process.env.ORCA_CLIENT);
const send = require(path.join(path.dirname(process.env.ORCA_CLIENT), "..", "shared", "structured-agent-session-send-mutation.js"));
const { session, text } = JSON.parse(process.env.ORCA_SEND);
const operation = Date.now() + "-" + require("crypto").randomBytes(16).toString("hex");
const params = send.structuredAgentSessionMessageSendMutation({
  sessionId: session, clientOperationId: operation, expectedRuntimeFence: 1, body: send.structuredAgentSessionSendBody(text, []),
});
new RuntimeClient().call("agentSession.send", params).then(
  (r) => { process.stdout.write(r?.ok && r.result?.ok !== false ? "ok" : String(r?.result?.refusal?.details?.reason ?? r?.result?.refusal?.code ?? "refus")); process.exit(0); },
  () => process.exit(1),
);`;
  const out = await orcaRuntime(bin, script, { ORCA_SEND: JSON.stringify({ session, text }) }).catch(() => null);
  if (out === "ok") return;
  throw new Error(out === "sessionNotAttached" ? "Ce chat n'est plus ouvert dans Orca" : "Orca n'a pas pu envoyer le message dans le chat");
}

/**
 * Usage Claude (5 h, 7 j) qu'Orca lit lui-même auprès d'Anthropic : `rateLimits.claude` de `accounts.list`. Seul ce bloc
 * sort du script (pas les comptes). Aucun jeton lu par le compagnon, aucun appel de plus à Anthropic.
 */
export async function orcaQuota(): Promise<Quota | null> {
  const bin = orcaBin();
  if (!bin) return null;
  const script = `const { RuntimeClient } = require(process.env.ORCA_CLIENT);
new RuntimeClient().call("accounts.list", {}).then(
  (r) => { process.stdout.write(JSON.stringify(r?.result?.rateLimits?.claude ?? null)); process.exit(0); },
  () => process.exit(1),
);`;
  const out = await orcaRuntime(bin, script).catch(() => null);
  if (!out) return null;
  try {
    return parseOrcaQuota(JSON.parse(out));
  } catch {
    return null;
  }
}

// --- Journal de la session (~/.claude/projects/<dossier>/<session>.jsonl) : le titre automatique que Claude donne à la
// session (`ai-title`) et la dernière MR créée (`pr-link`). Format interne de Claude Code, lu sans s'y fier : la fin du
// fichier seulement, relue quand sa taille change ; rien d'autre n'est gardé.

const TAIL_BYTES = 256 * 1024;
const transcripts = new Map<string, { size: number; info: TranscriptInfo }>();

/** Dossier du journal : le chemin de la session, chaque caractère non alphanumérique remplacé par « - ». */
export const projectSlug = (cwd: string) => cwd.replace(/[^A-Za-z0-9]/g, "-");

export function readTranscriptTail(text: string): TranscriptInfo {
  let title: string | null = null;
  let mr: number | null = null;
  for (const line of text.split("\n").reverse()) {
    if (title && mr) break;
    if (!title && line.includes('"ai-title"')) {
      try {
        const e = JSON.parse(line);
        if (e.type === "ai-title" && typeof e.aiTitle === "string") title = e.aiTitle;
      } catch {
        // Ligne coupée par le début de la lecture.
      }
    }
    if (!mr && line.includes('"pr-link"')) {
      try {
        const e = JSON.parse(line);
        if (e.type === "pr-link" && Number.isInteger(e.prNumber)) mr = e.prNumber;
      } catch {
        // Idem.
      }
    }
  }
  return { title, mr };
}

export async function transcriptInfo(sessionId: string, cwd: string, hinted: string | null): Promise<TranscriptInfo | null> {
  if (!/^[0-9a-f-]{36}$/i.test(sessionId)) return null;
  const candidates = [
    hinted,
    ...claudeConfigDirs().map((dir) => path.join(dir, "projects", projectSlug(cwd), `${sessionId}.jsonl`)),
  ].filter((p): p is string => Boolean(p));
  for (const file of candidates) {
    let size: number;
    try {
      size = fs.statSync(file).size;
    } catch {
      continue;
    }
    const cached = transcripts.get(file);
    if (cached && cached.size === size) return cached.info;
    const fd = fs.openSync(file, "r");
    try {
      const length = Math.min(size, TAIL_BYTES);
      const buf = Buffer.alloc(length);
      fs.readSync(fd, buf, 0, length, size - length);
      const info = readTranscriptTail(buf.toString("utf8"));
      // Titre ou MR plus anciens que la fin lue : on garde ceux d'avant.
      const merged = { title: info.title ?? cached?.info.title ?? null, mr: info.mr ?? cached?.info.mr ?? null };
      transcripts.set(file, { size, info: merged });
      return merged;
    } finally {
      fs.closeSync(fd);
    }
  }
  return null;
}
