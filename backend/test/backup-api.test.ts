import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, TOKEN, type TestServer } from "./server.ts";

// Page locale, section Sauvegarde : GET /local/config/backup rend un fichier avec les pages et touches, applis et sites,
// plugins et disposition de la tablette (jamais un jeton) ; POST le relit en ne reprenant que les parties choisies.

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-backup-"));
const write = (name: string, data: unknown) => fs.writeFileSync(path.join(dataDir, name), JSON.stringify(data));
const KEY = { label: "Micro", steps: [{ type: "system", command: "mute-mic" }] };
write("keys.json", { slots: [KEY] });
write("pages.json", { pages: [{ title: "Réunions", slots: [KEY] }] });
write("apps.json", { apps: [{ label: "Notes", name: "Notes" }] });
write("links.json", { links: [{ label: "Deezer", url: "https://www.deezer.com" }] });
write("plugins.json", { choices: { teams: false } });
write("github.json", { token: "ghp_secret-de-test" });
write("accounts.json", { accounts: [{ token: "secret-de-compte" }] });

let server: TestServer;
before(async () => {
  server = await startServer({ dataDir });
  server.setOs({ installed: ["Notes"] });
});
after(async () => {
  await server.stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

const local = (method: string, route: string, body?: unknown) => server.call(method, route, body, false);
const read = (name: string) => JSON.parse(fs.readFileSync(path.join(dataDir, name), "utf8"));

describe("sauvegarde de la configuration", () => {
  it("exporte touches, pages, applis, sites et plugins, sans aucun jeton", async () => {
    const res = await local("GET", "/local/config/backup");
    assert.equal(res.status, 200);
    assert.equal(res.json.format, "mate-config-1");
    assert.deepEqual(Object.keys(res.json.parts).sort(), ["apps", "keys", "links", "pages", "plugins"]);
    assert.equal(res.json.parts.keys.slots[0].label, "Micro");
    assert.equal(res.json.parts.pages.pages[0].title, "Réunions");
    const text = JSON.stringify(res.json);
    assert.ok(!text.includes("ghp_secret-de-test") && !text.includes("secret-de-compte"));
  });

  it("ne reprend que les parties choisies", async () => {
    const backup = (await local("GET", "/local/config/backup")).json;
    write("keys.json", { slots: [] });
    write("links.json", { links: [] });
    const res = await local("POST", "/local/config/backup", { backup, parts: ["keys"] });
    assert.equal(res.status, 200);
    assert.deepEqual(res.json.imported, ["keys"]);
    assert.equal(read("keys.json").slots[0].label, "Micro");
    assert.deepEqual(read("links.json").links, []);
  });

  it("reprend les plugins et les applis, et dit que la tablette éteinte attend", async () => {
    const backup = (await local("GET", "/local/config/backup")).json;
    backup.parts.layout = { layout: { names: ["Accueil"], widgets: [] }, topBar: ["clock"], rail: { labels: true, tools: [] } };
    write("plugins.json", { choices: {} });
    const res = await local("POST", "/local/config/backup", { backup, parts: ["plugins", "apps", "layout"] });
    assert.equal(res.status, 200);
    assert.deepEqual(res.json.imported, ["plugins", "apps"]);
    assert.match(res.json.skipped.layout, /Tablette/);
    assert.equal(read("plugins.json").choices.teams, false);
    assert.equal(read("apps.json").apps[0].name, "Notes");
  });

  it("laisse de côté les applis absentes de cet ordinateur, et les champs en trop", async () => {
    const backup = (await local("GET", "/local/config/backup")).json;
    backup.parts.apps.apps.push({ label: "Fantôme", name: "Fantôme" });
    const res = await local("POST", "/local/config/backup", { backup, parts: ["apps"] });
    assert.equal(res.status, 200);
    assert.match(res.json.notes.apps, /1 appli/);
    assert.deepEqual(read("apps.json"), { apps: [{ label: "Notes", name: "Notes" }] });
    backup.parts.keys.extra = "pirate";
    await local("POST", "/local/config/backup", { backup, parts: ["keys"] });
    assert.deepEqual(Object.keys(read("keys.json")), ["slots"]);
  });

  it("refuse un fichier d'un autre format ou une touche invalide", async () => {
    const bad = await local("POST", "/local/config/backup", { backup: { format: "autre", parts: {} }, parts: ["keys"] });
    assert.equal(bad.status, 400);
    const backup = { format: "mate-config-1", parts: { keys: { slots: [{ label: "", steps: [] }] } } };
    const res = await local("POST", "/local/config/backup", { backup, parts: ["keys"] });
    assert.equal(res.status, 400);
  });

  it("refuse une partie absente du fichier", async () => {
    const backup = { format: "mate-config-1", parts: {} };
    const res = await local("POST", "/local/config/backup", { backup, parts: ["keys"] });
    assert.equal(res.status, 400);
  });

  it("refuse une requête qui n'est pas de la page locale, même avec le jeton (DNS rebinding)", async () => {
    const { port } = new URL(server.url);
    const withHost = (method: string, body?: string) =>
      new Promise<number>((resolve, reject) => {
        const req = http.request(
          {
            host: "127.0.0.1",
            port,
            path: "/local/config/backup",
            method,
            headers: { host: `exemple.com:${port}`, authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
          },
          (res) => {
            res.resume();
            resolve(res.statusCode ?? 0);
          },
        );
        req.on("error", reject);
        req.end(body);
      });
    assert.equal(await withHost("GET"), 403);
    assert.equal(await withHost("POST", JSON.stringify({ backup: { format: "mate-config-1", parts: {} }, parts: ["keys"] })), 403);
    assert.equal((await local("GET", "/local/config/backup")).status, 200);
  });

  it("refuse une étape qui lance autre chose qu'un nom d'appli ou de raccourci", async () => {
    const key = (step: object) => ({ format: "mate-config-1", parts: { keys: { slots: [{ label: "X", steps: [step] }] } } });
    const before = JSON.stringify(read("keys.json"));
    for (const step of [
      { type: "app", name: "/Applications/Evil.app" },
      { type: "app", name: "Notes.app" },
      { type: "app", name: "..\\evil" },
      { type: "shortcut", name: "dossier/raccourci" },
      { type: "shortcut", name: "Evil.APP" },
    ]) {
      const res = await local("POST", "/local/config/backup", { backup: key(step), parts: ["keys"] });
      assert.equal(res.status, 400, JSON.stringify(step));
    }
    assert.equal(JSON.stringify(read("keys.json")), before);
    const ok = await local("POST", "/local/config/backup", { backup: key({ type: "app", name: "Notes" }), parts: ["keys"] });
    assert.equal(ok.status, 200);
  });

  it("tout ou rien : une partie invalide après une partie valide n'écrit rien", async () => {
    write("keys.json", { slots: [KEY] });
    write("links.json", { links: [] });
    const push = {
      label: "Maintenir",
      steps: [
        { type: "keys", keys: "cmd+c", push: true },
        { type: "media", command: "next" },
      ],
    };
    const backup = {
      format: "mate-config-1",
      parts: {
        keys: { slots: [] },
        links: { links: [{ label: "A", url: "https://a.example" }] },
        pages: { pages: [{ title: "P", slots: [push] }] },
      },
    };
    const res = await local("POST", "/local/config/backup", { backup, parts: ["keys", "links", "pages"] });
    assert.equal(res.status, 400);
    assert.equal(read("keys.json").slots.length, 1);
    assert.deepEqual(read("links.json").links, []);
  });

  it("normalise et dédoublonne les sites comme à l'ajout depuis la tablette", async () => {
    const links = [
      { label: "", url: "https://Exemple.COM/" },
      { label: "Doublon", url: "https://exemple.com" },
      { label: "  Page  ", url: "https://exemple.com/a?b=1" },
    ];
    const res = await local("POST", "/local/config/backup", {
      backup: { format: "mate-config-1", parts: { links: { links } } },
      parts: ["links"],
    });
    assert.equal(res.status, 200);
    assert.deepEqual(read("links.json").links, [
      { label: "exemple.com", url: "https://exemple.com" },
      { label: "Page", url: "https://exemple.com/a?b=1" },
    ]);
  });

  it("borne la disposition : 20 profils, 200 widgets", async () => {
    const layout = (names: unknown[], widgets: unknown[]) => ({
      format: "mate-config-1",
      parts: { layout: { layout: { names, widgets }, topBar: [], rail: { labels: true, tools: [] } } },
    });
    const widget = { t: "clock", s: "L", p: 0, c: 0, r: 0, cfg: {} };
    const tooManyNames = layout(Array(21).fill("P"), []);
    assert.equal((await local("POST", "/local/config/backup", { backup: tooManyNames, parts: ["layout"] })).status, 400);
    const tooManyWidgets = layout(["P"], Array(201).fill(widget));
    assert.equal((await local("POST", "/local/config/backup", { backup: tooManyWidgets, parts: ["layout"] })).status, 400);
  });
});
