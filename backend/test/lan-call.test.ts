import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import type { AddressInfo } from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";

// lan-call.ts lit config.ts, qui crée l'identité de l'ordinateur : dossier de données jetable.
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "mate-lan-call-"));
const lan = await import("../src/lan-call.ts");
after(() => fs.rmSync(process.env.DATA_DIR!, { recursive: true, force: true }));

describe("adresses permises", () => {
  const refused = [
    "169.254.169.254",
    "0.0.0.0",
    "100.64.0.1",
    "224.0.0.1",
    "255.255.255.255",
    "8.8.8.8",
    "172.32.0.1",
    "::",
    "ff02::1",
    "::ffff:a9fe:a9fe",
    "::1:2",
    "nom",
    "",
  ];
  const accepted = [
    "192.168.1.10",
    "10.0.0.2",
    "172.16.0.1",
    "172.31.255.254",
    "127.0.0.1",
    "127.9.9.9",
    "::1",
    "fe80::1",
    "fd12:3456::1",
    "::ffff:c0a8:010a",
    "::ffff:192.168.1.10",
  ];
  for (const a of refused) it(`refuse ${JSON.stringify(a)}`, () => assert.equal(lan.isLanAddress(a), false));
  for (const a of accepted) it(`accepte ${a}`, () => assert.equal(lan.isLanAddress(a), true));
});

describe("résolution gardée", () => {
  const resolverOf = (...addresses: string[]) =>
    ((_host: string, _opts: unknown, cb: (...a: unknown[]) => void) =>
      cb(
        null,
        addresses.map((address) => ({ address, family: address.includes(":") ? 6 : 4 })),
      )) as never;
  const run = (resolver: never, all = true) =>
    new Promise<{ err: any; res: any }>((resolve) =>
      lan.guardedLookup(resolver)("x.test", { all }, (err: any, res: any) => resolve({ err, res })),
    );

  it("refuse une adresse publique, même mêlée à une adresse locale", async () => {
    assert.equal((await run(resolverOf("8.8.8.8"))).err.code, "ELANBLOCKED");
    assert.equal((await run(resolverOf("192.168.1.2", "8.8.8.8"))).err.code, "ELANBLOCKED");
  });

  it("rend les adresses vérifiées", async () => {
    assert.deepEqual((await run(resolverOf("192.168.1.2"))).res, [{ address: "192.168.1.2", family: 4 }]);
  });
});

describe("connexion à l'adresse vérifiée", () => {
  let server: http.Server;
  let port: number;
  const hosts: (string | undefined)[] = [];
  before(async () => {
    server = http.createServer((req, res) => {
      hosts.push(req.headers.host);
      res.end("ok");
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    port = (server.address() as AddressInfo).port;
  });
  after(() => server.close());

  const resolverOf = (address: string) =>
    ((_h: string, _o: unknown, cb: (...a: unknown[]) => void) => cb(null, [{ address, family: 4 }])) as never;
  const target = () => ({ url: new URL(`http://rebind.test:${port}/x`), method: "GET" as const });

  it("une résolution publique est refusée avant tout envoi (403)", async () => {
    hosts.length = 0;
    await assert.rejects(lan.callTarget("t", target(), lan.guardedLookup(resolverOf("8.8.8.8"))), { statusCode: 403 });
    assert.equal(hosts.length, 0);
  });

  it("l'adresse vérifiée est celle qui reçoit la requête, le nom reste dans Host", async () => {
    hosts.length = 0;
    assert.deepEqual(await lan.callTarget("t", target(), lan.guardedLookup(resolverOf("127.0.0.1"))), { name: "t", status: 200 });
    assert.deepEqual(hosts, [`rebind.test:${port}`]);
  });

  it("une adresse publique écrite telle quelle est refusée (403)", async () => {
    await assert.rejects(lan.callTarget("t", { url: new URL("http://8.8.8.8/x"), method: "GET" }), { statusCode: 403 });
    await assert.rejects(lan.callTarget("t", { url: new URL("http://[::ffff:a9fe:a9fe]/x"), method: "GET" }), { statusCode: 403 });
  });
});

describe("cibles qui visent le compagnon", () => {
  const ports = [3000, 3001];
  const parse = (url: string) => [...lan.parseTargets(JSON.stringify({ c: { url } }), ports).keys()];
  it("refuse le port du compagnon sur une adresse de cet ordinateur", () => {
    for (const u of [
      "http://127.0.0.1:3000/x",
      "http://localhost:3000/",
      "http://127.4.5.6:3000",
      "http://[::1]:3000/",
      "https://127.0.0.1:3001/",
    ])
      assert.deepEqual(parse(u), [], u);
  });
  it("garde un autre port local, ou ce port sur une autre machine", () => {
    for (const u of ["http://127.0.0.1:8080/x", "http://192.168.1.5:3000/x", "http://127.0.0.1/x"]) assert.deepEqual(parse(u), ["c"], u);
  });
});
