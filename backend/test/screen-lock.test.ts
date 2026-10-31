import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseConsoleUsers } from "../src/mac/lock.ts";
import { isScreenLocked, startScreenLockWatch } from "../src/screen-lock.ts";

const until = async (done: () => boolean, ms = 2000) => {
  const end = Date.now() + ms;
  while (!done()) {
    if (Date.now() > end) throw new Error("délai dépassé");
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
};

/** Sortie d'`ioreg -n Root -d1` réduite : une session d'un autre utilisateur, puis celle de l'uid 502. */
const ioreg = (mine: string) =>
  [
    "+-o Root  <class IORegistryEntry, id 0x100000100, retain 32>",
    `      "IOConsoleUsers" = ({"kCGSSessionOnConsoleKey"=No,"kCGSSessionUserNameKey"="ci-runner","kCGSSessionUserIDKey"=5020},{${mine},"kCGSSessionUserNameKey"="eliot","kCGSSessionUserIDKey"=502})`,
  ].join("\n");

describe("verrouillage du Mac : lecture d'ioreg", () => {
  it("écran verrouillé, déverrouillé, ou pris par un autre utilisateur", () => {
    assert.equal(parseConsoleUsers(ioreg('"kCGSSessionOnConsoleKey"=Yes,"CGSSessionScreenIsLocked"=Yes'), 502), true);
    assert.equal(parseConsoleUsers(ioreg('"kCGSSessionOnConsoleKey"=Yes'), 502), false);
    assert.equal(parseConsoleUsers(ioreg('"kCGSSessionOnConsoleKey"=No'), 502), true);
  });

  it("aucune session graphique pour cet utilisateur : inconnu (502 ne se confond pas avec 5020)", () => {
    assert.equal(parseConsoleUsers(ioreg('"kCGSSessionOnConsoleKey"=Yes'), 50), null);
    assert.equal(parseConsoleUsers("+-o Root", 502), null);
  });
});

describe("verrouillage : la tablette est prévenue", () => {
  it("un ordre par changement, et l'état courant à une tablette qui arrive", async () => {
    // Verrouillé tant qu'aucune tablette n'écoute ; la suite n'avance qu'une fois branchée (sinon elle peut finir
    // avant le branchement, selon la vitesse de la machine, et rien n'est envoyé).
    const seq = [true, true, false, false];
    let before = 0;
    let reads = 0;
    let connected = false;
    const sent: boolean[] = [];
    const stop = startScreenLockWatch(async (l) => (sent.push(l), true), {
      intervalMs: 1,
      connected: () => connected,
      tabletLocked: () => undefined,
      read: async () => (connected ? seq[Math.min(reads++, seq.length - 1)] : (before++, true)),
    });
    await until(() => before >= 2);
    assert.deepEqual(sent, [], "aucune tablette : rien d'envoyé");
    assert.equal(isScreenLocked(), true, "l'API se ferme même sans tablette");
    connected = true;
    await until(() => reads >= seq.length + 2);
    stop();
    assert.deepEqual(sent, [true, false]);
    assert.equal(isScreenLocked(), false);
  });

  it("ordre non reçu : redit ; lecture illisible ou en échec : l'état est gardé", async () => {
    const seq: (boolean | null | Error)[] = [true, null, new Error("helper absent"), true, true];
    let reads = 0;
    let answers = 0;
    const sent: boolean[] = [];
    const stop = startScreenLockWatch(async (l) => (sent.push(l), answers++ > 0), {
      intervalMs: 1,
      connected: () => true,
      tabletLocked: () => undefined,
      read: async () => {
        const v = seq[Math.min(reads++, seq.length - 1)];
        if (v instanceof Error) throw v;
        return v;
      },
    });
    await until(() => reads >= seq.length + 2);
    stop();
    assert.equal(isScreenLocked(), true);
    assert.deepEqual(sent, [true, true], "premier envoi perdu, redit une fois");
  });

  it("tablette qui annonce son écran : seulement quand il n'est pas le bon", async () => {
    let shown: boolean | undefined = true;
    let reads = 0;
    const sent: boolean[] = [];
    const stop = startScreenLockWatch(async (l) => (sent.push(l), (shown = l), true), {
      intervalMs: 1,
      connected: () => true,
      tabletLocked: () => shown,
      read: async () => (reads++, false),
    });
    await until(() => reads >= 5);
    assert.deepEqual(sent, [false], "restée bloquée hors ligne : débloquée une fois");
    shown = true; // 423 reçu juste après le déverrouillage
    await until(() => reads >= 10);
    stop();
    assert.deepEqual(sent, [false, false]);
  });
});
