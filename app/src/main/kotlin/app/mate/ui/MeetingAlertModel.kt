package app.mate.ui

import app.mate.net.CalendarEvent
import java.util.Locale

/**
 * Alerte plein écran avant une réunion : quelle réunion annoncer, quand, et comment écrire le compte à rebours.
 * Une réunion est un événement horaire de l'agenda (comme le widget Prochain rendez-vous), avec ou sans lien de visio.
 */
object MeetingAlertModel {
    /** L'alerte monte 5 min avant le début. */
    const val LEAD_MS = 5 * 60_000L

    /** Sous cette durée le compte à rebours passe au rouge (« maintenant »). */
    const val URGENT_MS = 60_000L

    /** Une alerte par réunion : début, id et titre, pour qu'une réunion déplacée ou renommée soit annoncée de nouveau. */
    fun keyOf(event: CalendarEvent) = "${event.startMs}|${event.id}|${event.title}"

    /** La première réunion à annoncer : horaire, pas commencée, début dans 5 min au plus, pas déjà annoncée. */
    fun due(events: List<CalendarEvent>, now: Long, seen: Set<String>): CalendarEvent? =
        events.filter { !it.allDay && it.startMs > now && it.startMs - now <= LEAD_MS && keyOf(it) !in seen }
            .minByOrNull { it.startMs }

    /** `seen` plus `key`, sans les réunions commencées depuis plus d'un jour (le début est le premier champ de la clé). */
    fun remember(seen: Set<String>, key: String, now: Long): Set<String> =
        seen.filter { (it.substringBefore('|').toLongOrNull() ?: 0L) > now - 86_400_000L }.toSet() + key

    /** Pas d'alerte pendant un appel (ou une sonnerie), dans la Caméra plein écran, ni pendant une saisie. */
    fun blocked(inCall: Boolean, camera: Boolean, typing: Boolean) = inCall || camera || typing

    fun remainingMs(event: CalendarEvent, now: Long) = (event.startMs - now).coerceAtLeast(0L)

    fun urgent(remainingMs: Long) = remainingMs < URGENT_MS

    /** « 04:59 » : secondes arrondies au-dessus, pour que « 00:00 » ne s'affiche qu'au début. */
    fun countdown(remainingMs: Long): String {
        val seconds = ((remainingMs.coerceAtLeast(0L) + 999) / 1000).toInt()
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    /** Minutes restantes arrondies au-dessus, pour l'annonce parlée (« dans 5 min »). */
    fun minutesLeft(remainingMs: Long): Int = ((remainingMs.coerceAtLeast(0L) + 59_999) / 60_000).toInt()
}
