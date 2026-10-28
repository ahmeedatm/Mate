import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { handlePointerDatagram, MAX_RATE, parsePointerPacket, PointerGate } from "../src/pointer.ts";

const packet = (token: string, seq: number | string, body: unknown) =>
  Buffer.from(`UNOWHY-POINTER\n${token}\n${seq}\n${JSON.stringify(body)}`);

describe("parsePointerPacket", () => {
  it("lit un déplacement et un défilement", () => {
    assert.deepEqual(parsePointerPacket(packet("t", 7, { dx: 5, dy: -3 }), "t"), { seq: 7, kind: "move", dx: 5, dy: -3 });
    assert.deepEqual(parsePointerPacket(packet("t", 8, { sx: 0, sy: -2 }), "t"), { seq: 8, kind: "scroll", sx: 0, sy: -2 });
  });

  it("refuse un mauvais jeton, de longueur égale ou non", () => {
    assert.equal(parsePointerPacket(packet("x", 1, { dx: 1, dy: 1 }), "t"), null);
    assert.equal(parsePointerPacket(packet("tt", 1, { dx: 1, dy: 1 }), "t"), null);
    assert.equal(parsePointerPacket(packet("", 1, { dx: 1, dy: 1 }), "t"), null);
  });

  it("sans jeton attendu, n'en vérifie aucun", () => {
    assert.ok(parsePointerPacket(packet("", 1, { dx: 1, dy: 1 }), undefined));
  });

  it("refuse les paquets mal formés", () => {
    for (const bad of [
      "UNOWHY-POINTER\nt\n1",
      "UNOWHY-POINTER\nt\n1\n{pas du json",
      "UNOWHY-POINTER\nt\n1\n[]",
      "UNOWHY-POINTER\nt\n1\nnull",
      "UNOWHY-POINTER\nt\n1\n{}",
      'UNOWHY-POINTER\nt\n1\n{"dx":1}',
      'UNOWHY-POINTER\nt\n1\n{"dx":1.5,"dy":0}',
      'UNOWHY-POINTER\nt\n1\n{"dx":"1","dy":0}',
      'UNOWHY-POINTER\nt\n-1\n{"dx":1,"dy":0}',
      'UNOWHY-POINTER\nt\nabc\n{"dx":1,"dy":0}',
      'UNOWHY-POINTER\nt\n1\n{"dx":1,"dy":0}\nen trop',
      'AUTRE\nt\n1\n{"dx":1,"dy":0}',
      "",
    ]) {
      assert.equal(parsePointerPacket(Buffer.from(bad), "t"), null, bad);
    }
  });

  it("borne les valeurs : 2000 pixels et 50 crans", () => {
    assert.ok(parsePointerPacket(packet("t", 1, { dx: 2000, dy: -2000 }), "t"));
    assert.equal(parsePointerPacket(packet("t", 1, { dx: 2001, dy: 0 }), "t"), null);
    assert.equal(parsePointerPacket(packet("t", 1, { dx: 0, dy: -2001 }), "t"), null);
    assert.ok(parsePointerPacket(packet("t", 1, { sx: 50, sy: -50 }), "t"));
    assert.equal(parsePointerPacket(packet("t", 1, { sx: 51, sy: 0 }), "t"), null);
  });

  it("refuse un paquet démesuré", () => {
    assert.equal(parsePointerPacket(packet("t", 1, { dx: 1, dy: 1, pad: "x".repeat(600) }), "t"), null);
  });
});

describe("PointerGate", () => {
  it("n'accepte qu'un seq plus récent que le dernier, par source", () => {
    const gate = new PointerGate();
    assert.equal(gate.accept("a:1", 5, 0), true);
    assert.equal(gate.accept("a:1", 5, 1), false);
    assert.equal(gate.accept("a:1", 4, 2), false);
    assert.equal(gate.accept("a:1", 9, 3), true);
    // Autre source (adresse ou port) : sa propre séquence.
    assert.equal(gate.accept("a:2", 1, 4), true);
    assert.equal(gate.accept("b:1", 1, 5), true);
  });

  it("limite à 200 paquets par seconde et par source, puis reprend", () => {
    const gate = new PointerGate();
    let accepted = 0;
    for (let i = 0; i < MAX_RATE + 50; i++) if (gate.accept("a:1", i, 100)) accepted++;
    assert.equal(accepted, MAX_RATE);
    assert.equal(gate.accept("b:1", 0, 100), true, "une autre source n'est pas touchée");
    assert.equal(gate.accept("a:1", 1000, 1200), true, "la seconde suivante repart");
  });

  it("oublie une source muette : la tablette relancée recommence sa séquence", () => {
    const gate = new PointerGate();
    assert.equal(gate.accept("a:1", 500, 0), true);
    assert.equal(gate.accept("a:1", 1, 10_000), false);
    assert.equal(gate.accept("a:1", 1, 60_000), true);
  });
});

describe("handlePointerDatagram", () => {
  const from = { address: "10.0.0.2", port: 5555 };
  const calls: unknown[][] = [];
  const sink = {
    move: async (dx: number, dy: number) => void calls.push(["move", dx, dy]),
    scroll: async (sx: number, sy: number) => void calls.push(["scroll", sx, sy]),
  };

  it("applique un paquet valide, ignore le jeton faux, le seq rejoué et un pilotage en panne", async () => {
    const gate = new PointerGate();
    calls.length = 0;
    await handlePointerDatagram(gate, packet("t", 1, { dx: 4, dy: 5 }), from, "t", sink);
    await handlePointerDatagram(gate, packet("t", 2, { sx: 0, sy: 3 }), from, "t", sink);
    await handlePointerDatagram(gate, packet("faux", 3, { dx: 1, dy: 1 }), from, "t", sink);
    await handlePointerDatagram(gate, packet("t", 2, { dx: 1, dy: 1 }), from, "t", sink);
    await handlePointerDatagram(gate, packet("t", 4, { dx: 1, dy: 1 }), from, "t", {
      move: async () => {
        throw new Error("501");
      },
      scroll: sink.scroll,
    });
    assert.deepEqual(calls, [
      ["move", 4, 5],
      ["scroll", 0, 3],
    ]);
  });
});
