import { execFile, execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { osName } from "./os.ts";
import { logFailure } from "./log.ts";
import { dataDir, backendRoot } from "./paths.ts";

/**
 * Carte de Night City pour la page Cyberpunk de la tablette : la carte de fans (14 × 14 images webp de 7936 px, dépôt
 * More-TALK/Cyberpunk-2077-__Map, commit épinglé, SHA-1 de blob git de chaque image dans `assets/cyberpunk-map.json`)
 * est téléchargée une fois, puis découpée en pyramide de tuiles de 512 px : `<dataDir>/cyberpunk-map/<z>/<x>/<y>.webp`.
 * Niveaux z0..z3 = 256, 512, 1024, 2048 px par image source. Conversion par `dwebp` et `cwebp` de libwebp (BSD).
 */

export const TILE = 512;
export const LEVELS = 4;
const IMAGE_PX = [256, 512, 1024, 2048];
const GRID = 14;
const SOURCE_PX = 7936;
/** Côté de la carte au niveau max, en pixels. */
export const SIDE = GRID * IMAGE_PX[LEVELS - 1];
/** Calage mesuré sur la carte pleine résolution : pixel = (61008 + 10.788·x, 46934 − 10.788·y), x est, y nord, en mètres. */
const K = IMAGE_PX[LEVELS - 1] / SOURCE_PX;
const SCALE = 10.788 * K;
/** px = a·x + b·y + c, py = d·x + e·y + f au niveau max. */
export const AFFINE = [SCALE, 0, 61008 * K, 0, -SCALE, 46934 * K];
/** Tuiles par côté au niveau z : 7, 14, 28, 56. */
const tilesPerSide = (z: number) => (GRID * IMAGE_PX[z]) / TILE;

export type Converter = {
  /** Décode une image webp en PPM, réduite à `size` × `size` px. */
  decode(webp: string, size: number, ppm: string): Promise<void>;
  /** Encode un PPM en webp. */
  encode(ppm: string, webp: string): Promise<void>;
};
export type SourceFile = { file: string; sha1: string; size: number };
export type MapOptions = { dir: string; baseUrl: string; files: SourceFile[]; converter: () => Promise<Converter> };

/** Erreur dont le message peut s'afficher tel quel. */
class MapError extends Error {}

type Img = { w: number; h: number; data: Buffer };

/** PPM binaire (P6, 255) : en-tête en texte, puis RVB. */
function parsePpm(buf: Buffer): Img {
  const tokens: number[] = [];
  let i = 2;
  if (buf.toString("ascii", 0, 2) !== "P6") throw new Error("PPM illisible");
  while (tokens.length < 3) {
    while (/\s/.test(String.fromCharCode(buf[i]))) i++;
    let end = i;
    while (!/\s/.test(String.fromCharCode(buf[end]))) end++;
    tokens.push(Number(buf.toString("ascii", i, end)));
    i = end;
  }
  const [w, h] = tokens;
  return { w, h, data: buf.subarray(i + 1, i + 1 + w * h * 3) };
}

const writePpm = (file: string, w: number, h: number, data: Buffer) =>
  fs.writeFile(file, Buffer.concat([Buffer.from(`P6\n${w} ${h}\n255\n`), data]));

/** Moitié de la taille : moyenne de chaque bloc de 2 × 2 pixels. */
function halve(img: Img): Img {
  const w = img.w / 2;
  const h = img.h / 2;
  const out = Buffer.alloc(w * h * 3);
  const row = img.w * 3;
  const src = img.data;
  let o = 0;
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const i = 2 * y * row + 6 * x;
      for (let c = 0; c < 3; c++) out[o++] = (src[i + c] + src[i + 3 + c] + src[i + row + c] + src[i + row + 3 + c] + 2) >> 2;
    }
  return { w, h, data: out };
}

function crop(img: Img, x0: number, y0: number): Buffer {
  const out = Buffer.alloc(TILE * TILE * 3);
  for (let y = 0; y < TILE; y++) img.data.copy(out, y * TILE * 3, ((y0 + y) * img.w + x0) * 3, ((y0 + y) * img.w + x0 + TILE) * 3);
  return out;
}

type Entry = SourceFile & { col: number; row: number };

function parseEntry(f: SourceFile): Entry {
  const m = /^map_(\d+)_(\d+)\.webp$/.exec(f.file);
  const [col, row] = m ? [(Number(m[1]) - 14) / 31, (Number(m[2]) - 14) / 31] : [-1, -1];
  if (!Number.isInteger(col) || !Number.isInteger(row) || col < 0 || row < 0 || col >= GRID || row >= GRID)
    throw new Error(`image inattendue : ${f.file}`);
  return { ...f, col, row };
}

const range = (n: number) => [...Array(n).keys()];

