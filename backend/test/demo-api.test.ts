import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { afterEach, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

type Tile = { id: string; label?: string; type?: string; app: string | null };
type Page = { title: string; apps: boolean; editable: boolean; tiles: Tile[] };

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
