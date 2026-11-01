package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
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
 * entité : son nom et son état ; un toucher la bascule (une scène se lance). Allumée = bouton plein. L'état se relit à
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
    private var busy = false

    override fun applySize() {
        grid.columnCount = if (placed.size == WidgetSize.XL) 4 else 2
        render()
    }

    override fun onMinute() {
        if (!busy) host.scope.launch { load() }
    }

    override suspend fun load() {
        runCatchingCancellable { BackendClient(context).ha.entities() }
            .onSuccess { entities = it; status.visibility = View.GONE }
            .onFailure { status.text = context.errorText(it); status.visibility = View.VISIBLE }
        render()
    }

    override fun setActive(active: Boolean) {
        if (active) onMinute()
    }

    private fun render() {
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
        text = "${entity.name}\n${stateText(entity)}"
        textSize = if (placed.size == WidgetSize.M) 14f else 16f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        minHeight = dp(64)
        isEnabled = entity.available && !busy
        alpha = if (entity.available) 1f else 0.5f
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
        if (busy) return
        busy = true
        render()
        host.scope.launch {
            try {
                runCatchingCancellable { BackendClient(context).ha.toggle(entity.id) }
                    .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
                load()
            } finally {
                busy = false
                render()
            }
        }
    }
}