export function createMap(opts: MapOptions) {
  const entries = opts.files.map(parseEntry);
  const tilePath = (z: number, x: number, y: number) => path.join(opts.dir, String(z), String(x), `${y}.webp`);
  const exists = (file: string) =>
    fs.access(file).then(
      () => true,
      () => false,
    );

  /** Tuiles de z1 à z3 que couvre une image. */
  const imageTiles = (e: Entry) =>
    [1, 2, 3].flatMap((z) => {
      const n = 2 ** (z - 1);
      return range(n).flatMap((i) => range(n).map((j) => tilePath(z, e.col * n + i, e.row * n + j)));
    });
  // z0 : une tuile couvre 2 × 2 images.
  const groups = new Map<string, Entry[]>();
  for (const e of entries) groups.set(`${e.col >> 1},${e.row >> 1}`, [...(groups.get(`${e.col >> 1},${e.row >> 1}`) ?? []), e]);
  const groupTiles = (g: Entry[]) => [tilePath(0, g[0].col >> 1, g[0].row >> 1), ...g.flatMap(imageTiles)];

  async function doneCount() {
    let done = 0;
    for (const g of groups.values()) if ((await Promise.all(groupTiles(g).map(exists))).every(Boolean)) done += g.length;
    return done;
  }

  let tmpCount = 0;

  // Windows garde parfois un fichier tout juste écrit ou lu par un exe (antivirus, poignée pas encore rendue) :
  // EBUSY/EPERM pendant quelques millisecondes. fs.rm réessaie seul avec maxRetries ; le renommage, à la main.
  const removeTmp = (file: string) => fs.rm(file, { force: true, maxRetries: 10, retryDelay: 100 });
  async function renameRetry(from: string, to: string) {
    for (let i = 0; ; i++) {
      try {
        return await fs.rename(from, to);
      } catch (e) {
        const code = (e as NodeJS.ErrnoException).code;
        if (i >= 9 || (code !== "EBUSY" && code !== "EPERM")) throw e;
        await new Promise((r) => setTimeout(r, 100));
      }
    }
  }

  async function encodeTile(conv: Converter, data: Buffer, file: string) {
    const ppm = path.join(opts.dir, "tmp", `tile-${tmpCount++}.ppm`);
    await writePpm(ppm, TILE, TILE, data);
    await fs.mkdir(path.dirname(file), { recursive: true });
    await conv.encode(ppm, `${file}.part`);
    await renameRetry(`${file}.part`, file);
    await removeTmp(ppm);
  }

  async function download(e: Entry, dest: string) {
    const res = await fetch(`${opts.baseUrl}/${e.file}`, { signal: AbortSignal.timeout(180_000) });
    if (!res.ok) throw new MapError(`Téléchargement de la carte impossible (${e.file}, ${res.status})`);
    const body = Buffer.from(await res.arrayBuffer());
    // SHA-1 de blob git : sha1("blob <taille>\0" + contenu).
    if (createHash("sha1").update(`blob ${body.length}\0`).update(body).digest("hex") !== e.sha1)
      throw new MapError(`Image de carte refusée : ${e.file} ne correspond pas à l'empreinte attendue`);
    await fs.writeFile(dest, body);
  }

  /** Une image : les tuiles z1 à z3 (quatre à la fois) ; rend sa version 256 px pour z0. */
  async function processImage(conv: Converter, e: Entry): Promise<Img> {
    const src = path.join(opts.dir, "src", e.file);
    const big = path.join(opts.dir, "tmp", `${e.file}.ppm`);
    await download(e, src);
    try {
      await conv.decode(src, IMAGE_PX[3], big);
    } finally {
      await removeTmp(src);
    }
    const levels: Img[] = [];
    levels[3] = parsePpm(await fs.readFile(big));
    await removeTmp(big);
    for (const z of [2, 1, 0]) levels[z] = halve(levels[z + 1]);
    const jobs = [1, 2, 3].flatMap((z) => {
      const n = 2 ** (z - 1);
      return range(n).flatMap((i) => range(n).map((j) => ({ z, i, j, n })));
    });
    for (let k = 0; k < jobs.length; k += 4)
      await Promise.all(
        jobs
          .slice(k, k + 4)
          .map(({ z, i, j, n }) => encodeTile(conv, crop(levels[z], i * TILE, j * TILE), tilePath(z, e.col * n + i, e.row * n + j))),
      );
    return levels[0];
  }

  async function processGroup(conv: Converter, g: Entry[]) {
    if ((await Promise.all(groupTiles(g).map(exists))).every(Boolean)) return;
    const z0 = Buffer.alloc(TILE * TILE * 3);
    for (const e of g) {
      const small = await processImage(conv, e);
      for (let y = 0; y < small.h; y++)
        small.data.copy(z0, (((e.row & 1) * 256 + y) * TILE + (e.col & 1) * 256) * 3, y * small.w * 3, (y + 1) * small.w * 3);
    }
    await encodeTile(conv, z0, groupTiles(g)[0]);
  }

  let running: Promise<void> | null = null;
  let error: string | undefined;

  async function run() {
    await fs.rm(path.join(opts.dir, "src"), { recursive: true, force: true });
    await fs.rm(path.join(opts.dir, "tmp"), { recursive: true, force: true });
    await fs.mkdir(path.join(opts.dir, "src"), { recursive: true });
    await fs.mkdir(path.join(opts.dir, "tmp"), { recursive: true });
    try {
      const conv = await opts.converter();
      for (const g of groups.values()) await processGroup(conv, g);
    } finally {
      await fs.rm(path.join(opts.dir, "tmp"), { recursive: true, force: true });
    }
  }

  return {
    async status() {
      const done = await doneCount();
      const status = running ? "loading" : done === entries.length ? "ready" : error ? "error" : "absent";
      return {
        status,
        done,
        total: entries.length,
        tile: TILE,
        levels: LEVELS,
        size: [SIDE, SIDE],
        affine: AFFINE,
        ...(error && status === "error" ? { error } : {}),
      };
    },
    /** Lance la préparation en tâche de fond ; sans effet si elle tourne déjà. Reprend là où elle s'était arrêtée. */
    async prepare() {
      if (running) return;
      error = undefined;
      running = run()
        .catch((e) => {
          error = e instanceof MapError ? e.message : "La préparation de la carte a échoué (détail dans le journal de l'ordinateur)";
          if (!(e instanceof MapError)) logFailure("préparation de la carte Cyberpunk", e);
        })
        .finally(() => (running = null));
    },
    /** Tuile servie, ou null si elle n'existe pas (hors pyramide ou pas encore faite). */
    async tile(z: number, x: number, y: number): Promise<Buffer | null> {
      if (![z, x, y].every(Number.isInteger) || z < 0 || z >= LEVELS || x < 0 || y < 0 || x >= tilesPerSide(z) || y >= tilesPerSide(z))
        return null;
      return fs.readFile(tilePath(z, x, y)).catch(() => null);
    },
  };
}

