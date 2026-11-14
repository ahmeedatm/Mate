import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { gzipSync } from "node:zlib";
import { BG } from "../src/cyberpunk-blocks.ts";
import { AFFINE, SIDE, bmp, createMap, type Converter } from "../src/cyberpunk-map.ts";
import { fakeBuildings } from "./cyberpunk-blocks-fake.ts";
import { fakeSteam, until } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

const sha256 = (b: Buffer) => createHash("sha256").update(b).digest("hex");
/** Une tour à Watson, au pied du pixel (x, y) du niveau max = (61008 + 10,788·gx ; 46934 − 10,788·gy) × K. */
const { manifest, bin } = fakeBuildings({ watson: [{ x: -1500, y: 2000, z: 30, w: 60, d: 60, h: 60 }] });
const MANIFEST = Buffer.from(JSON.stringify(manifest));
const FILES = [
  { file: "manifest.json", sha256: sha256(MANIFEST), size: MANIFEST.length },
  { file: "buildings.bin", sha256: sha256(bin), size: bin.length },
];
const BODIES: Record<string, Buffer> = { "manifest.json": MANIFEST, "buildings.bin": bin };

let http1: http.Server;
let base: string;
let tmp: string;
let downloads = 0;

/** Convertisseur en Node pur : une tuile unie devient « uni r,g,b », une autre garde son PPM (le « webp » est le PPM). */
const fake: Converter = {
  async encode(input, out) {
    const ppm = fs.readFileSync(input);
    const px = ppm.subarray(ppm.length - 512 * 512 * 3);
    const uniform = px.every((v, i) => v === px[i % 3]);
    fs.writeFileSync(out, uniform ? Buffer.from(`uni ${px[0]},${px[1]},${px[2]}`) : ppm);
  },
};
/** Attend « ready » ; en cas d'échec, le message dit l'état. */
const ready = (map: ReturnType<typeof newMap>) =>
  until(async () => (await map.status()).status === "ready", 60_000).catch(async (e) => {
    throw new Error(`${e.message} : ${JSON.stringify(await map.status())}`);
  });
