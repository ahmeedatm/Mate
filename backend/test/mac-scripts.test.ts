import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

/** Fonctions pures des scripts JXA du Mac (src/mac/keys.ts, dnd.ts), reprises du texte et exécutées ici, sans osascript. */
const src = (file: string) => fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "mac", file), "utf8");
const fn = (source: string, name: string) => {
  const start = source.indexOf(`function ${name}(`);
  return new Function(`${source.slice(start, source.indexOf("\n}\n", start) + 2)}\nreturn ${name};`)();
};

describe("touches envoyées au Mac", () => {
  const keyFlags = fn(src("keys.ts"), "keyFlags") as (code: number) => number;

  it("pose fn et pavé numérique sur les flèches, sans quoi Ctrl + haut n'ouvre pas Mission Control", () => {
    for (const code of [123, 124, 125, 126]) assert.equal(keyFlags(code), 0x800000 | 0x200000);
  });

  it("pose fn sur les touches F, comme le clavier du Mac", () => {
    assert.equal(keyFlags(122), 0x800000);
    assert.equal(keyFlags(111), 0x800000);
  });

  it("ne touche pas aux autres touches", () => {
    for (const code of [0, 21, 36, 49, 53]) assert.equal(keyFlags(code), 0);
  });
});

describe("Ne pas déranger sur Mac", () => {
  const details = fn(src("dnd.ts"), "details") as (actions: { name: () => string }[]) => { name: () => string } | undefined;
  const actions = (...names: string[]) => names.map((n) => ({ name: () => n }));

  it("ouvre le détail de la Concentration parmi les actions de macOS 26", () => {
    const list = actions("AXIncrement", "AXDecrement", "AXDelete", "AXShowMenu", "AXCancel", "AXPress", "Name:afficher les détails");
    assert.equal(details(list)?.name(), "Name:afficher les détails");
  });

  it("garde le comportement de macOS 15 : la seule action autre qu'AXPress", () => {
    assert.equal(details(actions("AXPress", "Afficher les détails"))?.name(), "Afficher les détails");
  });
});