// --- libwebp -------------------------------------------------------------------------------------------------------

const LIBWEBP_ZIP = {
  url: "https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0-windows-x64.zip",
  /** Relevé le 14/11/2026 sur le zip officiel (4,1 Mo). */
  sha256: "48886f506b21f62e4661f0f4cbfca19800897c385128e8902542d29a950c93f1",
  bin: path.join("libwebp", "libwebp-1.6.0-windows-x64", "bin"),
};

/** Windows : `dwebp.exe` et `cwebp.exe` du zip officiel, téléchargé dans `.data/` au premier usage (SHA-256 vérifié). Ailleurs : le PATH. */
async function libwebpBin(): Promise<{ dwebp: string; cwebp: string }> {
  if (osName !== "windows") return { dwebp: "dwebp", cwebp: "cwebp" };
  const bin = path.join(dataDir, LIBWEBP_ZIP.bin);
  if (
    !(await fs.access(path.join(bin, "dwebp.exe")).then(
      () => true,
      () => false,
    ))
  ) {
    const res = await fetch(LIBWEBP_ZIP.url, { signal: AbortSignal.timeout(180_000) });
    const zip = Buffer.from(await res.arrayBuffer());
    if (!res.ok || createHash("sha256").update(zip).digest("hex") !== LIBWEBP_ZIP.sha256)
      throw new MapError("Outils de conversion de la carte (libwebp) : téléchargement refusé");
    await fs.rm(path.join(dataDir, "libwebp"), { recursive: true, force: true });
    await fs.mkdir(path.join(dataDir, "libwebp"), { recursive: true });
    await fs.writeFile(path.join(dataDir, "libwebp.zip"), zip);
    // bsdtar de Windows 10 lit les zip ; chemins relatifs (une lettre de lecteur serait prise pour un hôte).
    execFileSync("tar", ["-xf", "libwebp.zip", "-C", "libwebp"], { cwd: dataDir, stdio: "ignore", windowsHide: true });
    await fs.rm(path.join(dataDir, "libwebp.zip"));
  }
  return { dwebp: path.join(bin, "dwebp.exe"), cwebp: path.join(bin, "cwebp.exe") };
}

const run = (file: string, args: string[]) =>
  new Promise<void>((resolve, reject) =>
    execFile(
      file,
      args,
      { windowsHide: true, timeout: 180_000, env: { ...process.env, PATH: `${process.env.PATH}:/opt/homebrew/bin:/usr/local/bin` } },
      (e) => (e ? reject(e) : resolve()),
    ),
  );

export async function libwebp(): Promise<Converter> {
  const { dwebp, cwebp } = await libwebpBin();
  return {
    decode: (webp, size, ppm) => run(dwebp, ["-ppm", "-scale", String(size), String(size), "-o", ppm, webp]),
    encode: (ppm, webp) => run(cwebp, ["-quiet", "-q", "70", ppm, "-o", webp]),
  };
}

type Manifest = { commit: string; repo: string; dir: string; files: SourceFile[] };
let shared: ReturnType<typeof createMap> | undefined;

/** La carte de cet ordinateur (manifeste épinglé dans `assets/cyberpunk-map.json`). */
export async function cyberpunkMap() {
  if (!shared) {
    const m = JSON.parse(await fs.readFile(path.join(backendRoot, "assets", "cyberpunk-map.json"), "utf8")) as Manifest;
    shared = createMap({
      dir: path.join(dataDir, "cyberpunk-map"),
      baseUrl: `https://raw.githubusercontent.com/${m.repo}/${m.commit}/${m.dir}`,
      files: m.files,
      converter: libwebp,
    });
  }
  return shared;
}
