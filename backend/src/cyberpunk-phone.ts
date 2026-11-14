import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import type { FastifyInstance } from "fastify";
import { POI_ICONS } from "./cyberpunk-poi.ts";
import { findGame } from "./cyberpunk-steam.ts";
import { logFailure } from "./log.ts";
import { osName } from "./os.ts";
import { dataDir } from "./paths.ts";
import { readPng, writePng } from "./png-shrink.ts";

/**
 * Look du téléphone de Cyberpunk 2077 pour la tablette : les images (cadres, bulles, icônes) et deux polices sont extraites
 * du jeu installé de l'utilisateur, jamais livrées avec Mate. WolvenKit CLI (GPL-3.0, lancé à part, jamais lié ni
 * redistribué) et le runtime .NET portable sont téléchargés dans `<dataDir>/cyberpunk-phone-tools/` (zips épinglés par
 * SHA-256), puis `uncook` et `export` rendent les PNG et les TTF. Chaque pièce est découpée d'après l'`.inkatlas` du jeu
 * et rangée dans `<dataDir>/cyberpunk-phone/<pièce>.png`, avec `assets.json` (la liste blanche des noms servis).
 * Windows seulement.
 */

/** Atlas à extraire et pièces à en découper ; `prefix` devant le nom du fichier rendu. */
export const ATLASES: { name: string; parts: string[]; prefix?: string }[] = [
  { name: "atlas_phone", parts: ["frame_main", "frame_message", "frame_top", "icon_message", "icon_signal", "icon_battery", "icon_nc"] },
  { name: "new_phone_assets", parts: ["msgBuble_bg", "msgBuble_fg", "msgBuble_reply_bg", "msgBuble_reply_fg", "phone_icon", "circle"] },
  { name: "messenger", parts: ["avatar", "avatar_big", "nc"] },
  // Icônes des points d'intérêt de la carte (`base\gameplay\gui\common\icons\mappin_icons.inkatlas`).
  { name: "mappin_icons", parts: POI_ICONS, prefix: "poi_" },
];
export const FONTS = ["raj-bold", "blenderpro-book"];
/** Tous les fichiers d'une extraction complète : une liste plus courte (extraction d'une version d'avant) est à refaire. */
export const ASSETS = [...ATLASES.flatMap((a) => a.parts.map((p) => `${a.prefix ?? ""}${p}.png`)), ...FONTS.map((f) => `${f}.ttf`)];

/** Erreur dont le message peut s'afficher tel quel. */
class PhoneError extends Error {}

/** Ce que fait l'outil d'extraction : dépose dans `work` les PNG du jeu (`png/<atlas>_1080p.png`) et ce que `uncook` rend (`out/**`). */
export type Tools = { extract(gameDir: string, work: string): Promise<void> };
export type PhoneOptions = { dir: string; gameDir: () => string | null; tools: () => Promise<Tools> };

/** Fichier de ce nom dans `dir`, à n'importe quelle profondeur. */
async function find(dir: string, name: string): Promise<string | null> {
  for (const e of await fs.readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) {
      const hit = await find(full, name);
      if (hit) return hit;
    } else if (e.name === name) return full;
  }
  return null;
}

type Part = { partName?: { $value?: string }; clippingRectInUVCoords?: { Left: number; Top: number; Right: number; Bottom: number } };

/** Découpe une PNG RGBA en rectangle (pixels), lignes recopiées avec leur octet de filtre 0. */
function cutPng(png: NonNullable<ReturnType<typeof readPng>>, x0: number, y0: number, x1: number, y1: number): Buffer {
  const w = x1 - x0;
  const h = y1 - y0;
  const rows = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) png.rgba.copy(rows, y * (w * 4 + 1) + 1, ((y0 + y) * png.width + x0) * 4, ((y0 + y) * png.width + x1) * 4);
  return writePng(w, h, rows);
}

async function cutAtlas(work: string, atlas: (typeof ATLASES)[number]): Promise<Map<string, Buffer>> {
  const pngFile = await find(path.join(work, "png"), `${atlas.name}_1080p.png`);
  const jsonFile = await find(path.join(work, "out"), `${atlas.name}.inkatlas.json`);
  if (!pngFile || !jsonFile) throw new PhoneError(`Look du téléphone : l'image ${atlas.name} n'a pas été extraite du jeu`);
  const png = readPng(await fs.readFile(pngFile));
  if (!png) throw new PhoneError(`Look du téléphone : l'image ${atlas.name} est illisible`);
  const json = JSON.parse(await fs.readFile(jsonFile, "utf8"));
  const parts: Part[] = json?.Data?.RootChunk?.slots?.Elements?.[0]?.parts ?? [];
  const out = new Map<string, Buffer>();
  for (const name of atlas.parts) {
    const r = parts.find((p) => p.partName?.$value === name)?.clippingRectInUVCoords;
    if (!r) throw new PhoneError(`Look du téléphone : la pièce ${name} est introuvable dans ${atlas.name}`);
    const x0 = Math.max(0, Math.round(r.Left * png.width));
    const y0 = Math.max(0, Math.round(r.Top * png.height));
    const x1 = Math.min(png.width, Math.round(r.Right * png.width));
    const y1 = Math.min(png.height, Math.round(r.Bottom * png.height));
    if (x1 <= x0 || y1 <= y0) throw new PhoneError(`Look du téléphone : la pièce ${name} est vide`);
    out.set(`${atlas.prefix ?? ""}${name}.png`, cutPng(png, x0, y0, x1, y1));
  }
  return out;
}

