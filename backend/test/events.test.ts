import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { beforeEach, describe, it } from "node:test";

const dir = await fs.mkdtemp(path.join(os.tmpdir(), "companion-events-"));
process.env.DATA_DIR = dir;
const { addEvent, deleteEvent, eventReminderTick, localEvents, updateEvent } = await import("../src/events.ts");

const file = path.join(dir, "events.json");
const stored = async () => JSON.parse(await fs.readFile(file, "utf8")).items as Record<string, unknown>[];
const HOUR = 3_600_000;

beforeEach(() => fs.rm(file, { force: true }));

describe("agenda personnel : saisie", () => {
  it("rendez-vous sans fin : une heure ; titre nettoyé", async () => {
    const startMs = Date.now() + HOUR;
    const e = await addEvent({ title: "  Dentiste  ", startMs, allDay: false });
    assert.equal(e.title, "Dentiste");
    assert.equal(e.endMs, startMs + HOUR);
    assert.equal("remindBefore" in e, false);
    // Fin avant le début : même règle.
    assert.equal((await addEvent({ title: "X", startMs, endMs: startMs - 1, allDay: false })).endMs, startMs + HOUR);
  });

  it("journée entière : de minuit au minuit suivant, sans rappel", async () => {
    const noon = new Date();
    noon.setHours(12, 34, 0, 0);
    const e = await addEvent({ title: "Congé", startMs: noon.getTime(), allDay: true, remindBefore: 10 });
    const midnight = new Date(noon);
    midnight.setHours(0, 0, 0, 0);
    assert.equal(e.startMs, midnight.getTime());
    assert.equal(e.endMs, new Date(midnight).setDate(midnight.getDate() + 1));
    assert.equal(e.remindBefore, undefined);
  });

  it("modifier réarme le rappel ; un identifiant inconnu ne change rien", async () => {
    const startMs = Date.now() + 2 * 60_000;
    const e = await addEvent({ title: "Appel", startMs, allDay: false, remindBefore: 5 });
    await eventReminderTick(async () => true);
    assert.ok((await stored())[0].remindedMs, "rappel fait");

    const moved = await updateEvent(e.id, { title: "Appel décalé", startMs: startMs + HOUR, allDay: false, remindBefore: 5 });
    assert.equal(moved?.id, e.id);
    assert.equal((await stored())[0].remindedMs, undefined);
    assert.equal(await updateEvent("inconnu", { title: "X", startMs, allDay: false }), undefined);
  });

  it("supprime, une seule fois", async () => {
    const e = await addEvent({ title: "X", startMs: Date.now(), allDay: false });
    assert.equal(await deleteEvent(e.id), true);
    assert.equal(await deleteEvent(e.id), false);
  });

  it("oublie les rendez-vous finis depuis plus d'un mois, et garde la liste triée", async () => {
    const old = Date.now() - 40 * 24 * HOUR;
    await fs.writeFile(file, JSON.stringify({ items: [{ id: "vieux", title: "Vieux", startMs: old, endMs: old + HOUR, allDay: false }] }));
    await addEvent({ title: "Après", startMs: Date.now() + 2 * HOUR, allDay: false });
    await addEvent({ title: "Avant", startMs: Date.now() + HOUR, allDay: false });
    assert.deepEqual((await stored()).map((e) => e.title), ["Avant", "Après"]);
  });
});

describe("agenda personnel : lecture", () => {
  it("donne les rendez-vous qui touchent la plage, au format des autres agendas", async () => {
    const now = Date.now();
    const inside = await addEvent({ title: "Dedans", startMs: now + HOUR, allDay: false, remindBefore: 0 });
    await addEvent({ title: "Commencé", startMs: now - HOUR, endMs: now + 60_000, allDay: false });
    await addEvent({ title: "Fini", startMs: now - 2 * HOUR, endMs: now - HOUR, allDay: false });
    await addEvent({ title: "Plus tard", startMs: now + 48 * HOUR, allDay: false });

    const events = await localEvents(new Date(now), new Date(now + 24 * HOUR));
    assert.deepEqual(events.map((e) => e.title), ["Commencé", "Dedans"]);
    assert.deepEqual(events[1], {
      id: `local-${inside.id}@${inside.startMs}`, title: "Dedans", location: "", startMs: inside.startMs, endMs: inside.endMs,
      allDay: false, free: false, localId: inside.id, remindBefore: 0,
    });
  });
});

describe("agenda personnel : rappels parlés", () => {
  const tick = async () => {
    const said: string[] = [];
    await eventReminderTick(async (text) => (said.push(text), true));
    return said;
  };

  it("annonce les minutes restantes, ou l'heure quand c'est maintenant", async () => {
    const now = Date.now();
    await addEvent({ title: "Bientôt", startMs: now + 10 * 60_000 + 20_000, allDay: false, remindBefore: 15 });
    const start = now - 30_000;
    await addEvent({ title: "Maintenant", startMs: start, allDay: false, remindBefore: 0 });
    const hhmm = new Date(start).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
    assert.deepEqual((await tick()).sort(), ["Dans 10 min : Bientôt", `${hhmm} : Maintenant`].sort());
    assert.deepEqual(await tick(), [], "une seule fois");
  });

  it("ni trop tôt, ni plus d'une heure après le début, ni sans rappel demandé", async () => {
    const now = Date.now();
    await addEvent({ title: "Trop tôt", startMs: now + 30 * 60_000, allDay: false, remindBefore: 10 });
    await addEvent({ title: "Trop tard", startMs: now - 2 * HOUR, allDay: false, remindBefore: 0 });
    await addEvent({ title: "Sans rappel", startMs: now + 60_000, allDay: false });
    assert.deepEqual(await tick(), []);
  });

  it("rappel non reçu par la tablette : réessayé au tick suivant", async () => {
    await addEvent({ title: "Réessai", startMs: Date.now() + 60_000, allDay: false, remindBefore: 5 });
    let tries = 0;
    await eventReminderTick(async () => (tries++, false));
    assert.equal((await stored())[0].remindedMs, undefined);
    assert.deepEqual(await tick(), ["Dans 1 min : Réessai"]);
    assert.equal(tries, 1);
  });
});
