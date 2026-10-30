import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { applyEssai, applyUpdate, backendState, restoreOrigin, type Layout } from "../src/essai-backend.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-essai-test-"));
after(() => fs.rmSync(tmp, { recursive: true, force: true }));

function write(root: string, files: Record<string, string>) {
  for (const [file, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), content);
  }
}

const read = (root: string, file: string) => fs.readFileSync(path.join(root, file), "utf8");
const exists = (root: string, file: string) => fs.existsSync(path.join(root, file));

/** Backend d'une version `v` : ce que `npm run package -- essai` met dans l'archive (sans node ni lanceur). */
function files(platform: Layout["platform"], v: string, extra: Record<string, string> = {}) {
  return {
    "dist/server.mjs": `serveur ${v}`,
    "config/calls.json": `calls ${v}`,
    ...(platform === "windows" ? { "src/win/UnowhyHelper.exe": `pilote ${v}` } : { "src/mac/NowPlaying.dylib": `dylib ${v}` }),
    ...extra,
  };
}

function archive(platform: Layout["platform"], v: string, extra: Record<string, string> = {}) {
  const dir = fs.mkdtempSync(path.join(tmp, "src-"));
  write(dir, files(platform, v, extra));
  const out = path.join(fs.mkdtempSync(path.join(tmp, "tgz-")), "backend.tar.gz");
  execFileSync("tar", ["-czf", out, "-C", dir, "."]);
  return fs.readFileSync(out);
}

/** Compagnon installé par paquet, en version d'origine, avec ce qui est à l'ordinateur (node, lanceur, deck, jeton). */
function install(platform: Layout["platform"]): Layout {
  const dir = fs.mkdtempSync(path.join(tmp, "install-"));
  const root = path.join(dir, "backend");
  write(
    root,
    files(platform, "origine", {
      "dist/ancien.mjs": "fichier qui n'est plus dans l'essai",
      "config/deck.json": "deck de l'ordinateur",
      "config/tasks.json": "tâches de l'ordinateur",
      "node/node.exe": "node verrouillé",
      ...(platform === "windows" ? { "src/win/UnoMate.exe": "lanceur verrouillé" } : {}),
      ".env": "COMPANION_TOKEN=secret",
    }),
  );
  return { root, dataDir: path.join(dir, "data"), platform, packaged: true };
}

