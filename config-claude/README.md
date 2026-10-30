# Config Claude Code du projet

La config Claude Code utilisée pour développer le compagnon : Claude cadre la tâche avec toi, code dans
un worktree, vérifie (format + typecheck), ouvre la MR et publie un essai sur la tablette sans attendre le pipeline. Il fusionne
seulement après ton go.

## Installer

```sh
config-claude/install.sh
```

Puis relancer Claude Code à la racine du dépôt. Le script n'écrase rien : un fichier déjà présent est
gardé et signalé (`--force` pour écraser).

| Source | Installé dans | Contenu |
|---|---|---|
| `projet/` | `<dépôt>/.claude/` (non versionné) | règle d'autonomie, skills `/ship`, `/essai-local` et `/design-companion`, workflow `tache`, hook Stop, réglages |
| `global/scripts/` | `~/.claude/scripts/` | `wait-until.sh` et `gitlab-status.sh`, attente bornée du pipeline |
| `global/agents/` | `~/.claude/agents/` | agent `implementer`, appelé par le workflow `tache` |
| `global/output-styles/` | `~/.claude/output-styles/` | style de sortie Concis (facultatif, à choisir dans `/config`) |

Le serveur MCP `tablette` (`.mcp.json`) et le `CLAUDE.md` sont déjà versionnés à la racine.

## Prérequis

Mac, `glab` connecté (`glab auth login --hostname gitlab.unowhy.dev`), `jq`, Node 22, JDK 21, SDK Android
(`~/Library/Android/sdk`), Orca pour les worktrees du workflow `tache`. L'essai sur la
tablette passe par le registre de paquets du projet, pas par ton Mac (`docs/developpement.md`, section Release).
Pour retirer l'essai d'une MR fusionnée ou abandonnée : `GITLAB_TOKEN` (jeton personnel, scope `api`) dans l'environnement.

## Utiliser

- **Demander une tâche** : décrire la feature ou le bug. Claude pose des questions jusqu'à ce que le besoin
  soit clair, résume, attend ton « go ». Ensuite il travaille seul jusqu'à l'essai publié
  (`.claude/rules/autonomie-hackathon.md`).
- **Tester** : sur la tablette, Réglages > Mises à jour > Rechercher, choisir la MR dans les essais, puis
  « Ouvrir l'essai ». Si la MR touche le backend, celui de l'ordinateur est remplacé seul (compagnon installé par
  paquet) et remis à l'origine par « Revenir à la stable ». Un retour = une correction sur la même branche, nouvel essai.
- **Fusionner** : répondre « go ». Jamais de fusion sans ça.
- **`/ship`** : livre à la main le travail en cours (vérif locale rapide, commit, push, MR, essai ; pipeline vert exigé avant la fusion).
  Le hook Stop le rappelle s'il reste du travail non poussé.
- **`/essai-local <iid>`** : pose l'appli d'une MR sur la tablette branchée en adb, construite sur le Mac
  (sans pipeline), et passe le compagnon du Mac sur la MR. Rôle Maintainer requis la première fois
  (clé de release copiée dans le trousseau).
- **`/design-companion critique <écran>`** : passe qualité UX/UI d'un écran (aussi `polish`, `etats`,
  `harden`, `distill`, `animate`).

## À savoir

- La règle d'autonomie autorise Claude à commit, pousser et ouvrir des MR **sans demander**. Ne pas
  l'installer si tu préfères valider chaque action (supprimer `.claude/rules/autonomie-hackathon.md`).
- Le workflow `tache` demande l'outil Workflow de Claude Code. Sans lui, Claude code dans la session puis
  lance `/ship`.
- Plusieurs MR peuvent avoir un essai publié (une entrée par MR), mais une seule appli d'essai est installée
  sur la tablette : choisir une MR remplace la précédente.
- `main` est protégée : force push et push direct sur `main` sont bloqués par les réglages.
