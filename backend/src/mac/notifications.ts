import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import type { DatabaseSync } from "node:sqlite";
import { appNameFromId, type NotificationBatch, type RawNotification } from "../notifications.ts";

/**
 * Notifications du Mac : base de `usernoted`. Table record (rec_id croissant, app_id, delivered_date en secondes depuis
 * 2001, data = plist binaire { req: { titl, subt, body } }) jointe à app (identifier = bundle id). Demande l'accès
 * complet au disque pour node (Réglages Système > Confidentialité) : sans lui, l'ouverture échoue et la route répond 503.
 *
 * La base est en mode WAL : les notifications récentes sont dans le fichier `-wal` tant qu'il n'est pas intégré à `db`
 * (parfois des jours). Une connexion en lecture seule ne lit que `db` et les manquerait toutes. On lit donc un instantané
 * (copie de db + -wal + -shm dans un dossier temporaire, ouverte à part) : le WAL y est pris en compte, sans jamais
 * ouvrir ni modifier la base du système. Pas de Mac ici : écrit, non vérifié.
 */

const DB_FILE = path.join(os.homedir(), "Library", "Group Containers", "group.com.apple.usernoted", "db2", "db");
const COCOA_EPOCH_MS = 978_307_200_000;
const SINCE_FIRST_MS = 24 * 60 * 60 * 1000;

let Database: typeof DatabaseSync | undefined;

/**
 * Plist binaire → XML, par plutil (fourni avec macOS). Pas en JSON : une notification contient une date (et parfois des
 * données), que « plutil -convert json » refuse (« Invalid object in plist for JSON format ») ; en XML tout passe.
 */
function plistXml(data: Uint8Array): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = execFile(
      "plutil",
      ["-convert", "xml1", "-o", "-", "--", "-"],
      { timeout: 5_000, maxBuffer: 2 * 1024 * 1024 },
      (error, stdout) => (error ? reject(new Error("Notification illisible")) : resolve(stdout)),
    );
    child.stdin?.end(Buffer.from(data));
  });
}

const XML_ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };

/** Entités d'un texte de plist XML décodées (le reste est déjà en UTF-8). */
export function unentity(text: string): string {
  return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (whole, code: string) => {
    if (code[0] !== "#") return XML_ENTITIES[code.toLowerCase()] ?? whole;
    const n = code[1] === "x" || code[1] === "X" ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10);
    return Number.isFinite(n) && n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : whole;
  });
}

/** Valeur texte d'une clé d'un plist XML (`<key>titl</key><string>…</string>`) ; absente ou non-texte : "". */
export function plistString(xml: string, key: string): string {
  const match = xml.match(new RegExp(`<key>${key}</key>\\s*<string>([\\s\\S]*?)</string>`));
  return match ? unentity(match[1]) : "";
}

/** Les noms d'applis du Mac (« Microsoft Outlook ») viennent de leur bundle id faute de mieux : com.microsoft.Outlook → Outlook. */
export async function readMacNotifications(cursor?: string): Promise<NotificationBatch> {
  let db: DatabaseSync;
  // Instantané : la vraie base n'est jamais ouverte en écriture. La copie est ouverte sans `readOnly` pour que SQLite
  // y rejoue le -wal (les notifications récentes), ce qu'une connexion en lecture seule ne ferait pas.
  const snapshot = fs.mkdtempSync(path.join(os.tmpdir(), "mate-notif-"));
  try {
    Database ??= (await import("node:sqlite")).DatabaseSync;
    fs.copyFileSync(DB_FILE, path.join(snapshot, "db"));
    for (const suffix of ["-wal", "-shm"]) {
      try {
        fs.copyFileSync(DB_FILE + suffix, path.join(snapshot, "db" + suffix));
      } catch {
        // -wal/-shm absents (base déjà intégrée) : la copie de db suffit.
      }
    }
    db = new Database(path.join(snapshot, "db"));
  } catch {
    // Relu toutes les 2 s : sans accès au disque, un dossier vide resterait à chaque fois.
    fs.rmSync(snapshot, { recursive: true, force: true });
    throw Object.assign(new Error("Notifications du Mac illisibles : donner l'accès complet au disque au compagnon (node)"), {
      statusCode: 503,
    });
  }
  let rows: { id: number; at: number; data: Uint8Array; app: string }[];
  let top = 0;
  try {
    const after = cursor === undefined ? 0 : Number(cursor);
    const since = cursor === undefined ? (Date.now() - SINCE_FIRST_MS - COCOA_EPOCH_MS) / 1000 : 0;
    rows = db
      .prepare(
        `SELECT r.rec_id AS id, r.delivered_date AS at, r.data AS data, a.identifier AS app
         FROM record r JOIN app a ON a.app_id = r.app_id
         WHERE r.rec_id > ? AND r.delivered_date > ? ORDER BY r.rec_id LIMIT 200`,
      )
      .all(after, since) as typeof rows;
    if (cursor === undefined) top = Number((db.prepare("SELECT MAX(rec_id) AS top FROM record").get() as { top: number | null }).top ?? 0);
  } finally {
    db.close();
    fs.rmSync(snapshot, { recursive: true, force: true });
  }
  const items: RawNotification[] = [];
  for (const row of rows) {
    // titl/subt/body sont des textes dans le sous-dictionnaire `req` ; l'extraction cible ces clés (une seule par
    // notification). Notification illisible : titre et texte vides, elle sera ignorée par l'historique.
    const xml = await plistXml(row.data).catch(() => "");
    const body = [plistString(xml, "subt"), plistString(xml, "body")].filter(Boolean).join("\n");
    items.push({ app: appNameFromId(row.app), title: plistString(xml, "titl"), body, at: Math.round(row.at * 1000 + COCOA_EPOCH_MS) });
  }
  return { cursor: String(Math.max(top, rows.at(-1)?.id ?? 0, Number(cursor ?? 0))), items };
}
