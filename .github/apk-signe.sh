#!/usr/bin/env bash
# Signature à part d'un APK release construit sans clé (essai d'une PR ou tag vX.Y.Z), en deux temps :
#
#   .github/apk-signe.sh verifier <apk> <version> <paquet> <libellé> <aligné> <entrée.json>
#       sans secret : lit l'APK (aapt2 dump badging), refuse un autre paquet, libellé, version ou versionCode que ceux
#       attendus, ou un APK debuggable ou testOnly ; l'aligne (zipalign) dans <aligné> et écrit l'entrée
#       {package, label, versionCode, versionName, file} lue par scripts/updates-publish.mjs.
#   .github/apk-signe.sh signer <aligné> <sortie.apk>
#       avec KEYSTORE_BASE64 et KS_PASS (environnement « signature ») : signe (apksigner) et vérifie l'empreinte du
#       certificat. Seule étape qui voit la clé ; fichier de clé effacé même en cas d'échec.
#
# L'APK reçu est une donnée : rien n'en est exécuté. Voir docs/developpement.md, sections Release et Déployer un essai.
set -euo pipefail

# Empreinte SHA-256 du certificat de la clé de release (alias companion, créée le 01/11/2026).
SIGNING_CERT_SHA256=2c9cd9872f2ee1875b55f16e2da1f38c439480b10ef8389143d644f76b6b18f1

BT=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
die() { echo "::error::$*"; exit 1; }

# versionCode d'une version, comme app/build.gradle.kts : (X × 10000 + Y × 100 + Z) × 1000, + 1 + N mod 999 pour un essai.
expected_code() {
  [[ "$1" =~ ^([0-9]+)\.([0-9]{1,2})\.([0-9]{1,2})(-essai\.([0-9]+))?$ ]] || die "Version « $1 » : attendu X.Y.Z ou X.Y.Z-essai.N"
  local code=$(((10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[3]}) * 1000))
  [ -z "${BASH_REMATCH[5]}" ] || code=$((code + 1 + 10#${BASH_REMATCH[5]} % 999))
  echo $((code < 1 ? 1 : code))
}

verifier() {
  local apk=$1 version=$2 want_pkg=$3 want_label=$4 aligned=$5 entry=$6 badging pkg code name label
  badging=$("$BT/aapt2" dump badging "$apk")
  pkg=$(sed -n "1s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")
  code=$(sed -n "1s/.*versionCode='\([0-9]*\)'.*/\1/p" <<< "$badging")
  name=$(sed -n "1s/.*versionName='\([^']*\)'.*/\1/p" <<< "$badging")
  label=$(sed -n "s/^application-label:'\(.*\)'$/\1/p" <<< "$badging")
  [ "$pkg" = "$want_pkg" ] || die "Paquet « $pkg », attendu $want_pkg"
  [ "$label" = "$want_label" ] || die "Libellé « $label », attendu « $want_label »"
  [ "$name" = "$version" ] || die "Version « $name », attendu $version"
  [ "$code" = "$(expected_code "$version")" ] || die "versionCode $code, attendu $(expected_code "$version")"
  ! grep -qE "^application-debuggable|testOnly='-1'" <<< "$badging" || die "APK debuggable ou testOnly refusé"
  "$BT/zipalign" -f 4 "$apk" "$aligned"
  printf '{"package":"%s","label":"%s","versionCode":%s,"versionName":"%s","file":"companion-%s.apk"}\n' \
    "$pkg" "$label" "$code" "$version" "$version" > "$entry"
  cat "$entry"
}

signer() {
  local aligned=$1 out=$2
  : "${KEYSTORE_BASE64:?clé absente (environnement signature)}" "${KS_PASS:?mot de passe absent}"
  umask 077
  KEYSTORE=$(mktemp)
  trap 'rm -f "$KEYSTORE"' EXIT
  printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$KEYSTORE"
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias companion --ks-pass env:KS_PASS --key-pass env:KS_PASS \
    --out "$out" "$aligned"
  local certs
  certs=$("$BT/apksigner" verify --print-certs "$out")
  echo "$certs"
  grep -qx "Signer #1 certificate SHA-256 digest: $SIGNING_CERT_SHA256" <<< "$certs" || die "Certificat inattendu"
}

case "${1:-}" in
  verifier) [ $# -eq 7 ] || die "verifier : 6 arguments attendus"; shift; verifier "$@" ;;
  signer) [ $# -eq 3 ] || die "signer : 2 arguments attendus"; shift; signer "$@" ;;
  *) die "Usage : apk-signe.sh verifier|signer …" ;;
esac
