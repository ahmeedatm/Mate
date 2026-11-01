package app.mate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import app.mate.Computers
import app.mate.R
import app.mate.net.DeckPage
import app.mate.net.DeckTile
import app.mate.net.runCatchingCancellable
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.errorText
import app.mate.ui.showImmersive
import app.mate.ui.themeBool
import app.mate.ui.themeRes
import kotlinx.coroutines.launch

/**
 * Touches du deck choisies : lancer Discord, une macro, couper le micro… sans passer par le Deck. S, M et V gardent
 * leurs rangées de grandes touches (1, 2 ou 6) ; L et XL montrent toutes les touches choisies dans une grille qui
 * défile, avec une case « + ». Appui long : la seconde action de la touche si elle en a une, sinon la retirer du
 * widget. L'éditeur d'une touche perso s'ouvre seulement en mode Modifier (réglages du widget, bouton « Modifier »).
 * Les touches sont gardées avec leur nom et leur emoji : elles s'affichent même ordinateur éteint.
 */
class KeysWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    // Ni la racine ni l'état vide ne gardent l'appui long (mode Modifier) : seules les touches le font.
    override val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val hasSettings get() = true

    private val capacity get() = when (placed.size) { WidgetSize.S -> 1; WidgetSize.M -> 2; WidgetSize.V -> 6; else -> MAX_KEYS }

    private val grid by lazy {
        TileGridView(context, host.scope).apply {
            onTap = { cell, _ -> if (cell.add) showAddChoice() else run(cell.key) }
            onHold = { cell, _ -> holdOrRemove(cell.key) }
            ownsLongPress = true
        }
    }

    private val inGrid get() = placed.size == WidgetSize.L || placed.size == WidgetSize.XL

    override fun applySize() = render()

    /** Relit le deck : une touche modifiée ou vidée dans l'éditeur se met à jour ici. */
    override suspend fun load() {
        val pages = runCatchingCancellable { host.deckPages() }.getOrNull() ?: return
        // Sans ordinateur actif, rien n'est lu ni écrit : le deck lu ne serait celui de personne.
        val id = computerId ?: return
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

    private fun keys() = KeyRef.listFor(placed.config, computerId)

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
        // Grille vide (Grand, Très grand) : la même invitation au milieu que les petites tailles, pas un « + » perdu dans un coin.
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
        // Vertical : les touches dans une tuile titrée, comme les autres cartes de l'Accueil.
        if (placed.size == WidgetSize.V) {
            view.setBackgroundResource(themeRes(context, R.attr.c_home_card_bg))
            view.setPadding(dp(24), dp(24), dp(24), dp(20))
            view.addView(
                cardTitle(R.string.widget_keys_title),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) },
            )
        }
        // Vertical : 2 × 3 ; sinon une rangée.
        val perRow = if (capacity >= 4) 2 else capacity
        val gap = dp(12)
        keys.take(capacity).chunked(perRow).forEachIndexed { r, rowKeys ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, key) in rowKeys.withIndex()) {
                row.addView(rowTile(key), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = gap })
            }
            // Rangée incomplète : la place vide garde la largeur des touches.
            repeat(perRow - rowKeys.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap }) }
            view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { if (r > 0) topMargin = dp(ROW_GAP_DP) })
        }
    }

    private fun renderGrid(keys: List<KeyRef>) {
        view.asCard(12)
        grid.cells = keys.map { TileGridView.Cell(it) } + TileGridView.Cell(KeyRef(ADD_ID, context.getString(R.string.widget_keys_add)), add = true)
        (grid.parent as? ViewGroup)?.removeView(grid)
        view.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Touche des rangées : la même case que la grille, cliquable seule, dans un cadre qui garde son appui long. */
    private fun rowTile(key: KeyRef): View {
        val tile = TileGridView.newCell(context)
        TileGridView.bind(tile, TileGridView.Cell(key), host.scope)
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
                        }
                    }
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                stopListening?.invoke()
                stopListening = null
            }
        })
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
        const val MAX_KEYS = 30
        const val ADD_ID = "+"

        /** Entre deux rangées du widget vertical : 3 rangées de touches (52 dp + libellé) tiennent à peine. */
        const val ROW_GAP_DP = 4
    }
}
