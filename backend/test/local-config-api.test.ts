import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { after, before, beforeEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-local-config-"));
fs.writeFileSync(
  path.join(dataDir, "pages.json"),
  JSON.stringify({ pages: [{ title: "Réunions", slots: [{ label: "Micro", steps: [{ type: "system", command: "mute-mic" }] }] }] }),
);

let server: TestServer;
before(async () => {
  server = await startServer({ dataDir });
  server.setOs({ installed: ["Notes", "Discord"] });
});
after(async () => {
  await server?.stop();
  fs.rmSync(dataDir, { recursive: true, force: true });
});
beforeEach(async () => {
  await server.call("DELETE", "/local/config/apps", undefined, false);
  for (const l of (await server.call("GET", "/local/config/links", undefined, false)).json.links) {
    await server.call("DELETE", `/local/config/links/${l.id}`, undefined, false);
  }
  for (let slot = 0; slot < 3; slot++) await server.call("DELETE", `/local/config/keys/${slot}`, undefined, false);
});

// La page locale n'a pas de jeton : `auth: false`.
const local = (method: string, route: string, body?: unknown) => server.call(method, `/local/config/${route}`, body, false);
const key = (label: string) => ({ label, symbol: "lock", color: "#CA0538", steps: [{ type: "text", text: "bonjour" }] });
const message = "Demande invalide : mets à jour l'appli de la tablette";

describe("sans jeton, en local seulement", () => {
  it("refuse un hôte qui n'est pas local (DNS rebinding)", async () => {
    const { port } = new URL(server.url);
    const status = await new Promise<number>((resolve, reject) => {
      http
        .get({ host: "127.0.0.1", port, path: "/local/config/plugins", headers: { host: `exemple.com:${port}` } }, (res) => {
          res.resume();
          resolve(res.statusCode ?? 0);
        })
        .on("error", reject);
    });
    assert.equal(status, 401);
  });

  it("refuse un chemin hors du motif", async () => {
    assert.equal((await local("GET", "Plugins")).status, 401);
  });

  it("une écriture à corps non JSON est refusée (un formulaire d'un autre site ne passe pas)", async () => {
    const res = await fetch(`${server.url}/local/config/links`, {
      method: "POST",
      headers: { "content-type": "text/plain" },
      body: JSON.stringify({ url: "netflix.com" }),
    });
    assert.equal(res.status, 400);
    assert.deepEqual((await local("GET", "links")).json.links, []);
  });
});

describe("touches", () => {
  it("liste Perso, les pages nommées, les symboles, les commandes système et le catalogue", async () => {
    const { status, json } = await local("GET", "keys");
    assert.equal(status, 200);
    assert.equal(json.perso.title, "Perso");
    assert.equal(json.perso.keys.length, 14);
    assert.deepEqual(
      json.pages.map((p: { title: string; keys: unknown[] }) => [p.title, p.keys.length, (p.keys[0] as { label: string }).label]),
      [["Réunions", 14, "Micro"]],
    );
    assert.ok(json.symbols.includes("lock"));
    assert.ok(json.systemCommands.includes("mute-mic"));
    assert.deepEqual(json.catalog.apps, ["Notes", "Discord"]);
  });

  it("enregistre puis vide une touche Perso : même fichier que la tablette", async () => {
    assert.equal((await local("PUT", "keys/2", key("Salut"))).status, 200);
    assert.equal((await server.call("GET", "/deck/keys/2")).json.key.label, "Salut");
    assert.equal((await local("GET", "keys")).json.perso.keys[2].color, "#CA0538");
    assert.equal((await local("DELETE", "keys/2")).status, 200);
    assert.equal((await server.call("GET", "/deck/keys/2")).json.key, null);
  });

  it("macro : plusieurs étapes gardées dans l'ordre", async () => {
    const steps = [
      { type: "app", name: "Notes" },
      { type: "wait", ms: 500 },
      { type: "keys", keys: "cmd+n" },
    ];
    assert.equal((await local("PUT", "keys/1", { label: "Macro", steps })).status, 200);
    assert.deepEqual((await server.call("GET", "/deck/keys/1")).json.key.steps, steps);
  });

  it("refuse une touche invalide : corps vide, étape inconnue, couleur, emplacement hors limites", async () => {
    for (const body of [
      {},
      { label: "", steps: [{ type: "text", text: "a" }] },
      { label: "X", steps: [] },
      { label: "X", steps: [{ type: "inconnu" }] },
      { label: "X", color: "rouge", steps: [{ type: "text", text: "a" }] },
      { label: "X", symbol: "inconnu", steps: [{ type: "text", text: "a" }] },
      { label: "X", steps: [{ type: "system", command: "reboot" }] },
    ]) {
      const res = await local("PUT", "keys/0", body);
      assert.equal(res.status, 400, JSON.stringify(body));
      assert.equal(res.json.error, message);
    }
    assert.equal((await local("PUT", "keys/100", key("X"))).status, 400);
    assert.equal((await local("DELETE", "keys/100")).status, 400);
    assert.equal((await server.call("GET", "/deck/keys/0")).json.key, null);
  });

  it("page nommée : enregistre et vide une touche, page inconnue = 404", async () => {
    assert.equal((await local("PUT", "pages/0/keys/3", key("Ajout"))).status, 200);
    assert.equal((await server.call("GET", "/deck/pages/0/keys/3")).json.key.label, "Ajout");
    assert.equal((await local("DELETE", "pages/0/keys/3")).status, 200);
    assert.equal((await server.call("GET", "/deck/pages/0/keys/3")).json.key, null);

    const put = await local("PUT", "pages/7/keys/0", key("X"));
    assert.equal(put.status, 404);
    assert.equal(put.json.error, "Page inconnue");
    assert.equal((await local("DELETE", "pages/7/keys/0")).status, 404);
    assert.equal((await local("PUT", "pages/0/keys/0", { label: "X" })).status, 400);
  });
});

describe("plugins", () => {
  it("liste ceux de l'ordinateur et enregistre les choix", async () => {
    const before = (await local("GET", "plugins")).json.plugins as { id: string; on: boolean }[];
    assert.ok(before.every((p) => p.on));
    const { status, json } = await local("PUT", "plugins", { choices: { mixer: false } });
    assert.equal(status, 200);
    assert.equal(json.plugins.find((p: { id: string }) => p.id === "mixer").on, false);
    assert.deepEqual((await server.call("GET", "/deck/features")).json.off, ["mixer"]);
    await local("PUT", "plugins", { choices: { mixer: true } });
    assert.deepEqual((await server.call("GET", "/deck/features")).json.off, []);
  });

  it("rend aussi les applis absentes avec present: false ; la tablette ne reçoit que les présentes, sans ce champ", async () => {
    const all = (await local("GET", "plugins")).json.plugins as { id: string; present: boolean }[];
    assert.equal(all.find((p) => p.id === "discord")?.present, true);
    assert.equal(all.find((p) => p.id === "teams")?.present, false);
    const tablet = (await server.call("GET", "/plugins")).json.plugins as Record<string, unknown>[];
    assert.ok(!tablet.some((p) => p.id === "teams"));
    assert.ok(tablet.every((p) => !("present" in p)));
    const put = (await local("PUT", "plugins", { choices: { mixer: true } })).json.plugins as { id: string }[];
    assert.ok(put.some((p) => p.id === "teams"));
  });

  it("appli activée et installée : ajoutée à la page Apps", async () => {
    await local("PUT", "plugins", { choices: { discord: true } });
    const names = (await local("GET", "apps")).json.apps.map((a: { name: string }) => a.name);
    assert.ok(names.includes("Discord"));
  });

  it("refuse un plugin inconnu, une valeur qui n'est pas un booléen, un corps sans choices", async () => {
    for (const body of [{ choices: { inconnu: true } }, { choices: { open: "oui" } }, {}]) {
      const res = await local("PUT", "plugins", body);
      assert.equal(res.status, 400);
      assert.equal(res.json.error, message);
    }
  });
});

describe("applis du Deck", () => {
  it("ajoute une appli installée, la liste, la retire", async () => {
    const added = await local("POST", "apps", { name: "Notes" });
    assert.equal(added.status, 200);
    const list = (await local("GET", "apps")).json;
    assert.deepEqual(list.apps, [{ id: added.json.id, label: "Notes", name: "Notes" }]);
    assert.deepEqual(list.installed, ["Notes", "Discord"]);
    assert.equal((await local("DELETE", `apps/${added.json.id}`)).status, 200);
    assert.deepEqual((await local("GET", "apps")).json.apps, []);
  });

  it("appli absente de l'ordinateur : 404 ; corps invalide : 400", async () => {
    const missing = await local("POST", "apps", { name: "Inconnue" });
    assert.equal(missing.status, 404);
    assert.equal(missing.json.error, "Appli introuvable sur l'ordinateur");
    for (const body of [{}, { name: "" }, { name: {} }]) assert.equal((await local("POST", "apps", body)).status, 400);
  });

  it("retirer un identifiant inconnu ne change rien ; DELETE sans identifiant revient au profil de base", async () => {
    await local("POST", "apps", { name: "Notes" });
    assert.equal((await local("DELETE", "apps/a-0000000000")).status, 200);
    assert.equal((await local("GET", "apps")).json.apps.length, 1);
    assert.equal((await local("DELETE", "apps")).status, 200);
    assert.deepEqual(
      (await local("GET", "apps")).json.apps.map((a: { name: string }) => a.name),
      (await server.call("GET", "/deck")).json.pages.find((p: { apps: boolean }) => p.apps).tiles.map((t: { app: string }) => t.app),
    );
  });
});

describe("sites de la page Média", () => {
  it("ajoute (adresse nue ou avec nom), liste avec les suggestions, retire", async () => {
    const a = await local("POST", "links", { url: "netflix.com" });
    const b = await local("POST", "links", { url: "https://exemple.test/radio", label: "Radio" });
    assert.equal(a.status, 200);
    const { links, suggestions } = (await local("GET", "links")).json;
    assert.deepEqual(
      links.map((l: { label: string; url: string }) => [l.label, l.url]),
      [
        ["netflix.com", "https://netflix.com"],
        ["Radio", "https://exemple.test/radio"],
      ],
    );
    assert.ok(suggestions.some((s: { label: string }) => s.label === "YouTube"));
    assert.equal(links[1].id, b.json.id);
    assert.equal((await local("DELETE", `links/${a.json.id}`)).status, 200);
    assert.equal((await local("GET", "links")).json.links.length, 1);
    assert.equal((await local("DELETE", "links/l-0000000000")).status, 200);
    assert.equal((await local("GET", "links")).json.links.length, 1);
  });

  it("adresse invalide ou non web : 400 en français ; corps invalide : 400", async () => {
    for (const url of ["ftp://exemple.test", "http://"]) {
      const res = await local("POST", "links", { url });
      assert.equal(res.status, 400, url);
      assert.equal(res.json.error, "Adresse invalide (ex. netflix.com)");
    }
    for (const body of [{}, { url: "" }, { url: "a.fr", label: "x".repeat(41) }]) {
      assert.equal((await local("POST", "links", body)).status, 400);
    }
  });
});
