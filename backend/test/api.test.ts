import assert from "node:assert/strict";
import { execFile, execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { promisify } from "node:util";
import { startServer, TOKEN, type TestServer } from "./server.ts";

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server?.stop();
});

describe("santé et jeton", () => {
  it("/health répond sans jeton", async () => {
    const { status, json } = await server.call("GET", "/health", undefined, false);
    assert.equal(status, 200);
    assert.equal(json.ok, true);
    assert.equal(json.name, "Ordi de test");
    assert.equal(json.auth, false);
  });

  it("/health signale un jeton valide", async () => {
    const { json } = await server.call("GET", "/health");
    assert.equal(json.auth, true);
  });

  it("refuse une route protégée sans jeton", async () => {
    const { status, json } = await server.call("GET", "/tasks", undefined, false);
    assert.equal(status, 401);
    assert.match(json.error, /Jeton invalide/);
  });

  it("refuse un mauvais jeton", async () => {
    const res = await fetch(`${server.url}/tasks`, { headers: { authorization: "Bearer faux" } });
    assert.equal(res.status, 401);
  });

  it("remplace une erreur de validation par une phrase", async () => {
    const { status, json } = await server.call("POST", "/tasks", { label: "" });
    assert.equal(status, 400);
    assert.equal(json.error, "Demande invalide : mets à jour l'appli de la tablette");
  });
});

describe("appairage", () => {
  it("donne le jeton contre le code affiché sur la page locale", async () => {
    const req = await server.call("POST", "/pair/request", { tablet: "Tablette CI" }, false);
    assert.equal(req.status, 200);
    const local = await server.call("GET", "/local/status", undefined, false);
    assert.equal(local.status, 200);
    assert.equal(local.json.pairing.tablet, "Tablette CI");
    const code: string = local.json.pairing.code;
    assert.match(code, /^\d{6}$/);

    const wrong = String((Number(code) + 1) % 1_000_000).padStart(6, "0");
    const bad = await server.call("POST", "/pair/confirm", { request: req.json.request, code: wrong }, false);
    assert.equal(bad.status, 403);

    const ok = await server.call(
      "POST",
      "/pair/confirm",
      { request: req.json.request, code: `${code.slice(0, 3)} ${code.slice(3)}` },
      false,
    );
    assert.equal(ok.status, 200);
    assert.equal(ok.json.token, TOKEN);
    assert.equal(ok.json.name, "Ordi de test");

    const again = await server.call("POST", "/pair/confirm", { request: req.json.request, code }, false);
    assert.equal(again.status, 410);
  });
});

describe("tâches", () => {
  it("ajoute, coche puis supprime une tâche ponctuelle", async () => {
    const added = await server.call("POST", "/tasks", { label: "  Arroser la plante  " });
    assert.equal(added.status, 200);
    assert.equal(added.json.label, "Arroser la plante");
    const id = added.json.id;

    let list = await server.call("GET", "/tasks");
    assert.ok(list.json.tasks.some((t: { id: string }) => t.id === id));

    const done = await server.call("POST", `/tasks/${id}/done`, { done: true });
    assert.equal(done.status, 200);
    list = await server.call("GET", "/tasks");
    assert.equal(list.json.tasks.find((t: { id: string }) => t.id === id).done, true);

    assert.equal((await server.call("DELETE", `/tasks/${id}`)).status, 200);
    list = await server.call("GET", "/tasks");
    assert.ok(!list.json.tasks.some((t: { id: string }) => t.id === id));
  });

  it("range une tâche avec des jours dans les récurrentes", async () => {
    const added = await server.call("POST", "/tasks", { label: "Sport", days: [5, 1, 1], remindAt: "18:30" });
    assert.equal(added.status, 200);
    const { json } = await server.call("GET", "/tasks/recurring");
    const task = json.tasks.find((t: { id: string }) => t.id === added.json.id);
    assert.ok(task, "tâche récurrente absente");
    assert.equal(task.kind, "daily");
    assert.equal(task.remindAt, "18:30");
    assert.equal(task.deletable, true);
  });

  it("répond 404 pour une tâche inconnue", async () => {
    assert.equal((await server.call("POST", "/tasks/inconnue/done", { done: true })).status, 404);
  });
});

