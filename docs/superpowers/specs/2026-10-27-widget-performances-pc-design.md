# Widget « Performances du PC » — conception

27/10/2026 · Bruno · essai local (branche `feat/widget-perf-pc`, non poussée)

## Pourquoi

Voir d'un coup d'œil, sur la tablette posée à côté du PC, si l'ordinateur **chauffe ou sature** — en jeu comme au
bureau — sans Alt+Tab ni Gestionnaire des tâches. C'est l'usage « moniteur » du Corsair Xeneon Edge cité dans le pitch
([etat-du-projet.md](../../etat-du-projet.md)), et un argument de vente visible en démo.

## Objectifs

- Un nouveau widget de l'Accueil qui affiche, en direct :
  - utilisation du **processeur** (%) ;
  - utilisation de la **carte graphique** (%) ;
  - utilisation de la **RAM** (% et Go utilisés / total) ;
  - **température** et **vitesse du ventilateur** de la carte graphique, **seulement si le PC a une carte graphique
    dédiée** (NVIDIA, AMD) qui les fournit.
- **Rien à configurer** pour l'utilisateur : pas de pilote, pas de droits administrateur, rien à installer. Le widget
  s'ajoute depuis le catalogue comme les autres.

## Non-objectifs

- Température du processeur et ventilateurs du PC / du processeur : impossibles sans pilote noyau (type WinRing0,
  bloqué par Windows Defender et du même genre que ce que Bitdefender a déjà bloqué sur ce projet). Pas affichés.
- Images par seconde (FPS) du jeu, réseau, disque : pas dans cet essai.
- Historique au-delà d'une minute, alertes parlées ou notifications.

## Anti-objectifs (échec même si « ça marche »)

- Demander quoi que ce soit à l'utilisateur (installation, droit admin, autorisation).
- Ralentir la tablette (MT8167, 2 Go) ou le PC en jeu de façon perceptible.
- Afficher des valeurs fausses ou figées (une température à 0 °C, un ventilateur à 0 tr/min inventé) : une valeur
  inconnue est **absente**, jamais simulée.
- Un antivirus qui se met à bloquer le pilote Windows à cause de ce code.

## Décisions de Bruno

- Contenu : processeur, carte graphique, RAM ; température et ventilateur de la carte graphique en plus quand elle
  est dédiée (sinon ils disparaissent simplement).
- **Trois vues**, et l'utilisateur choisit la sienne :
  - **Moyen (2×1)** : chiffres + courbe de la dernière minute — taille d'ajout ;
  - **Grand (2×2)** : jauges rondes processeur / carte graphique / RAM, température et ventilateur en pastilles
    dessous, « 20,1 / 32 Go » sous la jauge RAM ;
  - **Bandeau (4×1)** : barres horizontales, température et ventilateur en pastilles à droite.
- La vue **suit la taille du widget**, choisie en mode Modifier de l'Accueil, comme les autres widgets (recommandation
  acceptée par Bruno ; pas de bouton sur le widget).

Maquettes validées : les trois cartes de [2026-10-27-widget-performances-pc-maquettes.html](2026-10-27-widget-performances-pc-maquettes.html)
(ancien thème Nuit) — titre « PC · *nom de l'ordinateur* ». Elles gardent la disposition des trois vues ; l'apparence
suit désormais la DA Cadran (voir « Apparence (DA Cadran) ») : plus de couleur par série ni d'orange, plus de
pastilles colorées. Le texte de cette fiche l'emporte sur les maquettes en cas d'écart.

## Choix faits par Claude (à vérifier par Bruno)

- **Seuils d'alerte** : utilisation (processeur, carte graphique, RAM) en alerte à partir de 85 % (critique à partir de
  95 %) ; température de la carte graphique en alerte à partir de 80 °C (critique à partir de 90 °C). Ventilateur :
  jamais en alerte. Alerte et critique ont la même couleur, le rouge Unowhy (`c_accent`) : Cadran n'a pas d'orange.
  Couleurs détaillées dans « Apparence (DA Cadran) ».
