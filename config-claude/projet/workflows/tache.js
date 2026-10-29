export const meta = {
  name: 'tache',
  description: "Tâche cadrée du compagnon de bout en bout : worktree, code et tests, émulateur et relecture, /ship jusqu'à l'essai publié",
  whenToUse:
    "Après le go du cadrage (.claude/rules/autonomie-hackathon.md, étape 2), ou pour un retour sur l'essai. args = {demande: récap validé ou retour à corriger, branche?: 'feat/x', worktree?: chemin du worktree existant}. Rend {message} : le message de fin d'essai, à relayer tel quel.",
  phases: [
    { title: 'Préparer', detail: 'worktree Orca + branche depuis origin/main' },
    { title: 'Coder', detail: 'implémentation, tests, vérif locale' },
    { title: 'Vérifier', detail: 'émulateur companion_test_api29 + relecture du diff, 2 corrections au plus' },
    { title: 'Livrer', detail: '/ship étapes 1 à 6 : commit, push, MR, pipeline vert, essai publié' },
  ],
}

const A = typeof args === 'string' ? (() => { try { return JSON.parse(args) } catch (e) { return { demande: args } } })() : (args || {})
if (!A.demande) throw new Error("args.demande manquant : le récap validé au cadrage (ce qui sera fait, hors sujet, comment tester).")

const REPO = '@REPO@'
const SONNET = 'sonnet'
const str = { type: 'string' }
const strs = { type: 'array', items: str }

const PREP = {
  type: 'object',
  properties: { ok: { type: 'boolean' }, worktree: str, branche: str, erreur: str },
  required: ['ok', 'worktree', 'branche'],
}
const CODE = {
  type: 'object',
  properties: {
    ok: { type: 'boolean' },
    resume: str,
    fichiers: strs,
    verifs: { type: 'string', description: 'commandes de vérif locale lancées et leur résultat' },
    aTester: { type: 'string', description: "1 à 3 lignes pour l'utilisateur : où aller, quel geste, ce qui doit se passer" },
    nonTestableEssai: { type: 'string', description: "ce qui ne se teste pas dans l'essai (device owner : Mises à jour, WiFi, Kiosque ; Mac à relancer), vide sinon" },
    choix: { type: 'array', items: str, description: 'choix imprévus faits en route' },
    toucheApp: { type: 'boolean', description: "app/ ou l'affichage sur la tablette est touché" },
    erreur: str,
  },
  required: ['ok', 'resume', 'fichiers', 'aTester', 'nonTestableEssai', 'choix', 'toucheApp'],
}
const EMU = {
  type: 'object',
  properties: { ok: { type: 'boolean' }, constat: str, captures: strs },
  required: ['ok', 'constat'],
}
const REV = {
  type: 'object',
  properties: {
    bloquants: {
      type: 'array',
      items: { type: 'object', properties: { fichier: str, probleme: str }, required: ['fichier', 'probleme'] },
    },
  },
  required: ['bloquants'],
}
const SHIP = {
  type: 'object',
  properties: {
    ok: { type: 'boolean' },
    mr: { type: 'integer' },
    url: str,
    pipeline: str,
    essai: { type: 'string', description: 'version X.Y.Z-essai.N annoncée, vide si pas d\'essai' },
    raison: { type: 'string', description: "pourquoi pas d'essai, ou ce qui bloque" },
  },
  required: ['ok', 'essai'],
}

phase('Préparer')
let wt = A.worktree
let branche = A.branche || ''
if (!wt) {
  const prep = await agent(
    `Crée le worktree d'une tâche du dépôt ${REPO}, rien d'autre.
1. \`git -C ${REPO} fetch --prune origin\`.
2. \`orca worktree create --repo path:${REPO} --name <slug> --no-parent --json\`, slug court tiré de la demande.
3. Dans le worktree : \`git branch -m ${A.branche || '<type>/<description-courte>'}\`${A.branche ? '' : ' (type : feat, fix, docs, test ou ci, selon la demande)'}.
4. Vérifie que HEAD = origin/main.
Rends le chemin absolu du worktree et la branche.

Demande : ${A.demande}`,
    { label: 'worktree', agentType: 'claude', model: SONNET, effort: 'low', schema: PREP },
  )
  if (!prep || !prep.ok) throw new Error('Worktree non créé : ' + ((prep && prep.erreur) || 'agent sans réponse'))
  wt = prep.worktree
  branche = prep.branche
}
log(`worktree ${wt}`)

const coder = (retour) =>
  agent(
    `Worktree : ${wt}. Travaille uniquement dedans, en chemins absolus.

Tâche validée avec l'utilisateur. Ne pose aucune question : un choix imprévu → le défaut le plus simple, noté dans "choix".
${A.demande}
${retour ? `\nÀ corriger d'abord (vérification précédente) :\n${retour}\n` : ''}
- Conventions du CLAUDE.md du projet (couleurs ?attr/c_…, textes dans strings.xml, errorText(e), pas de nouvelle dépendance sans nécessité).
- Tests : comportement backend → backend/test/, page web du backend → backend/e2e/, logique pure Android → app/src/test/.
- Vérif locale selon les dossiers touchés : table de l'étape 2 de ${REPO}/.claude/skills/ship/SKILL.md, formatage compris. node_modules absent : npm ci d'abord.
- Ne commit pas, ne pousse pas, ne lance pas d'émulateur (une autre étape s'en charge).
ok=false seulement si la vérif locale reste rouge.`,
    { label: retour ? 'correction' : 'code', phase: 'Coder', agentType: 'implementer', schema: CODE },
  )

const verifier = (code) =>
  parallel([
    () =>
      code.toucheApp
        ? agent(
            `Vérifie ce changement sur l'émulateur, sans modifier le code.
