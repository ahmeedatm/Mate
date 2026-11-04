import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { it } from "node:test";

// Sans accès complet au disque (ici : base absente), la lecture échoue toutes les 2 s : aucun dossier temporaire ne reste.
process.env.HOME = fs.mkdtempSync(path.join(os.tmpdir(), "mate-home-"));
const { DB_FILE, notificationsDiskAccess, readMacNotifications } = await import("../src/mac/notifications.ts");

const snapshots = () => fs.readdirSync(os.tmpdir()).filter((f) => f.startsWith("mate-notif-")).length;

it("base illisible : 503, et le dossier de l'instantané est supprimé", async () => {
  const before = snapshots();
  for (let i = 0; i < 3; i++) await assert.rejects(readMacNotifications(), (e: { statusCode?: number }) => e.statusCode === 503);
  assert.equal(snapshots(), before);
});

it("accès au disque : inconnu avant toute lecture ou si la base est absente, refusé sur EACCES, accordé après une copie réussie", async (t) => {
  assert.equal(notificationsDiskAccess(), "unknown");
  await assert.rejects(readMacNotifications());
  assert.equal(notificationsDiskAccess(), "unknown");
  fs.mkdirSync(path.dirname(DB_FILE), { recursive: true });
  fs.writeFileSync(DB_FILE, "pas une base");
  if (process.getuid?.() !== 0) {
    fs.chmodSync(DB_FILE, 0o000);
    await assert.rejects(readMacNotifications());
    assert.equal(notificationsDiskAccess(), "denied");
  }
  fs.chmodSync(DB_FILE, 0o644);
  await readMacNotifications().catch(() => undefined);
  assert.equal(notificationsDiskAccess(), "granted");
  t.after(() => fs.rmSync(process.env.HOME!, { recursive: true, force: true }));
});
