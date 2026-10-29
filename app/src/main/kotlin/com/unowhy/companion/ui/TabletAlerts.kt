package com.unowhy.companion.ui

import android.content.Context
import com.unowhy.companion.R
import com.unowhy.companion.audio.Speaker
import com.unowhy.companion.device.Tablet
import com.unowhy.companion.focus.Water
import com.unowhy.companion.ui.home.HomeLayout
import com.unowhy.companion.ui.home.WidgetType

/** Rappels de la tablette elle-même, ordinateur éteint compris : boire de l'eau, batterie faible. */
class TabletAlerts(private val context: Context, private val banner: Banner) {

    /** Bandeau « batterie faible » déjà montré (de nouveau après une recharge). */
    private var lowBatteryWarned = false

    /** « Pense à boire » : bandeau et voix ; vérifié chaque minute. Seulement si l'Accueil montre l'eau (widget Eau, ou l'Heure de Cadran). */
    fun remindWater() {
        val cadran = themeBool(context, R.attr.c_cadran)
        val shown = HomeLayout.has(context, cadran, WidgetType.WATER) || (cadran && (HomeLayout.has(context, true, WidgetType.HERO) || HomeLayout.has(context, true, WidgetType.DAY)))
        if (!shown || !Water.reminderDue(context)) return
        val state = Water.state(context)
        banner.show(context.getString(R.string.water_remind_banner, state.glasses, state.goal), 30)
        Speaker.speak(context.getString(R.string.water_remind_text))
    }

    fun onBattery(state: Tablet.Battery) {
        if (state.low && !lowBatteryWarned) banner.show(context.getString(R.string.tablet_battery_low, state.level), 10)
        lowBatteryWarned = state.low || (lowBatteryWarned && !state.charging)
    }
}
