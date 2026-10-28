import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { frontApp, isMac } from "./os.ts";
import type { ClaudeSession, OrcaInfo } from "./agents.ts";

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

function exec(bin: string, args: string[], timeoutMs: number): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(bin, args, { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => (err ? reject(err) : resolve(stdout)));
  });
}

export async function listClaude(): Promise<ClaudeSession[] | null> {
  const bin = claudeBin();
  if (!bin) return null;
  try {
    const list = JSON.parse(await exec(bin, ["agents", "--json"], 8_000));
    return Array.isArray(list) ? (list as ClaudeSession[]) : null;
  } catch {
    return null;
  }
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
        agents: ((w.agents ?? []) as Record<string, any>[])
          .filter((a) => a.agentType === "claude")
          .map((a) => ({
            paneKey: String(a.paneKey ?? ""),
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
