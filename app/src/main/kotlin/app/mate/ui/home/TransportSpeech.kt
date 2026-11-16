package app.mate.ui.home

import android.content.Context
import androidx.annotation.StringRes
import app.mate.R

/** Phrase lue par TalkBack pour une rangée du widget Transports : « métro 1 vers La Défense, dans 3 min, trafic perturbé ». */
object TransportSpeech {
    /** Formats de strings.xml (les `%` y sont déjà prêts) ; séparés du [Context] pour rester testables sans Android. */
    class Words(
        val lineFormats: Map<TransportLines.Mode, String>,
        val atStop: String,
        val approaching: String,
        val waitFormat: String,
        val rowFormat: String,
        val traffic: Map<TransportTraffic.Level, String> = emptyMap(),
    )

    fun row(words: Words, line: TransportLines.Line?, destination: String, wait: Transport.Wait, traffic: TransportTraffic.Level? = null): String {
        val name = line?.let { words.lineFormats.getValue(it.mode).format(it.name) }.orEmpty()
        val waiting = when (wait.kind) {
            Transport.Wait.Kind.AT_STOP -> words.atStop
            Transport.Wait.Kind.APPROACHING -> words.approaching
            Transport.Wait.Kind.NUMBER -> words.waitFormat.format(wait.minutes)
        }
        val said = words.rowFormat.format(name, destination, waiting).trim(' ', ',')
        return traffic?.let { words.traffic[it] }?.let { "$said, $it" } ?: said
    }

    fun words(context: Context) = Words(
        lineFormats = TransportLines.Mode.entries.associateWith { context.getString(it.spokenRes()) },
        atStop = context.getString(R.string.widget_transport_say_at_stop),
        approaching = context.getString(R.string.widget_transport_say_approaching),
        waitFormat = context.getString(R.string.widget_transport_say_wait),
        rowFormat = context.getString(R.string.widget_transport_talk_row),
        traffic = TransportTraffic.Level.entries.associateWith { context.getString(it.spokenRes()) },
    )
}

/** Gravité parlée : « trafic perturbé ». */
@StringRes
fun TransportTraffic.Level.spokenRes() = when (this) {
    TransportTraffic.Level.SOON -> R.string.widget_transport_say_traffic_soon
    TransportTraffic.Level.DISRUPTED -> R.string.widget_transport_say_traffic_disrupted
    TransportTraffic.Level.INTERRUPTED -> R.string.widget_transport_say_traffic_interrupted
}

/** Mot court de l'écran et titre de la carte : « Perturbé », « Trafic perturbé ». */
@StringRes
fun TransportTraffic.Level.wordRes() = when (this) {
    TransportTraffic.Level.SOON -> R.string.widget_transport_traffic_soon
    TransportTraffic.Level.DISRUPTED -> R.string.widget_transport_traffic_disrupted
    TransportTraffic.Level.INTERRUPTED -> R.string.widget_transport_traffic_interrupted
}

/** Titre de la carte d'info trafic : « Trafic perturbé ». */
@StringRes
fun TransportTraffic.Level.titleRes() = when (this) {
    TransportTraffic.Level.SOON -> R.string.widget_transport_traffic_title_soon
    TransportTraffic.Level.DISRUPTED -> R.string.widget_transport_traffic_title_disrupted
    TransportTraffic.Level.INTERRUPTED -> R.string.widget_transport_traffic_title_interrupted
}

/** Nom parlé du mode et de la ligne : « métro 1 », « RER A » (format à un argument). */
@StringRes
fun TransportLines.Mode.spokenRes() = when (this) {
    TransportLines.Mode.METRO -> R.string.widget_transport_spoken_metro
    TransportLines.Mode.RER -> R.string.widget_transport_spoken_rer
    TransportLines.Mode.TRAIN -> R.string.widget_transport_spoken_train
    TransportLines.Mode.TRAM -> R.string.widget_transport_spoken_tram
    TransportLines.Mode.BUS -> R.string.widget_transport_spoken_bus
}
