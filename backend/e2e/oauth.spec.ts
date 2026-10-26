import { expect, test } from "./fixtures.ts";

/** Retour de Microsoft/Google après la connexion d'un agenda : chaque échec ramène sur la page locale avec un message. */
test.describe("retour OAuth /oauth/:provider/callback", () => {
  const cases = [
    { name: "connexion annulée par l'utilisateur", query: "error=access_denied", result: "refused", message: "Connexion annulée." },
    {
      name: "erreur du fournisseur",
      query: "error=server_error&error_description=boom",
      result: "error",
      message: /^La connexion a échoué/,
    },
    { name: "code absent", query: "state=abc", result: "error", message: /^La connexion a échoué/ },
    { name: "state inconnu ou expiré", query: "code=abc&state=inconnu", result: "error", message: /^La connexion a échoué/ },
  ];
  for (const provider of ["microsoft", "google"]) {
    for (const c of cases) {
      test(`${provider} : ${c.name}`, async ({ page, server }) => {
        await page.goto(`/oauth/${provider}/callback?${c.query}`);
        await expect(page).toHaveURL(`${server.url}/?section=agenda&account=${c.result}`);
        await expect(page.locator("#accountmsg")).toHaveText(c.message);
        await expect(page.locator("#agenda")).toHaveClass(/focus/);
        // Rien n'est enregistré : aucun compte connecté.
        const { json } = await server.call("GET", "/local/status", undefined, false);
        expect(json.calendar.accounts).toEqual([]);
      });
    }
  }

  test("fournisseur inconnu : refusé sans jeton", async ({ request }) => {
    const res = await request.get("/oauth/yahoo/callback?code=abc&state=abc", { maxRedirects: 0 });
    expect(res.status()).toBe(401);
  });

  test("connexion non configurée : /oauth/:provider/start répond 404", async ({ request }) => {
    const res = await request.get("/oauth/google/start", { maxRedirects: 0 });
    expect(res.status()).toBe(404);
    expect((await res.json()).error).toBe("Connexion non disponible pour ce compte");
  });
});
