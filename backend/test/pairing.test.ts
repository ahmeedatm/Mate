import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { beforeEach, test } from "node:test";

process.env.DATA_DIR = await fs.mkdtemp(path.join(os.tmpdir(), "companion-pairing-"));
const { confirmPairing, pendingCode, requestPairing, resetPairing } = await import("../src/pairing.ts");

let dialogs = 0;
const show = async () => void dialogs++;
const status = (fn: () => unknown) => {
  try {
    fn();
    return 200;
  } catch (e) {
    return (e as { statusCode?: number }).statusCode;
  }
};

beforeEach(() => {
  resetPairing();
  dialogs = 0;
});

test("parcours normal : une demande, un dialogue, le bon code donne le jeton", () => {
  const { request, expiresInMs } = requestPairing("Tablette d'Eliot", "192.168.1.20", show);
  assert.ok(expiresInMs > 0);
  assert.equal(dialogs, 1);
  const out = confirmPairing(request, pendingCode()!.code);
  assert.equal(out.service, "unowhy-companion");
  assert.equal(pendingCode(), null);
});

test("un code en attente valide n'est pas écrasé par une autre adresse", () => {
  const first = requestPairing("Tablette", "192.168.1.20", show);
  const code = pendingCode()!.code;
  assert.equal(
    status(() => requestPairing("Intrus", "192.168.1.99", show)),
    409,
  );
  assert.equal(pendingCode()!.code, code);
  assert.equal(dialogs, 1);
  assert.ok(confirmPairing(first.request, code));
});

test("la même tablette qui redemande retrouve sa demande, sans second dialogue", () => {
  const first = requestPairing("Tablette", "192.168.1.20", show);
  const again = requestPairing("Tablette", "192.168.1.20", show);
  assert.equal(again.request, first.request);
  assert.equal(dialogs, 1);
});

test("rafale depuis des adresses différentes : un seul dialogue", () => {
  for (let i = 0; i < 100; i++) status(() => requestPairing("Spam", `10.0.0.${i}`, show));
  assert.equal(dialogs, 1);
});

test("la limite est par adresse : un abus n'empêche pas une autre tablette", () => {
  const spam = requestPairing("Spam", "10.0.0.5", show);
  for (let i = 0; i < 4; i++) requestPairing("Spam", "10.0.0.5", show);
  assert.equal(
    status(() => requestPairing("Spam", "10.0.0.5", show)),
    429,
  );
  // le code du spam est épuisé (3 mauvais essais) : la vraie tablette demande à son tour
  for (let i = 0; i < 3; i++) status(() => confirmPairing(spam.request, "xxxxxx"));
  assert.equal(pendingCode(), null);
  assert.equal(
    status(() => requestPairing("Tablette", "192.168.1.20", show)),
    200,
  );
});
