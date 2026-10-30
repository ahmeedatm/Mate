import assert from "node:assert/strict";
import { createHash, randomBytes, X509Certificate } from "node:crypto";
import fs from "node:fs";
import https from "node:https";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { after, before, describe, it } from "node:test";
import { freePort, pairingCode, startServer, TOKEN, type TestServer } from "./server.ts";
import { createCertificate, fingerprintOf, loadTls } from "../src/tls.ts";

describe("certificat de l'ordinateur", () => {
  it("se crée une fois (droits 600), puis se relit à l'identique", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "companion-tls-"));
    const first = loadTls(dir);
    assert.match(first.fingerprint, /^[0-9a-f]{64}$/);
    if (process.platform !== "win32") assert.equal(fs.statSync(path.join(dir, "tls.json")).mode & 0o777, 0o600);
    assert.deepEqual(loadTls(dir), first);
  });

  it("est un X.509 auto-signé valide, jusqu'en 2049", () => {
    const { cert } = createCertificate();
    const x = new X509Certificate(cert);
    assert.ok(x.verify(x.publicKey));
    assert.equal(x.subject, "CN=UnoMate");
    assert.equal(new Date(x.validTo).getUTCFullYear(), 2049);
    assert.notEqual(fingerprintOf(cert), fingerprintOf(createCertificate().cert));
  });
});

/** Requête HTTPS sans vérification d'autorité (comme la tablette) ; rend l'empreinte du certificat vu. */
function tlsCall(host: string, port: number, method: string, route: string, body?: unknown, token?: string) {
  return new Promise<{ status: number; json: any; fingerprint: string }>((resolve, reject) => {
    const req = https.request(
      {
        host,
        port,
        method,
        path: route,
        rejectUnauthorized: false,
        agent: false,
        headers: { ...(body !== undefined && { "content-type": "application/json" }), ...(token && { authorization: `Bearer ${token}` }) },
      },
      (res) => {
        const raw = (res.socket as import("node:tls").TLSSocket).getPeerCertificate().raw;
        let text = "";
        res.on("data", (c) => (text += c));
        res.on("end", () =>
          resolve({
            status: res.statusCode!,
            json: text ? JSON.parse(text) : null,
            fingerprint: createHash("sha256").update(raw).digest("hex"),
          }),
        );
      },
    );
    req.on("error", reject);
    req.end(body === undefined ? undefined : JSON.stringify(body));
  });
}

const canConnect = (host: string, port: number) =>
  new Promise<boolean>((resolve) => {
    const s = net.connect({ host, port }, () => (s.destroy(), resolve(true)));
    s.on("error", () => resolve(false));
  });

const lanAddress = Object.values(os.networkInterfaces())
  .flat()
  .find((a) => a && a.family === "IPv4" && !a.internal)?.address;

describe("serveur en WiFi (HOST=0.0.0.0)", () => {
  let server: TestServer;
  let tlsPort: number;
  let port: number;
  before(async () => {
    tlsPort = await freePort();
    server = await startServer({ host: "0.0.0.0", env: { TLS_PORT: String(tlsPort) } });
    port = Number(new URL(server.url).port);
  });
  after(async () => {
    await server?.stop();
  });

  it("répond en HTTPS avec le certificat de .data/tls.json, et annonce son port HTTPS", async () => {
    const res = await tlsCall("127.0.0.1", tlsPort, "GET", "/health");
    assert.equal(res.status, 200);
    assert.equal(res.json.tlsPort, tlsPort);
    assert.equal(res.fingerprint, fingerprintOf(JSON.parse(fs.readFileSync(path.join(server.dataDir, "tls.json"), "utf8")).cert));
  });

  it("le HTTP en clair n'écoute plus que sur 127.0.0.1", { skip: !lanAddress && "pas d'adresse réseau" }, async () => {
    assert.equal(await canConnect("127.0.0.1", port), true);
    assert.equal(await canConnect(lanAddress!, port), false);
    assert.equal((await tlsCall(lanAddress!, tlsPort, "GET", "/health")).status, 200);
  });

  it(
    "depuis le réseau, la saisie du code et les routes protégées demandent le jeton",
    { skip: !lanAddress && "pas d'adresse réseau" },
    async () => {
      assert.equal((await tlsCall(lanAddress!, tlsPort, "POST", "/local/pair", { code: "123456" })).status, 401);
      assert.equal((await tlsCall(lanAddress!, tlsPort, "GET", "/tasks")).status, 401);
      assert.equal((await tlsCall(lanAddress!, tlsPort, "GET", "/tasks", undefined, TOKEN)).status, 200);
    },
  );

  it("appairage en HTTPS : le code inclut l'empreinte vue par la tablette, qui la retrouve dans la réponse", async () => {
    const tablet = randomBytes(32).toString("base64url");
    const commit = createHash("sha256").update(tablet).digest("base64url");
    const req = await tlsCall("127.0.0.1", tlsPort, "POST", "/pair/request", { tablet: "Tablette WiFi", commit });
    assert.equal(req.status, 200);
    assert.equal((await tlsCall("127.0.0.1", tlsPort, "POST", "/pair/reveal", { request: req.json.request, nonce: tablet })).status, 200);

    // Un code calculé sans l'empreinte (intercepteur qui relaie en clair) est refusé.
    assert.equal((await server.call("POST", "/local/pair", { code: pairingCode("", tablet, req.json.nonce) }, false)).status, 403);
    assert.equal(
      (await server.call("POST", "/local/pair", { code: pairingCode(req.fingerprint, tablet, req.json.nonce) }, false)).status,
      200,
    );

    const ok = await tlsCall("127.0.0.1", tlsPort, "POST", "/pair/confirm", { request: req.json.request });
    assert.equal(ok.json.token, TOKEN);
    assert.equal(ok.json.fingerprint, ok.fingerprint);
  });
});
