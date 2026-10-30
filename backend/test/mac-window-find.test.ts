import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

/**
 * Recherche d'une fenêtre dans le script JXA du Mac (src/mac/windows.ts) : les fonctions pures `suffix`, `sameTitle`
 * et `find` sont reprises du texte du script et exécutées ici, sans osascript.
 */
const source = fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "mac", "windows.ts"), "utf8");
const script = source.slice(source.indexOf("const SCRIPT = `") + 16).replaceAll("\\`", "`");
const fn = (name: string) => {
  const start = script.indexOf(`function ${name}(`);
  return script.slice(start, script.indexOf("\n}\n", start) + 2);
};

type Entry = { index: number; w: { name: () => string } };
type Target = { index: number; title: string };
const find = new Function(`${fn("suffix")}\n${fn("sameTitle")}\n${fn("find")}\nreturn find;`)() as (
  list: Entry[],
  target: Target,
  strict?: boolean,
) => Entry | undefined;

const win = (index: number, title: string): Entry => ({ index, w: { name: () => title } });
const list = [win(1, "Téléchargements"), win(2, "Réglages du compagnon"), win(3, "deck.ts — mate")];

describe("fenêtre visée sur Mac", () => {
  it("retrouve la fenêtre par son titre, y compris raccourci par le menu Fenêtre", () => {
    assert.equal(find(list, { index: 9, title: "Réglages du compagnon" })?.index, 2);
    assert.equal(find([win(1, "Page - Google Chrome – Profil")], { index: 0, title: "Page" }, true)?.index, 1);
  });

  it("fermeture : jamais une autre fenêtre quand le titre n'est plus là", () => {
    assert.equal(find(list, { index: 2, title: "Onglet qui a changé" }, true), undefined);
    assert.equal(find(list, { index: 0, title: "README.md — mate" }, true), undefined);
  });

  it("autres gestes : le rang ou la fin du titre servent encore de repli", () => {
    assert.equal(find(list, { index: 2, title: "Onglet qui a changé" })?.index, 2);
    assert.equal(find(list, { index: 0, title: "README.md — mate" })?.index, 3);
  });
});
