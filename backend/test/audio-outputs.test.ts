import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import type { AudioOutput } from "../src/screens.ts";
import { startServer, type TestServer } from "./server.ts";

const outputs: AudioOutput[] = [
  { id: "{haut-parleurs}", name: "Haut-parleurs", current: true },
  { id: "{casque}", name: "Casque USB", current: false },
];

for (const os of ["windows", "mac"] as const) {
  describe(`Choix de la sortie audio (${os})`, () => {
    let server: TestServer;
    before(async () => {
      server = await startServer({ os });
    });
    after(async () => {
      await server?.stop();
    });
    beforeEach(() => {
      server.setOs({ audioOutputs: outputs });
      server.clearOsCalls();
    });

    it("liste les sorties et marque celle qui sert", async () => {
      const res = await server.call("GET", "/system/audio/outputs");
      assert.equal(res.status, 200);
      assert.deepEqual(res.json.outputs, outputs);
    });

    it("passe sur une autre sortie et renvoie la liste à jour", async () => {
      const res = await server.call("POST", "/system/audio/output", { id: "{casque}" });
      assert.equal(res.status, 200);
      assert.deepEqual(
        res.json.outputs.map((o: AudioOutput) => [o.id, o.current]),
        [
          ["{haut-parleurs}", false],
          ["{casque}", true],
        ],
      );
      assert.deepEqual(
        server.osCalls().filter((c) => c.fn === "setAudioOutput"),
        [{ fn: "setAudioOutput", args: ["{casque}"] }],
      );
    });

    it("refuse une sortie qui n'existe pas, sans rien changer", async () => {
      const res = await server.call("POST", "/system/audio/output", { id: "{inconnue}" });
      assert.equal(res.status, 404);
      assert.equal(server.osCalls().filter((c) => c.fn === "setAudioOutput").length, 0);
    });

    it("refuse un identifiant vide ou absent", async () => {
      assert.equal((await server.call("POST", "/system/audio/output", { id: "" })).status, 400);
      assert.equal((await server.call("POST", "/system/audio/output", {})).status, 400);
    });

    it("exige le jeton", async () => {
      assert.equal((await server.call("GET", "/system/audio/outputs", undefined, false)).status, 401);
      assert.equal((await server.call("POST", "/system/audio/output", { id: "{casque}" }, false)).status, 401);
    });
  });
}

describe("Choix de la sortie audio (système non pilotable)", () => {
  it("répond 501", async () => {
    const server = await startServer({ os: "other" });
    try {
      assert.equal((await server.call("GET", "/system/audio/outputs")).status, 501);
    } finally {
      await server.stop();
    }
  });
});

describe("Lecture de la liste des sorties du Mac", () => {
  it("garde les sorties complètes et écarte le reste", async () => {
    const { parseAudioOutputs } = await import("../src/mac/audio.ts");
    assert.deepEqual(
      parseAudioOutputs('[{"id":"A","name":"Casque","current":true},{"id":"","name":"x"},{"name":"sans id"},{"id":"B","name":"Écran"}]'),
      [
        { id: "A", name: "Casque", current: true },
        { id: "B", name: "Écran", current: false },
      ],
    );
  });

  it("refuse un texte qui n'est pas une liste", async () => {
    const { parseAudioOutputs } = await import("../src/mac/audio.ts");
    assert.throws(() => parseAudioOutputs("{}"), /illisible/);
  });
});
