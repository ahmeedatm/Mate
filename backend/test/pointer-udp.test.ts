import assert from "node:assert/strict";
import dgram from "node:dgram";
import { after, before, describe, it } from "node:test";
import { startServer, TOKEN, type TestServer } from "./server.ts";

// Le socket UDP n'existe qu'hors loopback (src/pairing.ts) : le serveur écoute sur toutes les interfaces.
let server: TestServer;
let port: number;
let socket: dgram.Socket;
before(async () => {
  server = await startServer({ host: "0.0.0.0" });
  port = Number(new URL(server.url).port);
  socket = dgram.createSocket("udp4");
});
after(async () => {
  socket?.close();
  await server?.stop();
});

const send = (text: string) =>
  new Promise<void>((resolve, reject) => socket.send(text, port, "127.0.0.1", (e) => (e ? reject(e) : resolve())));
const pointer = (token: string, seq: number, body: unknown) => send(`UNOWHY-POINTER\n${token}\n${seq}\n${JSON.stringify(body)}`);
/** UDP sans accusé : attendre que le serveur ait traité (ou ignoré) ce qui précède. */
const settle = () => new Promise((r) => setTimeout(r, 400));

describe("pavé tactile en UDP", () => {
  it("applique un paquet valide ; jeton faux, seq ancien et valeur hors bornes ne font rien", async () => {
    server.clearOsCalls();
    await pointer("mauvais-jeton", 1, { dx: 9, dy: 9 });
    await settle();
    assert.deepEqual(server.osCalls(), [], "mauvais jeton");

    await pointer(TOKEN, 10, { dx: 3, dy: -4 });
    await pointer(TOKEN, 11, { sx: 0, sy: 2 });
    await settle();
    assert.deepEqual(server.osCalls(), [
      { fn: "pointerMove", args: [3, -4] },
      { fn: "pointerScroll", args: [0, 2] },
    ]);

    server.clearOsCalls();
    await pointer(TOKEN, 10, { dx: 1, dy: 1 });
    await pointer(TOKEN, 11, { dx: 1, dy: 1 });
    await pointer(TOKEN, 12, { dx: 99999, dy: 0 });
    await settle();
    assert.deepEqual(server.osCalls(), [], "seq ancien ou valeur hors bornes");
  });

  it("ne journalise rien du paquet, et la découverte marche toujours", async () => {
    await pointer(TOKEN, 100, { dx: 31337, dy: 0 });
    await settle();
    assert.ok(!server.output().includes("31337"));
    assert.ok(!server.output().includes(TOKEN));

    const reply = await new Promise<string>((resolve, reject) => {
      const probe = dgram.createSocket("udp4");
      const timer = setTimeout(() => (probe.close(), reject(new Error("pas de réponse de découverte"))), 3000);
      probe.once("message", (m) => (clearTimeout(timer), probe.close(), resolve(m.toString())));
      probe.send("UNOWHY-COMPANION?", port, "127.0.0.1");
    });
    assert.equal(JSON.parse(reply).service, "unowhy-companion");
  });
});
