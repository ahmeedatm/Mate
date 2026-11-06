package app.mate.ui.home

import android.content.Context
import android.view.View
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.CalendarEvent
import app.mate.ui.CallSession
import app.mate.ui.errorText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val timeFormat get() = SimpleDateFormat("HH:mm", Locale.FRANCE)
private val dayHeader get() = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
private val dayName get() = SimpleDateFormat("EEEE", Locale.FRANCE)

/** Jours entre aujourd'hui et `ms` (0 = aujourd'hui), au calendrier (pas en tranches de 24 h). */
internal fun daysFrom(now: Long, ms: Long): Int {
    val a = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12) }
    val b = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12) }
    return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
}

/** « dans 23 min », « à 17:00 », « En cours, jusqu'à 15:30 », « demain à 9:30 », « mardi à 9:30 ». */
internal fun NextLine.whenText(context: Context): String = when (this) {
    is NextLine.InMinutes -> context.getString(R.string.in_minutes, minutes)
    is NextLine.At -> context.getString(R.string.at_time, timeFormat.format(Date(startMs)))
    is NextLine.Now -> context.getString(R.string.now_until, timeFormat.format(Date(endMs)))
    is NextLine.Tomorrow -> context.getString(R.string.tomorrow_at, timeFormat.format(Date(startMs)))
    is NextLine.Day -> context.getString(
        R.string.day_at, if (days < 7) dayName.format(Date(startMs)) else dayHeader.format(Date(startMs)), timeFormat.format(Date(startMs)),
    )
}

/** « Rejoindre » : l'ordinateur ouvre la réunion, puis le profil Réunion s'affiche jusqu'à la fin de l'événement. */
internal fun joinMeeting(host: HomeHost, event: CalendarEvent, button: View) {
    val context = button.context
    button.isEnabled = false
    host.scope.launch {
        val main = host.main
        try {
            val kind = BackendClient(context).calendar.joinMeeting(event.id)
            main?.showBanner(context.getString(R.string.join_opened, event.title), 6)
            // Profil Réunion : micro, caméra, son de l'appel, jusqu'à la fin de l'événement (ou de l'appel vu par l'ordinateur).
            main?.startCall(CallSession.appOfKind(kind), event.endMs)
        } catch (e: Exception) {
            main?.showBanner(context.errorText(e), 6)
        }
        button.isEnabled = true
    }
}
