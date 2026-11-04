#!/bin/bash
# Compile le lanceur d'Mate sur Mac (portable/mac/launcher/Launcher.swift, binaire universel arm64 + x86_64) :
#   portable/mac/launcher/Mate           exécutable de Mate.app (package.ts), sans signature de bundle : une signature de
#                                           bundle serait invalide dans l'appli, dont l'Info.plist change à chaque version
#   portable/mac/agent/Mate.app          le même binaire dans l'agent figé, signé ad hoc en bundle
# Résultat gardé dans le dépôt : le job CI package-mac tourne sous Linux. `npm run build:launcher` après toute modification du source, de l'Info.plist de l'agent
# ou de assets/compagnon.icns. ATTENTION : l'empreinte de l'agent change, tous les Mac redonnent leurs autorisations.
# .source-sha256 note l'empreinte de ces trois fichiers ; test/launcher-agent.test.ts la compare.
set -euo pipefail
cd "$(dirname "$0")/.."

mac=portable/mac
source=$mac/launcher/Launcher.swift
agent=$mac/agent/Mate.app

mkdir -p .cache "$agent/Contents/MacOS" "$agent/Contents/Resources"
swiftc -O -target arm64-apple-macos11 -o .cache/Launcher-arm64 "$source"
swiftc -O -target x86_64-apple-macos11 -o .cache/Launcher-x86_64 "$source"
lipo -create -output $mac/launcher/Mate .cache/Launcher-arm64 .cache/Launcher-x86_64
cp $mac/launcher/Mate "$agent/Contents/MacOS/Mate"
cp assets/compagnon.icns "$agent/Contents/Resources/compagnon.icns"
rm -rf "$agent/Contents/_CodeSignature"
codesign --force --sign - "$agent"
cat "$source" "$agent/Contents/Info.plist" assets/compagnon.icns | shasum -a 256 | cut -d' ' -f1 > $mac/agent/.source-sha256
echo "Agent : $(codesign -dvvv "$agent" 2>&1 | sed -n 's/^CDHash=//p')"
