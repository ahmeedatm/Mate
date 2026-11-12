package app.mate.ui

import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import app.mate.Computer
import app.mate.R
import app.mate.net.Link

/**
 * Témoin de l'ordinateur dans la pastille LCD du bandeau (thèmes Instrument), sans texte : le logo de l'ordinateur actif et
 * une diode, comme le témoin d'alimentation d'un appareil. Chaque état a sa forme (diode pleine, creuse, triangle, cadenas,
 * logo barré), pas seulement sa couleur ; des points sous le logo disent combien d'ordinateurs il y a et lequel est actif.
 */
internal object ComputerLamp {
    /** Au-delà, le dernier point vaut pour tous les suivants. */
    const val MAX_DOTS = 5

    enum class Glyph { MAC, WINDOWS, HOME, EMPTY }

    /** Logo plein, pâli (connexion en cours) ou pâli et barré (injoignable). */
    enum class Tone { FULL, DIM, CROSSED }

    /** Diode allumée, qui respire (connexion), creuse (éteinte), triangle (jeton refusé) ou cadenas (session verrouillée). */
    enum class Led { ON, BREATHING, RING, ALERT, LOCK }

    data class Look(
        val glyph: Glyph,
        val tone: Tone,
        val led: Led,
        @AttrRes val color: Int,
        val dots: Int,
        val activeDot: Int,
        @StringRes val spoken: Int,
    )

    fun of(link: Link, locked: Boolean, computers: List<Computer>): Look {
        val glyph = when {
            link.state == Link.State.HOME -> Glyph.HOME
            link.computer == null -> Glyph.EMPTY
            link.computer.os == "windows" -> Glyph.WINDOWS
            else -> Glyph.MAC
        }
        val spoken = linkMark(link.state, locked).spoken
        val dots = if (computers.size < 2) 0 else minOf(computers.size, MAX_DOTS)
        val index = computers.indexOfFirst { it.id == link.computer?.id }
        val activeDot = if (dots == 0 || index < 0) -1 else minOf(index, dots - 1)
        fun look(tone: Tone, led: Led, @AttrRes color: Int, @StringRes said: Int = spoken) = Look(glyph, tone, led, color, dots, activeDot, said)
        return when (link.state) {
            Link.State.ONLINE -> if (locked) look(Tone.FULL, Led.LOCK, R.attr.c_warn) else look(Tone.FULL, Led.ON, R.attr.c_ok)
            Link.State.HOME -> look(Tone.FULL, Led.ON, R.attr.c_ok)
            Link.State.UNAUTHORIZED -> look(Tone.FULL, Led.ALERT, R.attr.c_warn)
            Link.State.OFFLINE ->
                if (link.connecting) look(Tone.DIM, Led.BREATHING, R.attr.c_warn, R.string.link_connecting_spoken) else look(Tone.CROSSED, Led.RING, R.attr.c_ko)
            Link.State.NONE -> look(Tone.FULL, Led.RING, R.attr.c_text_faint)
        }
    }
}
