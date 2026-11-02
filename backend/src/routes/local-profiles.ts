import fs from "node:fs";
import path from "node:path";
import type { FastifyInstance } from "fastify";
import { config } from "../config.ts";
import { loadDeck } from "../deck.ts";
import { sendToTablet, tabletStatus, type TabletLayoutChange } from "../tablet.ts";

/**
 * Profils et barres de la tablette, réglés depuis la page locale (section Profils, local-config-profiles.ts). La tablette
 * reste la source de vérité : elle rend son état (ordre `layout` `get`) et vérifie ce qu'on lui envoie (`set`). Dernier état
 * lu gardé en mémoire et dans `tablet-layout.json` : tablette éteinte, la page l'affiche en lecture seule.
 */

type Cached = { data: Record<string, unknown>; at: number };

const CACHE_FILE = path.join(config.dataDir, "tablet-layout.json");
let cache: Cached | null | undefined;

export function readCache(): Cached | null {
  if (cache !== undefined) return cache;
  try {
    const saved = JSON.parse(fs.readFileSync(CACHE_FILE, "utf8"));
    cache = saved && typeof saved.data === "object" && typeof saved.at === "number" ? (saved as Cached) : null;
  } catch {
    cache = null;
  }
  return cache ?? null;
}

function writeCache(data: Record<string, unknown>) {
  cache = { data, at: Date.now() };
  try {
    fs.mkdirSync(config.dataDir, { recursive: true });
    fs.writeFileSync(CACHE_FILE, JSON.stringify(cache));
  } catch {
    // Le cache en mémoire suffit tant que le compagnon tourne.
  }
}

/** Pages de touches du Deck de cet ordinateur : de quoi choisir les touches des widgets Touches (emplacements vides exclus). */
async function deckPages() {
  try {
    return (await loadDeck()).map((page) => ({
      title: page.title,
      tiles: page.tiles.flatMap((t) =>
        "empty" in t ? [] : [{ id: t.id, label: t.label, glyph: t.glyph ?? null, color: t.color ?? null, symbol: t.symbol ?? null }],
      ),
    }));
  } catch {
    return [];
  }
}

async function reply(data: Record<string, unknown>, at: number, extra: Record<string, unknown> = {}) {
  return { ...data, readOnly: false, at, deck: await deckPages(), ...extra };
}

export default async function (app: FastifyInstance) {
  app.get("/local/tablet/layout", async (_req, res) => {
    const saved = readCache();
    if (!tabletStatus().connected) {
      return saved
        ? { ...saved.data, readOnly: true, at: saved.at, deck: await deckPages() }
        : { readOnly: true, empty: true, at: null, deck: await deckPages() };
    }
    const result = await sendToTablet({ type: "layout", action: "get" });
    if (result.ok && result.data) {
      writeCache(result.data);
      return reply(result.data, cache!.at);
    }
    // Connectée mais sans réponse (appli pas à jour, trop lente) : dernier état connu en lecture seule, avec la raison.
    if (saved) return { ...saved.data, readOnly: true, at: saved.at, deck: await deckPages(), error: result.error };
    return res.code(502).send({ error: result.error });
  });

  app.put<{ Body: TabletLayoutChange }>(
    "/local/tablet/layout",
    {
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          // Au moins une partie (minProperties ne voit pas les champs inconnus retirés par Fastify).
          anyOf: [{ required: ["layout"] }, { required: ["topBar"] }, { required: ["rail"] }],
          properties: {
            layout: {
              type: "object",
              required: ["names", "widgets"],
              properties: {
                names: { type: "array", items: { type: ["string", "null"] } },
                widgets: { type: "array", items: { type: "object" } },
              },
            },
            topBar: { type: "array", items: { type: "string" } },
            rail: {
              type: "object",
              additionalProperties: false,
              properties: { labels: { type: "boolean" }, tools: { type: "array", items: { type: "string" } } },
            },
          },
        },
      },
    },
    async (req, res) => {
      if (!tabletStatus().connected) return res.code(409).send({ error: "Tablette non connectée" });
      const result = await sendToTablet({ type: "layout", action: "set", ...req.body });
      if (!result.ok || !result.data) return res.code(502).send({ error: result.error ?? "La tablette n'a rien rendu" });
      writeCache(result.data);
      return reply(result.data, cache!.at);
    },
  );
}
