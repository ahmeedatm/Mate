import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import Fastify from "fastify";
import { readPng, writePng } from "../src/png-shrink.ts";
import { until } from "./cyberpunk-fakemod.ts";

// Les routes lisent dataDir au chargement du module : un dossier à part, jamais le vrai `.data`.
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mate-phone-"));
process.env.DATA_DIR = path.join(tmp, "data");
const { ATLASES, FONTS, createPhone, fetchZip, phoneRoutes } = await import("../src/cyberpunk-phone.ts");

/** Image générée : une bande verticale par pièce, de couleur propre (i + 1, 2·(i + 1), 3·(i + 1)), à moitié transparente. */
function fakeAtlas(parts: string[]) {
  const width = parts.length * 4;
  const height = 8;
  const rows = Buffer.alloc(height * (width * 4 + 1));
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      const i = Math.floor(x / 4) + 1;
      rows.set([i, 2 * i, 3 * i, 128], y * (width * 4 + 1) + 1 + x * 4);
    }
  const json = {
    Data: {
      RootChunk: {
        slots: {
          Elements: [
            {
              parts: parts.map((p, n) => ({
                partName: { $type: "CName", $storage: "string", $value: p },
                clippingRectInUVCoords: { Left: n / parts.length, Top: 0, Right: (n + 1) / parts.length, Bottom: 1 },
              })),
            },
          ],
        },
      },
    },
  };
  return { png: writePng(width, height, rows), json: JSON.stringify(json) };
}

const written = (work: string, rel: string, data: Buffer | string) => {
  fs.mkdirSync(path.dirname(path.join(work, rel)), { recursive: true });
  fs.writeFileSync(path.join(work, rel), data);
};

/** Faux outil : fait ce que WolvenKit fait (arborescence du jeu sous out/, PNG à plat sous png/) avec des images générées. */
const fakeTools = (opts: { fail?: boolean; skipPart?: string } = {}) => ({
  async extract(_game: string, work: string) {
    if (opts.fail) throw new Error("WolvenKit a planté : C:\\secret\\chemin");
    for (const a of ATLASES) {
      const parts = a.parts.filter((p) => p !== opts.skipPart);
      const img = fakeAtlas(parts);
      written(work, `png/${a.name}_1080p.png`, img.png);
      written(work, `out/base/gameplay/gui/${a.name}.inkatlas.json`, img.json);
    }
    for (const f of FONTS) written(work, `out/base/fonts/${f}.ttf`, `ttf-${f}`);
  },
});

const newPhone = (name: string, tools = fakeTools(), game: string | null = "C:\\jeu") =>
  createPhone({ dir: path.join(tmp, name), gameDir: () => game, tools: async () => tools });
const settle = (p: ReturnType<typeof newPhone>, want: string) =>
  until(async () => (await p.status()).status === want, 20_000).catch(async (e) => {
    throw new Error(`${e.message} : ${JSON.stringify(await p.status())}`);
  });

after(() => fs.rmSync(tmp, { recursive: true, force: true }));

describe("extraction du téléphone du jeu", () => {
  it("absent, loading, ready ; une PNG par pièce découpée d'après l'inkatlas, transparence gardée, polices copiées", async () => {
    const p = newPhone("ok");
    assert.deepEqual(await p.status(), { status: "absent", assets: [] });
    await p.prepare();
    await settle(p, "ready");
    const s = await p.status();
    const names = [...ATLASES.flatMap((a) => a.parts.map((x) => `${x}.png`)), ...FONTS.map((f) => `${f}.ttf`)];
    assert.deepEqual([...s.assets].sort(), [...names].sort());
    assert.ok(s.assets.includes("frame_main.png") && s.assets.includes("msgBuble_bg.png") && s.assets.includes("raj-bold.ttf"));
    // frame_main : première bande de atlas_phone (couleur 1, 2, 3, alpha 128), 4 × 8 px.
    const png = readPng((await p.asset("frame_main.png"))!)!;
    assert.deepEqual([png.width, png.height], [4, 8]);
    assert.deepEqual([...png.rgba.subarray(0, 4)], [1, 2, 3, 128]);
    // La pièce suivante vient de la bande suivante.
    const second = readPng((await p.asset("frame_message.png"))!)!;
    assert.deepEqual([...second.rgba.subarray(0, 4)], [2, 4, 6, 128]);
    assert.equal((await p.asset("raj-bold.ttf"))!.toString(), "ttf-raj-bold");
    // Rien d'autre que les pièces dans le dossier.
    assert.deepEqual(fs.readdirSync(path.join(tmp, "ok")).sort(), [...names, "assets.json"].sort());
  });

  it("liste blanche : seul un nom de la liste est servi", async () => {
    const p = newPhone("blanche");
    await p.prepare();
    await settle(p, "ready");
    fs.writeFileSync(path.join(tmp, "blanche", "intrus.png"), "x");
    assert.equal(await p.asset("intrus.png"), null);
    assert.equal(await p.asset("../ok/frame_main.png"), null);
    assert.equal(await p.asset("assets.json"), null);
    assert.ok(await p.asset("circle.png"));
  });

  it("prepare deux fois = un seul passage ; déjà prêt = rien refait", async () => {
    let calls = 0;
    const tools = fakeTools();
    const counting = { extract: (g: string, w: string) => (calls++, tools.extract(g, w)) };
    const p = newPhone("idem", counting);
    await Promise.all([p.prepare(), p.prepare()]);
    await settle(p, "ready");
    await p.prepare();
    await settle(p, "ready");
    assert.equal(calls, 1);
  });

  it("outil en échec : error avec une phrase, sans chemin ni détail technique", async () => {
    const p = newPhone("echec", fakeTools({ fail: true }));
    await p.prepare();
    await settle(p, "error");
    const s = await p.status();
    assert.match(s.error!, /téléphone/);
    assert.doesNotMatch(s.error!, /secret|WolvenKit/);
    assert.deepEqual(s.assets, []);
    assert.equal(await p.asset("frame_main.png"), null);
  });

  it("pièce absente de l'inkatlas : error, rien servi", async () => {
    const p = newPhone("manque", fakeTools({ skipPart: "icon_nc" }));
    await p.prepare();
    await settle(p, "error");
    assert.match((await p.status()).error!, /icon_nc/);
    assert.equal(await p.asset("frame_main.png"), null);
  });

  it("sans jeu : prepare refuse", async () => {
    const p = newPhone("sansjeu", fakeTools(), null);
    await p.prepare();
    await settle(p, "error");
    assert.match((await p.status()).error!, /introuvable/);
  });

  it("après une erreur, un nouvel essai repart", async () => {
    let fail = true;
    const tools = fakeTools();
    const p = newPhone("retry", { extract: (g, w) => (fail ? Promise.reject(new Error("x")) : tools.extract(g, w)) });
    await p.prepare();
    await settle(p, "error");
    fail = false;
    await p.prepare();
    await settle(p, "ready");
  });
});

