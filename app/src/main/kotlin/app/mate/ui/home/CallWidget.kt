package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.DeckPage
import app.mate.net.DeckTile
import app.mate.net.VoiceMember
import app.mate.net.VoiceState
import app.mate.net.runCatchingCancellable
import app.mate.ui.AppAudioView
import app.mate.ui.CallSession
import app.mate.ui.IconCache
import app.mate.ui.Look
import app.mate.ui.errorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * État de l'appel et touches d'une appli d'appel (page Discord ou Réunion du Deck) sur un profil. Les touches sont
 * celles de la page, montrées selon l'appel (`tileShown`) et allumées quand le micro est coupé (`tileLit`). L'appel est
 * relu toutes les 4 s tant que le profil est affiché, et 700 ms après un toucher. Pas de bouton Décrocher : la carte
 * d'appel plein écran s'en charge.
 *
 * Une mise en page par famille de formes (`planCall`, #184). Télécommande (Réunion, Appel) : la Pastille est la touche
 * de l'état, la Ligne et la Colonne des touches en rangée ou en grille, le Bloc ajoute les participants et le son propre
 * à l'appli, Grand et Plein écran agrandissent les touches. Îlot (Discord) : un gros rond pour la touche de l'état, les
 * autres en petits ronds, un halo couleur du texte autour du gros rond quand quelqu'un parle (lisible même sur la touche
 * rouge du micro coupé, quel que soit l'accent du thème) ; les photos en colonne dès la
 * Colonne, celui qui parle en grand en Plein écran. Les tailles des touches suivent la hauteur réelle de la carte.
 *
 * Discord avec le RPC de l'ordinateur autorisé : les participants sont des photos, avec un anneau autour de qui parle
 * (relu toutes les 500 ms pendant l'appel) ; pas encore autorisé : un bouton « Montrer les photos » ouvre « Autoriser »
 * dans Discord sur l'ordinateur.
 */
abstract class CallWidget(
    context: Context,
    host: HomeHost,
    placed: Placed,
    private val select: (DeckPage) -> Boolean,
) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(12).apply { orientation = LinearLayout.VERTICAL }

    /** Îlot (Discord) : ronds et halo ; sinon télécommande (Réunion, Appel). */
    protected open val island: Boolean get() = false

    protected var page: DeckPage? = null
        private set

    /** Les pages ont été lues au moins une fois : avant, ou pendant une panne, l'absence de page n'est pas un constat. */
    protected var read = false
        private set
    protected var voice: VoiceState? = null
        private set
    protected var active = false
        private set
    private var poll: Job? = null

    /** Aperçu du catalogue : l'exemple reste affiché, rien ne part sur le réseau. */
    protected var preview = false
        private set

    private var plan = planCall(ShapeFamily.PILL, island = false, emptyList())

    /** Touches dessinées (grille ou ronds), avec leur allumage et leurs cotes : refaites seulement si elles changent. */
    private var drawn: Any? = null

    /** Place utile de la carte, en dp, relevée à chaque mise en page : les touches s'y règlent. */
    protected var innerW = 0
        private set
    protected var innerH = 0
        private set

    private val title = cardTitle(placed.type.title)
    private val state = label(14f, R.attr.c_text_dim).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
    private val flag = label(14f, R.attr.c_ko).apply { isSingleLine = true }
    private val people = label(14f, R.attr.c_text_dim).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }

    /** Sous le gros rond de la Pastille de l'îlot : qui parle, sinon l'état. */
    private val caption = label(14f, R.attr.c_text_dim).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }
    private val appAudio = AppAudioView(context).apply { attach(host.scope) }
    private val memberRow = MemberRowView(context).apply { photoOf = IconCache::peekDiscord }
    private val bigRow = MemberRowView(context, BIG_PHOTO_DP).apply { photoOf = IconCache::peekDiscord }
    private val memberColumn = MemberRowView(context, vertical = true).apply { photoOf = IconCache::peekDiscord }
    private val featured = MemberRowView(context, FEATURED_PHOTO_DP).apply { photoOf = IconCache::peekDiscord }
    private val featuredName = label(22f).apply { gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }

    /** Rangées de photos de la mise en page en cours (Plein écran de l'îlot : celui qui parle à part, dans [featured]). */
    private var rows: List<MemberRowView> = emptyList()
    private val photosButton = Button(context, null, 0, R.style.ActionButton).apply {
        setText(R.string.call_photos_show)
        setOnClickListener { authorizePhotos() }
    }

    /** « Montrer les photos » touché : on attend le clic sur « Autoriser » dans Discord. */
    protected var authorizing = false
        private set
    private var speakPoll: Job? = null
    protected var speaking: Set<String> = emptySet()
        private set

    /** Relectures de « qui parle » ratées de suite : au-delà de 3, plus personne n'est montré parlant (pas d'anneau figé). */
    private var speakingFailures = 0

    /** Dernier instant où chacun a été vu parler (ordre d'un salon plein), oublié à la fin de l'appel. */
    private val lastSpoke = HashMap<String, Long>()
    private val loadingPhotos = HashSet<String>()

    private val grid = TileGridView(context, host.scope).apply {
        onTap = { cell, _ -> page?.tiles?.firstOrNull { it.id == cell.key.id }?.let(::run) }
    }

    /** État de l'appel, sous le titre. */
    private val stateRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        addView(state, LinearLayout.LayoutParams(0, wrap, 1f))
        addView(flag, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
    }

    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(stateRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
    }

    /** Place sous l'en-tête : la grille y est centrée en hauteur (plus de vide sous elle en Grand). */
    private val keys = FrameLayout(context).apply {
        addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val density = context.resources.displayMetrics.density
            val w = ((r - l) / density).roundToInt()
            val h = ((b - t) / density).roundToInt()
            if (w == gridW && h == gridH) return@addOnLayoutChangeListener
            gridW = w
            gridH = h
            v.post { renderKeys() }
        }
    }

    /** Place de la grille de la télécommande, en dp : la cote des touches s'y règle (`fitGrid`). */
    private var gridW = 0
    private var gridH = 0

    /** Touches une à une : la Pastille, la rangée de la Ligne, les petits ronds de l'îlot (avec le gros au milieu). */
    private val keyRow = LinearLayout(context).apply { gravity = Gravity.CENTER }

    /** Gros rond de l'îlot (ou touche seule de la Pastille), avec le halo derrière. */
    private val mainFrame = FrameLayout(context)
    private val halo = View(context).apply { alpha = 0f }
    private var haloOn = false

    init {
        view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val density = context.resources.displayMetrics.density
            val w = ((r - l - v.paddingLeft - v.paddingRight) / density).roundToInt()
            val h = ((b - t - v.paddingTop - v.paddingBottom) / density).roundToInt()
            if (w == innerW && h == innerH) return@addOnLayoutChangeListener
            innerW = w
            innerH = h
            // Pas de changement de vues pendant une mise en page : au tour suivant.
            v.post { onCardSize() }
        }
    }

    override fun applySize() {
        listOf(header, people, memberRow, bigRow, memberColumn, featured, featuredName, photosButton, keys, keyRow, mainFrame, caption, appAudio)
            .forEach { (it.parent as? ViewGroup)?.removeView(it) }
        view.removeAllViews()
        view.gravity = Gravity.NO_GRAVITY
        featured.centered = false
        bigRow.centered = false
        memberRow.centered = false
        keyRow.gravity = Gravity.CENTER
        // Place utile relevée à nouveau : pas de touches dessinées aux cotes de la forme d'avant.
        innerW = 0
        innerH = 0
        gridW = 0
        gridH = 0
        plan = planCall(family, island, shownTiles())
        when {
            family == ShapeFamily.PILL -> pillLayout()
            family == ShapeFamily.ROW -> rowLayout()
            island -> islandLayout()
            else -> remoteLayout()
        }
        appAudio.bind(page?.app.takeIf { plan.audio })
        drawn = null
        render()
    }

    private fun pillLayout() {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        view.gravity = Gravity.CENTER
        view.addView(header, LinearLayout.LayoutParams(match, wrap))
        // Télécommande : la touche prend la largeur, son libellé tient sur une ligne.
        view.addView(mainFrame, LinearLayout.LayoutParams(if (island) wrap else match, wrap))
        if (island) view.addView(caption, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        rows = emptyList()
    }

    private fun rowLayout() {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        // Télécommande : plaques alignées en haut, qu'un libellé tienne sur une ligne ou deux.
        if (!island) keyRow.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        line.addView(header, LinearLayout.LayoutParams(0, wrap, 1f))
        line.addView(keyRow, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        view.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rows = emptyList()
    }

    /** Colonne, Bloc, Grand et Plein écran de la télécommande : en-tête, participants, grille, son de l'appli. */
    private fun remoteLayout() {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        view.addView(header, LinearLayout.LayoutParams(match, wrap))
        val row = if (plan.bigKeys) bigRow else memberRow
        if (plan.members) {
            view.addView(people, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
            view.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6); bottomMargin = dp(6) })
            view.addView(photosButton, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(6) })
        }
        rows = if (plan.members) listOf(row) else emptyList()
        (grid.parent as? ViewGroup)?.removeView(grid)
        keys.addView(grid, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER_VERTICAL))
        view.addView(keys, LinearLayout.LayoutParams(match, 0, 1f))
        if (plan.audio) view.addView(appAudio, LinearLayout.LayoutParams(match, wrap))
    }

    /** Colonne, Bloc, Grand et Plein écran de l'îlot : en-tête, photos, ronds en bas, son de l'appli. */
    private fun islandLayout() {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        view.addView(header, LinearLayout.LayoutParams(match, wrap))
        view.addView(people, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
        val star = family == ShapeFamily.FULL || (family == ShapeFamily.LARGE && placed.size.h >= FEATURED_MIN_ROWS)
        when {
            family == ShapeFamily.COLUMN -> {
                view.addView(memberColumn, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(8) })
                rows = listOf(memberColumn)
            }
            // Plein écran, et Grand assez haut : celui qui parle en grand, les autres en rangée dessous.
            star -> {
                val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                center.addView(featured, LinearLayout.LayoutParams(match, wrap))
                center.addView(featuredName, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
                center.addView(bigRow, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(16) })
                view.addView(center, LinearLayout.LayoutParams(match, 0, 1f))
                rows = listOf(bigRow)
                featured.centered = true
                bigRow.centered = true
            }
            else -> {
                val row = if (family == ShapeFamily.LARGE) bigRow else memberRow
                row.centered = true
                view.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
                view.addView(View(context), LinearLayout.LayoutParams(match, 0, 1f))
                rows = listOf(row)
            }
        }
        view.addView(photosButton, LinearLayout.LayoutParams(wrap, wrap).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        if (family == ShapeFamily.COLUMN) view.addView(mainFrame, LinearLayout.LayoutParams(wrap, wrap).apply { gravity = Gravity.CENTER_HORIZONTAL })
        view.addView(keyRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(if (family == ShapeFamily.COLUMN) 12 else 0) })
        if (plan.audio) view.addView(appAudio, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
    }

    /** Aperçu du catalogue : un appel d'exemple, sans réseau ; il tient si [applySize] repasse. */
    override fun showPreview(sample: WidgetSample) {
        val call = (sample as? CallSample)?.build(context) ?: return
        preview = true
        read = true
        page = call.page
        voice = call.voice
        noteSpeaking(call.speaking)
        applySize()
    }

    /** Relit les pages (cache) : la page peut apparaître, changer de touches ou disparaître du Deck. */
    override suspend fun load() {
        if (preview) return
        if (readPage()) render()
    }

    /** Page à relire à chaque minute, l'appel toutes les 4 s tant que le profil est affiché. */
    override fun setActive(active: Boolean) {
        this.active = active && !preview
        appAudio.setActive(this.active)
        if (!this.active) {
            poll?.cancel()
            poll = null
            speakPoll?.cancel()
            speakPoll = null
            return
        }
        if (poll?.isActive == true) return
        poll = host.scope.launch {
            if (page == null) readPage()
            while (isActive) {
                readVoice()
                delay(POLL_MS)
            }
        }
    }

    /** Page de ce widget parmi celles du deck. */
    protected open fun pickPage(pages: List<DeckPage>): DeckPage? = pages.firstOrNull(select)

    /** Vrai si les pages ont été lues (même sans page pour ce widget : l'état d'appel est alors oublié). */
    private suspend fun readPage(): Boolean {
        val pages = runCatchingCancellable { host.deckPages() }.getOrNull() ?: return false
        read = true
        val next = pickPage(pages)
        if (next?.app != page?.app) voice = null
        page = next
        appAudio.bind(next?.app.takeIf { plan.audio })
        return true
    }

    /** Appel relu ; une erreur réseau garde l'état d'avant (pas de clignotement), l'appli fermée donne `null`. */
    private suspend fun readVoice() {
        val app = page?.app
        if (app != null) {
            runCatchingCancellable { BackendClient(context).deck.voice(app) }.onSuccess {
                voice = it
                it?.members?.let { members -> noteSpeaking(members.filter(VoiceMember::speaking).map(VoiceMember::id).toSet()) }
            }
        }
        render()
    }

    protected fun shownTiles() = page?.tiles.orEmpty().filter { tileShown(it, voice) }

    protected open fun render() {
        plan = planCall(family, island, shownTiles())
        renderKeys()
        // Pastille : le titre seulement sans touche (il dit ce qu'est le widget) ; la touche dit à quoi elle sert.
        header.visibility = if (plan.header) View.VISIBLE else View.GONE
        renderState()
    }

    /** Touches de la mise en page : la grille de la télécommande, ou les touches une à une (Pastille, Ligne, îlot). */
    private fun renderKeys() {
        val lit = { tile: DeckTile -> tile to tileLit(tile, voice) }
        if (keys.parent != null) {
            // De la plus grande cote à la plus petite : la première où toutes les touches tiennent, sinon « Quitter » reste.
            val choices = listOfNotNull(
                FULL_KEYS.takeIf { family == ShapeFamily.FULL },
                TileMetrics.LARGE.takeIf { plan.bigKeys },
                TileGridView.metrics(context),
                TileMetrics.NORMAL,
                SMALL_KEYS,
            ).distinct()
            val fit = if (gridH == 0) GridFit(choices.lastIndex, plan.keys.size) else fitGrid(plan.keys.size, gridW, gridH, choices.map(::cutOf), GRID_GAP_DP)
            val metrics = choices[fit.cut]
            val next = fitKeys(plan.keys, fit.count).map(lit)
            if (listOf(next, metrics) == drawn) return
            drawn = listOf(next, metrics)
            grid.cellMetrics = metrics
            grid.cells = next.map { (tile, on) -> TileGridView.Cell(KeyRef.of(tile), on, type = tile.type) }
            return
        }
        if (innerH == 0) return
        val sizes = callKeySizes(family, island, innerW, innerH, labelLineDp, plan.keys.size)
        val main = plan.main?.let(lit)
        val small = fitKeys(plan.keys, sizes.count).map(lit)
        val next = listOf(main, small, sizes)
        if (next == drawn) return
        drawn = next
        mainFrame.removeAllViews()
        keyRow.removeAllViews()
        if (main != null) {
            val ring = sizes.main + 2 * (CallKeyMetrics.HALO_GAP_DP + CallKeyMetrics.HALO_RING_DP)
            if (island) {
                halo.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(CallKeyMetrics.HALO_RING_DP), color(R.attr.c_text))
                }
                mainFrame.addView(halo, FrameLayout.LayoutParams(dp(ring), dp(ring), Gravity.CENTER))
            }
            val width = if (island) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
            mainFrame.addView(keyView(main, sizes.main, sizes.mainLabel), FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        // Le gros rond va au milieu des petits (îlot en Ligne, Bloc, Grand, Plein écran), en tête de la Ligne.
        val inline = island && main != null && family != ShapeFamily.PILL && family != ShapeFamily.COLUMN
        val half = if (family == ShapeFamily.ROW) 0 else (small.size + 1) / 2
        small.forEachIndexed { i, key ->
            if (inline && i == half) keyRow.addView(mainFrame, keyParams())
            keyRow.addView(keyView(key, sizes.small, sizes.smallLabel), keyParams(if (!island) sizes.width else 0))
        }
        if (inline && mainFrame.parent == null) keyRow.addView(mainFrame, keyParams())
        renderHalo()
    }

    private fun keyParams(width: Int = 0) = LinearLayout.LayoutParams(if (width > 0) dp(width) else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(CallKeyMetrics.KEY_GAP_DP / 2)
        marginEnd = dp(CallKeyMetrics.KEY_GAP_DP / 2)
    }

    /** Cote d'une case de la grille, en dp : la plaque et deux lignes de libellé, au moins `cellDp` (comme `TileGridView`). */
    private fun cutOf(metrics: TileMetrics): GridCut {
        val line = lineDp.getOrPut(metrics.labelSp) { (TextView(context).apply { textSize = metrics.labelSp }.lineHeight / context.resources.displayMetrics.density).roundToInt() }
        return GridCut(metrics.columnDp, maxOf(metrics.cellDp, metrics.plateDp + TileMetrics.LABEL_GAP_DP + 2 * line))
    }

    private val lineDp = HashMap<Float, Int>()

    /** Hauteur d'une ligne de libellé, en dp : relue une fois (elle suit la taille du texte des Réglages). */
    private val labelLineDp by lazy { (TextView(context).apply { textSize = KEY_LABEL_SP }.lineHeight / context.resources.displayMetrics.density).roundToInt() }

    /** Une touche de la page, au dessin de la grille (`TileGridView.bind`) : ronde dans l'îlot, libellé si la place le permet. */
    private fun keyView(entry: Pair<DeckTile, Boolean>, plateDp: Int, withLabel: Boolean): View {
        val (tile, on) = entry
        val metrics = TileMetrics(plateDp, plateDp, plateDp, KEY_LABEL_SP, plateDp * INITIAL_SHARE, round = island)
        val cell = TileGridView.newCell(context, metrics)
        TileGridView.bind(cell, TileGridView.Cell(KeyRef.of(tile), on, type = tile.type), host.scope)
        cell.findViewById<TextView>(R.id.tile_label).visibility = if (withLabel) View.VISIBLE else View.GONE
        // L'état allumé (micro coupé, caméra coupée) se dit aussi, pas seulement par la couleur de la plaque.
        cell.contentDescription = if (on) context.getString(R.string.call_key_on, tile.label) else tile.label
        cell.setOnClickListener { run(tile) }
        Look.pressSpring(cell)
        return cell
    }

    /** Halo de l'îlot : quelqu'un parle dans l'appel. Allumé en 120 ms, éteint en 300 ms, sans boucle. */
    private fun renderHalo() {
        val on = island && voice?.connected == true && speaking.isNotEmpty()
        if (on == haloOn) return
        haloOn = on
        halo.animate().cancel()
        halo.animate().alpha(if (on) 1f else 0f).setDuration(if (on) HALO_IN_MS else HALO_OUT_MS).start()
    }

    protected open fun renderState() {
        val v = voice
        val inCall = v?.connected == true
        val page = page
        state.text = when {
            page == null -> if (read) context.getString(R.string.call_no_page) else ""
            inCall -> listOfNotNull(
                context.getString(R.string.deck_voice_channel, v?.channel ?: page.app.orEmpty()),
                // Les participants ont leur place à partir du Bloc (photos ou « Avec … ») : pas deux fois.
                v?.people?.takeIf { it.isNotEmpty() && !plan.members }?.joinToString(", "),
            ).joinToString(" · ")
            v?.lobby == true -> context.getString(R.string.call_lobby)
            else -> context.getString(R.string.call_idle)
        }
        // La sourdine coupe aussi le micro côté Discord : on ne dit que l'essentiel.
        val alert = when {
            !inCall -> null
            v?.deafened == true -> R.string.deck_voice_deafened
            v?.muted == true -> R.string.deck_voice_mic_off
            else -> null
        }
        flag.text = alert?.let { context.getString(it) }.orEmpty()
        // Pastille, Ligne et Colonne de l'îlot : la touche du micro, allumée, le dit déjà (et laisse la place au salon).
        val keyTells = family == ShapeFamily.PILL || family == ShapeFamily.ROW || (island && family == ShapeFamily.COLUMN)
        flag.visibility = if (alert == null || keyTells) View.GONE else View.VISIBLE
        val names = v?.people?.takeIf { inCall && it.isNotEmpty() }
        people.text = names?.let { context.getString(R.string.deck_voice_with, it.joinToString(", ")) }.orEmpty()
        renderPhotos(v, inCall)
        renderCaption()
    }

    /** Pastille de l'îlot : qui parle, sinon l'état de l'appel. */
    private fun renderCaption() {
        val talker = voice?.members?.firstOrNull { it.id in speaking }?.takeIf { voice?.connected == true }
        caption.text = talker?.let { context.getString(R.string.call_speaking, it.name) } ?: state.text
    }

    /** Photos (RPC Discord en appel) à la place de « Avec … », et bouton « Montrer les photos ». */
    private fun renderPhotos(v: VoiceState?, inCall: Boolean) {
        val members = v?.members?.takeIf { inCall && rows.isNotEmpty() && it.isNotEmpty() }
        if (!inCall) {
            forgetSpeakers()
            // Le prochain appel repart d'une rangée vide (aucun anneau resté allumé).
            (rows + featured).forEach { it.show(emptyList(), emptyMap()) }
        }
        val photosShown = if (members != null) View.VISIBLE else View.GONE
        rows.forEach { it.visibility = photosShown }
        featured.visibility = photosShown
        featuredName.visibility = photosShown
        people.visibility = if (members == null && rows.isNotEmpty()) View.VISIBLE else View.GONE
        if (members != null) {
            renderMembers(members)
            loadPhotos(members)
        }
        val photos = v?.photos
        photosButton.visibility = if (rows.isNotEmpty() && inCall && photos != null && photos != "on") View.VISIBLE else View.GONE
        // Le bouton garde sa forme pendant l'attente : son texte dit quoi faire, un nouveau toucher ne relance rien.
        val waiting = authorizing || photos == "waiting"
        photosButton.setText(if (waiting) R.string.call_photos_waiting else R.string.call_photos_show)
        val followed = inCall && !v?.members.isNullOrEmpty() && (members != null || island)
        followSpeaking(followed)
    }

    /** Photos et halo, à chaque changement de « qui parle » ; en Plein écran de l'îlot, celui qui parle en grand. */
    protected open fun renderMembers(members: List<VoiceMember>) {
        val marked = members.map { it.copy(speaking = it.id in speaking) }
        val spoke = lastSpoke.toMap()
        if (featured.parent != null) {
            val star = featuredMember(marked, speaking, spoke)
            featured.show(listOfNotNull(star), spoke)
            featuredName.text = star?.name.orEmpty()
            rows.forEach { it.show(marked - listOfNotNull(star).toSet(), spoke) }
        } else {
            rows.forEach { it.show(marked, spoke) }
        }
        renderHalo()
        renderCaption()
    }

    /** Place utile de la carte relevée ([innerW] × [innerH]) : les touches s'y règlent. */
    protected open fun onCardSize() = renderKeys()

    /** Une photo de participant vient d'arriver. */
    protected open fun photosArrived() = (rows + featured).forEach { it.photosChanged() }

    /** Dernier instant où chacun a été vu parler (ordre d'un salon plein). */
    protected fun spokeAt(): Map<String, Long> = lastSpoke.toMap()

    /** Fin de l'appel : plus personne ne parle, l'ordre d'un salon plein est oublié. */
    protected fun forgetSpeakers() {
        lastSpoke.clear()
        speaking = emptySet()
    }

    private fun noteSpeaking(ids: Set<String>) {
        val now = SystemClock.elapsedRealtime()
        ids.forEach { lastSpoke[it] = now }
        speaking = ids
    }

    /** Qui parle, relu toutes les 500 ms pendant l'appel (photos ou halo à l'écran ; la carte relit l'appel toutes les 4 s). */
    protected fun followSpeaking(on: Boolean) {
        if (!on || !active) {
            speakPoll?.cancel()
            speakPoll = null
            return
        }
        if (speakPoll?.isActive == true) return
        speakPoll = host.scope.launch {
            val client = BackendClient(context)
            while (isActive) {
                delay(SPEAK_POLL_MS)
                val read = runCatchingCancellable { client.discord.speaking() }.getOrNull()
                val ids = when {
                    read != null -> read.also { speakingFailures = 0 }
                    ++speakingFailures >= MAX_SPEAKING_FAILURES -> emptySet()
                    else -> continue
                }
                if (ids == speaking) continue
                noteSpeaking(ids)
                voice?.members?.let(::renderMembers)
            }
        }
    }

    protected fun loadPhotos(members: List<VoiceMember>) {
        if (preview) return
        val missing = members.filter { it.avatar && IconCache.peekDiscord(it.id) == null && it.id !in loadingPhotos }
        if (missing.isEmpty()) return
        val client = BackendClient(context)
        for (m in missing) {
            loadingPhotos += m.id
            host.scope.launch {
                IconCache.loadDiscord(client, m.id)
                loadingPhotos.remove(m.id)
                photosArrived()
            }
        }
    }

    protected fun authorizePhotos() {
        if (authorizing || preview) return
        authorizing = true
        renderState()
        host.scope.launch {
            val result = runCatchingCancellable { BackendClient(context).discord.authorize() }
            authorizing = false
            // Widget plus à l'écran (profil changé pendant l'attente) : ni message, ni relecture.
            if (!active) return@launch
            result.onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            readVoice()
        }
    }

    protected fun run(tile: DeckTile) {
        if (preview) return
        host.scope.launch {
            val result = runTileSpoken(context, tile.id, tile.label)
            result.onFailure {
                Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show()
                return@launch
            }
            result.getOrNull()?.windows?.let { host.overlays.windows(tile, it) }
            // « Quitter l'appel », micro… : l'appel change, on le relit sans attendre la boucle.
            delay(VOICE_AFTER_MS)
            if (active) readVoice()
        }
    }

    private companion object {
        const val POLL_MS = 4_000L
        const val SPEAK_POLL_MS = 500L
        const val MAX_SPEAKING_FAILURES = 3
        const val VOICE_AFTER_MS = 700L

        const val INITIAL_SHARE = 0.45f
        const val KEY_LABEL_SP = 13f
        const val HALO_IN_MS = 120L
        const val HALO_OUT_MS = 300L
        const val BIG_PHOTO_DP = 56
        const val FEATURED_PHOTO_DP = 120

        /** Grand de l'îlot haut d'au moins 3 cases : celui qui parle en grand, comme en Plein écran. */
        const val FEATURED_MIN_ROWS = 3

        /** Touches du Plein écran de la télécommande : plus grandes que les grandes cotes du thème Contraste. */
        val FULL_KEYS = TileMetrics(columnDp = 200, cellDp = 196, plateDp = 112, labelSp = 20f, initialSp = 54f)

        /** Touches serrées d'une grille qui ne tient pas aux cotes normales (rangées basses, texte agrandi). */
        val SMALL_KEYS = TileMetrics(columnDp = 76, cellDp = 72, plateDp = 44, labelSp = 11f, initialSp = 22f)

        /** Espace entre deux cases de la grille (`TileGridView`). */
        const val GRID_GAP_DP = 8
    }
}

