---
name: Mate, pages web du compagnon
description: Panneau de l'ordinateur qui se lit comme la tablette elle-même : Console douce, jour et nuit, Figtree, un seul accent vert acide.
colors:
  bg-day: "#F4F2EE"
  tile-day: "#FFFFFF"
  tile-high-day: "#EFECE6"
  rail-day: "#ECE9E3"
  ink-day: "#1B1C1F"
  ink-2-day: "#474B54"
  ink-3-day: "#5E626C"
  line-day: "#D8D3CA"
  now-day: "#3F6B00"
  now-soft-day: "#DFF5B8"
  ok-day: "#1F7A4D"
  ok-soft-day: "#DFF3E8"
  ko-day: "#C62828"
  bg-night: "#17181B"
  tile-night: "#23252A"
  tile-high-night: "#2A2D33"
  rail-night: "#17181B"
  ink-night: "#ECEDEF"
  ink-2-night: "#B4B8C0"
  ink-3-night: "#9095A0"
  line-night: "#3A3D44"
  now-night: "#B8F34A"
  now-soft-night: "#2C3A12"
  ok-night: "#7FD8A6"
  ok-soft-night: "#1C3A2B"
  ko-night: "#FF7A70"
typography:
  headline:
    fontFamily: "Figtree, system-ui, sans-serif"
    fontSize: "24px"
    fontWeight: 500
    lineHeight: 1.2
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Figtree, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 500
    lineHeight: 1.25
  body:
    fontFamily: "Figtree, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Figtree, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.3
  figure:
    fontFamily: "Figtree, system-ui, sans-serif"
    fontSize: "72px"
    fontWeight: 300
    lineHeight: 1.1
    letterSpacing: "0.04em"
    fontFeature: "tnum"
rounded:
  sm: "8px"
  md: "12px"
  lg: "14px"
  xl: "24px"
  pill: "20px"
  field: "23px"
spacing:
  xs: "8px"
  sm: "12px"
  md: "16px"
  row-x: "26px"
  lg: "28px"
  section: "48px"
components:
  button-primary:
    backgroundColor: "{colors.ink-day}"
    textColor: "{colors.bg-day}"
    rounded: "{rounded.pill}"
    padding: "0 18px"
    height: "40px"
  button-ghost:
    backgroundColor: "{colors.tile-high-day}"
    textColor: "{colors.ink-day}"
    rounded: "{rounded.pill}"
    padding: "0 18px"
    height: "40px"
  button-danger:
    backgroundColor: "transparent"
    textColor: "{colors.ko-day}"
    rounded: "{rounded.pill}"
    height: "40px"
  card:
    backgroundColor: "{colors.tile-day}"
    rounded: "{rounded.xl}"
    padding: "22px 26px"
  input-text:
    backgroundColor: "{colors.bg-day}"
    textColor: "{colors.ink-day}"
    rounded: "{rounded.field}"
    padding: "0 20px"
    height: "46px"
  nav-item:
    textColor: "{colors.ink-2-day}"
    rounded: "{rounded.md}"
    height: "40px"
  nav-item-current:
    backgroundColor: "{colors.tile-day}"
    textColor: "{colors.ink-day}"
    rounded: "{rounded.md}"
  theme-choice:
    backgroundColor: "{colors.tile-high-day}"
    textColor: "{colors.ink-day}"
    rounded: "{rounded.lg}"
    height: "44px"
  theme-choice-selected:
    backgroundColor: "{colors.now-soft-day}"
    textColor: "{colors.ink-day}"
    rounded: "{rounded.lg}"
---

# Design System: Mate, pages web du compagnon

Périmètre : les pages web servies par le compagnon de l'ordinateur (`backend/src/local-page.ts`, visualiseur `/camera`). L'appli Android garde sa propre doc. Source des tokens : `backend/src/web-style.ts`, miroir de `app/src/main/res/values/palettes.xml`.

## Overview

**Creative North Star: "La Console déployée"**

La page locale est la tablette vue depuis l'ordinateur : mêmes tokens jour et nuit, même police, même geste de sélection (un trait vert qui glisse). Elle ne ressemble pas à une page d'admin faite de cartes centrées. Une barre de gauche teintée porte l'identité, l'état et les sections ; à droite, des listes groupées sur fond de tuile.

