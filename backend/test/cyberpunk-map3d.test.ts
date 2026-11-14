import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { gzipSync } from "node:zlib";
import { createMap3d, lightenBuildings, MAP3D_FILES } from "../src/cyberpunk-map3d.ts";
import { fakeBuildings } from "./cyberpunk-blocks-fake.ts";
import { fakeSteam, until } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

const sha256 = (b: Buffer) => createHash("sha256").update(b).digest("hex");
/** 20 boîtes à Watson, de 1 à 20 m de côté : les plus grosses sont gardées. */
const boxes = Array.from({ length: 20 }, (_, i) => ({ x: i * 50, y: 2000, z: 10, w: i + 1, d: i + 1, h: i + 1 }));
const { manifest, bin } = fakeBuildings({ watson: boxes, heywood: [{ x: 0, y: 0, z: 0, w: 5, d: 5, h: 5 }] });
const full = { ...manifest, roads: { file: "roads.bin" }, world: { file: "world.glb" } };
const BODIES: Record<string, Buffer> = {
  "manifest.json": Buffer.from(JSON.stringify(full)),
  "world.glb": Buffer.from("glTF-monde"),
  "roads.bin": Buffer.from("routes"),
  "buildings.bin": bin,
};
const FILES = Object.entries(BODIES).map(([file, b]) => ({ file, sha256: sha256(b), size: b.length }));

let server1: http.Server;
let base: string;
let tmp: string;
let downloads = 0;

