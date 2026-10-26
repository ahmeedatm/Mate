package com.unowhy.companion.ui

import android.content.Context
import com.unowhy.companion.R
import com.unowhy.companion.audio.Speaker
import com.unowhy.companion.device.Tablet
import com.unowhy.companion.focus.Water

/** Rappels de la tablette elle-même, ordinateur éteint compris : boire de l'eau, batterie faible. */
class TabletAlerts(private val context: Context, private val banner: Banner) {

    /** Bandeau « batterie faible » déjà montré (de nouveau après une recharge). */
    private var lowBatteryWarned = false

    /** « Pense à boire » (carte Eau de l'Accueil) : bandeau et voix ; vérifié chaque minute. */
    fun remindWater() {
        if (!Water.reminderDue(context)) return
        val state = Water.state(context)
        banner.show(context.getString(R.string.water_remind_banner, state.glasses, state.goal), 30)
        Speaker.speak(context.getString(R.string.water_remind_text))
    }

    fun onBattery(state: Tablet.Battery) {
        if (state.low && !lowBatteryWarned) banner.show(context.getString(R.string.tablet_battery_low, state.level), 10)
        lowBatteryWarned = state.low || (lowBatteryWarned && !state.charging)
    }
}
