import "./fake-os-hook.ts";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { beforeEach, describe, it } from "node:test";
import type { Tile } from "../src/deck.ts";
import { calls, state, type FakeState } from "./fake-os.ts";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-deck-"));
process.env.DATA_DIR = dir;
process.env.CALENDAR_ICS_URLS = "";
process.env.CLAUDE_BIN = "/inexistant";
const { config } = await import("../src/config.ts");
const deck = await import("../src/deck.ts");
const { saveKey } = await import("../src/perso.ts");

// Deck lu par le serveur : config/deck.json s'il existe, sinon le deck de départ.
const deckFile = existsSync(config.deckFile) ? config.deckFile : config.deckExampleFile;
const deckConfig = JSON.parse(readFileSync(deckFile, "utf8")) as {
  pages: {
    title: string;
    apps?: boolean;
    app?: string;
    media?: boolean;
    tiles: { label: string; action: { type: string; name?: string } }[];
  }[];
};
const baseApps = deckConfig.pages.find((p) => p.apps)!.tiles.map((t) => t.action.name!);
const appTileId = (name: string) => "a-" + createHash("sha1").update(name).digest("hex").slice(0, 10);

function reset(next: FakeState = {}) {
  for (const key of Object.keys(state)) delete state[key as keyof FakeState];
  Object.assign(state, next);
  calls.length = 0;
}

beforeEach(async () => {
  reset();
  await fs.rm(path.join(dir, "apps.json"), { force: true });
  await fs.rm(path.join(dir, "links.json"), { force: true });
  await fs.rm(path.join(dir, "keys.json"), { force: true });
});

