import dns from "node:dns/promises";
import { isIP } from "node:net";
import { isPrivateAddress, readCapped } from "./net-guard.ts";

/**
 * Étape `http` d'une touche du Deck : appelle une cible du réseau local (prise, lampe, NAS). Les cibles se déclarent
 * sur l'ordinateur (`LAN_TARGETS` du .env) ; la tablette n'envoie que le nom d'une cible, jamais une adresse, et le
 * jeton reste ici. À l'inverse de net-guard.ts (icônes : jamais le réseau local), seul le réseau local est permis.
 */

type Target = { url: URL; method: "GET" | "POST" | "PUT"; token?: string };

const METHODS = ["GET", "POST", "PUT"];
const TIMEOUT_MS = 8000;
/** La réponse n'est pas montrée : on en lit juste de quoi vider la connexion. */
const DRAIN_BYTES = 64 * 1024;
const LOOPBACK = /^(127\.0\.0\.1|localhost|\[::1\])$/i;

function parseTargets(raw: string | undefined): Map<string, Target> {
  const targets = new Map<string, Target>();
  if (!raw?.trim()) return targets;
  let entries: Record<string, { url?: unknown; method?: unknown; token?: unknown }>;
  try {
    entries = JSON.parse(raw);
  } catch {
    console.warn("LAN_TARGETS ignoré : JSON illisible.");
    return targets;
  }
  for (const [name, entry] of Object.entries(entries ?? {})) {
    const method = String(entry?.method ?? "GET").toUpperCase();
    const token = typeof entry?.token === "string" && entry.token ? entry.token : undefined;
    let url: URL;
    try {
      url = new URL(String(entry?.url));
    } catch {
      console.warn(`Cible réseau « ${name} » ignorée : adresse illisible.`);
      continue;
    }
    if (!/^https?:$/.test(url.protocol) || !METHODS.includes(method)) {
      console.warn(`Cible réseau « ${name} » ignorée : http(s) et GET, POST ou PUT seulement.`);
      continue;
    }
    // Un jeton en http clair partirait lisible sur le WiFi.
    if (token && url.protocol === "http:" && !LOOPBACK.test(url.hostname)) {
      console.warn(`Cible réseau « ${name} » ignorée : un jeton demande https.`);
      continue;
    }
    targets.set(name, { url, method: method as Target["method"], token });
  }
  return targets;
}

const targets = parseTargets(process.env.LAN_TARGETS);

const fail = (statusCode: number, message: string) => Object.assign(new Error(message), { statusCode });

/** Appelle la cible `name` ; rend son code de réponse. Erreur (phrase sans le contenu de la réponse) si elle refuse ou ne répond pas. */
export async function callLanTarget(name: string): Promise<{ name: string; status: number }> {
  const target = targets.get(name);
  if (!target) throw fail(404, "Cible réseau inconnue");
  // shortcut: le nom est résolu ici puis par fetch (une réponse DNS qui change entre les deux passerait), adresse posée par le propriétaire dans le .env ; à revoir si les cibles deviennent modifiables depuis la tablette.
  const host = target.url.hostname.replace(/^\[|\]$/g, "");
  const addresses = isIP(host) ? [host] : (await dns.lookup(host, { all: true }).catch(() => [])).map((a) => a.address);
  if (addresses.length === 0) throw fail(502, `${name} : adresse introuvable`);
  if (!addresses.every(isPrivateAddress)) throw fail(403, `${name} : adresse hors du réseau local refusée`);
  let res: Response;
  try {
    res = await fetch(target.url, {
      method: target.method,
      headers: target.token ? { authorization: `Bearer ${target.token}` } : undefined,
      signal: AbortSignal.timeout(TIMEOUT_MS),
      // Une redirection enverrait la requête (et le jeton) ailleurs que vers la cible déclarée.
      redirect: "manual",
    });
  } catch {
    throw fail(502, `${name} : pas de réponse`);
  }
  await readCapped(res, DRAIN_BYTES).catch(() => undefined);
  if (!res.ok) throw fail(502, `${name} : réponse ${res.status}`);
  return { name, status: res.status };
}