before(async () => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mate-map3d-"));
  server1 = http.createServer((req, res) => {
    const body = BODIES[path.basename(req.url ?? "")];
    if (!body) return void res.writeHead(404).end();
    downloads++;
    // Comme raw.githubusercontent.com : le JSON part compressé.
    if (req.url?.endsWith(".json")) {
      const gz = gzipSync(body);
      return void res.writeHead(200, { "content-encoding": "gzip", "content-length": gz.length }).end(gz);
    }
    res.writeHead(200).end(body);
  });
  await new Promise<void>((r) => server1.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server1.address() as AddressInfo).port}`;
});
after(async () => {
  server1.close();
  fs.rmSync(tmp, { recursive: true, force: true });
});

const newMap = (name: string, files = FILES, version = "v1") => createMap3d({ dir: path.join(tmp, name), baseUrl: base, files, version });
const settled = (map: ReturnType<typeof newMap>) => until(async () => (await map.status()).status !== "loading", 20_000);

describe("allègement des bâtiments", () => {
  it("garde la part la plus volumineuse de chaque quartier, au moins une boîte, et récrit le manifeste", () => {
    const out = lightenBuildings(full, bin, 0.1);
    const d = out.manifest.buildings.districts;
    assert.deepEqual(
      d.map((x: { name: string; offset: number; count: number }) => [x.name, x.offset, x.count]),
      [
        ["watson", 0, 2],
        ["heywood", 2, 1],
      ],
    );
    assert.equal(out.manifest.buildings.count, 3);
    assert.equal(out.bin.length, 3 * 16);
    // Les deux plus grosses boîtes de Watson (19 et 20 m), dans leur ordre d'origine.
    assert.deepEqual(out.bin.subarray(0, 32), bin.subarray(18 * 16, 20 * 16));
    // Le reste du manifeste ne bouge pas.
    assert.deepEqual((out.manifest as Record<string, unknown>).roads, full.roads);
  });

  it("des données d'une autre forme sont refusées", () => {
    assert.throws(() => lightenBuildings({ format: "autre" }, bin, 0.1));
    assert.throws(() => lightenBuildings(full, bin.subarray(0, 10), 0.1));
  });
});

describe("données de la carte 3D", () => {
  it("absent, puis ready : fichiers vérifiés, bâtiments allégés, empreintes données à la tablette", async () => {
    const map = newMap("ok");
    assert.equal((await map.status()).status, "absent");
    await map.prepare();
    await settled(map);
    const s = await map.status();
    assert.equal(s.status, "ready");
    assert.deepEqual(Object.keys(s.files).sort(), [...MAP3D_FILES].sort());
    const b = (await map.file("buildings.bin"))!;
    assert.equal(b.length, 3 * 16);
    assert.equal(s.files["buildings.bin"].sha256, sha256(b));
    assert.equal(s.files["buildings.bin"].size, b.length);
    assert.equal(JSON.parse((await map.file("manifest.json"))!.toString()).buildings.count, 3);
    assert.deepEqual(await map.file("world.glb"), BODIES["world.glb"]);
    assert.equal(await map.file("../identity.json"), null);
    assert.equal(await map.file("autre.bin"), null);
  });

  it("une carte prête ne retélécharge rien", async () => {
    const map = newMap("reprise");
    await map.prepare();
    await settled(map);
    const before = downloads;
    await map.prepare();
    await settled(map);
    assert.equal(downloads, before);
  });

  it("une autre version (source ou allègement changés) refait les données", async () => {
    const first = newMap("version");
    await first.prepare();
    await settled(first);
    const map = newMap("version", FILES, "v2");
    assert.equal((await map.status()).status, "absent");
    const before = downloads;
    await map.prepare();
    await settled(map);
    assert.equal((await map.status()).status, "ready");
    assert.equal(downloads - before, FILES.length);
  });

  it("une empreinte fausse est refusée, rien n'est gardé", async () => {
    const map = newMap(
      "sha",
      FILES.map((f) => (f.file === "world.glb" ? { ...f, sha256: "0".repeat(64) } : f)),
    );
    await map.prepare();
    await settled(map);
    const s = await map.status();
    assert.equal(s.status, "error");
    assert.match(s.error!, /world\.glb/);
    assert.equal(await map.file("world.glb"), null);
  });
});

describe("routes de la carte 3D", () => {
  const steam = fakeSteam();
  let server: TestServer;
  before(async () => {
    server = await startServer({ os: "windows", env: { MATE_STEAM_PATH: steam.steam } });
  });
  after(async () => {
    await server.stop();
    fs.rmSync(steam.steam, { recursive: true, force: true });
  });

  it("GET /cyberpunk/map3d : état", async () => {
    const r = await server.call("GET", "/cyberpunk/map3d");
    assert.equal(r.status, 200);
    assert.equal(r.json.status, "absent");
    assert.deepEqual(r.json.files, {});
  });

  it("fichiers : servis s'ils sont là, 404 sinon, noms hors liste refusés", async () => {
    const dir = path.join(server.dataDir, "cyberpunk-map3d");
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, "roads.bin"), "routes");
    const res = await fetch(`${server.url}/cyberpunk/map3d/roads.bin`, { headers: { authorization: "Bearer jeton-de-test" } });
    assert.equal(res.status, 200);
    assert.equal(await res.text(), "routes");
    assert.equal((await server.call("GET", "/cyberpunk/map3d/world.glb")).status, 404);
    for (const bad of ["identity.json", "..%2Fidentity.json", "roads.bin%00"])
      assert.ok([400, 404].includes((await server.call("GET", `/cyberpunk/map3d/${bad}`)).status), bad);
  });

  it("sans jeton : refusé", async () => {
    assert.equal((await server.call("GET", "/cyberpunk/map3d", undefined, false)).status, 401);
  });
});

describe("préparation sans le jeu", () => {
  let server: TestServer;
  before(async () => {
    // Pas de dossier Steam : ni jeu ni téléchargement.
    server = await startServer({ os: "windows", env: { MATE_STEAM_PATH: path.join(os.tmpdir(), "mate-pas-de-steam") } });
  });
  after(() => server.stop());

  it("POST /cyberpunk/map3d/prepare : 409, rien n'est lancé", async () => {
    const r = await server.call("POST", "/cyberpunk/map3d/prepare", {});
    assert.equal(r.status, 409);
    assert.equal((await server.call("GET", "/cyberpunk/map3d")).json.status, "absent");
  });
});
