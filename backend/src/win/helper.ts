import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Processus PowerShell résident (helper.ps1) qui exécute le code natif de native.cs : lancer un
 * PowerShell par action prendrait 1 à 2 s (démarrage + compilation), trop lent pour un deck.
 * Une requête à la fois, réponses dans l'ordre ; relancé au besoin s'il meurt ou ne répond plus.
 */

const HERE = path.dirname(fileURLToPath(import.meta.url));
/** Fichiers lus par PowerShell : helper.ps1 et native.cs (qu'il compile). */
const FILES = ["helper.ps1", "native.cs"];
/**
 * Dossier fixe des copies (pas le dossier temporaire) : un antivirus (Bitdefender) met en quarantaine un script
 * PowerShell apparu dans Temp, et un chemin fixe permet de lui ajouter une exception une fois pour toutes.
 */
const RUN_ROOT = path.join(process.env.LOCALAPPDATA ?? os.tmpdir(), "UnowhyCompanion", "helper");
const STARTUP_TIMEOUT_MS = 60_000; // première compilation sur un PC lent
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

function start(): Promise<void> {
  // PowerShell tourne sur une copie : Windows interdit de remplacer un script en cours d'exécution, ce qui bloquait
  // la mise à jour de helper.ps1 et native.cs tant que le compagnon tournait.
  fs.mkdirSync(RUN_ROOT, { recursive: true });
  const dir = fs.mkdtempSync(path.join(RUN_ROOT, "run-"));
  for (const file of FILES) fs.copyFileSync(path.join(HERE, file), path.join(dir, file));
  runDir = dir;
  const proc = spawn(
    "powershell.exe",
    ["-NoLogo", "-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-File", path.join(dir, "helper.ps1")],
    { windowsHide: true },
  );
  child = proc;
  const stderr: string[] = [];
  proc.stderr.on("data", (d: Buffer) => stderr.push(d.toString()));
  proc.on("error", (e) => stop(`PowerShell introuvable : ${e.message}`));
  proc.on("exit", () => {
    if (child === proc) stop(`PowerShell s'est arrêté ${stderr.join("").trim().slice(0, 300)}`.trim());
  });
  createInterface({ input: proc.stdout }).on("line", (line) => {
    if (!line.startsWith("@@")) return;
    const waiter = waiters.shift();
    if (!waiter) return;
    clearTimeout(waiter.timer);
    try {
      waiter.resolve(JSON.parse(line.slice(2)) as Reply);
    } catch {
      waiter.resolve({ ok: false, error: "Réponse illisible de PowerShell" });
    }
  });
  return next(STARTUP_TIMEOUT_MS).then((r) => {
    if (!r.ok) {
      stop(r.error ?? "");
      throw new Error(`Démarrage du pilote Windows impossible : ${r.error}`);
    }
  });
}

/** Exécute une opération de native.cs (voir Dispatch) et renvoie son résultat. */
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
