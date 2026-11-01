import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

// Deux comptes d'agenda connectés : celui qui tombe en panne ne fait pas perdre les événements de l'autre.
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-accounts-"));
process.env.DATA_DIR = dir;
process.env.ACCOUNTS_FILE = path.join(import.meta.dirname, "accounts-vide.json");
process.env.MICROSOFT_CLIENT_ID = "ms";
process.env.GOOGLE_CLIENT_ID = "g";
fs.writeFileSync(
  path.join(dir, "accounts.json"),
  JSON.stringify({ microsoft: { email: "a@outlook.com", refreshToken: "r1" }, google: { email: "b@gmail.com", refreshToken: "r2" } }),
);
const { accountEvents } = await import("../src/accounts.ts");

test("un compte en panne : les événements de l'autre restent, son erreur est rendue", async () => {
  const start = new Date();
  globalThis.fetch = (async (url: string) => {
    if (String(url).includes("/token")) return Response.json({ access_token: "t", expires_in: 3600 });
    if (String(url).includes("graph.microsoft.com")) return new Response("panne", { status: 503 });
    return Response.json({
      items: [{ id: "e1", summary: "Point Google", start: { dateTime: start.toISOString() }, end: { dateTime: start.toISOString() } }],
    });
  }) as typeof fetch;
  const { events, errors } = await accountEvents(new Date(start.getTime() - 3600_000), new Date(start.getTime() + 3600_000));
  assert.deepEqual(
    events.map((e) => e.title),
    ["Point Google"],
  );
  assert.equal(errors.length, 1);
  assert.match(errors[0].message, /503/);
});