describe("agenda personnel", () => {
  it("crée, modifie puis supprime un rendez-vous", async () => {
    const startMs = Date.now() + 3_600_000;
    const added = await server.call("POST", "/calendar/events", { title: "Dentiste", startMs, allDay: false });
    assert.equal(added.status, 200);
    const id = added.json.id;

    const updated = await server.call("PUT", `/calendar/events/${id}`, { title: "Dentiste (décalé)", startMs, allDay: false });
    assert.equal(updated.status, 200);
    assert.equal(updated.json.title, "Dentiste (décalé)");

    assert.equal((await server.call("DELETE", `/calendar/events/${id}`)).status, 200);
    assert.equal((await server.call("DELETE", `/calendar/events/${id}`)).status, 404);
  });

  it("refuse un rendez-vous sans titre", async () => {
    const { status } = await server.call("POST", "/calendar/events", { title: "", startMs: 0, allDay: true });
    assert.equal(status, 400);
  });
});

describe("canal tablette", () => {
  it("transmet un ordre à la tablette et rend sa réponse", async () => {
    // La tablette attend ses ordres (long-polling) avant que l'ordinateur en envoie un.
    const polling = server.call("POST", "/tablet/poll", { state: { mode: "home" } });
    await new Promise((r) => setTimeout(r, 300));
    const command = server.call("POST", "/tablet/command", { type: "message", text: "Coucou" });
    const poll = await polling;
    assert.equal(poll.status, 200);
    const [order] = poll.json.commands;
    assert.equal(order.command.type, "message");
    assert.equal(order.command.text, "Coucou");

    const result = await server.call("POST", "/tablet/result", { id: order.id, ok: true });
    assert.equal(result.json.known, true);
    const sent = await command;
    assert.equal(sent.status, 200);
    assert.equal(sent.json.ok, true);
    assert.equal(sent.json.received, true);
  });

  it("refuse un ordre sans son champ requis", async () => {
    const { status, json } = await server.call("POST", "/tablet/command", { type: "noise" });
    assert.equal(status, 400);
    assert.match(json.error, /kind/);
  });
});

describe("pages locales", () => {
  it("sert la page d'accueil sans jeton", async () => {
    const { status, json } = await server.call("GET", "/", undefined, false);
    assert.equal(status, 200);
    assert.match(json, /<html/i);
  });

  it("refuse la page locale demandée sous un autre nom d'hôte (DNS rebinding)", async () => {
    // fetch ne laisse pas changer l'en-tête Host : requête http brute.
    const status = await new Promise<number>((resolve, reject) => {
      const { port } = new URL(server.url);
      http
        .get({ host: "127.0.0.1", port, path: "/local/status", headers: { host: `exemple.com:${port}` } }, (res) => {
          res.resume();
          resolve(res.statusCode ?? 0);
        })
        .on("error", reject);
    });
    assert.equal(status, 401);
  });
});

