import zlib from "node:zlib";

/**
 * Réduit une image PNG pour la tablette (tas de 128 Mo, docs/tablette-4.2.md) : les icônes d'agent de valorant-api.com
 * font 1024 px et 570 Ko. Lit les PNG RGB ou RGBA 8 bits non entrelacés (ceux de valorant-api.com), divise la taille par
 * la plus petite puissance de deux qui la fait passer sous `maxSide` (moyenne de chaque bloc, pondérée par l'opacité pour
 * ne pas assombrir les bords) et réécrit un PNG RGBA. Déjà assez petite : rendue telle quelle ; autre format : null.
 */
export function shrinkPng(data: Buffer, maxSide: number): Buffer | null {
  const png = readPng(data);
  if (!png) return null;
  let factor = 1;
  while (Math.max(png.width, png.height) / factor > maxSide) factor *= 2;
  if (factor === 1) return data;
  const width = Math.max(1, Math.floor(png.width / factor));
  const height = Math.max(1, Math.floor(png.height / factor));
  const out = Buffer.alloc(height * (width * 4 + 1));
  for (let y = 0; y < height; y++) {
    const row = y * (width * 4 + 1);
    for (let x = 0; x < width; x++) {
      let r = 0,
        g = 0,
        b = 0,
        a = 0;
      for (let dy = 0; dy < factor; dy++) {
        for (let dx = 0; dx < factor; dx++) {
          const i = ((y * factor + dy) * png.width + x * factor + dx) * 4;
          const alpha = png.rgba[i + 3];
          r += png.rgba[i] * alpha;
          g += png.rgba[i + 1] * alpha;
          b += png.rgba[i + 2] * alpha;
          a += alpha;
        }
      }
      const o = row + 1 + x * 4;
      if (a > 0) {
        out[o] = Math.round(r / a);
        out[o + 1] = Math.round(g / a);
        out[o + 2] = Math.round(b / a);
      }
      out[o + 3] = Math.round(a / (factor * factor));
    }
  }
  return writePng(width, height, out);
}

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function readPng(data: Buffer): { width: number; height: number; rgba: Buffer } | null {
  if (data.length < 33 || !data.subarray(0, 8).equals(SIGNATURE)) return null;
  const width = data.readUInt32BE(16);
  const height = data.readUInt32BE(20);
  const [depth, color, , , interlace] = data.subarray(24, 29);
  const channels = color === 6 ? 4 : color === 2 ? 3 : 0;
  if (depth !== 8 || !channels || interlace !== 0 || width === 0 || height === 0 || width * height > 4096 * 4096) return null;
  const parts: Buffer[] = [];
  for (let at = 8; at + 8 <= data.length;) {
    const len = data.readUInt32BE(at);
    if (data.toString("ascii", at + 4, at + 8) === "IDAT") parts.push(data.subarray(at + 8, at + 8 + len));
    at += 12 + len;
  }
  let raw: Buffer;
  try {
    raw = zlib.inflateSync(Buffer.concat(parts));
  } catch {
    return null;
  }
  const stride = width * channels;
  if (raw.length < height * (stride + 1)) return null;
  const pixels = unfilter(raw, height, stride, channels);
  if (!pixels) return null;
  if (channels === 4) return { width, height, rgba: pixels };
  const rgba = Buffer.alloc(width * height * 4, 255);
  for (let i = 0; i < width * height; i++) pixels.copy(rgba, i * 4, i * 3, i * 3 + 3);
  return { width, height, rgba };
}

/** Défait les filtres de chaque ligne (PNG, § 9) ; null si un filtre est inconnu. */
function unfilter(raw: Buffer, height: number, stride: number, bpp: number): Buffer | null {
  const out = Buffer.alloc(height * stride);
  for (let y = 0; y < height; y++) {
    const type = raw[y * (stride + 1)];
    const src = y * (stride + 1) + 1;
    const dst = y * stride;
    for (let x = 0; x < stride; x++) {
      const left = x >= bpp ? out[dst + x - bpp] : 0;
      const up = y > 0 ? out[dst - stride + x] : 0;
      const upLeft = y > 0 && x >= bpp ? out[dst - stride + x - bpp] : 0;
      const value = raw[src + x];
      let predictor: number;
      if (type === 0) predictor = 0;
      else if (type === 1) predictor = left;
      else if (type === 2) predictor = up;
      else if (type === 3) predictor = (left + up) >> 1;
      else if (type === 4) predictor = paeth(left, up, upLeft);
      else return null;
      out[dst + x] = (value + predictor) & 0xff;
    }
  }
  return out;
}

function paeth(a: number, b: number, c: number) {
  const p = a + b - c;
  const pa = Math.abs(p - a),
    pb = Math.abs(p - b),
    pc = Math.abs(p - c);
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
}

/** PNG RGBA 8 bits ; `rows` : chaque ligne précédée de son octet de filtre (0). */
function writePng(width: number, height: number, rows: Buffer): Buffer {
  const chunk = (type: string, body: Buffer) => {
    const head = Buffer.alloc(4);
    head.writeUInt32BE(body.length);
    const typed = Buffer.concat([Buffer.from(type, "ascii"), body]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(zlib.crc32(typed));
    return Buffer.concat([head, typed, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([SIGNATURE, chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(rows)), chunk("IEND", Buffer.alloc(0))]);
}
