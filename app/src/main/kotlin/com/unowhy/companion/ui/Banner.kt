package com.unowhy.companion.ui

import android.view.View
import android.widget.TextView

/** Bandeau en haut de l'écran, par-dessus le mode courant ; un toucher le ferme. */
class Banner(private val view: TextView) {

    private val hide = Runnable { view.visibility = View.GONE }

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
        view.visibility = View.VISIBLE
        view.removeCallbacks(hide)
        view.postDelayed(hide, seconds * 1_000L)
    }
}
