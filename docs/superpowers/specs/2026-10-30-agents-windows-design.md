# Agents Claude sur Windows — conception

30/10/2026 · Bruno · branche `feat/agents-windows` (depuis `main`)

## Pourquoi

Les agents Claude (état des sessions Claude Code, réponses aux permissions et questions depuis la tablette) n'existaient
que sur Mac. Le public visé travaille aussi sous Windows, et Claude Code y tourne (installeur natif `claude.exe`, ou npm).

## Ce qui marche comme sur Mac, sans changement

- `claude agents --json` (filet) et les hooks HTTP (`POST /agents/hook`) : mêmes formats. Hooks de type `http` : aucun
  shell en jeu.
- Statusline (quota 5 h / 7 j) : Claude Code lance les commandes par Git Bash sous Windows (vu avec un hook `command`,
  Claude Code 2.1.260 : `$BASH_VERSION` = 5.2.37) ; l'enveloppe `sh` de `agents-hooks.ts` et le `curl` de Git y marchent.
  Pas vu en vrai pour la statusline elle-même (il faut une session interactive).
- Titre (journal `projects/<dossier>`), MR, ticket : le dossier du journal suit la même règle (`C--Users-…`).

## Ce qui change

- **Trouver Claude** : `claude.exe` puis `claude.cmd`, dans le PATH, `%USERPROFILE%\.local\bin`, `%USERPROFILE%\.claude\local`,
  `%APPDATA%\npm` ; fichier existant suffit (pas de bit d'exécution). Un `.cmd` n'est lancé que par le shell (Node ≥ 20.12) :
  seulement avec des arguments fixes (`[\w:./=-]`), sinon refus.
- **Listes de dossiers** (`AGENTS_CLAUDE_CONFIG_DIRS`) : séparateur `path.delimiter` (« ; »).
- **Relecture** : 8 s par défaut sous Windows (un `claude agents --json` y prend 0,9 à 1,3 s, 0,24 s sur Mac), 4 s sur Mac ;
  `AGENTS_POLL_MS` l'emporte.
- **Devant Claude ?** (`frontApp`) : nouvelle op `front` du pilote (fenêtre de devant → exe), `bundleId` = nom de l'exe en
  minuscules, comparé à `WINDOWS_HOSTS` (terminaux, VS Code et dérivés, IDE JetBrains, Claude Desktop, Orca).
- **Risque** (`isRisky`) : outil `PowerShell` traité comme `Bash` ; effacer (`Remove-Item`, `rd /s`, `del`), formater,
  registre, arrêt, processus et services, droits (`icacls`), élévation, script téléchargé puis exécuté ; écrire hors du
  projet avec des chemins `C:\…`, `\\serveur`, `%VAR%`, `$env:VAR`, `$HOME`, comparés sans casse ; `%TEMP%`, `$env:TEMP`,
  `…\AppData\Local\Temp`, `NUL` comptent comme le projet pour une commande.
- **Orca** : cherché (`orca.exe`, `orca.cmd`, profils `%APPDATA%\orca\claude-profiles`), jamais essayé (absent du PC) ;
  chat Orca et quota d'Orca absents (client du runtime introuvable hors `.app`).

## Fenêtre d'une session (sans Orca)

`src/agents-desktop.ts`, ops `ancestors`, `front`, `raise` du pilote (`helper/Native.Processes.cs`). Par le pid de la
session (`claude agents --json`) :

1. **console de la session** (le pilote s'y rattache un instant, `AttachConsole`, Ctrl-C ignoré) : fenêtre de la console
   classique, ou, pour une pseudo-console ConPTY, sa fenêtre propriétaire (Windows Terminal). Marche aussi quand Windows
   confie à Terminal une console ouverte depuis le menu Démarrer : la session ne descend alors pas de Terminal ;
2. sinon le **premier parent qui a une fenêtre** (Toolhelp32, arrêt à l'Explorateur et aux processus de la session Windows,
   numéro de processus réutilisé écarté par la date de création) : VS Code, Claude Desktop, un IDE ;
3. sinon la fenêtre la plus en avant d'un **hôte connu** : seulement pour l'amener devant.

`canFocus` dès qu'une fenêtre est trouvée. **Écrire** (message, « continue » de reprise, Échap) seulement si `canSend` :

- console classique : une fenêtre = une console, la session y est au premier plan ;
- Windows Terminal : seulement si le titre de la fenêtre (celui de l'onglet affiché) est **égal** au nom ou au titre que
  Claude donne à la session, symbole d'état ôté. Rien d'autre ne dit quel onglet est affiché ; pas « contient » (un shell
  affiche son dossier) ; le nom de dossier mis par défaut n'est pas un nom.
- jamais : éditeurs (le clavier peut être dans un fichier), Claude Desktop, IDE, fenêtre devinée.

Avant **chaque** frappe (Ctrl-U, texte collé, Entrée ; ou Échap), la fenêtre de devant est relue : même fenêtre (et même
titre pour Terminal), sinon arrêt en 409 (« rien n'a été tapé », ou « message interrompu, sans Entrée »). La règle « le
processus de devant est un parent de la session » n'est pas retenue : Terminal n'est pas un parent quand Windows lui a confié
la console. `canSend` faux : la tablette masque déjà le bouton (`AgentsView`).

## Vérifié sur le PC de Bruno (30/10/2026)

- `claude agents --json` par `listClaude` : la session de Claude Desktop lue (1,3 s).
- `ancestors` : `claude.exe` (CLI) ← `claude.exe` (Claude Desktop, fenêtre « Claude ») ; console cachée → devant seulement.
- Console ouverte depuis le menu Démarrer (confiée à Windows Terminal 1.24) : fenêtre de Terminal trouvée par la console.
- `raise` + `front` : Terminal puis Claude Desktop amenés devant. Envoi complet dans un onglet d'essai (titre égal) : tapé ;
  titre différent : refusé ; une fois, premier plan non obtenu : refusé sans rien taper.

## Pas vérifié

- Une vraie session Claude Code dans Windows Terminal (titre réel de l'onglet), dans une console classique, dans VS Code.
- La statusline enveloppée en session interactive ; Orca sous Windows ; une installation par npm (`claude.cmd`) réelle.
