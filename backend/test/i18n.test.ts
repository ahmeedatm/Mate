import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { langOf, translate } from "../src/i18n.ts";
import { startServer, TOKEN, type TestServer } from "./server.ts";

describe("langue des messages : Accept-Language", () => {
  it("anglais seulement si la première langue demandée est l'anglais", () => {
    assert.equal(langOf("en"), "en");
    assert.equal(langOf("en-GB,en;q=0.9,fr;q=0.8"), "en");
    assert.equal(langOf("fr"), "fr");
    assert.equal(langOf("fr-FR,en;q=0.5"), "fr");
    assert.equal(langOf("*"), "fr");
    assert.equal(langOf("de"), "fr");
    assert.equal(langOf(undefined), "fr");
  });

  it("traduit un message connu, avec ses valeurs, et laisse le reste en français", () => {
    assert.equal(translate("Demande invalide : mets à jour l'appli de la tablette", "en"), "Invalid request: update the tablet app");
    assert.equal(translate("Fichier introuvable dans le dossier partagé", "fr"), "Fichier introuvable dans le dossier partagé");
    assert.equal(
      translate("{} : adresse hors du réseau local refusée".replace("{}", "nas"), "en"),
      "nas: address outside the local network refused",
    );
    assert.equal(translate("Un message que personne n'a traduit", "en"), "Un message que personne n'a traduit");
  });
});

describe("langue des messages : erreurs de l'API", () => {
  let server: TestServer;
  before(async () => {
    server = await startServer();
  });
  after(() => server.stop());

  const invalid = (lang?: string) =>
    fetch(`${server.url}/tasks`, {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json", ...(lang ? { "accept-language": lang } : {}) },
      body: JSON.stringify({ label: "" }),
    }).then(async (res) => ({ status: res.status, json: (await res.json()) as { error: string } }));

  it("tablette en anglais : l'erreur arrive en anglais, même code", async () => {
    assert.deepEqual(await invalid("en"), { status: 400, json: { error: "Invalid request: update the tablet app" } });
  });

  it("sans langue ou en français : inchangée", async () => {
    assert.equal((await invalid()).json.error, "Demande invalide : mets à jour l'appli de la tablette");
    assert.equal((await invalid("fr")).json.error, "Demande invalide : mets à jour l'appli de la tablette");
  });
});
