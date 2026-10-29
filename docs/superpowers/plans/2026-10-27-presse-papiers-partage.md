# Presse-papiers partagé — plan de développement

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** faire passer du texte et des photos entre le PC et la tablette par le presse-papiers : photo et texte de la
tablette vers le presse-papiers du PC, texte du presse-papiers du PC affiché sur la tablette, contenus sensibles ignorés.

**Architecture :** le compagnon expose `GET/POST /system/clipboard` et `POST /system/clipboard/image`
(`routes/system.ts` → `os.ts` → pilote Windows / JXA Mac). La tablette ajoute une API, un écran « Presse-papiers »
dans « Depuis l'ordi », un bouton dans la Caméra et une commande vocale.

**Tech Stack :** Node 22 / TypeScript / Fastify, `node:test`, c8 ; C# .NET Framework 4.8 (`UnowhyHelper.exe`) ; JXA
(macOS) ; Kotlin, vues en code, JUnit 4 + org.json.

**Spec :** [docs/superpowers/specs/2026-10-27-presse-papiers-partage-design.md](../specs/2026-10-27-presse-papiers-partage-design.md)

## Global Constraints

- Dépôt `C:\Users\BrunoALHOMSI\unowhy-companion`, branche locale `feat/presse-papiers`, jamais poussée.
- **Aucun commit, aucun `git add`/`git stash`** par les agents : le contrôleur prend des instantanés ; les commits
  attendent l'accord de Bruno (CLAUDE.md du projet).
- Contrat HTTP (identique côté compagnon et tablette) :
  - `GET /system/clipboard` → `{ "kind": "text", "text": string, "truncated"?: true } | { "kind": "image" } |
    { "kind": "empty" } | { "kind": "protected" } | { "kind": "other" }` ; texte renvoyé au plus 100 000 caractères
    (au-delà : coupé + `truncated: true`).
  - `POST /system/clipboard` `{ "text": string }` (1 à 100 000 caractères) → `{ "ok": true }`.
  - `POST /system/clipboard/image` `{ "path": string }` (relatif au dossier partagé, `.jpg`/`.jpeg`/`.png`) →
    `{ "ok": true }` ; 400 autre extension, 404 fichier absent, hors dossier refusé par `resolveDoc`.
  - Hors Mac/Windows : 501 (hook existant de `server.ts` sur `/system`).
- Priorité de lecture : marqueur sensible → `protected` (même pour une image) ; sinon texte → `text` ; sinon image →
  `image` ; vide → `empty` ; reste (liste de fichiers…) → `other`.
  - Windows : formats `ExcludeClipboardContentFromMonitorProcessing`, `Clipboard Viewer Ignore`,
    `CanIncludeInClipboardHistory` = 0 (DWORD). Mac : types `org.nspasteboard.ConcealedType`,
    `org.nspasteboard.TransientType`, `com.agilebits.onepassword`.
- **Le contenu n'apparaît jamais dans un journal** (ni corps, ni message d'erreur).
- Pas de lecture en boucle : la tablette lit à l'ouverture de l'écran et sur « Actualiser » seulement.
- Tablette : affichage coupé à 10 000 caractères (« … »), « Copier sur la tablette » copie tout le texte reçu ; lien =
  texte entier qui est une seule URL `http://` ou `https://` (espaces autour ignorés).
- Voix : « copie sur l'ordi … » et « copie ça sur l'ordi … » ; « copie » seul non pris ; sans texte → « Dis ce que je
  dois copier ».
- Textes de l'UI en français (tutoiement, phrases courtes, sans « ! ») dans `strings.xml` ; DA Cadran (gris chaud,
  Outfit, pilules ≥ 44 dp, pas de titre en couleur, rouge réservé au « maintenant ») ; erreurs via `errorText(e)` ;
  saisie via `showForTyping` ; pas de nouvelle dépendance.
- Gradle sous Windows : `export TEMP='C:\gtmp' TMP='C:\gtmp' JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`.
- Backend : 8 tests échouent déjà sur ce PC Windows (deck-api ×5, sanity mac « garde /docs/open », store « mode
  demandé ») — aucun nouvel échec ; prettier vérifié fichier par fichier avec `--end-of-line auto` ; `npm run coverage`
  ne doit pas baisser par rapport à `main` (78,83 % lignes sur ce PC).

## Review Focus

1. **Mot de passe** dans le presse-papiers (marqueur sensible) → `protected`, le texte n'est jamais lu ni renvoyé →
   test faux système + vérification manuelle (Task 2).
2. **Presse-papiers verrouillé** par une autre appli (Windows : `OpenClipboard` échoue) → erreur lisible, pas de
   plantage du pilote ; reprises comme `SetClipboard` (Task 2).
