import assert from "node:assert/strict";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { after, before, describe, it } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { startServer, type TestServer } from "./server.ts";

// Porte MCP du compagnon (routes/mcp.ts) : un vrai client MCP, comme Claude Code ou Cursor, sans jeton depuis l'ordinateur.

let server: TestServer;
let client: Client;
before(async () => {
  server = await startServer();
  server.setOs({ installed: ["Notes", "Discord"] });
  client = new Client({ name: "test-ia", version: "1.0.0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(`${server.url}/mcp`)));
});
after(async () => {
  await client?.close();
  await server?.stop();
});

type Result = { isError?: boolean; content: { type: string; text?: string }[] };
const call = async (name: string, args: Record<string, unknown> = {}) => (await client.callTool({ name, arguments: args })) as Result;
const json = (r: Result) => JSON.parse(r.content[0].text!);

describe("porte MCP", () => {
  it("liste les outils de pilotage et de réglage", async () => {
    const names = (await client.listTools()).tools.map((t) => t.name);
    for (const name of [
      "tablette_etat",
      "deck_touche_affecter",
      "tablette_reglages",
      "tablette_regler",
      "profils_lire",
      "profils_ecrire",
      "plugins_choisir",
      "deck_appli_ajouter",
      "deck_site_ajouter",
    ]) {
      assert.ok(names.includes(name), name);
    }
  });

  it("marque les outils qui suppriment", async () => {
    const tools = (await client.listTools()).tools;
    const destructive = tools.filter((t) => t.annotations?.destructiveHint).map((t) => t.name);
    assert.ok(destructive.includes("deck_applis_reinitialiser"));
    assert.ok(destructive.includes("deck_touche_effacer"));
  });

  it("refuse un hôte qui n'est pas local (DNS rebinding)", async () => {
    const { port } = new URL(server.url);
    const status = await new Promise<number>((resolve, reject) => {
      const req = http.request(
        {
          host: "127.0.0.1",
          port,
          path: "/mcp",
          method: "POST",
          headers: { host: `exemple.com:${port}`, "content-type": "application/json", accept: "application/json, text/event-stream" },
        },
        (res) => {
          res.resume();
          resolve(res.statusCode ?? 0);
        },
      );
      req.on("error", reject);
      req.end(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }));
    });
    assert.equal(status, 401);
  });

  it("refuse un corps qui n'est pas du JSON (formulaire d'un autre site)", async () => {
    const res = await fetch(`${server.url}/mcp`, {
      method: "POST",
      headers: { "content-type": "text/plain", accept: "application/json, text/event-stream" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }),
    });
    assert.equal(res.status, 415);
  });

  it("répond 405 à GET : pas de flux ouvert", async () => {
    assert.equal((await fetch(`${server.url}/mcp`)).status, 405);
  });
});