- **Rafraîchissement** : toutes les 2 s, **seulement quand la page de l'Accueil qui porte le widget est à l'écran**
  (`setActive`). Courbe de la vue Moyen : les 30 dernières mesures (1 min), gardées en mémoire dans le widget, perdues
  au changement de page (pas de stockage).
- **Courbe** : processeur et carte graphique (deux traits) ; pas la RAM, qui bouge peu.
- **Mac** : processeur et RAM seulement (pas de carte graphique ni de température). Le projet exige qu'une fonction
  marche sur les deux systèmes ; c'est le minimum utile et simple.
- **Linux / pilotage absent** (réponse 501) : le widget affiche « Non disponible sur cet ordinateur ».
- **Pas d'ordinateur appairé** : comportement commun des widgets `computer = true` (carte « Connecter mon ordinateur »).
- **Ordinateur injoignable** : dernières valeurs grisées + phrase d'`errorText(e)` ; reprise automatique.
- Nom du widget dans le catalogue : « Performances du PC » ; description : « Processeur, carte graphique et mémoire en
  direct ».
- **Libellés courts** sur le widget : « CPU », « GPU », « RAM » (usuels en français, la place manque pour « Processeur »),
  « Ventilateur » en toutes lettres (pas « Ventilo »).
- **Ventilateur : une seule source**, celle de Windows (`D3DKMTQueryAdapterInfo`, comme la température). Pas de
  bibliothèque NVIDIA (NVML) ni AMD (ADLX) dans cet essai : un seul chemin de code, pas de nouvel appel natif pour
  l'antivirus. Si le pilote de la carte ne remplit pas ce champ, le ventilateur n'apparaît jamais : c'est accepté.
- **Ventilateur à l'arrêt** (cartes récentes en dessous de ~55 °C) : dès que la carte a donné une vitesse non nulle une
  fois depuis le démarrage du compagnon, un 0 ensuite s'affiche « Arrêté » au lieu de disparaître. Tant qu'aucune vitesse
  non nulle n'a été vue, le ventilateur reste absent (champ non fourni par le pilote). La réponse porte alors
  `"fanRpm": 0`.
- **Niveau de finition** : essai local, mais construit comme s'il devait être fusionné (tests, vérification
  `backend/qa/sanity.ts` exigée par CLAUDE.md pour toute nouvelle fonction du compagnon, exe recompilé avec le SDK figé
  par `backend/global.json`), pour pouvoir le montrer à Eliot et ouvrir une MR sans tout reprendre. Pas de MR dans cet
  essai.

## Apparence (DA Cadran)

Le widget suit la DA Cadran ([docs/ui-ux/da](../../ui-ux/da/README.md)), thèmes Cadran (jour) et Cadran nuit.

- **Tuile** : habillage commun (`asCard` : `c_card_bg`, rayon 32, marge intérieure `c_card_pad` 24), sans ombre ni
  dégradé. En tête, le titre « PC · *nom* » des tuiles Cadran (`TitleView` : 15 sp, Outfit 500, `c_text_dim`, sans
  pastille ni capitales) ; à droite, le message d'erreur en `c_text_faint` (14 sp).
