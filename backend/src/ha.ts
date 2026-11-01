/**
 * Home Assistant de la maison, via un jeton d'accès longue durée (profil de l'utilisateur dans Home Assistant).
 * Optionnel : actif seulement si HA_URL, HA_TOKEN et HA_ENTITIES sont définis dans .env. Le jeton reste sur l'ordinateur :
 * la tablette ne reçoit que l'état des entités de HA_ENTITIES (liste blanche) et ne peut agir que sur elles.
 * Le jeton a tous les droits du compte : comme pour Jira, https seulement, sauf http sur cet ordinateur (127.0.0.1,
 * localhost) ou avec HA_ALLOW_HTTP=1, que l'on pose en sachant que le jeton passe alors en clair sur le WiFi.
 */

import { readCapped } from "./net-guard.ts";

const rawUrl = process.env.HA_URL?.trim().replace(/\/+$/, "");
const token = process.env.HA_TOKEN?.trim();

/** Ce que la tablette peut piloter : `toggle` bascule (lumière, prise), `run` lance (scène). */
const SERVICES = {
  light: { kind: "toggle", service: "toggle" },
  switch: { kind: "toggle", service: "toggle" },
  scene: { kind: "run", service: "turn_on" },
} as const;

export const HA_ENTITY_ID = /^(light|switch|scene)\.[a-z0-9_]+$/;

/** Réponse lue au plus : `/api/states` d'une grosse maison fait quelques centaines de Ko. */
const MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

const LOCAL_HTTP = /^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/i;
const allowHttp = process.env.HA_ALLOW_HTTP?.trim() === "1";

function safeBase(url: string | undefined): string | undefined {
  if (!url || !/^https?:\/\/[^/\s]+$/i.test(url)) return undefined;
  if (/^https:/i.test(url) || LOCAL_HTTP.test(url) || allowHttp) return url;
  console.warn("Home Assistant ignoré : HA_URL en http enverrait le jeton en clair sur le réseau (https, ou HA_ALLOW_HTTP=1).");
  return undefined;
}

const base = safeBase(rawUrl);

/** Liste blanche, dans l'ordre du .env, sans doublon ; un identifiant mal écrit ou d'un domaine non géré est ignoré. */
export const haEntityIds = [
  ...new Set(
    (process.env.HA_ENTITIES ?? "")
      .split(",")
      .map((id) => id.trim())
      .filter((id) => HA_ENTITY_ID.test(id)),
  ),
];

export const haConfigured = Boolean(base && token && haEntityIds.length > 0);

export type HaEntity = { id: string; name: string; kind: "toggle" | "run"; on: boolean; available: boolean };

const domainOf = (id: string) => id.split(".")[0] as keyof typeof SERVICES;

async function ha<T>(path: string, init?: { body: object }): Promise<T> {
  const res = await fetch(`${base}${path}`, {
    method: init ? "POST" : "GET",
    headers: { authorization: `Bearer ${token}`, ...(init ? { "content-type": "application/json" } : {}) },
    body: init && JSON.stringify(init.body),
    signal: AbortSignal.timeout(10_000),
    // Une redirection enverrait la requête ailleurs que vers l'instance configurée.
    redirect: "error",
  }).catch(() => {
    // Réseau coupé, DNS, délai dépassé : « fetch failed » ne dit rien à l'écran de la tablette.
    throw new Error("Home Assistant ne répond pas : vérifie la connexion de l'ordinateur.");
  });
  if (res.status === 401 || res.status === 403) throw new Error("Home Assistant refuse le jeton du fichier .env de l'ordinateur.");
  if (!res.ok) throw new Error("Home Assistant n'a pas pu faire cela.");
  const { data, truncated } = await readCapped(res, MAX_RESPONSE_BYTES);
  try {
    if (truncated) throw new Error("réponse trop grosse");
    return JSON.parse(data.toString("utf8")) as T;
  } catch (err) {
    // Portail captif, proxy : le corps reçu n'a rien à faire sur l'écran de la tablette.
    console.warn(`Home Assistant : réponse illisible (${(err as Error).message.slice(0, 120)})`);
    throw new Error("Home Assistant a répondu quelque chose d'illisible.");
  }
}

type RawState = { entity_id: string; state: string; attributes?: { friendly_name?: string } };

/** État des entités de la liste blanche. Une entité que Home Assistant ne connaît pas ou n'atteint plus reste listée, grisée. */
export async function haEntities(): Promise<HaEntity[]> {
  const byId = new Map((await ha<RawState[]>("/api/states")).map((s) => [s.entity_id, s]));
  return haEntityIds.map((id) => {
    const s = byId.get(id);
    const available = Boolean(s) && s?.state !== "unavailable" && s?.state !== "unknown";
    return {
      id,
      name: s?.attributes?.friendly_name || id,
      kind: SERVICES[domainOf(id)].kind,
      on: available && s?.state === "on",
      available,
    };
  });
}

/** Bascule la lumière ou la prise, lance la scène. Faux : l'entité n'est pas dans la liste blanche, rien n'est envoyé. */
export async function haActivate(id: string): Promise<boolean> {
  if (!haEntityIds.includes(id)) return false;
  const domain = domainOf(id);
  await ha(`/api/services/${domain}/${SERVICES[domain].service}`, { body: { entity_id: id } });
  return true;
}
