# Widget « Performances du PC » — plan de développement

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** un widget de l'Accueil de la tablette qui affiche en direct l'utilisation processeur / carte graphique / RAM
du PC, plus la température et le ventilateur de la carte graphique quand elle est dédiée, en trois vues selon sa taille.

**Architecture :** le compagnon expose `GET /system/perf` (`routes/system.ts` → `os.ts`). Processeur et RAM viennent
du module `os` de Node (Mac et Windows), la carte graphique d'une nouvelle opération `perf` du pilote Windows
`UnowhyHelper.exe` (compteurs « GPU Engine » + données de performance WDDM). La tablette ajoute `WidgetType.PERF` et un
`PerfWidget` qui interroge la route toutes les 2 s tant que sa page est à l'écran.

**Tech Stack :** Node 22 + TypeScript (tsx) + Fastify, tests `node:test` ; C# .NET Framework 4.8 (P/Invoke gdi32,
PDH) compilé par le SDK .NET 8 ; Kotlin, vues en code, JUnit 4 + org.json.

**Spec :** [docs/superpowers/specs/2026-10-27-widget-performances-pc-design.md](../specs/2026-10-27-widget-performances-pc-design.md)
(maquettes : [2026-10-27-widget-performances-pc-maquettes.html](../specs/2026-10-27-widget-performances-pc-maquettes.html)).

## Global Constraints

- Dépôt `C:\Users\BrunoALHOMSI\unowhy-companion`, branche locale `feat/widget-perf-pc`, **jamais poussée**.
- **Ne rien commiter sans l'accord explicite de Bruno** (CLAUDE.md du projet). Les étapes « Commit » ne s'exécutent
  qu'avec cet accord ; message conventionnel, en français, ex. `feat(perf): …`.