describe("essai du backend d'une MR", () => {
  for (const platform of ["mac", "windows"] as const) {
    it(`${platform} : pose le backend de la MR, garde l'ordinateur tel quel, puis restaure l'origine`, () => {
      const layout = install(platform);
      const tgz = archive(platform, "mr109");
      applyEssai(tgz, 109, undefined, layout);

      const sha256 = createHash("sha256").update(tgz).digest("hex");
      assert.deepEqual(backendState(layout), { packaged: true, mode: "essai", mr: 109, sha256 });
      assert.equal(read(layout.root, "dist/server.mjs"), "serveur mr109");
      assert.equal(read(layout.root, "config/calls.json"), "calls mr109");
      assert.equal(exists(layout.root, "dist/ancien.mjs"), false, "fichier absent de l'essai retiré");
      // À l'ordinateur : ni node, ni lanceur, ni deck, ni jeton.
      assert.equal(read(layout.root, "config/deck.json"), "deck de l'ordinateur");
      assert.equal(read(layout.root, "config/tasks.json"), "tâches de l'ordinateur");
      assert.equal(read(layout.root, "node/node.exe"), "node verrouillé");
      if (platform === "windows") assert.equal(read(layout.root, "src/win/UnoMate.exe"), "lanceur verrouillé");
      assert.equal(read(layout.root, ".env"), "COMPANION_TOKEN=secret");
      // Copie d'origine : le backend installé, sans les données de l'ordinateur.
      const copy = path.join(layout.dataDir, "backend-origin");
      assert.equal(read(copy, "dist/server.mjs"), "serveur origine");
      assert.equal(exists(copy, "config/deck.json"), false);
      assert.equal(exists(copy, "node"), false);

      assert.equal(restoreOrigin(layout), true);
      assert.deepEqual(backendState(layout), { packaged: true, mode: "origin", mr: null, sha256: null });
      assert.equal(read(layout.root, "dist/server.mjs"), "serveur origine");
      assert.equal(read(layout.root, "dist/ancien.mjs"), "fichier qui n'est plus dans l'essai");
      assert.equal(read(layout.root, "config/calls.json"), "calls origine");
      assert.equal(read(layout.root, "config/deck.json"), "deck de l'ordinateur");
      assert.equal(read(layout.root, "node/node.exe"), "node verrouillé");
      assert.equal(exists(layout.dataDir, "backend-origin"), false, "copie supprimée avec le retour");
    });
  }

  it("un second essai ne remplace pas la copie d'origine", () => {
    const layout = install("windows");
    applyEssai(archive("windows", "mr109"), 109, undefined, layout);
    applyEssai(archive("windows", "mr110"), 110, undefined, layout);
    assert.equal(backendState(layout).mr, 110);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur mr110");
    assert.equal(read(layout.dataDir, "backend-origin/dist/server.mjs"), "serveur origine");
    restoreOrigin(layout);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur origine");
  });

  it("MR republiée : l'empreinte de la nouvelle archive remplace l'ancienne, la copie d'origine reste", () => {
    const layout = install("mac");
    applyEssai(archive("mac", "mr109 pipeline 1"), 109, undefined, layout);
    const republished = archive("mac", "mr109 pipeline 2");
    applyEssai(republished, 109, undefined, layout);
    assert.equal(backendState(layout).sha256, createHash("sha256").update(republished).digest("hex"));
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur mr109 pipeline 2");
    assert.equal(read(layout.dataDir, "backend-origin/dist/server.mjs"), "serveur origine");
  });

  it("une nouvelle copie d'origine est prise à l'essai suivant le retour (compagnon mis à jour entre-temps)", () => {
    const layout = install("mac");
    applyEssai(archive("mac", "mr109"), 109, undefined, layout);
    restoreOrigin(layout);
    write(layout.root, { "dist/server.mjs": "serveur mis à jour" });
    applyEssai(archive("mac", "mr110"), 110, undefined, layout);
    restoreOrigin(layout);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur mis à jour");
  });

  it("compagnon réinstallé pendant un essai : la marque et la copie d'origine périmées sont oubliées", () => {
    const layout = install("mac");
    applyEssai(archive("mac", "mr109"), 109, undefined, layout);
    write(layout.root, { "dist/server.mjs": "serveur du nouvel installeur" });
    assert.deepEqual(backendState(layout), { packaged: true, mode: "origin", mr: null, sha256: null });
    assert.equal(restoreOrigin(layout), false);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur du nouvel installeur", "pas remplacé par l'ancienne copie");
    assert.equal(exists(layout.dataDir, "backend-origin"), false);
    applyEssai(archive("mac", "mr110"), 110, undefined, layout);
    restoreOrigin(layout);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur du nouvel installeur");
  });

  it("restaurer sans essai en cours ne fait rien", () => {
    const layout = install("mac");
    assert.equal(restoreOrigin(layout), false);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur origine");
  });

  it("refuse hors paquet (lancé depuis le dépôt) sans rien changer", () => {
    const layout = { ...install("mac"), packaged: false };
    assert.throws(() => applyEssai(archive("mac", "mr109"), 109, undefined, layout), /depuis le dépôt/);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur origine");
    assert.equal(backendState(layout).mode, "origin");
  });

  it("refuse une archive abîmée, illisible, sans serveur ou d'une autre plateforme, sans rien changer", () => {
    const layout = install("windows");
    const good = archive("windows", "mr109");
    const sha = createHash("sha256").update(good).digest("hex");
    assert.throws(() => applyEssai(good, 109, "0".repeat(64), layout), /SHA-256/);
    assert.throws(() => applyEssai(Buffer.from("pas une archive"), 109, undefined, layout), /illisible/);
    assert.throws(() => applyEssai(archive("mac", "mr109"), 109, undefined, layout), /pas un backend pour cet ordinateur/);
    const noServer = fs.mkdtempSync(path.join(tmp, "src-"));
    write(noServer, { "src/win/UnowhyHelper.exe": "pilote" });
    const out = path.join(tmp, "sans-serveur.tar.gz");
    execFileSync("tar", ["-czf", out, "-C", noServer, "."]);
    assert.throws(() => applyEssai(fs.readFileSync(out), 109, undefined, layout), /pas un backend pour cet ordinateur/);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur origine");
    assert.equal(backendState(layout).mode, "origin");
    assert.equal(exists(layout.dataDir, "backend-origin"), false);

    applyEssai(good, 109, sha, layout);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur mr109");
  });

  it("remet l'origine si le remplacement échoue en route", () => {
    const layout = install("mac");
    // Lien cassé dans config/ : dist/ est déjà remplacé quand la copie de config/ échoue.
    const dir = fs.mkdtempSync(path.join(tmp, "src-"));
    write(dir, files("mac", "mr109"));
    fs.symlinkSync("n-existe-pas", path.join(dir, "config", "lien.json"));
    const out = path.join(fs.mkdtempSync(path.join(tmp, "tgz-")), "backend.tar.gz");
    execFileSync("tar", ["-czf", out, "-C", dir, "."]);

    assert.throws(() => applyEssai(fs.readFileSync(out), 109, undefined, layout), /ENOENT/);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur origine");
    assert.equal(read(layout.root, "config/calls.json"), "calls origine");
    assert.equal(backendState(layout).mode, "origin");
  });
});

