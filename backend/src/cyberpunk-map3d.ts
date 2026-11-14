import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { logFailure } from "./log.ts";
import { backendRoot, dataDir } from "./paths.ts";

/**
 * Données de la carte 3D de la page Cyberpunk de la tablette (moteur `op77_map3d` d'Open2077, embarqué dans l'appli) :
 * les quatre fichiers de `standalone/op77_map3d/web/data` (commit et SHA-256 épinglés dans `assets/cyberpunk-map3d.json`,
 * dérivés du jeu, jamais dans le dépôt) sont téléchargés une fois, les bâtiments allégés pour la tablette, puis servis
 * à la tablette qui les garde. Design : docs/specs/2026-11-15-cyberpunk-carte-3d-design.md.
 */

export const MAP3D_FILES = ["manifest.json", "world.glb", "roads.bin", "buildings.bin"] as const;
export type Map3dFile = (typeof MAP3D_FILES)[number];
/** Part des bâtiments gardée, les plus volumineux de chaque quartier : 40 images/s mesurées sur la Lenovo M10 (8 avec tous). */
export const KEEP = 0.1;
const RECORD = 16;

export type SourceFile = { file: string; sha256: string; size: number };
export type Map3dOptions = { dir: string; baseUrl: string; files: SourceFile[] };

/** Erreur dont le message peut s'afficher tel quel. */
class MapError extends Error {}

type District = { name: string; offset: number; count: number };
type Manifest = { format?: unknown; buildings?: { stride?: unknown; count?: number; districts?: District[] }; [key: string]: unknown };

/**
 * Garde, dans chaque quartier, la part `keep` des boîtes les plus volumineuses (au moins une), dans leur ordre d'origine,
 * et récrit `offset`/`count` du manifeste. Une boîte : u16 position × 3, i8 quaternion × 4, u16 taille × 3.
 */
export function lightenBuildings(manifest: unknown, bin: Buffer, keep: number) {
  const m = manifest as Manifest;
  const list = m?.buildings?.districts;
  if (m?.format !== "op77_map3d/1" || m.buildings?.stride !== RECORD || !Array.isArray(list))
    throw new Error("manifeste de la carte 3D inattendu");
  const total = list.reduce((s, d) => Math.max(s, d.offset + d.count), 0);
  if (!list.every((d) => Number.isInteger(d.offset) && Number.isInteger(d.count)) || bin.length !== total * RECORD)
    throw new Error("bâtiments de la carte 3D tronqués");
  const parts: Buffer[] = [];
  let offset = 0;
  const districts = list.map((d) => {
    const volume = (i: number) => {
      const o = (d.offset + i) * RECORD + 10;
      return bin.readUInt16LE(o) * bin.readUInt16LE(o + 2) * bin.readUInt16LE(o + 4);
    };
    const n = Math.max(1, Math.round(d.count * keep));
    const kept = [...Array(d.count).keys()]
      .sort((a, b) => volume(b) - volume(a))
      .slice(0, n)
      .sort((a, b) => a - b);
    for (const i of kept) parts.push(bin.subarray((d.offset + i) * RECORD, (d.offset + i + 1) * RECORD));
    const out = { ...d, offset, count: kept.length };
    offset += kept.length;
    return out;
  });
  return { manifest: { ...m, buildings: { ...m.buildings, count: offset, districts } }, bin: Buffer.concat(parts) };
}

const sha256 = (b: Buffer) => createHash("sha256").update(b).digest("hex");
const isFile = (name: string): name is Map3dFile => (MAP3D_FILES as readonly string[]).includes(name);