Le ton est calme, dense sans être serré, lisible de loin. L'accent vert est rare : il marque ce qui demande une action et ce qui est sélectionné. Le jour ou la nuit suit le mode de l'ordinateur ; le visualiseur est toujours en nuit.

**Key Characteristics:**

- Deux palettes Console (jour, nuit) portées par des variables CSS. Nuit par défaut sur la tablette ; ici, le mode de l'ordinateur.
- Figtree 300 / 400 / 500, servie par le compagnon (hors ligne).
- Aucun ombrage structurel : la profondeur vient du ton (fond, tuile, tuile haute).
- Pilules, interrupteurs et curseurs habillés, aucun contrôle natif brut.
- Signature : la tablette dessinée à l'échelle 16:10, qui reflète thème et luminosité en direct.

## Colors

Palette ardoise la nuit, papier chaud le jour, un seul accent vif (vert acide), un vert de réussite. Les valeurs sont dans le frontmatter ; les rôles ci-dessous.

### Primary

- **Vert Accent** (`now`, jour #3F6B00, nuit #B8F34A ; nom de variable gardé) : trait de la barre, curseur de l'interrupteur actif, pouce des curseurs, anneau de la carte mise en avant, point d'état en défaut. `now-soft` teinte le choix sélectionné et le seul bloc d'alerte (autorisation manquante).

### Secondary

- **Vert Accord** (`ok`, #1F7A4D / #7FD8A6) : état « marche ». `ok-soft` en fond.
- **Rouge Erreur** (`ko`, #C62828 / #FF7A70) : texte d'erreur et bouton destructif seulement.

### Neutral

- **Fond** (`bg`) : page et champs. **Tuile** (`tile`) : cartes, ligne sélectionnée. **Tuile haute** (`tile-high`) : survol, boutons discrets, code en ligne. **Rail** (`rail`) : barre de gauche.
- **Encre** (`ink`, `ink-2`, `ink-3`) : texte principal, secondaire, placeholder. **Filet** (`line`) : séparateurs et pistes.

### Named Rules

**The Accent Rare Rule.** Le vert `now` ne marque que l'état qui demande une action et la sélection. Jamais de décor, jamais en fond large (sauf `now-soft` pour l'autorisation manquante).

**The Token Pair Rule.** Toute couleur vient d'une variable qui existe en jour et en nuit. Pas de hex en dur dans une section de la page.

## Typography

**Display / Body / Label Font :** Figtree (repli system-ui, sans-serif), une seule famille.

**Character :** ronde, chaleureuse, lisible de loin ; la hiérarchie vient du poids (300 pour les grands chiffres, 400 courant, 500 pour titres et libellés), pas de la famille.

### Hierarchy

- **Headline** (500, 24px, 1.2, -0.01em) : titre de section.
- **Title** (500, 16px, 1.25) : nom du compagnon dans la barre.
- **Body** (400, 15px, 1.5) : texte courant ; secondaire en 14px `ink-2`.
- **Label** (500, 14px) : boutons, entrées de menu, code en ligne.
- **Figure** (300, 72px, chiffres tabulaires, +0.04em) : code d'appairage ; numéros des étapes en 300 28px.

### Named Rules

**The Weight Not Family Rule.** On distingue par le poids de Figtree, pas en ajoutant une police. Chiffres alignés par `tnum`.

## Layout

Grille à deux colonnes : barre de gauche de 272px (collante, pleine hauteur), contenu `main` de 1080px au plus, marge 36px / 56px. Sections espacées de 48px. Réglages en listes groupées : lignes de 56px au moins, padding 12px / 26px, séparateur fin décalé de 26px. La section Tablette met l'aperçu dessiné à gauche (collant) et les réglages à droite (deux colonnes ~1 : 1,05).

Responsive : sous 1100px l'aperçu passe au-dessus (520px au plus) ; sous 820px la barre devient un bandeau, le menu défile à l'horizontale, le trait et les titres de groupe disparaissent, les lignes passent à la ligne.

## Elevation & Depth

Plat par défaut, profondeur tonale : fond, tuile, tuile haute. L'anneau vert de 2px (`box-shadow: 0 0 0 2px var(--now)`) met en avant une carte demandée ou le code d'appairage. Seule l'ombre diffuse du boîtier de la tablette dessinée (`0 24px 48px -28px`) et celle, légère, du pouce d'interrupteur existent hors anneaux.

### Named Rules

**The Tonal Depth Rule.** Une surface se distingue par son ton, pas par une ombre portée. Pas d'ombre dure décalée.

## Shapes

Formes très arrondies, comme la tablette (tuiles de 28dp) : cartes 24px, champs 23px, boutons 20px (pilules), entrées de menu 12px, choix de thème 14px, code en ligne 8px, interrupteur 44×26 rond. Les listes ne portent pas de bordure, seulement des filets.

## Components

### Buttons

- **Shape :** pilule, 40px de haut, 20px de rayon.
- **Primary :** fond `ink`, texte `bg`, 14px / 500, padding 0 18px.
- **Ghost :** fond `tile-high`, texte `ink`. **Danger :** transparent, texte `ko`, filet intérieur `line`.
- **Hover / Active :** opacité 0,88 ; échelle 0,97 à l'appui (160ms). Focus : contour 2px `now`, décalage 2px.

### Cards / Containers

- Fond `tile`, rayon 24px, padding 22px 26px. Mise en avant : anneau `now` de 2px.

### Inputs / Fields

- Champ pilule 46px, fond `bg`, filet intérieur 1px `line`, focus : filet 2px `ink`. Interrupteur 44×26 (fond `line`, `now` actif). Curseur : piste 4px remplie en `ink`, pouce rond `now` cerclé de `tile`.

### Navigation

- Barre de gauche sur `rail` : logo, nom, bloc d'état (points `ok` / `now`), sections. Entrée 40px, texte `ink-2` ; courante sur fond `tile`, poids 500. Un trait vert de 3px glisse sur l'entrée courante (320ms, `cubic-bezier(.16, 1, .3, 1)`). Mobile : menu horizontal.

### Tablette dessinée (signature)

Boîtier sombre, écran 16:10 dimensionné en `cqw`, mini-Console (barre, heure en 300, ligne du jour avec point vert, trois tuiles). Il change de thème et de luminosité en direct ; en connexion, il montre les étapes d'appairage.

### Choix de thème

Grille de 3 colonnes (2 sous 820px), pastille de couleur + nom ; sélectionné : fond `now-soft`, anneau 1,5px `now`.

### Tuile de connecteur

Section Connecteurs : grille `minmax(156px, 1fr)`, tuile `tile` rayon 22px, logo 32px à la couleur de la marque (simple-icons, CC0 ; marques sombres en `ink`), nom en 500, état avec pastille (`ok` relié, `now` à reconnecter). État : « Connecté » ou « Non connecté », rien de technique. Toucher : ouvre la modale du connecteur (`<dialog>` natif, `tile` rayon 28px, fond assombri, entrée 220 ms ; fermée par la croix, Échap ou un clic à côté) avec, s'il n'est pas relié, le titre « Relier X », les étapes numérotées en chiffres fins et le bouton de connexion ; relié, le compte. Pas encore disponible : logo en gris à 45 %. Bientôt : tuile désactivée, pilule « Bientôt » sur `tile-high`.

## Do's and Don'ts

### Do:

- **Do** passer chaque couleur par une variable jour / nuit de `web-style.ts`.
- **Do** réserver le vert `now` à l'action requise et à la sélection.
- **Do** donner aux contrôles 40px de haut au moins et un focus visible de 2px `now`.
- **Do** couper les transitions sous `prefers-reduced-motion`.
- **Do** tester jour, nuit et mobile (820px) pour toute nouvelle section.

### Don't:

- **Don't** empiler des cartes centrées comme une page d'admin : listes groupées sur `tile` dans la barre + contenu.
- **Don't** introduire une seconde police ni un poids hors 300 / 400 / 500.
- **Don't** utiliser de contrôle natif non habillé (case, curseur, champ).
- **Don't** teinter de larges aplats en vert, ni ajouter une seconde couleur vive.
