import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createHash } from "node:crypto";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { checkUpdate, download, tabletHeaders } from "../src/app-update.ts";
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

describe("jeton envoyé à la tablette", () => {
  it("en-tête Bearer avec le jeton d'appairage, rien sans jeton", () => {
    assert.deepEqual(tabletHeaders("abc"), { Authorization: "Bearer abc" });
    assert.deepEqual(tabletHeaders(""), {});
  });

  it("le téléchargement envoie les en-têtes donnés, et aucun par défaut (ex. modèle Whisper chez un tiers)", async () => {
    const body = Buffer.from("contenu de l'installeur");
    const seen: (string | undefined)[] = [];
    const server = http.createServer((req, res) => {
      seen.push(req.headers.authorization);
      res.end(body);
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const url = `http://127.0.0.1:${(server.address() as AddressInfo).port}/Mate-Mac.zip`;
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "app-update-"));
    const expected = { size: body.length, sha256: createHash("sha256").update(body).digest("hex") };
    globalThis.fetch = realFetch;
    try {
      await download(url, path.join(dir, "a"), expected, tabletHeaders("abc"));
      await download(url, path.join(dir, "b"), expected);
      assert.deepEqual(seen, ["Bearer abc", undefined]);
      assert.deepEqual(fs.readFileSync(path.join(dir, "a")), body);
    } finally {
      server.close();
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});