const sha = (buf: Buffer) => createHash("sha256").update(buf).digest("hex");

describe("mise à jour stable du backend", () => {
  for (const platform of ["mac", "windows"] as const) {
    it(`${platform} : remplace le backend installé par celui de la release, garde l'ordinateur tel quel`, () => {
      const layout = install(platform);
      const tgz = archive(platform, "1.3.0");
      applyUpdate(tgz, sha(tgz), "1.3.0", "1.2.0", layout);

      assert.equal(read(layout.root, "dist/server.mjs"), "serveur 1.3.0");
      assert.equal(read(layout.root, "config/calls.json"), "calls 1.3.0");
      assert.equal(exists(layout.root, "dist/ancien.mjs"), false, "fichier absent de la release retiré");
      assert.equal(read(layout.root, "config/deck.json"), "deck de l'ordinateur");
      assert.equal(read(layout.root, "config/tasks.json"), "tâches de l'ordinateur");
      assert.equal(read(layout.root, "node/node.exe"), "node verrouillé");
      if (platform === "windows") assert.equal(read(layout.root, "src/win/UnoMate.exe"), "lanceur verrouillé");
      assert.equal(read(layout.root, ".env"), "COMPANION_TOKEN=secret");
      assert.equal(exists(layout.dataDir, "backend-origin"), false, "pas de copie d'origine");
      assert.equal(backendState(layout).mode, "origin");
    });
  }

  it("compagnon d'avant les versions ou backend d'essai (0.0.0) : mis à jour", () => {
    const tgz = archive("mac", "1.3.0");
    const old = install("mac");
    applyUpdate(tgz, sha(tgz), "1.3.0", undefined, old);
    assert.equal(read(old.root, "dist/server.mjs"), "serveur 1.3.0");
    const essai = install("mac");
    applyUpdate(tgz, sha(tgz), "1.3.0", "0.0.0", essai);
    assert.equal(read(essai.root, "dist/server.mjs"), "serveur 1.3.0");
  });

  it("par-dessus un essai de MR : la release devient l'origine, l'essai est oublié", () => {
    const layout = install("windows");
    applyEssai(archive("windows", "mr109"), 109, undefined, layout);
    const tgz = archive("windows", "1.3.0");
    applyUpdate(tgz, sha(tgz), "1.3.0", "0.0.0", layout);
    assert.deepEqual(backendState(layout), { packaged: true, mode: "origin", mr: null, sha256: null });
    assert.equal(exists(layout.dataDir, "backend-origin"), false);
    assert.equal(restoreOrigin(layout), false);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur 1.3.0");
  });

  it("refuse une version pas plus récente, hors paquet, abîmée ou d'une autre plateforme, sans rien changer", () => {
    const layout = install("mac");
    const tgz = archive("mac", "1.3.0");
    assert.throws(() => applyUpdate(tgz, sha(tgz), "1.3.0", "1.3.0", layout), /déjà en version 1\.3\.0/);
    assert.throws(() => applyUpdate(tgz, sha(tgz), "1.2.9", "1.3.0", layout), /déjà en version/);
    assert.throws(() => applyUpdate(tgz, sha(tgz), "1.3", "1.2.0", layout), /invalide/);
    assert.throws(() => applyUpdate(tgz, sha(tgz), "1.3.0", "1.2.0", { ...layout, packaged: false }), /depuis le dépôt/);
    assert.throws(() => applyUpdate(tgz, "0".repeat(64), "1.3.0", "1.2.0", layout), /SHA-256/);
    const win = archive("windows", "1.3.0");
    assert.throws(() => applyUpdate(win, sha(win), "1.3.0", "1.2.0", layout), /pas un backend pour cet ordinateur/);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur origine");
  });

  it("remet l'installation si le remplacement échoue en route", () => {
    const layout = install("mac");
    const dir = fs.mkdtempSync(path.join(tmp, "src-"));
    write(dir, files("mac", "1.3.0"));
    fs.symlinkSync("n-existe-pas", path.join(dir, "config", "lien.json"));
    const out = path.join(fs.mkdtempSync(path.join(tmp, "tgz-")), "backend.tar.gz");
    execFileSync("tar", ["-czf", out, "-C", dir, "."]);
    const tgz = fs.readFileSync(out);

    assert.throws(() => applyUpdate(tgz, sha(tgz), "1.3.0", "1.2.0", layout), /ENOENT/);
    assert.equal(read(layout.root, "dist/server.mjs"), "serveur origine");
    assert.equal(read(layout.root, "dist/ancien.mjs"), "fichier qui n'est plus dans l'essai");
    assert.equal(read(layout.root, "config/calls.json"), "calls origine");
    assert.equal(read(layout.root, "config/deck.json"), "deck de l'ordinateur");
  });
});

