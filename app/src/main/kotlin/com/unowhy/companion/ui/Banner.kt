package com.unowhy.companion.ui

import android.view.View
import android.widget.TextView

/** Bandeau en haut de l'écran, par-dessus le mode courant ; un toucher le ferme. */
class Banner(private val view: TextView) {

    private val hide = Runnable { view.visibility = View.GONE }

    init {
        view.setOnClickListener { hide.run() }
    }

    fun show(text: String, seconds: Int) {
        view.text = text
        view.visibility = View.VISIBLE
        view.removeCallbacks(hide)
        view.postDelayed(hide, seconds * 1_000L)
    }
}
