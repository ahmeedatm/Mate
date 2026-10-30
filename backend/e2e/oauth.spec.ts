import fs from "node:fs";
import path from "node:path";
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

/** Compte Microsoft relié avant la page Outlook du Deck : sans l'accès au courrier, la page propose de le reconnecter. */
test.describe("compte Microsoft et courrier", () => {
  const link = (server: { dataDir: string }, account: object) =>
    fs.writeFileSync(
      path.join(server.dataDir, "accounts.json"),
      JSON.stringify({ microsoft: { email: "moi@exemple.fr", refreshToken: "r1", ...account } }),
    );

  test("sans Mail.Read : à reconnecter pour le courrier", async ({ page, server }) => {
    link(server, {});
    try {
      await page.goto("/");
      const tile = page.locator(".cx", { hasText: "Microsoft" });
      await expect(tile).toContainText("Non connecté");
      await tile.click();
      const row = page.locator("#accounts .account");
      await expect(row).toContainText("Microsoft · moi@exemple.fr (à reconnecter pour le courrier)");
      await expect(row.getByRole("link", { name: "Reconnecter" })).toHaveAttribute("href", "/oauth/microsoft/start");
    } finally {
      fs.rmSync(path.join(server.dataDir, "accounts.json"), { force: true });
    }
  });

  test("avec Mail.Read : rien à reconnecter", async ({ page, server }) => {
    link(server, { mail: true });
    try {
      await page.goto("/");
      await expect(page.locator(".cx", { hasText: "Microsoft" })).toContainText("Connecté");
      const row = page.locator("#accounts .account");
      await expect(row).toContainText("Microsoft · moi@exemple.fr");
      await expect(row).not.toContainText("à reconnecter");
      await expect(row.getByRole("link", { name: "Reconnecter" })).toHaveCount(0);
    } finally {
      fs.rmSync(path.join(server.dataDir, "accounts.json"), { force: true });
    }
  });
});
