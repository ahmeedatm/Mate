package app.mate.ui.home

import app.mate.net.CalendarEvent

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
}
