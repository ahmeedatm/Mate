import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { it } from "node:test";

// Sans accès complet au disque (ici : base absente), la lecture échoue toutes les 2 s : aucun dossier temporaire ne reste.
process.env.HOME = fs.mkdtempSync(path.join(os.tmpdir(), "mate-home-"));
const { readMacNotifications } = await import("../src/mac/notifications.ts");

const snapshots = () => fs.readdirSync(os.tmpdir()).filter((f) => f.startsWith("mate-notif-")).length;

it("base illisible : 503, et le dossier de l'instantané est supprimé", async () => {
  const before = snapshots();
  for (let i = 0; i < 3; i++) await assert.rejects(readMacNotifications(), (e: { statusCode?: number }) => e.statusCode === 503);
  assert.equal(snapshots(), before);
});
