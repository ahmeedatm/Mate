import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { backendRoot } from "../config.ts";

/**
 * Pilote Windows résident (UnowhyHelper.exe, compilé depuis helper/ par `npm run build:helper`) :
 * un processus par action serait trop lent pour un deck. Un exe compilé d'avance et pas PowerShell, que les
 * antivirus bloquent.
 * Une requête à la fois, réponses dans l'ordre ; relancé au besoin s'il meurt ou ne répond plus.
 */

// Depuis la racine du backend : le paquet client tourne sur un seul fichier (dist/server.mjs).
const HERE = path.join(backendRoot, "src", "win");
const EXE = "UnowhyHelper.exe";
/** Dossier fixe des copies (pas le dossier temporaire, où un antivirus se méfie d'un programme apparu). */
const RUN_ROOT = path.join(process.env.LOCALAPPDATA ?? os.tmpdir(), "UnowhyCompanion", "helper");
const STARTUP_TIMEOUT_MS = 30_000;
const REQUEST_TIMEOUT_MS = 30_000;

type Reply = { ok: boolean; result?: unknown; error?: string; fatal?: boolean };
type Waiter = { resolve: (r: Reply) => void; timer: NodeJS.Timeout };

let child: ChildProcessWithoutNullStreams | undefined;
/** Dossier temporaire de la copie en cours d'exécution. */
let runDir: string | undefined;
let ready: Promise<void> | undefined;
const waiters: Waiter[] = [];
let queue: Promise<unknown> = Promise.resolve();

function stop(reason: string) {
  child?.kill();
  child = undefined;
  if (runDir) fs.rm(runDir, { recursive: true, force: true }, () => {});
  runDir = undefined;
  ready = undefined;
  for (const w of waiters.splice(0)) {
    clearTimeout(w.timer);
    w.resolve({ ok: false, error: reason });
  }
}

function next(timeoutMs: number): Promise<Reply> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => stop("Windows ne répond pas (délai dépassé)"), timeoutMs);
    waiters.push({ resolve, timer });
  });
}

/** Copies laissées par un serveur mort sans `stop()` ; celle d'un pilote encore vivant reste (Windows refuse de supprimer l'exe en cours). */
function purgeOldRuns() {
  for (const name of fs.readdirSync(RUN_ROOT)) {
    if (!name.startsWith("run-")) continue;
    try {
      fs.rmSync(path.join(RUN_ROOT, name), { recursive: true, force: true });
    } catch {
      // En cours d'utilisation : retentée au prochain démarrage.
    }
  }
}

function start(): Promise<void> {
  // L'exe tourne sur une copie : Windows interdit de remplacer un programme en cours d'exécution, ce qui bloquerait
  // sa mise à jour tant que le compagnon tourne.
  fs.mkdirSync(RUN_ROOT, { recursive: true });
  purgeOldRuns();
  const dir = fs.mkdtempSync(path.join(RUN_ROOT, "run-"));
  fs.copyFileSync(path.join(HERE, EXE), path.join(dir, EXE));
  runDir = dir;
  const proc = spawn(path.join(dir, EXE), [], { windowsHide: true });
  child = proc;
  const stderr: string[] = [];
  proc.stderr.on("data", (d: Buffer) => stderr.push(d.toString()));
  proc.on("error", (e) => stop(`Pilote Windows introuvable : ${e.message}`));
  proc.on("exit", () => {
    if (child === proc) stop(`Le pilote Windows s'est arrêté ${stderr.join("").trim().slice(0, 300)}`.trim());
  });
  createInterface({ input: proc.stdout }).on("line", (line) => {
    if (!line.startsWith("@@")) return;
    const waiter = waiters.shift();
    if (!waiter) return;
    clearTimeout(waiter.timer);
    try {
      waiter.resolve(JSON.parse(line.slice(2)) as Reply);
    } catch {
      waiter.resolve({ ok: false, error: "Réponse illisible du pilote Windows" });
    }
  });
  return next(STARTUP_TIMEOUT_MS).then((r) => {
    if (!r.ok) {
      stop(r.error ?? "");
      throw new Error(`Démarrage du pilote Windows impossible : ${r.error}`);
    }
  });
}

/** Exécute une opération du pilote (voir Native.Dispatch dans helper/Native.cs) et renvoie son résultat. */
export function call<T = unknown>(op: string, args: Record<string, unknown> = {}, timeoutMs = REQUEST_TIMEOUT_MS): Promise<T> {
  const task = queue.then(async () => {
    if (!child || !ready) ready = start();
    await ready;
    const reply = next(timeoutMs);
    child!.stdin.write(JSON.stringify({ ...args, op }) + "\n");
    const r = await reply;
    if (!r.ok) throw new Error(r.error ?? "Erreur Windows");
    return r.result as T;
  });
  // Une erreur ne bloque pas les requêtes suivantes.
  queue = task.catch(() => undefined);
  return task;
}
