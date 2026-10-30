import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { plistString, unentity } from "../src/mac/notifications.ts";

// Extraction des textes d'une notification du Mac depuis le plist XML (plutil -convert xml1). JSON est impossible : une
// notification contient une date que « plutil -convert json » refuse ; on lit donc le XML. Voir src/mac/notifications.ts.

const plist = (body: string) =>
  `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "">\n<plist version="1.0">\n<dict>\n${body}\n</dict>\n</plist>`;

describe("notifications du Mac : lecture du plist XML", () => {
  it("extrait titre, sous-titre et corps du sous-dictionnaire req", () => {
    const xml = plist(
      `<key>date</key><date>2026-10-30T10:00:00Z</date>\n<key>req</key><dict>\n<key>titl</key><string>Teams</string>\n<key>subt</key><string>Marie Dupont</string>\n<key>body</key><string>Réunion déplacée</string>\n</dict>`,
    );
    assert.equal(plistString(xml, "titl"), "Teams");
    assert.equal(plistString(xml, "subt"), "Marie Dupont");
    assert.equal(plistString(xml, "body"), "Réunion déplacée");
  });

  it("clé absente : chaîne vide", () => {
    const xml = plist(`<key>req</key><dict><key>titl</key><string>Rappel</string></dict>`);
    assert.equal(plistString(xml, "body"), "");
  });

  it("texte multiligne et entités décodées", () => {
    const xml = plist(`<key>req</key><dict><key>body</key><string>a &lt; b &amp; c\nligne 2 &#128512;</string></dict>`);
    assert.equal(plistString(xml, "body"), "a < b & c\nligne 2 😀");
  });

  it("décode les entités standard et numériques, laisse l'UTF-8 brut", () => {
    assert.equal(unentity("Tom &amp; Léa &lt;3 &#x1F600;"), "Tom & Léa <3 😀");
    assert.equal(unentity("rien à décoder"), "rien à décoder");
  });
});
