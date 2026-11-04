import assert from "node:assert/strict";
import { after, before, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// « Attend une tablette » = page locale affichée au premier plan : /local/status?visible=1.

let server: TestServer;
before(async () => {
  server = await startServer();
});
after(async () => {
  await server.stop();
});

const status = async (query = "") => (await server.call("GET", `/local/status${query}`, undefined, false)).json;

it("/local/status dit waiting selon la visibilité de la page, sans changer l'état quand elle ne le précise pas", async () => {
  assert.equal((await status()).waiting, false);
  assert.equal((await status("?visible=1")).waiting, true);
  assert.equal((await status()).waiting, true);
  assert.equal((await status("?visible=0")).waiting, false);
  assert.equal((await status()).waiting, false);
});
