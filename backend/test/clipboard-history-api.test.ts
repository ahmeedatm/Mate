import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { startServer, type TestServer } from "./server.ts";

// Historique du presse-papiers en HTTP, sur le faux pilotage (test/fake-os.ts) relu toutes les 30 ms.

let server: TestServer;
before(async () => {
  server = await startServer({ env: { CLIPBOARD_POLL_MS: "30" } });
});
after(async () => {
  await server?.stop();
});

/** Copie `text` sur le faux ordinateur et attend que la surveillance l'ait noté. */
async function copy(text: string) {
  server.setOs({ clipboard: { kind: "text", text } });
  for (let i = 0; i < 100; i++) {
    const { json } = await server.call("GET", "/system/clipboard/history");
    if (json.items[0]?.text === text) return json.items;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  assert.fail(`« ${text} » jamais noté`);
}

describe("historique du presse-papiers en HTTP", () => {
  it("liste les copies, la plus récente en haut, avec l'heure et le masquage", async () => {
    await server.call("DELETE", "/system/clipboard/history");
    await copy("bonjour le monde");
    const items = await copy("482913");
    assert.deepEqual(
      items.map((item: { text: string; secret: boolean }) => [item.text, item.secret]),
      [
        ["482913", true],
        ["bonjour le monde", false],
      ],
    );
    assert.ok(Math.abs(items[0].at - Date.now()) < 60_000);
  });

  it("ne note pas un contenu protégé (gestionnaire de mots de passe)", async () => {
    await server.call("DELETE", "/system/clipboard/history");
    await copy("visible");
    server.setOs({ clipboard: { kind: "protected" } });
    await new Promise((resolve) => setTimeout(resolve, 200));
    const { json } = await server.call("GET", "/system/clipboard/history");
    assert.deepEqual(
      json.items.map((item: { text: string }) => item.text),
      ["visible"],
    );
  });

  it("recopie une entrée sur l'ordinateur, la retire, vide tout ; 404 pour une entrée disparue", async () => {
    await server.call("DELETE", "/system/clipboard/history");
    const [code] = await copy("code-1234");
    await copy("autre chose");
    server.clearOsCalls();
    assert.deepEqual((await server.call("POST", `/system/clipboard/history/${code.id}/copy`)).json, { ok: true });
    assert.deepEqual(server.osCalls(), [{ fn: "setClipboardText", args: ["code-1234"] }]);
    // La recopie change le presse-papiers : la surveillance le note à nouveau. On l'attend, sinon elle peut tomber après « vider tout ».
    for (let i = 0; ; i++) {
      const { json } = await server.call("GET", "/system/clipboard/history");
      if (json.items[0]?.text === "code-1234") break;
      if (i === 100) assert.fail("la recopie n'a jamais été notée");
      await new Promise((resolve) => setTimeout(resolve, 20));
    }

    assert.equal((await server.call("DELETE", `/system/clipboard/history/${code.id}`)).status, 200);
    assert.equal((await server.call("DELETE", `/system/clipboard/history/${code.id}`)).status, 404);
    assert.equal((await server.call("POST", `/system/clipboard/history/${code.id}/copy`)).status, 404);

    assert.deepEqual((await server.call("DELETE", "/system/clipboard/history")).json, { ok: true });
    assert.deepEqual((await server.call("GET", "/system/clipboard/history")).json, { items: [] });
  });

  it("refuse sans jeton et n'écrit rien du contenu dans le journal", async () => {
    await copy("secret-du-journal-42");
    assert.equal((await server.call("GET", "/system/clipboard/history", undefined, false)).status, 401);
    assert.equal((await server.call("DELETE", "/system/clipboard/history", undefined, false)).status, 401);
    assert.ok(!server.output().includes("secret-du-journal-42"));
  });
});
