import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { frontApp, isMac } from "./os.ts";
import type { ClaudeSession, OrcaInfo, TranscriptInfo } from "./agents.ts";

/**
 * Ce que les agents Claude lisent sur l'ordinateur : `claude agents --json`, la CLI d'Orca, l'appli de devant.
 * Les chemins se cherchent dans le PATH puis dans les dossiers d'installation usuels : un service lancé au
 * démarrage de session n'a pas le PATH d'un terminal. `CLAUDE_BIN` et `ORCA_BIN` forcent un chemin (tests).
 */

const exists = (file: string) => {
  try {
    fs.accessSync(file, fs.constants.X_OK);
    return fs.statSync(file).isFile();
  } catch {
    return false;
  }
};

function findBinary(name: string, envVar: string, extra: string[]): string | null {
  const forced = process.env[envVar]?.trim();
  if (forced) return exists(forced) ? forced : null;
  const dirs = [...(process.env.PATH ?? "").split(path.delimiter), ...extra].filter(Boolean);
  for (const dir of dirs) {
    const candidate = path.join(dir, name);
    if (exists(candidate)) return candidate;
  }
  return null;
}

const home = os.homedir();
export const claudeBin = () =>
  findBinary("claude", "CLAUDE_BIN", [
    path.join(home, ".claude", "local"),
    path.join(home, ".local", "bin"),
    "/opt/homebrew/bin",
    "/usr/local/bin",
  ]);
export const orcaBin = () =>
  findBinary("orca", "ORCA_BIN", ["/Applications/Orca.app/Contents/Resources/bin", "/usr/local/bin", "/opt/homebrew/bin"]);

/** Les agents sont proposés sur Mac, quand Claude Code est installé. Windows : `claude agents` et les hooks n'y sont pas essayés. */
export const agentsAvailable = () => isMac && claudeBin() !== null;

function exec(bin: string, args: string[], timeoutMs: number, env?: NodeJS.ProcessEnv): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(bin, args, { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024, env }, (err, stdout) => (err ? reject(err) : resolve(stdout)));
  });
}

/**
 * Dossiers de config de Claude Code à lire : chaque dossier a ses propres sessions. Celui du compagnon (par défaut
 * `~/.claude`), ceux des profils d'Orca (ses sessions tournent avec `CLAUDE_CONFIG_DIR` = `…/orca/claude-profiles/<id>/home`,
 * invisibles depuis `~/.claude`), et ceux de `AGENTS_CLAUDE_CONFIG_DIRS` (liste séparée par « : »).
 */
export function claudeConfigDirs(): string[] {
  const dirs = [process.env.CLAUDE_CONFIG_DIR?.trim() || path.join(home, ".claude")];
  const profiles = path.join(home, "Library", "Application Support", "orca", "claude-profiles");
  try {
    for (const id of fs.readdirSync(profiles)) {
      const dir = path.join(profiles, id, "home");
      if (fs.existsSync(dir)) dirs.push(dir);
    }
  } catch {
    // Pas d'Orca sur cet ordinateur.
  }
  dirs.push(
    ...(process.env.AGENTS_CLAUDE_CONFIG_DIRS ?? "")
      .split(":")
      .map((d) => d.trim())
      .filter(Boolean),
  );
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

/** Applis où tourne Claude Code : terminaux, Orca, éditeurs. Devant l'une d'elles, l'utilisateur est « sur Claude ». */
const HOSTS = new Set([
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

/** Terminal de la session devant dans Orca, puis Orca devant. Sans terminal : Orca seulement. */
export async function focusSession(handle: string | null): Promise<void> {
  const bin = orcaBin();
  if (bin && handle && HANDLE.test(handle)) await exec(bin, ["terminal", "switch", "--terminal", handle], 6_000).catch(() => {});
  await exec("open", ["-a", "Orca"], 6_000).catch(() => {
    throw new Error("Impossible d'amener Orca devant");
  });
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
