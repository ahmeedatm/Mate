export const meta = {
  name: 'tache',
  description: "Tâche cadrée du compagnon de bout en bout : worktree, code et vérif rapide, /ship jusqu'à l'essai publié",
  whenToUse:
    "Après le go du cadrage (.claude/rules/autonomie-hackathon.md, étape 2), ou pour un retour sur l'essai. args = {demande: récap validé ou retour à corriger, branche?: 'feat/x', worktree?: chemin du worktree existant}. Rend {message} : le message de fin d'essai, à relayer tel quel.",
  phases: [
    { title: 'Préparer', detail: 'worktree Orca + branche depuis origin/main' },
    { title: 'Coder', detail: 'implémentation, tests écrits, format + typecheck' },
    { title: 'Livrer', detail: "/ship étapes 1 à 6 : commit, push, MR, essai publié pendant le pipeline" },
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
    erreur: str,
  },
  required: ['ok', 'resume', 'fichiers', 'aTester', 'nonTestableEssai', 'choix'],
}
const SHIP = {
  type: 'object',
  properties: {
    ok: { type: 'boolean' },
    mr: { type: 'integer' },
    url: str,
    pipeline: { type: 'string', description: 'success, running, ou failed suivi du job en échec' },
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

phase('Coder')
const code = await agent(
  `Worktree : ${wt}. Travaille uniquement dedans, en chemins absolus.

Tâche validée avec l'utilisateur. Ne pose aucune question : un choix imprévu → le défaut le plus simple, noté dans "choix".
${A.demande}

- Conventions du CLAUDE.md du projet (couleurs ?attr/c_…, textes dans strings.xml, errorText(e), pas de nouvelle dépendance sans nécessité).
- Tests : comportement backend → backend/test/, page web du backend → backend/e2e/, logique pure Android → app/src/test/. Écris-les, ne les lance pas : la CI s'en charge.
- Vérif locale = format + typecheck seulement : table de l'étape 2 de ${REPO}/.claude/skills/ship/SKILL.md. node_modules absent : npm ci d'abord. Pas d'émulateur, pas de lintDebug.
- Ne commit pas, ne pousse pas.
ok=false seulement si la vérif locale reste rouge.`,
  { label: 'code', agentType: 'implementer', schema: CODE },
)
if (!code || !code.ok) {
  return { message: `❌ Tâche arrêtée : vérif locale rouge (${(code && (code.erreur || code.verifs)) || 'agent sans réponse'}). Worktree : ${wt}` }
}

phase('Livrer')
const ship = await agent(
  `Livre le travail du worktree ${wt} (branche ${branche || 'courante'}) en suivant ${REPO}/.claude/skills/ship/SKILL.md, étapes 1 à 6 exactement. Lis le fichier d'abord, travaille depuis ce worktree.
- Changement : ${code.resume}
- Fichiers : ${code.fichiers.join(', ')}
- MR (seulement si elle n'existe pas) : titre court en français ; template du dépôt rempli ; « Testé sur » : rien de coché (l'utilisateur teste l'essai)${code.choix.length ? ` ; choix imprévus à écrire dans la description : ${code.choix.join(' ; ')}` : ''}.
- Juste après le push : essai.sh au premier plan (timeout Bash 600000), sans attendre le pipeline de la MR. Puis lis l'état du pipeline de la MR, sans l'attendre s'il tourne encore : "pipeline" = success, running, ou failed suivi du job en échec.
- Essai rouge : lire le log, corriger, revérifier en local, nouveau commit, repousser, relancer essai.sh. 2 tentatives au plus. Pipeline de la MR rouge : ne corrige pas ici (la fusion l'exige vert, étape 7), ok reste true si l'essai est publié.
- Arrête-toi avant l'étape 7 : pas de fusion, pas de message à l'utilisateur.`,
  { label: 'ship', agentType: 'claude', model: SONNET, schema: SHIP },
)

if (!ship || !ship.ok) {
  return { message: `❌ Livraison bloquée : ${(ship && ship.raison) || 'agent sans réponse'}.${ship && ship.url ? ` MR : ${ship.url}` : ''}`, worktree: wt }
}
const fin = []
if (code.nonTestableEssai) fin.push(`⚠️ ${code.nonTestableEssai}`)
if (/^failed/.test(ship.pipeline || '')) fin.push(`⚠️ pipeline de la MR rouge (${ship.pipeline}) : à corriger avant la fusion`)
const message = ship.essai
  ? [
      `C'est fini. Essai ${ship.essai} à installer (MR !${ship.mr}).`,
      "Installer : Réglages > Mises à jour > Rechercher, « Mettre à jour » sur Companion essai, puis « Ouvrir l'essai ».",
      `Tester : ${code.aTester}`,
      ...fin,
    ].join('\n')
  : [`C'est fini, sans essai (${ship.raison || 'rien à installer sur la tablette'}). MR !${ship.mr} : ${ship.url}, pipeline ${ship.pipeline}.`, ...fin].join('\n')

return { message, worktree: wt, branche, mr: ship.mr, url: ship.url }
