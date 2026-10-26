import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { test } from "node:test";
import { assertPublicUrl, fetchPublic, isPrivateAddress, readCapped } from "../src/net-guard.ts";

test("adresses privées, loopback et réservées refusées, publiques acceptées", () => {
  for (const ip of [
    "127.0.0.1",
    "10.1.2.3",
    "172.16.0.1",
    "172.31.255.255",
    "192.168.1.1",
    "169.254.169.254",
    "0.0.0.0",
    "100.64.0.1",
    "::1",
    "::",
    "fe80::1",
    "fd12:3456::1",
    "::ffff:127.0.0.1",
    "::ffff:192.168.0.5",
    "pas-une-ip",
  ]) {
    assert.equal(isPrivateAddress(ip), true, ip);
  }
  for (const ip of ["8.8.8.8", "93.184.216.34", "172.32.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"]) {
    assert.equal(isPrivateAddress(ip), false, ip);
  }
});

test("localhost et adresses du réseau local ne sont pas téléchargées", async () => {
  for (const url of ["http://127.0.0.1:1/", "http://localhost:1/", "http://[::1]:1/", "http://192.168.1.10/"]) {
    await assert.rejects(assertPublicUrl(new URL(url)), /réseau local/, url);
  }
});

test("fetchPublic n'atteint pas un serveur local", async () => {
  let hits = 0;
  const server = http.createServer((_req, res) => {
    hits++;
    res.end("secret");
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    await assert.rejects(fetchPublic(`http://127.0.0.1:${(server.address() as AddressInfo).port}/`, { maxBytes: 1000 }), /réseau local/);
    assert.equal(hits, 0);
  } finally {
    server.close();
  }
});

test("readCapped s'arrête au plafond sans lire tout le flux", async () => {
  let pulled = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      pulled++;
      controller.enqueue(new Uint8Array(1000));
    },
  });
  const { data, truncated } = await readCapped(new Response(body), 5000);
  assert.equal(data.length, 5000);
  assert.equal(truncated, true);
  assert.ok(pulled < 20, `${pulled} morceaux lus`);
});

test("readCapped : un corps sous le plafond est rendu en entier", async () => {
  const { data, truncated } = await readCapped(new Response("bonjour"), 5000);
  assert.equal(data.toString(), "bonjour");
  assert.equal(truncated, false);
});
