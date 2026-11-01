import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

type TileView = {
  id: string;
  label: string;
  type: string;
  app: string | null;
  symbol?: string | null;
  glyph?: string | null;
  state?: string | null;
  hold?: boolean;
};
type PageView = { title: string; apps: boolean; editable: boolean; app: string | null; sound: boolean; tiles: TileView[] };

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server?.stop();
});
beforeEach(async () => {
  server.setOs({});
  await server.call("DELETE", "/deck/apps");
  server.clearOsCalls();
});

const pages = async (): Promise<PageView[]> => (await server.call("GET", "/deck")).json.pages;
const tileByLabel = async (label: string) => (await pages()).flatMap((p) => p.tiles).find((t) => t.label === label)!;

describe("GET /deck", () => {
  it("donne les pages, la page Perso en dernier avec ses emplacements vides", async () => {
    const list = await pages();
    const perso = list.at(-1)!;
    assert.equal(perso.title, "Perso");
    assert.equal(perso.editable, true);
    assert.equal(perso.tiles.length, 14);
    assert.ok(perso.tiles.every((t) => t.type === "empty" && t.label === ""));
    assert.deepEqual(list.find((p) => p.apps)!.tiles, [], "aucune appli installée : page Apps vide");
  });

  it("les tuiles du deck de départ portent leur symbole (icône de la tablette), l'emoji reste en secours", async () => {
    const lock = await tileByLabel("Verrouiller");
    assert.equal(lock.symbol, "lock");
    assert.ok(lock.glyph, "emoji gardé pour les tablettes d'avant les symboles");
    assert.equal((await tileByLabel("Couper le micro")).symbol, "mic_off");
    assert.equal((await tileByLabel("Ne pas déranger")).symbol, "dnd");
  });

  it("les touches Micro et Ne pas déranger du deck de départ montrent leur état réel", async () => {
    assert.equal((await tileByLabel("Couper le micro")).state, "mic-muted");
    assert.equal((await tileByLabel("Ne pas déranger")).state, "dnd");
    assert.equal((await tileByLabel("Verrouiller")).state, null, "une touche sans état n'en reçoit pas");
  });

  it("`sound` : barre Son voulue par défaut sur la page Discord", async () => {
    const list = await pages();
    assert.equal(list.find((p) => p.title === "Discord")!.sound, true);
  });

  it("page Apps : applis installées, avec leur nom", async () => {
    server.setOs({ installed: ["Notes", "Microsoft Word"] });
    const apps = (await pages()).find((p) => p.apps)!;
    assert.deepEqual(
      apps.tiles.map((t) => [t.label, t.type, t.app]),
      [["Notes", "app", "Notes"]],
    );
  });
});

describe("POST /deck/run", () => {
  it("exécute l'action de la tuile, et rien pour une tuile inconnue", async () => {
    const tile = await tileByLabel("Capture zone");
    assert.equal((await server.call("POST", "/deck/run", { id: tile.id })).status, 200);
    assert.equal((await server.call("POST", "/deck/run", { id: "99-0" })).status, 404);
    assert.deepEqual(server.osCalls(), [{ fn: "sendKeys", args: ["cmd+shift+4"] }]);
  });

  it("appli à plusieurs fenêtres : renvoie les fenêtres à choisir au lieu de l'activer", async () => {
    server.setOs({ installed: ["Notes"], windows: { Notes: [{ title: "Courses" }, { title: "Idées" }] } });
    const { json } = await server.call("POST", "/deck/run", { id: (await tileByLabel("Notes")).id });
    assert.deepEqual(
      json.windows.windows.map((w: { title: string }) => w.title),
      ["Courses", "Idées"],
    );
    assert.deepEqual(server.osCalls(), []);
  });

  it("appli à une fenêtre : l'active", async () => {
    server.setOs({ installed: ["Notes"], windows: { Notes: [{ title: "Courses" }] } });
    await server.call("POST", "/deck/run", { id: (await tileByLabel("Notes")).id });
    assert.deepEqual(server.osCalls(), [{ fn: "launchApp", args: ["Notes"] }]);
  });
});

describe("POST /deck/run avec hold (appui long)", () => {
  it("lance la seconde action de la touche, pas la principale", async () => {
    const tile = await tileByLabel("Capture zone");
    assert.equal(tile.hold, true);
    assert.equal((await server.call("POST", "/deck/run", { id: tile.id, hold: true })).status, 200);
    const calls = server.osCalls();
    assert.ok(calls.length > 0 && !calls.some((c) => c.fn === "sendKeys"), "la touche principale ne part pas");
  });

  it("refuse (409) une touche sans seconde action, et rien ne part", async () => {
    const tile = await tileByLabel("Verrouiller");
    assert.ok(!tile.hold);
    assert.equal((await server.call("POST", "/deck/run", { id: tile.id, hold: true })).status, 409);
    assert.deepEqual(server.osCalls(), []);
  });
});