const newMap = (name: string, files = FILES) => createMap({ dir: path.join(tmp, name), baseUrl: base, files, converter: async () => fake });
/** Tuile du niveau max qui contient le point du jeu (x, y). */
const tileOf = (x: number, y: number) => [Math.floor((AFFINE[0] * x + AFFINE[2]) / 512), Math.floor((AFFINE[4] * y + AFFINE[5]) / 512)];

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mate-map-"));
  http1 = http.createServer((req, res) => {
    const body = BODIES[path.basename(req.url ?? "")];
    if (!body) return void res.writeHead(404).end();
    downloads++;
    // Comme raw.githubusercontent.com : le JSON part compressé, la taille annoncée est celle du gzip.
    if (req.url?.endsWith(".json")) {
      const gz = gzipSync(body);
      return void res.writeHead(200, { "content-encoding": "gzip", "content-length": gz.length }).end(gz);
    }
    res.writeHead(200).end(body);
  });
  await new Promise<void>((r) => http1.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(http1.address() as AddressInfo).port}`;
});
after(async () => {
  http1.close();
  fs.rmSync(tmp, { recursive: true, force: true });
});

describe("pyramide de tuiles", () => {
  it("calage : celui de l'ancienne carte, gardé (repères et points d'intérêt au même endroit)", () => {
    const k = 2048 / 7936;
    for (const [i, v] of [10.788 * k, 0, 61008 * k, 0, -10.788 * k, 46934 * k].entries()) assert.ok(Math.abs(AFFINE[i] - v) < 1e-9);
    assert.equal(SIDE, 28672);
  });

  it("absent, puis loading, puis ready ; les blocs sont dessinés à leur place, le reste au fond", async () => {
    const map = newMap("ok");
    const before = await map.status();
    assert.deepEqual([before.status, before.done, before.total], ["absent", 0, 49]);
    await map.prepare();
    await ready(map);
    const s = await map.status();
    assert.deepEqual([s.done, s.total], [49, 49]);
    const [tx, ty] = tileOf(-1500, 2000);
    const tower = (await map.tile(3, tx, ty))!;
    assert.equal(tower.toString("ascii", 0, 2), "P6", "la tuile de la tour n'est pas unie");
    assert.equal((await map.tile(3, 0, 0))!.toString(), `uni ${BG.join(",")}`);
    assert.equal((await map.tile(0, 0, 0))!.toString(), `uni ${BG.join(",")}`);
    assert.equal((await map.tile(0, tx >> 3, ty >> 3))!.toString("ascii", 0, 2), "P6");
    // Hors pyramide : rien.
    assert.equal(await map.tile(3, 56, 0), null);
    assert.equal(await map.tile(4, 0, 0), null);
    assert.deepEqual(fs.readdirSync(path.join(tmp, "ok")).sort(), ["0", "1", "2", "3"]);
  });

  it("idempotent, et reprise : une carte complète ne retélécharge rien, une tuile perdue est refaite", async () => {
    const map = newMap("reprise");
    const before = downloads;
    await Promise.all([map.prepare(), map.prepare()]);
    await ready(map);
    assert.equal(downloads - before, 2); // un seul passage malgré deux prepare
    await map.prepare();
    await ready(map);
    assert.equal(downloads - before, 2); // déjà complète : rien de retéléchargé
    fs.rmSync(path.join(tmp, "reprise", "3", "0", "0.webp"));
    assert.equal((await map.status()).status, "absent");
    await map.prepare();
    await ready(map);
    assert.ok(await map.tile(3, 0, 0));
  });

  it("des données dont le SHA-256 ne correspond pas sont refusées", async () => {
    const map = newMap("sha", [{ ...FILES[0] }, { ...FILES[1], sha256: "0".repeat(64) }]);
    await map.prepare();
    await until(async () => (await map.status()).status === "error", 60_000);
    assert.match((await map.status()).error!, /buildings\.bin/);
    assert.equal(await map.tile(3, 0, 0), null);
  });

  it("des données dont la taille annoncée n'est pas celle du manifeste sont refusées", async () => {
    const map = newMap("size", [{ ...FILES[0], size: FILES[0].size + 1 }, FILES[1]]);
    await map.prepare();
    await until(async () => (await map.status()).status === "error", 60_000);
    assert.match((await map.status()).error!, /taille/);
  });
});

describe("routes de la carte", () => {
  const steam = fakeSteam();
  let server: TestServer;
  before(async () => {
    server = await startServer({ os: "windows", env: { MATE_STEAM_PATH: steam.steam } });
  });
  after(async () => {
    await server.stop();
    fs.rmSync(steam.steam, { recursive: true, force: true });
  });

  it("GET /cyberpunk/map : état et constantes de calage", async () => {
    const r = await server.call("GET", "/cyberpunk/map");
    assert.equal(r.status, 200);
    assert.equal(r.json.status, "absent");
    assert.equal(r.json.total, 49);
    assert.equal(r.json.tile, 512);
    assert.equal(r.json.levels, 4);
    assert.deepEqual(r.json.size, [28672, 28672]);
    assert.deepEqual(r.json.affine, AFFINE);
  });

  it("tuiles : servies avec un cache long, 404 si absentes, entrées invalides refusées", async () => {
    const file = path.join(server.dataDir, "cyberpunk-blocs", "1", "2", "3.webp");
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, "RIFFwebp");
    const res = await fetch(`${server.url}/cyberpunk/tiles/1/2/3.webp`, { headers: { authorization: "Bearer jeton-de-test" } });
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "image/webp");
    assert.match(res.headers.get("cache-control")!, /max-age=\d{6,}/);
    assert.equal(await res.text(), "RIFFwebp");
    for (const bad of ["1/2/4.webp", "1/14/0.webp", "4/0/0.webp", "0/7/0.webp"])
      assert.equal((await server.call("GET", `/cyberpunk/tiles/${bad}`)).status, 404, bad);
    for (const bad of ["1/2/x.webp", "1/-1/0.webp", "1/..%2F..%2Fidentity/0.webp", "1/2/3.webp%00", "01/2/3.webp"]) {
      const s = (await server.call("GET", `/cyberpunk/tiles/${bad}`)).status;
      assert.ok(s === 400 || s === 404, `${bad} → ${s}`);
    }
  });

  it("sans jeton : refusé", async () => {
    assert.equal((await server.call("GET", "/cyberpunk/tiles/1/2/3.webp", undefined, false)).status, 401);
  });
});

describe("tuile BMP (cwebp de Windows ne lit pas le PPM)", () => {
  it("en-tête 24 bits et lignes du bas vers le haut, en BGR", () => {
    // 2 × 2 : haut = rouge, vert ; bas = bleu, blanc (RGB)
    const rgb = Buffer.from([255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255]);
    const b = bmp(2, 2, rgb);
    assert.equal(b.toString("latin1", 0, 2), "BM");
    const rowSize = 8; // 2 px × 3 octets, complétés à un multiple de 4
    assert.equal(b.readUInt32LE(2), 54 + rowSize * 2);
    assert.equal(b.readUInt32LE(10), 54);
    assert.equal(b.readInt32LE(18), 2);
    assert.equal(b.readInt32LE(22), 2);
    assert.equal(b.readUInt16LE(28), 24);
    // première ligne du fichier = ligne du bas : bleu puis blanc, en BGR
    assert.deepEqual([...b.subarray(54, 60)], [255, 0, 0, 255, 255, 255]);
    // deuxième ligne du fichier = ligne du haut : rouge puis vert, en BGR
    assert.deepEqual([...b.subarray(54 + rowSize, 60 + rowSize)], [0, 0, 255, 0, 255, 0]);
  });
});