describe("mises à jour de la tablette", () => {
  /** Fichiers d'une release GitLab (APK + entrée .json), puis `npm run updates:add`. */
  function addRelease(version: string, versionCode: number) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-release-"));
    const file = `companion-${version}.apk`;
    fs.writeFileSync(path.join(dir, file), `apk ${version}`);
    const entry = path.join(dir, `companion-${version}.json`);
    fs.writeFileSync(
      entry,
      JSON.stringify({ package: "com.unowhy.companion", label: "Companion", versionCode, versionName: version, file }),
    );
    execFileSync(process.execPath, ["--import", "tsx", "scripts/updates.ts", "add", entry], {
      cwd: path.resolve(import.meta.dirname, ".."),
      env: { ...process.env, DATA_DIR: server.dataDir },
    });
  }

  it("annonce la version ajoutée depuis une release, sans toucher aux autres paquets", async () => {
    const dir = path.join(server.dataDir, "updates");
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, "webview.apk"), "webview");
    const webview = {
      package: "com.google.android.webview",
      label: "Navigateur web",
      versionCode: 1,
      versionName: "1",
      file: "webview.apk",
    };
    fs.writeFileSync(path.join(dir, "manifest.json"), JSON.stringify({ updates: [webview] }));

    addRelease("1.2.3", 10203);
    addRelease("1.2.4", 10204);

    const { json } = await server.call("GET", "/updates");
    assert.deepEqual(
      json.updates.map((u: any) => u.package),
      ["com.google.android.webview", "com.unowhy.companion"],
    );
    const companion = json.updates[1];
    assert.equal(companion.versionCode, 10204);
    assert.equal(companion.sha256, createHash("sha256").update("apk 1.2.4").digest("hex"));
    assert.equal(fs.existsSync(path.join(dir, "companion-1.2.3.apk")), false, "ancienne version retirée");

    const res = await fetch(`${server.url}/updates/file/companion-1.2.4.apk`, { headers: { authorization: `Bearer ${TOKEN}` } });
    assert.equal(await res.text(), "apk 1.2.4");
  });

  it("updates:essai dépose l'essai du registre, vérifié, et refuse un APK abîmé ou un jeton absent", async () => {
    // Faux paquet générique companion-updates/essai : manifest.json + APK, lus avec le jeton personnel.
    const apk = "apk essai";
    const update = {
      package: "com.unowhy.companion",
      label: "Companion",
      versionCode: 2170,
      versionName: "0.0.2-essai.169",
      file: "companion-0.0.2-essai.169.apk",
      size: apk.length,
      sha256: createHash("sha256").update(apk).digest("hex"),
    };
    let served = apk;
    const registry = http.createServer((req, res) => {
      if (req.headers["private-token"] !== "jeton") return res.writeHead(401).end();
      if (req.url === "/essai/manifest.json") return res.end(JSON.stringify({ updates: [update] }));
      if (req.url === `/essai/${update.file}`) return res.end(served);
      res.writeHead(404).end();
    });
    await new Promise<void>((resolve) => registry.listen(0, "127.0.0.1", resolve));
    const { port } = registry.address() as { port: number };
    const run = (env: Record<string, string>) =>
      promisify(execFile)(process.execPath, ["--import", "tsx", "scripts/updates.ts", "essai"], {
        cwd: path.resolve(import.meta.dirname, ".."),
        env: { ...process.env, DATA_DIR: server.dataDir, UPDATES_URL: `http://127.0.0.1:${port}/essai`, GITLAB_TOKEN: "", ...env },
      });
    const companion = async () =>
      (await server.call("GET", "/updates")).json.updates.find((u: any) => u.package === "com.unowhy.companion");
    try {
      await assert.rejects(run({}), /Jeton absent/);
      await assert.rejects(run({ GITLAB_TOKEN: "faux" }), /jeton refusé/);
      served = "apk abîmé";
      await assert.rejects(run({ GITLAB_TOKEN: "jeton" }), /SHA-256/);
      assert.notEqual((await companion())?.versionName, update.versionName, "rien n'est changé");

      served = apk;
      await run({ GITLAB_TOKEN: "jeton" });
      const added = await companion();
      assert.equal(added.versionCode, 2170);
      assert.equal(added.versionName, "0.0.2-essai.169");
      assert.equal(added.sha256, update.sha256);
      const res = await fetch(`${server.url}/updates/file/${update.file}`, { headers: { authorization: `Bearer ${TOKEN}` } });
      assert.equal(await res.text(), apk);
    } finally {
      registry.close();
    }
  });
});

describe("pilotage hors Mac et Windows", () => {
  it("montre le deck et garde les touches perso, mais répond 501 quand on en touche une", async () => {
    const linux = await startServer({ os: "other" });
    try {
      const deck = await linux.call("GET", "/deck");
      assert.equal(deck.status, 200);
      assert.equal(deck.json.pages.at(-1).title, "Perso");

      const key = { label: "Bonjour", steps: [{ type: "text", text: "Salut" }] };
      assert.equal((await linux.call("PUT", "/deck/keys/0", key)).status, 200);
      assert.deepEqual((await linux.call("GET", "/deck/keys/0")).json.key, key);

      const run = await linux.call("POST", "/deck/run", { id: "k-0" });
      assert.equal(run.status, 501);
      assert.equal(run.json.message, "Pilotage disponible sur macOS et Windows seulement");
      assert.deepEqual(linux.osCalls(), []);
      assert.equal((await linux.call("GET", "/deck/catalog")).status, 501);
    } finally {
      await linux.stop();
    }
  });
});
