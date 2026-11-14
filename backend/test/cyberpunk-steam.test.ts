import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, it } from "node:test";
import { gameIn } from "../src/cyberpunk-steam.ts";

const dirs: string[] = [];
const tmp = () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "mate-steam-"));
  dirs.push(d);
  return d;
};
afterEach(() => dirs.splice(0).forEach((d) => fs.rmSync(d, { recursive: true, force: true })));

const put = (file: string, body = "") => {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, body);
};
const vdfPath = (p: string) => p.replace(/\\/g, "\\\\");
const manifest = (lib: string, installdir = "Cyberpunk 2077") =>
  put(
    path.join(lib, "steamapps", "appmanifest_1091500.acf"),
    `"AppState"\n{\n\t"appid"\t\t"1091500"\n\t"installdir"\t\t"${installdir}"\n}\n`,
  );
const libraries = (root: string, ...paths: string[]) =>
  put(
    path.join(root, "steamapps", "libraryfolders.vdf"),
    `"libraryfolders"\n{\n${paths.map((p, i) => `\t"${i}"\n\t{\n\t\t"path"\t\t"${vdfPath(p)}"\n\t}\n`).join("")}}\n`,
  );

describe("gameIn : Cyberpunk 2077 dans une installation de Steam", () => {
  it("jeu dans la bibliothèque de Steam, sans CET", () => {
    const root = tmp();
    manifest(root);
    fs.mkdirSync(path.join(root, "steamapps", "common", "Cyberpunk 2077"), { recursive: true });
    const game = gameIn(root)!;
    assert.equal(game.dir, path.join(root, "steamapps", "common", "Cyberpunk 2077"));
    assert.equal(game.cet, false);
    assert.equal(game.modDir, path.join(game.dir, "bin", "x64", "plugins", "cyber_engine_tweaks", "mods", "mate"));
  });

  it("jeu dans une autre bibliothèque (libraryfolders.vdf), CET reconnu à son .asi", () => {
    const root = tmp();
    const other = tmp();
    libraries(root, root, other);
    manifest(other);
    put(path.join(other, "steamapps", "common", "Cyberpunk 2077", "bin", "x64", "plugins", "cyber_engine_tweaks.asi"));
    const game = gameIn(root)!;
    assert.equal(game.dir, path.join(other, "steamapps", "common", "Cyberpunk 2077"));
    assert.equal(game.cet, true);
  });

  it("CET reconnu à son dossier", () => {
    const root = tmp();
    manifest(root);
    fs.mkdirSync(path.join(root, "steamapps", "common", "Cyberpunk 2077", "bin", "x64", "plugins", "cyber_engine_tweaks"), {
      recursive: true,
    });
    assert.equal(gameIn(root)!.cet, true);
  });

  it("pas de manifeste, ou dossier du jeu absent : null", () => {
    const root = tmp();
    assert.equal(gameIn(root), null);
    manifest(root);
    assert.equal(gameIn(root), null);
  });

  it("un manifeste qui sort de steamapps/common est refusé", () => {
    const root = tmp();
    manifest(root, "..\\..");
    manifest(root, "../..");
    assert.equal(gameIn(root), null);
  });

  it("un installdir « . » ou « .. » est refusé", () => {
    for (const dir of [".", ".."]) {
      const root = tmp();
      manifest(root, dir);
      fs.mkdirSync(path.join(root, "steamapps", "common"), { recursive: true });
      assert.equal(gameIn(root), null, dir);
    }
  });
});
