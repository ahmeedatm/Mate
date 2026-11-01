import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

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
});
