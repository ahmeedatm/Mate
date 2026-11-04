import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { startFakeGitlab, type FakeGitlab } from "./fake-gitlab.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

// `PUT /local/accounts/gitlab` écrit un secret : réservée à la page locale (127.0.0.1, hôte local), jamais depuis un autre
// site ni un autre ordinateur, avec ou sans jeton d'appairage.

const JETON = "glpat-jeton-fermeture-de-test";
let gitlab: FakeGitlab;
let withToken: TestServer;
let noToken: TestServer;
before(async () => {
  gitlab = await startFakeGitlab();
  gitlab.tokens[JETON] = ["api"];
  withToken = await startServer({ env: { GITLAB_URL: gitlab.url } });
  noToken = await startServer({ env: { GITLAB_URL: gitlab.url, COMPANION_TOKEN: "" } });
});
after(async () => {
  await withToken?.stop();
  await noToken?.stop();
  await gitlab?.stop();
});

/** `fetch` ne laisse pas choisir `Host` : requête brute. */
const put = (s: TestServer, headers: Record<string, string>, body = JSON.stringify({ token: JETON })) =>
  new Promise<{ status: number; body: string }>((resolve, reject) => {
    const u = new URL(s.url);
    const req = http.request(
      {
        host: u.hostname,
        port: u.port,
        path: "/local/accounts/gitlab",
        method: "PUT",
        headers: { "content-length": Buffer.byteLength(body), ...headers },
      },
      (res) => {
        let text = "";
        res.on("data", (c) => (text += c));
        res.on("end", () => resolve({ status: res.statusCode ?? 0, body: text }));
      },
    );
    req.on("error", reject);
    req.end(body);
  });
const json = { "content-type": "application/json" };
const savedFile = (s: TestServer) => path.join(s.dataDir, "gitlab.json");

describe("PUT /local/accounts/gitlab : fermeture", () => {
  it("Host étranger sans jeton d'appairage : 401, rien d'enregistré", async () => {
    const res = await put(withToken, { ...json, host: "evil.test:3000" });
    assert.equal(res.status, 401);
    assert.equal(fs.existsSync(savedFile(withToken)), false);
  });

  it("Host étranger même avec un jeton d'appairage valide : 403, rien d'enregistré", async () => {
    const res = await put(withToken, { ...json, host: "evil.test:3000", authorization: `Bearer ${TOKEN}` });
    assert.equal(res.status, 403);
    assert.equal(fs.existsSync(savedFile(withToken)), false);
  });

  it("Origin étrangère en mode sans jeton : 403, rien d'enregistré", async () => {
    for (const origin of ["https://exemple.com", "null", "http://127.0.0.1.evil.test"]) {
      const res = await put(noToken, { ...json, origin });
      assert.equal(res.status, 403, origin);
    }
    assert.equal(fs.existsSync(savedFile(noToken)), false);
  });

  it("corps text/plain (requête « simple » d'un autre site) : refusé, rien d'enregistré", async () => {
    for (const s of [withToken, noToken]) {
      const res = await put(s, { "content-type": "text/plain" });
      assert.equal(res.status, 400);
      assert.equal(fs.existsSync(savedFile(s)), false);
    }
  });

  it("la page locale (127.0.0.1, JSON, Origin local) passe", async () => {
    const res = await put(noToken, { ...json, origin: noToken.url });
    assert.equal(res.status, 200);
  });
});
