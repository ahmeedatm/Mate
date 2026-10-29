package com.unowhy.unomate.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.unowhy.unomate.R
import com.unowhy.unomate.device.Tablet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Heure, WiFi et batterie dans la barre latérale : le kiosque masque la barre d'état d'Android.
 * Mis à jour à chaque minute et à chaque changement (batterie, réseau). `onMinute` et `onBattery` : pour
 * MainActivity (luminosité de nuit, bandeau « batterie faible »).
 */
class TabletStatusView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onMinute: () -> Unit = {}
    var onBattery: (Tablet.Battery) -> Unit = {}

    private val time: TextView
    private val wifi: ImageView
    private val battery: ImageView
    private val level: TextView
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> renderBattery(Tablet.battery(intent))
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    renderTime()
                    onMinute()
                }
                else -> renderWifi()
            }
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_tablet_status, this, true)
        time = findViewById(R.id.tablet_time)
        wifi = findViewById(R.id.tablet_wifi)
        battery = findViewById(R.id.tablet_battery)
        level = findViewById(R.id.tablet_battery_level)
    }

    /** Pied du menu Cadran : l'heure, puis WiFi et batterie, sur une ligne alignée à gauche. */
    fun inline() {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = null
        setPadding(0, 0, 0, 0)
        (getChildAt(1).layoutParams as LayoutParams).apply {
            topMargin = 0
            marginStart = context.dp(12)
        }
        time.textSize = 14f
        time.setTextColor(themeColor(context, R.attr.c_text_dim))
        level.textSize = 14f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION") // suffisant pour un récepteur déclaré dans le code
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        // La batterie est une diffusion persistante : reçue tout de suite à l'enregistrement.
        context.registerReceiver(receiver, filter)
        renderTime()
        renderWifi()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(receiver)
        super.onDetachedFromWindow()
    }

    private fun renderTime() {
        time.text = timeFormat.format(Date())
    }

    private fun renderWifi() {
        val signal = Tablet.wifiLevel(context)
        // Signal faible : arcs estompés en plus de l'ambre (pas la couleur seule).
        wifi.setImageResource(
            when {
                signal == null -> R.drawable.ic_wifi_off
                signal <= 1 -> R.drawable.ic_wifi_weak
                else -> R.drawable.ic_wifi
            },
        )
        tint(wifi, when {
            signal == null -> R.attr.c_ko
            signal <= 1 -> R.attr.c_warn
            else -> R.attr.c_text_dim
        })
        wifi.contentDescription = context.getString(
            when {
                signal == null -> R.string.wifi_off
                signal <= 1 -> R.string.wifi_weak
                else -> R.string.wifi_current_unnamed
            },
        )
    }

    private fun renderBattery(state: Tablet.Battery) {
        battery.setImageResource(
            when {
                state.charging -> R.drawable.ic_battery_charging
                state.low || state.healthIssue -> R.drawable.ic_battery_alert
                else -> R.drawable.ic_battery
            },
        )
        val color = when {
            state.low -> R.attr.c_ko
            state.charging -> R.attr.c_ok
            else -> R.attr.c_text_dim
        }
        tint(battery, color)
        level.setTextColor(themeColor(context, color))
        level.text = context.getString(R.string.percent, state.level)
        // L'icône dit déjà « en charge » et « faible » ; TalkBack aussi.
        contentDescription = context.getString(
            when {
                state.charging -> R.string.tablet_battery_state_charging
                state.low -> R.string.tablet_battery_state_low
                else -> R.string.tablet_battery_state
            },
            state.level,
        )
        onBattery(state)
    }

    private fun tint(view: ImageView, color: Int) =
        ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(themeColor(context, color)))
}
