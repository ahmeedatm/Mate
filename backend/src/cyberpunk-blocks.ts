/**
 * Cubes de la carte holographique du jeu (Open2077/open77-resources, `standalone/op77_map3d/web/data`, MIT, extraits
 * de `base\entities\cameras\3dmap`) : décodage de `buildings.bin` et rendu d'une tuile de 512 px, vue de dessus avec
 * les toits levés vers le nord selon la hauteur, une couleur par quartier. Design :
 * docs/specs/2026-11-14-cyberpunk-carte-blocs-design.md.
 */

export type Rgb = [number, number, number];
/** px = a·x + b·y + c, py = d·x + e·y + f au niveau max (b = d = 0 : nord en haut, sans rotation). */
export type Affine = number[];

export const TILE = 512;
export const BG: Rgb = [7, 8, 12];

/**
 * Une couleur par quartier, dans l'ordre des index de `Boxes.district` ; « other » pour un nom inconnu. Loin du jaune du
 * joueur et du trajet, du cyan des repères et du rouge des charcudocs (`cp_*` de la tablette), pour qu'ils ressortent.
 */
export const COLORS: { name: string; rgb: Rgb }[] = [
  { name: "watson", rgb: [44, 168, 112] },
  { name: "westbrook", rgb: [190, 60, 150] },
  { name: "city_center", rgb: [118, 112, 222] },
  { name: "heywood", rgb: [96, 170, 70] },
  { name: "santo_domingo", rgb: [176, 130, 80] },
  { name: "pacifica", rgb: [160, 80, 210] },
  { name: "ep1_dogtown", rgb: [205, 85, 140] },
  { name: "badlands", rgb: [120, 112, 96] },
  { name: "ep1_spaceport", rgb: [140, 150, 172] },
  { name: "other", rgb: [110, 116, 130] },
];

/**
 * Le jeu range les Badlands avec Pacifica : une boîte de « pacifica » à l'est de x = −800 m ou au nord de y = −1700 m
 * (mètres du jeu) est aux Badlands ; Pacifica garde le sud-ouest, côte comprise.
 */
// shortcut: limite tracée à l'œil sur la vue d'ensemble, à remplacer par les vraies limites si on les extrait du jeu.
const PACIFICA_CORNER = { x: -800, y: -1700 };
/** Toit levé de 0,3 m vers le nord par mètre de hauteur (vue oblique, comme le mode RUE de la maquette retenue). */
const LIFT = 0.3;
/** Le sol d'une boîte = le bas le plus bas des boîtes de sa maille (relief du terrain, ponts, autoroutes surélevées). */
const GROUND_CELL = 80;
const RECORD = 16;

export type Boxes = {
  n: number;
  /** 4 coins au sol par boîte (x, y en mètres du jeu, X est, Y nord). */
  corners: Float32Array;
  /** Bas et haut au-dessus du sol voisin, en mètres. */
  base: Float32Array;
  top: Float32Array;
  district: Uint8Array;
  /** Ordre de dessin : du nord au sud, puis du plus bas au plus haut (le sud cache le nord une fois les toits levés). */
  order: Uint32Array;
};

type District = { name: string; offset: number; count: number; min: number[]; span: number[]; maxSize: number };

const isNums = (v: unknown, n: number): v is number[] => Array.isArray(v) && v.length === n && v.every(Number.isFinite);

function districtsOf(manifest: unknown): District[] {
  const m = manifest as { format?: unknown; buildings?: { stride?: unknown; districts?: unknown } } | null;
  const list = m?.buildings?.districts;
  if (m?.format !== "op77_map3d/1" || m.buildings?.stride !== RECORD || !Array.isArray(list))
    throw new Error("manifeste de la carte 3D inattendu");
  return list.map((d: Partial<District>) => {
    if (
      typeof d.name !== "string" ||
      !Number.isInteger(d.offset) ||
      !Number.isInteger(d.count) ||
      !isNums(d.min, 3) ||
      !isNums(d.span, 3) ||
      !Number.isFinite(d.maxSize)
    )
      throw new Error("quartier de la carte 3D inattendu");
    return d as District;
  });
}

/** Rotation d'un vecteur par un quaternion (x, y, z, w) : v + 2w(u×v) + 2u×(u×v). */
function rotate(q: number[], v: number[]): number[] {
  const [x, y, z, w] = q;
  const t = [2 * (y * v[2] - z * v[1]), 2 * (z * v[0] - x * v[2]), 2 * (x * v[1] - y * v[0])];
  return [v[0] + w * t[0] + (y * t[2] - z * t[1]), v[1] + w * t[1] + (z * t[0] - x * t[2]), v[2] + w * t[2] + (x * t[1] - y * t[0])];
}

const colorIndex = (name: string) => {
  const i = COLORS.findIndex((c) => c.name === name);
  return i >= 0 ? i : COLORS.length - 1;
};

