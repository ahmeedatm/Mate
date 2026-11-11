package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import app.mate.R
import app.mate.ui.dp

@StringRes
fun TransportLines.Mode.letterRes() = when (this) {
    TransportLines.Mode.METRO -> R.string.widget_transport_badge_metro
    TransportLines.Mode.RER -> R.string.widget_transport_badge_rer
    TransportLines.Mode.TRAIN -> R.string.widget_transport_badge_train
    TransportLines.Mode.TRAM -> R.string.widget_transport_badge_tram
    TransportLines.Mode.BUS -> R.string.widget_transport_badge_bus
}

/** Nom parlé du mode et de la ligne : « métro 1 », « RER A » (format à un argument). */
@StringRes
fun TransportLines.Mode.spokenRes() = when (this) {
    TransportLines.Mode.METRO -> R.string.widget_transport_spoken_metro
    TransportLines.Mode.RER -> R.string.widget_transport_spoken_rer
    TransportLines.Mode.TRAIN -> R.string.widget_transport_spoken_train
    TransportLines.Mode.TRAM -> R.string.widget_transport_spoken_tram
    TransportLines.Mode.BUS -> R.string.widget_transport_spoken_bus
}

/** Icône de mode du bandeau : lettre (M, RER, T, BUS) cerclée ; ronde pour métro et tram, carrée arrondie sinon. */
fun modeBadge(context: Context, mode: TransportLines.Mode, sizeDp: Int, color: Int): TextView = TextView(context).apply {
    val round = mode == TransportLines.Mode.METRO || mode == TransportLines.Mode.TRAM
    val letter = context.getString(mode.letterRes())
    text = letter
    setTextColor(color)
    typeface = ResourcesCompat.getFont(context, R.font.barlow_semicondensed_600)
    textSize = sizeDp * if (letter.length > 1) MULTI else SINGLE
    includeFontPadding = false
    gravity = Gravity.CENTER
    minWidth = context.dp(sizeDp)
    minHeight = context.dp(sizeDp)
    if (letter.length > 1) setPadding(context.dp(3), 0, context.dp(3), 0)
    background = GradientDrawable().apply {
        shape = if (round) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        cornerRadius = context.dp(5).toFloat()
        setStroke(context.dp(2), color)
    }
    // Décoratif : le nom parlé de la ligne est lu sur la rangée.
    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

private const val SINGLE = 0.6f
private const val MULTI = 0.36f
