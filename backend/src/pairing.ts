import dgram from "node:dgram";
import os from "node:os";
import { createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { config } from "./config.ts";
import { logFailure } from "./log.ts";
import { alert, computerName, openTarget, osName, pointerMove, pointerScroll } from "./os.ts";
import { handlePointerDatagram, POINTER_HEADER, PointerGate } from "./pointer.ts";

/**
 * Appairage d'une tablette sans rien recopier à la main (protocole détaillé : docs/securite-appairage.md) :
 * 1. Découverte : la tablette envoie "UNOWHY-COMPANION?" en diffusion UDP sur le port du serveur ;
 *    chaque ordinateur du réseau répond avec son identité (id, nom, système, ports).
 * 2. Code : en WiFi, la tablette parle en HTTPS (`tlsPort`) et retient le certificat qu'elle voit. Elle s'engage
 *    sur un nombre secret (`commit` = SHA-256 du nombre), reçoit celui de l'ordinateur, puis dévoile le sien.
 *    Les deux côtés calculent alors un code à 6 chiffres à partir des deux nombres et de l'empreinte du certificat :
 *    la tablette l'affiche, l'utilisateur le saisit sur l'ordinateur (page http://127.0.0.1:3000/). Si quelqu'un
 *    s'est glissé entre les deux avec son propre certificat, les codes diffèrent (une chance sur un million de
 *    tomber juste : l'engagement l'empêche d'essayer plusieurs nombres). Le jeton n'est donné qu'après la saisie.
 * Trois essais par code, deux minutes de validité, cinq demandes par adresse et par tranche de cinq minutes.
 * Une demande encore valide n'est jamais remplacée par une autre adresse : elle attend.
 */

export const PROBE = "UNOWHY-COMPANION?";
const CODE_TTL_MS = 2 * 60_000;
const MAX_ATTEMPTS = 3;

export const name = computerName();

/** `tlsPort` : HTTPS pour le WiFi ; null quand le serveur n'écoute qu'en local (USB). */
export function identity() {
  return {
    service: "unowhy-companion",
    id: config.computerId,
    name,
    os: osName,
    port: config.port,
    tlsPort: config.loopbackOnly ? null : config.tlsPort,
  };
}

/** Adresses IPv4 du réseau local, pour la page d'accueil et la saisie manuelle. */
export function lanAddresses(): string[] {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((a) => a && a.family === "IPv4" && !a.internal)
    .map((a) => a!.address);
}

/**
 * UDP sur le port HTTP (découverte) et sur le port HTTPS : la tablette envoie les paquets du pavé tactile au port de
 * l'adresse qu'elle connaît, c'est-à-dire le port HTTPS en WiFi.
 */
export function startDiscovery(log: (msg: string) => void) {
  if (config.loopbackOnly) return;
  const gate = new PointerGate();
  for (const port of [config.port, config.tlsPort]) listenUdp(port, gate, log);
}

function listenUdp(port: number, gate: PointerGate, log: (msg: string) => void) {
  const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  socket.on("message", (msg, from) => {
    // Pavé tactile : paquets de déplacement ; tout le reste de la découverte est inchangé (src/pointer.ts).
    if (msg.subarray(0, POINTER_HEADER.length).toString("utf8") === POINTER_HEADER) {
      void handlePointerDatagram(gate, msg, from, config.token, { move: pointerMove, scroll: pointerScroll });
      return;
    }
    if (msg.toString("utf8").trim() !== PROBE) return;
    socket.send(JSON.stringify(identity()), from.port, from.address);
  });
  socket.on("error", (e) => {
    log(`Découverte réseau indisponible (${e.message}) : la tablette devra saisir l'adresse à la main`);
    socket.close();
  });
  socket.bind(port, config.host === "0.0.0.0" || config.host === "::" ? undefined : config.host);
}

type Pending = {
  id: string;
  tablet: string;
  ip: string;
  expires: number;
  attempts: number;
  /** SHA-256 (base64url) du nombre de la tablette, reçu avant qu'elle ne connaisse celui de l'ordinateur. */
  commit: string;
  /** Nombre de l'ordinateur. */
  nonce: string;
  /** Empreinte du certificat de la connexion de la demande ; "" en HTTP local (USB : rien à intercepter). */
  channel: string;
  /** Code, calculé quand la tablette a dévoilé son nombre. */
  code?: string;
  approved: boolean;
};
let pending: Pending | undefined;

const sha256 = (s: string) => createHash("sha256").update(s).digest();

/** Code à 6 chiffres commun aux deux côtés (même calcul dans PairingCode.kt). */
export function pairingCode(channel: string, tabletNonce: string, computerNonce: string) {
  const n = sha256(`unowhy-pair-1\n${channel}\n${tabletNonce}\n${computerNonce}`).readUInt32BE(0) % 1_000_000;
  return String(n).padStart(6, "0");
}
/** Demandes récentes par adresse (sans compter les adresses qui n'ont plus rien de récent). */
const recent = new Map<string, number[]>();

/** Remet l'appairage à zéro (tests). */
export function resetPairing() {
  pending = undefined;
  recent.clear();
}

export class PairingError extends Error {
  constructor(
    public statusCode: number,
    message: string,
  ) {
    super(message);
  }
}

/** Prévient l'utilisateur et ouvre la page où saisir le code. */
const askCode = async (text: string) => {
  if (process.env.NO_ALERT || process.env.COMPANION_HEADLESS === "1") return;
  await openTarget(`http://127.0.0.1:${config.port}/?section=pairing`).catch((e) => logFailure("Page d'appairage", e));
  await alert("Compagnon Unowhy", text);
};

/**
 * Demande d'appairage (étape 1) : `commit` = SHA-256 (base64url) du nombre secret de la tablette ; renvoie celui de
 * l'ordinateur. Une seule demande à la fois : la même adresse repart de zéro (nouvel engagement), une autre reçoit 409.
 */
export function requestPairing(tablet: string, ip: string, commit: string, channel: string) {
  const now = Date.now();
  for (const [from, times] of recent) {
    const fresh = times.filter((t) => now - t <= 5 * 60_000);
    if (fresh.length) recent.set(from, fresh);
    else recent.delete(from);
  }
  const mine = recent.get(ip) ?? [];
  if (mine.length >= 5) throw new PairingError(429, "Trop de demandes, réessaie dans quelques minutes");
  recent.set(ip, [...mine, now]);

  if (pending && now < pending.expires && pending.ip !== ip) {
    throw new PairingError(409, "Un appairage est déjà en cours sur cet ordinateur, réessaie dans quelques minutes");
  }
  const nonce = randomBytes(32).toString("base64url");
  pending = {
    id: randomUUID(),
    tablet: tablet.slice(0, 60),
    ip,
    expires: now + CODE_TTL_MS,
    attempts: 0,
    commit,
    nonce,
    channel,
    approved: false,
  };
  return { request: pending.id, nonce, expiresInMs: CODE_TTL_MS };
}

const current = (request: string) => {
  const p = pending;
  if (!p || p.id !== request || Date.now() > p.expires) throw new PairingError(410, "Code expiré : recommence l'appairage");
  return p;
};

/**
 * Étape 2 : la tablette dévoile son nombre (vérifié contre l'engagement), l'ordinateur calcule le code et demande
 * de le saisir. `show` : remplacé dans les tests.
 */
export function revealPairing(request: string, tabletNonce: string, show: (text: string) => Promise<unknown> = askCode) {
  const p = current(request);
  if (p.code) throw new PairingError(409, "Code déjà affiché : saisis-le sur l'ordinateur");
  const commit = Buffer.from(p.commit, "base64url");
  if (commit.length !== 32 || !timingSafeEqual(sha256(tabletNonce), commit)) {
    pending = undefined;
    throw new PairingError(403, "Demande d'appairage invalide : recommence");
  }
  p.code = pairingCode(p.channel, tabletNonce, p.nonce);
  console.log(`\n  Appairage demandé par ${p.tablet} : saisir sur http://127.0.0.1:${config.port}/ le code affiché sur la tablette\n`);
  void show(
    `La tablette « ${p.tablet} » demande à se connecter.\n\nSaisis le code affiché sur la tablette dans la page du compagnon qui vient de s'ouvrir.`,
  ).catch((e) => logFailure("Dialogue d'appairage", e));
  return { expiresInMs: p.expires - Date.now() };
}

/** Étape 3, sur l'ordinateur (page locale) : le code affiché par la tablette. */
export function approvePairing(code: string) {
  const p = pending;
  if (!p || Date.now() > p.expires || !p.code)
    throw new PairingError(410, "Aucune tablette n'attend : recommence l'appairage depuis la tablette");
  const given = Buffer.from(code.replace(/\D/g, ""));
  if (given.length !== 6 || !timingSafeEqual(given, Buffer.from(p.code))) {
    p.attempts++;
    if (p.attempts >= MAX_ATTEMPTS) pending = undefined;
    throw new PairingError(403, pending ? "Code incorrect" : "Code incorrect : recommence l'appairage depuis la tablette");
  }
  p.approved = true;
  return { tablet: p.tablet };
}

/**
 * Étape 4 : la tablette attend la saisie (elle redemande chaque seconde) ; une fois le code accepté, elle reçoit le
 * jeton et l'empreinte du certificat (à retenir : en USB, elle ne l'a pas vu ; en WiFi, elle la compare).
 */
export function confirmPairing(request: string, fingerprint: string) {
  const p = current(request);
  if (!p.approved) return { waiting: true as const };
  pending = undefined;
  return { token: config.token ?? "", fingerprint, ...identity() };
}

/** Pour la page locale : la tablette qui attend qu'on saisisse son code (jamais le code lui-même). */
export function pendingCode() {
  if (!pending || Date.now() >= pending.expires || !pending.code || pending.approved) return null;
  // En secondes (jamais 6 chiffres à la suite : la page ne montre aucun code) ; la page en tire son compte à rebours.
  return { tablet: pending.tablet, remainingS: Math.ceil((pending.expires - Date.now()) / 1000), ttlS: CODE_TTL_MS / 1000 };
}
