# Les agents Claude Code sur la tablette (recherche, V1 en cours de livraison)

Étudié le 28/10/2026, avec Claude Code 2.1.308 sur le Mac de développement. La V1 est livrée le même jour (MR !59 à !63) : voir « Résultats des essais » plus bas et [etat-du-projet.md](etat-du-projet.md). Idée : la tablette montre les sessions Claude Code de l'ordinateur (travaille, a fini, attend une réponse) et on répond depuis la tablette, surtout aux permissions et aux questions à choix (outil `AskUserQuestion`).

## En bref

- **Faisable sans IA sur la tablette.** Tout passe par ce que Claude Code expose déjà : `claude agents --json` pour l'état, les hooks pour le détail et la réponse.
- **Lire l'état ne demande aucune installation.** `claude agents --json` liste toutes les sessions (terminal compris) avec `busy`, `idle` ou `waiting`.
- **Répondre demande des hooks HTTP** dans `~/.claude/settings.json`. Le backend garde la requête du hook ouverte, la tablette répond, le backend renvoie la décision. Sans réponse à temps, Claude Code retombe sur son dialogue normal dans le terminal.
- **Le plus proche de notre idée** : EchoPortal (tablette Android posée sur le bureau, sans réponse possible) et claude-code-monitor (réponse depuis le téléphone, par frappes dans le terminal). Personne ne fait la réponse propre par hook sur un écran dédié.
- **Concurrence à assumer** : Orca a déjà une appli mobile, et Claude Code a Remote Control. Notre angle : un écran physique sur le bureau, en local, sans compte.

## Projets similaires

Étoiles et date du dernier push lus avec l'API GitHub le 28/10/2026.

