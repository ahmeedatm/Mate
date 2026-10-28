import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { runSanity } from "../qa/sanity.ts";
import { clipboardUnavailable, whenClipboardAvailable } from "../src/driver-common.ts";
import { run, UTF8_ENV } from "../src/mac/run.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

// Presse-papiers partagé : GET/POST /system/clipboard et POST /system/clipboard/image, sur le faux pilotage
// (test/fake-os.ts). Le contenu du presse-papiers ne doit jamais se retrouver dans le journal du serveur.

const MAX = 100_000;
const docsDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-clip-docs-"));
let server: TestServer;
before(async () => {
  fs.mkdirSync(path.join(docsDir, "Photos tablette"));
  fs.writeFileSync(path.join(docsDir, "Photos tablette", "x.jpg"), "jpg");
  fs.writeFileSync(path.join(docsDir, "Photos tablette", "y.PNG"), "png");
  fs.writeFileSync(path.join(docsDir, "Photos tablette", "note.txt"), "texte");
  fs.writeFileSync(path.join(docsDir, "Photos tablette", "fakejpg"), "pas une image");
  server = await startServer({ env: { DOCS_DIR: docsDir } });
});
after(async () => {
  await server?.stop();
  fs.rmSync(docsDir, { recursive: true, force: true });
});

describe("lecture du presse-papiers", () => {
  it("vide par défaut", async () => {
    server.setOs({});
    const { status, json } = await server.call("GET", "/system/clipboard");
    assert.equal(status, 200);
    assert.deepEqual(json, { kind: "empty" });
  });

  it("renvoie le texte, une image, un contenu protégé ou un autre contenu tels que le système les lit", async () => {
    for (const clipboard of [
      { kind: "text", text: "bonjour\nle monde" },
      { kind: "image" },
      { kind: "protected" },
      { kind: "other" },
    ] as const) {
      server.setOs({ clipboard });
      const { status, json } = await server.call("GET", "/system/clipboard");
      assert.equal(status, 200);
      assert.deepEqual(json, clipboard);
    }
  });

  it("garde un texte de 100 000 caractères entier", async () => {
    server.setOs({ clipboard: { kind: "text", text: "a".repeat(MAX) } });
    const { json } = await server.call("GET", "/system/clipboard");
    assert.equal(json.text.length, MAX);
    assert.equal(json.truncated, undefined);
  });

  it("coupe un texte de 100 001 caractères à 100 000 et le signale", async () => {
    server.setOs({ clipboard: { kind: "text", text: "a".repeat(MAX + 1) } });
    const { status, json } = await server.call("GET", "/system/clipboard");
    assert.equal(status, 200);
    assert.equal(json.kind, "text");
    assert.equal(json.text.length, MAX);
    assert.equal(json.truncated, true);
  });

  it("n'écrit pas le contenu lu dans le journal du serveur", async () => {
    server.setOs({ clipboard: { kind: "text", text: "secret-de-lecture-xyz" } });
    await server.call("GET", "/system/clipboard");
    assert.ok(!server.output().includes("secret-de-lecture-xyz"));
  });

  it("refuse sans jeton", async () => {
    const { status } = await server.call("GET", "/system/clipboard", undefined, false);
    assert.equal(status, 401);
  });
});

describe("presse-papiers indisponible", () => {
  it("répond 503 avec une phrase claire quand l'ordinateur n'a pas de session de bureau", async () => {
    server.setOs({ clipboardUnavailable: true });
    const { status, json } = await server.call("GET", "/system/clipboard");
    assert.equal(status, 503);
    assert.match(json.message, /Presse-papiers indisponible/);
  });

  it("une autre panne du pilote reste une erreur 500", async () => {
    server.setOs({ fail: ["getClipboard"] });
    const { status } = await server.call("GET", "/system/clipboard");
    assert.equal(status, 500);
  });
});

describe("presse-papiers indisponible à l'écriture", () => {
  it("répond 503, comme à la lecture, pour un texte et pour une image", async () => {
    server.setOs({ clipboardUnavailable: true });
    server.clearOsCalls();
    const text = await server.call("POST", "/system/clipboard", { text: "a" });
    assert.equal(text.status, 503);
    assert.match(text.json.message, /Presse-papiers indisponible/);
    const image = await server.call("POST", "/system/clipboard/image", { path: "Photos tablette/x.jpg" });
    assert.equal(image.status, 503);
    assert.match(image.json.message, /Presse-papiers indisponible/);
    assert.deepEqual(server.osCalls(), []);
    server.setOs({});
  });
});

