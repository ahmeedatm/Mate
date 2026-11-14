import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { osName } from "./os.ts";
import { logFailure } from "./log.ts";
import { dataDir, backendRoot } from "./paths.ts";
import { BG, TILE, decodeBuildings, renderTile, tileIndex, type Boxes } from "./cyberpunk-blocks.ts";

/**
 * Carte de Night City pour la page Cyberpunk de la tablette, en blocs façon carte holographique du jeu : les cubes de
 * la carte 3D (Open2077/open77-resources, commit épinglé, SHA-256 de chaque fichier dans `assets/cyberpunk-blocs.json`)
 * sont téléchargés une fois (4 Mo), dessinés en tuiles de 512 px au niveau max (`cyberpunk-blocks.ts`), réduits pour
 * les niveaux du dessus, puis encodés par `cwebp` de libwebp (BSD) : `<dataDir>/cyberpunk-blocs/<z>/<x>/<y>.webp`.
 */

export { TILE };
export const LEVELS = 4;
/** Côté de la carte au niveau max, en pixels : 56 tuiles, même géométrie que l'ancienne carte (repères, POI inchangés). */
export const SIDE = 56 * TILE;
/** Calage de l'ancienne carte de fans, gardé : pixel = (61008 + 10.788·x, 46934 − 10.788·y) sur 111 104 px, x est, y nord. */
const K = SIDE / 111104;
const SCALE = 10.788 * K;
/** px = a·x + b·y + c, py = d·x + e·y + f au niveau max. */
export const AFFINE = [SCALE, 0, 61008 * K, 0, -SCALE, 46934 * K];
/** Tuiles par côté au niveau z : 7, 14, 28, 56. */
const tilesPerSide = (z: number) => SIDE / TILE / 2 ** (LEVELS - 1 - z);
/** Un groupe = une tuile de z0 et les 4 + 16 + 64 tuiles qu'elle couvre en dessous. */
const GROUPS = tilesPerSide(0);
const SPAN = 2 ** (LEVELS - 1);

export type Converter = {
  /** Encode une tuile (PPM, ou BMP si `input` le demande) en webp. */
  encode(tile: string, webp: string): Promise<void>;
  /** Format de tuile que l'encodeur sait lire ; PPM par défaut. */
  input?: "ppm" | "bmp";
};
export type SourceFile = { file: string; sha256: string; size: number };
export type MapOptions = { dir: string; baseUrl: string; files: SourceFile[]; converter: () => Promise<Converter> };

/** Erreur dont le message peut s'afficher tel quel. */
class MapError extends Error {}

type Img = { w: number; h: number; data: Buffer };

const writePpm = (file: string, w: number, h: number, data: Buffer) =>
  fs.writeFile(file, Buffer.concat([Buffer.from(`P6\n${w} ${h}\n255\n`), data]));

/** BMP 24 bits (lignes du bas vers le haut, BGR, complétées à 4 octets) : cwebp de Windows lit par WIC, sans PPM. */
export function bmp(w: number, h: number, rgb: Buffer): Buffer {
  const row = Math.ceil((w * 3) / 4) * 4;
  const out = Buffer.alloc(54 + row * h);
  out.write("BM", 0, "latin1");
  out.writeUInt32LE(out.length, 2);
  out.writeUInt32LE(54, 10);
  out.writeUInt32LE(40, 14);
  out.writeInt32LE(w, 18);
  out.writeInt32LE(h, 22);
  out.writeUInt16LE(1, 26);
  out.writeUInt16LE(24, 28);
  out.writeUInt32LE(row * h, 34);
  for (let y = 0; y < h; y++) {
    const dst = 54 + (h - 1 - y) * row;
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 3;
      out[dst + x * 3] = rgb[i + 2];
      out[dst + x * 3 + 1] = rgb[i + 1];
      out[dst + x * 3 + 2] = rgb[i];
    }
  }
  return out;
}

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

const range = (n: number) => [...Array(n).keys()];

