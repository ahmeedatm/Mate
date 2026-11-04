import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { beforeEach, describe, it } from "node:test";

// Chaque point d'entrée qui envoie un ordre AppleScript demande d'abord la permission à `automation` : porte fermée,
// il refuse (ou rend vide) sans lancer osascript. Un faux `osascript` sert de témoin : il note ce qu'on lui envoie.
const bin = fs.mkdtempSync(path.join(os.tmpdir(), "mate-osascript-"));
const log = path.join(bin, "calls.log");
fs.writeFileSync(
  path.join(bin, "osascript"),
  `#!/bin/sh
printf '%s\\n' "$*" >> "${log}"
case "$*" in
  *'kind: "player"'*) echo '[{"kind":"player","app":"Spotify","state":"playing","title":"Titre","artist":"Artiste","album":"","pos":1,"dur":100000}]' ;;
  *'"op":"list"'*) echo '{"running":true,"windows":[],"displays":[]}' ;;
  *) echo '[]' ;;
esac
`,
  { mode: 0o755 },
);
process.env.PATH = `${bin}:${process.env.PATH}`;

const { automation } = await import("../src/mac/permissions.ts");
const { setDnd } = await import("../src/mac/dnd.ts");
const { systemCommand } = await import("../src/mac/system.ts");
const { controlOpenWindow, listOpenWindows } = await import("../src/mac/windows.ts");
const { browserTabs } = await import("../src/mac/browser.ts");
const { mediaControl, mediaSessions } = await import("../src/mac/media.ts");

const sent = () => (fs.existsSync(log) ? fs.readFileSync(log, "utf8") : "");

describe("porte des ordres AppleScript fermée", () => {
  beforeEach(() => fs.rmSync(log, { force: true }));

  it("Ne pas déranger : refusé avec la phrase, sans osascript", async (t) => {
    t.mock.method(automation, "allowed", async () => false);
    await assert.rejects(setDnd(true), /Autorise Mate à piloter System Events/);
    assert.equal(sent(), "");
  });

  it("mode sombre : refusé avec la phrase, sans osascript", async (t) => {
    t.mock.method(automation, "allowed", async () => false);
    await assert.rejects(systemCommand("toggle-dark-mode"), /Autorise Mate à piloter System Events/);
    assert.equal(sent(), "");
  });

  it("fenêtre d'une appli ouverte : refusée avec la phrase, sans osascript", async (t) => {
    t.mock.method(automation, "allowed", async () => false);
    await assert.rejects(
      controlOpenWindow("com.apple.Safari", { index: 0 } as never, "focus" as never),
      /Autorise Mate à piloter System Events/,
    );
    assert.equal(sent(), "");
  });

  it("vue « Ouvertes » : aucune appli, la liste des fenêtres n'est pas demandée", async (t) => {
    t.mock.method(automation, "allowed", async () => false);
    const open = await listOpenWindows();
    assert.deepEqual(open.apps, []);
    assert.ok(!sent().includes('"op":"all"'));
  });

  it("onglets du navigateur : liste vide, sans osascript", async (t) => {
    t.mock.method(automation, "allowed", async () => false);
    assert.deepEqual(await browserTabs(), []);
    assert.equal(sent(), "");
  });

  it("lecteur Spotify : refusé avec la phrase, sans ordre vers l'appli", async (t) => {
    const allowed = t.mock.method(automation, "allowed", async () => true);
    // Sur un vrai Mac, MediaRemote peut décrire lui-même Spotify : l'identifiant diffère, la cible est la même.
    const spotify = (await mediaSessions()).find((s) => ["Spotify", "com.spotify.client"].includes(s.app));
    assert.ok(spotify);
    allowed.mock.mockImplementation(async () => false);
    fs.rmSync(log, { force: true });
    await assert.rejects(
      mediaControl("play-pause", spotify.app),
      /Autorise Mate à piloter Spotify : page de l'ordinateur, Autorisations du Mac\./,
    );
    assert.equal(sent(), "");
  });
});