- Rien à configurer pour l'utilisateur : ni pilote, ni droit administrateur, ni PowerShell à l'exécution.
- Tout accès au système passe par `backend/src/os.ts` ; Windows natif uniquement dans `UnowhyHelper.exe`.
- Valeur inconnue = champ **absent** de la réponse, jamais 0 inventé (sauf `fanRpm: 0` = « Arrêté », Task 1).
- Seuils : utilisation orange ≥ 85 %, rouge ≥ 95 % ; température GPU orange ≥ 80 °C, rouge ≥ 90 °C ; ventilateur jamais coloré.
- Rafraîchissement tablette : 2 000 ms, seulement pendant `setActive(true)` ; historique : 30 mesures (CPU + GPU, pas la RAM).
- Mesure côté compagnon au plus une fois par seconde, partagée entre appels.
- Tailles du widget : `listOf(WidgetSize.M, WidgetSize.L, WidgetSize.W)` (M = taille d'ajout) ; M = chiffres + courbe,
  L = jauges rondes, W = barres.
- Libellés : « CPU », « GPU », « RAM », « Ventilateur », « Arrêté », « tr/min » ; titre « PC · *nom de l'ordinateur* » ;
  catalogue « Performances du PC » / « Processeur, carte graphique et mémoire en direct » ; 501 → « Non disponible sur
  cet ordinateur ». Textes dans `res/values/strings.xml`.
- Couleurs uniquement par attributs de thème : normal `R.attr.c_accent`, orange `R.attr.c_hue_orange`, rouge
  `R.attr.c_hue_red`, textes secondaires `R.attr.c_text_dim`. Jamais de couleur en dur.
- Android : pas de nouvelle dépendance ; erreurs affichées via `errorText(e)` ; vues construites en code comme les
  autres widgets de `HomeWidgets.kt`.
- Gradle sous Windows : `TEMP`/`TMP` sur un chemin court (`C:\gtmp`) et `JAVA_HOME` = JBR d'Android Studio, sinon
  « Unable to establish loopback connection ».

## Review Focus

1. **Premier appel après démarrage** : il n'existe pas encore de mesure CPU précédente ; on attend un pourcentage réel
   (0–100), jamais `NaN` ni 0 forcé → test `getPerf premier appel` (Task 1).
2. **Pilote Windows absent ou planté** (antivirus qui met `UnowhyHelper.exe` en quarantaine, opération `perf` qui lève) :
   `/system/perf` doit répondre 200 avec CPU et RAM, sans `gpu` → test `gpu en échec` (Task 1).
3. **Valeurs aberrantes du pilote** : somme des moteurs GPU > 100 %, température lue en dixièmes (810) ou nulle →
   utilisation bornée à 0–100, température gardée seulement entre 1 et 150 °C → tests `normalizeGpu` (Task 1).
4. **Réponse partielle côté tablette** (Mac : pas de `gpu` ; Intel : `gpu` sans `tempC`/`fanRpm`) : rien ne plante,
   les éléments disparaissent → tests `parsePerf` (Task 4).
5. **Widget hors écran** (autre page de l'Accueil, autre mode, appli en pause) : plus aucune requête ; reprise au retour
   → vérification manuelle avec le journal du compagnon (Task 6).

---

### Task 1 : mesure des performances dans le compagnon (`src/perf.ts`)

**Files :**
- Create : `backend/src/perf.ts`
- Test : `backend/test/perf.test.ts`

**Interfaces :**
- Consumes : rien.
- Produces :
  - `export type GpuPerf = { name: string; usage: number; dedicated: boolean; tempC?: number; fanRpm?: number }`
  - `export type RamUsage = { used: number; total: number; percent: number }` (Go à 1 décimale, % entier)
  - `export type Perf = { cpu: number; ram: RamUsage; gpu?: GpuPerf }`
  - `export type CpuTimes = { idle: number; total: number }`
  - `export function cpuTimes(cpus?: os.CpuInfo[]): CpuTimes` (somme sur tous les cœurs ; défaut `os.cpus()`)
  - `export function cpuPercent(prev: CpuTimes, next: CpuTimes): number` (entier 0–100 ; écart nul → 0)
  - `export function ramUsage(totalBytes: number, availableBytes: number): RamUsage`
  - `export function normalizeGpu(raw: GpuPerf): GpuPerf` (usage arrondi et borné 0–100 ; `tempC` retiré hors 1–150 ;
    `fanRpm` négatif retiré)
  - `export class FanMemory { apply(gpu: GpuPerf): GpuPerf }` : tant qu'aucun `fanRpm > 0` n'a été vu, un `fanRpm` à 0
    est retiré ; après, 0 est gardé (« Arrêté »). Une mémoire par nom de carte.
  - `export type PerfSources = { cpus: () => os.CpuInfo[]; memory: () => Promise<{ total: number; available: number }>; gpu: (() => Promise<GpuPerf | null>) | null; now: () => number }`
  - `export function perfSampler(sources: PerfSources): () => Promise<Perf>` : résultat mis en cache 1 000 ms ;
    premier appel = deux lectures CPU à 250 ms d'écart ; GPU qui lève ou renvoie `null` → pas de `gpu` ; GPU passé par
    `normalizeGpu` puis `FanMemory`.

- [ ] **Step 1 : écrire les tests qui échouent** (`node:test` + `assert/strict`, comme `test/store.test.ts`)

```ts
it("cpuPercent : 25 % d'activité", () => {
  assert.equal(cpuPercent({ idle: 100, total: 200 }, { idle: 175, total: 300 }), 25);
});
it("cpuPercent : aucun écart → 0, jamais NaN", () => {
  assert.equal(cpuPercent({ idle: 5, total: 10 }, { idle: 5, total: 10 }), 0);
});
it("ramUsage : 20,1 / 32 Go, 63 %", () => {
  const g = 1024 ** 3;
  assert.deepEqual(ramUsage(32 * g, 11.9 * g), { used: 20.1, total: 32, percent: 63 });
});
it("normalizeGpu borne l'usage et rejette une température aberrante", () => {
  assert.deepEqual(normalizeGpu({ name: "X", usage: 134.6, dedicated: true, tempC: 810, fanRpm: -1 }), { name: "X", usage: 100, dedicated: true });
  assert.equal(normalizeGpu({ name: "X", usage: 40, dedicated: true, tempC: 0 }).tempC, undefined);
});
it("FanMemory : 0 absent tant qu'aucune vitesse vue, puis « Arrêté »", () => {
  const fans = new FanMemory();
  const g = { name: "RTX", usage: 5, dedicated: true };
  assert.equal(fans.apply({ ...g, fanRpm: 0 }).fanRpm, undefined);
  assert.equal(fans.apply({ ...g, fanRpm: 1850 }).fanRpm, 1850);
  assert.equal(fans.apply({ ...g, fanRpm: 0 }).fanRpm, 0);
});
it("getPerf premier appel : pourcentage réel entre 0 et 100", async () => { /* sources factices, cpus qui avancent */ });
it("cache : deux appels à moins d'1 s ne relisent pas les sources", async () => { /* compteur d'appels de memory() */ });
it("gpu en échec : cpu et ram présents, pas de gpu", async () => { /* gpu: async () => { throw new Error("pilote") } */ });
```

- [ ] **Step 2 : lancer et voir échouer** — `cd backend && node --import tsx --test test/perf.test.ts` → échec « Cannot find module ../src/perf.ts ».
- [ ] **Step 3 : implémenter `backend/src/perf.ts`** selon les interfaces ci-dessus (commentaire d'en-tête en français, style des autres modules).
- [ ] **Step 4 : relancer** — même commande → tous verts.
- [ ] **Step 5 : commit** (avec l'accord de Bruno) — `feat(perf): mesure processeur, mémoire et carte graphique`.

### Task 2 : route `GET /system/perf`, `os.ts`, faux système et contrôle de la sanity

**Files :**
- Modify : `backend/src/os.ts` (nouvel export à côté de `getAudio`, ~l. 120)
- Modify : `backend/src/mac/system.ts` (mémoire disponible du Mac)
- Modify : `backend/src/win/index.ts` (section « Son », ajouter une section « Performances »)
- Modify : `backend/src/routes/system.ts`
- Modify : `backend/test/fake-os.ts` (mêmes exports que `os.ts`, sinon les tests échouent au chargement)
- Modify : `backend/qa/sanity.ts` (contrôle après « son », ~l. 148)
- Test : `backend/test/perf-api.test.ts`

**Interfaces :**
- Consumes : `Perf`, `GpuPerf`, `perfSampler` (Task 1).
- Produces :
  - `os.ts` : `export function getPerf(): Promise<Perf>` (501 via `unsupported()` hors Mac/Windows) et
    `export function getPerfDetail(): Promise<unknown>` (Windows : diagnostic brut du pilote ; Mac : `{ gpu: null }`).
  - `mac/system.ts` : `export async function availableMemory(): Promise<number>` (octets ; `vm_stat` : pages free +
    inactive + speculative + purgeable × taille de page), utilisé à la place de `os.freemem()` sur Mac.
  - `win/index.ts` : `export function gpuPerf(): Promise<GpuPerf | null>` = `call<GpuPerf | null>("perf")` et
    `export function gpuPerfDetail(): Promise<unknown>` = `call("perf", { detail: true })`.
  - Routes : `GET /system/perf` → `Perf` ; `GET /system/perf/detail` → diagnostic (pour tester plus tard sur un PC à
    carte dédiée).
  - `fake-os.ts` : `FakeState.perf?: Perf` ; `getPerf()` renvoie `read().perf ?? { cpu: 12, ram: { used: 4, total: 16, percent: 25 } }`
    après le contrôle 501 habituel ; `getPerfDetail()` → `{ gpu: null }`.

- [ ] **Step 1 : tests qui échouent** (`startServer()` de `test/server.ts`, comme `api.test.ts`)

```ts
it("GET /system/perf renvoie cpu et ram", async () => {
  const { status, json } = await server.call("GET", "/system/perf");
  assert.equal(status, 200);
  assert.equal(typeof json.cpu, "number");
  assert.ok(json.ram.total > 0);
});
it("GET /system/perf demande le jeton", async () => {
  assert.equal((await server.call("GET", "/system/perf", undefined, false)).status, 401);
});
it("GET /system/perf répond 501 hors Mac et Windows", async () => { /* serveur lancé avec le faux système « other », comme les tests 501 existants */ });
it("GET /system/perf garde les champs gpu fournis", async () => { /* FakeState.perf avec gpu { tempC: 81, fanRpm: 1850 } */ });
```

- [ ] **Step 2 : voir échouer** — `cd backend && node --import tsx --test test/perf-api.test.ts` → 404 sur `/system/perf`.
- [ ] **Step 3 : implémenter** `os.ts`, `mac/system.ts`, `win/index.ts` (l'opération `perf` du pilote arrive en Task 3 ; d'ici là l'appel lève « Opération inconnue » et la route renvoie quand même CPU + RAM grâce au Task 1), routes, faux système.
- [ ] **Step 4 : contrôle de la sanity** « performances » : non piloté → `expectStatus(res, 501, …)` ; sinon 200,
  `cpu` nombre entre 0 et 100, `ram.total > 0` ; détail renvoyé : `cpu X %, ram Y %` plus `, gpu Z %` si présent.
- [ ] **Step 5 : vérifier** — `cd backend && npm test && npm run lint` → tout vert (dont `test/sanity.test.ts`).
- [ ] **Step 6 : contrôle réel** — `npm run dev` puis `curl -H "authorization: Bearer <jeton de .data/identity.json>" 127.0.0.1:3000/system/perf` → `cpu` et `ram` proches du Gestionnaire des tâches.
- [ ] **Step 7 : commit** (avec l'accord de Bruno) — `feat(perf): route /system/perf`.

### Task 3 : opération `perf` du pilote Windows (carte graphique)

**Files :**
- Create : `backend/src/win/helper/Native.Perf.cs` (classe partielle `Native`, comme `Native.AppAudio.cs`)
- Modify : `backend/src/win/helper/Native.cs:56` (ajouter `case "perf": return Perf(req);` dans `Dispatch`)
- Regenerate : `backend/src/win/UnowhyHelper.exe` (et `UnoMate.exe` si la compilation le change)

**Interfaces :**
- Consumes : protocole JSON ligne à ligne de `helper.ts` (`call("perf", { detail? })`).
- Produces : `static object Perf(Dictionary<string, object> req)` →
  - sans `detail` : `null` si aucune carte lisible, sinon `{ name, usage, dedicated, tempC?, fanRpm? }` pour **la carte
    retenue** : la dédiée si elle existe, sinon la première ;
  - avec `detail: true` : `{ adapters: [ { luid, name, dedicatedMemoryMb, usage, tempRaw, fanRaw, perfDataError } ] }`
    (valeurs brutes, aucune règle appliquée) — c'est le diagnostic pour un PC à carte dédiée.

Choix fixés (le reste au développeur) :
- **Utilisation** : compteurs PDH `\GPU Engine(*)\Utilization Percentage` ; instance → `luid_0x…_0x…` et `engtype_…` ;
  par carte, somme par type de moteur puis **maximum des types** (règle du Gestionnaire des tâches). Deux lectures du
  compteur sont nécessaires : garder la requête PDH ouverte entre deux appels (le pilote est résident) et ne renvoyer
  rien au tout premier appel plutôt qu'un 0.
- **Cartes, nom, dédiée** : DXGI (`CreateDXGIFactory1`, `EnumAdapters1`, `DXGI_ADAPTER_DESC1`) ; dédiée =
  `DedicatedVideoMemory ≥ 512 Mo` et pas l'adaptateur logiciel (`DXGI_ADAPTER_FLAG_SOFTWARE`) ; rapprochement avec PDH
  par le LUID.
- **Température et ventilateur** : `D3DKMTOpenAdapterFromLuid` + `D3DKMTQueryAdapterInfo` type « adapter perf data »
  (`D3DKMT_ADAPTER_PERFDATA` : `Temperature` en dixièmes de °C, `FanRPM`) puis `D3DKMTCloseAdapter`. **Vérifier la
  valeur exacte de l'énumération et la disposition de la structure dans `d3dkmthk.h` du WDK** avant d'écrire le
  P/Invoke ; en cas d'échec de l'appel, champs absents (pas d'exception).
- Conversion `tempC = Temperature / 10` arrondi ; le filtrage (0, aberrations, ventilateur arrêté) reste côté
  TypeScript (Task 1).

- [ ] **Step 1 : installer un SDK .NET 8 local** si `dotnet --list-sdks` est vide : `backend/qa/build-helper-windows.ps1`
  (outil de développement, pas d'exécution chez le client).
- [ ] **Step 2 : implémenter** `Native.Perf.cs` + la ligne du `Dispatch`.
- [ ] **Step 3 : compiler** — `cd backend && npm run build:helper` → les deux exe régénérés sans erreur ni avertissement nouveau.
- [ ] **Step 4 : contrôle sur le PC de Bruno** (Intel Core Ultra 7 265U, puce intégrée) — compagnon relancé, puis
  `curl …/system/perf` → `gpu.name` contient « Intel », `dedicated: false`, `usage` proche du Gestionnaire des tâches
  (± 5 points), **pas** de `tempC` ni `fanRpm`. `curl …/system/perf/detail` → la puce listée avec ses valeurs brutes.
- [ ] **Step 5 : antivirus** — aucune alerte Windows Defender au lancement du nouvel exe (Journal de protection vide
  pour `UnowhyHelper.exe`).
- [ ] **Step 6 : PC à carte dédiée — NON TESTÉ** tant que Bruno n'en a pas un sous la main : le noter dans
  `docs/etat-du-projet.md` (Task 6) avec la marche à suivre : installer le paquet, lancer
  `curl …/system/perf/detail`, comparer `tempRaw/10` au Gestionnaire des tâches et `fanRaw` à l'outil NVIDIA / AMD,
  vérifier Bitdefender.
- [ ] **Step 7 : commit** (avec l'accord de Bruno) — `feat(perf): carte graphique lue par le pilote Windows`.

### Task 4 : API de la tablette et logique pure du widget

**Files :**
- Modify : `app/src/main/kotlin/com/unowhy/unomate/net/SystemApi.kt`
- Create : `app/src/main/kotlin/com/unowhy/unomate/ui/home/PerfModel.kt`
- Test : `app/src/test/kotlin/com/unowhy/unomate/net/SystemApiPerfTest.kt`
- Test : `app/src/test/kotlin/com/unowhy/unomate/ui/home/PerfModelTest.kt`

**Interfaces :**
- Consumes : JSON de `GET /system/perf` (Task 2).
- Produces :
  - `data class GpuPerf(val name: String, val usage: Int, val dedicated: Boolean, val tempC: Int?, val fanRpm: Int?)`
  - `data class PcPerf(val cpu: Int, val ramPercent: Int, val ramUsedGb: Double, val ramTotalGb: Double, val gpu: GpuPerf?)`
  - `fun parsePerf(json: String): PcPerf` (top-level dans `SystemApi.kt`, testable sur la JVM)
  - `SystemApi.perf(): PcPerf` = `http.io { parsePerf(http.get("/system/perf", timeoutMs = 5_000)) }`
  - `PerfModel.kt` : `enum class PerfLevel { NORMAL, WARN, CRITICAL }`, `fun usageLevel(percent: Int): PerfLevel`,
    `fun tempLevel(celsius: Int): PerfLevel`, `class PerfHistory(val capacity: Int = 30) { fun add(cpu: Int, gpu: Int?); val cpu: List<Int>; val gpu: List<Int?> }`
    (les plus anciennes mesures sortent au-delà de `capacity`).

- [ ] **Step 1 : tests qui échouent**

```kotlin
@Test fun `parsePerf lit une carte dédiée complète`() {
    val p = parsePerf("""{"cpu":42,"ram":{"used":20.1,"total":32,"percent":63},"gpu":{"name":"RTX","usage":97,"dedicated":true,"tempC":81,"fanRpm":1850}}""")
    assertEquals(42, p.cpu); assertEquals(63, p.ramPercent); assertEquals(81, p.gpu!!.tempC); assertEquals(1850, p.gpu!!.fanRpm)
}
@Test fun `parsePerf sans gpu (Mac)`() { assertNull(parsePerf("""{"cpu":5,"ram":{"used":8,"total":16,"percent":50}}""").gpu) }
@Test fun `parsePerf puce intégrée sans température ni ventilateur`() { /* gpu sans tempC/fanRpm → null, null */ }
@Test fun `parsePerf ventilateur arrêté`() { /* "fanRpm":0 → 0, pas null */ }
@Test fun `seuils d'utilisation`() { assertEquals(NORMAL, usageLevel(84)); assertEquals(WARN, usageLevel(85)); assertEquals(CRITICAL, usageLevel(95)) }
@Test fun `seuils de température`() { assertEquals(NORMAL, tempLevel(79)); assertEquals(WARN, tempLevel(80)); assertEquals(CRITICAL, tempLevel(90)) }
@Test fun `l'historique garde les 30 dernières mesures`() { /* 31 ajouts → taille 30, première = 2e ajoutée */ }
```

- [ ] **Step 2 : voir échouer** — `./gradlew testDebugUnitTest --tests "*Perf*"` → erreurs de compilation (symboles absents).
- [ ] **Step 3 : implémenter** `GpuPerf`, `PcPerf`, `parsePerf`, `SystemApi.perf()`, `PerfModel.kt`.
- [ ] **Step 4 : relancer** — même commande → verts ; puis `./gradlew ktlintCheck` → vert.
- [ ] **Step 5 : commit** (avec l'accord de Bruno) — `feat(perf): API et logique du widget sur la tablette`.

### Task 5 : le widget `PerfWidget` (trois vues)

**Files :**
- Create : `app/src/main/kotlin/com/unowhy/unomate/ui/home/PerfWidget.kt` (widget + petites vues dessinées : jauge
  ronde, courbe, barre)
- Modify : `app/src/main/kotlin/com/unowhy/unomate/ui/home/WidgetModel.kt` (entrée `PERF` après `MEDIA`)
- Modify : `app/src/main/kotlin/com/unowhy/unomate/ui/home/HomeWidgets.kt` (`create` : `WidgetType.PERF -> PerfWidget(...)`)
- Modify : `app/src/main/res/values/strings.xml`
- Create : `app/src/main/res/drawable/ic_speed.xml` (icône Material « speed », même format que les autres `ic_*`)

**Interfaces :**
- Consumes : `SystemApi.perf()`, `PcPerf`, `PerfLevel`, `usageLevel`, `tempLevel`, `PerfHistory` (Task 4) ;
  `HomeWidget` (`applySize`, `setActive`, `asCard`, `cardTitle`, `label`, `color`, `dp`) ; `Computers.active(context)?.name`.
- Produces : `WidgetType.PERF("perf", R.string.widget_perf, R.string.widget_perf_desc, R.drawable.ic_speed, listOf(WidgetSize.M, WidgetSize.L, WidgetSize.W), true)`
  et `class PerfWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed)`.

Chaînes à ajouter : `widget_perf` « Performances du PC », `widget_perf_desc` « Processeur, carte graphique et mémoire en
direct », `perf_title` « PC · %1$s », `perf_cpu` « CPU », `perf_gpu` « GPU », `perf_ram` « RAM », `perf_ram_gb`
« %1$s / %2$s Go », `perf_fan` « Ventilateur », `perf_fan_rpm` « %1$s tr/min », `perf_fan_stopped` « Arrêté »,
`perf_unavailable` « Non disponible sur cet ordinateur ».

Comportement fixé :
- `applySize()` reconstruit la vue selon `placed.size` (M : quatre chiffres CPU / GPU / RAM / GPU °C + courbe CPU et
  GPU ; L : trois jauges + « 20,1 / 32 Go » + pastilles température / ventilateur ; W : trois barres + pastilles à
  droite), puis réaffiche la dernière mesure.
- `setActive(true)` lance une boucle `host.scope.launch { while (isActive) { charger ; delay(2_000) } }` ; `setActive(false)`
  l'annule. Une seule boucle à la fois.
- Couleur de chaque valeur : `usageLevel` / `tempLevel` → `c_accent` / `c_hue_orange` / `c_hue_red`. Ventilateur en
  `c_text`.
- Sans `gpu` : colonne / jauge / barre GPU absente ; sans `tempC` ou `fanRpm` : pastille absente ; `fanRpm == 0` → « Arrêté ».
- `HttpException` 501 → `perf_unavailable` à la place du contenu, boucle arrêtée. Autre erreur : dernières valeurs en
  `c_text_dim` + `errorText(e)` en petit ; retour normal à la mesure suivante réussie.
- Nombres : `%` entier ; Go avec une décimale et virgule (`Locale.FRANCE`) ; tr/min groupés par milliers (« 1 850 »).

- [ ] **Step 1 : implémenter** `PerfWidget.kt`, l'entrée du catalogue, la branche de `create`, les chaînes, l'icône.
- [ ] **Step 2 : vérifier** — `./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` → tout vert.
- [ ] **Step 3 : sur la tablette** (USB, `adb install -r …/app-debug.apk`, `adb reverse tcp:3000 tcp:3000`, compagnon
  en `npm run dev`) : ajouter le widget depuis le catalogue (taille Moyen), passer en Grand puis Bandeau en mode
  Modifier ; vérifier les trois vues dans les quatre thèmes (Réglages) ; capture `adb exec-out screencap -p > perf-M.png`
  par vue pour le compte rendu.
- [ ] **Step 4 : commit** (avec l'accord de Bruno) — `feat(perf): widget Performances du PC sur l'Accueil`.

### Task 6 : vérification de bout en bout et documentation

**Files :**
- Modify : `docs/etat-du-projet.md` (« Fait mais pas encore testé » ; « Historique » une fois commité)

- [ ] **Step 1 : comparaison** — Gestionnaire des tâches ouvert à côté, lancer une charge (vidéo 4K, onglets) : CPU,
  GPU, RAM du widget à ± 5 points pendant 1 min.
- [ ] **Step 2 : widget hors écran** — journal du compagnon (`npm run dev`) : des `GET /system/perf` toutes les ~2 s
  sur la page du widget ; **aucune** sur une autre page de l'Accueil, dans le Deck, écran de la tablette éteint ; elles
  reprennent au retour.
- [ ] **Step 3 : compagnon coupé** (Ctrl+C) : valeurs grisées + message ; relancé : le widget repart seul.
- [ ] **Step 4 : fluidité** — balayer les pages de l'Accueil et changer de mode sur la tablette : pas de saccade visible
  par rapport à avant (comparer avec le widget retiré).
- [ ] **Step 5 : documentation** — ligne dans « Fait mais pas encore testé » : ce qui est vérifié (PC Intel, tablette,
  4 thèmes), ce qui ne l'est pas (carte dédiée : température, ventilateur, choix de la carte sur un portable double
  carte ; Mac : RAM via `vm_stat`, à vérifier sur le Mac d'Eliot ; Bitdefender) et la marche à suivre avec
  `/system/perf/detail`.
- [ ] **Step 6 : vérification finale** — `cd backend && npm test && npm run lint` puis
  `./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug` → tout vert.
- [ ] **Step 7 : commit** (avec l'accord de Bruno) — `docs: état du widget Performances du PC`.
