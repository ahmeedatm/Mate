import path from "node:path";
import { randomUUID } from "node:crypto";
import { config } from "./config.ts";
import { readJson, updateJson } from "./store.ts";

/** Liste de courses : des articles à cocher, gardés sur l'ordinateur ; la tablette l'affiche et la remplit (à la voix aussi). */

export type ShoppingItem = { id: string; label: string; done: boolean };
type Store = { items: ShoppingItem[] };

export const MAX_ITEMS = 100;
export const MAX_LABEL = 80;

const storeFile = path.join(config.dataDir, "shopping.json");
const emptyStore = (): Store => ({ items: [] });

/** Deux écritures du même article ("Lait", " lait ") ne font qu'un. */
const sameLabel = (a: string, b: string) => a.localeCompare(b, "fr", { sensitivity: "base" }) === 0;

export async function listShopping(): Promise<ShoppingItem[]> {
  return (await readJson<Partial<Store>>(storeFile, emptyStore)).items ?? [];
}

const edit = <R>(change: (items: ShoppingItem[]) => R) =>
  updateJson<Partial<Store>, R>(storeFile, emptyStore, (store) => change((store.items ??= [])));

/**
 * Ajoute les articles (texte nettoyé, vides ignorés). Un article déjà à acheter n'est pas doublé ; déjà coché, il
 * repasse à acheter. Rend les articles concernés, dans l'ordre demandé. Liste pleine : les derniers sont refusés (`null`).
 */
export function addShopping(labels: string[]): Promise<ShoppingItem[] | null> {
  const clean = labels.map((l) => l.trim().slice(0, MAX_LABEL)).filter(Boolean);
  return edit((items) => {
    const touched: ShoppingItem[] = [];
    for (const label of clean) {
      let item = items.find((i) => sameLabel(i.label, label));
      if (item) item.done = false;
      else {
        if (items.length >= MAX_ITEMS) return null;
        item = { id: randomUUID(), label, done: false };
        items.push(item);
      }
      touched.push(item);
    }
    return touched;
  });
}

export const setShoppingDone = (id: string, done: boolean) =>
  edit((items) => {
    const item = items.find((i) => i.id === id);
    if (item) item.done = done;
    return Boolean(item);
  });

export const deleteShopping = (id: string) =>
  edit((items) => {
    const at = items.findIndex((i) => i.id === id);
    if (at >= 0) items.splice(at, 1);
    return at >= 0;
  });

/** Retire les articles cochés ; rend combien. */
export const clearDoneShopping = () =>
  edit((items) => {
    const before = items.length;
    const keep = items.filter((i) => !i.done);
    items.splice(0, items.length, ...keep);
    return before - keep.length;
  });
