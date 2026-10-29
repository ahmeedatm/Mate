import { execFile } from "node:child_process";
import os from "node:os";
import path from "node:path";
import type { DatabaseSync } from "node:sqlite";
import { appNameFromId, type NotificationBatch, type RawNotification } from "../notifications.ts";

/**
 * Notifications du Mac : base de `usernoted`, lue en lecture seule. Table record (rec_id croissant, app_id,
 * delivered_date en secondes depuis 2001, data = plist binaire { req: { titl, subt, body } }) jointe à app (identifier =
 * bundle id). Demande l'accès complet au disque pour node (Réglages Système > Confidentialité) : sans lui, l'ouverture
 * échoue et la route répond 503. Pas de Mac ici : écrit, non vérifié.
 */

const DB_FILE = path.join(os.homedir(), "Library", "Group Containers", "group.com.apple.usernoted", "db2", "db");
const COCOA_EPOCH_MS = 978_307_200_000;
const SINCE_FIRST_MS = 24 * 60 * 60 * 1000;

let Database: typeof DatabaseSync | undefined;

/** Plist binaire → objet, par plutil (fourni avec macOS). */
function plist(data: Uint8Array): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const child = execFile(
      "plutil",
      ["-convert", "json", "-o", "-", "--", "-"],
      { timeout: 5_000, maxBuffer: 1024 * 1024 },
      (error, stdout) => (error ? reject(new Error("Notification illisible")) : resolve(JSON.parse(stdout) as Record<string, unknown>)),
    );
    child.stdin?.end(Buffer.from(data));
  });
}

/** Les noms d'applis du Mac (« Microsoft Outlook ») viennent de leur bundle id faute de mieux : com.microsoft.Outlook → Outlook. */
export async function readMacNotifications(cursor?: string): Promise<NotificationBatch> {
  let db: DatabaseSync;
  try {
    Database ??= (await import("node:sqlite")).DatabaseSync;
    db = new Database(DB_FILE, { readOnly: true });
  } catch {
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
  }
  const items: RawNotification[] = [];
  for (const row of rows) {
    const req = ((await plist(row.data).catch(() => ({}))) as { req?: Record<string, unknown> }).req ?? {};
    const text = (key: string) => (typeof req[key] === "string" ? (req[key] as string) : "");
    const body = [text("subt"), text("body")].filter(Boolean).join("\n");
    items.push({ app: appNameFromId(row.app), title: text("titl"), body, at: Math.round(row.at * 1000 + COCOA_EPOCH_MS) });
  }
  return { cursor: String(Math.max(top, rows.at(-1)?.id ?? 0, Number(cursor ?? 0))), items };
}