3. **Chemin d'image hors du dossier** (`../`, absolu) ou extension non image → refusé (Task 1).
4. **Texte énorme** (copie d'un document entier) → réponse coupée à 100 000 avec `truncated`, affichage coupé à 10 000,
   l'UI reste fluide (Tasks 1, 4).
5. **Écran quitté pendant une requête** / compagnon coupé → pas de plantage, message `errorText` (Task 5).

---

### Task 1 : compagnon — routes, `os.ts`, Mac, faux système, tests, sanity

**Files :** Modify `backend/src/os.ts`, `backend/src/routes/system.ts`, `backend/src/win/index.ts`,
`backend/src/mac/system.ts` (ou nouveau `backend/src/mac/clipboard.ts`), `backend/test/fake-os.ts`,
`backend/qa/sanity.ts` ; Create `backend/test/clipboard-api.test.ts`.

**Interfaces (produites) :**
- `export type ClipboardContent = { kind: "text"; text: string; truncated?: true } | { kind: "image" } | { kind: "empty" } | { kind: "protected" } | { kind: "other" }` (dans `src/screens.ts`, types partagés).
- `os.ts` : `getClipboard(): Promise<ClipboardContent>`, `setClipboardText(text: string): Promise<void>`,
  `setClipboardImage(fullPath: string): Promise<void>` (501 via `unsupported()` hors Mac/Windows).
- `win/index.ts` : `getClipboard()` = `call<ClipboardContent>("clipget")`, `setClipboardText(text)` =
  `call("clipset", { text })`, `setClipboardImage(path)` = `call("clipimage", { path })` (opérations du pilote : Task 2).
- Mac : JXA `NSPasteboard.generalPasteboard` — types sensibles d'abord, puis `stringForType(NSPasteboardTypeString)`,
  puis présence d'une image (`public.png`/`public.tiff`) ; écriture texte par `pbcopy` (existe dans `mac/keys.ts`, à
  exporter ou dupliquer proprement) ; image par JXA (`NSImage` depuis le fichier → `writeObjects`).
- Faux système : `FakeState.clipboard?: ClipboardContent`, `getClipboard()` → `read().clipboard ?? { kind: "empty" }`,
  `setClipboardText` / `setClipboardImage` notés par `record(...)` sans écrire le contenu dans le journal du faux
  système au-delà de ce que font les autres actions.
- Coupe à 100 000 caractères faite dans la route (pas dans chaque OS).

- [ ] **Step 1 : tests qui échouent** (`clipboard-api.test.ts`, `startServer()` comme `api.test.ts`) :
  GET renvoie `{kind:"empty"}` par défaut ; GET renvoie le `FakeState.clipboard` (text / image / protected / other) ;
  texte de 100 001 caractères → 100 000 + `truncated: true` ; POST texte → `record` `setClipboardText` ; POST texte vide
  ou > 100 000 → 400 ; POST image `Photos tablette/x.jpg` existant → `setClipboardImage` avec le chemin complet ; `.txt`
  → 400 ; absent → 404 ; `../x.jpg` → refusé ; sans jeton → 401 ; os « other » → 501.
- [ ] **Step 2 : les voir échouer** — `cd backend && node --import tsx --test test/clipboard-api.test.ts`.
- [ ] **Step 3 : implémenter** (routes avec `logLevel: "warn"` sur le GET ; aucun `req.log` du contenu).
- [ ] **Step 4 : contrôle sanity « presse-papiers »** : non piloté → 501 ; sinon lire ; si la lecture échoue
  (session sans bureau) → `Skip` ; si l'ancien contenu est `text` ou `empty` : écrire `"sanity-presse-papiers"`,
  relire, vérifier, puis remettre l'ancien texte (ou vider) ; sinon seulement la lecture.
- [ ] **Step 5 : vérifier** — `npm test` (8 échecs connus seulement), `npm run coverage`, `npm run typecheck`, prettier
  sur les fichiers touchés.

### Task 2 : pilote Windows — `clipget`, `clipset`, `clipimage`

**Files :** Create `backend/src/win/helper/Native.Clipboard.cs` ; Modify `backend/src/win/helper/Native.cs`
(`Dispatch`) ; Regenerate `backend/src/win/UnowhyHelper.exe`.

**Interfaces :** réponses exactement au contrat ; `clipget` vérifie d'abord les formats sensibles
(`RegisterClipboardFormat` + `IsClipboardFormatAvailable`, valeur DWORD de `CanIncludeInClipboardHistory`), puis texte
Unicode, puis image (`ContainsImage`/DIB), puis vide/autre ; `clipset` réutilise `SetClipboard` (reprises) de
`Native.Keys.cs` ; `clipimage` charge le fichier (`Image.FromFile` dans un `using`, copie en `Bitmap` pour libérer le
fichier) puis `Clipboard.SetImage` avec reprises. Presse-papiers verrouillé → exception au message neutre (sans
contenu), le pilote continue.

- [ ] **Step 1 : implémenter.**
- [ ] **Step 2 : compiler** — `cd backend && npm run build:helper` (SDK local `%USERPROFILE%\.dotnet-companion`),
  0 avertissement nouveau. Le service compagnon de Bruno tourne depuis ce dossier : `npm run service:stop`, compiler,
  `npm run service:start` ; le laisser démarré.
