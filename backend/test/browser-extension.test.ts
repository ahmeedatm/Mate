import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { sendToExtension, waitCommands } from "../src/browser-extension.ts";

describe("ordres de lecture pour l'extension du navigateur", () => {
  it("ordre remis à l'attente en cours ; « Google Chrome » = « Chrome »", async () => {
    const waiting = waitCommands("Chrome");
    assert.equal(sendToExtension("Google Chrome", { action: "play-pause", title: "Ghost" }), true);
    assert.deepEqual(await waiting, [{ action: "play-pause", title: "Ghost" }]);
  });

  it("entre deux attentes : gardé pour la suivante ; navigateur sans extension : refusé", async () => {
    await waitCommands("Opera GX", undefined, 1);
    assert.equal(sendToExtension("Opera GX", { action: "next" }), true);
    assert.deepEqual(await waitCommands("Opera GX"), [{ action: "next" }]);
    assert.equal(sendToExtension("Safari", { action: "next" }), false);
  });

  it("extension partie pendant l'attente : liste vide", async () => {
    const gone = new AbortController();
    const waiting = waitCommands("Brave", gone.signal);
    gone.abort();
    assert.deepEqual(await waiting, []);
  });
});
