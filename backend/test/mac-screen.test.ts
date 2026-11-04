import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { screenPreview, type ScreenDeps } from "../src/mac/screen.ts";

// Aperçu de l'écran du Mac : la capture (osascript) n'est jamais lancée sans l'autorisation « Enregistrement de l'écran ».

const status = (e: unknown) => (e as { statusCode?: number }).statusCode;

function fake(allowed: boolean | null, capture?: ScreenDeps["capture"]) {
  const launched: number[] = [];
  const deps: ScreenDeps = {
    allowed: async () => allowed,
    capture: capture ?? (async (width) => (launched.push(width), Buffer.from([0xff, 0xd8, 0xff, 0xd9]).toString("base64"))),
  };
  return { deps, launched };
}

describe("aperçu de l'écran du Mac", () => {
  it("autorisation fausse : 403 avec la marche à suivre, capture jamais lancée", async () => {
    const { deps, launched } = fake(false);
    await assert.rejects(screenPreview(640, deps), (e) => status(e) === 403 && /Enregistrement de l'écran/.test(String(e)));
    assert.deepEqual(launched, []);
  });

  it("autorisation inconnue (utilitaire absent ou planté) : 503 avec une phrase, capture jamais lancée", async () => {
    const { deps, launched } = fake(null);
    await assert.rejects(screenPreview(640, deps), (e) => status(e) === 503 && /autorisations du Mac/.test(String(e)));
    assert.deepEqual(launched, []);
  });

  it("autorisation accordée : la capture est lancée à la largeur demandée, l'image décodée", async () => {
    const { deps, launched } = fake(true);
    const jpeg = await screenPreview(640, deps);
    assert.deepEqual([...jpeg], [0xff, 0xd8, 0xff, 0xd9]);
    assert.deepEqual(launched, [640]);
  });

  it("capture en échec (session sans bureau) : 503, texte technique gardé pour le journal", async () => {
    const { deps } = fake(true, async () => {
      throw new Error("Aucun écran à lire");
    });
    await assert.rejects(screenPreview(640, deps), (e) => status(e) === 503 && (e as { technical?: boolean }).technical === true);
  });
});
