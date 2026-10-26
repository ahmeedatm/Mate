import dns from "node:dns/promises";
import { BlockList, isIP } from "node:net";

/**
 * Téléchargements d'adresses saisies sur la tablette (icônes de site) : jamais vers le réseau local ou la machine
 * elle-même (intranet, services locaux), et jamais plus de `maxBytes` lus.
 */

const PRIVATE = new BlockList();
for (const [net, bits] of [
  ["0.0.0.0", 8],
  ["10.0.0.0", 8],
  ["100.64.0.0", 10],
  ["127.0.0.0", 8],
  ["169.254.0.0", 16],
  ["172.16.0.0", 12],
  ["192.168.0.0", 16],
  ["224.0.0.0", 3],
] as const)
  PRIVATE.addSubnet(net, bits, "ipv4");
for (const [net, bits] of [
  ["::", 128],
  ["::1", 128],
  ["fc00::", 7],
  ["fe80::", 10],
  ["ff00::", 8],
] as const)
  PRIVATE.addSubnet(net, bits, "ipv6");

/** Adresse IP du réseau local, de la machine, ou réservée ; les IPv6 « mappées » (::ffff:a.b.c.d) suivent leur IPv4. */
export function isPrivateAddress(ip: string): boolean {
  const family = isIP(ip);
  return family === 0 || PRIVATE.check(ip, family === 4 ? "ipv4" : "ipv6");
}

/** Erreur si le nom ou l'adresse mène au réseau local. */
export async function assertPublicUrl(url: URL) {
  const host = url.hostname.replace(/^\[|\]$/g, "");
  const addresses = isIP(host) ? [host] : (await dns.lookup(host, { all: true })).map((a) => a.address);
  if (addresses.length === 0 || addresses.some(isPrivateAddress)) throw new Error("Adresse du réseau local refusée");
}

/** Lit au plus `maxBytes` du corps ; `truncated` : il y en avait davantage (le reste n'est pas téléchargé). */
export async function readCapped(res: Response, maxBytes: number): Promise<{ data: Buffer; truncated: boolean }> {
  const chunks: Buffer[] = [];
  let size = 0;
  if (res.body) {
    const reader = res.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(Buffer.from(value));
      size += value.length;
      if (size > maxBytes) {
        await reader.cancel();
        break;
      }
    }
  }
  const all = Buffer.concat(chunks);
  return { data: all.subarray(0, maxBytes), truncated: all.length > maxBytes };
}

const MAX_REDIRECTS = 5;

/**
 * GET http(s) vers une adresse publique seulement, y compris à chaque redirection. Erreur si l'adresse est privée.
 * `url` : adresse finale (pour résoudre les liens relatifs de la page).
 */
export async function fetchPublic(
  start: string | URL,
  { maxBytes, headers, timeoutMs = 8000 }: { maxBytes: number; headers?: Record<string, string>; timeoutMs?: number },
): Promise<{ url: string; ok: boolean; data: Buffer; truncated: boolean }> {
  const signal = AbortSignal.timeout(timeoutMs);
  let url = new URL(start);
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    if (url.protocol !== "http:" && url.protocol !== "https:") throw new Error("Adresse web attendue (https://…)");
    await assertPublicUrl(url);
    const res = await fetch(url, { headers, signal, redirect: "manual" });
    const location = res.headers.get("location");
    if (res.status >= 300 && res.status < 400 && location) {
      await res.body?.cancel();
      url = new URL(location, url);
      continue;
    }
    return { url: url.href, ok: res.ok, ...(await readCapped(res, maxBytes)) };
  }
  throw new Error("Trop de redirections");
}
