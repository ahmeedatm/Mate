import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

// Lanceur Mac et agent figé (portable/mac) : binaires gardés dans le dépôt, compilés sur le Mac par `npm run build:launcher`.
// macOS lie les autorisations à l'empreinte de l'agent : un source ou un Info.plist changé sans recompiler, ou une version de
// release dans son Info.plist, ferait redonner toutes les autorisations ou garderait un binaire périmé.

const mac = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "portable", "mac");
const backend = path.resolve(mac, "..", "..");
const agent = path.join(mac, "agent", "Mate.app", "Contents");
const plist = fs.readFileSync(path.join(agent, "Info.plist"), "utf8");
const value = (key: string) => new RegExp(`<key>${key}</key>\\s*<string>([^<]*)</string>`).exec(plist)?.[1];

describe("agent figé", () => {
  it("le bundle gardé correspond au source Swift, à l'Info.plist et à l'icône (sinon : npm run build:launcher)", () => {
    const hash = createHash("sha256");
    for (const file of [
      path.join(mac, "launcher", "Launcher.swift"),
      path.join(agent, "Info.plist"),
      path.join(backend, "assets", "compagnon.icns"),
    ]) {
      hash.update(fs.readFileSync(file));
    }
    const noted = fs.readFileSync(path.join(mac, "agent", ".source-sha256"), "utf8").trim();
    assert.equal(
      hash.digest("hex"),
      noted,
      "l'agent n'est plus à jour : lancer `npm run build:launcher` (les Mac devront redonner leurs autorisations)",
    );
  });

  it("l'icône de l'agent est celle de l'appli", () => {
    assert.ok(
      fs
        .readFileSync(path.join(agent, "Resources", "compagnon.icns"))
        .equals(fs.readFileSync(path.join(backend, "assets", "compagnon.icns"))),
    );
  });

  it("l'Info.plist n'a aucune version de release : l'agent garde les mêmes octets d'une version à l'autre", () => {
    assert.equal(value("CFBundleVersion"), "1");
    assert.equal(value("CFBundleShortVersionString"), "1");
  });

  it("identité Mate, sans icône dans le Dock, avec les textes d'autorisation en français", () => {
    assert.equal(value("CFBundleIdentifier"), "app.mate.agent");
    assert.equal(value("CFBundleName"), "Mate");
    assert.equal(value("CFBundleDisplayName"), "Mate");
    assert.equal(value("CFBundleDevelopmentRegion"), "fr");
    assert.equal(value("LSMinimumSystemVersion"), "11.0");
    assert.match(plist, /<key>LSUIElement<\/key>\s*<true\/>/);
    for (const key of ["NSAppleEventsUsageDescription", "NSMicrophoneUsageDescription", "NSLocalNetworkUsageDescription"]) {
      assert.ok((value(key) ?? "").length > 20, key);
    }
  });

  it("l'agent est signé et l'exécutable de l'appli est le même binaire sans signature de bundle", () => {
    assert.ok(fs.existsSync(path.join(agent, "_CodeSignature", "CodeResources")));
    const exe = fs.statSync(path.join(agent, "MacOS", "Mate"));
    assert.ok(exe.mode & 0o111, "agent non exécutable");
    assert.ok(fs.statSync(path.join(mac, "launcher", "Mate")).mode & 0o111, "lanceur non exécutable");
    // Mach-O universel (fat) : 0xCAFEBABE.
    for (const file of [path.join(agent, "MacOS", "Mate"), path.join(mac, "launcher", "Mate")]) {
      assert.equal(fs.readFileSync(file).subarray(0, 4).toString("hex"), "cafebabe", file);
    }
  });
});
