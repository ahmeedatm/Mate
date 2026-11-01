import { SYSTEM_COMMANDS, TILE_SYMBOLS } from "./screens.ts";
import { existsSync } from "node:fs";
import path from "node:path";
import { config } from "./config.ts";
import { readJson, removeJson, updateJson, writeJson } from "./store.ts";
import type { Step } from "./deck.ts";

/**
 * Touches perso : page "Perso" du deck, emplacements vides que l'on remplit depuis la tablette
 * ou depuis Claude (outil MCP deck_touche). Une étape = raccourci simple, plusieurs = macro.
 */
/** `cycle` : chaque appui lance l'étape suivante (puis recommence) au lieu de toutes les étapes. `span` : 2 pour une touche large (deux cases de large dans la grille de la tablette). `hold` : seconde action, lancée par l'appui long (une étape = raccourci simple, plusieurs = macro). */
export type PersoKey = {
  label: string;
  symbol?: string;
  glyph?: string;
  color?: string;
  cycle?: boolean;
  span?: 1 | 2;
  steps: Step[];
  hold?: Step[];
};

/** Emplacements montrés au minimum. */
export const MIN_SLOTS = 14;
/** Plafond : validation de la route et de l'outil MCP (mcp.ts en recopie la valeur). */
export const MAX_SLOTS = 100;
export const keysFile = path.join(config.dataDir, "keys.json");

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
  return fit((await readJson<{ slots?: Slots }>(keysFile, () => ({}))).slots);
}

/** Une étape `push` (touches tenues pendant l'appui) est la seule de sa touche, sans cycle ni seconde action. */
export function assertPushAlone(key: PersoKey | null) {
  if (!key) return;
  const pushes = (steps: Step[] = []) => steps.some((s) => s.type === "keys" && s.push);
  if (!pushes(key.steps) && !pushes(key.hold)) return;
  if (key.steps.length !== 1 || key.cycle || key.hold || !pushes(key.steps)) {
    throw Object.assign(new Error("Une touche à maintenir est une étape seule, sans cycle ni seconde action"), { statusCode: 400 });
  }
}

export async function saveKey(slot: number, key: PersoKey | null) {
  assertPushAlone(key);
  await updateJson<{ slots?: Slots }>(
    keysFile,
    () => ({}),
    (data) => {
      data.slots = put(data.slots, slot, key);
    },
  );
}

/**
 * Pages de touches nommées (.data/pages.json) : comme Perso, éditables depuis la tablette. Elles s'affichent après
 * Perso, la `n`-ième a les touches `k<n+1>-<emplacement>`.
 */
export type KeyPage = { title: string; slots: Slots };
export const pagesFile = path.join(config.dataDir, "pages.json");

export async function loadKeyPages(): Promise<{ title: string; keys: Slots }[]> {
  const saved = (await readJson<{ pages?: KeyPage[] }>(pagesFile, () => ({}))).pages ?? [];
  return saved.map((p) => ({ title: p.title, keys: fit(p.slots) }));
}

