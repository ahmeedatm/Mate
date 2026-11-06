package app.mate.ui.home

import app.mate.net.CalendarEvent
import java.util.Calendar

/**
 * Ce que la Ligne du jour montre dans une forme (#180, direction Cadran). [band] : la Ligne garde la ligne animée
 * ([DayLineView]). Ailleurs, un en-tête « maintenant » ([DayNow]) et la journée en ligne de temps ([DayTrack]) : petite et
 * sans titres en Pastille, couchée avec les titres (Bloc, Grand large), debout avec l'heure et le titre de chaque
 * rendez-vous (Colonne, Grand étroit, Plein écran).
 */
data class DayLineFace(val band: Boolean, val line: Line) {
    enum class Line { MINI, ACROSS, DOWN }

    companion object {
        fun of(size: WidgetSize): DayLineFace = when (size.family) {
            ShapeFamily.ROW -> DayLineFace(band = true, line = Line.ACROSS)
            ShapeFamily.PILL -> DayLineFace(band = false, line = Line.MINI)
            ShapeFamily.COLUMN -> DayLineFace(band = false, line = Line.DOWN)
            ShapeFamily.BLOCK -> DayLineFace(band = false, line = Line.ACROSS)
            ShapeFamily.LARGE -> DayLineFace(band = false, line = if (size.w > size.h) Line.ACROSS else Line.DOWN)
            ShapeFamily.FULL -> DayLineFace(band = false, line = Line.DOWN)
        }
    }
}

/** Ce qui se passe maintenant : un rendez-vous jusqu'à sa fin, ou libre jusqu'au prochain du jour (null : plus rien aujourd'hui). */
sealed interface DayNow {
    data class Busy(val title: String, val untilMs: Long) : DayNow

    data class Free(val untilMs: Long?) : DayNow

    companion object {
        /** Les journées entières ne rendent pas occupé ; deux rendez-vous en même temps : le premier à finir. */
        fun of(events: List<CalendarEvent>, now: Long): DayNow {
            val timed = events.filter { !it.allDay }
            timed.filter { it.startMs <= now && now < it.endMs }.minByOrNull { it.endMs }?.let { return Busy(it.title, it.endMs) }
            // Fin du jour calculée une fois (pas deux calendriers par rendez-vous, chaque minute).
            val tomorrow = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val next = timed.filter { it.startMs in (now + 1) until tomorrow }.minByOrNull { it.startMs }
            return Free(next?.startMs)
        }
    }
}
