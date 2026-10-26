import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-reminders-"));
process.env.DATA_DIR = dir;
const { addTask, listTasks, reminderTick } = await import("../src/tasks.ts");
const { addEvent, eventReminderTick, localEvents } = await import("../src/events.ts");

test("rappel de tâche : une tâche ajoutée pendant l'envoi n'est pas écrasée", async () => {
  await fs.writeFile(
    path.join(dir, "tasks.json"),
    JSON.stringify({ done: {}, items: [{ id: "vieux", label: "Vieux", createdMs: 1, remindAtMs: Date.now() - 1000 }] }),
  );
  const notify = async () => {
    await addTask("Ajoutée pendant l'envoi"); // la tablette crée une tâche pendant que le rappel part
    return true;
  };
  await reminderTick(notify, new Map());
  const labels = (await listTasks()).map((t) => t.label).sort();
  assert.deepEqual(labels, ["Ajoutée pendant l'envoi", "Vieux"]);
  const stored = JSON.parse(await fs.readFile(path.join(dir, "tasks.json"), "utf8"));
  assert.ok(stored.items.find((i: { id: string }) => i.id === "vieux").remindedMs, "le rappel est noté comme fait");
});

test("rappel de tâche : relancer le tick ne rappelle pas deux fois", async () => {
  let sent = 0;
  await reminderTick(async () => (sent++, true), new Map());
  assert.equal(sent, 0);
});

test("rappel d'événement : un événement ajouté pendant l'envoi n'est pas écrasé", async () => {
  const now = Date.now();
  await addEvent({ title: "Réunion", startMs: now + 60_000, allDay: false, remindBefore: 5 });
  const notify = async () => {
    await addEvent({ title: "Ajouté pendant l'envoi", startMs: now + 3_600_000, allDay: false });
    return true;
  };
  await eventReminderTick(notify);
  const events = await localEvents(new Date(now - 1000), new Date(now + 86_400_000));
  assert.deepEqual(events.map((e) => e.title).sort(), ["Ajouté pendant l'envoi", "Réunion"]);
  const stored = JSON.parse(await fs.readFile(path.join(dir, "events.json"), "utf8"));
  assert.ok(stored.items.find((e: { title: string }) => e.title === "Réunion").remindedMs);
});