/** `buildings.bin` : par boîte, u16 position (scène x, y haut, z = −y du jeu), i8 quaternion /127, u16 taille (scène). */
export function decodeBuildings(manifest: unknown, bin: Buffer): Boxes {
  const districts = districtsOf(manifest);
  const n = districts.reduce((s, d) => Math.max(s, d.offset + d.count), 0);
  if (bin.length !== n * RECORD) throw new Error("données de la carte 3D tronquées");
  const corners = new Float32Array(n * 8);
  const lo = new Float32Array(n);
  const hi = new Float32Array(n);
  const cx = new Float32Array(n);
  const cy = new Float32Array(n);
  const district = new Uint8Array(n);
  const badlands = colorIndex("badlands");
  for (const d of districts) {
    const own = colorIndex(d.name);
    for (let i = d.offset; i < d.offset + d.count; i++) {
      const o = i * RECORD;
      const p = [0, 1, 2].map((k) => d.min[k] + (bin.readUInt16LE(o + k * 2) / 65535) * d.span[k]);
      const q = [0, 1, 2, 3].map((k) => bin.readInt8(o + 6 + k) / 127);
      const s = [0, 1, 2].map((k) => (bin.readUInt16LE(o + 10 + k * 2) / 65535) * d.maxSize);
      const x = p[0];
      const y = -p[2];
      cx[i] = x;
      cy[i] = y;
      lo[i] = p[1] - s[1] / 2;
      hi[i] = p[1] + s[1] / 2;
      [
        [-1, -1],
        [1, -1],
        [1, 1],
        [-1, 1],
      ].forEach(([sx, sz], k) => {
        const r = rotate(q, [(sx * s[0]) / 2, 0, (sz * s[2]) / 2]);
        corners[i * 8 + k * 2] = x + r[0];
        corners[i * 8 + k * 2 + 1] = y - r[2];
      });
      district[i] = d.name === "pacifica" && !(x < PACIFICA_CORNER.x && y < PACIFICA_CORNER.y) ? badlands : own;
    }
  }
  const cellOf = (i: number) => `${Math.floor(cx[i] / GROUND_CELL)},${Math.floor(cy[i] / GROUND_CELL)}`;
  const ground = new Map<string, number>();
  for (let i = 0; i < n; i++) ground.set(cellOf(i), Math.min(ground.get(cellOf(i)) ?? Infinity, lo[i]));
  const base = new Float32Array(n);
  const top = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const g = ground.get(cellOf(i))!;
    base[i] = Math.max(0, lo[i] - g);
    top[i] = Math.max(0, hi[i] - g);
  }
  const order = Uint32Array.from({ length: n }, (_, i) => i).sort((a, b) => cy[b] - cy[a] || top[a] - top[b]);
  return { n, corners, base, top, district, order };
}

/** Coins d'une boîte en pixels du niveau max, levés de `lift` mètres vers le nord. */
function project(b: Boxes, i: number, aff: Affine, lift: number, out: Float64Array) {
  for (let k = 0; k < 4; k++) {
    const x = b.corners[i * 8 + k * 2];
    const y = b.corners[i * 8 + k * 2 + 1] + lift;
    out[k * 2] = aff[0] * x + aff[1] * y + aff[2];
    out[k * 2 + 1] = aff[3] * x + aff[4] * y + aff[5];
  }
}

/** Tuiles du niveau max (`perSide` par côté, clé = ty·perSide + tx) que touche chaque boîte, toit compris, dans l'ordre de dessin. */
export function tileIndex(b: Boxes, aff: Affine, perSide: number): Map<number, number[]> {
  const index = new Map<number, number[]>();
  const foot = new Float64Array(8);
  const roof = new Float64Array(8);
  for (const i of b.order) {
    project(b, i, aff, 0, foot);
    project(b, i, aff, b.top[i] * LIFT, roof);
    const xs = [0, 2, 4, 6].flatMap((k) => [foot[k], roof[k]]);
    const ys = [1, 3, 5, 7].flatMap((k) => [foot[k], roof[k]]);
    const tx0 = Math.max(0, Math.floor(Math.min(...xs) / TILE));
    const tx1 = Math.min(perSide - 1, Math.floor(Math.max(...xs) / TILE));
    const ty0 = Math.max(0, Math.floor(Math.min(...ys) / TILE));
    const ty1 = Math.min(perSide - 1, Math.floor(Math.max(...ys) / TILE));
    for (let ty = ty0; ty <= ty1; ty++)
      for (let tx = tx0; tx <= tx1; tx++) {
        const key = ty * perSide + tx;
        const list = index.get(key);
        if (list) list.push(i);
        else index.set(key, [i]);
      }
  }
  return index;
}

