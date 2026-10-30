import { SYSTEM_COMMANDS, TILE_SYMBOLS } from "./screens.ts";
import path from "node:path";
import { config } from "./config.ts";
import { readJson, removeJson, updateJson, writeJson } from "./store.ts";
import type { Step } from "./deck.ts";

/**
 * Touches perso : page "Perso" du deck, emplacements vides que l'on remplit depuis la tablette
 * ou depuis Claude (outil MCP deck_touche). Une étape = raccourci simple, plusieurs = macro.
 */
export type PersoKey = { label: string; symbol?: string; glyph?: string; color?: string; steps: Step[] };

/** Emplacements montrés au minimum. */
export const MIN_SLOTS = 14;
/** Plafond : validation de la route et de l'outil MCP (mcp.ts en recopie la valeur). */
export const MAX_SLOTS = 100;
const storeFile = path.join(config.dataDir, "keys.json");

type Slots = (PersoKey | null)[];

/** Longueur : un emplacement libre après le dernier rempli, entre MIN_SLOTS et MAX_SLOTS. */
const fit = (saved: Slots = []): Slots => {
  const length = Math.min(MAX_SLOTS, Math.max(MIN_SLOTS, saved.findLastIndex(Boolean) + 2));
  return Array.from({ length }, (_, i) => saved[i] ?? null);
};

/** Le fichier s'arrête au dernier emplacement rempli : pas de `null` en queue. */
const put = (saved: Slots | undefined, slot: number, key: PersoKey | null): Slots => {
  const slots = Array.from({ length: Math.max(saved?.length ?? 0, slot + 1) }, (_, i) => saved?.[i] ?? null);
  slots[slot] = key;
  slots.length = slots.findLastIndex(Boolean) + 1;
  return slots;
};

export async function loadKeys(): Promise<Slots> {
  return fit((await readJson<{ slots?: Slots }>(storeFile, () => ({}))).slots);
}

export async function saveKey(slot: number, key: PersoKey | null) {
  await updateJson<{ slots?: Slots }>(
    storeFile,
    () => ({}),
    (data) => {
      data.slots = put(data.slots, slot, key);
    },
  );
}

/**
 * Pages de touches nommées (.data/pages.json) : comme Perso, éditables depuis la tablette. Elles s'affichent après
 * Perso, la `n`-ième a les touches `k<n+1>-<emplacement>`. Posées par la démo (src/demo.ts).
 */
export type KeyPage = { title: string; slots: Slots };
export const pagesFile = path.join(config.dataDir, "pages.json");

export async function loadKeyPages(): Promise<{ title: string; keys: Slots }[]> {
  const saved = (await readJson<{ pages?: KeyPage[] }>(pagesFile, () => ({}))).pages ?? [];
  return saved.map((p) => ({ title: p.title, keys: fit(p.slots) }));
}

/** Faux si la page n'existe pas. */
export async function saveKeyPage(page: number, slot: number, key: PersoKey | null): Promise<boolean> {
  return updateJson<{ pages?: KeyPage[] }, boolean>(
    pagesFile,
    () => ({}),
    (data) => {
      const target = data.pages?.[page];
      if (target) target.slots = put(target.slots, slot, key);
      return target !== undefined;
    },
  );
}

// Schéma JSON (validation Fastify) d'une touche : chaque type d'étape avec ses seuls champs.
// if/then plutôt que oneOf : Fastify retire les champs en trop (removeAdditional), et avec oneOf
// il les retire en essayant chaque branche, y compris celles qui ne correspondent pas.
const step = (type: string, required: string[], properties: Record<string, object>) => ({
  if: { properties: { type: { const: type } } },
  then: { additionalProperties: false, required, properties: { type: {}, ...properties } },
});
const name = { type: "string", minLength: 1, maxLength: 200 };

export const stepSchema = {
  type: "object",
  required: ["type"],
  properties: { type: { enum: ["app", "url", "keys", "shortcut", "media", "system", "text", "wait", "window"] } },
  allOf: [
    step("app", ["name"], { name }),
    step("url", ["url"], { url: { type: "string", maxLength: 2000 } }),
    step("keys", ["keys"], { keys: { type: "string", minLength: 1, maxLength: 60 }, app: name }),
    step("shortcut", ["name"], { name }),
    step("media", ["command"], { command: { enum: ["play-pause", "next", "previous"] } }),
    step("system", ["command"], { command: { enum: SYSTEM_COMMANDS } }),
    step("text", ["text"], { text: { type: "string", minLength: 1, maxLength: 4000 } }),
    step("wait", ["ms"], { ms: { type: "integer", minimum: 0, maximum: 10_000 } }),
    step("window", ["app"], { app: name, display: { type: "string", maxLength: 100 }, layout: { enum: ["keep", "left", "right", "max"] } }),
  ],
};

export const keySchema = {
  type: "object",
  additionalProperties: false,
  required: ["label", "steps"],
  properties: {
    label: { type: "string", minLength: 1, maxLength: 40 },
    symbol: { enum: TILE_SYMBOLS },
    glyph: { type: "string", maxLength: 8 },
    color: { type: "string", pattern: "^#[0-9A-Fa-f]{6}$" },
    steps: { type: "array", minItems: 1, maxItems: 20, items: stepSchema },
  },
};

/**
 * Page Apps du deck : profil de base (page `"apps": true` de config/deck.json) tant que rien
 * n'est enregistré ; dès qu'on ajoute ou retire une appli, la liste complète va dans .data/apps.json.
 */
export type DeckApp = { label: string; name: string };
const appsFile = path.join(config.dataDir, "apps.json");

/** `undefined` : profil de base. */
export async function loadSavedApps(): Promise<DeckApp[] | undefined> {
  return (await readJson<{ apps?: DeckApp[] }>(appsFile, () => ({}))).apps;
}

/** `null` : retour au profil de base. */
export async function saveApps(apps: DeckApp[] | null) {
  if (!apps) return removeJson(appsFile);
  await writeJson(appsFile, { apps });
}

/** Page Média : sites ajoutés depuis la tablette (YouTube, Netflix…), vide au départ, dans .data/links.json. */
export type DeckLink = { label: string; url: string };
const linksFile = path.join(config.dataDir, "links.json");

export async function loadLinks(): Promise<DeckLink[]> {
  return (await readJson<{ links?: DeckLink[] }>(linksFile, () => ({}))).links ?? [];
}

export async function saveLinks(links: DeckLink[]) {
  await writeJson(linksFile, { links });
}
