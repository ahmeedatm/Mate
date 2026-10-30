# Copie GitHub

Dépôt privé `ahmeedatm/Mate` : copie du projet GitLab `efreifer/unowhy-companion`, branche par défaut `master` (= `main` de GitLab). Le dossier `.github/` n'existe que sur GitHub. Tout y arrive par PR, y compris `main` de GitLab : `git push https://github.com/ahmeedatm/Mate.git origin/main:refs/heads/sync/gitlab`, puis une PR de `sync/gitlab` vers `master`.

CI : `.github/workflows/ci.yml`, mêmes jobs `backend`, `e2e` et `android` que le stage test de `.gitlab-ci.yml`, mêmes images, même ciblage par zone en PR (toucher `.github/workflows/` relance tout), plus `site` (image construite, jamais poussée). Le job `ci` rassemble les résultats : vert si chaque job a réussi ou n'avait pas à tourner. Pas de release, d'essais, de purge ni de `helper-windows` : ils dépendent du registre de paquets GitLab, de la clé de signature, de Harbor et du runner Windows.

Règle « master » (Settings > Rules) : PR obligatoire avec une approbation, discussions résolues, contrôles `ci` et `nommage` verts, ni force push ni suppression ; personne ne la contourne.

Nommage, pour qu'une PR se lise sans connaître le code : titre = ce qui change pour l'utilisateur, en français (« Ajout d'un bouton Supprimer sur les tâches », pas « feat: add delete button ») ; branche `feat|fix|docs|test|ci/` puis ce qui change, en minuscules et tirets (`feat/bouton-supprimer-tache`), ou `sync/gitlab`. Le contrôle `nommage` (`.github/workflows/nommage.yml`, `.github/nommage.sh`) refuse une branche hors format et un titre à préfixe technique (`feat:`, `fix(x):`, `Docs :`) ou à `[balise]` ; il est relancé quand on modifie le titre. Description : modèle `.github/pull_request_template.md` (ce qui change, comment tester, testé sur), sans liste de fichiers.
