import fs from "node:fs";
import path from "node:path";
import { randomBytes, timingSafeEqual } from "node:crypto";
import { config } from "./config.ts";

/** Secret des hooks des agents : propre à eux, distinct du jeton d'appairage (qui ouvre tout le reste de l'API). */

const secretFile = path.join(config.dataDir, "agents-hook.json");
let secret: string | undefined;

export function hookSecret(): string {
  if (secret) return secret;
  try {
    secret = JSON.parse(fs.readFileSync(secretFile, "utf8")).secret as string;
  } catch {
    secret = randomBytes(24).toString("hex");
    fs.mkdirSync(config.dataDir, { recursive: true });
    try {
      fs.writeFileSync(secretFile, JSON.stringify({ secret }) + "\n", { flag: "wx", mode: 0o600 });
    } catch {
      secret = JSON.parse(fs.readFileSync(secretFile, "utf8")).secret as string;
    }
  }
  return secret!;
}

export function hookSecretOk(given: string | undefined): boolean {
  const a = Buffer.from(given ?? "");
  const b = Buffer.from(hookSecret());
  return a.length === b.length && timingSafeEqual(a, b);
}
