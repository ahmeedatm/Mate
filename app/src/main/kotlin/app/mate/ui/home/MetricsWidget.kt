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

    override fun setActive(active: Boolean) {
        if (active) host.scope.launch { load() }
    }

    override suspend fun load() {
        runCatchingCancellable { BackendClient(context).metrics.latest() }
            .onSuccess { metrics = it; render(null) }
            .onFailure { render(context.errorText(it)) }
    }

    /** `error` : phrase de l'échec de la dernière lecture ; les dernières valeurs reçues restent affichées dessous. */
    private fun render(error: String?) {
        val current = metrics
        rows.removeAllViews()
        // Pas de phrase « écris tes commandes » tant que la première lecture n'est pas revenue.
        val note = error ?: if (current?.isEmpty() == true) context.getString(R.string.widget_metrics_empty) else null
        status.text = note
        status.visibility = if (note == null) View.GONE else View.VISIBLE
        current?.forEach { rows.addView(row(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
    }

    private fun row(metric: Metric) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        if (metric.value == null) contentDescription = context.getString(R.string.widget_metrics_unavailable, metric.label)
        addView(label(if (placed.size == WidgetSize.XL) 30f else 22f).apply {
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
