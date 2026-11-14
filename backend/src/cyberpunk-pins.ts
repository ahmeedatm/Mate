import path from "node:path";
import { enqueue, inGame, sendCommand } from "./cyberpunk-commands.ts";
import { findGame } from "./cyberpunk-steam.ts";
import { readState } from "./cyberpunk.ts";
import { logFailure } from "./log.ts";
import { dataDir } from "./paths.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Repères posés par Ahmed sur la carte : gardés dans `.data/cyberpunk-pins.json` et envoyés au mod (`pin`, `unpin`).
 * Le jeu les oublie à chaque partie chargée : au changement de session (ou mod rechargé), les repères absents de
 * `state.pins` sont renvoyés, une fois chacun. Le mod ignore un `pid` qu'il a déjà (« exists »).
 */

export type Pin = { id: number; x: number; y: number; z: number; name?: string; at: number };
/** `next` : dernier id attribué + 1, jamais réutilisé même après suppression. */
type PinsFile = { pins: Pin[]; tracked?: number; next?: number };

const file = () => path.join(dataDir, "cyberpunk-pins.json");
const empty = (): PinsFile => ({ pins: [] });
/** Le mod garde au plus 20 ordres : un rejeu plus long se fait en plusieurs sondages. */
const REPLAY_BATCH = 20;

const notFound = () => Object.assign(new Error("Repère inconnu"), { statusCode: 404 });
const pinCmd = (p: Pin) => ({ t: "pin" as const, pid: p.id, x: p.x, y: p.y, z: p.z });

export const listPins = () => readJson(file(), empty);

/** Repères déjà envoyés pour la session et le compteur `seq` du mod en cours. */
const sent = new Set<number>();
let epoch: { session: unknown; seq: number } | null = null;

export async function addPin(input: { x: number; y: number; z?: number; name?: string }) {
  const pin: Pin = { id: 0, x: input.x, y: input.y, z: input.z ?? 0, ...(input.name ? { name: input.name } : {}), at: Date.now() };
  await updateJson(file(), empty, (d) => {
    pin.id = Math.max(d.next ?? 1, ...d.pins.map((p) => p.id + 1));
    d.next = pin.id + 1;
    d.pins.push(pin);
  });
  if (await inGame()) {
    sent.add(pin.id);
    await sendCommand(pinCmd(pin)).catch((e) => logFailure("repère Cyberpunk non confirmé par le mod", e));
  }
  return { id: pin.id };
}

export async function removePin(id: number) {
  const found = await updateJson(file(), empty, (d) => {
    const had = d.pins.some((p) => p.id === id);
    d.pins = d.pins.filter((p) => p.id !== id);
    if (d.tracked === id) delete d.tracked;
    return had;
  });
  if (!found) throw notFound();
  sent.delete(id);
  if (await inGame()) await enqueue([{ t: "unpin", pid: id }]).catch((e) => logFailure("repère Cyberpunk non retiré dans le mod", e));
  return { ok: true };
}

export async function trackPin(id: number) {
  if (!(await listPins()).pins.some((p) => p.id === id)) throw notFound();
  const res = await sendCommand({ t: "track", kind: "pin", ref: id });
  if (res.ok) await updateJson(file(), empty, (d) => void (d.tracked = id));
  return res;
}

let replaying = false;

/** À appeler quand l'état du jeu vient d'être lu : renvoie au mod les repères qu'il n'a pas. Jamais d'erreur vers l'appelant. */
export async function replayPins() {
  if (replaying) return;
  replaying = true;
  try {
    const game = findGame();
    if (!game) return;
    const { state, fresh } = await readState(game);
    if (!fresh || state?.inGame !== true) return;
    // Nouvelle partie, ou mod rechargé (son compteur repart de zéro) : le jeu n'a plus rien, on repart de zéro.
    if (!epoch || epoch.session !== state.session || state.seq < epoch.seq) sent.clear();
    epoch = { session: state.session, seq: state.seq };
    const inGameNow = new Set(((state.pins as { pid: unknown }[] | undefined) ?? []).map((p) => String(p.pid)));
    const missing = (await listPins()).pins.filter((p) => !inGameNow.has(String(p.id)) && !sent.has(p.id)).slice(0, REPLAY_BATCH);
    if (missing.length === 0) return;
    for (const p of missing) sent.add(p.id);
    await enqueue(missing.map(pinCmd));
  } catch (e) {
    logFailure("rejeu des repères Cyberpunk", e);
  } finally {
    replaying = false;
  }
}
