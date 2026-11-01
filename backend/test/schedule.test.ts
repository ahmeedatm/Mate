import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-schedule-"));
process.env.DATA_DIR = dir;
const { addSchedule, deleteSchedule, listSchedule, scheduleTick, setScheduleEnabled } = await import("../src/schedule.ts");

const at = (hhmm: string, day = "2026-11-02") => new Date(`${day}T${hhmm}:00`); // 2 novembre 2026 = lundi

test("heure fixe : la touche part à l'heure, une seule fois par jour", async () => {
  const { id } = await addSchedule({ tileId: "t-mute", at: "18:00" }, at("09:00"));
  const ran: string[] = [];
  const run = async (tileId: string) => void ran.push(tileId);
  await scheduleTick(run, at("17:59"));
  assert.deepEqual(ran, []);
  await scheduleTick(run, at("18:00"));
  await scheduleTick(run, at("18:01"));
  assert.deepEqual(ran, ["t-mute"]);
  await scheduleTick(run, at("18:00", "2026-11-03"));
  assert.deepEqual(ran, ["t-mute", "t-mute"], "le lendemain, elle repart");
  await deleteSchedule(id);
});

test("heure fixe : seulement les jours choisis, et pas de rattrapage tardif", async () => {
  const { id } = await addSchedule({ tileId: "t-dnd", at: "18:00", days: [1] }, at("09:00"));
  const ran: string[] = [];
  const run = async (tileId: string) => void ran.push(tileId);
  await scheduleTick(run, at("18:00", "2026-11-03")); // mardi
  await scheduleTick(run, at("19:30")); // lundi, mais l'ordinateur se réveille 90 min trop tard
  assert.deepEqual(ran, []);
  await scheduleTick(run, at("18:04"));
  assert.deepEqual(ran, ["t-dnd"], "réveil dans les 5 minutes : elle part");
  await deleteSchedule(id);
});

test("intervalle : toutes les N minutes depuis la création, sans rafale après une longue veille", async () => {
  const { id } = await addSchedule({ tileId: "t-pause", everyMinutes: 45 }, at("09:00"));
  const ran: string[] = [];
  const run = async (tileId: string) => void ran.push(tileId);
  await scheduleTick(run, at("09:44"));
  assert.deepEqual(ran, []);
  await scheduleTick(run, at("09:45"));
  await scheduleTick(run, at("10:00"));
  assert.equal(ran.length, 1);
  await scheduleTick(run, at("13:00")); // ordinateur endormi 3 h : un seul lancement
  assert.equal(ran.length, 2);
  await deleteSchedule(id);
});

test("désactivée : ne part pas ; supprimée : disparaît ; échec : noté, pas rejoué dans la minute", async () => {
  const { id } = await addSchedule({ tileId: "t-x", everyMinutes: 5 }, at("09:00"));
  assert.equal(await setScheduleEnabled(id, false), true);
  const ran: string[] = [];
  const run = async (t: string) => void ran.push(t);
  await scheduleTick(run, at("10:00"));
  assert.deepEqual(ran, []);
  await setScheduleEnabled(id, true);
  const broken = async () => {
    throw new Error("touche cassée");
  };
  await scheduleTick(broken, at("10:00")); // ne doit pas lever
  await scheduleTick(run, at("10:01"));
  assert.deepEqual(ran, [], "un échec compte comme un lancement : prochaine tentative dans 5 minutes");
  assert.equal((await listSchedule()).length, 1);
  assert.equal(await deleteSchedule(id), true);
  assert.equal(await deleteSchedule(id), false);
});