export function createPhone(opts: PhoneOptions) {
  const listFile = path.join(opts.dir, "assets.json");
  let running: Promise<void> | null = null;
  let error: string | undefined;

  async function list(): Promise<string[]> {
    try {
      const names = JSON.parse(await fs.readFile(listFile, "utf8")) as string[];
      const present = await Promise.all(
        names.map((n) =>
          fs.access(path.join(opts.dir, n)).then(
            () => true,
            () => false,
          ),
        ),
      );
      return present.every(Boolean) && ASSETS.every((n) => names.includes(n)) ? names : [];
    } catch {
      return [];
    }
  }

  async function run() {
    const game = opts.gameDir();
    if (!game) throw new PhoneError("Cyberpunk 2077 (Steam) est introuvable sur cet ordinateur");
    if ((await list()).length) return;
    const work = `${opts.dir}.tmp`;
    await fs.rm(work, { recursive: true, force: true });
    await fs.mkdir(work, { recursive: true });
    try {
      await (await opts.tools()).extract(game, work);
      const files = new Map<string, Buffer>();
      for (const a of ATLASES) for (const [n, b] of await cutAtlas(work, a)) files.set(n, b);
      for (const f of FONTS) {
        const ttf = await find(path.join(work, "out"), `${f}.ttf`);
        if (!ttf) throw new PhoneError(`Look du téléphone : la police ${f} n'a pas été extraite du jeu`);
        files.set(`${f}.ttf`, await fs.readFile(ttf));
      }
      await fs.rm(opts.dir, { recursive: true, force: true });
      await fs.mkdir(opts.dir, { recursive: true });
      for (const [n, b] of files) await fs.writeFile(path.join(opts.dir, n), b);
      // La liste en dernier : sans elle, rien n'est servi.
      await fs.writeFile(listFile, JSON.stringify([...files.keys()]));
    } finally {
      await fs.rm(work, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 }).catch(() => {});
    }
  }

  return {
    async status() {
      const assets = await list();
      const status = running ? "loading" : assets.length ? "ready" : error ? "error" : "absent";
      return { status, ...(error && status === "error" ? { error } : {}), assets };
    },
    /** Lance l'extraction en tâche de fond ; sans effet si elle tourne déjà ou si tout est prêt. */
    async prepare() {
      if (running) return;
      error = undefined;
      running = run()
        .catch((e) => {
          error =
            e instanceof PhoneError ? e.message : "L'extraction du téléphone du jeu a échoué (détail dans le journal de l'ordinateur)";
          if (!(e instanceof PhoneError)) logFailure("extraction du téléphone Cyberpunk", e);
        })
        .finally(() => (running = null));
    },
    /** Contenu d'un fichier de la liste blanche, sinon null (jamais un chemin libre). */
    async asset(name: string): Promise<Buffer | null> {
      if (!(await list()).includes(name)) return null;
      return fs.readFile(path.join(opts.dir, name)).catch(() => null);
    },
  };
}

// --- WolvenKit et .NET -----------------------------------------------------------------------------------------------

export type ToolZip = { url: string; size: number; sha256: string };

const WOLVENKIT: ToolZip = {
  url: "https://github.com/WolvenKit/WolvenKit/releases/download/9.1.1/WolvenKit.Console-9.1.1.zip",
  size: 45266480,
  sha256: "a14eae46931251635256e7b209cd4aa998ae5039a5fbd506fefaab671bf7f451",
};
const DOTNET: ToolZip = {
  url: "https://builds.dotnet.microsoft.com/dotnet/Runtime/10.0.15/dotnet-runtime-10.0.15-win-x64.zip",
  size: 36980907,
  sha256: "c9f219ad2ec570478f4b1950ae56f88a48e76cabba75e7f54764a1d8a9009e87",
};

/** Zip téléchargé dont la taille et le SHA-256 sont ceux épinglés, sinon refusé. */
export async function fetchZip(spec: ToolZip): Promise<Buffer> {
  const refused = new PhoneError("Outils d'extraction du téléphone : téléchargement refusé");
  const res = await fetch(spec.url, { signal: AbortSignal.timeout(600_000) });
  const announced = res.headers.get("content-length");
  if (!res.ok || (announced !== null && Number(announced) !== spec.size)) throw refused;
  const zip = Buffer.from(await res.arrayBuffer());
  if (zip.length !== spec.size || createHash("sha256").update(zip).digest("hex") !== spec.sha256) throw refused;
  return zip;
}

const exists = (file: string) =>
  fs.access(file).then(
    () => true,
    () => false,
  );

const execFileP = (file: string, args: string[], opts: { cwd?: string; env?: NodeJS.ProcessEnv; timeout: number }) =>
  new Promise<void>((resolve, reject) => execFile(file, args, { ...opts, windowsHide: true }, (e) => (e ? reject(e) : resolve())));