export function createMap3d(opts: Map3dOptions) {
  const file = (name: Map3dFile) => path.join(opts.dir, name);

  async function download(f: SourceFile): Promise<Buffer> {
    const res = await fetch(`${opts.baseUrl}/${f.file}`, { signal: AbortSignal.timeout(180_000) });
    if (!res.ok) throw new MapError(`Téléchargement de la carte impossible (${f.file}, ${res.status})`);
    // Un corps compressé (le JSON l'est sur raw.githubusercontent.com) annonce sa taille compressée : seule la taille lue compte alors.
    const announced = res.headers.get("content-encoding") ? null : res.headers.get("content-length");
    if (announced !== null && Number(announced) !== f.size)
      throw new MapError(`Données de carte refusées : ${f.file} n'a pas la taille attendue`);
    const body = Buffer.from(await res.arrayBuffer());
    if (body.length !== f.size) throw new MapError(`Données de carte refusées : ${f.file} n'a pas la taille attendue`);
    if (sha256(body) !== f.sha256) throw new MapError(`Données de carte refusées : ${f.file} ne correspond pas à l'empreinte attendue`);
    return body;
  }

  async function present() {
    const out: Record<string, { size: number; sha256: string }> = {};
    for (const name of MAP3D_FILES) {
      const body = await fs.readFile(file(name)).catch(() => null);
      if (!body) return null;
      out[name] = { size: body.length, sha256: sha256(body) };
    }
    return out;
  }

  /** Tout est vérifié avant d'écrire : les fichiers n'arrivent sur le disque qu'ensemble, le manifeste en dernier. */
  async function run() {
    if (await present()) return;
    const bodies = new Map<string, Buffer>();
    for (const f of opts.files) bodies.set(f.file, await download(f));
    let light: ReturnType<typeof lightenBuildings>;
    try {
      light = lightenBuildings(JSON.parse(bodies.get("manifest.json")!.toString("utf8")), bodies.get("buildings.bin")!, KEEP);
    } catch {
      throw new MapError("Données de carte illisibles");
    }
    await fs.mkdir(opts.dir, { recursive: true });
    const write = async (name: Map3dFile, body: Buffer) => {
      await fs.writeFile(`${file(name)}.part`, body);
      await fs.rename(`${file(name)}.part`, file(name));
    };
    await fs.rm(file("manifest.json"), { force: true });
    await write("world.glb", bodies.get("world.glb")!);
    await write("roads.bin", bodies.get("roads.bin")!);
    await write("buildings.bin", light.bin);
    await write("manifest.json", Buffer.from(JSON.stringify(light.manifest)));
  }

  let running: Promise<void> | null = null;
  let error: string | undefined;
  let cache: Record<string, { size: number; sha256: string }> | null = null;

  return {
    async status() {
      if (!running && !cache) cache = await present();
      const status = running ? "loading" : cache ? "ready" : error ? "error" : "absent";
      return { status, files: (status === "ready" && cache) || {}, ...(error && status === "error" ? { error } : {}) };
    },
    /** Lance la préparation en tâche de fond ; sans effet si elle tourne déjà ou si tout est là. */
    async prepare() {
      if (running) return;
      error = undefined;
      cache = null;
      running = run()
        .catch((e) => {
          error = e instanceof MapError ? e.message : "La préparation de la carte a échoué (détail dans le journal de l'ordinateur)";
          if (!(e instanceof MapError)) logFailure("préparation de la carte 3D Cyberpunk", e);
        })
        .finally(() => (running = null));
    },
    /** Un des quatre fichiers, ou null (nom hors liste, pas encore préparé). */
    async file(name: string): Promise<Buffer | null> {
      if (!isFile(name)) return null;
      return fs.readFile(file(name)).catch(() => null);
    },
  };
}

type Pinned = { commit: string; repo: string; dir: string; files: SourceFile[] };
let shared: ReturnType<typeof createMap3d> | undefined;

/** Les données de cet ordinateur (sources épinglées dans `assets/cyberpunk-map3d.json`). */
export async function cyberpunkMap3d() {
  if (!shared) {
    const m = JSON.parse(await fs.readFile(path.join(backendRoot, "assets", "cyberpunk-map3d.json"), "utf8")) as Pinned;
    shared = createMap3d({
      dir: path.join(dataDir, "cyberpunk-map3d"),
      baseUrl: `https://raw.githubusercontent.com/${m.repo}/${m.commit}/${m.dir}`,
      files: m.files,
    });
  }
  return shared;
}
