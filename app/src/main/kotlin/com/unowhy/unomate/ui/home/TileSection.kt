package com.unowhy.unomate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.widget.ImageViewCompat
import com.unowhy.unomate.R
import com.unowhy.unomate.net.BackendClient
import com.unowhy.unomate.net.DeckPage
import com.unowhy.unomate.net.DeckTile
import com.unowhy.unomate.net.TileResult
import com.unowhy.unomate.net.runCatchingCancellable
import com.unowhy.unomate.ui.TitleView
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.errorText
import com.unowhy.unomate.ui.themeColor
import kotlinx.coroutines.launch

/**
 * Titre avec son « + » et grille de tuiles d'une page du Deck, pour un widget (Apps, sites de Média). [tilesOf] tire les
 * tuiles des pages de l'ordinateur, null si la page n'existe pas : titre et grille disparaissent alors.
 */
class TileSection(
    private val context: Context,
    private val host: HomeHost,
    @StringRes title: Int,
    @DrawableRes icon: Int,
    @StringRes addDescription: Int,
    private val tilesOf: (List<DeckPage>) -> List<DeckTile>?,
    onAdd: () -> Unit,
    @DrawableRes addIcon: Int = R.drawable.ic_add,
) {
    var tiles: List<DeckTile> = emptyList()
        private set

    val grid = TileGridView(context, host.scope)

    /** « + » à droite du titre : toujours visible, quelle que soit la taille du widget. */
    private val addButton = ImageButton(context, null, 0, R.style.IconButton_Flat).apply {
        setImageResource(addIcon)
        // `tint` du style n'est lu que par les vues AppCompat gonflées depuis le XML.
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(themeColor(context, R.attr.c_accent)))
        contentDescription = context.getString(addDescription)
        setOnClickListener { onAdd() }
    }

    val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TitleView(context).apply { setText(title); setIcon(icon) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(addButton, LinearLayout.LayoutParams(context.dp(BUTTON_DP), context.dp(BUTTON_DP)))
    }

    private var pageExists = true
    private var wanted = true

    /** Le widget montre la section ou non (sa taille) ; sans page, elle reste cachée. */
    var shown: Boolean
        get() = wanted
        set(value) {
            wanted = value
            val visibility = if (wanted && pageExists) View.VISIBLE else View.GONE
            header.visibility = visibility
            grid.visibility = visibility
        }

    fun addTo(parent: LinearLayout, headerTopMarginDp: Int = 0) {
        parent.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(headerTopMarginDp) })
        parent.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Relit les pages (cache) : une tuile ajoutée ou retirée depuis le Deck apparaît dans la minute. */
    suspend fun load() {
        val pages = runCatchingCancellable { host.deckPages() }.getOrNull() ?: return
        store(pages)
    }

    private fun store(pages: List<DeckPage>) {
        val next = tilesOf(pages)
        if (next != null != pageExists) {
            pageExists = next != null
            shown = wanted
        }
        val list = next.orEmpty()
        // Inchangé : la grille garde son défilement.
        if (list == tiles) return
        tiles = list
        grid.cells = tiles.map { TileGridView.Cell(KeyRef.of(it), type = it.type) }
    }

    fun tileOf(cell: TileGridView.Cell) = tiles.firstOrNull { it.id == cell.key.id }

    /** Après un ajout ou un retrait : relit le deck pour de bon. */
    fun reload() {
        host.scope.launch {
            runCatchingCancellable { host.deckPages(force = true) }
                .onSuccess(::store)
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }

    /** Lance la tuile sur l'ordinateur ; [onResult] reçoit la réponse (fenêtres à choisir, appli ouverte). */
    fun run(tile: DeckTile, onResult: (TileResult) -> Unit = {}) {
        host.scope.launch {
            runCatchingCancellable { BackendClient(context).deck.runTile(tile.id) }
                .onSuccess(onResult)
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }

    private companion object {
        /** Cible tactile du « + ». */
        const val BUTTON_DP = 48
    }
}
