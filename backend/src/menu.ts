import path from "node:path";
import { config } from "./config.ts";
import { readJson, writeJson } from "./store.ts";

/** Menu de la semaine : un plat à midi et un le soir pour chaque jour, réglé sur la page locale (menu.json), montré par la tablette. */

export type MenuDay = { lunch: string; dinner: string };

export const WEEK_DAYS = 7;
export const MAX_DISH = 80;

const storeFile = path.join(config.dataDir, "menu.json");

const dish = (value: unknown) => (typeof value === "string" ? value.trim().slice(0, MAX_DISH) : "");

/** Toujours sept jours, du lundi au dimanche : ce qui manque ou n'est pas du texte devient un repas vide. */
const week = (days: unknown): MenuDay[] =>
  Array.from({ length: WEEK_DAYS }, (_, i) => {
    const day = Array.isArray(days) ? days[i] : undefined;
    return { lunch: dish(day?.lunch), dinner: dish(day?.dinner) };
  });

export const loadMenu = async () => week((await readJson<{ days?: unknown }>(storeFile, () => ({}))).days);

/** Remplace tout le menu (texte nettoyé, coupé à MAX_DISH) et rend ce qui est gardé. */
export async function saveMenu(days: MenuDay[]) {
  const clean = week(days);
  await writeJson(storeFile, { days: clean });
  return clean;
}
