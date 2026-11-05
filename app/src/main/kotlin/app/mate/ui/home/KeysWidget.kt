package app.mate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.mate.Computers
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.DeckPage
import app.mate.net.DeckTile
import app.mate.net.runCatchingCancellable
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.dp
import app.mate.ui.errorText
import app.mate.ui.showImmersive
import app.mate.ui.themeBool
import app.mate.ui.themeRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exemple des Touches pour le catalogue : libellé et symbole de chaque touche, sans ordinateur ni icône à charger. */
class KeysSample(val keys: List<Pair<Int, String>>) : WidgetSample

/**
 * Touches du deck choisies : lancer Discord, une macro, couper le micro… sans passer par le Deck. Pastille, Ligne et
 * Colonne montrent les premières touches, autant que la place en tient ([TileShapes]) ; Bloc, Grand et Plein écran
 * montrent toutes les touches choisies dans une grille qui défile, avec une case « + ». Appui long : la seconde action de la touche si elle en a une, sinon la retirer du
 * widget. L'éditeur d'une touche perso s'ouvre seulement en mode Modifier (réglages du widget, bouton « Modifier »).
 * Les touches sont gardées avec leur nom et leur emoji : elles s'affichent même ordinateur éteint.
 */
class KeysWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    // Ni la racine ni l'état vide ne gardent l'appui long (mode Modifier) : seules les touches le font.
    override val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val hasSettings get() = true

    /** Aperçu du catalogue : ses touches remplacent celles de l'ordinateur, même si [applySize] repasse. */
    private var preview: List<KeyRef>? = null

    /** Rangées dessinées en Colonne : la vue se redessine quand sa hauteur en fait tenir un autre nombre. */
    private var drawnFit = 0

    init {
        view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (!inGrid && fitFor(right - left, bottom - top) != drawnFit) view.post { render() }
        }
    }

    private val grid by lazy {
        TileGridView(context, host.scope).apply {
            onTap = { cell, _ -> if (cell.add) showAddChoice() else run(cell.key) }
            onHold = { cell, _ -> holdOrRemove(cell.key) }
            isPush = { pushIdsOf == computerId && it.key.id in pushIds }
            onPush = { cell, down -> push(cell.key, down) }
            ownsLongPress = true
        }
    }

    /** Touches à maintenir du deck, lues à l'ouverture ; les appuis arrivent dans l'ordre, jamais un relâchement avant son appui. */
    private var pushIds = emptySet<String>()
    private var pushIdsOf: String? = null
    private val pushOrder = Mutex()

    /** Touches tenues en ce moment ; relâchées si leur vue disparaît pendant l'appui. */
    private val held = mutableSetOf<String>()

    /** Hors du scope de la vue : un relâchement ne doit jamais être annulé avec elle. */
    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val inGrid get() = TileShapes.usesGrid(placed.size)

    override fun applySize() = render()

    override fun showPreview(sample: WidgetSample) {
        val keys = (sample as? KeysSample)?.keys ?: return
        preview = keys.mapIndexed { i, (label, symbol) -> KeyRef("$SAMPLE_ID$i", context.getString(label), symbol = symbol) }
        render()
    }

    /** Relit le deck : une touche modifiée ou vidée dans l'éditeur se met à jour ici. */
    override suspend fun load() {
        val pages = runCatchingCancellable { host.deckPages() }.getOrNull() ?: return
        // Sans ordinateur actif, rien n'est lu ni écrit : le deck lu ne serait celui de personne.
        val id = computerId ?: return
        tilePushIds(pages).let { if (it != pushIds || pushIdsOf != id) { pushIds = it; pushIdsOf = id; render() } }
        if (KeyRef.adopt(placed.config, id)) host.save()
        // Profil Deck : ses touches de départ arrivent avec l'ordinateur (page Raccourcis de son deck).
        KeyRef.seed(placed.config, id, pages)?.let {
            store(it)
            return
        }
        reconcileKeys(keys(), pages)?.let { store(it) }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /** Pages du deck, ou null (message affiché) si l'ordinateur ne répond pas. */
    private suspend fun readPages(force: Boolean): List<DeckPage>? =
        runCatchingCancellable { host.deckPages(force) }
            .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            .getOrNull()

    /** Les touches sont rangées par ordinateur : leurs ids (`k-N`, pages fixes) n'ont de sens que sur le sien. */
    private val computerId get() = Computers.active(context)?.id

    private fun keys() = preview ?: KeyRef.listFor(placed.config, computerId)

    private fun store(keys: List<KeyRef>) {
        if (!KeyRef.putFor(placed.config, computerId, keys)) return
        host.save()
        render()
    }

    private fun render() {
        view.removeAllViews()
        view.background = null
        view.setPadding(0, 0, 0, 0)
        val keys = keys()
        // Grille vide (Bloc, Grand, Plein écran) : la même invitation au milieu que les petites formes, pas un « + » perdu dans un coin.
        if (inGrid && keys.isNotEmpty()) return renderGrid(keys, add = true)
        if (keys.isEmpty()) {
            view.addView(LinearLayout(context).apply {
                setBackgroundResource(R.drawable.tile_empty_bg)
                gravity = Gravity.CENTER
                orientation = LinearLayout.VERTICAL
                addView(label(30f, R.attr.c_text_faint).apply { text = "+"; gravity = Gravity.CENTER })
                addView(label(16f, R.attr.c_text_dim).apply { setText(R.string.widget_keys_choose); gravity = Gravity.CENTER })
                setOnClickListener { if (inGrid) showAddChoice() else showSettings() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        drawnFit = fitFor(view.width, view.height)
        when (Variant.of(context)) {
            'B' -> renderGrid(keys, add = false)
            'C' -> renderList(keys)
            else -> renderBig(keys)
        }
    }

    /** Ce que la forme fait tenir (rangées, ou touches en Ligne pour la liste), d'après la taille réelle de la vue. */
    private fun fitFor(width: Int, height: Int): Int = when (Variant.of(context)) {
        'B' -> 0
        'C' -> if (family == ShapeFamily.ROW) TileShapes.fit(width, dp(ICON_ONLY_DP), dp(8)) else TileShapes.fit(height, dp(LIST_ROW_DP), dp(4))
        else -> if (family == ShapeFamily.COLUMN) TileShapes.fit(height - dp(COLUMN_CHROME_DP), dp(BIG_ROW_DP), dp(ROW_GAP_DP)) else 1
    }

    /** Variante A : grandes touches égales, une par case en Ligne, deux de front en Colonne (dans une tuile titrée). */
    private fun renderBig(keys: List<KeyRef>) {
        if (family == ShapeFamily.COLUMN) {
            view.setBackgroundResource(themeRes(context, R.attr.c_home_card_bg))
            view.setPadding(dp(24), dp(24), dp(24), dp(20))
            view.addView(
                cardTitle(R.string.widget_keys_title),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) },
            )
        }
        val perRow = TileShapes.bigColumns(placed.size)
        val gap = dp(12)
        keys.take(perRow * drawnFit).chunked(perRow).forEachIndexed { r, rowKeys ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, key) in rowKeys.withIndex()) {
                row.addView(rowTile(key), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = gap })
            }
            // Rangée incomplète : la place vide garde la largeur des touches.
            repeat(perRow - rowKeys.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap }) }
            view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (r > 0) topMargin = dp(ROW_GAP_DP) })
        }
    }

    /**
     * Variante C : en Colonne, une liste (icône et nom sur une ligne) ; en Ligne, des icônes seules, plus nombreuses ;
     * en Pastille, l'icône de la première touche, seule.
     */
    private fun renderList(keys: List<KeyRef>) {
        val row = family == ShapeFamily.ROW
        view.orientation = if (row) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val shown = keys.take(if (family == ShapeFamily.PILL) 1 else drawnFit)
        for ((i, key) in shown.withIndex()) {
            val tile = rowTile(key, if (family == ShapeFamily.COLUMN) TileLook.LIST else TileLook.ICON)
            val params = if (row) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(8) }
            } else if (family == ShapeFamily.PILL) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(LIST_ROW_DP)).apply { if (i > 0) topMargin = dp(4) }
            }
            view.addView(tile, params)
        }
    }

    /** Grille qui défile ; [add] : la case « + » en dernier (Bloc et plus grand). */
    private fun renderGrid(keys: List<KeyRef>, add: Boolean) {
        view.orientation = LinearLayout.VERTICAL
        view.asCard(12)
        val addCell = TileGridView.Cell(KeyRef(ADD_ID, context.getString(R.string.widget_keys_add)), add = true)
        grid.cells = keys.map { TileGridView.Cell(it) } + listOfNotNull(addCell.takeIf { add })
        (grid.parent as? ViewGroup)?.removeView(grid)
        view.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Touche des rangées : la même case que la grille, cliquable seule, dans un cadre qui garde son appui long. */
    private fun rowTile(key: KeyRef, look: TileLook = TileLook.TILE): View {
        val tile = TileGridView.newCell(context)
        TileGridView.bind(tile, TileGridView.Cell(key), host.scope)
        look.apply(tile)
        // Allumée ou éteinte côté ordinateur : la touche se redessine si son état a changé, tant qu'elle est à l'écran.
        var stopListening: (() -> Unit)? = null
        var drawnLit = TileStates.isLit(key.id)
        tile.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                stopListening = TileStates.listen {
                    v.post {
                        val lit = TileStates.isLit(key.id)
                        if (v.isAttachedToWindow && lit != drawnLit) {
                            drawnLit = lit
                            TileGridView.bind(v, TileGridView.Cell(key), host.scope)
                            look.apply(v)
                        }
                    }
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                stopListening?.invoke()
                stopListening = null
            }
        })
        if (pushIdsOf == computerId && key.id in pushIds) return pushTile(tile, key)
        Look.pressSpring(tile)
        tile.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            run(key)
        }
        tile.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            holdOrRemove(key)
            true
        }
        return OwnerFrameLayout(context, ownsLongPress = true).apply {
            addView(tile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** Touche à maintenir seule : appui = touches tenues, relâchement ou annulation = touches lâchées. */
    private fun pushTile(tile: View, key: KeyRef): View {
        tile.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) = releaseHeld(listOf(key))
        })
        tile.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Look.pressTo(v, true)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    push(key, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    Look.pressTo(v, false)
                    push(key, false)
                }
            }
            true
        }
        return OwnerFrameLayout(context, ownsLongPress = true).apply {
            addView(tile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun push(key: KeyRef, down: Boolean) {
        if (down) held += key.id else if (!held.remove(key.id)) return
        pushScope.launch {
            pushOrder.withLock {
                // Un relâchement raté laisserait le micro ouvert : on réessaie avant de le dire.
                var result = runCatchingCancellable { BackendClient(context).deck.pushTile(key.id, down) }
                repeat(if (down) 0 else PUSH_RETRIES) {
                    if (result.isFailure) {
                        delay(PUSH_RETRY_MS)
                        result = runCatchingCancellable { BackendClient(context).deck.pushTile(key.id, down) }
                    }
                }
                result.onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Les touches tenues sont lâchées quand le widget se redessine ou disparaît. */
    private fun releaseHeld(keys: List<KeyRef>) = keys.filter { it.id in held }.forEach { push(it, false) }

    /** Lance la touche. Une appli à plusieurs fenêtres ne se lance pas : l'ordinateur rend ses fenêtres, on les propose. */
    private fun run(key: KeyRef) {
        host.scope.launch {
            val result = runTileSpoken(context, key.id, key.label)
            result.onFailure {
                Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show()
                return@launch
            }
            val windows = result.getOrNull()?.windows ?: return@launch
            val tile = runCatchingCancellable { host.deckPages() }.getOrNull()?.flatMap { it.tiles }?.firstOrNull { it.id == key.id } ?: return@launch
            host.overlays.windows(tile, windows)
        }
    }

    /** Appui long : la seconde action de la touche si le deck lui en donne une, sinon la retirer du widget. */
    private fun holdOrRemove(key: KeyRef) {
        host.scope.launch {
            val pages = runCatchingCancellable { host.deckPages() }.getOrNull()
            if (pages == null || !tileHasHold(pages, key.id)) return@launch confirmRemove(key)
            runTileSpoken(context, key.id, key.label, hold = true)
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }

    /** Éditeur de la touche perso ou d'une page nommée `id`, null pour toute autre touche. */
    private fun editorOf(id: String): (() -> Unit)? = slotOf(id)?.let { slot -> { host.overlays.editKey(slot) { reload() } } }
        ?: pageSlotOf(id)?.let { (page, pageSlot) -> { host.overlays.editKey(pageSlot, page) { reload() } } }

    private fun confirmRemove(key: KeyRef) {
        AlertDialog.Builder(context)
            .setMessage(context.getString(R.string.widget_keys_remove, key.label))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.widget_keys_remove_yes) { _, _ -> store(KeyRef.without(keys(), key.id)) }
            .showImmersive()
    }

    private fun showAddChoice() {
        AlertDialog.Builder(context)
            .setItems(arrayOf(context.getString(R.string.widget_keys_new), context.getString(R.string.widget_keys_existing))) { _, which ->
                if (which == 0) newKey() else showSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .showImmersive()
    }

    /** Ouvre l'éditeur sur le premier emplacement libre ; la touche créée rejoint le widget. */
    private fun newKey() {
        host.scope.launch {
            // Relu pour de bon : un emplacement déjà pris serait écrasé.
            val pages = readPages(force = true) ?: return@launch
            // Créée sans place dans le widget, la touche n'y apparaîtrait pas : on le dit avant.
            if (keys().size >= MAX_KEYS) return@launch toast(context.getString(R.string.widget_keys_widget_full, MAX_KEYS))
            if (pages.none { it.editable }) return@launch toast(context.getString(R.string.widget_keys_no_perso))
            val slot = nextFreeSlot(pages) ?: return@launch toast(context.getString(R.string.widget_keys_full))
            host.overlays.editKey(slot) { reload("k-$slot") }
        }
    }

    /** Après l'éditeur : relit le deck, met les touches à jour et ajoute [added] (touche créée) si elle existe. */
    private fun reload(added: String? = null) {
        host.scope.launch {
            val pages = readPages(force = true) ?: return@launch
            val current = keys()
            var keys = reconcileKeys(current, pages) ?: current
            val created = pages.flatMap { it.tiles }.firstOrNull { it.id == added && it.type != "empty" }
            if (created != null && keys.none { it.id == created.id } && keys.size < MAX_KEYS) keys = keys + KeyRef.of(created)
            if (keys != current) store(keys) else render()
        }
    }

    /** Liste des touches de toutes les pages du deck de l'ordinateur actif, à cocher (au plus [MAX_KEYS]). */
    override fun showSettings() {
        host.scope.launch {
            val pages = readPages(force = false) ?: return@launch
            val current = keys()
            val selected = current.map { it.id }.toMutableList()
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(8)) }
            val boxes = mutableListOf<Pair<CheckBox, DeckTile>>()
            lateinit var dialog: AlertDialog
            // Une touche choisie mais absente du deck pour l'instant (page d'appel) reste telle qu'elle est enregistrée.
            fun save() {
                val all = pages.flatMap { it.tiles }
                store(selected.mapNotNull { id -> all.firstOrNull { it.id == id }?.let(KeyRef::of) ?: current.firstOrNull { it.id == id } })
            }
            val hint = label(15f, R.attr.c_text_dim).apply { setPadding(0, 0, 0, dp(8)) }
            box.addView(hint)
            fun update() {
                hint.text = context.getString(R.string.widget_keys_hint, selected.size, MAX_KEYS)
                boxes.forEach { (check, tile) -> check.isEnabled = check.isChecked || selected.size < MAX_KEYS || tile.id in selected }
            }
            for (page in pages) {
                val tiles = page.tiles.filter { it.type != "empty" }
                if (tiles.isEmpty()) continue
                box.addView(TitleView(context).apply { text = page.title; setPadding(0, dp(12), 0, dp(4)) })
                for (tile in tiles) {
                    val check = CheckBox(context).apply {
                        text = tile.label
                        textSize = 17f
                        minHeight = dp(44)
                        isChecked = tile.id in selected
                        setOnCheckedChangeListener { _, on ->
                            if (on) { if (tile.id !in selected) selected += tile.id } else selected -= tile.id
                            update()
                        }
                    }
                    boxes += check to tile
                    // Seul le mode Modifier ouvre l'éditeur d'une touche.
                    val edit = editorOf(tile.id)?.takeIf { host.editing }
                    if (edit == null) {
                        box.addView(check)
                    } else {
                        box.addView(LinearLayout(context).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            addView(Button(context, null, 0, R.style.ActionButton).apply {
                                setText(R.string.widget_keys_edit)
                                contentDescription = context.getString(R.string.widget_keys_edit_named, tile.label)
                                setOnClickListener { save(); dialog.dismiss(); edit() }
                            })
                        })
                    }
                }
            }
            update()
            dialog = AlertDialog.Builder(context)
                .setTitle(R.string.widget_keys)
                .setView(ScrollView(context).apply { addView(box) })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.widget_keys_new) { _, _ -> save(); newKey() }
                .setPositiveButton(R.string.save) { _, _ -> save() }
                .showImmersive()
        }
    }

    private companion object {
        /** Relâchement raté : nouveaux essais, et délai entre deux. */
        const val PUSH_RETRIES = 2
        const val PUSH_RETRY_MS = 300L

        const val MAX_KEYS = 30
        const val ADD_ID = "+"
        const val SAMPLE_ID = "exemple-"

        /** Entre deux rangées en Colonne : 3 rangées de touches (52 dp + libellé) tiennent à peine en 1 × 2. */
        const val ROW_GAP_DP = 4

        /** Hauteur d'une rangée de grandes touches en Colonne, au moins : la plaque de 52 dp et une ligne de libellé. */
        const val BIG_ROW_DP = 80

        /** En Colonne, la tuile titrée prend ses marges (24 + 20) et son titre (24 + 12) sur la hauteur. */
        const val COLUMN_CHROME_DP = 80

        /** Variante C : une ligne de la liste, une icône seule en Ligne. */
        const val LIST_ROW_DP = 56
        const val ICON_ONLY_DP = 64
    }
}

/** Habit d'une case de [TileGridView.newCell] hors de la grille : case normale, ligne de liste, ou icône sans nom. */
enum class TileLook {
    TILE,
    LIST,
    ICON,
    ;

    /** À rappeler après chaque [TileGridView.bind] : il ne touche ni à l'orientation ni au libellé caché. */
    fun apply(tile: View) {
        val label = tile.findViewById<TextView>(R.id.tile_label)
        when (this) {
            TILE -> Unit
            ICON -> {
                label.visibility = View.GONE
                (tile as LinearLayout).gravity = Gravity.CENTER
                tile.contentDescription = label.text
            }
            LIST -> {
                (tile as LinearLayout).orientation = LinearLayout.HORIZONTAL
                tile.gravity = Gravity.CENTER_VERTICAL
                label.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                label.maxLines = 1
                label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = tile.context.dp(12) }
            }
        }
    }
}

/** shortcut: variantes à montrer à Ahmed (#183), lues dans `files/variante-touches` ; retiré une fois son choix fait. */
private object Variant {
    fun of(context: Context): Char = runCatching { java.io.File(context.filesDir, "variante-touches").readText().trim().first() }.getOrDefault('A')
}
