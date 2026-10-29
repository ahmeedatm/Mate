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
process.env.GITLAB_TOKEN = "";
process.env.ACCOUNTS_FILE = new URL("./accounts-vide.json", import.meta.url).pathname;
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
    sound?: boolean;
    mail?: boolean;
    tiles: { label: string; action: { type: string; name?: string; keys?: string; steps?: { type: string; keys?: string }[] } }[];
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

  it("système : mute-mic bascule la sourdine du micro, dnd bascule Ne pas déranger", async () => {
    reset({ dnd: false });
    await deck.runAction({ type: "system", command: "mute-mic" });
    await deck.runAction({ type: "system", command: "dnd" });
    assert.deepEqual(calls, [
      { fn: "setAudio", args: [{ micMuted: true }] },
      { fn: "setDnd", args: [true] },
    ]);
    reset({ dnd: true });
    await deck.runAction({ type: "system", command: "dnd" });
    assert.deepEqual(calls, [{ fn: "setDnd", args: [false] }]);
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
    assert.deepEqual(await deck.homeFeatures(), {
      agenda: true,
      linked: false,
      meetings: true,
      teams: false,
      agents: false,
      gitlab: false,
      jira: false,
      off: [],
    });
    await deck.addDeckApp("Microsoft Teams");
    assert.equal((await deck.homeFeatures()).teams, true);
  });

  it("agents Claude : seulement si Claude Code est installé", async () => {
    process.env.CLAUDE_BIN = process.execPath;
    assert.equal((await deck.homeFeatures()).agents, true);
    process.env.CLAUDE_BIN = "/inexistant";
    assert.equal((await deck.homeFeatures()).agents, false);
  });

  it("gitlab : jeton GITLAB_TOKEN ou appli OAuth réglée (GITLAB_CLIENT_ID), sinon non", async () => {
    delete process.env.GITLAB_CLIENT_ID;
    process.env.GITLAB_TOKEN = "glpat-de-test";
    assert.equal((await deck.homeFeatures()).gitlab, true);
    process.env.GITLAB_TOKEN = " ";
    assert.equal((await deck.homeFeatures()).gitlab, false);
    process.env.GITLAB_CLIENT_ID = "app-id-de-test";
    assert.equal((await deck.homeFeatures()).gitlab, true);
    delete process.env.GITLAB_CLIENT_ID;
  });
});

describe("suite Microsoft dans les decks de départ", () => {
  const OFFICE = ["Word", "Excel", "PowerPoint", "Outlook"];
  const examples = ["deck.example.json", "deck.example.windows.json"].map((name) => ({
    name,
    deck: JSON.parse(readFileSync(path.join(path.dirname(config.deckExampleFile), name), "utf8")) as typeof deckConfig,
  }));

  for (const { name, deck: example } of examples) {
    it(`${name} : une page par appli, ouverte depuis sa tuile de la page Apps, après les pages d'avant`, () => {
      const appNames = example.pages.find((p) => p.apps)!.tiles.map((t) => t.action.name);
      const titles = example.pages.map((p) => p.title);
      // Ajoutées à la fin : les identifiants des tuiles déjà placées sur la tablette (`page-tuile`) ne bougent pas.
      assert.deepEqual(titles.slice(-OFFICE.length), OFFICE);
      for (const title of OFFICE) {
        const page = example.pages.find((p) => p.title === title)!;
        assert.ok(appNames.includes(page.app), `${title} : ${page.app} dans la page Apps`);
        assert.ok(page.tiles.length > 0);
      }
    });
  }

  for (const { name, deck: example } of examples) {
    it(`${name} : pas de barre Son sur les pages Office, courrier au-dessus des touches d'Outlook seulement`, () => {
      for (const title of OFFICE) {
        const page = example.pages.find((p) => p.title === title)!;
        assert.equal(page.sound, false, `${title} : sound`);
        assert.equal(page.mail === true, title === "Outlook", `${title} : mail`);
      }
    });

    it(`${name} : raccourcis écrits avec des touches connues, sans doublon d'étiquette par page`, () => {
      const modifiers = new Set(["cmd", "ctrl", "alt", "shift"]);
      const named = new Set([
        "return",
        "space",
        "delete",
        "del",
        "insert",
        "home",
        "pageup",
        "pagedown",
        "end",
        "left",
        "right",
        "escape",
        "f2",
        "f5",
        "f7",
        "f9",
        "f11",
        "f12",
      ]);
      for (const title of OFFICE) {
        const page = example.pages.find((p) => p.title === title)!;
        const labels = page.tiles.map((t) => t.label);
        assert.equal(new Set(labels).size, labels.length, `${title} : étiquettes en double`);
        const steps = page.tiles.flatMap((t) => (t.action.type === "macro" ? t.action.steps! : [t.action]));
        for (const step of steps.filter((s) => s.type === "keys")) {
          const parts = step.keys!.split("+");
          const key = parts.pop()!;
          assert.ok(
            parts.every((m) => modifiers.has(m)),
            `${title} : ${step.keys}`,
          );
          assert.ok(key.length === 1 || named.has(key), `${title} : touche ${key} de ${step.keys}`);
        }
      }
    });
  }

  it("pages ordinaires (pas seulement en appel) quand les applis sont installées", async () => {
    reset({ installed: baseApps });
    const pages = await deck.loadDeck();
    for (const title of OFFICE) {
      const page = pages.find((p) => p.title === title)!;
      assert.equal(page.callOnly, undefined, title);
      // `sound` et `mail` de la config arrivent jusqu'à la page, pour la route /deck.
      assert.equal(page.sound, false, title);
      assert.equal(page.mail === true, title === "Outlook", title);
    }
  });
});
