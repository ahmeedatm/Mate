import fs from "node:fs/promises";
import path from "node:path";
import { findGame, type Game } from "./cyberpunk-steam.ts";
import { isAppRunning } from "./os.ts";
import { backendRoot } from "./paths.ts";

/**
 * Plugin Cyberpunk 2077 : lecture de ce que le mod CET de Mate écrit dans son dossier (`state.json` chaque seconde,
 * `inventory.json`, `contacts.json`, `quests.json` à part) et installation du mod. Contrat : docs/specs/2026-11-14-plugin-cyberpunk-design.md.
 * Le jeu n'est jamais piloté d'ici : les ordres passent par `commands.json` (autre lot).
 */

const GAME_PROCESS = "Cyberpunk2077";
/** Au-delà, `state.json` est périmé : le jeu est au menu ou fermé. */
export const STALE_MS = 10_000;
const ASSETS = path.join(backendRoot, "assets", "cyberpunk-mod");
const MOD_FILES = ["init.lua", "logic.lua", "VERSION"] as const;

type Json = Record<string, any>;

/** `state.json` encore bon : écrit il y a moins de 10 s (`at`, en secondes) et `seq` encore en train de changer. */
export function isFresh(at: unknown, seqSeenAt: number, now: number) {
  return typeof at === "number" && now - at * 1000 <= STALE_MS && now - seqSeenAt <= STALE_MS;
}

/** Dernier contenu valide de chaque fichier du mod : un JSON coupé ou illisible ne le remplace pas. */
const lastGood = new Map<string, Json>();
const seqSeen = new Map<string, { seq: unknown; at: number }>();

async function readBlock(file: string, valid: (j: Json) => boolean): Promise<Json | null> {
  try {
    const json = JSON.parse(await fs.readFile(file, "utf8"));
    if (json && typeof json === "object" && valid(json)) lastGood.set(file, json);
  } catch (e) {
    // Coupé ou en cours d'écriture : on garde le dernier état valide ; fichier absent (mod retiré) : plus rien.
    if ((e as NodeJS.ErrnoException).code === "ENOENT") lastGood.delete(file);
  }
  return lastGood.get(file) ?? null;
}

export async function readState(game: Game): Promise<{ state: Json | null; fresh: boolean }> {
  const file = path.join(game.modDir, "state.json");
  const state = await readBlock(file, (j) => typeof j.seq === "number");
  if (!state) return { state: null, fresh: false };
  const now = Date.now();
  const seen = seqSeen.get(file);
  if (seen?.seq !== state.seq) seqSeen.set(file, { seq: state.seq, at: now });
  return { state, fresh: isFresh(state.at, seqSeen.get(file)!.at, now) };
}

async function running(fresh: boolean) {
  return fresh || (await isAppRunning(GAME_PROCESS).catch(() => false));
}

async function readText(file: string) {
  return fs.readFile(file, "utf8").catch(() => null);
}

/** Mod à installer à la fermeture du jeu (`init.lua` changé pendant qu'il tournait). */
let pending = false;

type Differs = Record<(typeof MOD_FILES)[number], boolean>;

async function differs(game: Game): Promise<Differs> {
  const out = {} as Differs;
  for (const f of MOD_FILES) out[f] = (await readText(path.join(game.modDir, f))) !== (await readText(path.join(ASSETS, f)));
  return out;
}

async function write(game: Game, name: string, body: string) {
  const file = path.join(game.modDir, name);
  await fs.mkdir(game.modDir, { recursive: true });
  await fs.writeFile(`${file}.tmp`, body);
  await fs.rename(`${file}.tmp`, file);
}

/** Copie les fichiers qui changent ; `reload` : écrit `reload.txt` pour que le chargeur relise `logic.lua` jeu ouvert. */
async function copyMod(game: Game, diff: Differs, reload: boolean) {
  for (const f of MOD_FILES) if (diff[f]) await write(game, f, await fs.readFile(path.join(ASSETS, f), "utf8"));
  if (reload && diff["logic.lua"]) await write(game, "reload.txt", "1");
}

