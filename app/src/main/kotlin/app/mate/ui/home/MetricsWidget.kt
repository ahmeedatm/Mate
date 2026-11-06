package app.mate.ui.home

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.Metric
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.launch

/**
 * Des chiffres calculés par l'ordinateur (taille d'un dossier, état d'un build, compteur). Les commandes sont écrites
 * à la main dans metrics.json sur l'ordinateur, qui les lance : la tablette lit seulement le résultat, chaque minute.
 * Pastille : le premier chiffre ; Ligne : côte à côte ; Colonne et 2 cases de large : empilés ; dès 3 × 2 : en grille
 * de 2 colonnes, plus grands en Grand et en Plein écran.
 */
class MetricsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = cardTitle(R.string.widget_metrics)
    private val status = label(14f, R.attr.c_text_dim)
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    private var metrics: List<Metric>? = null
    private var error: String? = null

    /** Aperçu du catalogue : l'exemple reste, aucune lecture ne l'écrase. */
    private var previewing = false

    override fun showPreview(sample: WidgetSample) {
        metrics = (sample as? MetricsSample)?.metrics ?: return
        previewing = true
        error = null
        render(null)
    }

    override fun applySize() {
        // Ligne (une case de haut) : les chiffres côte à côte ; sinon empilés.
        rows.orientation = if (placed.size.h == 1 && placed.size.w > 1) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        render(error)
    }

    override fun setActive(active: Boolean) {
        if (active && !previewing) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (previewing) return
        runCatchingCancellable { BackendClient(context).metrics.latest() }
            .onSuccess { metrics = it; error = null; render(null) }
            .onFailure { error = context.errorText(it); render(error) }
    }

    /** `error` : phrase de l'échec de la dernière lecture ; les dernières valeurs reçues restent affichées dessous. */
    private fun render(error: String?) {
        val current = metrics
        rows.removeAllViews()
        // Pas de phrase « écris tes commandes » tant que la première lecture n'est pas revenue.
        val note = error ?: if (current?.isEmpty() == true) context.getString(R.string.widget_metrics_empty) else null
        status.text = note
        status.visibility = if (note == null) View.GONE else View.VISIBLE
        val side = rows.orientation == LinearLayout.HORIZONTAL
        val shown = metricsShown(placed.size.w, placed.size.h)?.let { current?.take(it) } ?: current ?: return
        // Dès 3 cases de large sur 2 de haut : deux chiffres par rangée.
        val perRow = if (side) shown.size else if (placed.size.w >= 3) 2 else 1
        for (group in shown.chunked(perRow.coerceAtLeast(1))) {
            val line = LinearLayout(context)
            group.forEach { line.addView(row(it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
            rows.addView(line, if (side) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, group.size.toFloat()) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun valueSize() = when (family) {
        ShapeFamily.FULL -> 40f
        ShapeFamily.PILL, ShapeFamily.LARGE -> 30f
        else -> 22f
    }

    private fun row(metric: Metric) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        if (metric.value == null) contentDescription = context.getString(R.string.widget_metrics_unavailable, metric.label)
        addView(label(valueSize()).apply {
            text = metric.value ?: context.getString(R.string.widget_metrics_none)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(label(14f, R.attr.c_text_dim).apply {
            text = metric.label
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }
}

/** Petites formes : combien de chiffres tiennent (Pastille le premier, Ligne un par case, Colonne deux par case moins le titre) ; null : tous. */
internal fun metricsShown(w: Int, h: Int): Int? = when {
    h == 1 -> w
    w == 1 -> 2 * h - 1
    else -> null
}
