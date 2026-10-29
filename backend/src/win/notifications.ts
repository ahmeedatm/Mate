import os from "node:os";
import path from "node:path";
import type { DatabaseSync } from "node:sqlite";
import { parseToast, type NotificationBatch, type RawNotification } from "../notifications.ts";

/**
 * Notifications de Windows : base du centre de notifications de l'utilisateur, lue en lecture seule (SQLite, WAL : les
 * lectures ne gênent pas Windows). `UserNotificationListener` (WinRT) demanderait une appli empaquetée.
 * Table Notification (Id croissant, Type « toast », Payload = XML du toast, ArrivalTime = FILETIME) jointe à
 * NotificationHandler (PrimaryId = AppUserModelID de l'appli).
 */

const DB_FILE = path.join(
  process.env.LOCALAPPDATA ?? path.join(os.homedir(), "AppData", "Local"),
  "Microsoft",
  "Windows",
  "Notifications",
  "wpndatabase.db",
);

/** Premier passage : notifications des dernières 24 h. FILETIME = centaines de ns depuis 1601 ; 11 644 473 600 000 ms d'écart avec 1970. */
const SINCE_FIRST_MS = 24 * 60 * 60 * 1000;
const QUERY = `SELECT n.Id AS id, CAST(n.ArrivalTime / 10000 - 11644473600000 AS INTEGER) AS at, n.Payload AS payload, h.PrimaryId AS app
  FROM Notification n JOIN NotificationHandler h ON h.RecordId = n.HandlerId
  WHERE n.Type = 'toast' AND n.Id > ? AND n.ArrivalTime / 10000 - 11644473600000 > ?
  ORDER BY n.Id LIMIT 200`;

let Database: typeof DatabaseSync | undefined;

async function open(): Promise<DatabaseSync> {
  // Chargé au premier usage : node:sqlite n'existe sans option qu'à partir de node 22.13 (le paquet embarque 22.23).
  Database ??= (await import("node:sqlite")).DatabaseSync;
  return new Database(DB_FILE, { readOnly: true });
}

/** `appName` : nom du menu Démarrer pour un AppUserModelID (undefined : nom tiré de l'identifiant par l'appelant). */
export async function readWindowsNotifications(
  cursor: string | undefined,
  appName: (id: string) => Promise<string>,
): Promise<NotificationBatch> {
  const db = await open();
  let rows: { id: number; at: number; payload: Uint8Array; app: string }[];
  let top = 0;
  try {
    const after = cursor === undefined ? 0 : Number(cursor);
    const since = cursor === undefined ? Date.now() - SINCE_FIRST_MS : 0;
    rows = db.prepare(QUERY).all(after, since) as typeof rows;
    // Premier passage : le curseur part de la dernière notification, même plus vieille que 24 h (jamais renvoyée ensuite).
    if (cursor === undefined)
      top = Number((db.prepare("SELECT MAX(Id) AS top FROM Notification").get() as { top: number | null }).top ?? 0);
  } finally {
    db.close();
  }
  const items: RawNotification[] = [];
  for (const row of rows) {
    const { title, body } = parseToast(Buffer.from(row.payload).toString("utf8"));
    items.push({ app: await appName(row.app), title, body, at: row.at });
  }
  return { cursor: String(Math.max(top, rows.at(-1)?.id ?? 0, Number(cursor ?? 0))), items };
}