/** Installation reportée : faite au premier appel une fois le jeu fermé. */
async function settle(game: Game, fresh: boolean) {
  if (!pending || (await running(fresh))) return;
  pending = false;
  await copyMod(game, await differs(game), false);
}

const gameFail = (message: string, statusCode: number): never => {
  throw Object.assign(new Error(message), { statusCode });
};

/** Réponse pour la tablette, qui la sonde sur chaque ordinateur Windows appairé, `available: false` s'il n'y a rien à voir. */
export async function presence(on: boolean) {
  const game = on ? findGame() : null;
  if (!game) return { available: false, gameFound: false, modInstalled: false, running: false, inGame: false, session: 0 };
  const { state, fresh } = await readState(game);
  await settle(game, fresh);
  return {
    available: true,
    gameFound: true,
    modInstalled: (await readText(path.join(game.modDir, "init.lua"))) !== null,
    running: await running(fresh),
    inGame: fresh && state?.inGame === true,
    session: typeof state?.session === "number" ? state.session : 0,
  };
}

export async function overview() {
  const game = findGame();
  const install = {
    gameFound: game !== null,
    cet: game?.cet ?? false,
    modInstalled: false,
    modVersion: null as string | null,
    current: false,
    pending,
  };
  if (!game) return { status: "off", install, state: null };
  const { state, fresh } = await readState(game);
  await settle(game, fresh);
  const diff = await differs(game);
  install.modInstalled = (await readText(path.join(game.modDir, "init.lua"))) !== null;
  install.modVersion = (await readText(path.join(game.modDir, "VERSION")))?.trim() ?? null;
  install.current = install.modInstalled && !Object.values(diff).some(Boolean);
  install.pending = pending;
  const status = fresh && state?.inGame === true ? "ingame" : (await running(fresh)) ? "menu" : "off";
  return { status, install, state: status === "off" ? null : state };
}

const BLOCKS = {
  inventory: { file: "inventory.json", empty: { v: 1, items: [] } },
  contacts: { file: "contacts.json", empty: { v: 1, contacts: [], threads: [] } },
  quests: { file: "quests.json", empty: { v: 1, quests: [] } },
  mappins: { file: "mappins.json", empty: { v: 1, mappins: [] } },
} as const;

/** Blocs lents (inventaire, contacts, quêtes, points de la carte) ; vide tant que le mod ne les a pas écrits. */
export async function block(name: keyof typeof BLOCKS) {
  const game = findGame();
  const { file, empty } = BLOCKS[name];
  return (game && (await readBlock(path.join(game.modDir, file), (j) => j.v === 1))) || empty;
}

/**
 * Copie le mod dans le dossier de CET. `init.lua` changé pendant que le jeu tourne : rien n'est écrit, installation
 * à la fermeture (`deferred`). `logic.lua` seul : remplacé et rechargé par le chargeur (`reload.txt`).
 */
export async function installMod() {
  const game = findGame() ?? gameFail("Cyberpunk 2077 (Steam) est introuvable sur cet ordinateur", 404);
  if (!game.cet) gameFail("Cyber Engine Tweaks n'est pas installé dans le jeu", 409);
  const diff = await differs(game);
  const { fresh } = await readState(game);
  const live = await running(fresh);
  if (diff["init.lua"] && live) {
    pending = true;
    return { ok: true, deferred: true };
  }
  pending = false;
  await copyMod(game, diff, live);
  return { ok: true, deferred: false };
}

/** Supprime le dossier du mod (chemin calculé, jamais reçu de la requête). */
export async function uninstallMod() {
  const game = findGame() ?? gameFail("Cyberpunk 2077 (Steam) est introuvable sur cet ordinateur", 404);
  pending = false;
  await fs.rm(game.modDir, { recursive: true, force: true });
  lastGood.clear();
  seqSeen.clear();
  return { ok: true, deferred: false };
}
