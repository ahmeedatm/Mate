#!/usr/bin/env bash
# Attend qu'une commande de vérification réussisse, avec un délai maximum.
#
#   wait-until.sh [--timeout 1800] [--interval 30] [--max-errors 3] -- <commande>
#
# La commande de vérification décide de l'état par son code de sortie :
#   0  terminé       -> on affiche sa sortie et on sort en 0
#   1  pas encore    -> on attend --interval puis on recommence
#   2+ erreur        -> après --max-errors erreurs d'affilée, on sort en 2 avec l'erreur
# Délai dépassé -> sortie 124 avec la dernière sortie vue.
# Chaque changement de sortie est affiché (utile sous Monitor : un événement par changement).

set -u
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

timeout=1800 interval=30 max_errors=3
while [ $# -gt 0 ]; do
  case "$1" in
    --timeout) timeout=$2; shift 2 ;;
    --interval) interval=$2; shift 2 ;;
    --max-errors) max_errors=$2; shift 2 ;;
    --) shift; break ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { echo "usage: wait-until.sh [--timeout s] [--interval s] [--max-errors n] -- <commande>" >&2; exit 64; }

deadline=$((SECONDS + timeout)) errors=0 prev=""
while :; do
  out=$("$@" 2>&1); rc=$?
  if [ "$out" != "$prev" ] && [ -n "$out" ]; then echo "$out"; prev=$out; fi
  case $rc in
    0) exit 0 ;;
    1) errors=0 ;;
    *) errors=$((errors + 1))
       if [ "$errors" -ge "$max_errors" ]; then
         echo "ERREUR: la vérification échoue $errors fois d'affilée (code $rc) : $out" >&2
         exit 2
       fi ;;
  esac
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "TIMEOUT après ${timeout}s, dernier état : ${out:-vide}" >&2
    exit 124
  fi
  sleep "$interval"
done
