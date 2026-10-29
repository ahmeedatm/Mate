package com.unowhy.companion.ui

import android.view.View
import android.widget.TextView

/** Bandeau en haut de l'écran, par-dessus le mode courant ; un toucher le ferme. Il arrive d'en haut et y repart (Look.slideBanner). */
class Banner(private val view: TextView) {

    /** En train de repartir : un nouveau message le fait revenir avant qu'il soit masqué. */
    private var leaving = false

    private val hide = Runnable {
        if (view.visibility != View.VISIBLE) return@Runnable
        leaving = true
        Look.slideBanner(view, show = false) { if (leaving) view.visibility = View.GONE }
    }

    private var onTap: (() -> Unit)? = null

    init {
        // Annoncé par TalkBack dès qu'il change, sans couper ce qui est en train d'être lu.
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        view.setOnClickListener {
            hide.run()
            onTap?.invoke()
        }
    }

    /** `onTap` : en plus de la fermer, un toucher fait cette action. */
    fun show(text: String, seconds: Int, onTap: (() -> Unit)? = null) {
        this.onTap = onTap
        view.text = text
        val arriving = view.visibility != View.VISIBLE || leaving
        leaving = false
        view.visibility = View.VISIBLE
        if (arriving) Look.slideBanner(view, show = true)
        view.removeCallbacks(hide)
        view.postDelayed(hide, seconds * 1_000L)
    }
}
