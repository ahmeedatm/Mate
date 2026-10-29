import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

type PluginView = { id: string; kind: string; name: string; app: string | null; on: boolean; fresh: boolean };
type PageView = { title: string; app: string | null; apps: boolean; media: boolean; tiles: { id: string; app: string | null }[] };

let server: TestServer | undefined;
afterEach(async () => {
  await server?.stop();
  server = undefined;
});

async function start(installed: string[] = [], os?: "mac" | "windows" | "other") {
  server = await startServer({ os });
  server.setOs({ installed });
  return server;
}

const plugins = async (s: TestServer): Promise<PluginView[]> => (await s.call("GET", "/plugins")).json.plugins;
const titles = async (s: TestServer) => ((await s.call("GET", "/deck")).json.pages as PageView[]).map((p) => p.title);

describe("GET /plugins", () => {
  it("première utilisation : applis trouvées sur l'ordinateur et plugins intégrés, tous actifs et à proposer", async () => {
    const s = await start(["Discord", "Notes"]);
    const list = await plugins(s);
    assert.deepEqual(
      list.map((p) => [p.id, p.kind, p.on, p.fresh]),
      [
        ["discord", "app", true, true],
        ["open", "native", true, true],
        ["mixer", "native", true, true],
        ["pad", "native", true, true],
        ["media", "native", true, true],
      ],
    );
    assert.equal(list[0].app, "Discord");
  });

  it("une appli absente n'est pas proposée", async () => {
    const s = await start([]);
    assert.ok(!(await plugins(s)).some((p) => p.kind === "app"));
  });

  it("ordinateur non pilotable : aucun plugin", async () => {
    const s = await start(["Discord"], "other");
    assert.deepEqual(
      (await plugins(s)).map((p) => p.id),
      [],
    );
  });
});

describe("POST /plugins", () => {
  it("enregistre les choix : plus rien à proposer, l'appli installée ensuite l'est", async () => {
    const s = await start(["Discord"]);
    const { status, json } = await s.call("POST", "/plugins", {
      choices: { discord: true, open: true, mixer: false, pad: true, media: true },
    });
    assert.equal(status, 200);
    assert.ok(json.plugins.every((p: PluginView) => !p.fresh));
    assert.equal(json.plugins.find((p: PluginView) => p.id === "mixer").on, false);
    s.setOs({ installed: ["Discord", "Microsoft Teams"] });
    assert.deepEqual(
      (await plugins(s)).filter((p) => p.fresh).map((p) => p.id),
      ["teams"],
    );
  });

  it("refuse un plugin inconnu ou une valeur qui n'est pas un booléen", async () => {
    const s = await start();
    assert.equal((await s.call("POST", "/plugins", { choices: { inconnu: true } })).status, 400);
    assert.equal((await s.call("POST", "/plugins", { choices: { open: "oui" } })).status, 400);
  });

  it("coupé : plugin listé dans `off` de /deck/features", async () => {
    const s = await start();
    assert.deepEqual((await s.call("GET", "/deck/features")).json.off, []);
    await s.call("POST", "/plugins", { choices: { open: false, pad: false } });
    assert.deepEqual((await s.call("GET", "/deck/features")).json.off, ["open", "pad"]);
  });

  it("Discord coupé : sa page disparaît du deck ; rallumé, elle revient", async () => {
    const s = await start(["Discord"]);
    assert.ok((await titles(s)).includes("Discord"));
    await s.call("POST", "/plugins", { choices: { discord: false } });
    assert.ok(!(await titles(s)).includes("Discord"));
    await s.call("POST", "/plugins", { choices: { discord: true } });
    assert.ok((await titles(s)).includes("Discord"));
  });

  it("Teams coupé : plus de page Réunion ni de raccourcis Teams sur l'Accueil", async () => {
    const s = await start(["Microsoft Teams"]);
    assert.equal((await s.call("GET", "/deck/features")).json.teams, true);
    await s.call("POST", "/plugins", { choices: { teams: false } });
    assert.equal((await s.call("GET", "/deck/features")).json.teams, false);
    assert.ok(!(await titles(s)).includes("Réunion"));
  });

  it("Média coupé : plus de page Média, les autres tuiles gardent leur identifiant", async () => {
    const s = await start();
    const before = (await s.call("GET", "/deck")).json.pages as PageView[];
    await s.call("POST", "/plugins", { choices: { media: false } });
    const after = (await s.call("GET", "/deck")).json.pages as PageView[];
    assert.ok(!after.some((p) => p.media));
    const ids = (pages: PageView[]) => pages.find((p) => p.title === "Raccourcis")!.tiles.map((t) => t.id);
    assert.deepEqual(ids(after), ids(before));
  });

  it("appli activée absente de la page Apps enregistrée : elle y est ajoutée", async () => {
    const s = await start(["Notes"]);
    // Page Apps enregistrée avant l'installation de Discord : Discord n'y est pas.
    await s.call("POST", "/deck/apps", { name: "Notes" });
    s.setOs({ installed: ["Notes", "Discord"] });
    const appsPage = async () => ((await s.call("GET", "/deck")).json.pages as PageView[]).find((p) => p.apps)!.tiles.map((t) => t.app);
    assert.deepEqual(await appsPage(), ["Notes"]);
    await s.call("POST", "/plugins", { choices: { discord: true } });
    assert.deepEqual(await appsPage(), ["Notes", "Discord"]);
  });
});
