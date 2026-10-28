/**
 * Pavé tactile de la tablette : paquets UDP de déplacement et de défilement (même port que la découverte, voir
 * pairing.ts). Logique pure : le branchement sur le pilotage de l'ordinateur est fait par l'appelant.
 *
 * Paquet : `UNOWHY-POINTER\n<jeton>\n<seq>\n<json>`, json = {"dx","dy"} (pixels) ou {"sx","sy"} (crans de molette).
 * Un paquet invalide, sans le bon jeton, ancien ou trop fréquent est ignoré sans réponse ; son contenu n'est jamais journalisé.
 */
import { timingSafeEqual } from "node:crypto";

export const POINTER_HEADER = "UNOWHY-POINTER\n";
/** Déplacement maximal par paquet, en pixels, et défilement maximal, en crans (valeur absolue). */
export const MAX_MOVE = 2000;
export const MAX_SCROLL = 50;
/** Débit maximal par source (adresse + port), en paquets par seconde. */
export const MAX_RATE = 200;
/** Un paquet de pavé tient en quelques dizaines d'octets : le reste n'est pas de nous. */
const MAX_PACKET = 512;
/** Source sans paquet depuis ce délai : son compteur repart de zéro (la tablette relancée recommence sa séquence). */
const IDLE_RESET_MS = 30_000;

export type PointerPacket = { seq: number; kind: "move"; dx: number; dy: number } | { seq: number; kind: "scroll"; sx: number; sy: number };
export type PointerButton = "left" | "right";
export type PointerAction = "click" | "down" | "up";

/** Gestes des pavés tactiles modernes : liste fermée, la tablette n'envoie jamais de raccourci libre. */
export const POINTER_GESTURES = [
  "zoom-in",
  "zoom-out",
  "back",
  "forward",
  "task-view",
  "desktop",
  "app-previous",
  "app-next",
  "search",
  "desktop-previous",
  "desktop-next",
  "notifications",
] as const;
export type PointerGesture = (typeof POINTER_GESTURES)[number];

const sameToken = (given: string, expected: string) => {
  const a = Buffer.from(given);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
};

const within = (v: unknown, max: number): v is number => Number.isInteger(v) && Math.abs(v as number) <= max;

/**
 * Lit un paquet ; null s'il est mal formé, sans le bon jeton (`token` absent : aucun jeton à vérifier, comme en HTTP)
 * ou hors bornes. Le débit et la séquence se contrôlent à part (`PointerGate`).
 */
export function parsePointerPacket(buf: Buffer, token: string | undefined): PointerPacket | null {
  if (buf.length > MAX_PACKET) return null;
  const parts = buf.toString("utf8").split("\n");
  if (parts.length !== 4 || parts[0] + "\n" !== POINTER_HEADER) return null;
  if (token !== undefined && !sameToken(parts[1], token)) return null;
  if (!/^\d{1,15}$/.test(parts[2])) return null;
  const seq = Number(parts[2]);
  let body: unknown;
  try {
    body = JSON.parse(parts[3]);
  } catch {
    return null;
  }
  if (typeof body !== "object" || body === null) return null;
  const b = body as Record<string, unknown>;
  if ("dx" in b || "dy" in b) return within(b.dx, MAX_MOVE) && within(b.dy, MAX_MOVE) ? { seq, kind: "move", dx: b.dx, dy: b.dy } : null;
  if ("sx" in b || "sy" in b)
    return within(b.sx, MAX_SCROLL) && within(b.sy, MAX_SCROLL) ? { seq, kind: "scroll", sx: b.sx, sy: b.sy } : null;
  return null;
}

type Source = { seq: number; windowStart: number; count: number; last: number };

/** Séquence et débit par source : un paquet n'est retenu que si son `seq` dépasse le dernier vu et que le débit tient. */
export class PointerGate {
  private sources = new Map<string, Source>();

  accept(source: string, seq: number, now = Date.now()): boolean {
    for (const [key, s] of this.sources) if (now - s.last > IDLE_RESET_MS) this.sources.delete(key);
    let s = this.sources.get(source);
    if (!s) {
      s = { seq: -1, windowStart: now, count: 0, last: now };
      this.sources.set(source, s);
    }
    s.last = now;
    if (now - s.windowStart >= 1000) {
      s.windowStart = now;
      s.count = 0;
    }
    if (++s.count > MAX_RATE) return false;
    if (seq <= s.seq) return false;
    s.seq = seq;
    return true;
  }
}

export type PointerSink = {
  move: (dx: number, dy: number) => Promise<unknown>;
  scroll: (sx: number, sy: number) => Promise<unknown>;
};

/** Traite un datagramme déjà reconnu comme paquet de pavé ; ne répond jamais et n'écrit rien dans le journal. */
export async function handlePointerDatagram(
  gate: PointerGate,
  msg: Buffer,
  from: { address: string; port: number },
  token: string | undefined,
  sink: PointerSink,
  now = Date.now(),
): Promise<void> {
  const packet = parsePointerPacket(msg, token);
  if (!packet || !gate.accept(`${from.address}:${from.port}`, packet.seq, now)) return;
  try {
    if (packet.kind === "move") await sink.move(packet.dx, packet.dy);
    else await sink.scroll(packet.sx, packet.sy);
  } catch {
    // Pilotage indisponible : un paquet perdu n'est pas grave, et le détail n'a rien à faire ici.
  }
}
