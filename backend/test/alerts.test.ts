import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseAlertSpecs, startAlertWatch, type AlertSpec } from "../src/alerts.ts";

const until = async (done: () => boolean, ms = 2000) => {
  const end = Date.now() + ms;
  while (!done()) {
    if (Date.now() > end) throw new Error("délai dépassé");
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
};

type States = Record<string, { state: string; name?: string }>;

describe("HA_ALERTS : lecture de la liste", () => {
  it("accepte un capteur seul, `:off` et `=texte`, ignore le reste", () => {
    const specs = parseAlertSpecs(
      "binary_sensor.porte, binary_sensor.fuite=Fuite sous l'évier,binary_sensor.lessive:off=Lessive finie, light.salon,pas un id,binary_sensor.porte",
    );
    assert.deepEqual(specs, [
      { id: "binary_sensor.porte", when: "on", text: undefined },
      { id: "binary_sensor.fuite", when: "on", text: "Fuite sous l'évier" },
      { id: "binary_sensor.lessive", when: "off", text: "Lessive finie" },
    ]);
  });

  it("liste vide ou absente : aucune alerte", () => {
    assert.deepEqual(parseAlertSpecs(undefined), []);
    assert.deepEqual(parseAlertSpecs("  "), []);
  });
});

describe("alerte maison : l'ordinateur prévient la tablette", () => {
  const specs: AlertSpec[] = [
    { id: "binary_sensor.porte", when: "on" },
    { id: "binary_sensor.lessive", when: "off", text: "Lessive finie" },
  ];

  const run = async (seq: States[], received: () => boolean = () => true) => {
    let reads = 0;
    const sent: string[] = [];
    const stop = startAlertWatch(
      async (text) => {
        sent.push(text);
        return received();
      },
      {
        specs,
        intervalMs: 1,
        read: async () => seq[Math.min(reads++, seq.length - 1)],
      },
    );
    await until(() => reads >= seq.length + 2);
    stop();
    return sent;
  };

  it("n'alerte pas pour l'état trouvé au démarrage", async () => {
    const sent = await run([{ "binary_sensor.porte": { state: "on", name: "Porte" } }]);
    assert.deepEqual(sent, []);
  });

  it("alerte une fois quand le capteur passe à l'état voulu, avec son nom ou son texte", async () => {
    const sent = await run([
      { "binary_sensor.porte": { state: "off", name: "Porte d'entrée" }, "binary_sensor.lessive": { state: "on" } },
      { "binary_sensor.porte": { state: "on", name: "Porte d'entrée" }, "binary_sensor.lessive": { state: "on" } },
      { "binary_sensor.porte": { state: "on", name: "Porte d'entrée" }, "binary_sensor.lessive": { state: "off" } },
    ]);
    assert.deepEqual(sent, ["Porte d'entrée", "Lessive finie"]);
  });

  it("indisponible ne compte pas : le retour à l'état voulu alerte une fois", async () => {
    const sent = await run([
      { "binary_sensor.porte": { state: "off", name: "Porte" } },
      { "binary_sensor.porte": { state: "unavailable", name: "Porte" } },
      { "binary_sensor.porte": { state: "off", name: "Porte" } },
      { "binary_sensor.porte": { state: "on", name: "Porte" } },
    ]);
    assert.deepEqual(sent, ["Porte"]);
  });

  it("renvoie l'alerte tant que la tablette ne l'a pas reçue", async () => {
    let tries = 0;
    const sent = await run(
      [{ "binary_sensor.porte": { state: "off", name: "Porte" } }, { "binary_sensor.porte": { state: "on", name: "Porte" } }],
      () => ++tries >= 3,
    );
    assert.deepEqual(sent, ["Porte", "Porte", "Porte"]);
  });

  it("une lecture ratée garde l'état d'avant", async () => {
    let reads = 0;
    const sent: string[] = [];
    const stop = startAlertWatch(async (t) => (sent.push(t), true), {
      specs,
      intervalMs: 1,
      read: async () => {
        reads++;
        if (reads === 2) throw new Error("Home Assistant ne répond pas");
        return { "binary_sensor.porte": { state: reads === 1 ? "off" : "on", name: "Porte" } };
      },
    });
    await until(() => reads >= 5);
    stop();
    assert.deepEqual(sent, ["Porte"]);
  });

  it("borne le texte et garde les 10 dernières alertes pendant une coupure", async () => {
    const long: AlertSpec[] = [{ id: "binary_sensor.porte", when: "on" }];
    const flip = (n: number) => ({ "binary_sensor.porte": { state: n % 2 ? "on" : "off", name: `${n}`.padEnd(300, "x") } });
    let reads = 0;
    const sent: string[] = [];
    let online = false;
    const stop = startAlertWatch(
      async (text) => {
        if (online) sent.push(text);
        return online;
      },
      { specs: long, intervalMs: 1, read: async () => flip(reads++) },
    );
    await until(() => reads >= 40);
    online = true;
    await until(() => sent.length >= 10);
    stop();
    assert.ok(sent.every((t) => t.length === 200));
    assert.ok(sent.length <= 11, "au plus la file gardée");
  });
});
