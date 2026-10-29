import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

describe("GET /mail/summary", () => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-mail-api-"));
  let server: TestServer;
  before(async () => {
    server = await startServer({ dataDir });
  });
  after(async () => {
    await server?.stop();
    fs.rmSync(dataDir, { recursive: true, force: true });
  });

  it("jeton obligatoire", async () => {
    assert.equal((await server.call("GET", "/mail/summary", undefined, false)).status, 401);
  });

  it("aucun compte Microsoft relié : no-account", async () => {
    const res = await server.call("GET", "/mail/summary");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { status: "no-account" });
  });

  it("compte relié avant le courrier (sans Mail.Read) : reconnect", async () => {
    fs.writeFileSync(path.join(dataDir, "accounts.json"), JSON.stringify({ microsoft: { email: "moi@exemple.fr", refreshToken: "r1" } }));
    assert.deepEqual((await server.call("GET", "/mail/summary")).json, { status: "reconnect" });
  });
});
