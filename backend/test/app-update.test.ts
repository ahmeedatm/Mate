import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { checkUpdate, download, installerBase } from "../src/app-update.ts";
import { noteTabletAddress, pollCommands } from "../src/tablet.ts";

// Mise à jour de l'appli portable : la liste (taille, SHA-256) vient du canal appairé, jamais du port 8080 en clair.

const system = process.platform === "win32" ? "windows" : "mac";
const installer = {
  os: system,
  file: system === "mac" ? "Mate-Mac.zip" : "Mate-Windows.exe",
  size: 1000,
  sha256: "b".repeat(64),
  version: "9.0.0",
};

/** La tablette fait un poll (HTTPS, jeton) avec cet état, depuis cette adresse WiFi. */
async function tabletPolls(state: Record<string, unknown>) {
  noteTabletAddress("192.0.2.10");
  await pollCommands(state, 0, new AbortController().signal);
}

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
});

describe("mise à jour de l'appli portable", () => {
  it("lit la version et l'empreinte dans l'état du poll, sans rien demander en HTTP", async () => {
    const calls: string[] = [];
    globalThis.fetch = (async (url: string) => {
      calls.push(String(url));
      return new Response(JSON.stringify({ installers: [{ ...installer, sha256: "c".repeat(64), version: "99.0.0" }] }));
    }) as typeof fetch;
    await tabletPolls({ installers: [installer] });
    assert.deepEqual(await checkUpdate("1.0.0"), { current: "1.0.0", available: "9.0.0" });
    assert.deepEqual(calls, []);
  });

  it("tablette qui n'annonce pas ses installeurs (ancienne appli) : refus clair, pas de repli en clair", async () => {
    globalThis.fetch = (async () => assert.fail("aucune requête HTTP attendue")) as typeof fetch;
    await tabletPolls({ mode: "home" });
    await assert.rejects(
      checkUpdate("1.0.0"),
      (e: Error & { statusCode?: number }) => e.statusCode === 409 && /mets à jour/.test(e.message),
    );
  });
});

describe("téléchargement (installeur, modèle Whisper)", () => {
  it("une erreur d'écriture (disque plein, dossier absent) rejette au lieu de faire planter le compagnon", async () => {
    // Deux morceaux espacés : l'erreur tombe pendant l'attente du réseau, quand personne n'écoute le fichier.
    const body = new ReadableStream<Uint8Array>({
      async start(controller) {
        controller.enqueue(new Uint8Array(500));
        await new Promise((resolve) => setTimeout(resolve, 100));
        controller.enqueue(new Uint8Array(500));
        controller.close();
      },
    });
    globalThis.fetch = (async () => new Response(body)) as typeof fetch;
    const file = path.join(os.tmpdir(), "dossier-absent-mate", "x.bin");
    await assert.rejects(download("http://192.0.2.10/x", file, { size: 1000, sha256: "d".repeat(64) }), /ENOENT/);
    assert.equal(fs.existsSync(file), false);
  });
});

describe("adresse des fichiers sur la tablette", () => {
  it("le code lu dans l'état du poll se met entre le port et le fichier", () => {
    assert.equal(installerBase("192.0.2.10", { installerCode: "K7M2Q" }), "http://192.0.2.10:8080/K7M2Q");
  });

  it("port annoncé par la tablette (8080 pris par une autre appli) : celui-là ; illisible : refus clair", () => {
    assert.equal(installerBase("192.0.2.10", { installerCode: "K7M2Q", installerPort: 8081 }), "http://192.0.2.10:8081/K7M2Q");
    for (const installerPort of [null, "8081", 80, 0, 70000, 8081.5]) {
      assert.throws(
        () => installerBase("192.0.2.10", { installerCode: "K7M2Q", installerPort }),
        (e: Error & { statusCode?: number }) => e.statusCode === 409,
        JSON.stringify(installerPort),
      );
    }
  });

  it("sans code (ancienne tablette), code vide ou qui sort du chemin : refus clair", () => {
    for (const state of [
      null,
      {},
      { installerCode: "" },
      { installerCode: 12345 },
      { installerCode: "../x" },
      { installerCode: "K7M2Q/../.." },
    ]) {
      assert.throws(
        () => installerBase("192.0.2.10", state),
        (e: Error & { statusCode?: number }) => e.statusCode === 409 && /mets à jour/.test(e.message),
        JSON.stringify(state),
      );
    }
  });
});

describe("code périmé", () => {
  it("un 404 de la tablette dit de réessayer, un 404 ailleurs reste générique", async () => {
    globalThis.fetch = (async () => new Response("Introuvable.", { status: 404 })) as typeof fetch;
    const file = path.join(os.tmpdir(), "x-mate-test.bin");
    const expected = { size: 10, sha256: "d".repeat(64) };
    await assert.rejects(
      download("http://192.0.2.10:8080/K7M2Q/a.zip", file, expected, "Code périmé"),
      (e: Error & { statusCode?: number }) => e.statusCode === 502 && /Code périmé/.test(e.message),
    );
    await assert.rejects(download("http://192.0.2.10/a", file, expected), /HTTP 404/);
  });
});