describe("runAction", () => {
  it("passe chaque étape à la fonction de pilotage correspondante", async () => {
    await deck.runAction({ type: "app", name: "Notes" });
    await deck.runAction({ type: "keys", keys: "cmd+shift+4", app: "Notes" });
    await deck.runAction({ type: "shortcut", name: "Mode concentration" });
    await deck.runAction({ type: "media", command: "next" });
    await deck.runAction({ type: "text", text: "Bonjour" });
    await deck.runAction({ type: "system", command: "lock" });
    await deck.runAction({ type: "url", url: "https://example.com" });
    assert.deepEqual(calls, [
      { fn: "launchApp", args: ["Notes"] },
      { fn: "sendKeys", args: ["cmd+shift+4", "Notes"] },
      { fn: "runShortcut", args: ["Mode concentration"] },
      { fn: "sendMediaKey", args: ["next"] },
      { fn: "typeText", args: ["Bonjour"] },
      { fn: "systemCommand", args: ["lock"] },
      { fn: "openTarget", args: ["https://example.com"] },
    ]);
  });

  it("enchaîne les étapes d'une macro dans l'ordre", async () => {
    await deck.runAction({
      type: "macro",
      steps: [
        { type: "keys", keys: "cmd+n" },
        { type: "wait", ms: 1 },
        { type: "text", text: "Compte rendu" },
      ],
    });
    assert.deepEqual(
      calls.map((c) => c.fn),
      ["sendKeys", "typeText"],
    );
  });

  it("s'arrête à la première étape qui échoue", async () => {
    reset({ fail: ["sendKeys"] });
    await assert.rejects(
      deck.runAction({
        type: "macro",
        steps: [
          { type: "keys", keys: "cmd+n" },
          { type: "text", text: "jamais" },
        ],
      }),
    );
    assert.deepEqual(calls, []);
  });

  it("refuse les schémas d'URL hors liste sans rien ouvrir", async () => {
    for (const url of ["file:///etc/passwd", "javascript:alert(1)", "ssh://serveur", "/Applications/Calculator.app"]) {
      await assert.rejects(deck.runAction({ type: "url", url }), /Schéma d'URL non autorisé/);
    }
    await deck.runAction({ type: "url", url: "msteams://teams.microsoft.com/l/meetup-join/x" });
    assert.deepEqual(
      calls.map((c) => c.args[0]),
      ["msteams://teams.microsoft.com/l/meetup-join/x"],
    );
  });

  it("appuie sur le premier bouton trouvé, erreur si aucun", async () => {
    reset({ buttons: { Discord: ["Couper le micro", "Déconnecter"] } });
    await deck.runAction({ type: "press", app: "Discord", names: ["Mute", "Couper le micro"] });
    assert.deepEqual(calls, [{ fn: "pressButton", args: ["Discord", "Couper le micro"] }]);
    await assert.rejects(deck.runAction({ type: "press", app: "Discord", names: ["Raccrocher"] }), /Bouton introuvable dans Discord/);
  });

  it("attend un bouton qui apparaît (panneau ouvert par l'étape d'avant), jusqu'à waitMs", async () => {
    reset({ buttons: { "Microsoft Teams": [] } });
    setTimeout(() => (state.buttons = { "Microsoft Teams": ["Écran : Built-in Retina Display"] }), 100);
    await deck.runAction({ type: "press", app: "Microsoft Teams", names: ["Écran : Built-in Retina Display"], waitMs: 2_000 });
    assert.deepEqual(calls, [{ fn: "pressButton", args: ["Microsoft Teams", "Écran : Built-in Retina Display"] }]);
    await assert.rejects(deck.runAction({ type: "press", app: "Microsoft Teams", names: ["Quitter"], waitMs: 400 }), /Bouton introuvable/);
  });

  it("place les fenêtres d'une appli déjà ouverte sur l'écran nommé, sans la relancer", async () => {
    reset({ windows: { Notes: [{ title: "Notes" }] } });
    await deck.runAction({ type: "window", app: "Notes", display: "ÉCRAN", layout: "left" });
    assert.deepEqual(calls, [{ fn: "placeWindow", args: ["Notes", { display: 1, layout: "left" }] }]);
  });

  it("lance l'appli sans fenêtre visible, puis la place ; écran inconnu : on garde le sien", async () => {
    reset({ windows: { Notes: [{ title: "Notes", elsewhere: true }] }, onLaunch: { Notes: [{ title: "Notes" }] } });
    await deck.runAction({ type: "window", app: "Notes", display: "Moniteur débranché" });
    assert.deepEqual(calls, [
      { fn: "launchApp", args: ["Notes"] },
      { fn: "placeWindow", args: ["Notes", { display: undefined, layout: "keep" }] },
    ]);
  });

  it("refuse une action inconnue", async () => {
    await assert.rejects(deck.runAction({ type: "inconnue" } as never), /Action inconnue/);
  });
});

describe("loadDeck", () => {
  it("page Apps : profil de base réduit aux applis installées", async () => {
    reset({ installed: [baseApps[1], baseApps[0], "Autre appli"] });
    const apps = (await deck.loadDeck()).find((p) => p.apps)!;
    assert.deepEqual(
      apps.tiles.map((t) => t.id),
      [appTileId(baseApps[0]), appTileId(baseApps[1])],
    );
  });

  it("pages d'appli absentes de la page Apps : gardées pour les appels seulement (callOnly)", async () => {
    const appPages = deckConfig.pages.filter((p) => p.app);
    assert.ok(appPages.length > 0, "le deck de départ a des pages d'appli");
    const callOnly = async (title: string) => (await deck.loadDeck()).find((p) => p.title === title)?.callOnly;
    for (const page of appPages) assert.equal(await callOnly(page.title), true, `${page.title} pour les appels seulement`);

    // Ajoutée depuis la tablette : page ordinaire.
    await deck.addDeckApp(appPages[0].app!);
    assert.equal(await callOnly(appPages[0].title), undefined);

    // Tuiles numérotées d'après leur place dans le fichier, pages masquées comprises.
    const last = deckConfig.pages.length - 1;
    const lastPage = (await deck.loadDeck()).find((p) => p.title === deckConfig.pages[last].title)!;
    assert.equal(lastPage.tiles[0].id, `${last}-0`);
  });

  it("page Perso en dernier : 14 emplacements, une étape seule ou une macro", async () => {
    await saveKey(2, { label: "Capture", steps: [{ type: "keys", keys: "cmd+shift+4" }] });
    await saveKey(3, {
      label: "Réunion",
      color: "#112233",
      steps: [
        { type: "app", name: "Notes" },
        { type: "text", text: "Ordre du jour" },
      ],
    });
    const perso = (await deck.loadDeck()).at(-1)!;
    assert.equal(perso.title, "Perso");
    assert.equal(perso.editable, true);
    assert.equal(perso.tiles.length, 14);
    assert.deepEqual(perso.tiles[0], { id: "k-0", empty: true });
    assert.deepEqual(perso.tiles[2], {
      id: "k-2",
      label: "Capture",
      glyph: undefined,
      color: undefined,
      action: { type: "keys", keys: "cmd+shift+4" },
    });
    assert.deepEqual((perso.tiles[3] as Tile).action, {
      type: "macro",
      steps: [
        { type: "app", name: "Notes" },
        { type: "text", text: "Ordre du jour" },
      ],
    });
    assert.equal((await deck.findTile("k-3"))?.label, "Réunion");
    assert.equal(await deck.findTile("k-0"), undefined, "un emplacement vide n'est pas une tuile");
  });
});

describe("page Apps modifiée depuis la tablette", () => {
  it("ajoute une appli (nom sans « Microsoft »), une seule fois, puis la retire", async () => {
    reset({ installed: [baseApps[0]] });
    const id = await deck.addDeckApp("Microsoft PowerPoint");
    assert.equal(await deck.addDeckApp("Microsoft PowerPoint"), id);
    let apps = (await deck.loadDeck()).find((p) => p.apps)!.tiles as Tile[];
    assert.deepEqual(
      apps.map((t) => t.label),
      [deckConfig.pages.find((p) => p.apps)!.tiles[0].label, "PowerPoint"],
    );

    await deck.removeDeckApp(id);
    apps = (await deck.loadDeck()).find((p) => p.apps)!.tiles as Tile[];
    assert.deepEqual(
      apps.map((t) => t.id),
      [appTileId(baseApps[0])],
    );
  });

  it("retour au profil de base", async () => {
    await deck.addDeckApp("Microsoft PowerPoint");
    await deck.resetDeckApps();
    assert.deepEqual((await deck.loadDeck()).find((p) => p.apps)!.tiles, []);
  });
});

describe("page Média : sites", () => {
  it("complète l'adresse tapée et en tire le nom", async () => {
    await deck.addDeckLink("netflix.com");
    await deck.addDeckLink("https://www.youtube.com/");
    await deck.addDeckLink("https://music.youtube.com/watch?list=LM", "  Mes titres  ");
    assert.deepEqual(
      (await deck.deckLinks()).map(({ label, url }) => ({ label, url })),
      [
        { label: "netflix.com", url: "https://netflix.com" },
        { label: "youtube.com", url: "https://www.youtube.com" },
        { label: "Mes titres", url: "https://music.youtube.com/watch?list=LM" },
      ],
    );
  });

  it("n'ajoute pas deux fois le même site, et le retire par son identifiant", async () => {
    const id = await deck.addDeckLink("https://netflix.com");
    assert.equal(await deck.addDeckLink("netflix.com"), id);
    assert.equal((await deck.deckLinks()).length, 1);
    await deck.removeDeckLink(id);
    assert.deepEqual(await deck.deckLinks(), []);
  });

  it("refuse ce qui n'est pas une adresse web", async () => {
    await assert.rejects(deck.addDeckLink("ftp://serveur/fichier"), /Adresse web attendue/);
    await assert.rejects(deck.addDeckLink("file:///etc/passwd"), /Adresse web attendue/);
    assert.deepEqual(await deck.deckLinks(), []);
  });
});

describe("homeFeatures", () => {
  it("bouton Rejoindre de Teams seulement si Teams est dans la page Apps", async () => {
    assert.deepEqual(await deck.homeFeatures(), { agenda: true, linked: false, meetings: true, teams: false, agents: false, jira: false });
    await deck.addDeckApp("Microsoft Teams");
    assert.equal((await deck.homeFeatures()).teams, true);
  });

  it("agents Claude : seulement si Claude Code est installé", async () => {
    process.env.CLAUDE_BIN = process.execPath;
    assert.equal((await deck.homeFeatures()).agents, true);
    process.env.CLAUDE_BIN = "/inexistant";
    assert.equal((await deck.homeFeatures()).agents, false);
  });
});
