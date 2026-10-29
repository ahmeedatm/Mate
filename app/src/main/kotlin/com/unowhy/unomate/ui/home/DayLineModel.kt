package com.unowhy.unomate.ui.home

import com.unowhy.unomate.net.CalendarEvent
import java.util.Calendar

/** Ligne du jour : de 8 h à 20 h, les événements en barres, le passé effacé, « maintenant » en rouge. Calculs purs. */
object DayLineModel {
    const val FROM_HOUR = 8
    const val TO_HOUR = 20
    private const val SPAN_MS = (TO_HOUR - FROM_HOUR) * 3_600_000L

    enum class State { PAST, NEXT, LATER }

    /** Une barre : `from` et `to` en fraction de la ligne (0 à 1), déjà rognés à la fenêtre. */
    class Bar(val event: CalendarEvent, val from: Float, val to: Float, val state: State)

    /** 8 h du jour de `now`. */
    fun dayStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, FROM_HOUR)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Position de `ms` sur la ligne, bornée à 0..1. */
    fun fraction(ms: Long, dayStart: Long): Float = ((ms - dayStart).toFloat() / SPAN_MS).coerceIn(0f, 1f)

    /** `ms` tombe entre 8 h et 20 h : sinon le point « maintenant » est masqué. */
    fun inWindow(ms: Long, dayStart: Long): Boolean = ms in dayStart..dayStart + SPAN_MS

    /**
     * Barres du jour dans l'ordre du temps. Sont exclus : les événements sur la journée entière et ceux qui sortent de
     * la fenêtre. État : fini ([State.PAST]), le premier pas fini ([State.NEXT], en cours ou à venir), le reste.
     */
    fun bars(events: List<CalendarEvent>, dayStart: Long, now: Long): List<Bar> {
        val shown = events
            .filter { !it.allDay && it.endMs > dayStart && it.startMs < dayStart + SPAN_MS }
            .sortedBy { it.startMs }
        val next = shown.firstOrNull { it.endMs > now }
        return shown.map {
            val state = when {
                it.endMs <= now -> State.PAST
                it === next -> State.NEXT
                else -> State.LATER
            }
            Bar(it, fraction(it.startMs, dayStart), fraction(it.endMs, dayStart), state)
        }
    }

    /** Largeur offerte au titre de chaque barre : jusqu'à la barre suivante (moins `gap`), ou jusqu'au bord. Jamais négative. */
    fun labelRoom(bars: List<Bar>, width: Float, gap: Float): FloatArray = FloatArray(bars.size) { i ->
        val end = if (i + 1 < bars.size) bars[i + 1].from * width - gap else width
        (end - bars[i].from * width).coerceAtLeast(0f)
    }
}
