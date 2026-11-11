package app.mate.ui.home

import android.content.Context
import app.mate.R

/** Phrase lue par TalkBack pour une rangée du widget Transports : « métro 1 vers La Défense, dans 3 min ». */
object TransportSpeech {
    /** Formats de strings.xml (les `%` y sont déjà prêts) ; séparés du [Context] pour rester testables sans Android. */
    class Words(
        val lineFormats: Map<TransportLines.Mode, String>,
        val atStop: String,
        val approaching: String,
        val waitFormat: String,
        val rowFormat: String,
    )

    fun row(words: Words, line: TransportLines.Line?, destination: String, wait: Transport.Wait): String {
        val name = line?.let { words.lineFormats.getValue(it.mode).format(it.name) }.orEmpty()
        val waiting = when (wait.kind) {
            Transport.Wait.Kind.AT_STOP -> words.atStop
            Transport.Wait.Kind.APPROACHING -> words.approaching
            Transport.Wait.Kind.NUMBER -> words.waitFormat.format(wait.minutes)
        }
        return words.rowFormat.format(name, destination, waiting).trim(' ', ',')
    }

    fun words(context: Context) = Words(
        lineFormats = TransportLines.Mode.entries.associateWith { context.getString(it.spokenRes()) },
        atStop = context.getString(R.string.widget_transport_say_at_stop),
        approaching = context.getString(R.string.widget_transport_say_approaching),
        waitFormat = context.getString(R.string.widget_transport_say_wait),
        rowFormat = context.getString(R.string.widget_transport_talk_row),
    )
}