/** bsdtar de Windows 10+ (chemin complet, comme cyberpunk-map.ts) ; chemins relatifs, une lettre de lecteur serait prise pour un hôte. */
const winTar = () => path.join(process.env.SystemRoot ?? "C:\\Windows", "System32", "tar.exe");

async function installZip(root: string, folder: string, spec: ToolZip) {
  const zip = await fetchZip(spec);
  // Extraction dans un dossier temporaire, puis renommage : un arrêt en route ne laisse pas un dossier à moitié rempli.
  await fs.rm(path.join(root, folder), { recursive: true, force: true });
  await fs.rm(path.join(root, `${folder}.tmp`), { recursive: true, force: true });
  await fs.mkdir(path.join(root, `${folder}.tmp`), { recursive: true });
  await fs.writeFile(path.join(root, `${folder}.zip`), zip);
  try {
    await execFileP(winTar(), ["-xf", `${folder}.zip`, "-C", `${folder}.tmp`], { cwd: root, timeout: 600_000 });
    await fs.rename(path.join(root, `${folder}.tmp`), path.join(root, folder));
  } finally {
    await fs.rm(path.join(root, `${folder}.zip`), { force: true });
    await fs.rm(path.join(root, `${folder}.tmp`), { recursive: true, force: true });
  }
}

const ATLAS_REGEX = [
  "widgets.phone.(atlas_phone|new_phone_assets)(_1080p)?\\.(xbm|inkatlas)$",
  "phone_quest_menu.messenger(_1080p)?\\.(xbm|inkatlas)$",
  "common.icons.mappin_icons(_1080p)?\\.(xbm|inkatlas)$",
].join("|");
/** Polices à part : avec `-s`, WolvenKit sort un `.fnt.json` au lieu du `.ttf` (vu sur ATLAS le 2026-11-14). */
const FONT_REGEX = "fonts.(raj.raj-bold|blender.book.blenderpro-book)\\.fnt$";

async function wolvenKit(): Promise<Tools> {
  if (osName !== "windows") throw new PhoneError("Le look du téléphone du jeu ne s'extrait que sous Windows");
  const root = path.join(dataDir, "cyberpunk-phone-tools");
  await fs.mkdir(root, { recursive: true });
  const cli = path.join(root, "wolvenkit", "WolvenKit.CLI.exe");
  const dotnet = path.join(root, "dotnet");
  if (!(await exists(cli))) await installZip(root, "wolvenkit", WOLVENKIT);
  if (!(await exists(path.join(dotnet, "dotnet.exe")))) await installZip(root, "dotnet", DOTNET);
  const env = { ...process.env, DOTNET_ROOT: dotnet };
  return {
    async extract(game, work) {
      const out = path.join(work, "out");
      const png = path.join(work, "png");
      await fs.mkdir(png, { recursive: true });
      const archives = path.join(game, "archive", "pc", "content");
      await execFileP(cli, ["uncook", archives, "-o", out, "-r", ATLAS_REGEX, "-s"], { env, timeout: 900_000 });
      await execFileP(cli, ["uncook", archives, "-o", out, "-r", FONT_REGEX], { env, timeout: 900_000 });
      const xbm = await Promise.all(ATLASES.map((a) => find(out, `${a.name}_1080p.xbm`)));
      if (xbm.some((f) => !f)) throw new PhoneError("Look du téléphone : images introuvables dans les archives du jeu");
      await execFileP(cli, ["export", ...(xbm as string[]), "-o", png, "--uext", "png", "-gp", game], { env, timeout: 600_000 });
    },
  };
}

let shared: ReturnType<typeof createPhone> | undefined;

/** Le téléphone de cet ordinateur. */
export function cyberpunkPhone() {
  shared ??= createPhone({ dir: path.join(dataDir, "cyberpunk-phone"), gameDir: () => findGame()?.dir ?? null, tools: wolvenKit });
  return shared;
}

const nameParams = { type: "object", properties: { name: { type: "string", pattern: "^[A-Za-z0-9_-]{1,40}\\.(png|ttf)$" } } };

export async function phoneRoutes(app: FastifyInstance) {
  app.get("/cyberpunk/phone", () => cyberpunkPhone().status());
  app.post("/cyberpunk/phone/prepare", async () => {
    // Pas de jeu, pas de téléchargement de 80 Mo.
    if (!findGame()) throw Object.assign(new Error("Cyberpunk 2077 (Steam) est introuvable sur cet ordinateur"), { statusCode: 409 });
    const phone = cyberpunkPhone();
    await phone.prepare();
    return phone.status();
  });
  app.get<{ Params: { name: string } }>("/cyberpunk/phone/:name", { schema: { params: nameParams } }, async (req, reply) => {
    const file = await cyberpunkPhone().asset(req.params.name);
    if (!file) return reply.code(404).send({ error: "Fichier absent" });
    const type = req.params.name.endsWith(".ttf") ? "font/ttf" : "image/png";
    return reply.header("cache-control", "private, max-age=3600").type(type).send(file);
  });
}
