import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { beforeEach, test } from "node:test";

process.env.DATA_DIR = await fs.mkdtemp(path.join(os.tmpdir(), "companion-pairing-"));
const { approvePairing, confirmPairing, pairingCode, pendingCode, requestPairing, resetPairing, revealPairing } =
  await import("../src/pairing.ts");

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

/** Côté tablette : nombre secret et son engagement. */
const secret = () => {
  const nonce = randomBytes(32).toString("base64url");
  return { nonce, commit: createHash("sha256").update(nonce).digest("base64url") };
};
const FP = "aa".repeat(32);

/** Parcours de la tablette jusqu'au code qu'elle affiche (calculé avec l'empreinte qu'elle a vue). */
function pairUntilCode(ip = "192.168.1.20", seenFingerprint = FP, channel = FP) {
  const t = secret();
  const { request, nonce } = requestPairing("Tablette d'Eliot", ip, t.commit, channel);
  revealPairing(request, t.nonce, show);
  return { request, code: pairingCode(seenFingerprint, t.nonce, nonce) };
}

beforeEach(() => {
  resetPairing();
  dialogs = 0;
});

test("parcours normal : la tablette affiche le code, l'ordinateur le reçoit, la tablette reçoit le jeton et l'empreinte", () => {
  const { request, code } = pairUntilCode();
  assert.equal(dialogs, 1);
  assert.equal(pendingCode()?.tablet, "Tablette d'Eliot");
  assert.ok(pendingCode()!.remainingS <= pendingCode()!.ttlS);
  assert.deepEqual(confirmPairing(request, FP), { waiting: true });
  approvePairing(code.slice(0, 3) + " " + code.slice(3));
  const out = confirmPairing(request, FP);
  assert.ok("token" in out);
  assert.equal(out.fingerprint, FP);
  assert.equal(pendingCode(), null);
  assert.equal(
    status(() => confirmPairing(request, FP)),
    410,
  );
});

test("intercepteur avec son propre certificat : les deux codes diffèrent, le jeton n'est jamais donné", () => {
  // La tablette voit le certificat de l'intercepteur, l'ordinateur calcule avec le sien.
  const { request, code } = pairUntilCode("192.168.1.20", "bb".repeat(32), FP);
  for (let i = 0; i < 3; i++)
    assert.equal(
      status(() => approvePairing(code)),
      403,
    );
  assert.equal(
    status(() => confirmPairing(request, FP)),
    410,
  );
});

test("la tablette ne peut pas changer de nombre après s'être engagée", () => {
  const t = secret();
  const { request } = requestPairing("Tablette", "192.168.1.20", t.commit, FP);
  assert.equal(
    status(() => revealPairing(request, secret().nonce, show)),
    403,
  );
  assert.equal(dialogs, 0);
  assert.equal(
    status(() => revealPairing(request, t.nonce, show)),
    410,
  );
});

test("le code ne se donne qu'une fois dévoilé, et ne sort jamais par la page locale", () => {
  const t = secret();
  requestPairing("Tablette", "192.168.1.20", t.commit, FP);
  assert.equal(pendingCode(), null);
  assert.equal(
    status(() => approvePairing("000000")),
    410,
  );
  assert.ok(!JSON.stringify(pendingCode()).match(/\d{6}/));
});

test("une demande en cours n'est pas écrasée par une autre adresse ; la même adresse repart de zéro", () => {
  const first = pairUntilCode();
  assert.equal(
    status(() => requestPairing("Intrus", "192.168.1.99", secret().commit, FP)),
    409,
  );
  approvePairing(first.code);
  assert.ok("token" in confirmPairing(first.request, FP));

  const again = requestPairing("Tablette", "192.168.1.20", secret().commit, FP);
  const restarted = requestPairing("Tablette", "192.168.1.20", secret().commit, FP);
  assert.notEqual(restarted.request, again.request);
});

test("rafale depuis des adresses différentes : aucun dialogue tant que personne n'a dévoilé son nombre", () => {
  for (let i = 0; i < 100; i++) status(() => requestPairing("Spam", `10.0.0.${i}`, secret().commit, FP));
  assert.equal(dialogs, 0);
});

test("la limite est par adresse ; trois mauvais codes annulent la demande et libèrent la place", () => {
  for (let i = 0; i < 5; i++) requestPairing("Spam", "10.0.0.5", secret().commit, FP);
  assert.equal(
    status(() => requestPairing("Spam", "10.0.0.5", secret().commit, FP)),
    429,
  );
  resetPairing();
  pairUntilCode("10.0.0.6");
  for (let i = 0; i < 3; i++) status(() => approvePairing("xxxxxx"));
  assert.equal(pendingCode(), null);
  assert.equal(
    status(() => requestPairing("Tablette", "192.168.1.20", secret().commit, FP)),
    200,
  );
});

test("code : 6 chiffres, dépend de chaque entrée", () => {
  const base = pairingCode(FP, "a", "b");
  assert.match(base, /^\d{6}$/);
  assert.notEqual(pairingCode("", "a", "b"), base);
  assert.notEqual(pairingCode(FP, "c", "b"), base);
  assert.notEqual(pairingCode(FP, "a", "c"), base);
});
