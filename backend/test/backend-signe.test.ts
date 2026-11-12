import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { generateKeyPairSync, verify } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { after, describe, it } from "node:test";
import { promisify } from "node:util";

const repo = path.resolve(import.meta.dirname, "..", "..");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "companion-signe-"));
after(() => fs.rmSync(tmp, { recursive: true, force: true }));

const pem = (k: { export: (o: { type: "spki" | "pkcs8"; format: "pem" }) => string | Buffer }, type: "spki" | "pkcs8") =>
  k.export({ type, format: "pem" }).toString();
const ci = generateKeyPairSync("ed25519");
const publicFile = path.join(tmp, "backend-signature.pub");
fs.writeFileSync(publicFile, pem(ci.publicKey, "spki"));

function signe(args: string[], env: Record<string, string | undefined>) {
  return promisify(execFile)(process.execPath, [path.join(repo, "scripts", "backend-signe.mjs"), ...args], {
    env: { ...process.env, BACKEND_SIGNING_PUB: publicFile, ...env },
  });
}

function dist(platforms = ["mac", "windows"]) {
  const dir = fs.mkdtempSync(path.join(tmp, "dist-"));
  for (const p of platforms) fs.writeFileSync(path.join(dir, `backend-${p}.tar.gz`), `archive ${p}`);
  return dir;
}

describe("signature des archives du backend par la CI", () => {
  it("pose à côté de chaque archive une signature que la clé publique du compagnon accepte", async () => {
    const dir = dist();
    await signe([dir], { BACKEND_SIGNING_KEY: pem(ci.privateKey, "pkcs8") });
    for (const p of ["mac", "windows"]) {
      const sig = fs.readFileSync(path.join(dir, `backend-${p}.tar.gz.sig`), "utf8").trim();
      assert.match(sig, /^[A-Za-z0-9+/]{86}==$/);
      assert.ok(verify(null, Buffer.from(`archive ${p}`), pem(ci.publicKey, "spki"), Buffer.from(sig, "base64")));
    }
  });

  it("s'arrête sans clé, avec une clé qui ne correspond pas à la clé publique, ou avec une seule plateforme", async () => {
    await assert.rejects(signe([dist()], { BACKEND_SIGNING_KEY: "" }), /BACKEND_SIGNING_KEY/);
    const other = generateKeyPairSync("ed25519").privateKey;
    const dir = dist();
    await assert.rejects(signe([dir], { BACKEND_SIGNING_KEY: pem(other, "pkcs8") }), /ne correspond pas/);
    assert.equal(fs.existsSync(path.join(dir, "backend-mac.tar.gz.sig")), false);
    await assert.rejects(signe([dist(["mac"])], { BACKEND_SIGNING_KEY: pem(ci.privateKey, "pkcs8") }), /par plateforme/);
  });

  it("--cle crée une paire qui va ensemble, et refuse de poser la clé privée dans le dépôt", async () => {
    const publicOut = path.join(tmp, "nouvelle.pub");
    const privateOut = path.join(tmp, "nouvelle.pem");
    await signe(["--cle", privateOut], { BACKEND_SIGNING_PUB: publicOut });
    const dir = dist();
    await signe([dir], { BACKEND_SIGNING_PUB: publicOut, BACKEND_SIGNING_KEY: fs.readFileSync(privateOut, "utf8") });
    assert.ok(fs.existsSync(path.join(dir, "backend-windows.tar.gz.sig")));
    await assert.rejects(signe(["--cle", path.join(repo, "cle.pem")], { BACKEND_SIGNING_PUB: publicOut }), /jamais dans le dépôt/);
    assert.equal(fs.existsSync(path.join(repo, "cle.pem")), false);
  });

  it("sans archive (essai qui ne touche pas au backend) : rien à signer, la clé n'est pas demandée", async () => {
    await signe([dist([])], { BACKEND_SIGNING_KEY: "" });
  });
});
