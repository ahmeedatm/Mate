package app.mate.ui.home

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import app.mate.R
import app.mate.net.DeckTile
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.themeRes

/** Exemple des Applis pour le catalogue : des noms d'applis, chacune dessinée par son initiale (aucune icône à charger). */
class AppsSample(@StringRes val names: List<Int>) : WidgetSample

/**
 * Les applis de la page Apps du Deck, sur un profil : toucher lance l'appli (ou propose ses fenêtres si elle en a
 * plusieurs), appui long = ses fenêtres et « Retirer », bouton du titre = toutes les applis de l'ordinateur (lancer, épingler ici). Seule
 * la grille garde l'appui long : sur le titre ou la marge, le widget s'agrandit. Pastille, Ligne et Colonne montrent les
 * premières applis sans titre ni grille ([TileStrip]) ; Bloc, Grand et Plein écran ont le titre, son « + » et la grille.
 */
class AppsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(PANEL_PAD_DP).apply { orientation = LinearLayout.VERTICAL }

    private val section = TileSection(
        context, host, R.string.widget_apps, R.string.apps_all,
        tilesOf = { pages -> pages.firstOrNull { it.apps }?.tiles },
        onAdd = ::addApps,
        // Toutes les applis de l'ordinateur : les lancer, ou les épingler sur la carte.
        addIcon = R.drawable.ic_apps,
    )

    /** Thème à fenêtre LCD (Instrument) : les applis en capuchons numérotés à DEL, comme les Touches ([KeyCaps]). */
    private val caps = KeyCaps.applies(themeRes(context, R.attr.c_lcd_overlay))

    /** Pastille, Ligne, Colonne : les premières applis, sans la grille. */
    private val strip = LinearLayout(context)

    private val inGrid get() = TileShapes.usesGrid(placed.size)

    /** Panneau d'un thème à filet : Pastille, Ligne et Colonne gardent leur cadre, la Colonne son titre. */
    private val caption = panelCaption(R.string.widget_apps)
    private val framed get() = !inGrid && panel
    private val titled get() = framed && family == ShapeFamily.COLUMN

    /** Applis qui tiennent dans le cadre : sans sa marge, ni le titre de la Colonne. */
    private fun fitStrip(): Int {
        val pad = if (framed) dp(TileShapes.PANEL_STRIP_PAD_DP) * 2 else 0
        val room = view.height - pad - if (titled) dp(TileShapes.PANEL_CAPTION_DP + TileShapes.PANEL_CAPTION_GAP_DP) else 0
        if (caps) return KeyCaps.stripFit(family, view.width - pad, room, context.resources.displayMetrics.density, TileStrip.gapDp(context))
        return TileStrip.fit(context, family, view.width - pad, room, panel = framed)
    }

    /** Aperçu du catalogue : ses applis remplacent celles de l'ordinateur, même si [applySize] repasse. */
    private var preview: List<KeyRef>? = null

    /** Applis dessinées hors de la grille : la vue se redessine quand sa taille en fait tenir un autre nombre. */
    private var drawnFit = 0

    // Hors d'un `apply` sur la section : `::run` y désignerait `TileSection.run`, pas celui du widget.
    init {
        section.grid.keyCaps = caps
        section.grid.onTap = { cell, _ -> section.tileOf(cell)?.let(::run) }
        section.grid.ownsLongPress = true
        section.grid.onHold = { cell, _ -> section.tileOf(cell)?.let { host.overlays.windows(it, removable = true) { section.reload() } } }
        section.onTiles = { if (!inGrid) renderStrip() }
        view.addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(TileShapes.PANEL_CAPTION_GAP_DP) })
        section.addTo(view)
        view.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            // Bandeau sur la ligne fine (92 dp) : pas la place pour le titre au-dessus des applis.
            val short = bottom - top in 1 until view.dp(SHORT_DP)
            if (short != section.headerHidden) view.post { section.headerHidden = short }
            if (!inGrid && fitStrip() != drawnFit) view.post { renderStrip() }
        }
    }

    private companion object {
        /** Hauteur sous laquelle le titre laisse la place aux applis. */
        const val SHORT_DP = 130

        const val PANEL_PAD_DP = 12
        const val ADD_ID = "+"
        const val SAMPLE_ID = "exemple-"
    }

    override fun applySize() {
        section.shown = inGrid
        strip.visibility = if (inGrid) View.GONE else View.VISIBLE
        if (inGrid) view.asCard(PANEL_PAD_DP) else if (framed) view.asCard(TileShapes.PANEL_STRIP_PAD_DP) else view.apply { background = null; setPadding(0, 0, 0, 0) }
        caption.visibility = if (titled) View.VISIBLE else View.GONE
        renderStrip()
    }

    override fun showPreview(sample: WidgetSample) {
        val names = (sample as? AppsSample)?.names ?: return
        preview = names.mapIndexed { i, name -> context.getString(name).let { KeyRef("$SAMPLE_ID$i", it, glyph = it.take(1)) } }
        section.grid.cells = preview.orEmpty().map { TileGridView.Cell(it) }
        renderStrip()
    }

    override suspend fun load() = section.load()

    /** Les applis à dessiner : l'exemple du catalogue, sinon celles de la page Apps. */
    private fun cells(): List<TileGridView.Cell> =
        preview?.map { TileGridView.Cell(it) } ?: section.tiles.map { TileGridView.Cell(KeyRef.of(it), type = it.type) }

    private fun renderStrip() {
        strip.removeAllViews()
        if (inGrid) return
        drawnFit = fitStrip()
        strip.orientation = TileStrip.orientation(family)
        // Aucune appli épinglée : le « + » de la grille, qui ouvre toutes les applis de l'ordinateur.
        val cells = cells().ifEmpty { listOf(TileGridView.Cell(KeyRef(ADD_ID, context.getString(R.string.apps_all_title)), add = true)) }
        for ((i, cell) in cells.take(drawnFit).withIndex()) strip.addView(stripTile(cell, i), TileStrip.params(context, family, i, panel = framed))
    }

    /** Une appli hors de la grille : toucher la lance, appui long ouvre ses fenêtres ; le « + » ouvre toutes les applis. */
    private fun stripTile(cell: TileGridView.Cell, index: Int): FrameLayout {
        val tile = TileGridView.newCell(context, caps = caps)
        TileGridView.bind(tile, cell, host.scope)
        if (!cell.add) tile.findViewById<TextView>(R.id.key_number)?.text = KeyCaps.number(index)
        TileStrip.look(family).apply(tile)
        if (caps) {
            tile.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> TileGridView.press(v, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> TileGridView.press(v, false)
                }
                false
            }
        } else {
            Look.pressSpring(tile)
        }
        tile.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (cell.add) addApps() else section.tileOf(cell)?.let(::run)
        }
        if (!cell.add) {
            tile.setOnLongClickListener {
                val app = section.tileOf(cell) ?: return@setOnLongClickListener false
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                host.overlays.windows(app, removable = true) { section.reload() }
                true
            }
        }
        return OwnerFrameLayout(context, ownsLongPress = true).apply {
            addView(tile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun addApps() {
        // Nom d'appli → identifiant de tuile (applis déjà dans la page Apps).
        val present = section.tiles.mapNotNull { t -> t.app?.let { it to t.id } }.toMap()
        host.overlays.addApps(present) { section.reload() }
    }

    private fun run(tile: DeckTile) =
        section.run(tile) { result -> result.windows?.let { host.overlays.windows(tile, it, removable = true) { section.reload() } } }
}
