# Dépôt GitHub

Dépôt privé `ahmeedatm/Mate`, branche par défaut `master`. Tout y arrive par PR.

CI : `.github/workflows/ci.yml`, jobs `backend`, `e2e` et `android`, ciblés par zone en PR (toucher `.github/workflows/` relance tout). Le job `ci` rassemble les résultats : vert si chaque job a réussi ou n'avait pas à tourner. Pas encore de release, d'essais, de purge ni de `helper-windows` (clé de signature, runner Windows).

Règle « master » (Settings > Rules) : PR obligatoire avec une approbation, celle d'un propriétaire de `.github/CODEOWNERS` si la PR touche `.github/`, discussions résolues, contrôles `ci` et `nommage` verts, fusion en squash seulement, ni force push ni suppression ; seul le rôle admin la contourne, et seulement en fusionnant une PR (pas de push direct).

Dépôt : fusion en squash seulement (commit = titre et description de la PR), branche supprimée après fusion. Actions : jeton des workflows en lecture seule par défaut, actions épinglées par SHA obligatoires (`uses: owner/action@<sha> # vX.Y.Z`).

Nommage, pour qu'une PR se lise sans connaître le code : titre = ce qui change pour l'utilisateur, en français (« Ajout d'un bouton Supprimer sur les tâches », pas « feat: add delete button ») ; branche `feat|fix|docs|test|ci/` puis ce qui change, en minuscules et tirets (`feat/bouton-supprimer-tache`). Le contrôle `nommage` (`.github/workflows/nommage.yml`, `.github/nommage.sh`) refuse une branche hors format et un titre à préfixe technique (`feat:`, `fix(x):`, `Docs :`) ou à `[balise]` ; il est relancé quand on modifie le titre. Description : modèle `.github/pull_request_template.md` (ce qui change, comment tester, testé sur), sans liste de fichiers.
