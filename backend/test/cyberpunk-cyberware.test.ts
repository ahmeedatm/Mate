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
const call = (m: string, r: string) => server.call(m, r);

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

describe("GET /cyberpunk/cyberware", () => {
  it("vide tant que le mod n'a rien écrit (mod ancien)", async () => {
    mod = fakeMod(modDir);
    const r = await call("GET", "/cyberpunk/cyberware");
    assert.equal(r.status, 200);
    assert.deepEqual(r.json, { v: 1, zones: {}, owned: [] });
  });

  it("rend cyberware.json du mod", async () => {
    mod = fakeMod(modDir);
    const body = { v: 1, body: "woman", capacity: { used: 40, max: 120 }, zones: { EyesCW: [{ locked: true }] }, owned: [] };
    fs.writeFileSync(path.join(modDir, "cyberware.json"), JSON.stringify(body));
    const r = await call("GET", "/cyberpunk/cyberware");
    assert.deepEqual(r.json, body);
  });
});