/** Faux si la page n'existe pas. */
export async function saveKeyPage(page: number, slot: number, key: PersoKey | null): Promise<boolean> {
  assertPushAlone(key);
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

/** Plafond de pages nommées : validation des routes (`pageSlotParams`, routes/deck.ts). */
export const MAX_PAGES = 50;
const refuse = (statusCode: number, message: string) => Object.assign(new Error(message), { statusCode });

/** Où est (ou va) une touche : `page` absent pour Perso. */
export type KeyPlace = { page?: number; slot: number };

/**
 * Copie la touche `from` dans le premier emplacement libre de `to` (rang d'une page nommée, absent : Perso). La copie est
 * indépendante de l'original. 404 : page inconnue ou touche vide ; 409 : plus d'emplacement libre.
 */
export async function duplicateKey(from: KeyPlace, to?: number): Promise<KeyPlace> {
  const pages = await loadKeyPages();
  const source = (from.page === undefined ? await loadKeys() : pages[from.page]?.keys)?.[from.slot];
  if (!source) throw refuse(404, "Touche vide ou page inconnue");
  const copy = structuredClone(source);
  // Choix de la place et écriture dans le même `updateJson` : deux copies à la fois ne visent pas la même place.
  const place = (saved: Slots | undefined) => {
    const slot = fit(saved).indexOf(null);
    if (slot < 0) throw refuse(409, "Plus d'emplacement libre sur cette page");
    return { slots: put(saved, slot, copy), slot };
  };
  if (to === undefined) {
    const slot = await updateJson<{ slots?: Slots }, number>(
      keysFile,
      () => ({}),
      (data) => {
        const placed = place(data.slots);
        data.slots = placed.slots;
        return placed.slot;
      },
    );
    return { slot };
  }
  const slot = await updateJson<{ pages?: KeyPage[] }, number>(
    pagesFile,
    () => ({}),
    (data) => {
      const target = data.pages?.[to];
      if (!target) throw refuse(404, "Page inconnue");
      const placed = place(target.slots);
      target.slots = placed.slots;
      return placed.slot;
    },
  );
  return { page: to, slot };
}

/** Ajoute à la fin une copie de la page `page`, titrée « … (copie) », et rend son rang. 404 : page inconnue ; 409 : trop de pages. */
export async function duplicateKeyPage(page: number): Promise<number> {
  return updateJson<{ pages?: KeyPage[] }, number>(
    pagesFile,
    () => ({}),
    (data) => {
      const source = data.pages?.[page];
      if (!source) throw refuse(404, "Page inconnue");
      if (data.pages!.length >= MAX_PAGES) throw refuse(409, "Trop de pages");
      return data.pages!.push({ title: `${source.title} (copie)`, slots: structuredClone(source.slots) }) - 1;
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
    step("keys", ["keys"], { keys: { type: "string", minLength: 1, maxLength: 60 }, app: name, push: { type: "boolean" } }),
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
    cycle: { type: "boolean" },
    span: { enum: [1, 2] },
    steps: { type: "array", minItems: 1, maxItems: 20, items: stepSchema },
    hold: { type: "array", minItems: 1, maxItems: 20, items: stepSchema },
  },
};

/**
 * Page Apps du deck : profil de base (page `"apps": true` de config/deck.json) tant que rien
 * n'est enregistré ; dès qu'on ajoute ou retire une appli, la liste complète va dans .data/apps.json.
 */
export type DeckApp = { label: string; name: string };
export const appsFile = path.join(config.dataDir, "apps.json");

/** `undefined` : profil de base. */
export async function loadSavedApps(): Promise<DeckApp[] | undefined> {
  return (await readJson<{ apps?: DeckApp[] }>(appsFile, () => ({}))).apps;
}

/** `null` : retour au profil de base. */
export async function saveApps(apps: DeckApp[] | null) {
  if (!apps) return removeJson(appsFile);
  await writeJson(appsFile, { apps });
}

/**
 * Retire une fois de la liste enregistrée les applis dont `retired(name)` est vrai (Office retiré du deck, #18),
 * puis note la migration faite : ce que l'utilisateur rajoute ensuite reste.
 */
let retiring: Promise<void> | undefined;
export function retireSavedApps(retired: (name: string) => boolean) {
  // Appels simultanés (/deck, accueil) : une seule migration en vol, sinon le second écraserait un ajout fait entre-temps.
  retiring ??= retireOnce(retired).finally(() => (retiring = undefined));
  return retiring;
}

async function retireOnce(retired: (name: string) => boolean) {
  const marker = path.join(config.dataDir, "apps-office-retire.json");
  if (existsSync(marker)) return;
  const saved = await loadSavedApps();
  if (saved?.some((a) => retired(a.name))) await saveApps(saved.filter((a) => !retired(a.name)));
  await writeJson(marker, { done: true });
}

/** Page Média : sites ajoutés depuis la tablette (YouTube, Netflix…), vide au départ, dans .data/links.json. */
export type DeckLink = { label: string; url: string };
export const linksFile = path.join(config.dataDir, "links.json");

export async function loadLinks(): Promise<DeckLink[]> {
  return (await readJson<{ links?: DeckLink[] }>(linksFile, () => ({}))).links ?? [];
}

export async function saveLinks(links: DeckLink[]) {
  await writeJson(linksFile, { links });
}
