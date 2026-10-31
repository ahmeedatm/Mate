---
name: nouvelle-feature
description: Point d'entrée de toute demande de fonctionnalité ou de correction dans Mate, à charger avant d'écrire du code ou un plan ("ajoute…", "je veux que…", "corrige…", "fais en sorte que…", "il y a un bug sur…"). Une demande = une branche = un sujet : reformule, crée la branche et la PR brouillon, impose le test rouge d'abord, la relecture par les agents, puis la PR prête. Refuse d'empiler un 2e sujet.
---

# nouvelle-feature

Le circuit d'une demande, de la phrase à la PR prête. Les règles du projet vivent dans `CLAUDE.md`
(§ Règles de travail, § Conventions du code) : ici on enchaîne les étapes, on ne les recopie pas.
Demander une feature par cette skill vaut accord pour sa branche, ses commits et sa PR brouillon
(`CLAUDE.md`, § Règles de travail). Rien d'autre n'est poussé ni fusionné sans demande.

Commandes valables sur Mac et Windows (Git Bash pour les scripts `.sh`). Si `gh` manque, ou n'est
pas connecté (`gh auth status`), les étapes GitHub deviennent : afficher le titre et le corps de PR
à coller sur GitHub, et le dire.

## 1. Cadrer la demande

Lire d'abord le code concerné (`docs/architecture.md`, § Détail par fonction, pour la zone). Puis
écrire, en deux lignes :

- **La phrase** : ce qui change pour la personne qui utilise la tablette ou l'ordinateur.
- **N'inclut pas** : ce que la demande ne couvre pas, y compris le voisin tentant (refonte,
  renommage, autre écran, autre bug vu en passant).

Une info manque et change le résultat : une seule question, la réponse recommandée en premier.
Faire valider la phrase et l'exclusion avant d'aller plus loin. Ce sont la référence du
`gardien-perimetre` : ne les change plus sans l'accord de la personne.

## 2. Un seul sujet par branche

`git branch --show-current`, `git status --short`, `gh pr list --head <branche>`.

La branche courante n'est pas `master` et porte déjà un autre sujet (PR ouverte au titre différent,
commits hors sujet par rapport à `git log origin/master..HEAD --oneline`) : **ne pas empiler**.
Dire pourquoi en une phrase, proposer une nouvelle branche à partir de `origin/master`, et ne pas
continuer sur l'ancienne. Des changements non commités d'un autre sujet : les signaler, ne pas
les embarquer.

## 3. Branche

`git fetch origin`, puis `git switch -c <type>/<sujet> origin/master`. Type : `feat`, `fix`, `docs`,
`test` ou `ci` ; sujet en minuscules et tirets. Titre de PR : en français, ce qui change pour
l'utilisateur, sans préfixe technique. Vérifier les deux :

`bash .github/nommage.sh "<branche>" "<titre>"` (sortie 0 attendue, sinon corriger).

Le contrôle de la branche est fiable partout ; celui du titre demande bash 4 ou plus (Git Bash sous
Windows convient ; avec le bash 3.2 du Mac, une ligne « bad substitution » veut dire que le titre
n'a pas été contrôlé). Dans ce cas, vérifier le titre à la main : en français, sans préfixe
`feat:` / `fix(x):` ni `[balise]` ; le contrôle `nommage` de la CI le confirmera.

## 4. Commit d'ouverture et PR brouillon

```
git commit --allow-empty -m "Début : <la phrase>"
git push -u origin <branche>
gh pr create --draft --base master --title "<titre>" --body-file <fichier>
```

Le corps suit `.github/pull_request_template.md` (sections « Ce qui change », « Comment tester »,
« Testé sur »). Écrit dans un fichier temporaire hors du dépôt. « Ce qui change » contient la phrase
puis `N'inclut pas : …`. Les autres sections restent à remplir à l'étape 9. Sans `gh` : afficher
ce corps à coller sur GitHub.

## 5. Skill de zone

Charger celle qui correspond au code touché : `feature-android` (`app/`), `feature-backend`
(`backend/`), et `ecran-design` dès qu'un écran ou une page web change. Une feature de bout en
bout (ordre vers la tablette, nouveau réglage) les charge toutes.

## 6. Test rouge d'abord

Écrire le test de la zone, le lancer, le **voir échouer** pour la bonne raison. Seulement ensuite le
code minimal qui le fait passer. Emplacements et commandes : skills de zone. Un correctif de bug
commence par le test qui reproduit le bug. Pas de test possible (écran pur, install) : le dire et
dire ce qui le remplace (capture, essai à la main).

## 7. Format et tests de la zone

Les commandes sont dans la skill de zone. Format avant de pousser, puis les tests de la zone
modifiée. Commits petits, un par étape logique, message en français qui dit ce qui change. Pousser
la branche après chaque commit utile.

## 8. Relecture

Charger la skill `relecture`. Corriger tous les `bloquant`, recommencer jusqu'à zéro. Les
`important` et `mineur` sont présentés à l'humain, qui tranche : ne pas les corriger d'office si
cela sort de la demande validée.

## 9. PR prête

Compléter le corps de la PR : « Comment tester » (gestes et résultat attendu), « Testé sur »
(cocher ce qui a vraiment été essayé, laisser le reste décoché), captures par thème pour un écran
(jamais commitées). `gh` ne sait pas joindre d'images : les glisser dans la description de la PR
sur github.com, ou le demander à l'humain, et le dire dans la ligne `⚠️` si ce n'est pas fait. Mettre à jour avec `gh pr edit <n> --body-file <fichier>`,
puis `gh pr ready <n>`.

Impact équipe : juger si la PR change un comportement par défaut ou une habitude des autres
(réglage par défaut changé, bouton déplacé, fonction retirée). Si oui : `gh pr edit <n> --add-label
impact-equipe`, « Impact équipe : oui » dans le corps avec la raison en une phrase ; le contrôle
`impact-equipe` reste rouge jusqu'à l'approbation d'une autre personne. Un bug corrigé, un widget ou
une option que chacun active s'il veut : « non », pas d'étiquette.

Terminer par une ligne `⚠️` sur ce qui n'a pas été fait ou vérifié.
La fusion reste à la CI verte, et à l'approbation d'une autre personne si la PR porte `impact-equipe`
(`CLAUDE.md`, § Règles de travail).

## Un 2e sujet en cours de route

L'humain demande autre chose pendant la feature, ou Claude voit un problème voisin : **le noter**
(une ligne dans le compte-rendu : « à faire dans une autre branche : … »), ne pas le faire ici.
Il aura sa propre demande, sa branche et sa PR.
