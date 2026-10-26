import dgram from "node:dgram";
import os from "node:os";
import { randomInt, randomUUID } from "node:crypto";
import { config } from "./config.ts";
import { logFailure } from "./log.ts";
import { alert, computerName, osName } from "./os.ts";

/**
 * Appairage d'une tablette sans rien recopier à la main :
 * 1. Découverte : la tablette envoie "UNOWHY-COMPANION?" en diffusion UDP sur le port du serveur ;
 *    chaque ordinateur du réseau répond avec son identité (id, nom, système, port).
 * 2. Code : la tablette demande un appairage, l'ordinateur affiche un code à 6 chiffres
 *    (boîte de dialogue et page http://127.0.0.1:3000/) ; la tablette l'envoie et reçoit le jeton.
 * Trois essais par code, deux minutes de validité, cinq demandes par adresse et par tranche de cinq minutes.
 * Un code encore valide n'est jamais remplacé : la même tablette retrouve sa demande, une autre adresse attend.
 */

export const PROBE = "UNOWHY-COMPANION?";
const CODE_TTL_MS = 2 * 60_000;
const MAX_ATTEMPTS = 3;

export const name = computerName();

export function identity() {
  return { service: "unowhy-companion", id: config.computerId, name, os: osName, port: config.port };
}

/** Adresses IPv4 du réseau local, pour la page d'accueil et la saisie manuelle. */
export function lanAddresses(): string[] {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((a) => a && a.family === "IPv4" && !a.internal)
    .map((a) => a!.address);
}

export function startDiscovery(log: (msg: string) => void) {
  if (config.loopbackOnly) return;
  const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
  socket.on("message", (msg, from) => {
    if (msg.toString("utf8").trim() !== PROBE) return;
    socket.send(JSON.stringify(identity()), from.port, from.address);
  });
  socket.on("error", (e) => {
    log(`Découverte réseau indisponible (${e.message}) : la tablette devra saisir l'adresse à la main`);
    socket.close();
  });
  socket.bind(config.port, config.host === "0.0.0.0" || config.host === "::" ? undefined : config.host);
}

type Pending = { id: string; code: string; tablet: string; ip: string; expires: number; attempts: number };
let pending: Pending | undefined;
/** Demandes récentes par adresse (sans compter les adresses qui n'ont plus rien de récent). */
const recent = new Map<string, number[]>();

/** Remet l'appairage à zéro (tests). */
export function resetPairing() {
  pending = undefined;
  recent.clear();
}

export class PairingError extends Error {
  constructor(public statusCode: number, message: string) {
    super(message);
  }
}

const showCode = (text: string) => alert("Compagnon Unowhy", text);

/**
 * Demande d'appairage : affiche le code sur l'ordinateur. Une seule demande à la fois : tant que le code est valide,
 * la même adresse retrouve la demande en cours (pas de second dialogue), une autre reçoit 409.
 */
export function requestPairing(tablet: string, ip: string, show: (text: string) => Promise<unknown> = showCode) {
  const now = Date.now();
  for (const [from, times] of recent) {
    const fresh = times.filter((t) => now - t <= 5 * 60_000);
    if (fresh.length) recent.set(from, fresh);
    else recent.delete(from);
  }
  const mine = recent.get(ip) ?? [];
  if (mine.length >= 5) throw new PairingError(429, "Trop de demandes, réessaie dans quelques minutes");
  recent.set(ip, [...mine, now]);

  if (pending && now < pending.expires) {
    if (pending.ip !== ip) throw new PairingError(409, "Un appairage est déjà en cours sur cet ordinateur, réessaie dans quelques minutes");
    return { request: pending.id, expiresInMs: pending.expires - now };
  }
  const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
  pending = { id: randomUUID(), code, tablet: tablet.slice(0, 60), ip, expires: now + CODE_TTL_MS, attempts: 0 };
  const spaced = `${code.slice(0, 3)} ${code.slice(3)}`;
  console.log(`\n  Code d'appairage pour ${pending.tablet} : ${spaced}\n`);
  void show(`Code à saisir sur la tablette (${pending.tablet}) :\n\n${spaced}`).catch((e) => logFailure("Dialogue d'appairage", e));
  return { request: pending.id, expiresInMs: CODE_TTL_MS };
}

/** Vérifie le code ; renvoie le jeton et l'identité de l'ordinateur. */
export function confirmPairing(request: string, code: string) {
  const p = pending;
  if (!p || p.id !== request || Date.now() > p.expires) throw new PairingError(410, "Code expiré : recommence l'appairage");
  if (code.replace(/\D/g, "") !== p.code) {
    p.attempts++;
    if (p.attempts >= MAX_ATTEMPTS) pending = undefined;
    throw new PairingError(403, pending ? "Code incorrect" : "Code incorrect : recommence l'appairage");
  }
  pending = undefined;
  return { token: config.token ?? "", ...identity() };
}

/** Pour la page locale : le code en attente, s'il y en a un. */
export function pendingCode() {
  return pending && Date.now() < pending.expires ? { code: pending.code, tablet: pending.tablet } : null;
}
