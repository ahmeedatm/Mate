import assert from "node:assert/strict";
import http from "node:http";
import { after, before, describe, it } from "node:test";
import type { AddressInfo } from "node:net";
import { startServer, type TestServer } from "./server.ts";

type Seen = { method?: string; url?: string; auth?: string };
let seen: Seen[] = [];
let lan: http.Server;
let server: TestServer;

before(async () => {
  lan = http.createServer((req, res) => {
    seen.push({ method: req.method, url: req.url, auth: req.headers.authorization });
    if (req.url === "/relay/0?turn=on") return void res.end("ok");
    if (req.url === "/panne") return void res.writeHead(500).end("secret-interne");
    if (req.url === "/ailleurs") return void res.writeHead(302, { location: "/relay/0?turn=on" }).end();
    res.writeHead(404).end();
  });
  await new Promise<void>((resolve) => lan.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${(lan.address() as AddressInfo).port}`;
  server = await startServer({
    env: {
      LAN_TARGETS: JSON.stringify({
        prise: { url: `${base}/relay/0?turn=on` },
        nas: { url: `${base}/relay/0?turn=on`, method: "POST", token: "jeton-nas" },
        panne: { url: `${base}/panne` },
        detour: { url: `${base}/ailleurs` },
        dehors: { url: "http://8.8.8.8/relay" },
        // Écartées au démarrage : un jeton en http clair hors de cet ordinateur, une méthode inconnue.
        piege: { url: "http://192.168.1.5/hook", token: "jeton" },
        effacer: { url: `${base}/relay/0?turn=on`, method: "DELETE" },
      }),
    },
  });
});
after(async () => {
  await server?.stop();
  lan?.close();
});

const SLOT = 5;
const press = async (name: string) => {
  const put = await server.call("PUT", `/deck/keys/${SLOT}`, { label: "Prise", steps: [{ type: "http", name }] });
  assert.equal(put.status, 200);
  return server.call("POST", "/deck/run", { id: `k-${SLOT}` });
};

describe("étape http d'une touche du Deck", () => {
  it("appelle la cible déclarée sur l'ordinateur et rend le code de réponse", async () => {
    seen = [];
    const res = await press("prise");
    assert.equal(res.status, 200);
    assert.deepEqual(res.json, { ok: true, http: { name: "prise", status: 200 } });
    assert.deepEqual(seen, [{ method: "GET", url: "/relay/0?turn=on", auth: undefined }]);
  });

  it("envoie la méthode et le jeton gardés sur l'ordinateur", async () => {
    seen = [];
    assert.equal((await press("nas")).status, 200);
    assert.deepEqual(seen, [{ method: "POST", url: "/relay/0?turn=on", auth: "Bearer jeton-nas" }]);
  });

  it("dit que l'appareil a refusé, sans recopier sa réponse", async () => {
    const res = await press("panne");
    assert.equal(res.status, 502);
    assert.match(res.json.message, /panne.*500/);
    assert.ok(!JSON.stringify(res.json).includes("secret-interne"));
  });

  it("ne suit pas une redirection", async () => {
    seen = [];
    const res = await press("detour");
    assert.equal(res.status, 502);
    assert.equal(seen.length, 1);
  });

  it("refuse une cible inconnue sans rien appeler", async () => {
    seen = [];
    assert.equal((await press("inconnue")).status, 404);
    assert.equal(seen.length, 0);
  });

  it("écarte au démarrage un jeton en http clair et une méthode inconnue", async () => {
    seen = [];
    assert.equal((await press("piege")).status, 404);
    assert.equal((await press("effacer")).status, 404);
    assert.equal(seen.length, 0);
  });

  it("refuse une cible qui sort du réseau local", async () => {
    assert.equal((await press("dehors")).status, 403);
  });

  it("ne garde que le nom de la cible : une adresse ajoutée à l'étape est retirée", async () => {
    const put = await server.call("PUT", `/deck/keys/${SLOT}`, {
      label: "X",
      steps: [{ type: "http", name: "prise", url: "http://10.0.0.1" }],
    });
    assert.equal(put.status, 200);
    assert.deepEqual((await server.call("GET", `/deck/keys/${SLOT}`)).json.key.steps, [{ type: "http", name: "prise" }]);
  });
});
