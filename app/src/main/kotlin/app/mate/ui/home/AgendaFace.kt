package app.mate.ui.home

import app.mate.net.CalendarEvent

/**
 * Ce que l'Agenda montre dans une forme (#180, direction Cadran) : la journée en ligne de temps (Pastille, Ligne), debout
 * (Colonne), la liste par jour (Bloc, Grand étroit), ou les 7 jours en colonnes (Grand large, Plein écran). [titles] :
 * le titre de chaque rendez-vous sur la ligne de temps.
 */
data class AgendaFace(val mode: Mode, val titles: Boolean) {
    enum class Mode { TRACK, TRACK_DOWN, LIST, WEEK }

    companion object {
        fun of(size: WidgetSize): AgendaFace = when (size.family) {
            ShapeFamily.PILL -> AgendaFace(Mode.TRACK, titles = false)
            ShapeFamily.ROW -> AgendaFace(Mode.TRACK, titles = size.w >= 3)
            ShapeFamily.COLUMN -> AgendaFace(Mode.TRACK_DOWN, titles = true)
            ShapeFamily.BLOCK -> AgendaFace(Mode.LIST, titles = true)
            ShapeFamily.LARGE -> AgendaFace(if (size.w > size.h) Mode.WEEK else Mode.LIST, titles = true)
            ShapeFamily.FULL -> AgendaFace(Mode.WEEK, titles = true)
        }
    }
}

/** Calculs de l'Agenda, sans vue. */
object AgendaPlan {
    const val WEEK_DAYS = 7

    /** Rendez-vous horaires d'aujourd'hui pas encore finis (en cours compris). */
    fun remaining(events: List<CalendarEvent>, now: Long) = events.count { !it.allDay && it.endMs > now && daysFrom(now, it.startMs) == 0 }

    /** Aujourd'hui et les 6 jours suivants : les rendez-vous de chaque jour ; commencé avant aujourd'hui = rangé aujourd'hui. */
    fun week(events: List<CalendarEvent>, now: Long): List<List<CalendarEvent>> {
        val byDay = events.groupBy { daysFrom(now, it.startMs).coerceAtLeast(0) }
        return List(WEEK_DAYS) { byDay[it].orEmpty().sortedBy { e -> e.startMs } }
    }
}
