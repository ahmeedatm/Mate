package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportLinesTest {

    /** Extrait au format de assets/lignes-idfm.tsv : id, nom court, mode, fond, texte. */
    private val sample = TransportLines.parse(
        """
        # Lignes d'IDFM.
        C01371	1	metro	FFCD00	000000
        C01728	A	rer	E2231A	FFFFFF
        C01737	N	train	00A88F	FFFFFF
        C01389	T3a	tram	F18E1C	FFFFFF
        C01128	86	bus	FF8C00	000000
        C0XXXX	Z	bus	pas-une-couleur	000000
        ligne cassée
        """.trimIndent().lineSequence(),
    )

    @Test
    fun idOf_keeps_the_code_of_a_siri_line_ref() {
        assertEquals("C01371", TransportLines.idOf("STIF:Line::C01371:"))
    }

    @Test
    fun idOf_is_null_when_the_ref_has_no_code() {
        assertNull(TransportLines.idOf(""))
        assertNull(TransportLines.idOf("n'importe quoi"))
    }

    @Test
    fun parse_reads_name_mode_and_colors() {
        val rer = sample.getValue("C01728")
        assertEquals("A", rer.name)
        assertEquals(TransportLines.Mode.RER, rer.mode)
        assertEquals(0xFFE2231A.toInt(), rer.bg)
        assertEquals(0xFFFFFFFF.toInt(), rer.fg)
    }

    @Test
    fun parse_knows_every_mode() {
        val modes = listOf("C01371", "C01728", "C01737", "C01389", "C01128").map { sample.getValue(it).mode }
        assertEquals(
            listOf(
                TransportLines.Mode.METRO,
                TransportLines.Mode.RER,
                TransportLines.Mode.TRAIN,
                TransportLines.Mode.TRAM,
                TransportLines.Mode.BUS,
            ),
            modes,
        )
    }

    @Test
    fun parse_ignores_invalid_color_and_broken_lines() {
        assertEquals(5, sample.size)
        assertNull(sample["C0XXXX"])
    }

    @Test
    fun unknown_line_is_null() {
        assertNull(sample["C99999"])
    }
}
