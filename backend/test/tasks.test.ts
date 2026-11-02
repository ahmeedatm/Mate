import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { beforeEach, describe, it } from "node:test";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-tasks-"));
process.env.DATA_DIR = dir;
const { addTask, deleteTask, listRecurring, listTasks, reminderTick, setTaskDone } = await import("../src/tasks.ts");

const file = path.join(dir, "tasks.json");
const today = new Date().getDay() || 7;
const otherDay = (today % 7) + 1;
const hhmm = (d: Date) => `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
// Tâches de config/tasks.json (s'il existe sur ce poste) mises à part.
const mine = async () => (await listRecurring()).filter((t) => t.deletable);

beforeEach(() => fs.rm(file, { force: true }));

describe("tâches récurrentes", () => {
  it("décrit les jours et l'heure du rappel", async () => {
    await addTask("Tous", { days: [7, 6, 5, 4, 3, 2, 1] });
    await addTask("Semaine", { days: [5, 4, 3, 2, 1, 1], remindAt: "08:00" });
    await addTask("Week-end", { days: [7, 6] });
    await addTask("Certains", { days: [5, 1, 3] });
    assert.deepEqual(
      (await mine()).map((t) => [t.label, t.schedule]),
      [
        ["Tous", "Tous les jours"],
        ["Semaine", "Lun–ven · 08:00"],
        ["Week-end", "Week-end"],
        ["Certains", "Lun, mer, ven"],
      ],
    );
  });

  it("rappel toutes les 30 min pendant 2 h, jamais après minuit", async () => {
    await addTask("Soir", { days: [1], remindAt: "22:45" });
    await addTask("Matin", { days: [1], remindAt: "09:10" });
    const stored = JSON.parse(await fs.readFile(file, "utf8")).recurring;
    assert.deepEqual(
      stored.map((t: Record<string, unknown>) => [t.remindAt, t.repeatMinutes, t.remindUntil]),
      [
        ["22:45", 30, "23:59"],
        ["09:10", 30, "11:10"],
      ],
    );
  });

  it("n'apparaît dans la liste du jour que ses jours-là, mais reste dans la liste 🔁", async () => {
    const todayTask = await addTask("Aujourd'hui", { days: [today] });
    const other = await addTask("Un autre jour", { days: [otherDay] });
    const ids = (await listTasks()).map((t) => t.id);
    assert.ok(ids.includes(todayTask.id));
    assert.ok(!ids.includes(other.id));
    assert.deepEqual(
      (await mine()).map((t) => t.id),
      [todayTask.id, other.id],
    );
  });

  it("cochée pour aujourd'hui seulement, décochable, supprimable", async () => {
    const task = await addTask("Sport", { days: [today] });
    assert.equal(await setTaskDone(task.id, true), true);
    assert.equal((await listTasks()).find((t) => t.id === task.id)?.done, true);
    await setTaskDone(task.id, false);
    assert.equal((await listTasks()).find((t) => t.id === task.id)?.done, false);

    assert.equal(await deleteTask(task.id), true);
    assert.equal(await deleteTask(task.id), false);
    assert.equal(await setTaskDone(task.id, true), false);
  });

  it("garde une semaine de jours cochés", async () => {
    const task = await addTask("Sport", { days: [today] });
    await fs.writeFile(file, JSON.stringify({ ...JSON.parse(await fs.readFile(file, "utf8")), done: { "2000-01-01": [task.id] } }));
    await setTaskDone(task.id, true);
    assert.deepEqual(Object.keys(JSON.parse(await fs.readFile(file, "utf8")).done).length, 1);
  });
});

describe("rappels des tâches récurrentes", () => {
  const recurring = (task: Record<string, unknown>) => fs.writeFile(file, JSON.stringify({ done: {}, items: [], recurring: [task] }));
  const tick = async (last: Map<string, number>) => {
    const said: string[] = [];
    await reminderTick(async (text) => (said.push(text), true), last);
    return said;
  };

  it("rappelle à l'heure, puis toutes les `repeatMinutes`, jusqu'à ce qu'elle soit faite", async () => {
    const now = new Date();
    await recurring({ id: "r-1", label: "Worklog", days: [today], remindAt: hhmm(now), repeatMinutes: 30, remindUntil: "23:59" });
    const last = new Map<string, number>();
    assert.deepEqual(await tick(last), ["À faire : Worklog"]);
    assert.deepEqual(await tick(last), [], "pas avant 30 min");

    // 31 min plus tard.
    for (const key of last.keys()) last.set(key, Date.now() - 31 * 60_000);
    assert.deepEqual(await tick(last), ["À faire : Worklog"]);

    for (const key of last.keys()) last.set(key, Date.now() - 31 * 60_000);
    await setTaskDone("r-1", true);
    assert.deepEqual(await tick(last), [], "faite : plus de rappel");
  });

  it("sans `repeatMinutes` : une fois par jour", async () => {
    await recurring({ id: "r-2", label: "Eau", remindAt: hhmm(new Date()), remindUntil: "23:59" });
    const last = new Map<string, number>();
    assert.deepEqual(await tick(last), ["À faire : Eau"]);
    for (const key of last.keys()) last.set(key, 0);
    assert.deepEqual(await tick(last), []);
  });

  it("ni un autre jour, ni avant l'heure, ni après `remindUntil`", async () => {
    const last = new Map<string, number>();
    await recurring({ id: "r-3", label: "Autre jour", days: [otherDay], remindAt: "00:00", remindUntil: "23:59" });
    assert.deepEqual(await tick(last), []);
    await recurring({ id: "r-4", label: "Plus tard", remindAt: "23:59" });
    if (hhmm(new Date()) !== "23:59") assert.deepEqual(await tick(last), []);
    await recurring({ id: "r-5", label: "Fini", remindAt: "00:00", remindUntil: "00:00" });
    if (hhmm(new Date()) !== "00:00") assert.deepEqual(await tick(last), []);
  });

  it("tablette injoignable : rappel retenté au tick suivant", async () => {
    await recurring({ id: "r-6", label: "Réessai", remindAt: "00:00", remindUntil: "23:59" });
    const last = new Map<string, number>();
    await reminderTick(async () => false, last);
    assert.equal(last.size, 0);
    assert.deepEqual(await tick(last), ["À faire : Réessai"]);
  });
});

describe("corvées à tour de rôle", () => {
  const assignee = async (id: string) => (await listTasks()).find((t) => t.id === id)?.assignee;

  it("affiche la première personne, et cocher passe la corvée à la suivante", async () => {
    const task = await addTask("Sortir les poubelles", { days: [1, 2, 3, 4, 5, 6, 7], people: ["Léa", "Tom", "Sam"] });
    assert.equal(task.assignee, "Léa");
    await setTaskDone(task.id, true);
    assert.equal(await assignee(task.id), "Tom");
    await setTaskDone(task.id, false);
    assert.equal(await assignee(task.id), "Léa");
  });

  it("cocher deux fois la même journée n'avance qu'une fois, et la liste reboucle", async () => {
    const task = await addTask("Vaisselle", { days: [1, 2, 3, 4, 5, 6, 7], people: ["Léa", "Tom"] });
    await setTaskDone(task.id, true);
    await setTaskDone(task.id, true);
    assert.equal(await assignee(task.id), "Tom");
    await setTaskDone(task.id, false);
    await setTaskDone(task.id, true);
    await setTaskDone(task.id, false);
    assert.equal(await assignee(task.id), "Léa");
  });

  it("supprimer la corvée efface son tour", async () => {
    const task = await addTask("Éphémère", { days: [1, 2, 3, 4, 5, 6, 7], people: ["Léa", "Tom"] });
    await setTaskDone(task.id, true);
    await deleteTask(task.id);
    assert.equal(JSON.parse(await fs.readFile(file, "utf8")).turns?.[task.id], undefined);
  });

  it("une tâche sans personnes n'a pas de responsable", async () => {
    const task = await addTask("Seul", { days: [1, 2, 3, 4, 5, 6, 7] });
    assert.equal(task.assignee, null);
  });
});
