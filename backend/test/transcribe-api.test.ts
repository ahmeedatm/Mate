import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { cleanTranscript } from "../src/transcribe.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

/** Faux `whisper-cli` : note ses arguments, rend une sortie avec des marques de non-parole. */
const bin = fs.mkdtempSync(path.join(os.tmpdir(), "companion-whisper-"));
const argsFile = path.join(bin, "args.txt");
const whisper = path.join(bin, "whisper-cli");
fs.writeFileSync(whisper, `#!/bin/sh\necho "$@" > "${argsFile}"\nprintf '[BLANK_AUDIO]\\n Lance les tests (musique)\\n du backend.\\n'\n`, {
  mode: 0o755,
});
const model = path.join(bin, "modele.bin");
fs.writeFileSync(model, "faux modèle");

/** WAV 16 kHz mono 16 bits de `ms` millisecondes de silence. */
function wav(ms: number) {
  const data = Buffer.alloc((16_000 * 2 * ms) / 1000);
  const h = Buffer.alloc(44);
  h.write("RIFF", 0, "ascii");
  h.writeUInt32LE(36 + data.length, 4);
  h.write("WAVEfmt ", 8, "ascii");
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20);
  h.writeUInt16LE(1, 22);
  h.writeUInt32LE(16_000, 24);
  h.writeUInt32LE(32_000, 28);
  h.writeUInt16LE(2, 32);
  h.writeUInt16LE(16, 34);
  h.write("data", 36, "ascii");
  h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}

const post = (server: TestServer, body: Buffer, auth = true) =>
  fetch(`${server.url}/agents/transcribe`, {
    method: "POST",
    headers: { "content-type": "audio/wav", ...(auth ? { authorization: `Bearer ${TOKEN}` } : {}) },
    body: new Uint8Array(body),
  });

describe("dictée d'un prompt : whisper-cli présent", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer({ env: { WHISPER_BIN: whisper, WHISPER_MODEL: model } });
  });
  after(async () => server?.stop());

  it("GET /agents annonce le micro", async () => {
    assert.equal((await server.call("GET", "/agents")).json.voice, true);
  });

  it("transcrit en français avec le modèle, sans les marques de non-parole", async () => {
    const res = await post(server, wav(500));
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { text: "Lance les tests du backend." });
    const args = fs.readFileSync(argsFile, "utf8");
    assert.match(args, /-m .*modele\.bin/);
    assert.match(args, /-l fr/);
  });

  it("refuse ce qui n'est pas un WAV, ou trop long", async () => {
    assert.equal((await post(server, Buffer.from("pas un wav du tout, vraiment pas du tout, non"))).status, 400);
    assert.equal((await post(server, wav(61_000))).status, 400);
    // Trop gros pour être lu : 413, ou la connexion coupée avant la fin de l'envoi (EPIPE), selon le moment.
    assert.equal(
      await post(server, wav(70_000)).then(
        (r) => r.status,
        () => 413,
      ),
      413,
    );
  });

  it("demande le jeton", async () => {
    assert.equal((await post(server, wav(100), false)).status, 401);
  });
});

describe("dictée d'un prompt : rien d'installé", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer({ env: { WHISPER_BIN: path.join(bin, "absent"), WHISPER_MODEL: path.join(bin, "absent.bin") } });
  });
  after(async () => {
    await server?.stop();
    fs.rmSync(bin, { recursive: true, force: true });
  });

  it("pas de micro, et une phrase si la tablette essaie quand même", async () => {
    assert.equal((await server.call("GET", "/agents")).json.voice, false);
    const res = await post(server, wav(100));
    assert.equal(res.status, 409);
    assert.match((await res.json()).error, /brew install whisper-cpp/);
  });
});

describe("cleanTranscript", () => {
  it("ne garde que la parole", () => {
    assert.equal(cleanTranscript("[BLANK_AUDIO]\n"), "");
    assert.equal(cleanTranscript(" Bonjour\n  (rires) Claude [Musique]\n"), "Bonjour Claude");
  });
});
