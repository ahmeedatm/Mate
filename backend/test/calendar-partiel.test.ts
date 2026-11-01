import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

// Deux agendas, dont un illisible : le résultat partiel n'est pas gardé en cache, l'agenda revenu se relit tout de suite.
const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-calendar-partiel-"));
const first = path.join(dir, "a.ics");
const second = path.join(dir, "b.ics");
process.env.DATA_DIR = dir;
process.env.CALENDAR_ICS_URLS = `${first},${second}`;
const { dayRange, getEvents } = await import("../src/calendar.ts");

const pad = (n: number) => String(n).padStart(2, "0");
const calendar = (uid: string, title: string) => {
  const d = new Date();
  const day = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
  return `BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//FR\r\nBEGIN:VEVENT\r\nUID:${uid}\r\nDTSTAMP:${day}T080000Z\r\nDTSTART:${day}T100000\r\nDTEND:${day}T110000\r\nSUMMARY:${title}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n`;
};
const titles = async () => (await getEvents(...dayRange(1))).events.map((e) => e.title).sort();

test("un agenda en échec : le résultat partiel n'est pas mis en cache", async () => {
  await fs.writeFile(first, calendar("a", "Agenda A"));
  assert.deepEqual(await titles(), ["Agenda A"]);
  await fs.writeFile(second, calendar("b", "Agenda B"));
  assert.deepEqual(await titles(), ["Agenda A", "Agenda B"]);
});