- **Une seule couleur vivante** : tout est neutre, le rouge Unowhy (`c_accent`) ne sert qu'à l'alerte (chiffre et
  remplissage ou trait de la valeur en alerte). Aucune `c_hue_*`, aucune couleur écrite en dur (`perfColorAttr`).
  - chiffres en `c_text`, libellés en `c_text_dim`, texte tertiaire (« 13,8 / 15,5 Go », « Arrêté ») en `c_text_faint` ;
  - anneaux et barres : piste en `c_stroke` (comme les verres d'eau), remplissage en `c_text_dim` (comme la suite de la
    ligne du jour : le chiffre mène, la jauge l'accompagne) ;
  - courbe : processeur en `c_text`, carte graphique en `c_text_faint` ; les libellés « CPU » et « GPU » du Moyen
    reprennent ces deux tons et servent de légende. Une valeur en alerte rend tout son trait rouge ;
  - mesures anciennes (ordinateur qui ne répond pas, retour sur la page) : chiffres en `c_text_faint`, remplissages et
    traits en `c_graphic` (le ton des graduations éteintes).
- **Typographie** : Outfit seulement. Grands chiffres en 300 à chiffres tabulaires (`tnum`, largeur fixe : un relevé ne
  décale rien), libellés en 14 sp 400, sans capitales espacées. Température et ventilateur en texte simple (libellé
  14 sp puis valeur 18 sp), sans fond de pastille : ils ne se touchent pas, et Cadran réserve la pilule aux boutons.
- **Tailles suivant la ligne** : les lignes de l'Accueil Cadran n'ont pas la même hauteur (232 / 92 / 184 / 184 dp) ; la
  tuile calcule ses tailles à chaque mesure, d'après la place reçue.
  - **Moyen** : quatre colonnes (CPU, GPU, RAM, GPU °C), libellé au-dessus du chiffre, puis la courbe sur le reste de la
    hauteur (24 dp au moins). Chiffres proportionnels à la hauteur, jusqu'à 52 sp, bornés pour qu'un « 100% » tienne
    dans sa colonne. Sur la ligne basse (92 dp) : ni titre ni courbe, les quatre « libellé chiffre » sur une ligne.
  - **Grand** : trois anneaux (piste et arc proportionnels à leur taille), le chiffre et son libellé au centre ; le
    chiffre prend la taille « Valeurs » de la DA (jusqu'à 52 sp) si elle tient dans l'anneau, sinon la plus grande qui
    tient. « 13,8 / 15,5 Go » sous l'anneau de la RAM, température et ventilateur sur une ligne en bas.
  - **Bandeau** : trois lignes « libellé, barre de 8 dp, chiffre » (chiffre à 80 % de la hauteur de sa ligne, jusqu'à
    44 sp), température et ventilateur l'un sous l'autre à droite. Sur la ligne basse : sans titre, les trois barres
    côte à côte et température et ventilateur l'un à côté de l'autre.
- **Mouvement** : aucun. Toutes les 2 s, le texte n'est remplacé que s'il change, anneaux, barres et courbe sont
  redessinés d'un coup, sans ressort ni boucle d'animation (exception assumée, comme le chrono du Focus).

## Contraintes du projet

- **Une seule porte vers le système** : `backend/src/os.ts`, qui appelle `mac/` ou `win/`. La tablette ne parle qu'au
  compagnon, en HTTP avec le jeton (route protégée comme les autres).
- **Windows** : tout le natif passe par `UnowhyHelper.exe` (C# .NET Framework 4.8, `backend/src/win/helper/`). Pas de
  PowerShell, pas de commande lancée à la volée (classées malveillantes par Bitdefender). L'exe est **versionné** et la
  CI (`helper-windows`) échoue s'il ne correspond plus aux sources : toute modification du pilote se termine par
  `npm run build:helper`, qui demande le **SDK .NET 8** (absent du PC de Bruno aujourd'hui : runtime seul ;
  `backend/qa/build-helper-windows.ps1` installe un SDK local).
- **Tablette** : vues XML / code, pas de Compose ; `HomeWidget` (`ui/home/HomeWidgets.kt`) : `applySize()` refait la vue
  selon `placed.size`, `setActive()` dit si la page est à l'écran, `load()` passe chaque minute (trop lent ici : boucle
  propre au widget pendant `setActive(true)`). Catalogue : `WidgetType` (`ui/home/WidgetModel.kt`), tailles
  `M, L, W` (la première = taille d'ajout).
- Couleurs uniquement par attributs de thème (`themeColor`, `R.attr.c_*`) pour suivre les deux thèmes Cadran (jour et
  nuit).
- Textes en français, dans `res/values/strings.xml`.

## Comment (pistes, détail laissé au développeur)

### Compagnon

- Nouvelle route `GET /system/perf` dans `routes/system.ts` → `os.ts` → `win/` ou `mac/`. Réponse : champs absents
  quand inconnus, par exemple :

  ```json
  { "cpu": 42, "ram": { "used": 20.1, "total": 32, "percent": 63 },
    "gpu": { "name": "NVIDIA GeForce RTX 4070", "usage": 97, "dedicated": true, "tempC": 81, "fanRpm": 1850 } }
  ```

- **Processeur et RAM** : module `os` de Node (écart des temps `os.cpus()` entre deux mesures ; `totalmem`/`freemem`),
  identique sur Mac et Windows, sans le pilote. **Windows** : le processeur est lu en plus par le pilote (opération
  `cpu`, compteur PDH `\Processor Information(_Total)\% Processor Utility`, celui du Gestionnaire des tâches, borné à
  100) : `os.cpus()` donne le « temps processeur », qui sous-estime nettement la charge quand le processeur accélère
  (~50 % contre 66–100 % au Gestionnaire des tâches sur le PC de Bruno). Pilote absent, en échec ou trop lent
  (1,5 s) : repli sur `os.cpus()`. Sur Mac, `freemem` sous-estime la mémoire disponible (cache) : à
  corriger (mémoire « disponible » du système) sinon la RAM paraît toujours pleine.
- Mesure faite **au plus une fois par seconde** côté compagnon et partagée (plusieurs tablettes, plusieurs widgets).
- **Carte graphique (Windows)** : nouvelle opération `perf` du pilote :
  - utilisation : compteurs « GPU Engine » de Windows (ceux du Gestionnaire des tâches), par carte, moteur le plus
    chargé ;
  - température et ventilateur : données de performance de la carte exposées par le pilote d'affichage (WDDM 2.4+,
    `D3DKMTQueryAdapterInfo`), même source que le Gestionnaire des tâches ; valeurs absentes si la carte ne les
    fournit pas (puces intégrées) ou si elles valent 0 ;
  - plusieurs cartes (portable avec puce Intel + NVIDIA) : afficher la carte dédiée si elle existe.
- Tests : `backend/test/` avec le faux système (`test/fake-os.ts`) — forme de la réponse, champs absents, calcul du %
  processeur.

### Tablette

- `WidgetType.PERF("perf", …, listOf(M, L, W), computer = true)` et un `PerfWidget` : trois mises en page selon
  `placed.size`, boucle de 2 s pendant `setActive(true)`, arrêtée sinon.
- Appel via l'API système de la tablette (`net/SystemApi.kt`).
- Tests JVM : couleur selon les seuils, tampon de 30 mesures, lecture de la réponse avec et sans `gpu`.

### Documentation

- `docs/etat-du-projet.md` : ligne dans « Fait mais pas encore testé », puis « Ce qui marche » après test sur la
  tablette.

## Comment on saura que c'est réussi

1. Sur le PC de Bruno (Intel Core Ultra 7 265U, puce graphique intégrée) : le widget montre processeur, carte
   graphique et RAM, **sans** température ni ventilateur, valeurs proches du Gestionnaire des tâches (± 5 points).
2. Sur un PC avec carte dédiée : la température apparaît et suit le Gestionnaire des tâches ; le ventilateur, s'il est
   fourni, suit l'outil du fabricant (application NVIDIA / AMD), le Gestionnaire des tâches ne l'affichant pas.
3. Les trois tailles s'affichent correctement en Cadran et Cadran nuit, sur chaque ligne de l'Accueil où elles peuvent
   se poser ; changer de taille en mode Modifier change de vue.
4. La tablette reste fluide sur l'Accueil ; le widget ne fait plus de requête quand on quitte l'Accueil.
5. Aucune alerte antivirus (Bitdefender, Defender) avec le pilote recompilé.

## Laissé au développeur

Mise en page exacte (marges, tailles de police) dans l'esprit des maquettes et des widgets existants ; dessin des
jauges et de la courbe (vue maison, pas de bibliothèque de graphiques) ; nom des fichiers ; découpage du code du pilote.
