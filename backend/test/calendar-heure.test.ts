import assert from "node:assert/strict";
import { test } from "node:test";

// Jour du passage à l'heure d'hiver (25 h) et d'été (23 h) : la plage finit quand même à 23:59:59.999 du dernier jour.
process.env.TZ = "Europe/Paris";
const { dayRange } = await import("../src/calendar.ts");

test("dayRange suit les jours du calendrier, pas des tranches de 24 h", () => {
  for (const now of [new Date(2026, 9, 25, 12), new Date(2026, 2, 29, 12)]) {
    const [from, to] = dayRange(1, now);
    assert.equal(from.getHours(), 0);
    assert.equal(to.getDate(), now.getDate());
    assert.deepEqual([to.getHours(), to.getMinutes(), to.getMilliseconds()], [23, 59, 999]);
  }
  const [, to] = dayRange(7, new Date(2026, 9, 24, 12));
  assert.deepEqual([to.getMonth(), to.getDate(), to.getHours()], [9, 30, 23]);
});