describe("réglages", () => {
  it("affecte une touche Perso avec une icône, puis la vide", async () => {
    const set = await call("deck_touche_affecter", {
      numero: 2,
      libelle: "Verrouiller",
      icone: "lock",
      etapes: [{ type: "system", command: "lock" }],
    });
    assert.ok(!set.isError, set.content[0].text);
    const listed = json(await call("deck_touches_lister"));
    assert.deepEqual(listed.perso[1], { numero: 2, label: "Verrouiller", symbol: "lock", steps: [{ type: "system", command: "lock" }] });
    assert.ok(listed.icones.includes("lock"));

    assert.ok(!(await call("deck_touche_effacer", { numero: 2 })).isError);
    assert.deepEqual(json(await call("deck_touches_lister")).perso[1], { numero: 2, vide: true });
  });

  it("une page nommée inconnue est refusée avec la raison", async () => {
    const res = await call("deck_touche_affecter", { numero: 1, page: 9, libelle: "X", etapes: [{ type: "wait", ms: 10 }] });
    assert.equal(res.isError, true);
    assert.equal(res.content[0].text, "Page inconnue");
  });

  it("ajoute puis retire une appli et un site du deck", async () => {
    const add = await call("deck_appli_ajouter", { nom: "Discord" });
    assert.ok(!add.isError, add.content[0].text);
    const apps = json(await call("deck_applis_lister"));
    const discord = apps.apps.find((a: { name: string }) => a.name === "Discord");
    assert.ok(discord);
    assert.ok(!(await call("deck_appli_retirer", { id: discord.id })).isError);
    assert.equal((await call("deck_appli_ajouter", { nom: "Absente" })).content[0].text, "Appli introuvable sur l'ordinateur");

    assert.ok(!(await call("deck_site_ajouter", { adresse: "netflix.com", libelle: "Netflix" })).isError);
    const links = json(await call("deck_sites_lister")).links;
    assert.equal(links.length, 1);
    assert.ok(!(await call("deck_site_retirer", { id: links[0].id })).isError);
    assert.deepEqual(json(await call("deck_sites_lister")).links, []);
  });

  it("coupe un plugin", async () => {
    const before = json(await call("plugins_lister"));
    const id = before[0].id;
    assert.ok(!(await call("plugins_choisir", { choix: { [id]: false } })).isError);
    assert.equal(json(await call("plugins_lister")).find((p: { id: string }) => p.id === id).on, false);
    await call("plugins_choisir", { choix: { [id]: true } });
  });

  it("sans tablette, les réglages le disent", async () => {
    assert.equal(json(await call("tablette_reglages")).connectee, false);
    const res = await call("tablette_regler", { theme: "CONSOLE_JOUR" });
    assert.equal(res.isError, true);
    assert.equal(res.content[0].text, "Tablette non connectée");
  });

  it("change le thème et la luminosité de la tablette connectée", async () => {
    const settings = { theme: "CONSOLE", brightness: 0.6, volume: 0.4, nightDim: true, focusDnd: true };
    const state = { mode: "home", app: "1.4.0", name: "Tablette", model: "Tab 4.2", settings };
    const polling = server.call("POST", "/tablet/poll", { state });
    await new Promise((r) => setTimeout(r, 300));

    const result = call("tablette_regler", { theme: "CONSOLE_JOUR", luminosite: 30 });
    const [order] = (await polling).json.commands;
    assert.deepEqual(order.command, { type: "settings", theme: "CONSOLE_JOUR", brightness: 0.3 });
    await server.call("POST", "/tablet/result", {
      id: order.id,
      ok: true,
      state: { ...state, settings: { ...settings, theme: "CONSOLE_JOUR" } },
    });
    assert.equal(json(await result).reglages.theme, "CONSOLE_JOUR");
  });

  it("refuse un changement de profils vide", async () => {
    const res = await call("profils_ecrire", {});
    assert.equal(res.isError, true);
  });
});

describe("pont stdio (Claude Desktop)", () => {
  const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const bridge = (url: string) =>
    new StdioClientTransport({ command: process.execPath, args: ["--import", "tsx", "src/mcp-bridge.ts", url], cwd: backendDir });

  it("relaie les outils de la porte HTTP", async () => {
    const desktop = new Client({ name: "claude-desktop", version: "1.0.0" });
    await desktop.connect(bridge(`${server.url}/mcp`));
    try {
      const names = (await desktop.listTools()).tools.map((t) => t.name);
      assert.ok(names.includes("profils_ecrire"));
      const res = (await desktop.callTool({ name: "tablette_reglages", arguments: {} })) as Result;
      assert.ok(json(res).themes.includes("CONSOLE_JOUR"));
    } finally {
      await desktop.close();
    }
  });

  it("compagnon arrêté : le client reçoit une erreur lisible", async () => {
    const desktop = new Client({ name: "claude-desktop", version: "1.0.0" });
    await assert.rejects(desktop.connect(bridge("http://127.0.0.1:9/mcp")), /Compagnon Mate injoignable/);
    await desktop.close();
  });
});
