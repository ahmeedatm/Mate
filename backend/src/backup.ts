import { keysFile, loadLinks, loadSavedApps, pagesFile, type DeckApp, type DeckLink, type KeyPage, type PersoKey } from "./perso.ts";
import { choices } from "./plugins.ts";
import { readJson } from "./store.ts";

/**
 * Fichier de sauvegarde de la configuration (page locale, section Sauvegarde) : pages et touches du Deck, applis et sites,
 * plugins, et disposition de la tablette (profils, widgets, barres). Rien qui ouvre un accès : ni jeton d'appairage, ni
 * comptes, ni clés (identity.json, agents-hook.json, accounts.json, github.json, gitlab.json restent hors du fichier).
 */
export const BACKUP_FORMAT = "mate-config-1";
export const BACKUP_PARTS = ["keys", "pages", "apps", "links", "plugins", "layout"] as const;
export type BackupPart = (typeof BACKUP_PARTS)[number];

type Slots = (PersoKey | null)[];

/** Disposition de la tablette, telle que la page Profils l'envoie (ordre `layout` `set`). */
export type BackupLayout = { layout: Record<string, unknown>; topBar: string[]; rail: { labels: boolean; tools: string[] } };

export type BackupParts = {
  keys?: { slots: Slots };
  pages?: { pages: KeyPage[] };
  apps?: { apps: DeckApp[] };
  links?: { links: DeckLink[] };
  plugins?: { choices: Record<string, boolean> };
  layout?: BackupLayout;
};

export type Backup = { format: typeof BACKUP_FORMAT; exportedAt: string; parts: BackupParts };

/** Dernier état lu de la tablette (tablet-layout.json) ramené à ce que `set` accepte ; `undefined` si elle n'a jamais répondu. */
export function layoutFrom(data: Record<string, any> | undefined): BackupLayout | undefined {
  if (!data?.layout || typeof data.layout !== "object") return undefined;
  const keys = (list: unknown): string[] => (Array.isArray(list) ? list.map((i: { key: string }) => i.key) : []);
  return {
    layout: data.layout,
    topBar: keys(data.topBar?.items),
    rail: { labels: data.rail?.labels !== false, tools: keys(data.rail?.tools) },
  };
}

export async function exportBackup(layout: BackupLayout | undefined): Promise<Backup> {
  const parts: BackupParts = {
    keys: { slots: (await readJson<{ slots?: Slots }>(keysFile, () => ({}))).slots ?? [] },
    pages: { pages: (await readJson<{ pages?: KeyPage[] }>(pagesFile, () => ({}))).pages ?? [] },
    links: { links: await loadLinks() },
    plugins: { choices: await choices() },
  };
  // Pas d'applis enregistrées : le profil de base du Deck s'applique, il n'y a rien à sauvegarder.
  const apps = await loadSavedApps();
  return {
    format: BACKUP_FORMAT,
    exportedAt: new Date().toISOString(),
    parts: { ...parts, ...(apps && { apps: { apps } }), ...(layout && { layout }) },
  };
}
