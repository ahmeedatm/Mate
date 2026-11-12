package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.ContextThemeWrapper
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
import androidx.appcompat.content.res.AppCompatResources
import app.mate.Computers
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.DeckPage
import app.mate.net.DeckTile
import app.mate.net.runCatchingCancellable
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.errorText
import app.mate.ui.showImmersive
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeFont
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
 * Colonne montrent les premières touches, autant que la place en tient ([TileStrip]) ; Bloc, Grand et Plein écran
 * montrent toutes les touches choisies dans une grille qui défile, avec une case « + ». Appui long : la seconde action
 * de la touche si elle en a une, sinon la retirer du widget. L'éditeur d'une touche perso s'ouvre seulement en mode Modifier (réglages du widget, bouton « Modifier »).
 * Les touches sont gardées avec leur nom et leur emoji : elles s'affichent même ordinateur éteint.
 */
class KeysWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    // Ni la racine ni l'état vide ne gardent l'appui long (mode Modifier) : seules les touches le font.
    override val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val hasSettings get() = true

    /** Aperçu du catalogue : ses touches remplacent celles de l'ordinateur, même si [applySize] repasse. */
    private var preview: List<KeyRef>? = null

    /** Touches dessinées en Ligne ou Colonne : la vue se redessine quand sa taille en fait tenir un autre nombre. */
    private var drawnFit = 0

    init {
        view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (!inGrid && fitFor(right - left, bottom - top) != drawnFit) view.post { render() }
        }
    }

    /** Thème à fenêtre LCD (Instrument) : capuchons numérotés à DEL, et en grille la fenêtre de la dernière touche ([KeyCaps]). */
    private val caps = KeyCaps.applies(themeRes(context, R.attr.c_lcd_overlay))
    private val lcdContext by lazy { ContextThemeWrapper(context, themeRes(context, R.attr.c_lcd_overlay)) }

    /** Ligne de la fenêtre LCD : la dernière touche envoyée (ou ratée), gardée quand le widget se redessine. */
    private var lcdLine: String? = null
    private var lcdState: TextView? = null

    private val grid by lazy {
        TileGridView(context, host.scope).apply {
            keyCaps = caps
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
        view.orientation = LinearLayout.VERTICAL
        // Avant toute sortie, état vide compris : sinon le listener de layout redessinerait à chaque passe.
        drawnFit = fitFor(view.width, view.height)
        val keys = keys()
        // Grille vide (Bloc, Grand, Plein écran) : la même invitation au milieu que les petites formes, pas un « + » perdu dans un coin.
        if (inGrid && keys.isNotEmpty()) return renderGrid(keys)
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
        renderStrip(keys)
    }

    /** Panneau d'un thème à filet : Pastille, Ligne et Colonne gardent leur cadre, la Colonne son titre ([panelCaption]). */
    private val framed get() = !inGrid && panel
    private val titled get() = framed && family == ShapeFamily.COLUMN

    /** Touches qui tiennent dans une vue de [width] × [height] px, sans la marge du cadre ni le titre de la Colonne. */
    private fun fitFor(width: Int, height: Int): Int {
        val pad = if (framed) dp(TileShapes.PANEL_STRIP_PAD_DP) * 2 else 0
        val room = height - pad - if (titled) dp(TileShapes.PANEL_CAPTION_DP + TileShapes.PANEL_CAPTION_GAP_DP) else 0
        if (caps) return KeyCaps.stripFit(family, width - pad, room, context.resources.displayMetrics.density, TileStrip.gapDp(context))
        return TileStrip.fit(context, family, width - pad, room, panel = framed)
    }

    /** Pastille, Ligne, Colonne : les premières touches, autant que la forme en tient ([TileStrip]). */
    private fun renderStrip(keys: List<KeyRef>) {
        if (framed) view.asCard(TileShapes.PANEL_STRIP_PAD_DP)
        view.orientation = TileStrip.orientation(family)
        if (titled) view.addView(panelCaption(R.string.widget_keys_title), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(TileShapes.PANEL_CAPTION_GAP_DP) })
        for ((i, key) in keys.take(drawnFit).withIndex()) view.addView(rowTile(key, i, TileStrip.look(family)), TileStrip.params(context, family, i, panel = framed))
    }

    /** Bloc, Grand, Plein écran : toutes les touches dans une grille qui défile, la case « + » en dernier. */
    private fun renderGrid(keys: List<KeyRef>) {
        view.orientation = LinearLayout.VERTICAL
        view.asCard(PANEL_PAD_DP)
        grid.bigCaps = caps && KeyCaps.labelLines(placed.size) == 2
        grid.cells = keys.map { TileGridView.Cell(it) } + TileGridView.Cell(KeyRef(ADD_ID, context.getString(R.string.widget_keys_add)), add = true)
        (grid.parent as? ViewGroup)?.removeView(grid)
        lcdState = null
        if (caps && KeyCaps.lcd(placed.size)) {
            view.addView(lcdWindow(keys.size), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(LCD_GAP_DP) })
        }
        view.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Fenêtre LCD au-dessus de la grille : la dernière touche envoyée à gauche, le nombre de touches à droite. */
    private fun lcdWindow(count: Int) = LinearLayout(lcdContext).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = AppCompatResources.getDrawable(lcdContext, themeRes(lcdContext, R.attr.c_home_card_bg))
        setPadding(dp(LCD_PAD_DP), dp(LCD_PAD_V_DP), dp(LCD_PAD_DP), dp(LCD_PAD_V_DP))
        val state = lcdText(R.attr.c_text).apply { text = lcdLine ?: context.getString(R.string.widget_keys_lcd_ready) }
        lcdState = state
        addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(lcdText(R.attr.c_text_dim).apply { text = context.resources.getQuantityString(R.plurals.widget_keys_lcd_count, count, count) })
    }

    private fun lcdText(colorAttr: Int) = TextView(lcdContext).apply {
        textSize = LCD_SP
        setTextColor(themeColor(lcdContext, colorAttr))
        typeface = themeFont(lcdContext, R.attr.c_font_display)
        isAllCaps = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** La touche [label] est partie ([ok]) ou a échoué : la fenêtre LCD le dit, si elle est à l'écran. */
    private fun showSent(label: String, ok: Boolean) {
        if (!caps) return
        lcdLine = context.getString(if (ok) R.string.widget_keys_lcd_sent else R.string.widget_keys_lcd_failed, label)
        lcdState?.text = lcdLine
    }

    /** Touche des rangées : la même case que la grille, cliquable seule, dans un cadre qui garde son appui long. */
    private fun rowTile(key: KeyRef, index: Int, look: TileLook = TileLook.TILE): View {
        val tile = TileGridView.newCell(context, caps = caps)
        TileGridView.bind(tile, TileGridView.Cell(key), host.scope)
        tile.findViewById<TextView>(R.id.key_number)?.text = KeyCaps.number(index)
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
                    TileGridView.press(v, true)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    push(key, true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    TileGridView.press(v, false)
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
                if (down) showSent(key.label, result.isSuccess)
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
            showSent(key.label, result.isSuccess)
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

        const val PANEL_PAD_DP = 12

        /** Fenêtre LCD des Touches : corps du texte, marges et écart avec la grille. */
        const val LCD_SP = 13f
        const val LCD_PAD_DP = 12
        const val LCD_PAD_V_DP = 7
        const val LCD_GAP_DP = 10
        const val MAX_KEYS = 30
        const val ADD_ID = "+"
        const val SAMPLE_ID = "exemple-"
    }
}
