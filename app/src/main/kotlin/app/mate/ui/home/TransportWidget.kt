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

/**
 * Exemple du catalogue : les prochains passages à Nation, sans réseau. [dirs] : destinations, [minutes] : attentes en
 * min, [lines] : identifiants IDFM des lignes (`C01371`), dans le même ordre. [trafficLine] porte [trafficText] (un
 * exemple d'info trafic, sa gravité se lit dans le texte comme en vrai).
 */
class TransportSample(
    @StringRes val stop: Int,
    @ArrayRes val dirs: Int,
    val minutes: List<Int>,
    val lines: List<String>,
    val trafficLine: String? = null,
    @StringRes val trafficText: Int? = null,
) : WidgetSample

/**
 * Prochains passages à un arrêt d'Île-de-France, lus par la tablette chez IDFM ([Transport]), rafraîchis chaque minute.
 * L'arrêt (et une direction optionnelle) se règlent par les réglages du widget. Dessiné en afficheur de quai
 * ([TransportBoard]) : carte du thème, écran à points et jauge d'approche ; une mise en page par famille ([TransportShape]). Le picto de
 * chaque ligne vient du référentiel d'IDFM ([TransportLines]) ; « À l'approche » clignote ([Blinker]). L'info trafic des
 * lignes de l'arrêt ([TransportTraffic]) se relit toutes les 5 minutes : pastille sur le picto, jauge rayée, mot en tête ;
 * un toucher pose la carte des messages, qui se retire au toucher suivant ou après 10 s.
 */
class TransportWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(CARD_PAD)

    override val hasSettings get() = true

    private val board = TransportBoard(context, view)
    private val blinker = Blinker({ board.blinking }, { Prefs.reduceMotion(context) })
    private var shape = TransportShape.of(placed.size)
    private var departures: List<Transport.Departure> = emptyList()
    private var lines: Map<String, TransportLines.Line> = emptyMap()
    private var traffic: List<TransportTraffic.Message> = emptyList()
    private var trafficLines: List<String> = emptyList()
    private var trafficAt = 0L
    private val closeNote = Runnable { board.hideNote() }
    private var active = false

    /** Horloge de la tablette à la dernière lecture : les minutes se recalculent dessus sans refaire d'appel. */
    private var baseNow = 0L
    private var error: String? = null
    private var preview = false
    private var previewStop = ""

    init {
        // Pas encore d'arrêt réglé : un toucher ouvre le choix, comme le widget Météo sans ville. Sinon il montre l'info trafic.
        view.setOnClickListener {
            when {
                preview || Transport.keyless -> Unit
                stopId().isBlank() -> showSettings()
                traffic.isNotEmpty() -> toggleNote()
            }
        }
    }

    private fun toggleNote() {
        view.removeCallbacks(closeNote)
        if (board.noteShown) return board.hideNote()
        board.showNote(
            TransportTraffic.sorted(traffic).map { m ->
                val line = TransportLines.idOf(m.lineRef)?.let { lines[it] }
                TrafficNote(line, PictoSpec.of(line, themeColor(context, R.attr.c_bg), themeColor(context, R.attr.c_text)), m.level, m.text)
            },
        )
        view.postDelayed(closeNote, NOTE_MS)
    }

    override fun setActive(active: Boolean) {
        this.active = active
        blinker.sync(active && !preview)
        if (!active) {
            view.removeCallbacks(closeNote)
            board.hideNote()
        }
        if (active && !preview) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (preview) return
        if (Transport.keyless || stopId().isBlank()) return render()
        runCatchingCancellable { TransportLines.load(context) }.onSuccess { lines = it }
        runCatchingCancellable { Transport.departures(stopId(), System.currentTimeMillis()) }
            .onSuccess {
                departures = Transport.pick(
                    it,
                    placed.config.optString("line"),
                    placed.config.optString("direction"),
                    placed.config.optString("side"),
                )
                baseNow = System.currentTimeMillis()
                error = null
                render()
                loadTraffic(it)
            }
            .onFailure { error = context.errorText(it); render() }
    }

    /** Info trafic des lignes vues à l'arrêt, toutes les 5 minutes ; en échec, l'info d'avant reste (pas de faux « tout va bien »). */
    private suspend fun loadTraffic(all: List<Transport.Departure>) {
        val watched = TransportTraffic.watched(all, placed.config.optString("line"))
        val now = System.currentTimeMillis()
        if (!TransportTraffic.due(trafficAt, trafficLines, watched, now)) return
        runCatchingCancellable { TransportTraffic.load(watched, now) }.onSuccess {
            traffic = it
            trafficLines = watched
            trafficAt = now
            if (traffic.isEmpty()) board.hideNote()
            render()
        }
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
        traffic = example.trafficLine?.let { line ->
            example.trafficText?.let { res ->
                val text = context.getString(res)
                listOf(TransportTraffic.Message("STIF:Line::$line:", TransportTraffic.level(text, System.currentTimeMillis()), text))
            }
        }.orEmpty()
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
        val content = BoardContent(stop.ifBlank { context.getString(R.string.widget_transport) }, items(live, now), TransportTraffic.worst(traffic))
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

    private fun items(live: List<Transport.Departure>, now: Long): List<BoardItem> {
        val badged = HashSet<String>()
        return live.take(shape.rows + 1).map { d -> item(d, live, now, if (badged.add(d.lineRef)) TransportTraffic.worst(traffic, d.lineRef) else null) }
    }

    /** [level] : info trafic, seulement sur le premier passage de sa ligne, pour ne pas répéter la pastille à chaque rangée. */
    private fun item(d: Transport.Departure, live: List<Transport.Departure>, now: Long, level: TransportTraffic.Level?): BoardItem {
        val line = TransportLines.idOf(d.lineRef)?.let { lines[it] }
        val then = Transport.following(live, d)?.let { Transport.waitMinutes(remaining(it, now)) }
        return BoardItem(
            line = line,
            picto = PictoSpec.of(line, themeColor(context, R.attr.c_bg), themeColor(context, R.attr.c_text)),
            destination = d.destination,
            wait = Transport.waitOf(Transport.waitMinutes(remaining(d, now))),
            thenMinutes = then,
            approach = Transport.approach(remaining(d, now)),
            traffic = level,
        )
    }

    override fun showSettings() = Transport.choose(context, host.scope, placed.config) {
        departures = emptyList()
        traffic = emptyList()
        trafficAt = 0L
        board.hideNote()
        error = null
        host.save()
        render()
        host.scope.launch { load() }
    }

    private fun stopId() = placed.config.optString("stopId")
    private fun remaining(d: Transport.Departure, now: Long) = d.waitMs - (now - baseNow)

    private companion object {
        const val CARD_PAD = 7
        const val MINUTE_MS = 60_000L
        const val NOTE_MS = 10_000L
    }
}