const shade = (c: Rgb, k: number): Rgb => [
  Math.min(255, Math.round(c[0] * k)),
  Math.min(255, Math.round(c[1] * k)),
  Math.min(255, Math.round(c[2] * k)),
];

/** Tuile RVB de 512 px dessinée par polygones convexes et traits de 1 px, coupés au bord. */
class Canvas {
  readonly data = Buffer.alloc(TILE * TILE * 3).fill(Buffer.from(BG));
  constructor(
    private ox: number,
    private oy: number,
  ) {}

  private set(x: number, y: number, c: Rgb) {
    if (x < 0 || y < 0 || x >= TILE || y >= TILE) return;
    this.data.set(c, (y * TILE + x) * 3);
  }

  /** Remplit un polygone convexe (pixels dont le centre est dedans). */
  fill(pts: number[], c: Rgb) {
    const n = pts.length / 2;
    let y0 = Infinity;
    let y1 = -Infinity;
    for (let k = 0; k < n; k++) {
      y0 = Math.min(y0, pts[k * 2 + 1] - this.oy);
      y1 = Math.max(y1, pts[k * 2 + 1] - this.oy);
    }
    const from = Math.max(0, Math.ceil(y0 - 0.5));
    const to = Math.min(TILE - 1, Math.floor(y1 - 0.5));
    for (let y = from; y <= to; y++) {
      const sy = y + 0.5 + this.oy;
      let xl = Infinity;
      let xr = -Infinity;
      for (let k = 0; k < n; k++) {
        const ax = pts[k * 2];
        const ay = pts[k * 2 + 1];
        const bx = pts[((k + 1) % n) * 2];
        const by = pts[((k + 1) % n) * 2 + 1];
        if (ay === by || sy < Math.min(ay, by) || sy > Math.max(ay, by)) continue;
        const x = ax + ((sy - ay) / (by - ay)) * (bx - ax);
        xl = Math.min(xl, x);
        xr = Math.max(xr, x);
      }
      const a = Math.max(0, Math.ceil(xl - this.ox - 0.5));
      const b = Math.min(TILE - 1, Math.floor(xr - this.ox - 0.5));
      for (let x = a; x <= b; x++) this.data.set(c, (y * TILE + x) * 3);
    }
  }

  /** Contour fermé, trait de 1 px (Bresenham). */
  outline(pts: number[], c: Rgb) {
    const n = pts.length / 2;
    for (let k = 0; k < n; k++) {
      let x0 = Math.round(pts[k * 2] - this.ox);
      let y0 = Math.round(pts[k * 2 + 1] - this.oy);
      const x1 = Math.round(pts[((k + 1) % n) * 2] - this.ox);
      const y1 = Math.round(pts[((k + 1) % n) * 2 + 1] - this.oy);
      if (Math.max(x0, x1) < 0 || Math.min(x0, x1) >= TILE || Math.max(y0, y1) < 0 || Math.min(y0, y1) >= TILE) continue;
      const dx = Math.abs(x1 - x0);
      const dy = -Math.abs(y1 - y0);
      const sx = x0 < x1 ? 1 : -1;
      const sy = y0 < y1 ? 1 : -1;
      let err = dx + dy;
      for (;;) {
        this.set(x0, y0, c);
        if (x0 === x1 && y0 === y1) break;
        const e2 = 2 * err;
        if (e2 >= dy) {
          err += dy;
          x0 += sx;
        }
        if (e2 <= dx) {
          err += dx;
          y0 += sy;
        }
      }
    }
  }
}

/** Tuile (tx, ty) du niveau max : murs sombres, toit plus clair avec la hauteur, arêtes lumineuses (look hologramme). */
export function renderTile(b: Boxes, boxes: number[], tx: number, ty: number, aff: Affine): Buffer {
  const canvas = new Canvas(tx * TILE, ty * TILE);
  const foot = new Float64Array(8);
  const roof = new Float64Array(8);
  for (const i of boxes) {
    const col = COLORS[b.district[i]].rgb;
    const t = b.top[i];
    project(b, i, aff, b.base[i] * LIFT, foot);
    project(b, i, aff, t * LIFT, roof);
    if (t - b.base[i] > 0.5) {
      const wall = shade(col, 0.16);
      const edge = shade(col, 0.55);
      for (let k = 0; k < 4; k++) {
        const m = (k + 1) % 4;
        const quad = [
          foot[k * 2],
          foot[k * 2 + 1],
          foot[m * 2],
          foot[m * 2 + 1],
          roof[m * 2],
          roof[m * 2 + 1],
          roof[k * 2],
          roof[k * 2 + 1],
        ];
        canvas.fill(quad, wall);
        canvas.outline(quad, edge);
      }
    }
    const lum = 0.32 + Math.min(0.4, t / 300);
    const pts = [...roof];
    canvas.fill(pts, shade(col, lum));
    canvas.outline(pts, shade(col, 1.35));
  }
  return canvas.data;
}
