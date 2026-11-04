import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import http from "node:http";
import { after, before, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// « Attend une tablette » = page locale affichée au premier plan : POST /local/visible {visible}.

let server: TestServer;
let loud: TestServer;
before(async () => {
  server = await startServer();
  // NO_ALERT vide : le compagnon notifie vraiment (via le faux pilotage), comme sur un poste.
  loud = await startServer({ env: { NO_ALERT: "" } });
});
after(async () => {
  await server.stop();
  await loud.stop();
});

const status = async (s: TestServer = server) => (await s.call("GET", "/local/status", undefined, false)).json;
const visible = (visible: unknown, s: TestServer = server) => s.call("POST", "/local/visible", { visible }, false);

it("/local/visible dit waiting selon la visibilité de la page ; /local/status ne change pas l'état", async () => {
  assert.equal((await status()).waiting, false);
  assert.equal((await visible(true)).status, 200);
  assert.equal((await status()).waiting, true);
  assert.equal((await server.call("GET", "/local/status?visible=0", undefined, false)).json.waiting, true, "la lecture ne change rien");
  assert.equal((await visible(false)).status, 200);
  assert.equal((await status()).waiting, false);
});

it("/local/visible refuse un corps mal formé et exige la page locale (ni jeton, ni autre origine)", async () => {
  assert.equal((await visible("peut-être")).status, 400);
  assert.equal((await server.call("POST", "/local/visible", {}, false)).status, 400);
  // Autre nom d'hôte (fetch ne laisse pas changer `Host`) : une page web qui rebondit sur 127.0.0.1 n'est pas la page locale.
  const code = await new Promise<number | undefined>((resolve, reject) => {
    const req = http.request(
      `${server.url}/local/visible`,
      { method: "POST", headers: { "content-type": "application/json", host: "evil.example" } },
      (res) => (res.resume(), resolve(res.statusCode)),
    );
    req.on("error", reject);
    req.end(JSON.stringify({ visible: true }));
  });
  assert.equal(code, 401);
  assert.equal((await status()).waiting, false);
});

/** Parcours de la tablette jusqu'à la notification (demande puis nombre dévoilé). */
async function pairRequest(s: TestServer) {
  const nonce = randomBytes(32).toString("base64url");
  const commit = createHash("sha256").update(nonce).digest("base64url");
  const req = await s.call("POST", "/pair/request", { tablet: "Tablette", commit }, false);
  assert.equal(req.status, 200);
  assert.equal((await s.call("POST", "/pair/reveal", { request: req.json.request, nonce }, false)).status, 200);
}

it("appairage par adresse : l'ordinateur n'attend pas, la page s'ouvre en plus de la notification", async () => {
  await pairRequest(loud);
  const calls = loud.osCalls();
  assert.equal(calls.filter((c) => c.fn === "notify").length, 1);
  assert.deepEqual(
    calls.filter((c) => c.fn === "openTarget").map((c) => c.args),
    [[`http://127.0.0.1:${new URL(loud.url).port}/?section=pairing`]],
  );
});

it("appairage : la page est déjà au premier plan, la notification suffit", async () => {
  const before = loud.osCalls().filter((c) => c.fn === "openTarget").length;
  assert.equal((await visible(true, loud)).status, 200);
  await pairRequest(loud);
  assert.equal(loud.osCalls().filter((c) => c.fn === "openTarget").length, before);
  assert.equal(loud.osCalls().filter((c) => c.fn === "notify").length, 2);
});
