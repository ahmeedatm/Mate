package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.CalendarEvent
import app.mate.ui.CallSession
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.errorText
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import app.mate.ui.themePx
import app.mate.ui.themeRes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val timeFormat get() = SimpleDateFormat("HH:mm", Locale.FRANCE)
private val dayHeader get() = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
private val dayName get() = SimpleDateFormat("EEEE", Locale.FRANCE)
private val shortDay get() = SimpleDateFormat("EEE", Locale.FRANCE)

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
