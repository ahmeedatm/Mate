import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { isNewer, pickInstaller } from "../src/app-update.ts";
import { isLegacyMacAgent, isLegacyRun, macLaunchAgent, windowsRunCommand } from "../src/portable.ts";

// Appli portable : commandes de démarrage, reconnaissance de l'ancienne installation, choix de l'installeur annoncé par la tablette.

const sha = "a".repeat(64);
const entry = (over: Record<string, unknown> = {}) => ({
  os: "windows",
  file: "Mate-Windows.exe",
  size: 23_000_000,
  sha256: sha,
  version: "1.3.0",
  ...over,
});

describe("lancement au démarrage", () => {
  it("Windows : l'exe entre guillemets puis --demarrage (pas de page à l'ouverture de session)", () => {
    assert.equal(
      windowsRunCommand("C:\\Users\\Eliot\\Mes outils\\Compagnon.exe"),
      '"C:\\Users\\Eliot\\Mes outils\\Compagnon.exe" --demarrage',
    );
  });

  it("Mac : agent lancé à l'ouverture de session, sans relance launchd (la boucle du lanceur s'en charge)", () => {
    const plist = macLaunchAgent("app.mate.demarrage", "/Users/a&b/Mate.app/Contents/MacOS/Mate");
    assert.match(plist, /<key>RunAtLoad<\/key>\s*<true\/>/);
    assert.match(plist, /<string>\/Users\/a&amp;b\/Mate\.app\/Contents\/MacOS\/Mate<\/string>\s*<string>--demarrage<\/string>/);
    assert.ok(!plist.includes("KeepAlive"));
  });
});

describe("ancienne installation", () => {
  it("Mac : l'agent du paquet installé, pas celui du dépôt (npm run service:install)", () => {
    assert.ok(isLegacyMacAgent("<string>/Applications/Mate.app/Contents/Resources/backend/node/darwin-arm64/node</string>"));
    assert.ok(!isLegacyMacAgent("<string>/Users/a/dev/mate/backend/node_modules/.bin/tsx</string>"));
  });

  it("Windows : la clé Run de l'ancien lanceur avec le node embarqué, pas celle du dépôt ni la nouvelle", () => {
    const legacy =
      '"C:\\Users\\a\\AppData\\Local\\Programs\\Mate\\backend\\src\\win\\Mate.exe" --node "C:\\Users\\a\\AppData\\Local\\Programs\\Mate\\backend\\node\\node.exe" --backend "C:\\x\\backend" --logs l --data d';
    assert.ok(isLegacyRun(legacy));
    assert.ok(
      !isLegacyRun('"C:\\dev\\backend\\src\\win\\Mate.exe" --node "C:\\Program Files\\nodejs\\node.exe" --backend "C:\\dev\\backend"'),
    );
    assert.ok(!isLegacyRun(windowsRunCommand("C:\\x\\Compagnon.exe")));
  });
});

describe("mise à jour depuis la tablette", () => {
  it("prend l'installeur du système demandé", () => {
    const json = { installers: [entry({ os: "mac", file: "Mate-Mac.zip" }), entry()] };
    assert.equal(pickInstaller(json, "windows")?.file, "Mate-Windows.exe");
    assert.equal(pickInstaller(json, "mac")?.file, "Mate-Mac.zip");
    assert.equal(pickInstaller({ installers: [entry()] }, "mac"), undefined);
  });

  it("ignore une entrée incomplète ou au nom de fichier suspect", () => {
    for (const bad of [
      entry({ file: "../evil.exe" }),
      entry({ file: "dossier\\evil.exe" }),
      entry({ file: ".cache" }),
      entry({ sha256: "zz" }),
      entry({ size: 0 }),
      entry({ size: 600 * 1024 * 1024 }),
      entry({ version: undefined }),
    ]) {
      assert.equal(pickInstaller({ installers: [bad] }, "windows"), undefined, JSON.stringify(bad));
    }
    assert.equal(pickInstaller({}, "windows"), undefined);
    assert.equal(pickInstaller(null, "windows"), undefined);
  });

  it("la version proposée doit être strictement plus récente", () => {
    assert.ok(isNewer("1.3.0", "1.2.9"));
    assert.ok(isNewer("0.10.0", "0.9.5"));
    assert.ok(!isNewer("1.2.0", "1.2.0"));
    assert.ok(!isNewer("1.1.9", "1.2.0"));
  });

  it("lancé depuis le dépôt (dev) ou version illisible : jamais de mise à jour", () => {
    assert.ok(!isNewer("1.3.0", "dev"));
    assert.ok(!isNewer("beta", "1.0.0"));
  });
});