| Projet | ★ | Dernier push | Ce que ça fait | Comment il voit l'état | Comment il répond |
|---|---|---|---|---|---|
| [siteboon/claudecodeui](https://github.com/siteboon/claudecodeui) | 14 320 | 27/10/2026 | Interface web et mobile pour Claude Code, Codex, Cursor CLI | Il lance Claude par le SDK ([`claude-runtime.provider.ts`](https://github.com/siteboon/claudecodeui/blob/main/server/modules/providers/list/claude/claude-runtime.provider.ts)) | Permission et question par le SDK : [`AskUserQuestionPanel.tsx`](https://github.com/siteboon/claudecodeui/blob/main/src/modules/chat/tools/InteractiveRenderers/AskUserQuestionPanel.tsx) renvoie `allow` + `updatedInput` avec `answers` |
| [slopus/happy](https://github.com/slopus/happy) | 24 375 | 28/10/2026 | Appli mobile (iOS, Android, web), CLI et relais chiffré de bout en bout ([README](https://github.com/slopus/happy)) | Le CLI pilote Claude par le SDK | Callback `canUseTool` : [`permissionHandler.ts`](https://github.com/slopus/happy/blob/main/packages/happy-cli/src/claude/utils/permissionHandler.ts) ; `AskUserQuestion` jamais approuvé automatiquement, réponse dans `updatedInput` |
| [smtg-ai/claude-squad](https://github.com/smtg-ai/claude-squad) | 8 686 | 20/08/2026 | Gère plusieurs agents (Claude, Codex, OpenCode, Amp) dans le terminal | tmux + worktrees git ([README](https://github.com/smtg-ai/claude-squad#how-it-works)) ; méthode de détection non lue | On s'attache au tmux |
| [kbwo/ccmanager](https://github.com/kbwo/ccmanager) | 1 274 | 27/10/2026 | Gestionnaire de sessions multi-agents, sans tmux, avec worktrees | « Stratégie de détection » par outil : idle, busy, waiting ([README](https://github.com/kbwo/ccmanager)) ; méthode non lue | Dans son terminal ; hooks de changement d'état ; approbation automatique expérimentale par IA |
| [JessyTsui/Claude-Code-Remote](https://github.com/JessyTsui/Claude-Code-Remote) | 1 287 | 06/12/2025 | Notifications email, Telegram, Discord, LINE ; on répond par message | Hook `Stop` ([README](https://github.com/JessyTsui/Claude-Code-Remote)) | Injection de la commande dans tmux ou dans un PTY |
| [onikan27/claude-code-monitor](https://github.com/onikan27/claude-code-monitor) | 314 | 29/01/2026 | Tableau de bord multi-sessions, appli web mobile par WebSocket, code QR | Hooks `PreToolUse`, `PostToolUse`, `Notification`, `Stop`, `UserPromptSubmit` ([`handler.ts`](https://github.com/onikan27/claude-code-monitor/blob/main/src/hook/handler.ts)) | Frappes dans le terminal par son TTY (iTerm2, Terminal.app, Ghostty) : flèches + Entrée, capture d'écran du terminal ([README](https://github.com/onikan27/claude-code-monitor#-mobile-web-interface)) ; jeton, WiFi local ou Tailscale |
| [kevin101681/claude-code-dashboard](https://github.com/kevin101681/claude-code-dashboard) | 11 | 26/08/2026 | Poste de contrôle web pour Claude Code et Codex, utilisable depuis un téléphone | Transcripts `~/.claude/projects/**/*.jsonl` lus par morceaux ([README](https://github.com/kevin101681/claude-code-dashboard#how-it-works)) | Il héberge lui-même les sessions (ConPTY) et y tape la réponse |
| [Stargx/claude-code-dashboard](https://github.com/Stargx/claude-code-dashboard) | 16 | 09/03/2026 | Page locale qui suit les sessions (thinking, waiting, idle) | Lecture des `.jsonl`, relue toutes les 2 s ([README](https://github.com/Stargx/claude-code-dashboard#how-it-works)) | Aucune (lecture seule) |
| [Elidder/Claude-Code-StreamDeck-Mirabox](https://github.com/Elidder/Claude-Code-StreamDeck-Mirabox) | 1 | 18/10/2026 | Plugin Stream Dock (Mirabox, AJAZZ) : lance Claude Code, quota, droïde animé qui affiche « ! » quand une permission est attendue | Un hook installé dans `~/.claude/settings.json`, avec sauvegarde ([README](https://github.com/Elidder/Claude-Code-StreamDeck-Mirabox)) | Aucune réponse décrite |
| [PartiallyFrozen/EchoPortal](https://github.com/PartiallyFrozen/EchoPortal) | 56 | 03/10/2026 | Echo Spot (Android 11, LineageOS) en tableau de bord de PC, dont un écran « Claude » avec carillon | Hooks → un datagramme UDP en local → machine à états ([`claude_hook.py`](https://github.com/PartiallyFrozen/EchoPortal/blob/main/pc-agent/claude_hook.py), [`claudemon.py`](https://github.com/PartiallyFrozen/EchoPortal/blob/main/pc-agent/claudemon.py)) | Aucune : il prévient seulement |
| [CVSRohit/claude-code-remote](https://github.com/CVSRohit/claude-code-remote) | 0 | 13/06/2026 | Boîtier ESP32 avec écran et boutons approuver / refuser, via un relais | Agent SDK ([README](https://github.com/CVSRohit/claude-code-remote)) | `canUseTool` bloque l'appel jusqu'au bouton ; en mode auto, un hook `PreToolUse` fait seulement défiler les appels |
| [EatingVoltage/clampel](https://github.com/EatingVoltage/clampel) | 0 | 14/10/2026 | Une LED (ESP32-C3) : rouge travaille, ambre attend, bleu limite atteinte | Hooks, tableau complet des événements ([README](https://github.com/EatingVoltage/clampel)) ; fin du transcript lue pour détecter un `Esc` | Aucune |
| [generativereality/ccremote](https://github.com/generativereality/ccremote) | 48 | 30/06/2026 | Approbations par Discord, reprise après limite de quota | tmux lu toutes les 2 s ([README](https://github.com/generativereality/ccremote)) | Frappes dans tmux. **Abandonné** : l'auteur renvoie vers Remote Control |
| [JoJoJotarou/AskUserQuestionPlus](https://github.com/JoJoJotarou/AskUserQuestionPlus) | 37 | 05/02/2026 | Serveur MCP qui pose les questions dans une page web, à la place de `AskUserQuestion` | Outil MCP que le modèle choisit d'appeler ([README](https://github.com/JoJoJotarou/AskUserQuestionPlus)) | Réponse renvoyée comme résultat de l'outil |
| [stablyai/orca](https://github.com/stablyai/orca) | 108 594 | 28/10/2026 | Gestionnaire de worktrees et d'agents, avec une [appli mobile](https://www.onorca.dev/docs/mobile) (iOS, Android) | Hooks Claude posés par Orca (voir plus bas) | Message court, permissions et questions par le Chat UI ([doc mobile](https://www.onorca.dev/docs/mobile)) |

Non retenus : les tableaux de bord de coût ou de quota ([sniffly](https://github.com/chiphuyen/sniffly), claumon), les notifieurs simples (`claude-code-ntfy`, `claude-code-toast`) et les autres plugins Stream Deck à 0 étoile (par exemple [jonasthim/claude-code-stream-deck](https://github.com/jonasthim/claude-code-stream-deck), qui lit les transcripts).

### Ce qu'on en retient

- **Trois façons de voir l'état** : les hooks (propre, en direct), les transcripts `.jsonl` (format interne, instable : [doc](https://code.claude.com/docs/en/sessions)), le terminal (fragile). Les hooks gagnent.
- **Trois façons de répondre** : le SDK `canUseTool` (il faut lancer Claude soi-même, donc pas les sessions déjà ouvertes), les frappes dans le terminal (marche partout, fragile : navigation aux flèches), ou un hook qui décide. Seule la dernière marche sur une session ordinaire sans frappes.
- **Les écrans dédiés ne répondent pas** (EchoPortal, Stream Dock, clampel). Le boîtier ESP32 répond, mais par le SDK, pas par les hooks.
- **Le tableau des hooks de clampel** (SessionStart, UserPromptSubmit, PreToolUse sur `AskUserQuestion|ExitPlanMode`, Notification, Stop, StopFailure, SessionEnd) est la meilleure base d'une machine à états. Le détail est repris plus bas.
- **Les sessions mortes sans `SessionEnd`** et l'arrêt par `Esc` (qui peut ne pas lancer `Stop`) demandent un filet de sécurité : clampel lit la fin du transcript, nous pouvons relire `claude agents --json`.

## Ce que Claude Code permet

Sources : la documentation officielle lue le 28/10/2026, sauf mention « testé ».

| Question | Réponse | Source |
|---|---|---|
| Lister les sessions et leur état depuis l'extérieur ? | **Oui.** `claude agents --json` donne les sessions interactives et en arrière-plan : `cwd`, `kind`, `startedAt`, `sessionId`, `name`, `pid`, `status` (`busy`, `idle`, `waiting`) et `waitingFor` (`permission prompt`, `input needed`, `sandbox request`…). C'est « the supported way to read session state from outside Claude Code ». Testé : 5 sessions en 0,24 s ; `waiting` pas encore observé | [agent view](https://code.claude.com/docs/en/agent-view#read-session-state-from-a-script) |
| Savoir quelle question ou quelle commande est en attente ? | **Seulement par les hooks.** `PermissionRequest` : `tool_name`, `tool_input`, `permission_suggestions`. `PreToolUse` : `tool_input` (pour `AskUserQuestion` : `questions`). `Notification` : `message`, `notification_type` (`permission_prompt`, `elicitation_dialog`, `agent_needs_input`, `idle_prompt`…). La statusline n'a aucun champ de ce genre | [hooks](https://code.claude.com/docs/en/hooks), [statusline](https://code.claude.com/docs/en/statusline) |
| Fin de tour, échec, fin de session ? | Hooks `Stop` (`last_assistant_message`), `StopFailure`, `SessionEnd`, `SubagentStart` / `SubagentStop` | [hooks](https://code.claude.com/docs/en/hooks) |
| Un hook peut-il être une URL ? | **Oui.** Type `http` : Claude Code envoie le JSON de l'événement en POST, la réponse 2xx en JSON décide comme la sortie d'un hook command. Un échec de connexion ou un non-2xx ne bloque pas | [hooks, HTTP hooks](https://code.claude.com/docs/en/hooks#http-hook-fields) |
| Répondre à une permission à distance ? | **Oui.** Hook `PermissionRequest` qui renvoie `hookSpecificOutput.decision.behavior` = `allow` ou `deny` (`message` pour refuser). Sans décision, le dialogue normal reste | [hooks](https://code.claude.com/docs/en/hooks#permissionrequest-decision-control) |
| Répondre à `AskUserQuestion` à distance ? | **Oui.** Hook `PreToolUse` qui renvoie `permissionDecision: "allow"` et `updatedInput` avec les `questions` d'origine et `answers` (`{"texte de la question": "label choisi"}`, labels joints par des virgules pour le multi-choix). « Returning `allow` alone is not sufficient » | [hooks](https://code.claude.com/docs/en/hooks#tools-that-require-user-interaction) |
| Combien de temps un hook peut-il attendre ? | `timeout` : 600 s par défaut pour `command`, `http`, `mcp_tool`. Au dépassement le hook est annulé et, sur la plupart des événements, aucune décision n'est rendue. Le plafond n'est pas documenté | [hooks, common fields](https://code.claude.com/docs/en/hooks#common-fields) |
| Un hook peut-il passer outre une règle `deny` ? | **Non.** Les règles deny et ask restent évaluées. `bypassPermissions` n'approuve pas non plus `AskUserQuestion` | [permissions](https://code.claude.com/docs/en/permissions), [permission modes](https://code.claude.com/docs/en/permission-modes) |
| Répondre depuis `claude agents` (aperçu) ? | Une question à choix : oui, par son numéro. Une permission : « replying doesn't answer it » (il faut s'attacher). Pas de commande documentée pour répondre depuis un script | [agent view, peek and reply](https://code.claude.com/docs/en/agent-view#peek-and-reply) |
| Les sessions ordinaires apparaissent-elles dans `claude agents` ? | Oui dans `--json` (`kind: interactive`, testé). Les champs `id` et `state` n'existent que pour les sessions en arrière-plan | [agent view](https://code.claude.com/docs/en/agent-view#list-sessions-as-json) |
| `AskUserQuestion` dans un sous-agent ? | Non disponible | [user input](https://code.claude.com/docs/en/agent-sdk/user-input) |
| `~/.claude/sessions` ? | N'existe pas (testé). Les transcripts sont dans `~/.claude/projects/<dossier>/<session>.jsonl` ; leur format « is internal and changes between versions » | [sessions](https://code.claude.com/docs/en/sessions) |
| Ce qu'Anthropic propose déjà à distance ? | **Remote Control** : claude.ai/code et l'appli mobile pilotent une session locale ; permissions et `AskUserQuestion` transmises, restent ouvertes jusqu'à la réponse. Exige un abonnement Pro, Max, Team ou Enterprise (pas de clé API) ; le transcript passe par les serveurs d'Anthropic. **Channels** (aperçu de recherche) : relais de permission par serveur MCP, mais pas pour `AskUserQuestion` | [remote control](https://code.claude.com/docs/en/remote-control), [channels](https://code.claude.com/docs/en/channels-reference) |

### Les événements utiles

| Événement | Ce qu'on en tire |
|---|---|
| `SessionStart` | session présente, au repos |
| `UserPromptSubmit` | travaille (`prompt`) |
| `PreToolUse` | travaille (`tool_name`, `tool_input`) ; sur `AskUserQuestion` et `ExitPlanMode` : **attend une réponse** |
| `PermissionRequest` | **attend une permission** (commande, fichier) |
| `Notification` | `permission_prompt`, `elicitation_dialog`, `agent_needs_input` : attend ; `idle_prompt` : fini depuis 60 s |
| `PostToolUse`, `PostToolUseFailure` | travaille (et fin de l'attente si on a répondu dans le terminal) |
| `Stop` | a fini (`last_assistant_message`) |
| `StopFailure` | échec ou limite atteinte |
| `SubagentStart`, `SubagentStop` | sous-agents en cours |
| `SessionEnd` | session retirée |

Source : [hooks](https://code.claude.com/docs/en/hooks), recoupé avec [clampel](https://github.com/EatingVoltage/clampel) et [EchoPortal](https://github.com/PartiallyFrozen/EchoPortal/blob/main/pc-agent/claudemon.py).

## Ce que la CLI Orca expose

Constaté sur ce poste le 28/10/2026 (`orca --help`, `orca skills get orca-cli`).

| Commande | Ce qu'on y lit | Mesure |
|---|---|---|
| `orca worktree ps --json` | Par worktree : repo, branche, chemin, `workspaceStatus`, `comment`, MR liée, et `agents[]` avec `state` (`working`, `done` ; la [doc mobile](https://www.onorca.dev/docs/mobile) cite aussi « waiting on input »), `prompt`, `lastAssistantMessage`, `toolName`, `toolInput`, `workingMode` | 0,18 s, 50 Ko pour 20 worktrees |
| `orca terminal list --json` | Terminaux vivants et leur `handle` | 0,16 s |
| `orca terminal read --screen` | Écran réel d'un terminal | non mesuré |
| `orca terminal send --terminal <handle> --text … [--enter]` | Frappes vers un agent. Un message suivi d'Entrée est refusé (`agent_prompt_blocked`) tant qu'un menu de permission est ouvert : il faut envoyer le texte et Entrée séparément | non mesuré |
| `orca terminal wait --for tui-idle` | Attend que l'agent soit au repos | non mesuré |
| `orca worktree set --comment …` | Met à jour la carte | non mesuré |

Constats :
- **Orca pose déjà ses propres hooks** dans `~/.claude/settings.json` : `PermissionRequest`, `Elicitation`, `SubagentStart`, `TeammateIdle`, `StopFailure` et les autres, vers un port local (`ORCA_AGENT_HOOK_PORT`). Les hooks d'un même événement tournent en parallèle ([doc](https://code.claude.com/docs/en/hooks)) : les nôtres s'ajoutent sans les remplacer. À vérifier : Orca ne réécrit-il pas ce fichier au démarrage ?
- **`worktree ps` donne ce que `claude agents` ne donne pas** : worktree, branche, MR, dernier outil, dernier message. `claude agents` donne ce qu'Orca ne montre pas : `waitingFor`.
- **La jonction se fait par le dossier** : le `cwd` d'une session Claude est le chemin du worktree Orca.
- **Orca ne couvre que les sessions lancées dans Orca.** `claude agents --json` couvre aussi un terminal ordinaire.
- **Frappes avec `orca terminal send`** : repli possible pour répondre à un menu que les hooks ne couvrent pas (dialogue de confiance, par exemple), avec le même défaut que claude-code-monitor : fragile.

## Proposition

### Fonctions

**Indispensable pour une démo**

1. **Liste des sessions** : nom (dossier ou worktree, branche), pastille d'état (travaille, attend, fini, inactif), temps passé dans l'état, dernier outil, extrait de la dernière réponse. Sources : `claude agents --json`, Orca, hooks.
2. **Alerte « attend toi »** : carte par-dessus l'écran, comme l'appel entrant, avec une phrase dite à voix haute (`audio/Speaker`) et un son. Compteur dans le menu et sur l'Accueil.
3. **Permission** : la commande ou le fichier exact, **Autoriser une fois** / **Refuser**.
4. **Question à choix** : une page par question (1 à 4), une option = un gros bouton, cases pour le multi-choix puis **Valider**, option **Autre** avec dictée vocale (reconnaissance Android déjà là).
5. **Fin de session** : carte « Fini » avec 3 lignes de `last_assistant_message`, toucher = amener le terminal devant sur l'ordinateur (`orca terminal switch`).
6. **Widget d'Accueil** : trois compteurs (travaille, attend, fini) et la liste courte.

**Utile**

7. **Lampe d'ambiance** : l'écran vire à l'ambre quand quelque chose attend, utile à un joueur dont le jeu est en plein écran. À rapprocher du modèle de l'Accueil Veille.
8. **Commandes vocales** (sans IA, tournures connues dans `VoiceCommands`) : « autorise », « refuse », « option deux », « premier choix ».
9. **Focus** : pendant la concentration, seuls les « attend toi » sonnent ; pas de son pour « fini ».
10. **Mode privé** : texte des commandes et des questions masqué (écran partagé ou projeté en réunion : la tablette peut passer à l'écran).
11. **Message rapide** à une session au repos (« continue », une phrase dictée) : par `orca terminal send`, donc sessions Orca seulement.
12. **Outil MCP `agents_list`** dans `mcp.ts` : Claude lui-même peut lire l'état des autres sessions.

**Plus tard**

13. Approuver un plan (`ExitPlanMode` : plan long en Markdown, mal adapté à 1280×800).
14. Arrêter ou interrompre une session (avec confirmation) ; lancer un agent (`orca worktree create --agent`, `claude --bg`).
15. Barre de quota 5 h / 7 j (statusline : `rate_limits`, abonnés Pro et Max). Il faut poser une statusline, qui est déjà occupée chez beaucoup d'utilisateurs.
16. Outil MCP `ask_tablet` : l'agent pose une question sur la tablette de lui-même (modèle d'AskUserQuestionPlus). Il faut que le modèle choisisse de l'appeler.
17. Historique de la journée, plusieurs ordinateurs, règles « toujours autoriser ».

### Où ça vit sur la tablette

Pas de nouveau mode. On suit les précédents du projet :

- **Onglet « Agents » du Deck**, après « Son » (comme `OpenWindowsView` et `MixerView`), affiché seulement si `GET /deck/features` annonce `agents` (même logique que `meetings`) : liste complète et réponses.
- **Widget d'Accueil** `AgentsWidget` (tailles M et L) : compteurs et liste courte.
- **Carte par-dessus tout** `AgentAskOverlay`, construite sur le modèle de `CallOverlay` : alerte, permission, question.

### Flux de données

```
Claude Code (N sessions, terminal ou Orca)
   │  hooks HTTP (type http) : POST /agents/hook  (127.0.0.1 + secret)
   ▼
backend agents.ts ── machine à états par session_id ◀── claude agents --json (toutes les 3 à 5 s : filet de sécurité)
   │                                                 ◀── orca worktree ps --json (branche, MR, handle ; optionnel)
   │  ordre `agent` par le canal /tablet (carte « attend toi », « fini »)
   ▼
Tablette : AgentAskOverlay / onglet Agents / widget
   │  POST /agents/answer (jeton d'appairage)
   ▼
backend ── répond à la requête du hook, restée ouverte ──▶ Claude Code applique la décision
```

Règles du backend :
- **Événements d'observation** : réponse `{}` tout de suite.
- **`PermissionRequest` et `PreToolUse` sur `AskUserQuestion` ou `ExitPlanMode`** : la requête reste ouverte jusqu'à la réponse de la tablette ou jusqu'à `AGENTS_HOLD_S` (90 s proposé, sous le `timeout` du hook). Au bout : `{}`, donc le dialogue normal du terminal.
- **Tablette non connectée** ([`tabletStatus().connected`](../backend/src/tablet.ts)) : `{}` tout de suite, pour ne jamais faire attendre l'utilisateur devant son clavier pour rien.
- **Réponse déjà donnée dans le terminal** : l'événement `PostToolUse` suivant efface la carte.
- **La tablette ne reçoit que des cartes** par `/tablet` (« reçue »), et répond par un POST séparé, comme `/calls/answer`. Le délai de 15 s de `sendToTablet` ne bloque donc pas la réponse.

### Routes et fichiers probables

Backend :

| Fichier | Rôle |
|---|---|
| `backend/src/agents.ts` | machine à états, requêtes en attente, construction de la décision JSON, lecture de `claude agents --json` (`execFile`, sans dépendance) |
| `backend/src/orca.ts` | optionnel : `orca worktree ps --json`, ignoré si la commande est absente |
| `backend/src/routes/agents.ts` | `POST /agents/hook`, `GET /agents`, `POST /agents/answer` |
| `backend/src/server.ts` | `app.register`, et `POST /agents/hook` ajouté à `isLocalPage` (local, sans jeton, avec secret propre) |
| `backend/src/tablet.ts`, `routes/tablet.ts` | nouvel ordre `agent` (état `ask`, `done`, `clear`), réservé à l'ordinateur comme `call` |
| `backend/src/routes/deck.ts` | `agents` dans `GET /deck/features` |
| `backend/src/mcp.ts` | outil `agents_list` |
| `backend/scripts/agents-hooks.ts` | `npm run agents:hooks` : affiche le bloc de hooks (secret et URL remplis) ; `--install` le fusionne dans `~/.claude/settings.json` avec sauvegarde |
| `backend/test/agents.test.ts` | machine à états, décisions JSON, délais, refus de réponse inconnue |
| `backend/qa/sanity.ts` | un contrôle : faux événements de hook, état attendu |

Android (`app/src/main/kotlin/app/mate/`) :

| Fichier | Rôle |
|---|---|
| `net/AgentsApi.kt` + `client.agents` dans `BackendClient` | `GET /agents`, `POST /agents/answer` |
| `ui/AgentsView.kt` (dans `DeckFragment`) | onglet Agents |
| `ui/AgentAskOverlay.kt` | carte alerte, permission, question |
| `ui/home/` | `AgentsWidget`, déclaré dans `WidgetModel` |
| `remote/RemoteChannel.kt` | branche `"agent"` dans `execute` |
| `voice/VoiceCommands.kt` | tournures « autorise », « refuse », « option N » |
| `res/values/strings.xml` | textes en français |
| `app/src/test/` | logique pure : choix multiples → `answers`, classement des états |

### Bloc de hooks (forme)

```json
{
  "hooks": {
    "PermissionRequest": [
      { "hooks": [{ "type": "http", "url": "http://127.0.0.1:3000/agents/hook", "timeout": 120, "headers": { "X-Companion-Hook": "<secret>" } }] }
    ],
    "PreToolUse": [
      { "matcher": "AskUserQuestion|ExitPlanMode", "hooks": [{ "type": "http", "url": "http://127.0.0.1:3000/agents/hook", "timeout": 120, "headers": { "X-Companion-Hook": "<secret>" } }] }
    ],
    "Stop": [
      { "hooks": [{ "type": "http", "url": "http://127.0.0.1:3000/agents/hook", "timeout": 5, "headers": { "X-Companion-Hook": "<secret>" } }] }
    ]
  }
}
```

Même forme, `timeout` 5, pour `SessionStart`, `UserPromptSubmit`, `Notification`, `StopFailure` et `SessionEnd`.
Les en-têtes passent par la [syntaxe `headers`](https://code.claude.com/docs/en/hooks#http-hook-fields). `PostToolUse` est un événement très fréquent : on peut s'en passer en première version (le filet `claude agents --json` efface l'attente).

### Sécurité

- **`POST /agents/hook`** : local seulement (`127.0.0.1`, `Host` vérifié contre le rebinding DNS, comme les autres routes locales), JSON uniquement, taille limitée, secret propre dans `.data/` (pas le jeton d'appairage, qui ouvre tout le reste). Les événements ne sont jamais écrits sur disque ; l'état vit en mémoire.
- **`POST /agents/answer`** : jeton d'appairage ordinaire. Le backend n'accepte qu'une réponse à une requête en attente, avec son identifiant ; un choix doit être un des `label` de la question ; le texte libre est limité à 500 caractères.
- **Jamais de décision par défaut.** Délai dépassé ou tablette absente : pas de décision, le terminal décide. Aucune route ne renvoie `allow` sans action explicite sur la tablette.
- **Pas de « toujours autoriser », pas de changement de mode** (`updatedPermissions`, `setMode`, `bypassPermissions`) : la tablette n'envoie que `allow` ou `deny` pour **un** appel.
- **Pas d'action destructive sans confirmation.** La commande exacte est affichée. Les commandes à risque (`rm`, `git push`, `git reset --hard`, `sudo`, `curl … | sh`, écriture hors du dossier du projet) demandent un appui maintenu pour autoriser. **Refuser** reste un simple appui.
- **Le texte vient de l'agent, donc d'un modèle qui a pu lire du contenu hostile.** Il est affiché en texte brut, tronqué (question 200 caractères, commande 400), jamais interprété.
- **Mode privé** pour le direct : aucune commande ni question à l'écran.
- **Les secrets** : un `tool_input` peut contenir un secret ; ni journal complet, ni capture d'écran du canal `/tablet` pendant une alerte.
- **Réseau** : comme le reste, WiFi local, la tablette n'ouvre aucun port.

### Limites sur ce matériel

- **Mémoire** : 2 Go et un tas de 128 Mo ([`tablette-4.2.md`](tablette-4.2.md)). L'état de 10 sessions pèse quelques Ko.
- **Pas de `RecyclerView`** (dépendances : appcompat, lifecycle, coroutines) : une liste de 10 lignes au plus dans un `LinearLayout`, comme `OpenWindowsView`.
- **Écran 1280×800 à 55 Hz** : texte court, pas de Markdown, pas de WebView, pas de diff.
- **Réseau** : pas de relance à 1 s. Les cartes arrivent par le canal `/tablet` (poussées) ; l'onglet n'interroge `GET /agents` toutes les 2 s que lorsqu'il est affiché. Réponse JSON sous 4 Ko.
- **Coût côté ordinateur** : `claude agents --json` est un processus lancé à chaque appel (0,24 s mesuré sur Mac). Toutes les 5 s, seulement quand la tablette est connectée : environ 5 % d'un cœur. Windows : non mesuré.
- **Écran éteint ou atténué (22 h – 7 h)** : l'appli est en pause, les ordres attendent ([`architecture.md`](architecture.md)). Une alerte de nuit n'est pas vue sans réveil de l'écran : à décider.
- **Une seule tablette** par ordinateur (`tablet.ts`).
- **Une session qui attend n'est pas bloquée par la tablette** : le terminal reste utilisable, mais l'utilisateur devant son clavier attend le délai du hook si la tablette est connectée. D'où le délai court et la question 3 plus bas.

### Ordre de réalisation

1. **Lecture seule, sans toucher à la config** : `claude agents --json` + Orca, onglet, widget, alerte « attend toi » et « fini ». Déjà une démo.
2. **Hooks HTTP et permission** : `agents.ts`, route, `agents:hooks`, carte Autoriser / Refuser.
3. **Questions à choix** : une page par question, multi-choix, dictée pour « Autre ».
4. **Voix, lampe, Focus, mode privé.**

## Résultats des essais (28/10/2026, Claude Code 2.1.308, Mac)

Session Claude jetable (`claude --settings <fichier de test>`, rien dans `~/.claude/settings.json`), contre un serveur d'essai sur un autre port, puis contre le vrai backend (instance de test, autre `DATA_DIR`) avec une fausse tablette. Aucun point ne casse la V1.

| # | Question | Résultat |
|---|---|---|
| 1 | Le dialogue du terminal est-il affiché pendant que le hook attend ? | **Oui.** `Do you want to proceed?` est à l'écran dès le début de l'attente ; répondre au clavier marche tout de suite, sans attendre le hook. Le `PostToolUse` arrive ensuite, et `claude agents --json` repasse à `busy` : la carte de la tablette se retire seule. L'utilisateur au clavier n'attend jamais |
| 2 | `PreToolUse` + `updatedInput` sur `AskUserQuestion`, session interactive | **Marche.** Le dialogue n'apparaît pas, le modèle reçoit `User answered Claude's questions: · question → réponse`. Les `questions` d'origine doivent être renvoyées avec `answers` |
| 3 | Texte libre dans `answers` | **Marche.** « Violet foncé, presque noir » et « Fraise des bois » (hors des options) sont transmis tels quels ; multi-choix = libellés joints par `, ` |
| 4 | Durée d'un hook http | Retenu **130 s** avec `timeout` 300 : la décision `allow` est appliquée. Avec `timeout` 8, le hook est annulé au bout de 8 s, le dialogue du terminal reste, et la réponse tardive du serveur est ignorée. Le `timeout` du hook doit donc dépasser `AGENTS_HOLD_S` (120 s pour 90 s) |
| 5 | `waiting` et `waitingFor` | **Vus** : `status: "waiting"`, `waitingFor: "permission prompt"` pendant une vraie permission (même pendant que le hook la retient), `busy` ensuite. `input needed` (question) non observé directement. Aucune valeur pour une limite d'usage |
| 6 | Coexistence avec les hooks d'Orca | **Oui.** Les hooks de `~/.claude/settings.json` (Orca, `afplay`) et les nôtres tournent en parallèle sur le même événement (`running Stop hooks… 2/3`) ; la décision `allow` du nôtre s'applique malgré le `PermissionRequest` d'Orca. Orca remplit toujours sa carte (`worktree ps` : `done`). Non vérifié : Orca ne réécrit pas `settings.json` à son démarrage (non relancé pour ne pas gêner les autres sessions) |
| 7 | Windows | Non essayé : `agents` absent de `/deck/features` et `/agents/*` répondent 501 |

Constats en plus :

- **`SessionStart` n'accepte pas les hooks `http`** (command et mcp_tool seulement, doc). Le premier événement vu est `UserPromptSubmit` ; les sessions déjà ouvertes sont connues par `claude agents --json`.
- **Compagnon éteint** : chaque hook affiche `UserPromptSubmit hook error · connect ECONNREFUSED` dans le terminal, sans rien bloquer. Le message revient à chaque événement hooké : on ne pose donc pas `PostToolUse` (un par outil), seulement les événements de début et de fin de tour.
- **`$ORCA_PANE_KEY` passe en en-tête** (`headers` + `allowedEnvVars`) : le compagnon connaît le volet exact (`tabId:leafId`) de la session, donc son terminal Orca (`orca terminal list`) sans deviner par le dossier. `$TERM_SESSION_ID` arrive vide, `$__CFBundleIdentifier` est mal lu : non utilisés.
- **Les sessions de chat Orca** (`structuredHostOwned`) n'ont pas de terminal : `orca terminal list` ne les voit pas. Le message rapide n'est offert que pour les sessions d'un terminal Orca (essayé : `orca terminal send --enter` démarre bien le tour, `terminal switch` amène l'onglet devant). Pour amener un chat devant, la CLI n'a rien : le client du runtime qu'elle utilise (`app.asar.unpacked/out/cli/runtime-client.js`, lancé par l'exécutable d'Orca en `ELECTRON_RUN_AS_NODE`) accepte `worktree.activate` et `session.tabs.activate` ; l'onglet d'un chat est `agent-session:<id>`, l'id lu dans la clé du volet `structured-agent-session-<id>:…` de `orca worktree ps`. API interne d'Orca : si elle change, le compagnon retombe sur Orca devant. Terminal ordinaire (Terminal.app, iTerm2, Ghostty) : aucun moyen fiable, pas de bouton.
- **`PermissionRequest`** n'a pas de `tool_use_id` : la demande se rattache à la session, pas à l'appel.
- **Limite d'usage** : `StopFailure` avec `error: "rate_limit"` et `last_assistant_message` = texte de l'erreur. Aucune heure de reprise dans un champ ; le compagnon la lit dans le texte (« resets 3pm ») et, si elle est illisible, n'offre pas la reprise automatique. Une vraie limite n'a pas pu être provoquée : la lecture de l'heure n'est testée que sur des textes écrits à la main.

### Essais du 28/10/2026 (suite : plan, interruption, quota)

| Question | Résultat |
|---|---|
| Approuver un plan (`ExitPlanMode`) depuis un hook | `PreToolUse` et `PermissionRequest` arrivent tous deux, avec `tool_input.plan` et `planFilePath`. `allow` seul ne suffit pas (ni en `PermissionRequest`, ni en `PreToolUse`) : le dialogue reste. **`PreToolUse` `allow` + `updatedInput` (l'entrée d'origine renvoyée) approuve** : Claude sort du mode plan vers le mode **default** (chaque modification reste demandée), jamais vers auto |
| Refuser un plan avec un retour | `PreToolUse` `deny` + `permissionDecisionReason` : Claude reçoit la raison, reste en mode plan et corrige son plan |
| Pendant un `PreToolUse` tenu (question, plan) | Le terminal **n'affiche aucun dialogue** (il n'apparaît qu'au retour du hook) et `claude agents --json` dit `busy`. On ne peut donc pas y répondre au clavier : le filet ne doit pas rendre la main (corrigé), et quelqu'un revenu au clavier attend la fin du délai ou répond sur la tablette |
| Fichier du plan | En mode plan, Claude écrit le plan dans `<config>/plans/*.md` avant `ExitPlanMode` : une permission `Write` hors du projet, à ne pas traiter comme une écriture à risque |
| Interrompre une session (terminal Orca) | `orca terminal send --text $'\e'` (Échap) interrompt le tour en cours (`busy` → `idle`, pas de `Stop`), sans risque de quitter Claude. `--interrupt` le fait aussi mais envoie Ctrl-C (deux de suite quittent Claude). Le texte du tour interrompu revient dans la zone de saisie : avant d'écrire un message, l'effacer (`\x15`) |
| Quota 5 h / 7 j | La statusline reçoit `rate_limits` : `five_hour` et `seven_day`, chacun `used_percentage` et `resets_at` (secondes) ; absent tant qu'aucune réponse de l'API n'est arrivée dans la session. Aucun hook ne le donne |

## Windows (30/10/2026, Claude Code 2.1.260 et 2.1.309, PC de Bruno)

- `claude agents --json` : même format, 0,9 à 1,3 s par appel (0,24 s sur Mac) : relecture toutes les 8 s sous Windows.
- Les hooks `command` sont lancés par **Git Bash** (essai : un `SessionStart` dans un `--settings` à part, `CLAUDE_CONFIG_DIR`
  vide, sans compte : rien n'est facturé) : `BASH_VERSION` 5.2.37, `SHELL` = `/bin/bash.exe`. La statusline enveloppée en
  `sh` marche donc telle quelle (supposé : même lanceur que les hooks).
- Claude Desktop lance la CLI (`claude.exe`) sur une console cachée : sa fenêtre est celle de Claude Desktop, parent direct.
- Sous Windows 11, une console ouverte depuis le menu Démarrer est confiée à Windows Terminal (« terminal par défaut ») :
  Terminal n'est pas un parent du shell. La console d'un processus donne pourtant sa fenêtre : pseudo-fenêtre ConPTY
  (`PseudoConsoleWindow`, qui se dit visible) dont le propriétaire est la fenêtre de Terminal.
- Claude Code règle le titre du terminal (titre de session, titre généré, sinon « Claude Code ») : seul moyen de savoir
  quel onglet de Terminal est affiché. Titre réel d'une session dans Terminal : pas encore vu.
- Détail et règles : [spec](superpowers/specs/2026-10-30-agents-windows-design.md).

## Questions pour toi

1. **Positionnement.** Orca a une appli mobile, Claude Code a Remote Control. On vend la tablette comme écran de bureau local, sans compte et sans nuage ?
2. **Où poser les hooks ?** `~/.claude/settings.json` global (toutes les sessions, y compris celles d'Orca, avec sauvegarde), ou seulement dans certains projets ?
3. **Délai d'attente avant retour au terminal** : 90 s ? Et la tablette ne répond-elle que quand tu es « absent » (réglage, ou détection par écran de l'ordinateur verrouillé) ?
4. **Quelles permissions la tablette peut-elle autoriser ?** Tout avec appui maintenu pour le risqué (proposé), ou jamais `Bash` ?
5. **Réveil de l'écran** pour une alerte, malgré l'atténuation de nuit et le kiosque ?
6. **Mode privé par défaut** pour les salariés en réunion (écran partagé), ou seulement sur demande ?
7. **Claude seulement**, ou aussi Codex et les autres agents d'Orca (`worktree ps` les voit, mais pas les hooks) ?
8. **Angle de la démo** : développeur multi-sessions, ou joueur qui lance un agent et joue pendant ce temps ?
9. **Plans à approuver** (`ExitPlanMode`) dans la première version, ou plus tard ?
10. **Nom** de l'onglet et du widget (« Agents », « Sessions », « Claude » ?).

## Sources

- Documentation Claude Code, lue le 28/10/2026 : [hooks](https://code.claude.com/docs/en/hooks), [agent view](https://code.claude.com/docs/en/agent-view), [permissions](https://code.claude.com/docs/en/permissions), [permission modes](https://code.claude.com/docs/en/permission-modes), [statusline](https://code.claude.com/docs/en/statusline), [sessions](https://code.claude.com/docs/en/sessions), [remote control](https://code.claude.com/docs/en/remote-control), [channels](https://code.claude.com/docs/en/channels-reference), [SDK : entrée utilisateur](https://code.claude.com/docs/en/agent-sdk/user-input).
- Orca : [appli mobile](https://www.onorca.dev/docs/mobile), [notifications](https://www.onorca.dev/docs/notifications), CLI testée en local.
- Projets : liens du tableau.
