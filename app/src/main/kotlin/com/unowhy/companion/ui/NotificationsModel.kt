package com.unowhy.companion.ui

import com.unowhy.companion.net.PcNotification
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Notifications de l'ordinateur : textes du bandeau et des lignes de l'historique, sans vue (testables sur la JVM).

/** Texte du bandeau au plus (le reste est dans l'historique, Depuis l'ordi > Notifications). */
private const val BANNER_BODY_MAX = 160

private val SPACES = Regex("\\s+")

/** « Outlook · Marie Dupont » ; l'appli seule quand le titre est vide. */
fun notificationHeading(n: PcNotification): String {
    val title = n.title.trim()
    return if (title.isEmpty()) n.app else "${n.app} · $title"
}

/** Texte de la notification pour la liste ; null en mode discret ou s'il est vide. */
fun notificationBody(n: PcNotification, discreet: Boolean): String? =
    if (discreet) null else n.body.trim().ifEmpty { null }

/**
 * Bandeau : « App · Titre », puis le texte sur une seconde ligne, mis sur une ligne (retours et espaces répétés
 * fondus) et coupé à [BANNER_BODY_MAX] caractères puis « … ». En mode discret : « App · Titre » seulement.
 */
fun notificationBannerText(n: PcNotification, discreet: Boolean): String {
    val heading = notificationHeading(n)
    val body = notificationBody(n, discreet)?.replace(SPACES, " ") ?: return heading
    return heading + "\n" + displayText(body, BANNER_BODY_MAX)
}

/**
 * Heure d'une ligne de l'historique : « 14:32 » le jour même, sinon précédée du jour, « mer. 28 oct. · 14:32 »
 * (l'historique du compagnon garde 24 h : la veille au plus).
 */
fun notificationTime(at: Long, now: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = zone }.format(Date(at))
    if (sameDay(at, now, zone)) return time
    val day = SimpleDateFormat("EEE d MMM", locale).apply { timeZone = zone }.format(Date(at))
    return "$day · $time"
}

private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
    val ca = Calendar.getInstance(zone).apply { timeInMillis = a }
    val cb = Calendar.getInstance(zone).apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}
