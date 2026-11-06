package app.mate.ui.home

import app.mate.net.CalendarEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ce que le Prochain rendez-vous montre dans une forme (#180, direction Cadran) : [across] = l'anneau à gauche du titre
 * (sinon au-dessus), [details] = lieu, début–fin et durée, [following] = rendez-vous suivants au plus (autant que la
 * place en laisse), [followBeside] = les suivants à droite (forme large et basse) plutôt que dessous.
 */
data class NextFace(val across: Boolean, val details: Boolean, val following: Int, val followBeside: Boolean) {
    companion object {
        fun of(size: WidgetSize): NextFace = when (size.family) {
            ShapeFamily.PILL -> NextFace(across = true, details = false, following = 0, followBeside = false)
            ShapeFamily.ROW -> NextFace(across = true, details = false, following = if (size.w == COLS) 1 else 0, followBeside = true)
            ShapeFamily.COLUMN -> NextFace(across = false, details = false, following = 4, followBeside = false)
            ShapeFamily.BLOCK -> NextFace(
                across = size.w >= size.h,
                details = true,
                following = if (size.h >= 3) 3 else if (size.w >= 3) 2 else 0,
                followBeside = size.h < 3,
            )
            ShapeFamily.LARGE -> NextFace(across = size.w > size.h, details = true, following = 5, followBeside = size.h < 3)
            ShapeFamily.FULL -> NextFace(across = true, details = true, following = 6, followBeside = false)
        }
    }
}

/** L'anneau du Prochain rendez-vous : part allumée, de 0 à 1. */
object NextRing {
    private const val HOUR_MS = 60 * 60_000L

    /**
     * Dans moins d'une heure : le temps qui reste, sur l'heure (15 min = un quart) ; en cours : le temps passé de la
     * réunion ; plus tard ou rien : vide.
     */
    fun fraction(event: CalendarEvent?, now: Long): Float {
        if (event == null) return 0f
        if (event.startMs <= now) {
            val length = (event.endMs - event.startMs).coerceAtLeast(1)
            return ((now - event.startMs).toFloat() / length).coerceIn(0f, 1f)
        }
        val left = event.startMs - now
        return if (left < HOUR_MS) left.toFloat() / HOUR_MS else 0f
    }

    /** Ce qui s'écrit sous la valeur : rien, « min », « en cours », « demain », ou le jour court. */
    enum class Unit { NONE, MIN, NOW, TOMORROW, DAY }

    data class Center(val value: String, val unit: Unit)

    /** Durée découpée : [hours] et [minutes] (« 45 min », « 1 h », « 1 h 30 »). */
    data class Length(val hours: Int, val minutes: Int)

    /** Au centre : les minutes avant (moins d'une heure), les minutes qui restent (en cours, minute entamée comptée), sinon l'heure. */
    fun center(event: CalendarEvent, now: Long): Center = when (val line = nextLine(event, now)) {
        is NextLine.InMinutes -> Center(line.minutes.coerceAtLeast(1).toString(), Unit.MIN)
        is NextLine.Now -> Center(((event.endMs - now + MINUTE - 1) / MINUTE).toString(), Unit.NOW)
        is NextLine.At -> Center(clock(event.startMs), Unit.NONE)
        is NextLine.Tomorrow -> Center(clock(event.startMs), Unit.TOMORROW)
        is NextLine.Day -> Center(clock(event.startMs), Unit.DAY)
    }

    fun length(minutes: Int) = Length(minutes / 60, minutes % 60)

    private const val MINUTE = 60_000L

    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(ms))
}