export function createMap(opts: MapOptions) {
  const tilePath = (z: number, x: number, y: number) => path.join(opts.dir, String(z), String(x), `${y}.webp`);
  const groups = range(GROUPS).flatMap((gy) => range(GROUPS).map((gx) => ({ gx, gy })));
  /** Les tuiles d'un groupe, de z0 à z3. */
  const groupTiles = ({ gx, gy }: { gx: number; gy: number }) =>
    range(LEVELS).flatMap((z) => {
      const n = 2 ** z;
      return range(n).flatMap((j) => range(n).map((i) => ({ z, x: gx * n + i, y: gy * n + j })));
    });
  const groupDone = async (g: { gx: number; gy: number }) =>
    (await Promise.all(groupTiles(g).map((t) => exists(tilePath(t.z, t.x, t.y))))).every(Boolean);

  async function doneCount() {
    let done = 0;
    for (const g of groups) if (await groupDone(g)) done++;
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

  const blank = Buffer.alloc(TILE * TILE * 3).fill(Buffer.from(BG));

  /** Une tuile vide (fond seul) n'est encodée qu'une fois par préparation, puis recopiée. */
  async function encodeTile(conv: Converter, data: Buffer, file: string) {
    await fs.mkdir(path.dirname(file), { recursive: true });
    const empty = path.join(opts.dir, "tmp", "vide.webp");
    if (data.equals(blank) && (await exists(empty))) {
      await fs.copyFile(empty, `${file}.part`);
      return renameRetry(`${file}.part`, file);
    }
    const ppm = path.join(opts.dir, "tmp", `tile-${tmpCount++}.${conv.input ?? "ppm"}`);
    if (conv.input === "bmp") await fs.writeFile(ppm, bmp(TILE, TILE, data));
    else await writePpm(ppm, TILE, TILE, data);
    await conv.encode(ppm, `${file}.part`);
    if (data.equals(blank)) await fs.copyFile(`${file}.part`, empty);
    await renameRetry(`${file}.part`, file);
    await removeTmp(ppm);
  }

  async function download(f: SourceFile): Promise<Buffer> {
    const res = await fetch(`${opts.baseUrl}/${f.file}`, { signal: AbortSignal.timeout(180_000) });
    if (!res.ok) throw new MapError(`Téléchargement de la carte impossible (${f.file}, ${res.status})`);
    // Taille annoncée différente de l'attendue : refusée avant de lire le corps. Un corps compressé (le JSON l'est sur
    // raw.githubusercontent.com) annonce sa taille compressée : seule la taille lue compte alors.
    const announced = res.headers.get("content-encoding") ? null : res.headers.get("content-length");
    if (announced !== null && Number(announced) !== f.size)
      throw new MapError(`Données de carte refusées : ${f.file} n'a pas la taille attendue`);
    const body = Buffer.from(await res.arrayBuffer());
    if (body.length !== f.size) throw new MapError(`Données de carte refusées : ${f.file} n'a pas la taille attendue`);
    if (createHash("sha256").update(body).digest("hex") !== f.sha256)
      throw new MapError(`Données de carte refusées : ${f.file} ne correspond pas à l'empreinte attendue`);
    return body;
  }

  async function loadBoxes() {
    const [manifest, bin] = await Promise.all(opts.files.map(download));
    let boxes: Boxes;
    try {
      boxes = decodeBuildings(JSON.parse(manifest.toString("utf8")), bin);
    } catch {
      throw new MapError("Données de carte illisibles");
    }
    return { boxes, index: tileIndex(boxes, AFFINE, tilesPerSide(LEVELS - 1)) };
  }

  /** Un groupe : ses 64 tuiles de z3 dessinées, puis réduites en 16, 4 et 1 ; encodées quatre à la fois. */
  async function processGroup(conv: Converter, g: { gx: number; gy: number }, data: Awaited<ReturnType<typeof loadBoxes>>) {
    const side = SPAN * TILE;
    const levels: Img[] = [];
    levels[LEVELS - 1] = { w: side, h: side, data: Buffer.alloc(side * side * 3).fill(Buffer.from(BG)) };
    const per = tilesPerSide(LEVELS - 1);
    for (let j = 0; j < SPAN; j++)
      for (let i = 0; i < SPAN; i++) {
        const tx = g.gx * SPAN + i;
        const ty = g.gy * SPAN + j;
        const boxes = data.index.get(ty * per + tx);
        if (!boxes) continue;
        const tile = renderTile(data.boxes, boxes, tx, ty, AFFINE);
        for (let y = 0; y < TILE; y++)
          tile.copy(levels[LEVELS - 1].data, ((j * TILE + y) * side + i * TILE) * 3, y * TILE * 3, (y + 1) * TILE * 3);
      }
    for (let z = LEVELS - 2; z >= 0; z--) levels[z] = halve(levels[z + 1]);
    const jobs = groupTiles(g);
    // La tuile vide est encodée une fois, avant les autres, pour être recopiée ensuite.
    if (!(await exists(path.join(opts.dir, "tmp", "vide.webp"))))
      await encodeTile(conv, blank, path.join(opts.dir, "tmp", "vide-modele.webp"));
    for (let k = 0; k < jobs.length; k += 4) {
      // allSettled : sur un échec, attendre les trois autres cwebp avant de nettoyer tmp (sinon EBUSY masque la cause).
      const results = await Promise.allSettled(
        jobs.slice(k, k + 4).map(({ z, x, y }) => {
          const n = 2 ** z;
          return encodeTile(conv, crop(levels[z], (x - g.gx * n) * TILE, (y - g.gy * n) * TILE), tilePath(z, x, y));
        }),
      );
      const failed = results.find((r): r is PromiseRejectedResult => r.status === "rejected");
      if (failed) throw failed.reason;
    }
  }

  let running: Promise<void> | null = null;
  let error: string | undefined;

  async function run() {
    await fs.rm(path.join(opts.dir, "tmp"), { recursive: true, force: true });
    await fs.mkdir(path.join(opts.dir, "tmp"), { recursive: true });
    try {
      const todo = [];
      for (const g of groups) if (!(await groupDone(g))) todo.push(g);
      if (todo.length === 0) return;
      const conv = await opts.converter();
      const data = await loadBoxes();
      for (const g of todo) await processGroup(conv, g, data);
    } finally {
      // Un nettoyage raté ne doit pas remplacer l'erreur d'origine ; tmp est vidé au prochain lancement.
      await fs.rm(path.join(opts.dir, "tmp"), { recursive: true, force: true, maxRetries: 10, retryDelay: 100 }).catch(() => {});
    }
  }

  return {
    async status() {
      const done = await doneCount();
      const status = running ? "loading" : done === groups.length ? "ready" : error ? "error" : "absent";
      return {
        status,
        done,
        total: groups.length,
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
  /** Octets du zip, vérifiés avant de le lire. */
  size: 4106264,
  sha256: "48886f506b21f62e4661f0f4cbfca19800897c385128e8902542d29a950c93f1",
  bin: path.join("libwebp", "libwebp-1.6.0-windows-x64", "bin"),
};

const exists = (file: string) =>
  fs.access(file).then(
    () => true,
    () => false,
  );

/** bsdtar de Windows 10+ (chemin complet : un `tar` du PATH, ex. Git, lit mal les zip). Chemins relatifs, une lettre de lecteur serait prise pour un hôte. */
const winTar = () => path.join(process.env.SystemRoot ?? "C:\\Windows", "System32", "tar.exe");
const execFileP = (file: string, args: string[], cwd: string) =>
  new Promise<void>((resolve, reject) =>
    execFile(file, args, { cwd, windowsHide: true, timeout: 180_000 }, (e) => (e ? reject(e) : resolve())),
  );

/** Windows : `cwebp.exe` du zip officiel, téléchargé dans `.data/` au premier usage (taille et SHA-256 vérifiés). Ailleurs : le PATH. */
async function libwebpBin(): Promise<{ cwebp: string }> {
  if (osName !== "windows") return { cwebp: "cwebp" };
  const bin = path.join(dataDir, LIBWEBP_ZIP.bin);
  if (!(await exists(path.join(bin, "cwebp.exe")))) {
    const res = await fetch(LIBWEBP_ZIP.url, { signal: AbortSignal.timeout(180_000) });
    const announced = res.headers.get("content-length");
    if (!res.ok || (announced !== null && Number(announced) !== LIBWEBP_ZIP.size))
      throw new MapError("Outils de conversion de la carte (libwebp) : téléchargement refusé");
    const zip = Buffer.from(await res.arrayBuffer());
    if (zip.length !== LIBWEBP_ZIP.size || createHash("sha256").update(zip).digest("hex") !== LIBWEBP_ZIP.sha256)
      throw new MapError("Outils de conversion de la carte (libwebp) : téléchargement refusé");
    // Extraction dans un dossier temporaire, puis renommage : un arrêt en route ne laisse pas un dossier à moitié rempli.
    await fs.rm(path.join(dataDir, "libwebp"), { recursive: true, force: true });
    await fs.rm(path.join(dataDir, "libwebp.tmp"), { recursive: true, force: true });
    await fs.mkdir(path.join(dataDir, "libwebp.tmp"), { recursive: true });
    await fs.writeFile(path.join(dataDir, "libwebp.zip"), zip);
    try {
      await execFileP(winTar(), ["-xf", "libwebp.zip", "-C", "libwebp.tmp"], dataDir);
      await fs.rename(path.join(dataDir, "libwebp.tmp"), path.join(dataDir, "libwebp"));
    } finally {
      await fs.rm(path.join(dataDir, "libwebp.zip"), { force: true });
      await fs.rm(path.join(dataDir, "libwebp.tmp"), { recursive: true, force: true });
    }
  }
  return { cwebp: path.join(bin, "cwebp.exe") };
}

const run = (file: string, args: string[]) =>
  new Promise<void>((resolve, reject) =>
    execFile(
      file,
      args,
      {
        windowsHide: true,
        timeout: 180_000,
        env: {
          ...process.env,
          PATH: osName === "windows" ? process.env.PATH : [process.env.PATH, "/opt/homebrew/bin", "/usr/local/bin"].join(path.delimiter),
        },
      },
      (e) => (e ? reject(e) : resolve()),
    ),
  );

export async function libwebp(): Promise<Converter> {
  const { cwebp } = await libwebpBin();
  return {
    encode: (tile, webp) => run(cwebp, ["-quiet", "-q", "70", tile, "-o", webp]),
    input: osName === "windows" ? "bmp" : "ppm",
  };
}

type Manifest = { commit: string; repo: string; dir: string; files: SourceFile[] };
let shared: ReturnType<typeof createMap> | undefined;

/** La carte de cet ordinateur (sources épinglées dans `assets/cyberpunk-blocs.json`). */
export async function cyberpunkMap() {
  if (!shared) {
    const m = JSON.parse(await fs.readFile(path.join(backendRoot, "assets", "cyberpunk-blocs.json"), "utf8")) as Manifest;
    // Tuiles de l'ancienne carte de fans (plan détaillé, ~120 Mo) : remplacées par les blocs, plus jamais lues.
    fs.rm(path.join(dataDir, "cyberpunk-map"), { recursive: true, force: true }).catch((e) =>
      logFailure("suppression de l'ancienne carte Cyberpunk", e),
    );
    shared = createMap({
      dir: path.join(dataDir, "cyberpunk-blocs"),
      baseUrl: `https://raw.githubusercontent.com/${m.repo}/${m.commit}/${m.dir}`,
      files: m.files,
      converter: libwebp,
    });
  }
  return shared;
}
