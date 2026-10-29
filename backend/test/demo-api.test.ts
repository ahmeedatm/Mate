import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { afterEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

type Tile = { id: string; label?: string; type?: string; app: string | null };
type Page = { title: string; apps: boolean; editable: boolean; keyPage: number | null; tiles: Tile[] };

let server: TestServer | undefined;
afterEach(async () => {
  await server?.stop();
  server = undefined;
});

const start = async (installed: string[] = []) => {
  server = await startServer();
  server.setOs({ installed });
  return server;
};
const pages = async (s: TestServer): Promise<Page[]> => (await s.call("GET", "/deck")).json.pages;
const persoLabels = async (s: TestServer) =>
  (await pages(s)).find((p) => p.editable)!.tiles.flatMap((t) => (t.type === "empty" ? [] : [t.label]));
const keyPages = async (s: TestServer) => (await pages(s)).filter((p) => p.keyPage !== null);
const pluginsOff = async (s: TestServer): Promise<string[]> => (await s.call("GET", "/deck/features")).json.off;
const filled = (page: Page) => page.tiles.filter((t) => t.type !== "empty");
const taskLabels = async (s: TestServer): Promise<string[]> =>
  (await s.call("GET", "/tasks/recurring")).json.tasks.map((t: { label: string }) => t.label);
const exists = (s: TestServer, name: string) =>
  fs.access(path.join(s.dataDir, name)).then(
    () => true,
    () => false,
  );

describe("démo : personas", () => {
  it("pose touches, tâches et agenda du persona, puis rend l'état d'avant", async () => {
    const s = await start();
    await s.call("PUT", "/deck/keys/0", { label: "Ma touche", steps: [{ type: "text", text: "a" }] });
    await s.call("POST", "/tasks", { label: "Ma tâche", days: [1, 2, 3, 4, 5, 6, 7] });

    const set = await s.call("POST", "/demo/persona", { persona: "sylvie" });
    assert.equal(set.status, 200);
    assert.equal(set.json.persona, "sylvie");
    assert.equal(set.json.keys[0].id, "k-0");
    assert.ok(set.json.keys.length >= 6);
    assert.deepEqual(
      await persoLabels(s),
      set.json.keys.map((k: { label: string }) => k.label),
    );
    assert.ok(!(await taskLabels(s)).includes("Ma tâche"));
    assert.ok((await taskLabels(s)).includes("Rapprochement bancaire"));
    assert.ok(
      (await exists(s, "events.json")) && JSON.parse(await fs.readFile(path.join(s.dataDir, "events.json"), "utf8")).items.length > 0,
    );

    const quit = await s.call("DELETE", "/demo");
    assert.deepEqual(quit.json, { restored: true });
    assert.deepEqual(await persoLabels(s), ["Ma touche"]);
    assert.ok((await taskLabels(s)).includes("Ma tâche"));
    assert.ok(!(await taskLabels(s)).includes("Rapprochement bancaire"));
    // Pas d'agenda ni de page Apps enregistrés avant la démo : ils n'existent pas après.
    assert.equal(await exists(s, "events.json"), false);
    assert.equal(await exists(s, "apps.json"), false);
    assert.equal(await exists(s, "demo.json"), false);
  });

  it("changer de persona garde la sauvegarde d'avant la démo", async () => {
    const s = await start();
    await s.call("PUT", "/deck/keys/0", { label: "Ma touche", steps: [{ type: "text", text: "a" }] });
    await s.call("POST", "/demo/persona", { persona: "julie" });
    const karim = await s.call("POST", "/demo/persona", { persona: "karim" });
    assert.deepEqual(
      await persoLabels(s),
      karim.json.keys.map((k: { label: string }) => k.label),
    );
    await s.call("DELETE", "/demo");
    assert.deepEqual(await persoLabels(s), ["Ma touche"]);
  });

  it("pose les pages de touches nommées du persona, éditables, puis les retire", async () => {
    const s = await start();
    await s.call("POST", "/demo/persona", { persona: "sylvie" });
    const named = await keyPages(s);
    assert.deepEqual(
      named.map((p) => p.title),
      ["Raccourcis Excel", "Libellés", "Dossiers clients"],
    );
    assert.ok(named.every((p, n) => p.editable && p.keyPage === n && p.tiles[0].id === `k${n + 1}-0` && filled(p).length >= 5));

    // Éditée depuis la tablette comme Perso, une touche d'une page nommée est lue, lancée et retirée.
    const put = await s.call("PUT", "/deck/pages/1/keys/9", { label: "Ma touche", steps: [{ type: "text", text: "a" }] });
    assert.equal(put.status, 200);
    assert.equal((await s.call("GET", "/deck/pages/1/keys/9")).json.key.label, "Ma touche");
    assert.equal((await keyPages(s))[1].tiles[9].label, "Ma touche");
    assert.equal((await s.call("POST", "/deck/run", { id: "k2-9" })).status, 200);
    assert.equal((await s.call("DELETE", "/deck/pages/1/keys/9")).status, 200);
    assert.equal((await keyPages(s))[1].tiles[9].type, "empty");
    // Perso n'est pas touchée.
    assert.equal((await persoLabels(s)).includes("Ma touche"), false);

    assert.equal((await s.call("GET", "/deck/pages/7/keys/0")).status, 404);
    assert.equal((await s.call("PUT", "/deck/pages/7/keys/0", { label: "x", steps: [{ type: "text", text: "a" }] })).status, 404);

    // Un autre persona remplace les pages ; quitter les retire.
    await s.call("POST", "/demo/persona", { persona: "camille" });
    assert.deepEqual(
      (await keyPages(s)).map((p) => p.title),
      ["Travail", "Maison"],
    );
    await s.call("POST", "/demo/persona", { persona: "karim" });
    assert.deepEqual(await keyPages(s), []);
    await s.call("DELETE", "/demo");
    assert.deepEqual(await keyPages(s), []);
    assert.equal(await exists(s, "pages.json"), false);
  });

  it("coupe les plugins du persona, les choix d'avant la démo sont rendus", async () => {
    const s = await start();
    await s.call("POST", "/plugins", { choices: { pad: false } });

    await s.call("POST", "/demo/persona", { persona: "sylvie" });
    const off = await pluginsOff(s);
    for (const id of ["discord", "mixer", "pad", "media", "claude"]) assert.ok(off.includes(id), id);
    assert.ok(!off.includes("open"));
    assert.ok((await s.call("GET", "/plugins")).json.plugins.every((p: { fresh: boolean }) => !p.fresh));

    await s.call("POST", "/demo/persona", { persona: "leo" });
    const leo = await pluginsOff(s);
    assert.ok(!leo.includes("claude") && !leo.includes("open"));

    await s.call("DELETE", "/demo");
    assert.deepEqual(await pluginsOff(s), ["pad"]);
  });

  it("sans choix de plugins avant la démo, rien n'est choisi après", async () => {
    const s = await start();
    await s.call("POST", "/demo/persona", { persona: "karim" });
    assert.ok((await pluginsOff(s)).length >= 5);
    await s.call("DELETE", "/demo");
    assert.deepEqual(await pluginsOff(s), []);
    assert.equal(await exists(s, "plugins.json"), false);
  });

  it("la page Apps ne garde que les applis installées", async () => {
    const s = await start(["Microsoft Excel", "Microsoft Word"]);
    await s.call("POST", "/demo/persona", { persona: "sylvie" });
    const apps = (await pages(s)).find((p) => p.apps)!.tiles.map((t) => t.app);
    assert.deepEqual(apps, ["Microsoft Excel", "Microsoft Word"]);
  });

  it("quitter sans démo en cours ne change rien", async () => {
    const s = await start();
    await s.call("PUT", "/deck/keys/0", { label: "Ma touche", steps: [{ type: "text", text: "a" }] });
    assert.deepEqual((await s.call("DELETE", "/demo")).json, { restored: false });
    assert.deepEqual(await persoLabels(s), ["Ma touche"]);
  });

  it("refuse un persona inconnu et l'absence de jeton", async () => {
    const s = await start();
    assert.equal((await s.call("POST", "/demo/persona", { persona: "inconnu" })).status, 400);
    assert.equal((await s.call("POST", "/demo/persona", { persona: "leo" }, false)).status, 401);
    assert.equal((await s.call("DELETE", "/demo", undefined, false)).status, 401);
  });
});