describe("page Apps depuis la tablette", () => {
  it("refuse une appli absente de l'ordinateur", async () => {
    const { status, json } = await server.call("POST", "/deck/apps", { name: "Appli inconnue" });
    assert.equal(status, 404);
    assert.match(json.error, /introuvable/);
  });

  it("ajoute une appli installée, la retire, puis revient au profil de base", async () => {
    server.setOs({ installed: ["Notes", "Spotify"] });
    const added = await server.call("POST", "/deck/apps", { name: "Spotify" });
    assert.equal(added.status, 200);
    let apps = (await pages()).find((p) => p.apps)!;
    assert.deepEqual(
      apps.tiles.map((t) => t.app),
      ["Notes", "Spotify"],
    );

    await server.call("DELETE", `/deck/apps/${added.json.id}`);
    await server.call("DELETE", `/deck/apps/${apps.tiles[0].id}`);
    apps = (await pages()).find((p) => p.apps)!;
    assert.deepEqual(apps.tiles, []);

    await server.call("DELETE", "/deck/apps");
    apps = (await pages()).find((p) => p.apps)!;
    assert.deepEqual(
      apps.tiles.map((t) => t.app),
      ["Notes"],
    );
  });

  it("commande vocale « ouvre X » : seulement une appli installée", async () => {
    server.setOs({ installed: ["Spotify"] });
    assert.equal((await server.call("POST", "/apps/launch", { name: "Inconnue" })).status, 404);
    assert.equal((await server.call("POST", "/apps/launch", { name: "Spotify" })).status, 200);
    assert.deepEqual(server.osCalls(), [{ fn: "launchApp", args: ["Spotify"] }]);
  });
});

describe("page Média : sites", () => {
  it("ajoute un site tapé à la main, refuse une adresse invalide, le retire", async () => {
    const initial = await server.call("GET", "/deck/links");
    assert.deepEqual(initial.json.links, []);
    assert.ok(initial.json.suggestions.some((s: { label: string }) => s.label === "YouTube"));

    const added = await server.call("POST", "/deck/links", { url: "netflix.com" });
    assert.equal(added.status, 200);
    const bad = await server.call("POST", "/deck/links", { url: "ftp://serveur" });
    assert.equal(bad.status, 400);
    assert.equal(bad.json.error, "Adresse invalide (ex. netflix.com)");

    let { json } = await server.call("GET", "/deck/links");
    assert.deepEqual(json.links, [{ label: "netflix.com", url: "https://netflix.com", id: added.json.id }]);
    await server.call("DELETE", `/deck/links/${added.json.id}`);
    ({ json } = await server.call("GET", "/deck/links"));
    assert.deepEqual(json.links, []);
  });
});

describe("cartes de l'Accueil et onglets d'appli", () => {
  it("/deck/features : Teams seulement s'il est dans la page Apps", async () => {
    assert.deepEqual((await server.call("GET", "/deck/features")).json, {
      agenda: true,
      linked: false,
      meetings: true,
      teams: false,
      agents: false,
      gitlab: false,
      github: false,
      jira: false,
      ha: false,
      off: [],
    });
    server.setOs({ installed: ["Microsoft Teams"] });
    assert.equal((await server.call("GET", "/deck/features")).json.teams, true);
  });

  it("/deck/live : applis des pages d'appli ouvertes, et celles en appel", async () => {
    server.setOs({ installed: ["Microsoft Teams", "Discord"], running: ["Discord"], inCall: ["Microsoft Teams"] });
    await server.call("POST", "/deck/apps", { name: "Discord" });
    const { json } = await server.call("GET", "/deck/live");
    assert.deepEqual(json, { apps: ["Microsoft Teams"], open: ["Microsoft Teams", "Discord"] });
  });

  it("/deck/live : appli hors de la page Apps, onglet seulement pendant un appel", async () => {
    server.setOs({ installed: ["Discord"], running: ["Discord"] });
    assert.deepEqual((await server.call("GET", "/deck/live")).json, { apps: [], open: [] }, "ouverte mais pas en appel");
    server.setOs({ installed: ["Discord"], running: ["Discord"], inCall: ["Discord"] });
    assert.deepEqual((await server.call("GET", "/deck/live")).json, { apps: ["Discord"], open: ["Discord"] });
  });
});

describe("fenêtres de l'appli d'une tuile", () => {
  it("liste, active et place les fenêtres ; 404 pour une tuile qui n'est pas une appli", async () => {
    server.setOs({ installed: ["Notes"], windows: { Notes: [{ title: "Courses" }] } });
    const notes = await tileByLabel("Notes");
    const list = await server.call("GET", `/deck/windows/${notes.id}`);
    assert.equal(list.json.windows[0].title, "Courses");
    assert.equal((await server.call("GET", `/deck/windows/${(await tileByLabel("Capture zone")).id}`)).status, 404);

    await server.call("POST", `/deck/windows/${notes.id}`, { action: "focus" });
    await server.call("POST", `/deck/windows/${notes.id}`, { action: "focus", window: { index: 0, title: "Courses" } });
    await server.call("POST", `/deck/windows/${notes.id}`, { action: "place", display: 1, layout: "right" });
    assert.deepEqual(server.osCalls(), [
      { fn: "launchApp", args: ["Notes"] },
      { fn: "focusWindow", args: ["Notes", { index: 0, title: "Courses" }] },
      { fn: "placeWindow", args: ["Notes", { display: 1, layout: "right" }] },
    ]);
  });

  it("/deck/catalog : applis installées et écrans, pour l'éditeur de touche", async () => {
    server.setOs({ installed: ["Notes"] });
    assert.deepEqual((await server.call("GET", "/deck/catalog")).json, { os: "mac", apps: ["Notes"], shortcuts: [], displays: ["Écran"] });
  });
});
