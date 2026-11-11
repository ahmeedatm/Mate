package app.mate.ui.home

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import app.mate.Prefs
import app.mate.R
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import app.mate.ui.themeColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exemple du catalogue : les prochains passages à Nation, sans réseau. [dirs] : destinations, [minutes] : attentes en
 * min, [lines] : identifiants IDFM des lignes (`C01371`), dans le même ordre.
 */
class TransportSample(@StringRes val stop: Int, @ArrayRes val dirs: Int, val minutes: List<Int>, val lines: List<String>) : WidgetSample

/**
 * Prochains passages à un arrêt d'Île-de-France, lus par la tablette chez IDFM ([Transport]), rafraîchis chaque minute.
 * L'arrêt (et une direction optionnelle) se règlent par les réglages du widget. Dessiné en afficheur de quai
 * ([TransportBoard]) : bandeau du thème et écran à points ; une mise en page par famille ([TransportShape]). Le picto de
 * chaque ligne vient du référentiel d'IDFM ([TransportLines]) ; « À l'approche » clignote ([Blinker]).
 */
class TransportWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(CARD_PAD)

    override val hasSettings get() = true

    private val board = TransportBoard(context, view)
    private val blinker = Blinker({ board.blinking }, { Prefs.reduceMotion(context) })
    private var shape = TransportShape.of(placed.size)
    private var departures: List<Transport.Departure> = emptyList()
    private var lines: Map<String, TransportLines.Line> = emptyMap()
    private var active = false

    /** Horloge de la tablette à la dernière lecture : les minutes se recalculent dessus sans refaire d'appel. */
    private var baseNow = 0L
    private var error: String? = null
    private var preview = false
    private var previewStop = ""

    init {
        // Pas encore d'arrêt réglé : un toucher ouvre le choix, comme le widget Météo sans ville.
        view.setOnClickListener { if (!preview && !Transport.keyless && stopId().isBlank()) showSettings() }
    }

    override fun setActive(active: Boolean) {
        this.active = active
        blinker.sync(active && !preview)
        if (active && !preview) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (preview) return
        if (Transport.keyless || stopId().isBlank()) return render()
        runCatchingCancellable { TransportLines.load(context) }.onSuccess { lines = it }
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
        board.layout(family, shape.rows)
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        val example = sample as? TransportSample ?: return
        val dirs = context.resources.getStringArray(example.dirs)
        preview = true
        baseNow = System.currentTimeMillis()
        departures = example.minutes.mapIndexed { i, m ->
            Transport.Departure("STIF:Line::${example.lines.getOrElse(i) { "" }}:", dirs.getOrElse(i) { "" }, m * MINUTE_MS)
        }
        previewStop = context.getString(example.stop)
        render()
        // Les pictos viennent d'un fichier des ressources lu une fois, hors du fil de l'interface : pas de réseau.
        host.scope.launch {
            lines = runCatchingCancellable { TransportLines.load(context) }.getOrDefault(emptyMap())
            render()
        }
    }

    private fun render() {
        val stop = if (preview) previewStop else placed.config.optString("stop")
        val now = System.currentTimeMillis()
        val live = departures.filter { remaining(it, now) >= -MINUTE_MS }
        val content = BoardContent(stop.ifBlank { context.getString(R.string.widget_transport) }, clock(now), items(live, now))
        val message = when {
            !preview && Transport.keyless -> context.getString(R.string.widget_transport_no_key)
            !preview && stopId().isBlank() -> context.getString(R.string.widget_transport_choose)
            live.isEmpty() && error != null -> error
            live.isEmpty() -> context.getString(R.string.widget_transport_empty)
            else -> null
        }
        if (message != null) board.showMessage(content, message) else board.show(content)
        blinker.sync(active && !preview)
    }

    private fun items(live: List<Transport.Departure>, now: Long): List<BoardItem> = live.take(shape.rows + 1).map { d ->
        val line = TransportLines.idOf(d.lineRef)?.let { lines[it] }
        val then = Transport.following(live, d)?.let { Transport.waitMinutes(remaining(it, now)) }
        BoardItem(
            line = line,
            picto = PictoSpec.of(line, themeColor(context, R.attr.c_bg), themeColor(context, R.attr.c_text)),
            destination = d.destination,
            wait = Transport.waitOf(Transport.waitMinutes(remaining(d, now))),
            thenMinutes = then,
        )
    }

    override fun showSettings() = Transport.choose(context, host.scope, placed.config) {
        departures = emptyList()
        error = null
        host.save()
        render()
        host.scope.launch { load() }
    }

    private fun clock(now: Long) = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(now))
    private fun stopId() = placed.config.optString("stopId")
    private fun remaining(d: Transport.Departure, now: Long) = d.waitMs - (now - baseNow)

    private companion object {
        const val CARD_PAD = 7
        const val MINUTE_MS = 60_000L
    }
}