describe("clipboardUnavailable", () => {
  it("lève une erreur 503 en français", () => {
    assert.throws(clipboardUnavailable, (e: Error & { statusCode?: number }) => e.statusCode === 503 && /indisponible/.test(e.message));
  });
});

describe("whenClipboardAvailable (erreur du pilote Windows)", () => {
  it("change l'erreur du pilote qui porte CLIPBOARD_UNAVAILABLE en 503, sans laisser le mot interne passer", async () => {
    const driverError = new Error("Presse-papiers indisponible : aucune session de bureau (CLIPBOARD_UNAVAILABLE)");
    await assert.rejects(whenClipboardAvailable(Promise.reject(driverError)), (e: Error & { statusCode?: number }) => {
      return e.statusCode === 503 && /indisponible/.test(e.message) && !e.message.includes("CLIPBOARD_UNAVAILABLE");
    });
  });

  it("laisse passer une autre erreur du pilote et le résultat d'un appel réussi", async () => {
    const other = new Error("Image illisible");
    await assert.rejects(
      whenClipboardAvailable(Promise.reject(other)),
      (e: Error & { statusCode?: number }) => e === other && e.statusCode === undefined,
    );
    assert.equal(await whenClipboardAvailable(Promise.resolve("ok")), "ok");
  });
});

describe("contrôle sanity du presse-papiers", () => {
  const sanity = async () => (await runSanity({ url: server.url, token: TOKEN, os: "mac" })).find((r) => r.name === "presse-papiers")!;

  it("écrit un texte accentué, le relit à l'identique et remet l'ancien texte", async () => {
    server.setOs({ clipboard: { kind: "text", text: "avant é" } });
    const r = await sanity();
    assert.equal(r.status, "ok", r.detail);
    assert.deepEqual((await server.call("GET", "/system/clipboard")).json, { kind: "text", text: "avant é" });
  });

  it("passe son tour seulement quand le compagnon répond 503 (pas de session de bureau)", async () => {
    server.setOs({ clipboardUnavailable: true });
    assert.equal((await sanity()).status, "skip");
  });

  it("échoue sur toute autre erreur de lecture, un script Mac cassé par exemple", async () => {
    server.setOs({ fail: ["getClipboard"] });
    const r = await sanity();
    assert.equal(r.status, "fail");
    assert.match(r.detail, /500/);
  });
});

describe("pbcopy et pbpaste", () => {
  it("tournent en UTF-8 même quand le service ne définit aucune langue", async () => {
    assert.equal(UTF8_ENV.LANG, "en_US.UTF-8");
    const lang = (env?: NodeJS.ProcessEnv) => run(process.execPath, ["-p", "process.env.LANG || 'aucune'"], 10_000, env);
    assert.equal(await lang(UTF8_ENV), "en_US.UTF-8");
    assert.equal(await lang({ PATH: process.env.PATH, SystemRoot: process.env.SystemRoot }), "aucune");
  });
});

describe("écriture d'un texte", () => {
  it("le copie dans le presse-papiers de l'ordinateur", async () => {
    server.clearOsCalls();
    const { status, json } = await server.call("POST", "/system/clipboard", { text: "à copier\nsur deux lignes" });
    assert.equal(status, 200);
    assert.deepEqual(json, { ok: true });
    assert.deepEqual(server.osCalls(), [{ fn: "setClipboardText", args: ["à copier\nsur deux lignes"] }]);
  });

  it("garde les accents du texte écrit", async () => {
    server.clearOsCalls();
    await server.call("POST", "/system/clipboard", { text: "é à ç œ €" });
    assert.deepEqual(server.osCalls(), [{ fn: "setClipboardText", args: ["é à ç œ €"] }]);
  });

  it("accepte 100 000 caractères", async () => {
    const { status } = await server.call("POST", "/system/clipboard", { text: "a".repeat(MAX) });
    assert.equal(status, 200);
  });

  it("refuse un texte vide, trop long, absent ou nul", async () => {
    server.clearOsCalls();
    for (const body of [{ text: "" }, { text: "a".repeat(MAX + 1) }, {}, { text: null }]) {
      const { status } = await server.call("POST", "/system/clipboard", body);
      assert.equal(status, 400, JSON.stringify(body).slice(0, 40));
    }
    assert.deepEqual(server.osCalls(), []);
  });

  it("n'écrit pas le texte dans le journal du serveur", async () => {
    await server.call("POST", "/system/clipboard", { text: "secret-d-ecriture-xyz" });
    assert.ok(!server.output().includes("secret-d-ecriture-xyz"));
  });

  it("refuse sans jeton", async () => {
    const { status } = await server.call("POST", "/system/clipboard", { text: "a" }, false);
    assert.equal(status, 401);
  });
});

