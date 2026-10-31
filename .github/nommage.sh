#!/bin/bash
# Nom de branche et titre de PR (.github/copie-github.md).
# Usage : nommage.sh <branche> <titre>
BRANCHE=$1
TITRE=$2
ERREUR=0

if ! [[ $BRANCHE =~ ^(feat|fix|docs|test|ci)/[a-z0-9]+(-[a-z0-9]+)*$ || $BRANCHE == sync/gitlab ]]; then
  echo "Branche « $BRANCHE » : attendu feat|fix|docs|test|ci/ puis ce qui change, en minuscules et tirets."
  echo "  Ex. : feat/bouton-supprimer-tache, fix/heure-figee-accueil"
  ERREUR=1
fi

if [[ ${TITRE,,} =~ ^[[:space:]]*(\[[^]]*\]|(feat|fix|docs|test|tests|ci|chore|refactor|build|perf|style|wip|hotfix)[[:space:]]*(\([^\)]*\))?!?[[:space:]]*:) ]]; then
  echo "Titre « $TITRE » : pas de préfixe technique ni de [balise]."
  echo "  Écrire ce qui change pour l'utilisateur. Ex. : « Ajout d'un bouton Supprimer sur les tâches »"
  ERREUR=1
fi

[ $ERREUR = 0 ] && echo "Branche et titre conformes."
exit $ERREUR