/** La page Discord du Deck : l'appli sans la page Réunion. */
val isDiscordPage: (DeckPage) -> Boolean = { it.app.equals("Discord", ignoreCase = true) && !it.meeting }

/** Le widget Discord : en îlot, ronds et halo de qui parle. Les thèmes Instrument prennent [DiscordConsoleWidget]. */
class DiscordWidget(context: Context, host: HomeHost, placed: Placed) :
    CallWidget(context, host, placed, isDiscordPage) {
    override val island get() = true
}

/** La page Réunion du Deck (Teams) : micro, caméra, partage, quitter ; « Rejoindre maintenant » dans la salle d'attente. */
class MeetingWidget(context: Context, host: HomeHost, placed: Placed) :
    CallWidget(context, host, placed, { it.meeting })

/**
 * Appel en cours, quelle que soit l'appli (profil Réunion, Deck V2) : la page Réunion pour Teams, celle de Discord, et
 * pour une appli sans page (Meet, Zoom, Webex) la touche « Couper le micro » du deck. Hors appel : l'aperçu vide.
 */
class AnyCallWidget(context: Context, host: HomeHost, placed: Placed) : CallWidget(context, host, placed, { false }) {
    override fun pickPage(pages: List<DeckPage>): DeckPage? {
        val app = host.main?.callApp ?: return null
        return CallSession.pageFor(app, pages) ?: CallSession.fallbackPage(pages)
    }
}
