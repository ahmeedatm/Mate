# Travailler sur le projet

Pour reprendre le code sur un autre ordinateur ou le confier à un collègue. Pour comprendre comment le tout fonctionne : [architecture.md](architecture.md). Pour savoir où on en est : [etat-du-projet.md](etat-du-projet.md).

## Récupérer le projet (sans dépôt distant)

Le projet n'est publié sur aucun serveur git. On le transporte avec un **bundle git**, un fichier unique qui contient tout l'historique.

**Créer la sauvegarde** (sur l'ordinateur qui a le projet) :

```bash
scripts/sauvegarde.sh            # dans ~/Desktop ; ou scripts/sauvegarde.sh /chemin/du/dossier
```

Le script crée deux fichiers :

| Fichier | Contenu | À qui |
|---|---|---|
| `unowhy-companion-AAAA-MM-JJ.bundle` | tout le code et l'historique (commits seulement) | à partager avec les collègues |
| `unowhy-companion-donnees-perso-AAAA-MM-JJ.tgz` | `backend/.env`, `backend/.data/` (jeton d'appairage, agendas reliés, tâches, touches…), deck et tâches perso | **à garder pour soi** : il contient des secrets |

Seul ce qui est commité part dans le bundle : il faut commiter avant.

**Récupérer sur un autre ordinateur** :

```bash
git clone unowhy-companion-2026-10-24.bundle unowhy-companion
cd unowhy-companion
tar xzf ../unowhy-companion-donnees-perso-2026-10-24.tgz   # seulement pour retrouver SES données
```

**Mettre à jour une copie existante** avec un bundle plus récent : `git pull ../unowhy-companion-AAAA-MM-JJ.bundle main`.

Plus tard, pour publier : `git remote add origin <url>` puis `git push -u origin main`. L'historique est conservé.

## Ce qui n'est pas dans git (et comment le recréer)

| Fichier | Rôle | Sans lui |
|---|---|---|
| `local.properties` | `sdk.dir=/Users/<toi>/Library/Android/sdk` | le build Android ne trouve pas le SDK |
| `backend/.env` | réglages et secrets (liste dans `backend/.env.example` : `PORT`, `HOST`, `MODE`, `COMPUTER_NAME`, `DOCS_DIR`, `CALENDAR_ICS_URLS`, `ANTHROPIC_API_KEY`, `JIRA_*`…) | valeurs par défaut, sans IA ni Jira |
| `backend/.data/` | données de cet ordinateur : identité et jeton, agendas, tâches, touches, applis, sites | recréé vide : il faut appairer la tablette à nouveau |
| `backend/config/deck.json` | deck de cet ordinateur | créé depuis `deck.example*.json` |
| `backend/config/tasks.json` | tâches quotidiennes | pas de tâche quotidienne |
| `backend/node_modules/` | dépendances | `cd backend && npm install` |
| `shared/` | dossier Partage par défaut | vide |

Ne jamais mettre de clé ou de jeton dans un fichier versionné, ni sur la tablette.

## Installer l'environnement

- **Android** : JDK 17+ (testé avec 21), Android SDK (`compileSdk 36`), adb. AGP 9 avec Kotlin intégré.
- **Ordinateur** : Node 22+.
- macOS : si `ANDROID_HOME` pointe ailleurs (cask platform-tools), le préciser dans les commandes de l'émulateur (voir README).

Puis :

```bash
cd backend && npm install && npm run typecheck
cd .. && ./gradlew assembleDebug
```

## Boucle de développement

**Compagnon** (sur l'ordinateur) : arrêter le service, puis `cd backend && npm run dev` (rechargement à chaque sauvegarde). Pour le relancer en service : `npm run service:start`.

Pour un **compagnon de test** à côté du vrai (autres données, autre port) :

```bash
HOST=127.0.0.1 PORT=3021 DATA_DIR=/tmp/companion-test npx tsx --env-file-if-exists=.env src/server.ts
```

**Appli** :

```bash
./gradlew assembleDebug
adb -s <série> install -r app/build/outputs/apk/debug/app-debug.apk   # un appareil précis
```

`./gradlew installDebug` installe sur **tous** les appareils branchés, tablette comprise. Une tablette en kiosque relance l'appli toute seule après l'installation (`UpdatedReceiver`). Pour installer, il faut que « Autoriser les installations » soit activé (Réglages > appui long sur le titre > code technicien).

**Émulateur** `companion_api29` (Android 10, 1280×800, sans services Google) : l'ordinateur hôte y est `10.0.2.2` (« Saisir l'adresse »). La recherche WiFi ne traverse pas l'émulateur, son DNS peut être en panne (météo) et sa caméra plante. On teste sur la vraie tablette dès que possible : l'émulateur ne reflète pas les performances.

**Captures de la tablette** : `adb exec-out screencap -p > capture.png`. Une capture noire veut dire que l'écran est éteint : l'appli est alors en pause et n'écoute plus l'ordinateur.

## Mettre à jour le compagnon d'un PC Windows à distance

Si le dossier du compagnon du PC est partagé sur le réseau (SMB) et monté sur le Mac :

```bash
rsync -rt --exclude node_modules --exclude .data --exclude .cache --exclude ".env" --exclude ".env.bak*" \
  --exclude config/tasks.json --exclude config/deck.json --exclude .DS_Store --exclude "*.log" backend/ /Volumes/backend/
curl -X POST -H "Authorization: Bearer <jeton du PC>" -H "Content-Type: application/json" -d '{}' http://<ip-du-pc>:3000/companion/restart
```

Le compagnon redémarre seul en ~8 s. Vérifier ensuite une route Windows (`/system/media`) : une erreur de compilation de `native.cs` casse tout le pilotage (HTTP 500), et on ne peut pas compiler le C# sur le Mac. Si `package.json` change, il faut lancer `npm install` sur le PC.

## Fabriquer les paquets clients (sans Node à installer)

Sur le Mac, dans `backend/` : `npm run package` (`scripts/package.ts`). Résultat dans `dist/` à la racine (ignoré par git) :

- `Compagnon-Unowhy-Windows.zip` (~34 Mo) et `Compagnon-Unowhy-Mac.zip` (~76 Mo, node Apple Silicon et Intel) ;
- dans chaque zip, un dossier « Compagnon Unowhy » : l'installeur à double-cliquer, `backend/dist/server.mjs` et `service.mjs` (sources regroupées par esbuild), `backend/config` (sans `deck.json` ni `tasks.json`), `helper.ps1`/`native.cs` et `run-windows.ps1` pour Windows, et le node officiel de nodejs.org (ligne 22, téléchargé une fois dans `backend/.cache/node`, vérifié par SHA-256).

Les installeurs prennent `backend/node/…` s'il existe (sans `npm install`), sinon le Node du système comme avant ; `run-windows.ps1` et `service.ts` lancent `dist/server.mjs` s'il existe, sinon `src/server.ts` par tsx. Commandes du service dans un paquet : `node\node.exe dist\service.mjs status|stop|start|uninstall` (Windows), `node/darwin-arm64/node dist/service.mjs …` (Mac).

Données d'un ordinateur installé par paquet : hors du dossier extrait, dans `%LOCALAPPDATA%\UnowhyCompanion\data` (Windows) ou `~/Library/Application Support/UnowhyCompanion` (Mac) (`src/paths.ts`). Mettre à jour = extraire le nouveau zip n'importe où et relancer son installeur : l'identité est gardée, la tablette retrouve l'ordinateur. Le dossier Partage du paquet est `Documents\Partage tablette` (Documents lu dans le registre sous Windows, OneDrive compris). Un paquet plus ancien gardait `backend/.data` et `shared/` dans le dossier extrait : ils sont recopiés une fois au premier lancement (les originaux restent). Sur Mac, l'autorisation Accessibilité est donnée au node du paquet. Sur Windows, l'installeur ouvre le pare-feu au `node.exe` du paquet (`scripts/firewall-windows.ps1`, règle « Compagnon Unowhy », réseaux privés, accord administrateur seulement si la règle manque ou vise un autre dossier).

Testé sur le Mac (serveur et service regroupés, node embarqué). Pas encore essayé sur un PC Windows.

## Pièges connus

- **C# 5 seulement** dans `native.cs` (`out` est un mot réservé ; pas de déclaration en double : la classe `Native` est unique). WinRT est inaccessible depuis le C# d'Add-Type : on passe par `helper.ps1`, sans accents dans ses chaînes (fichier lu en ANSI).
- **Mac** : le compagnon a besoin de l'Accessibilité (pour `node`, ou pour le terminal qui lance `npm run dev`). Sans elle : raccourcis, fenêtres et macros échouent (bandeau sur le Deck).
- Redémarrer le compagnon actif fait basculer la tablette sur un autre ordinateur qui répond. Il faut ensuite revenir à la main (nom en bas de la barre latérale).
- Le compagnon lancé depuis une session (terminal, éditeur) meurt avec elle. La tablette affiche alors l'ordinateur en rouge et « Aucun événement ».
- Un champ de texte construit dans le code doit être gonflé depuis `item_input.xml` : un `EditText` construit avec un style perd le focus et le curseur.
- Toute saisie dans une boîte de dialogue passe par `showForTyping` : sinon le clavier cache la boîte.

## Avec Claude Code

`CLAUDE.md` à la racine donne à Claude le contexte et les conventions. Il est versionné : il voyage avec le bundle. Les notes de session de Claude, elles, restent sur l'ordinateur où il tourne : ce qui compte est recopié dans [etat-du-projet.md](etat-du-projet.md). Le serveur MCP `tablette` (`.mcp.json`) permet à Claude de piloter la tablette.
