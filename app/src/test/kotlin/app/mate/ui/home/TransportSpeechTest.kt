package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ce que TalkBack lit pour une rangée du widget Transports, avec les vrais textes de strings.xml. */
class TransportSpeechTest {

    private val strings = Regex("""<string name="(\w+)">(.*?)</string>""")
        .findAll(File("src/main/res/values/strings.xml").readText())
        .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private val words = TransportSpeech.Words(
        lineFormats = TransportLines.Mode.entries.associateWith { strings.getValue("widget_transport_spoken_${it.name.lowercase()}") },
        atStop = strings.getValue("widget_transport_say_at_stop"),
        approaching = strings.getValue("widget_transport_say_approaching"),
        waitFormat = strings.getValue("widget_transport_say_wait"),
        rowFormat = strings.getValue("widget_transport_talk_row"),
    )

    private fun line(mode: TransportLines.Mode, name: String) = TransportLines.Line(name, mode, 0, 0)

    @Test
    fun a_row_says_line_destination_and_wait() {
        val said = TransportSpeech.row(words, line(TransportLines.Mode.METRO, "1"), "La Défense", Transport.waitOf(3))
        assertEquals("métro 1 vers La Défense, dans 3 min", said)
    }

    @Test
    fun each_mode_has_its_spoken_name() {
        fun name(mode: TransportLines.Mode, n: String) = TransportSpeech.row(words, line(mode, n), "X", Transport.waitOf(5)).substringBefore(" vers")
        assertEquals("RER A", name(TransportLines.Mode.RER, "A"))
        assertEquals("bus 86", name(TransportLines.Mode.BUS, "86"))
        assertEquals("tram T3a", name(TransportLines.Mode.TRAM, "T3a"))
        assertEquals("train N", name(TransportLines.Mode.TRAIN, "N"))
    }

    @Test
    fun at_stop_and_approaching_are_spoken_in_words() {
        val metro = line(TransportLines.Mode.METRO, "9")
        assertEquals("métro 9 vers Pont de Sèvres, à quai", TransportSpeech.row(words, metro, "Pont de Sèvres", Transport.waitOf(0)))
        assertEquals("métro 9 vers Pont de Sèvres, à l'approche", TransportSpeech.row(words, metro, "Pont de Sèvres", Transport.waitOf(1)))
    }

    @Test
    fun an_unknown_line_leaves_out_the_line_name_without_stray_commas() {
        assertEquals("vers La Défense, dans 4 min", TransportSpeech.row(words, null, "La Défense", Transport.waitOf(4)))
    }

    @Test
    fun the_sample_lines_exist_in_the_lines_file() {
        val ids = File("src/main/assets/lignes-idfm.tsv").readLines().filter { !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        val sample = WidgetSamples.of(WidgetType.TRANSPORT) as TransportSample
        assertTrue(sample.lines.isNotEmpty())
        assertEquals(emptyList<String>(), sample.lines.filter { it !in ids })
    }
}
