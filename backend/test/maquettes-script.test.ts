import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// scripts/maquettes.mjs : sert les maquettes, et un clic du sélecteur ouvre la maquette sur la tablette (ordre `page`).

const script = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../scripts/maquettes.mjs");
let server: TestServer;
let child: ChildProcess;
let selector: string;
let dir: string;

before(async () => {
  server = await startServer();
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "maquettes-"));
  fs.writeFileSync(path.join(dir, "a-dense.html"), "<title>Dense</title><button>Ok</button>");
  fs.writeFileSync(path.join(dir, "b-aere.html"), "<title>Aérée</title>");
  const companion = new URL(server.url).port;
  child = spawn(process.execPath, [script, dir, "--port", "0", "--compagnon", companion, "--sans-onglet", "--sans-adb"]);
  selector = await new Promise((resolve, reject) => {
    let out = "";
    child.stdout!.on("data", (c) => {
      out += c;
      const m = /Sélecteur : (\S+)/.exec(out);
      if (m) resolve(m[1]);
    });
    child.on("exit", (code) => reject(new Error(`script arrêté (${code}) : ${out}`)));
  });
});
after(async () => {
  child?.kill();
  await server.stop();
  fs.rmSync(dir, { recursive: true, force: true });
});

const choose = (file: string | null) =>
  fetch(selector + "choisir", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ file }) });

async function nextPage() {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const { json } = await server.call("POST", "/tablet/poll", { state: {} });
    const order = json.commands.find((c: { command: { type: string } }) => c.command.type === "page");
    if (order) return order;
  }
  throw new Error("Aucun ordre `page` reçu");
}

describe("sélecteur de maquettes", () => {
  it("liste les maquettes par leur titre et les sert", async () => {
    const page = await (await fetch(selector)).text();
    assert.match(page, /Dense/);
    assert.match(page, /Aérée/);
    assert.match(page, /data-file="a-dense.html"/);
    assert.equal(await (await fetch(selector + "a-dense.html")).text(), "<title>Dense</title><button>Ok</button>");
  });

  it("ne sert rien hors du dossier", async () => {
    for (const p of ["..%2Fpackage.json", "%2e%2e/%2e%2e/etc/hosts", "absente.html"]) {
      assert.equal((await fetch(selector + p)).status, 404, p);
    }
  });

  it("sans tablette, le dit sans planter", async () => {
    const lines = await (await choose("a-dense.html")).json();
    assert.equal(lines[0].ok, false);
    assert.match(lines[0].detail, /tablette/);
  });

  it("un clic envoie l'ordre page avec l'adresse de la maquette, puis la ferme", async () => {
    // Un poll rend la tablette connectée.
    const warm = server.call("POST", "/tablet/poll", { state: {} });
    await new Promise((r) => setTimeout(r, 300));
    const polling = nextPage();
    const post = choose("b-aere.html");
    const order = await polling;
    // Compagnon de test qui n'écoute qu'en local : adresse 127.0.0.1 (adb reverse côté appareil).
    assert.deepEqual(order.command, { type: "page", url: `http://127.0.0.1:${new URL(selector).port}/b-aere.html` });
    await server.call("POST", "/tablet/result", { id: order.id, ok: true });
    const lines = await (await post).json();
    assert.equal(lines[0].ok, true, JSON.stringify(lines));

    const closing = nextPage();
    const close = choose(null);
    const closed = await closing;
    assert.deepEqual(closed.command, { type: "page", url: null });
    await server.call("POST", "/tablet/result", { id: closed.id, ok: true });
    assert.equal((await (await close).json())[0].ok, true);
    await warm;
  });

  it("refuse une maquette inconnue", async () => {
    assert.equal((await choose("../secret.html")).status, 404);
  });

  it("n'envoie d'ordre que sur une requête JSON au nom local", async () => {
    const plain = await fetch(selector + "choisir", { method: "POST", headers: { "content-type": "text/plain" }, body: '{"file":null}' });
    assert.equal(plain.status, 415);
  });

  it("ne sert ni fichier caché ni lien qui sort du dossier", async () => {
    fs.writeFileSync(path.join(dir, ".env"), "SECRET=1");
    fs.symlinkSync("/etc/hosts", path.join(dir, "hosts.html"));
    assert.equal((await fetch(selector + ".env")).status, 404);
    assert.equal((await fetch(selector + "hosts.html")).status, 404);
  });
});
