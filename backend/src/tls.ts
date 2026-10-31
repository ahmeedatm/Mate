import { createHash, generateKeyPairSync, randomBytes, sign, X509Certificate } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

/**
 * Certificat TLS de cet ordinateur, pour le WiFi (le HTTP en clair ne sert plus qu'en local : page, extension, USB).
 * Auto-signé, créé au premier lancement dans .data/tls.json : la tablette n'a pas d'autorité de certification à
 * consulter, elle retient son empreinte à l'appairage (épinglage) et refuse ensuite tout autre certificat.
 * Le garder : un nouveau certificat oblige à réappairer toutes les tablettes.
 *
 * Node ne sait pas fabriquer de certificat X.509 : encodage DER à la main, le strict nécessaire (version 1,
 * sans extension, ECDSA P-256), signé par `crypto.sign`.
 */

export type Tls = { key: string; cert: string; /** SHA-256 du certificat (DER), en hexadécimal minuscule. */ fingerprint: string };

const der = (tag: number, content: Buffer) => {
  const n = content.length;
  const len = n < 0x80 ? Buffer.from([n]) : n < 0x100 ? Buffer.from([0x81, n]) : Buffer.from([0x82, n >> 8, n & 0xff]);
  return Buffer.concat([Buffer.from([tag]), len, content]);
};
const seq = (...items: Buffer[]) => der(0x30, Buffer.concat(items));
const oid = (bytes: number[]) => der(0x06, Buffer.from(bytes));
const ECDSA_SHA256 = seq(oid([0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02])); // 1.2.840.10045.4.3.2
const COMMON_NAME = [0x55, 0x04, 0x03]; // 2.5.4.3
const utcTime = (d: Date) => der(0x17, Buffer.from(d.toISOString().replace(/[-:T]/g, "").slice(2, 14) + "Z"));

export function createCertificate(name = "Mate", now = new Date()): Omit<Tls, "fingerprint"> {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
  const subject = seq(der(0x31, seq(oid(COMMON_NAME), der(0x0c, Buffer.from(name)))));
  // Numéro de série positif (bit de poids fort à 0), jamais nul.
  const serial = randomBytes(16);
  serial[0] = (serial[0] & 0x7f) | 0x01;
  const tbs = seq(
    der(0x02, serial),
    ECDSA_SHA256,
    subject,
    // UTCTime jusqu'en 2049 (au-delà, GeneralizedTime) : la validité n'est pas vérifiée par la tablette, seule l'empreinte compte.
    seq(utcTime(new Date(now.getTime() - 24 * 3600_000)), utcTime(new Date("2049-12-31T23:59:59Z"))),
    subject,
    publicKey.export({ type: "spki", format: "der" }),
  );
  const signature = sign("sha256", tbs, privateKey);
  const cert = seq(tbs, ECDSA_SHA256, der(0x03, Buffer.concat([Buffer.from([0]), signature])));
  const pem = `-----BEGIN CERTIFICATE-----\n${cert
    .toString("base64")
    .match(/.{1,64}/g)!
    .join("\n")}\n-----END CERTIFICATE-----\n`;
  return { key: privateKey.export({ type: "pkcs8", format: "pem" }) as string, cert: pem };
}

export const fingerprintOf = (certPem: string) => createHash("sha256").update(new X509Certificate(certPem).raw).digest("hex");

/** Certificat de .data/tls.json, créé s'il manque (droits 600 : la clé ne doit pas être lisible par les autres comptes). */
export function loadTls(dataDir: string): Tls {
  const file = path.join(dataDir, "tls.json");
  let stored: Omit<Tls, "fingerprint">;
  try {
    stored = JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    stored = createCertificate();
    fs.mkdirSync(dataDir, { recursive: true });
    try {
      fs.writeFileSync(file, JSON.stringify(stored, null, 2) + "\n", { flag: "wx", mode: 0o600 });
    } catch {
      stored = JSON.parse(fs.readFileSync(file, "utf8"));
    }
  }
  return { ...stored, fingerprint: fingerprintOf(stored.cert) };
}
