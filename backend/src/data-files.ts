import { randomBytes, randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

/**
 * Fichier JSON de .data créé au premier lancement (droits 600). Abîmé (coupure pendant l'écriture, édition à la main) :
 * mis de côté en `<nom>.abime-<date>` puis recréé, au lieu de bloquer le démarrage pour toujours. Le serveur et le
 * serveur MCP peuvent le créer en même temps : « wx », et celui qui arrive second relit celui du premier.
 */
export function loadOrCreate<T>(file: string, create: () => T, valid: (value: unknown) => value is T): T {
  const read = (): T | undefined => {
    try {
      const value: unknown = JSON.parse(fs.readFileSync(file, "utf8"));
      return valid(value) ? value : undefined;
    } catch {
      return undefined;
    }
  };
  const existing = read();
  if (existing) return existing;
  if (fs.existsSync(file)) {
    try {
      fs.renameSync(file, `${file}.abime-${new Date().toISOString().replace(/[:.]/g, "-")}`);
      console.warn(`${path.basename(file)} abîmé : mis de côté et recréé (la tablette devra être appairée à nouveau)`);
    } catch {
      // Déjà mis de côté par l'autre processus.
    }
  }
  const fresh = create();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  try {
    fs.writeFileSync(file, JSON.stringify(fresh, null, 2) + "\n", { flag: "wx", mode: 0o600 });
    return fresh;
  } catch {
    return read() ?? fresh;
  }
}

export type Identity = { id: string; token: string };

const isIdentity = (v: unknown): v is Identity =>
  typeof (v as Identity)?.id === "string" &&
  (v as Identity).id.length > 0 &&
  typeof (v as Identity).token === "string" &&
  (v as Identity).token.trim().length >= 16;

/**
 * Identité de cet ordinateur, créée au premier lancement : `id` permet à la tablette de le reconnaître
 * même si son adresse IP change, `token` est le jeton donné à la tablette à l'appairage. Jamais sans jeton.
 */
export const loadIdentity = (dataDir: string): Identity =>
  loadOrCreate(path.join(dataDir, "identity.json"), () => ({ id: randomUUID(), token: randomBytes(24).toString("hex") }), isIdentity);
