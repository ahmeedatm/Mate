package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import app.mate.R
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.launch

/** Exemple du catalogue : les prochains passages à Nation, sans réseau. [dirs] destinations, [minutes] attentes en min. */
class TransportSample(@StringRes val stop: Int, @ArrayRes val dirs: Int, val minutes: List<Int>) : WidgetSample

/**
 * Prochains passages à un arrêt d'Île-de-France, lus par la tablette chez IDFM ([Transport]), rafraîchis chaque minute.
 * L'arrêt (et une direction optionnelle) se règlent par les réglages du widget. Une mise en page par famille
 * ([TransportShape]) : Pastille et Ligne = le prochain passage en gros ; Colonne, Bloc, Grand et Plein écran = la liste
 * des prochains passages, l'arrêt en tête dès qu'il y a la place.
 */
class TransportWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val disc = hueDisc(context, R.drawable.ic_transport, R.attr.c_hue_green)
    private val title = cardTitle(R.string.widget_transport).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val head = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(disc, hueDiscParams(context, side = false))
        addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    }

    /** Pastille et Ligne : le prochain passage en gros (minutes, unité) et sa destination. */
    private val bigWait = label(40f, light = true).apply { isSingleLine = true }
    private val bigUnit = label(15f, R.attr.c_text_dim).apply { isSingleLine = true }
    private val bigDest = label(14f, R.attr.c_text_faint).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val lead = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                addView(bigWait)
                addView(bigUnit, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(4); bottomMargin = dp(8) })
            },
        )
        addView(bigDest)
    }

    /** Invite centrée (clé absente, aucun arrêt réglé, aucun passage), à la place du contenu, comme l'invite de Météo. */
    private val status = label(14f, R.attr.c_text_dim).apply {
        maxLines = 2
        gravity = Gravity.CENTER
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val view = LinearLayout(context).asCard(16)

    override val hasSettings get() = true

    private var shape = TransportShape.of(placed.size)

    /** Gravité de la carte quand elle montre son contenu (centrée verticalement en ligne, en haut sinon). */
    private var normalGravity = Gravity.TOP
    private var departures: List<Transport.Departure> = emptyList()

    /** Horloge de la tablette à la dernière lecture : les minutes se recalculent dessus sans refaire d'appel. */
    private var baseNow = 0L
    private var error: String? = null
    private var preview = false

    override fun setActive(active: Boolean) {
        if (active && !preview) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (preview) return
        if (Transport.keyless || stopId().isBlank()) return render()
        runCatchingCancellable { Transport.departures(stopId(), System.currentTimeMillis()) }
            .onSuccess {
                departures = Transport.pick(it, placed.config.optString("line"), placed.config.optString("direction"))
                baseNow = System.currentTimeMillis()
                error = null
                render()
            }
            .onFailure { error = context.errorText(it); render() }
    }

    override fun onMinute() = render()

    override fun applySize() {
        shape = TransportShape.of(placed.size)
        for (part in listOf(head, lead, rows, status, disc, title)) (part.parent as? ViewGroup)?.removeView(part)
        view.removeAllViews()
        normalGravity = if (shape.side) Gravity.CENTER_VERTICAL else Gravity.TOP
        if (shape.side) {
            view.orientation = LinearLayout.HORIZONTAL
            view.addView(lead)
            if (shape.rows > 0 && !pill()) view.addView(rows, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(16) })
        } else {
            view.orientation = LinearLayout.VERTICAL
            if (shape.title) view.addView(head)
            view.addView(rows, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = if (shape.title) dp(8) else 0 })
        }
        view.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        val example = sample as? TransportSample ?: return
        val dirs = context.resources.getStringArray(example.dirs)
        preview = true
        baseNow = System.currentTimeMillis()
        departures = example.minutes.mapIndexed { i, m -> Transport.Departure("", dirs.getOrElse(i) { "" }, m * 60_000L) }
        title.text = context.getString(example.stop)
        render()
    }

    private fun render() {
        val stopName = placed.config.optString("stop")
        if (!preview) title.text = stopName.ifBlank { context.getString(R.string.widget_transport) }
        val now = System.currentTimeMillis()
        val live = departures.filter { remaining(it, now) >= -60_000L }

        val message = when {
            !preview && Transport.keyless -> context.getString(R.string.widget_transport_no_key)
            !preview && stopId().isBlank() -> context.getString(R.string.widget_transport_choose)
            live.isEmpty() && error != null -> error
            live.isEmpty() -> context.getString(R.string.widget_transport_empty)
            else -> null
        }
        val showMsg = message != null
        status.text = message.orEmpty()
        status.visibility = if (showMsg) View.VISIBLE else View.GONE
        head.visibility = if (!showMsg && !shape.side && shape.title) View.VISIBLE else View.GONE
        lead.visibility = if (!showMsg && shape.side) View.VISIBLE else View.GONE
        rows.visibility = if (showMsg) View.GONE else View.VISIBLE
        view.gravity = if (showMsg) Gravity.CENTER else normalGravity
        if (showMsg) return

        if (shape.side) {
            renderLead(live, now)
            renderRows(live.drop(1).take(shape.rows), now)
        } else {
            renderRows(live.take(shape.rows), now)
        }
    }

    /** Pastille et Ligne : le prochain passage en gros. */
    private fun renderLead(live: List<Transport.Departure>, now: Long) {
        val next = live.firstOrNull() ?: return
        val mins = Transport.waitMinutes(remaining(next, now))
        if (mins <= 0) {
            bigWait.text = context.getString(R.string.widget_transport_now)
            bigUnit.visibility = View.GONE
        } else {
            bigWait.text = mins.toString()
            bigUnit.visibility = View.VISIBLE
            bigUnit.setText(R.string.widget_transport_unit)
        }
        bigDest.text = next.destination
        bigDest.visibility = if (next.destination.isBlank() || pill()) View.GONE else View.VISIBLE
    }

    private fun renderRows(items: List<Transport.Departure>, now: Long) {
        rows.removeAllViews()
        for (d in items) {
            val mins = Transport.waitMinutes(remaining(d, now))
            val wait = if (mins <= 0) context.getString(R.string.widget_transport_now) else context.getString(R.string.widget_transport_wait, mins)
            rows.addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        label(shape.sp).apply {
                            text = d.destination
                            isSingleLine = true
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                    )
                    addView(
                        label(shape.sp, R.attr.c_accent).apply {
                            text = wait
                            isSingleLine = true
                        },
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) },
                    )
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) },
            )
        }
    }

    override fun showSettings() = Transport.choose(context, host.scope, placed.config) {
        departures = emptyList()
        error = null
        host.save()
        render()
        host.scope.launch { load() }
    }

    private fun stopId() = placed.config.optString("stopId")
    private fun pill() = family == ShapeFamily.PILL
    private fun remaining(d: Transport.Departure, now: Long) = d.waitMs - (now - baseNow)
}