describe("routes de l'essai du backend", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer();
  });
  after(async () => {
    await server?.stop();
  });

  const upload = (query: string, body: Buffer | string, auth = true) =>
    fetch(`${server.url}/companion/backend/essai${query}`, {
      method: "POST",
      headers: { "content-type": "application/gzip", ...(auth ? { authorization: `Bearer ${TOKEN}` } : {}) },
      body: typeof body === "string" ? body : new Uint8Array(body),
    });

  it("dit que le compagnon est sur son backend d'origine, et que lancé hors paquet il ne peut pas basculer", async () => {
    const { json } = await server.call("GET", "/companion/backend");
    assert.deepEqual(json, { packaged: false, mode: "origin", mr: null, sha256: null });
  });

  it("refuse l'essai lancé depuis le dépôt avec une phrase en français (409)", async () => {
    const res = await upload("?mr=109", archive("mac", "mr109"));
    assert.equal(res.status, 409);
    assert.match(((await res.json()) as { message: string }).message, /lancé depuis le dépôt/);
  });

  it("refuse sans jeton, sans numéro de MR, avec une empreinte mal formée ou sans corps", async () => {
    assert.equal((await upload("?mr=109", "x", false)).status, 401);
    assert.equal((await upload("", "x")).status, 400);
    assert.equal((await upload("?mr=109&sha256=abc", "x")).status, 400);
    assert.equal((await upload("?mr=109", "")).status, 415);
  });

  it("/health annonce la mise à jour depuis la tablette seulement pour un compagnon installé par paquet", async () => {
    const { json } = await server.call("GET", "/health");
    assert.equal(json.backendUpdate, false);
  });

  it("refuse la mise à jour lancée depuis le dépôt (409), sans version ou empreinte, sans corps", async () => {
    const tgz = archive("mac", "1.3.0");
    const update = (query: string, body: Buffer | string) =>
      fetch(`${server.url}/companion/backend/update${query}`, {
        method: "POST",
        headers: { "content-type": "application/gzip", authorization: `Bearer ${TOKEN}` },
        body: typeof body === "string" ? body : new Uint8Array(body),
      });
    const res = await update(`?version=1.3.0&sha256=${sha(tgz)}`, tgz);
    assert.equal(res.status, 409);
    assert.match(((await res.json()) as { message: string }).message, /lancé depuis le dépôt/);
    assert.equal((await update(`?sha256=${sha(tgz)}`, tgz)).status, 400);
    assert.equal((await update("?version=1.3.0", tgz)).status, 400);
    assert.equal((await update(`?version=1.3&sha256=${sha(tgz)}`, tgz)).status, 400);
    assert.equal((await update(`?version=1.3.0&sha256=${sha(tgz)}`, "")).status, 415);
  });

  it("restaurer l'origine sans essai en cours ne change rien", async () => {
    const { status, json } = await server.call("POST", "/companion/backend/origin", {});
    assert.equal(status, 200);
    assert.deepEqual(json, { ok: true, changed: false });
  });
});
