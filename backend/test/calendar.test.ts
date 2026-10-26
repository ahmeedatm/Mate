import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-calendar-"));
const ics = path.join(dir, "agenda.ics");
process.env.DATA_DIR = dir;
process.env.CALENDAR_ICS_URLS = ics;
const { dayRange, getEvents } = await import("../src/calendar.ts");

const pad = (n: number) => String(n).padStart(2, "0");
const calendar = (title: string) => {
  const d = new Date();
  const day = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
  return `BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//FR\r\nBEGIN:VEVENT\r\nUID:e1\r\nDTSTAMP:${day}T080000Z\r\nDTSTART:${day}T100000\r\nDTEND:${day}T110000\r\nSUMMARY:${title}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n`;
};
const titles = async (days: number) => (await getEvents(...dayRange(days))).events.map((e) => e.title);

test("changer de plage ne vide pas le cache des autres plages", async () => {
  await fs.writeFile(ics, calendar("Version 1"));
  assert.deepEqual(await titles(1), ["Version 1"]);
  assert.deepEqual(await titles(7), ["Version 1"]);
  await fs.writeFile(ics, calendar("Version 2"));
  // l'agenda a changé, mais la plage d'un jour est encore en cache : pas de nouvelle lecture
  assert.deepEqual(await titles(1), ["Version 1"]);
});
