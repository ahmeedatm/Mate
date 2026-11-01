import dns from "node:dns";
import http from "node:http";
import https from "node:https";
import { BlockList, isIP } from "node:net";
import { config } from "./config.ts";

/**
 * Étape `http` d'une touche du Deck : appelle une cible du réseau local (prise, lampe, NAS). Les cibles se déclarent
 * sur l'ordinateur (`LAN_TARGETS` du .env) ; la tablette n'envoie que le nom d'une cible, jamais une adresse, et le
 * jeton reste ici. À l'inverse de net-guard.ts (icônes : jamais le réseau local), seul le réseau local est permis, et
 * l'adresse vérifiée est celle de la connexion (résolution faite une seule fois, par `lookup`).
 */

export type Target = { url: URL; method: "GET" | "POST" | "PUT"; token?: string };

const METHODS = ["GET", "POST", "PUT"];
const TIMEOUT_MS = 8000;
/** La réponse n'est pas montrée : on en lit juste de quoi vider la connexion. */
const DRAIN_BYTES = 64 * 1024;
const LOOPBACK = /^(127\.0\.0\.1|localhost|\[::1\])$/i;
/** Cet ordinateur : 127/8, localhost, ::1 (le reste de l'URL, sans crochets pour IPv6). */
const OWN_HOST = /^(127\.\d+\.\d+\.\d+|localhost|\[::1\])$/i;

/** Exactement les préfixes annoncés par .env.example. */
const ALLOWED = new BlockList();
for (const [net, bits] of [
  ["10.0.0.0", 8],
  ["172.16.0.0", 12],
  ["192.168.0.0", 16],
  ["127.0.0.0", 8],
] as const)
  ALLOWED.addSubnet(net, bits, "ipv4");
for (const [net, bits] of [
  ["fc00::", 7],
  ["fe80::", 10],
  ["::1", 128],
] as const)
  ALLOWED.addSubnet(net, bits, "ipv6");

/** Adresse du réseau local ou de cet ordinateur ? Une IPv4 mappée en IPv6 est jugée comme IPv4. */
export function isLanAddress(address: string): boolean {
  const ip = address.replace(/%.*$/, "");
  const kind = isIP(ip);
  if (kind === 0) return false;
  const mapped = kind === 6 ? /^::ffff:(?:(\d+\.\d+\.\d+\.\d+)|([0-9a-f]{1,4}):([0-9a-f]{1,4}))$/i.exec(ip) : null;
  if (mapped) {
    const v4 = mapped[1] ?? [mapped[2], mapped[3]].flatMap((h) => [parseInt(h, 16) >> 8, parseInt(h, 16) & 255]).join(".");
    return ALLOWED.check(v4, "ipv4");
  }
  return ALLOWED.check(ip, kind === 4 ? "ipv4" : "ipv6");
}

type Resolver = typeof dns.lookup;

/** `lookup` de http.request : résout puis refuse toute adresse hors liste ; la connexion part vers ce qui a été vérifié. */
export function guardedLookup(resolve: Resolver = dns.lookup): NonNullable<http.RequestOptions["lookup"]> {
  return (hostname, options, callback) => {
    resolve(hostname, { ...options, all: true }, (err: NodeJS.ErrnoException | null, found: dns.LookupAddress[] = []) => {
      if (err) return callback(err, "", 4);
      if (found.length === 0 || !found.every((a) => isLanAddress(a.address)))
        return callback(Object.assign(new Error("adresse hors du réseau local"), { code: "ELANBLOCKED" }), "", 4);
      if (options.all) return (callback as unknown as (e: null, a: dns.LookupAddress[]) => void)(null, found);
      callback(null, found[0].address, found[0].family);
    });
  };
}

/** `ownPorts` : ports du compagnon, qu'une cible ne doit pas viser sur cet ordinateur (il s'appellerait lui-même, jeton compris). */
export function parseTargets(raw: string | undefined, ownPorts: number[]): Map<string, Target> {
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
    if (OWN_HOST.test(url.hostname) && ownPorts.includes(Number(url.port || (url.protocol === "https:" ? 443 : 80)))) {
      console.warn(`Cible réseau « ${name} » ignorée : elle vise le compagnon lui-même.`);
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

const targets = parseTargets(process.env.LAN_TARGETS, [config.port, config.tlsPort]);
// shortcut: seul le port est contrôlé sur une adresse écrite ; un nom qui se résout vers cet ordinateur sur le port du compagnon passerait (le .env est posé par le propriétaire), à revoir si les cibles deviennent modifiables depuis la tablette.

const fail = (statusCode: number, message: string) => Object.assign(new Error(message), { statusCode });

/** Un seul appel à la fois par cible : un second appui rejoint l'appel en cours au lieu de renvoyer. */
const inFlight = new Map<string, Promise<{ name: string; status: number }>>();

/** Appelle la cible `name` ; rend son code de réponse. Erreur (phrase sans le contenu de la réponse) si elle refuse ou ne répond pas. */
export function callLanTarget(name: string): Promise<{ name: string; status: number }> {
  const target = targets.get(name);
  if (!target) return Promise.reject(fail(404, "Cible réseau inconnue"));
  let call = inFlight.get(name);
  if (!call) {
    call = callTarget(name, target).finally(() => inFlight.delete(name));
    inFlight.set(name, call);
  }
  return call;
}

/** La requête elle-même ; `lookup` en paramètre pour les tests. */
export function callTarget(name: string, target: Target, lookup = guardedLookup()): Promise<{ name: string; status: number }> {
  const { url } = target;
  // Une adresse écrite telle quelle ne passe pas par `lookup` : vérifiée ici.
  const literal = url.hostname.replace(/^\[|\]$/g, "");
  if (isIP(literal) && !isLanAddress(literal)) return Promise.reject(fail(403, `${name} : adresse hors du réseau local refusée`));
  return new Promise((resolve, reject) => {
    const req = (url.protocol === "https:" ? https : http).request(
      url,
      {
        method: target.method,
        headers: target.token ? { authorization: `Bearer ${target.token}` } : undefined,
        lookup,
        // Pas de connexion gardée : chaque appel résout et vérifie à nouveau.
        agent: false,
        signal: AbortSignal.timeout(TIMEOUT_MS),
      },
      (res) => {
        // La réponse n'est pas montrée : on en lit juste de quoi vider la connexion. Une redirection est un échec
        // (elle enverrait la requête, et le jeton, ailleurs que vers la cible déclarée).
        let size = 0;
        const done = () => {
          const status = res.statusCode ?? 0;
          if (status >= 200 && status < 300) resolve({ name, status });
          else reject(fail(502, `${name} : réponse ${status}`));
        };
        res.on("data", (chunk: Buffer) => {
          size += chunk.length;
          if (size > DRAIN_BYTES) res.destroy();
        });
        res.once("end", done);
        res.once("close", done);
        res.once("error", () => undefined);
      },
    );
    req.once("error", (e: NodeJS.ErrnoException) => {
      if (e.code === "ELANBLOCKED") reject(fail(403, `${name} : adresse hors du réseau local refusée`));
      else if (e.code === "ENOTFOUND") reject(fail(502, `${name} : adresse introuvable`));
      else reject(fail(502, `${name} : pas de réponse`));
    });
    req.end();
  });
}
