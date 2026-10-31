import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { fakePr } from "./fake-github.ts";

// Dossier de données vide : aucun compte connecté.
process.env.DATA_DIR = await fs.mkdtemp(path.join(os.tmpdir(), "companion-github-"));
const { scopeCanWrite } = await import("../src/github-auth.ts");
const { checksOf, prStatusLabel, runStatusOf, toPr } = await import("../src/github.ts");

describe("GitHub : traductions", () => {
  it("contrôles du dernier commit → pipeline", () => {
    assert.deepEqual(["SUCCESS", "FAILURE", "ERROR", "PENDING", "EXPECTED", null, "AUTRE"].map(checksOf), [
      "success",
      "failed",
      "failed",
      "running",
      "running",
      "none",
      "none",
    ]);
  });

  it("état de fusion : « bloquée » précisée par la relecture puis les contrôles", () => {
    assert.equal(prStatusLabel("CLEAN", null, "success"), "Prête à fusionner");
    assert.equal(prStatusLabel("DIRTY", null, "success"), "Conflit à résoudre");
    assert.equal(prStatusLabel("BLOCKED", "CHANGES_REQUESTED", "success"), "Modifications demandées");
    assert.equal(prStatusLabel("BLOCKED", "REVIEW_REQUIRED", "running"), "Approbation manquante");
    assert.equal(prStatusLabel("BLOCKED", "APPROVED", "running"), "Contrôles en cours");
    assert.equal(prStatusLabel("BLOCKED", "APPROVED", "failed"), "Contrôles à faire passer");
    assert.equal(prStatusLabel("BLOCKED", null, "success"), "Bloquée par les règles de la branche");
    assert.equal(prStatusLabel("NOUVEAU", null, "none"), "Pas fusionnable pour l'instant");
  });

  it("brouillon : jamais fusionnable, quel que soit l'état de GitHub", () => {
    const pr = toPr(fakePr({ isDraft: true }) as Parameters<typeof toPr>[0]);
    assert.deepEqual([pr.draft, pr.mergeable, pr.statusLabel], [true, false, "Brouillon"]);
    assert.equal(toPr(fakePr({ mergeStateStatus: "UNSTABLE" }) as Parameters<typeof toPr>[0]).mergeable, true);
  });

  it("run GitHub Actions : pas terminé = en cours, sinon sa conclusion", () => {
    assert.equal(runStatusOf("queued", null), "running");
    assert.equal(runStatusOf("in_progress", null), "running");
    assert.equal(runStatusOf("completed", "success"), "success");
    assert.equal(runStatusOf("completed", "timed_out"), "failed");
    assert.equal(runStatusOf("completed", "cancelled"), "canceled");
    assert.equal(runStatusOf("completed", "skipped"), "none");
  });

  it("portée en écriture : repo ou public_repo, séparées par des virgules", () => {
    assert.equal(scopeCanWrite("repo"), true);
    assert.equal(scopeCanWrite("read:user,public_repo"), true);
    assert.equal(scopeCanWrite("read:user, repo:status"), false);
    assert.equal(scopeCanWrite(""), false);
  });
});