describe("téléchargement des outils", () => {
  let server: http.Server;
  let url: string;
  const body = Buffer.from("faux zip");
  before(async () => {
    server = http.createServer((req, res) => {
      if (req.url === "/ko") return void res.writeHead(404).end();
      res.writeHead(200).end(body);
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    url = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });
  after(() => server.close());
  const sha = createHash("sha256").update(body).digest("hex");

  it("zip conforme : rendu", async () => {
    assert.deepEqual(await fetchZip({ url, size: body.length, sha256: sha }), body);
  });
  it("SHA-256 faux : refusé", async () => {
    await assert.rejects(fetchZip({ url, size: body.length, sha256: "0".repeat(64) }), /refusé/);
  });
  it("taille différente : refusé", async () => {
    await assert.rejects(fetchZip({ url, size: body.length + 1, sha256: sha }), /refusé/);
  });
  it("erreur HTTP : refusé", async () => {
    await assert.rejects(fetchZip({ url: `${url}/ko`, size: 1, sha256: sha }), /refusé/);
  });
});

describe("routes du téléphone", () => {
  const app = Fastify();
  const dir = path.join(process.env.DATA_DIR!, "cyberpunk-phone");
  before(async () => {
    await phoneRoutes(app);
    await app.ready();
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, "frame_main.png"), "PNGDATA");
    fs.writeFileSync(path.join(dir, "raj-bold.ttf"), "TTFDATA");
    fs.writeFileSync(path.join(dir, "intrus.png"), "x");
    fs.writeFileSync(path.join(dir, "assets.json"), JSON.stringify(["frame_main.png", "raj-bold.ttf"]));
  });
  after(() => app.close());

  it("GET /cyberpunk/phone : état et liste", async () => {
    const r = await app.inject({ method: "GET", url: "/cyberpunk/phone" });
    assert.equal(r.statusCode, 200);
    assert.deepEqual(r.json(), { status: "ready", assets: ["frame_main.png", "raj-bold.ttf"] });
  });

  it("GET /cyberpunk/phone/:name : type, cache long, 404 hors liste, nom invalide refusé", async () => {
    const png = await app.inject({ method: "GET", url: "/cyberpunk/phone/frame_main.png" });
    assert.equal(png.statusCode, 200);
    assert.equal(png.headers["content-type"], "image/png");
    assert.match(String(png.headers["cache-control"]), /max-age=\d{6,}/);
    assert.equal(png.body, "PNGDATA");
    const ttf = await app.inject({ method: "GET", url: "/cyberpunk/phone/raj-bold.ttf" });
    assert.equal(ttf.headers["content-type"], "font/ttf");
    assert.equal((await app.inject({ method: "GET", url: "/cyberpunk/phone/intrus.png" })).statusCode, 404);
    assert.equal((await app.inject({ method: "GET", url: "/cyberpunk/phone/absent.png" })).statusCode, 404);
    for (const bad of ["..%2Fassets.json", "a.exe", "a.png%00", "x".repeat(41) + ".png", "assets.json"])
      assert.equal((await app.inject({ method: "GET", url: `/cyberpunk/phone/${bad}` })).statusCode, 400, bad);
  });

  it("POST /cyberpunk/phone/prepare : 409 sans jeu", async () => {
    const r = await app.inject({ method: "POST", url: "/cyberpunk/phone/prepare", payload: {} });
    assert.equal(r.statusCode, 409);
  });
});