- [ ] **Step 3 : vérifier sur ce PC** (jeton dans `backend/.data/identity.json`) : copier un texte (PowerShell
  `Set-Clipboard`) → GET le renvoie ; POST texte → `Get-Clipboard` le rend ; POST image d'un JPEG du dossier partagé →
  `Get-Clipboard -Format Image` non nul (ou collage dans Paint) ; poser le format `ExcludeClipboardContentFromMonitorProcessing`
  (petit script C#/PowerShell de test, hors dépôt) → GET `protected` ; copier un fichier dans l'Explorateur → `other`.
  Windows Defender : aucune détection.

### Task 3 : tablette — API et logique pure

**Files :** Modify `app/src/main/kotlin/com/unowhy/unomate/net/SystemApi.kt` ; Create
`app/src/main/kotlin/com/unowhy/unomate/ui/ClipboardModel.kt` ; Modify
`app/src/main/kotlin/com/unowhy/unomate/voice/VoiceCommands.kt` ; Tests `app/src/test/.../net/ClipboardApiTest.kt`,
`app/src/test/.../ui/ClipboardModelTest.kt`, `app/src/test/.../voice/` (fichier de test existant des commandes vocales).

**Interfaces (produites) :**
- `sealed class ClipContent { data class Text(val text: String, val truncated: Boolean) ; object Image ; object Empty ; object Protected ; object Other }` et `fun parseClip(json: String): ClipContent` (kind inconnu → `Other`).
- `SystemApi.clipboard(): ClipContent`, `SystemApi.copyToComputer(text: String)`, `SystemApi.copyImageToComputer(path: String)`.
- `ClipboardModel.kt` : `fun displayText(text: String, max: Int = 10_000): String` (coupe + « … »), `fun linkOf(text: String): String?` (URL http/https seule, sinon null).
- `VoiceCommand.CopyToComputer(val text: String)` ; « copie sur l'ordi bonjour » → `CopyToComputer("bonjour")` (texte
  d'origine, casse et accents gardés, comme « écris ») ; « copie ça sur l'ordi … » idem ; « copie sur l'ordi » seul →
  `CopyToComputer("")` ; « copie le fichier » → pas `CopyToComputer`.

- [ ] **Step 1 : tests qui échouent** (les cas ci-dessus + chaque `kind`, `truncated`, coupe à 10 000, liens :
  `" https://x.fr/a "` → `https://x.fr/a`, `"voir https://x.fr"` → null, `"ftp://x"` → null).
- [ ] **Step 2 : voir échouer** — `./gradlew testDebugUnitTest --tests "*Clip*" --tests "*Voice*"`.
- [ ] **Step 3 : implémenter.** **Step 4 :** relancer + `./gradlew ktlintCheck`.

### Task 4 : tablette — écran « Presse-papiers », Caméra, voix

**Files :** Modify `ui/FromComputerFragment.kt` + `res/layout/fragment_from_computer.xml` (3e choix
« Presse-papiers », même présentation que les deux autres) ; Create `ui/ClipboardView.kt` (ou fragment de vue
équivalent) ; Modify `MainActivity.kt` (`fun openUrl(url: String)` : ouvre l'URL dans le panneau document, même
WebView que « Page de l'ordi ») ; Modify `ui/CameraFragment.kt` + son layout (bouton « Copier sur l'ordi » à côté de
« Photo », actif après une photo envoyée, lié à la dernière, caché en quittant le mode) ; Modify
`voice/VoiceControl.kt` (exécute `CopyToComputer` : vide → dire « Dis ce que je dois copier », sinon envoyer puis
dire « Copié sur l'ordi ») ; Modify `res/values/strings.xml` ; icône Material si besoin (`ic_*` existant de préférence).

**Comportement :**
- Écran : tuile « Sur l'ordi » (contenu selon `ClipContent` : texte via `displayText`, « Une image est copiée sur
  l'ordi », « Rien n'est copié sur l'ordi », « Contenu protégé (mot de passe ?) : il n'est pas affiché », « Ce contenu
  ne s'affiche pas ici » ; boutons Actualiser, Copier sur la tablette (texte seulement), Ouvrir (si `linkOf`)) et tuile
  « Vers l'ordi » (Écrire… → boîte `showForTyping` avec « Copier sur l'ordi » ; Dicter → reconnaissance Android,
  résultat proposé dans la même boîte avant envoi) ; confirmation « Copié sur l'ordi ».
- Lecture à l'affichage de l'écran et sur Actualiser seulement ; 501 → « Non disponible sur cet ordinateur ».
- Style Cadran : tuiles `asCard`/habillage existant, Outfit, pilules.

- [ ] **Step 1 : implémenter.** **Step 2 : vérifier** — `./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug`.

### Task 5 : vérification sur la tablette et documentation (contrôleur, avec Bruno)

- [ ] Installer (`adb install -r -d`), vérifier les 5 critères de réussite de la spec (photo collée dans Paint/Teams,
  texte et voix, PC → tablette et lien, contenu protégé, Cadran jour/nuit), journal du compagnon sans contenu.
- [ ] `docs/etat-du-projet.md` : puce « Fait mais pas encore testé » (ce qui est vérifié, Mac à vérifier).
