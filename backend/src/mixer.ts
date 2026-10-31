import path from "node:path";
import { randomUUID } from "node:crypto";
import { config } from "./config.ts";
import { mixer } from "./os.ts";
import { readJson, updateJson } from "./store.ts";

/**
 * Scènes de l'onglet Son : volumes de la sortie et des applis enregistrés sous un nom (« Jeu » : jeu 60 %, Discord
 * 100 %, Spotify 20 %), rappelés en un toucher. Le micro n'en fait pas partie : une scène ne doit jamais l'ouvrir.
 * Une scène par ordinateur, dans `.data/mixer.json`.
 */

export type SceneLevel = { volume: number; muted: boolean };
export type Scene = { id: string; name: string; master: SceneLevel | null; apps: Record<string, SceneLevel> };
type Store = { scenes: Scene[] };

export const MAX_SCENES = 8;

const storeFile = path.join(config.dataDir, "mixer.json");
const empty = (): Store => ({ scenes: [] });

export async function listScenes(): Promise<Scene[]> {
  return (await readJson(storeFile, empty)).scenes;
}

/** Enregistre les volumes actuels sous `name` ; une scène du même nom est remplacée. */
export async function saveScene(name: string): Promise<Scene> {
  const now = await mixer();
  const level = (c: { volume: number | null; muted: boolean }): SceneLevel | null =>
    c.volume === null ? null : { volume: c.volume, muted: c.muted };
  const apps: Record<string, SceneLevel> = {};
  for (const app of now.apps ?? []) {
    const l = level(app);
    if (l) apps[app.id] = l;
  }
  const label = name.trim();
  return updateJson(storeFile, empty, (store) => {
    const same = store.scenes.findIndex((s) => s.name.toLowerCase() === label.toLowerCase());
    if (same < 0 && store.scenes.length >= MAX_SCENES) throw new Error(`${MAX_SCENES} scènes au plus : supprime-en une d'abord`);
    const scene: Scene = { id: same >= 0 ? store.scenes[same].id : randomUUID().slice(0, 8), name: label, master: level(now.master), apps };
    if (same >= 0) store.scenes[same] = scene;
    else store.scenes.push(scene);
    return scene;
  });
}

/** Ordre des scènes choisi sur la tablette : `ids` d'abord (inconnus ignorés), les scènes oubliées à la suite. */
export async function orderScenes(ids: string[]): Promise<Scene[]> {
  const rank = (id: string) => (ids.includes(id) ? ids.indexOf(id) : ids.length);
  return updateJson(storeFile, empty, (store) => {
    store.scenes = store.scenes
      .map((scene, i) => ({ scene, i }))
      .sort((a, b) => rank(a.scene.id) - rank(b.scene.id) || a.i - b.i)
      .map((x) => x.scene);
    return store.scenes;
  });
}

export async function deleteScene(id: string) {
  await updateJson(storeFile, empty, (store) => {
    store.scenes = store.scenes.filter((s) => s.id !== id);
  });
}

/**
 * Remet les volumes de la scène : la sortie, puis chaque appli qui a du son en ce moment (une appli fermée sera à
 * son volume habituel à son retour, Windows le garde). `missing` : applis de la scène absentes du mélangeur.
 */
export async function applyScene(id: string) {
  const scene = (await listScenes()).find((s) => s.id === id);
  if (!scene) throw Object.assign(new Error("Scène introuvable"), { statusCode: 404 });
  let now = await mixer();
  const differs = (c: { volume: number | null; muted: boolean }, l: SceneLevel) => c.volume !== l.volume || c.muted !== l.muted;
  if (scene.master && differs(now.master, scene.master)) now = await mixer({ id: "master", ...scene.master });
  const missing: string[] = [];
  for (const [app, level] of Object.entries(scene.apps)) {
    const channel = now.apps?.find((c) => c.id === app);
    if (!channel) missing.push(app);
    else if (differs(channel, level)) now = await mixer({ id: app, ...level });
  }
  return { mixer: now, missing };
}
