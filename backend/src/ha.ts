/**
 * Home Assistant de la maison, via un jeton d'accès longue durée (profil de l'utilisateur dans Home Assistant).
 * Optionnel : actif seulement si HA_URL, HA_TOKEN et HA_ENTITIES sont définis dans .env. Le jeton reste sur l'ordinateur :
 * la tablette ne reçoit que l'état des entités de HA_ENTITIES (liste blanche) et ne peut agir que sur elles.
 * Home Assistant tourne presque toujours en http sur le réseau de la maison : http est accepté ici, contrairement à Jira.
 */

const rawUrl = process.env.HA_URL?.trim().replace(/\/+$/, "");
const token = process.env.HA_TOKEN?.trim();

/** Ce que la tablette peut piloter : `toggle` bascule (lumière, prise…), `run` lance (scène, script). */
const SERVICES = {
  light: { kind: "toggle", service: "toggle" },
  switch: { kind: "toggle", service: "toggle" },
  input_boolean: { kind: "toggle", service: "toggle" },
  fan: { kind: "toggle", service: "toggle" },
  scene: { kind: "run", service: "turn_on" },
  script: { kind: "run", service: "turn_on" },
} as const;

export const HA_ENTITY_ID = /^(light|switch|input_boolean|fan|scene|script)\.[a-z0-9_]+$/;

const base = rawUrl && /^https?:\/\/[^/\s]+$/i.test(rawUrl) ? rawUrl : undefined;

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
  }).catch(() => {
    // Réseau coupé, DNS, délai dépassé : « fetch failed » ne dit rien à l'écran de la tablette.
    throw new Error("Home Assistant ne répond pas : vérifie la connexion de l'ordinateur.");
  });
  if (res.status === 401 || res.status === 403) throw new Error("Home Assistant refuse le jeton du fichier .env de l'ordinateur.");
  if (!res.ok) throw new Error(`Home Assistant HTTP ${res.status}`);
  return (await res.json()) as T;
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
