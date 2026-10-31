# Config Claude Code du projet

La config Claude Code utilisée pour développer le compagnon.

## Installer

```sh
config-claude/install.sh
```

Puis relancer Claude Code à la racine du dépôt. Le script n'écrase rien : un fichier déjà présent est
gardé et signalé (`--force` pour écraser).

| Source | Installé dans | Contenu |
|---|---|---|
| `projet/` | `<dépôt>/.claude/` (non versionné) | skill `/design-companion`, réglages (pas de force push ni de push direct sur `master`) |
| `global/scripts/` | `~/.claude/scripts/` | `wait-until.sh`, attente bornée d'une commande |
| `global/agents/` | `~/.claude/agents/` | agent `implementer`, une tâche d'implémentation en TDD dans un worktree |
| `global/output-styles/` | `~/.claude/output-styles/` | style de sortie Concis (facultatif, à choisir dans `/config`) |

Le serveur MCP `tablette` (`.mcp.json`) et le `CLAUDE.md` sont déjà versionnés à la racine.

## Prérequis

Mac, Node 22, JDK 21, SDK Android (`~/Library/Android/sdk`).

## Utiliser

- **`/design-companion critique <écran>`** : passe qualité UX/UI d'un écran (aussi `polish`, `etats`,
  `harden`, `distill`, `animate`).
- Livrer : branche, PR sur `master`, CI verte (`docs/developpement.md`, section Branches, PR et CI).
