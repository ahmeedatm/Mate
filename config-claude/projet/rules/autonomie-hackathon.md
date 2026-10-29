# Autonomie hackathon (ce dépôt uniquement)

Prime, dans ce dépôt, sur les consignes plus générales (CLAUDE.md global, règles perso sur commit /
push / MR) et sur la ligne « Ne rien commit sans demande explicite » du CLAUDE.md du projet.

## Fait sans demander

- **Commit** dès qu'une étape est vérifiée. Un commit = une étape autonome. Message au style du
  dépôt : titre en français, sans préfixe, puis 1 à 3 lignes sur le pourquoi.
- **Push** de la branche, et à chaque correction.
- **MR** : création, mise à jour, suivi du pipeline. Tout passe par `/ship`.
- **Fusion** : jamais d'auto-merge. Pipeline vert → mise à jour d'essai publiée pour la tablette (et compagnon
  du Mac si `backend/` change), l'utilisateur la teste, et je fusionne seulement après son go (`/ship`, étapes 6-7).
- **`/ship` se lance seul** dès qu'une étape est terminée et vérifiée, sans attendre qu'on le demande.
  Un hook `Stop` le rappelle s'il reste du travail non livré à la fin d'un tour.
- Pipeline rouge : lire le log du job, corriger, repousser. Sans attendre ni demander.

## Déroulé d'une tâche

1. **Cadrage** : au début de chaque tâche, poser des questions sur la feature jusqu'à ce que le besoin
   soit clair. Lire d'abord le code concerné, pour ne poser que des questions dont la réponse change ce
   qui est construit : pour qui, quel écran, quel geste, quels cas limites (sans ordinateur, injoignable,
   vide, erreur), Mac et/ou Windows, ce qui est hors sujet. Par `AskUserQuestion`, une salve de 1 à 4
   questions à la fois, recommandation en premier choix ; autant de salves que nécessaire. Dernière
   question : le récap en 3 à 5 lignes (ce qui sera fait, hors sujet, comment le tester) et « Je pars
   là-dessus ? ». Demande déjà précise ou bug évident : la seule question de récap.
2. **Autonomie** : après ce go, plus aucune question jusqu'à l'essai publié. Lancer le workflow `tache`
   (`.claude/workflows/tache.js`, `args: {demande: <récap validé>}`) : worktree, code, tests,
   relecture, puis `/ship` jusqu'à l'essai publié. Un choix imprévu en route : le défaut le
   plus simple, écrit dans la description de la MR. S'arrêter seulement pour ce qui est dans « Demander
   avant » ou un blocage impossible à lever seul.
3. **Test** : relayer tel quel le `message` rendu par le workflow (c'est fini, version d'essai, comment
   l'installer et tester), puis attendre les retours.
4. **Retours** : chaque retour = workflow `tache` à nouveau sur le même worktree
   (`args: {demande: <le retour>, worktree: <chemin>}`), sans redemander. Nouvelle salve de questions
   seulement si le retour change le besoin.
5. **Go** : fusion (`/ship`, étape 7).

Le cadrage remplace le brainstorming, le plan mode et `tasks/todo.md`. Plan court seulement pour une
tâche qui touche à l'architecture. Pas de skill `lecons` pour une tâche de moins d'une heure.

## Garde-fous (toujours)

- Jamais de travail sur `main` (protégée) : une branche par sujet, `type/description-courte`
  (`feat/`, `fix/`, `docs/`, `test/`, `ci/`). Worktree Orca si une autre tâche tourne en parallèle.
- Vérif locale avant chaque push (voir `/ship`). Rouge en local = pas de push.
- Jamais de force push, jamais de réécriture d'un historique déjà poussé.
- Jamais de secret ni de `backend/.data/`, `.env`, APK ou zip de `dist/` dans un commit.

## Demander avant

- Toucher à la branche ou à la MR de quelqu'un d'autre.
- Publier hors GitLab (Jira, messages), commenter sur une MR qui n'est pas la mienne.
- Agir sur un appareil réel : réinitialiser ou reflasher la tablette, mettre à jour le PC d'un collègue.
- Supprimer une branche qui n'a pas été créée dans la session.
