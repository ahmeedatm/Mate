package app.mate.ui.home

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.HaEntity
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.launch

/**
 * Lumières, prises et scènes de Home Assistant (liste blanche réglée sur l'ordinateur, qui garde le jeton). Une case par
 * entité : son nom et son état ; un toucher la bascule (une scène se lance). Allumé = bouton plein. L'état se relit à
 * chaque minute et après chaque toucher. M : deux colonnes, une rangée ; L : deux colonnes ; XL : quatre colonnes.
 */
class HaWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = cardTitle(R.string.widget_ha)
    private val status = label(14f, R.attr.c_text_dim)
    private val grid = GridLayout(context)
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    private var entities: List<HaEntity> = emptyList()
    /** Entités dont l'ordre est parti : seule leur case est bloquée, les autres restent touchables. */
    private var pending: Set<String> = emptySet()

    /** Dernière liste dessinée : rien n'est refait tant qu'elle ne change pas (toutes les minutes, rien n'a bougé). */
    private var drawn: Pair<List<HaEntity>, Set<String>>? = null

    /** Numéro de la dernière lecture demandée : une lecture plus ancienne que le dernier toucher est jetée. */
    private var reads = 0

    override fun applySize() {
        grid.columnCount = if (placed.size == WidgetSize.XL) 4 else 2
        drawn = null
        render()
    }

    override fun onMinute() {
        host.scope.launch { load() }
    }

    override suspend fun load() {
        val mine = ++reads
        val result = runCatchingCancellable { BackendClient(context).ha.entities() }
        if (mine != reads) return
        result
            .onSuccess { entities = it; status.visibility = View.GONE }
            .onFailure { status.text = context.errorText(it); status.visibility = View.VISIBLE }
        render()
    }

    override fun setActive(active: Boolean) {
        if (active) onMinute()
    }

    private fun render() {
        if (drawn == entities to pending) return
        drawn = entities to pending
        grid.removeAllViews()
        if (entities.isEmpty() && status.visibility != View.VISIBLE) {
            status.setText(R.string.ha_empty)
            status.visibility = View.VISIBLE
        }
        for (entity in entities) {
            val params = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(tile(entity), params)
        }
    }

    private fun tile(entity: HaEntity) = Button(context, null, 0, if (entity.on) R.style.PrimaryButton else R.style.ActionButton).apply {
        // Le nom d'abord, l'état en dessous plus petit ; le nom perd sa place avant l'état.
        val name = entity.name
        text = SpannableString("$name\n${stateText(entity)}").apply {
            setSpan(RelativeSizeSpan(0.8f), name.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textSize = if (placed.size == WidgetSize.M) 14f else 16f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        minHeight = dp(64)
        isEnabled = entity.id !in pending
        alpha = if (entity.available) 1f else 0.7f
        setOnClickListener { toggle(entity) }
    }

    private fun stateText(e: HaEntity) = context.getString(
        when {
            !e.available -> R.string.ha_unavailable
            e.isScene -> R.string.ha_scene
            e.on -> R.string.ha_on
            else -> R.string.ha_off
        },
    )

    private fun toggle(entity: HaEntity) {
        if (!entity.available) return toast(context.getString(R.string.ha_unavailable_hint, entity.name))
        if (entity.id in pending) return
        pending = pending + entity.id
        reads++
        render()
        host.scope.launch {
            try {
                runCatchingCancellable { BackendClient(context).ha.toggle(entity.id) }
                    .onFailure { toast(context.errorText(it)) }
                load()
            } finally {
                pending = pending - entity.id
                render()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