describe("écriture d'une image", () => {
  const photo = (name: string) => path.join(docsDir, "Photos tablette", name);

  it("copie une photo du dossier partagé avec son chemin complet", async () => {
    server.clearOsCalls();
    const { status, json } = await server.call("POST", "/system/clipboard/image", { path: "Photos tablette/x.jpg" });
    assert.equal(status, 200);
    assert.deepEqual(json, { ok: true });
    assert.deepEqual(server.osCalls(), [{ fn: "setClipboardImage", args: [photo("x.jpg")] }]);
  });

  it("accepte .png, même en majuscules", async () => {
    server.clearOsCalls();
    const { status } = await server.call("POST", "/system/clipboard/image", { path: "Photos tablette/y.PNG" });
    assert.equal(status, 200);
    assert.deepEqual(server.osCalls(), [{ fn: "setClipboardImage", args: [photo("y.PNG")] }]);
  });

  it("refuse une extension qui n'est pas une image (400)", async () => {
    server.clearOsCalls();
    for (const name of ["note.txt", "fakejpg"]) {
      const { status } = await server.call("POST", "/system/clipboard/image", { path: `Photos tablette/${name}` });
      assert.equal(status, 400, name);
    }
    assert.deepEqual(server.osCalls(), []);
  });

  it("répond 404 pour un fichier absent", async () => {
    server.clearOsCalls();
    const { status } = await server.call("POST", "/system/clipboard/image", { path: "Photos tablette/absente.jpg" });
    assert.equal(status, 404);
    assert.deepEqual(server.osCalls(), []);
  });

  it("répond 404 pour un dossier nommé comme une image", async () => {
    fs.mkdirSync(path.join(docsDir, "dossier.jpg"), { recursive: true });
    const { status } = await server.call("POST", "/system/clipboard/image", { path: "dossier.jpg" });
    assert.equal(status, 404);
  });

  it("refuse un chemin qui sort du dossier partagé", async () => {
    server.clearOsCalls();
    for (const p of ["../x.jpg", "Photos tablette/../../x.jpg"]) {
      const { status } = await server.call("POST", "/system/clipboard/image", { path: p });
      assert.equal(status, 400, p);
    }
    assert.deepEqual(server.osCalls(), []);
  });

  it("ne sort pas du dossier partagé avec un chemin absolu (refusé ou ramené dans le dossier, jamais copié)", async () => {
    server.clearOsCalls();
    for (const p of ["/etc/x.jpg", "C:\\x.jpg", "\\\\server\\share\\x.jpg"]) {
      const { status } = await server.call("POST", "/system/clipboard/image", { path: p });
      assert.ok(status === 400 || status === 404, `${p} : ${status}`);
    }
    assert.deepEqual(server.osCalls(), []);
  });

  it("refuse un chemin vide ou absent", async () => {
    for (const body of [{ path: "" }, {}]) {
      const { status } = await server.call("POST", "/system/clipboard/image", body);
      assert.equal(status, 400, JSON.stringify(body));
    }
  });

  it("refuse sans jeton", async () => {
    const { status } = await server.call("POST", "/system/clipboard/image", { path: "Photos tablette/x.jpg" }, false);
    assert.equal(status, 401);
  });
});

describe("système non pilotable", () => {
  it("répond 501 partout", async () => {
    const other = await startServer({ os: "other" });
    try {
      assert.equal((await other.call("GET", "/system/clipboard")).status, 501);
      assert.equal((await other.call("POST", "/system/clipboard", { text: "a" })).status, 501);
      assert.equal((await other.call("POST", "/system/clipboard/image", { path: "x.jpg" })).status, 501);
    } finally {
      await other.stop();
    }
  });
});
