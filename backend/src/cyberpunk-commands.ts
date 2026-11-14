import fs from "node:fs/promises";
import path from "node:path";
import { readState } from "./cyberpunk.ts";
import { logFailure } from "./log.ts";
import { findGame, type Game } from "./cyberpunk-steam.ts";

/**
 * Ordres vers le mod CET : `commands.json` (fichier temporaire puis renommage, 20 ordres au plus) et attente de l'ack
 * correspondant dans `state.json` (`acks:[{id,ok,res}]`). Le mod ignore les ordres déjà là à son premier passage et
 * n'exécute que `id` > dernier traité : les ids ne reculent donc jamais, même après un redémarrage du backend.
 * Contrat : docs/specs/2026-11-14-plugin-cyberpunk-design.md.
 */

const MAX_CMDS = 20;
/** Délai d'attente de l'ack ; MATE_CYBERPUNK_ACK_MS le raccourcit dans les tests. */
const ackWaitMs = () => Number(process.env.MATE_CYBERPUNK_ACK_MS) || 5000;

export type Command = { t: "call" | "answer" | "hangup" | "use" | "summon" | "track" | "pin" | "unpin" | "thread" | "reply" } & Record<
  string,
  unknown
>;
export type Ack = { id: number; ok: boolean; res: string };

const fail = (message: string, statusCode: number): never => {
  throw Object.assign(new Error(message), { statusCode });
};

/** Base = l'heure au démarrage : un id est toujours plus grand que ceux d'avant le redémarrage. */
let lastId = Date.now();
let chain: Promise<unknown> = Promise.resolve();

/** Le jeu, seulement si une partie est en cours (`state.json` frais et `inGame`). */
async function liveGame(): Promise<Game> {
  const game = findGame() ?? fail("Cyberpunk 2077 (Steam) est introuvable sur cet ordinateur", 404);
  const { state, fresh } = await readState(game);
  if (!fresh || state?.inGame !== true) fail("Cyberpunk 2077 n'est pas en partie", 409);
  return game;
}

export async function inGame() {
  return liveGame().then(
    () => true,
    () => false,
  );
}

async function readCmds(file: string): Promise<(Command & { id: number })[]> {
  try {
    const cmds = JSON.parse(await fs.readFile(file, "utf8")).cmds;
    return Array.isArray(cmds) ? cmds.filter((c) => typeof c?.id === "number") : [];
  } catch {
    return [];
  }
}

/** Le mod peut avoir le fichier ouvert au moment du renommage (Windows) : quelques essais. */
async function replace(tmp: string, file: string) {
  for (let i = 0; ; i++) {
    try {
      return await fs.rename(tmp, file);
    } catch (e) {
      if (i >= 4) throw e;
      await new Promise((r) => setTimeout(r, 50));
    }
  }
}

async function append(game: Game, cmds: Command[]): Promise<number[]> {
  const file = path.join(game.modDir, "commands.json");
  const old = await readCmds(file);
  lastId = Math.max(lastId, ...old.map((c) => c.id));
  const added = cmds.map((c) => ({ ...c, id: (lastId = Math.max(lastId + 1, Date.now())) }));
  await fs.writeFile(`${file}.tmp`, JSON.stringify({ v: 1, cmds: [...old, ...added].slice(-MAX_CMDS) }));
  await replace(`${file}.tmp`, file);
  return added.map((c) => c.id);
}

/** Écrit les ordres (une seule écriture) ; 409 hors partie. Rend leurs ids. */
export async function enqueue(cmds: Command[]): Promise<number[]> {
  const game = await liveGame();
  const run = chain.then(() => append(game, cmds));
  chain = run.catch(() => undefined);
  return run;
}

/** Ack de l'ordre `id`, ou null si le mod n'a rien rendu dans le délai. */
export async function waitAck(id: number): Promise<Ack | null> {
  const game = findGame();
  if (!game) return null;
  for (const end = Date.now() + ackWaitMs(); Date.now() < end; await new Promise((r) => setTimeout(r, 100))) {
    const { state } = await readState(game);
    const ack = (state?.acks as Ack[] | undefined)?.find((a) => a.id === id);
    if (ack) return ack;
  }
  return null;
}

/** Un ordre et sa réponse ; 409 hors partie, 504 si le mod ne répond pas. */
export async function sendCommand(cmd: Command) {
  const [id] = await enqueue([cmd]);
  const ack = (await waitAck(id)) ?? fail("Cyberpunk 2077 n'a pas répondu", 504);
  return { ok: ack.ok, id, res: ack.res };
}

const REFUS: Record<string, string> = {
  "fil-introuvable": "Ce fil n'existe plus dans le téléphone du jeu",
  "choix-perime": "Ce choix n'est plus proposé : le fil a changé",
  // Ordre inconnu : c'est la réponse d'un mod 1.0.0 encore chargé.
  inconnu: "Le mod Mate du jeu est trop ancien : mets-le à jour depuis le compagnon",
};

/** Erreur 409 qui garde le `res` du mod quand il est connu (la tablette lit `res`) ; jamais une trace Lua brute. */
export class PhoneRefusal extends Error {
  statusCode = 409;
  res: string;
  constructor(res: string) {
    super(REFUS[res] ?? "Le téléphone du jeu n'a pas pu répondre");
    this.res = res in REFUS ? res : "erreur";
  }
}

async function phoneCommand(cmd: Command) {
  const sent = await sendCommand(cmd);
  if (!sent.ok) {
    if (!(sent.res in REFUS)) logFailure(`cyberpunk ${cmd.t}`, new Error(sent.res));
    throw new PhoneRefusal(sent.res);
  }
  return sent;
}

/** Un fil lu à la fois : `thread.json` est unique, deux lectures croisées se l'écraseraient. */
let threadLock: Promise<unknown> = Promise.resolve();

/** Lit le fil `hash` : ordre `thread`, puis `thread.json` lu tel quel (jamais le cache des blocs) et lié à l'ordre par `cmd`. */
export async function readThread(hash: string) {
  const run = threadLock.then(() => readThreadNow(hash));
  threadLock = run.catch(() => undefined);
  return run;
}

async function readThreadNow(hash: string) {
  const { id } = await phoneCommand({ t: "thread", hash });
  const game = findGame() ?? fail("Cyberpunk 2077 (Steam) est introuvable sur cet ordinateur", 404);
  let doc: Record<string, unknown>;
  try {
    doc = JSON.parse(await fs.readFile(path.join(game.modDir, "thread.json"), "utf8"));
  } catch {
    return fail("Le fil n'a pas pu être lu", 409);
  }
  if (doc.cmd !== id || doc.hash !== hash) fail("Le fil lu n'est pas celui demandé", 409);
  const { cmd: _cmd, ...thread } = doc;
  // Le mod écrit un tableau vide en objet vide : la tablette reçoit toujours des tableaux.
  const list = (v: unknown) => (Array.isArray(v) ? v : []);
  return { ...thread, messages: list(thread.messages), choices: list(thread.choices) };
}

/** Envoie le choix `choice` du fil `hash` ; le mod refuse si le choix n'est plus ouvert ou si `version` a changé. */
export async function replyThread(hash: string, choice: string, version: string) {
  await phoneCommand({ t: "reply", hash, choice, version });
  return { ok: true };
}
