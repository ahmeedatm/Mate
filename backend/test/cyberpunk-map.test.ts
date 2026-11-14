import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { AFFINE, createMap, type Converter } from "../src/cyberpunk-map.ts";
import { fakeSteam, until } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

const gitSha1 = (b: Buffer) => createHash("sha1").update(`blob ${b.length}\0`).update(b).digest("hex");
/** Fausse « image webp » : un octet (la couleur) répété ; le faux convertisseur en tire un PPM de la bonne taille. */
const A = Buffer.alloc(64, 200);
const B = Buffer.alloc(64, 90);
const FILES = [
  { file: "map_14_14.webp", sha1: gitSha1(A), size: A.length },
  { file: "map_45_14.webp", sha1: gitSha1(B), size: B.length },
];
const BODIES: Record<string, Buffer> = { "map_14_14.webp": A, "map_45_14.webp": B };

let http1: http.Server;
let base: string;
let tmp: string;
let decodes = 0;

const ppm = (w: number, h: number, data: Buffer) => Buffer.concat([Buffer.from(`P6\n${w} ${h}\n255\n`), data]);
/** Convertisseur en Node pur : décode = unie de la couleur du fichier ; encode = recopie du PPM (le « webp » est le PPM). */
const fake: Converter = {
  async decode(src, size, out) {
    decodes++;
    const color = fs.readFileSync(src)[0];
    fs.writeFileSync(out, ppm(size, size, Buffer.alloc(size * size * 3, color)));
  },
  async encode(input, out) {
    fs.copyFileSync(input, out);
  },
};
const header = (b: Buffer) => b.toString("ascii", 0, 15).split("\n").slice(0, 2).join(" ");
/** Attend « ready » ; en cas d'échec, le message dit l'état. */
const ready = (map: ReturnType<typeof newMap>) =>
  until(async () => (await map.status()).status === "ready", 20_000).catch(async (e) => {
    throw new Error(`${e.message} : ${JSON.stringify(await map.status())}`);
  });
const newMap = (name: string, files = FILES) => createMap({ dir: path.join(tmp, name), baseUrl: base, files, converter: async () => fake });

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mate-map-"));
  http1 = http.createServer((req, res) => {
    const body = BODIES[path.basename(req.url ?? "")];
    if (!body) return void res.writeHead(404).end();
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
  it("calage : l'affine du niveau max vient des constantes mesurées", () => {
    const k = 2048 / 7936;
    assert.deepEqual(AFFINE, [10.788 * k, 0, 61008 * k, 0, -10.788 * k, 46934 * k]);
  });

  it("absent, puis loading, puis ready ; sources effacées ; tuiles aux bonnes places", async () => {
    const map = newMap("ok");
    const before = await map.status();
    assert.deepEqual([before.status, before.done, before.total], ["absent", 0, 2]);
    await map.prepare();
    await ready(map);
    const s = await map.status();
    assert.deepEqual([s.done, s.total], [2, 2]);
    // z3 : l'image A (colonne 0) couvre x 0..3, B (colonne 1) x 4..7 ; tuile de 512 px.
    const a = (await map.tile(3, 2, 1))!;
    assert.equal(header(a), "P6 512 512");
    assert.equal(a[a.length - 1], 200);
    assert.equal((await map.tile(3, 5, 0))!.at(-1), 90);
    // z1 : une tuile par image ; z0 : 4 images de 256 px, A en haut à gauche, B à sa droite, le reste noir.
    assert.equal((await map.tile(1, 1, 0))!.at(-1), 90);
    const z0 = (await map.tile(0, 0, 0))!;
    const px = (x: number, y: number) => z0[z0.length - 512 * 512 * 3 + (y * 512 + x) * 3];
    assert.deepEqual([px(0, 0), px(255, 255), px(256, 0), px(0, 256)], [200, 200, 90, 0]);
    // Hors pyramide ou pas encore faite : rien.
    assert.equal(await map.tile(3, 56, 0), null);
    assert.equal(await map.tile(4, 0, 0), null);
    assert.equal(await map.tile(3, 40, 40), null);
    assert.deepEqual(fs.readdirSync(path.join(tmp, "ok", "src")), []);
  });

  it("idempotent, et reprise : une carte complète ne retélécharge rien, une tuile perdue est refaite", async () => {
    const map = newMap("reprise");
    const decodesBefore = decodes;
    await Promise.all([map.prepare(), map.prepare()]);
    await ready(map);
    assert.equal(decodes - decodesBefore, 2); // un seul passage malgré deux prepare
    await map.prepare();
    await ready(map);
    assert.equal(decodes - decodesBefore, 2); // déjà complète : rien de retéléchargé
    fs.rmSync(path.join(tmp, "reprise", "3", "0", "0.webp"));
    assert.equal((await map.status()).status, "absent");
    await map.prepare();
    await ready(map);
    assert.ok(await map.tile(3, 0, 0));
  });

  it("une image dont le SHA-1 ne correspond pas est refusée", async () => {
    const map = newMap("sha", [{ ...FILES[0], sha1: "0".repeat(40) }, FILES[1]]);
    await map.prepare();
    await until(async () => (await map.status()).status === "error", 60_000);
    const s = await map.status();
    assert.match(s.error!, /map_14_14\.webp/);
    assert.equal(await map.tile(3, 0, 0), null);
    assert.deepEqual(fs.readdirSync(path.join(tmp, "sha", "src")), []);
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
    assert.equal(r.json.total, 196);
    assert.equal(r.json.tile, 512);
    assert.equal(r.json.levels, 4);
    assert.deepEqual(r.json.size, [28672, 28672]);
    assert.deepEqual(r.json.affine, AFFINE);
  });

  it("tuiles : servies avec un cache long, 404 si absentes, entrées invalides refusées", async () => {
    const file = path.join(server.dataDir, "cyberpunk-map", "1", "2", "3.webp");
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