Worktree : ${wt}
Changement : ${code.resume}
Ce qui doit se passer : ${code.aTester}

- Émulateur réservé : AVD companion_test_api29, port 5600 (adb -s emulator-5600). Jamais emulator-5570 ni un autre : autres sessions. Démarre-le s'il ne tourne pas.
- APK debug construit et installé depuis le worktree (GRADLE_USER_HOME=/tmp/gh-companion). Backend de démo si besoin, lancé depuis le worktree avec un DATA_DIR à part.
- Captures : adb -s emulator-5600 exec-out screencap -p > /tmp/…png, regarde-les.
- Arrête seulement ce que tu as lancé, par le PID noté. Jamais pkill -f, jamais par port.
ok=false avec le constat précis si ce qui doit se passer ne se passe pas.`,
            { label: 'émulateur', phase: 'Vérifier', agentType: 'claude', model: SONNET, schema: EMU },
          )
        : Promise.resolve({ ok: true, constat: 'pas touché à app/' }),
    () =>
      agent(
        `Relis le changement en cours du worktree ${wt}, en lecture seule : \`git -C ${wt} diff origin/main\` et les fichiers non suivis.
Demande : ${A.demande}
Ne remonte que le bloquant, avec fichier:ligne : bug de correction, demande non couverte, test manquant, règle du CLAUDE.md du projet violée (couleur en dur, e.message affiché, texte hors strings.xml, dépendance ajoutée). Rien de cosmétique. Liste vide si rien.`,
        { label: 'relecture', phase: 'Vérifier', agentType: 'claude', model: SONNET, schema: REV },
      ),
  ])

phase('Coder')
let code = await coder('')
const alertes = []
for (let tour = 0; ; tour++) {
  if (!code || !code.ok) {
    return { message: `❌ Tâche arrêtée : vérif locale rouge (${(code && (code.erreur || code.verifs)) || 'agent sans réponse'}). Worktree : ${wt}` }
  }
  phase('Vérifier')
  const [emu, rev] = await verifier(code)
  const problemes = []
  if (emu === null) alertes.push("vérif sur l'émulateur non faite")
  else if (!emu.ok) problemes.push(`Émulateur : ${emu.constat}`)
  if (rev) rev.bloquants.forEach((b) => problemes.push(`${b.fichier} : ${b.probleme}`))
  if (!problemes.length) break
  if (tour >= 2) {
    return { message: `⚠️ Pas livré : encore rouge après 2 corrections.\n${problemes.map((p) => '- ' + p).join('\n')}\nWorktree : ${wt}` }
  }
  log(`${problemes.length} problème(s), correction ${tour + 1}/2`)
  phase('Coder')
  code = await coder(problemes.join('\n'))
}

phase('Livrer')
const ship = await agent(
  `Livre le travail du worktree ${wt} (branche ${branche || 'courante'}) en suivant ${REPO}/.claude/skills/ship/SKILL.md, étapes 1 à 6 exactement. Lis le fichier d'abord, travaille depuis ce worktree.
- Changement : ${code.resume}
- Fichiers : ${code.fichiers.join(', ')}
- MR (seulement si elle n'existe pas) : titre court en français ; template du dépôt rempli ; « Testé sur » : émulateur coché seulement s'il a été vérifié${alertes.length ? ' (il ne l\'a pas été)' : ''}${code.choix.length ? ` ; choix imprévus à écrire dans la description : ${code.choix.join(' ; ')}` : ''}.
- Attente du pipeline au premier plan : \`wait-until.sh --timeout 540\`, relancé tant qu'il sort 124, 30 min au plus. essai.sh au premier plan, timeout Bash 600000.
- Pipeline rouge : lire le log, corriger, revérifier en local, nouveau commit, repousser. 3 tentatives au plus.
- Arrête-toi avant l'étape 7 : pas de fusion, pas de message à l'utilisateur.`,
  { label: 'ship', agentType: 'claude', model: SONNET, schema: SHIP },
)

if (!ship || !ship.ok) {
  return { message: `❌ Livraison bloquée : ${(ship && ship.raison) || 'agent sans réponse'}.${ship && ship.url ? ` MR : ${ship.url}` : ''}`, worktree: wt }
}
if (code.nonTestableEssai) alertes.push(code.nonTestableEssai)
const fin = alertes.map((a) => `⚠️ ${a}`)
const message = ship.essai
  ? [
      `C'est fini. Essai ${ship.essai} à installer (MR !${ship.mr}).`,
      "Installer : Réglages > Mises à jour > Rechercher, « Mettre à jour » sur Companion essai, puis « Ouvrir l'essai ».",
      `Tester : ${code.aTester}`,
      ...fin,
    ].join('\n')
  : [`C'est fini, sans essai (${ship.raison || 'rien à installer sur la tablette'}). MR !${ship.mr} : ${ship.url}, pipeline ${ship.pipeline}.`, ...fin].join('\n')

return { message, worktree: wt, branche, mr: ship.mr, url: ship.url }
