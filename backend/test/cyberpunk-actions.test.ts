import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, afterEach, before, beforeEach, describe, it } from "node:test";
import { fakeMod, fakeSteam } from "./cyberpunk-fakemod.ts";
import { startServer, type TestServer } from "./server.ts";

const { steam, modDir } = fakeSteam();
const env = { MATE_STEAM_PATH: steam, MATE_CYBERPUNK_ACK_MS: "700" };
let server: TestServer;
let mod: ReturnType<typeof fakeMod> | undefined;
const call = (m: string, r: string, b?: unknown) => server.call(m, r, b);
const act = (body: object) => call("POST", "/cyberpunk/action", body);

before(async () => {
  server = await startServer({ os: "windows", env });
});
after(async () => {
  await server?.stop();
  fs.rmSync(steam, { recursive: true, force: true });
});
beforeEach(() => {
  fs.rmSync(modDir, { recursive: true, force: true });
});
afterEach(() => mod?.stop());

describe("POST /cyberpunk/action", () => {
  it("écrit l'ordre dans commands.json, attend l'ack et le rend", async () => {
    mod = fakeMod(modDir);
    const r = await act({ type: "call", contact: "judy" });
    assert.equal(r.status, 200);
    assert.equal(r.json.ok, true);
    assert.equal(r.json.res, "call");
    assert.deepEqual(
      mod.executed.map((c) => ({ t: c.t, contact: c.contact, id: c.id })),
      [{ t: "call", contact: "judy", id: r.json.id }],
    );
    const file = JSON.parse(fs.readFileSync(path.join(modDir, "commands.json"), "utf8"));
    assert.equal(file.v, 1);
  });

  it("chaque type devient son ordre", async () => {
    mod = fakeMod(modDir);
    await act({ type: "answer" });
    await act({ type: "hangup" });
    await act({ type: "summon" });
    await act({ type: "use", tdb: "Items.FirstAidWhiffV0" });
    await act({ type: "track", kind: "quest", id: "q101_01" });
    assert.deepEqual(
      mod.executed.map(({ id, ...c }) => c),
      [
        { t: "answer" },
        { t: "hangup" },
        { t: "summon" },
        { t: "use", tdb: "Items.FirstAidWhiffV0" },
        { t: "track", kind: "quest", ref: "q101_01" },
      ],
    );
  });

  it("ids strictement croissants, 20 ordres au plus dans le fichier", async () => {
    mod = fakeMod(modDir);
    const ids: number[] = [];
    for (let i = 0; i < 23; i++) ids.push((await act({ type: "hangup" })).json.id);
    assert.deepEqual(
      ids,
      [...ids].sort((a, b) => a - b),
    );
    assert.equal(new Set(ids).size, 23);
    const file = JSON.parse(fs.readFileSync(path.join(modDir, "commands.json"), "utf8"));
    assert.equal(file.cmds.length, 20);
    assert.equal(file.cmds.at(-1).id, ids.at(-1));
  });

  it("ids encore croissants après un redémarrage du backend", async () => {
    mod = fakeMod(modDir);
    const first = (await act({ type: "hangup" })).json.id;
    const second = await startServer({ os: "windows", env, dataDir: server.dataDir });
    const again = (await second.call("POST", "/cyberpunk/action", { type: "hangup" })).json.id;
    await second.stop();
    assert.ok(again > first, `${again} <= ${first}`);
  });

  it("504 si le mod ne répond pas", async () => {
    mod = fakeMod(modDir, { silent: true });
    const r = await act({ type: "answer" });
    assert.equal(r.status, 504);
  });

  it("409 hors partie (menu, jeu fermé)", async () => {
    mod = fakeMod(modDir);
    mod.setInGame(false);
    await new Promise((r) => setTimeout(r, 100));
    assert.equal((await act({ type: "answer" })).status, 409);
    mod.stop();
    fs.rmSync(modDir, { recursive: true, force: true });
    assert.equal((await act({ type: "answer" })).status, 409);
  });

  it("validation stricte", async () => {
    mod = fakeMod(modDir);
    for (const body of [
      { type: "danse" },
      { type: "call" },
      { type: "call", contact: "../judy" },
      { type: "call", contact: "Judy Alvarez" },
      { type: "use" },
      { type: "use", tdb: "Weapons.Lexington" },
      { type: "use", tdb: "Items.A/../B" },
      { type: "track" },
      { type: "track", kind: "autre", id: "x" },
      { type: "track", kind: "pin" },
      {},
    ])
      assert.equal((await act(body)).status, 400, JSON.stringify(body));
    assert.equal(mod.executed.length, 0);
  });
});
