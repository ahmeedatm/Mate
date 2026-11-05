import fs from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import { riotLockfile } from "./os.ts";

/**
 * Accès au client Riot de l'ordinateur (API locale : port et mot de passe du « lockfile ») et, avec le jeton qu'il donne,
 * aux serveurs Riot de la région (glz : partie en cours ; pd : rangs, noms, historique). Le jeton ne quitte jamais
 * l'ordinateur autrement que vers ces serveurs.
 */

/** Région du client (`-ares-deployment=`) → serveurs glz et pd. */
const SHARDS: Record<string, { region: string; shard: string }> = {
  eu: { region: "eu", shard: "eu" },
  na: { region: "na", shard: "na" },
  latam: { region: "latam", shard: "na" },
  br: { region: "br", shard: "na" },
  ap: { region: "ap", shard: "ap" },
  kr: { region: "kr", shard: "kr" },
};

/** En-tête de plateforme attendu par les serveurs Riot (celui du client PC). */
const PLATFORM = Buffer.from(
  JSON.stringify({ platformType: "PC", platformOS: "Windows", platformOSVersion: "10.0.19042.1.256.64bit", platformChipset: "Unknown" }),
).toString("base64");

export class RiotError extends Error {
  constructor(readonly status: number) {
    super(`Riot a répondu ${status}`);
  }
}

export type Lock = { port: number; password: string; protocol: string };

export async function readLock(): Promise<Lock | null> {
  const file = riotLockfile();
  if (!file) return null;
  const text = await fs.readFile(file, "utf8").catch(() => null);
  // nom:pid:port:mot de passe:protocole
  const [, , port, password, protocol] = text?.trim().split(":") ?? [];
  return port && password ? { port: Number(port), password, protocol: protocol === "http" ? "http" : "https" } : null;
}

/** API locale du client Riot : 127.0.0.1 seulement, certificat du client (auto-signé) accepté. */
export function local(lock: Lock, route: string): Promise<any> {
  return new Promise((resolve, reject) => {
    const req = (lock.protocol === "http" ? http : https).request(
      {
        host: "127.0.0.1",
        port: lock.port,
        path: route,
        rejectUnauthorized: false,
        timeout: 3000,
        headers: { authorization: `Basic ${Buffer.from(`riot:${lock.password}`).toString("base64")}` },
      },
      (res) => {
        let raw = "";
        res.setEncoding("utf8");
        res.on("data", (c) => (raw += c));
        res.on("end", () => {
          if (res.statusCode !== 200) return reject(new RiotError(res.statusCode ?? 0));
          try {
            resolve(JSON.parse(raw));
          } catch (e) {
            reject(e);
          }
        });
      },
    );
    req.on("timeout", () => req.destroy(new Error("Client Riot muet")));
    req.on("error", reject);
    req.end();
  });
}

export type Remote = { glz: string; pd: string; headers: Record<string, string> };

export async function remote(r: Remote, url: string, init: RequestInit = {}): Promise<any> {
  // Pas de redirection suivie : les jetons ne partent que vers les serveurs Riot (ou la boucle locale des tests).
  const res = await fetch(url, {
    ...init,
    headers: { ...r.headers, ...init.headers },
    redirect: "error",
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new RiotError(res.status);
  return res.json();
}

/**
 * Serveur Riot : `VALORANT_GLZ_URL` / `VALORANT_PD_URL` remplacent les serveurs de Riot pour les tests (faux serveurs),
 * mais seulement vers la boucle locale : le jeton Riot ne part jamais vers une autre machine.
 */
export function riotServer(override: string | undefined, riot: string): string {
  if (!override) return riot;
  try {
    const host = new URL(override).hostname;
    return host === "127.0.0.1" || host === "localhost" ? override : riot;
  } catch {
    return riot;
  }
}

/** Jeton du client et serveurs de sa région ; null si le client ne les donne pas (pas connecté). */
export async function remoteFor(lock: Lock, version: string): Promise<Remote | null> {
  try {
    const token = await local(lock, "/entitlements/v1/token");
    // Client lancé sans compte connecté : pas de jeton, rien ne part vers Riot.
    if (!token?.accessToken || !token.token) return null;
    const sessions = await local(lock, "/product-session/v1/external-sessions");
    const args: string[] =
      (Object.values(sessions ?? {}) as any[]).find((s) => s?.productId === "valorant")?.launchConfiguration?.arguments ?? [];
    const deployment = args.find((a) => a.startsWith("-ares-deployment="))?.split("=")[1] ?? "eu";
    const { region, shard } = SHARDS[deployment] ?? SHARDS.eu;
    return {
      glz: riotServer(process.env.VALORANT_GLZ_URL, `https://glz-${region}-1.${shard}.a.pvp.net`),
      pd: riotServer(process.env.VALORANT_PD_URL, `https://pd.${shard}.a.pvp.net`),
      headers: {
        authorization: `Bearer ${token.accessToken}`,
        "x-riot-entitlements-jwt": token.token,
        "x-riot-clientversion": version,
        "x-riot-clientplatform": PLATFORM,
      },
    };
  } catch {
    return null;
  }
}

/** Écart entre deux appels de la file (historiques et détails de parties : ~40 par partie). */
const gapMs = () => Number(process.env.VALORANT_GAP_MS ?? 300);
let tail: Promise<unknown> = Promise.resolve();

/**
 * File au débit limité vers pd, un appel à la fois : les stats de tracker ne déclenchent jamais une rafale qui ferait
 * refuser par Riot le rang des joueurs (appelé, lui, en dehors de la file).
 */
export function paced<T>(call: () => Promise<T>): Promise<T> {
  const run = tail.then(call);
  tail = run.catch(() => undefined).then(() => new Promise((r) => setTimeout(r, gapMs())));
  return run;
}
