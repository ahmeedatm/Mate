import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { runSanity } from "../qa/sanity.ts";
import { mediaUnavailable, whenMediaAvailable } from "../src/driver-common.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

// Lecture en cours sans session de bureau (Windows lancé en service) : 503 et une phrase fixe, comme le presse-papiers.

let server: TestServer;
before(async () => {
  server = await startServer({ os: "windows" });
});
after(async () => {
  await server?.stop();
});

describe("GET /system/media sans session de bureau", () => {
  it("répond 503 avec une phrase claire", async () => {
    server.setOs({ mediaUnavailable: true });
    const { status, json } = await server.call("GET", "/system/media");
    assert.equal(status, 503);
    assert.match(json.message, /indisponible.*session de bureau/);
    server.setOs({});
  });

  it("le contrôle sanity passe son tour sur ce 503", async () => {
    server.setOs({ mediaUnavailable: true });
    const r = (await runSanity({ url: server.url, token: TOKEN, os: "windows" })).find((c) => c.name === "minuteur de pause musique")!;
    assert.equal(r.status, "skip", r.detail);
    server.setOs({});
  });
});

describe("whenMediaAvailable (erreur du pilote Windows)", () => {
  it("change l'erreur qui porte MEDIA_UNAVAILABLE en 503, sans laisser le mot interne ni le texte de Windows passer", async () => {
    const driverError = new Error("Le service spécifié n'existe pas (MEDIA_UNAVAILABLE)");
    await assert.rejects(whenMediaAvailable(Promise.reject(driverError)), (e: Error & { statusCode?: number }) => {
      return e.statusCode === 503 && /indisponible/.test(e.message) && !/MEDIA_UNAVAILABLE|service spécifié/.test(e.message);
    });
  });

  it("laisse passer une autre erreur et un résultat", async () => {
    const other = new Error("Windows ne répond pas");
    await assert.rejects(whenMediaAvailable(Promise.reject(other)), (e: Error) => e === other);
    assert.equal(await whenMediaAvailable(Promise.resolve("ok")), "ok");
    assert.throws(mediaUnavailable, (e: Error & { statusCode?: number }) => e.statusCode === 503);
  });
});
