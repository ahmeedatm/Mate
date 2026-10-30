package com.unowhy.unomate.ui

import android.view.View
import android.widget.TextView
import com.unowhy.unomate.R

/**
 * Bandeau en haut de l'écran, par-dessus le mode courant ; un toucher le ferme. Il arrive d'en haut et y repart
 * (Look.slideBanner). Deux lignes : l'objet (titre, toujours là) et, pour une notification, le corps en dessous (masqué
 * si vide, coupé s'il dépasse — mise en forme dans le layout).
 */
class Banner(private val view: View) {

    private val objet: TextView = view.findViewById(R.id.banner_objet)
    private val corps: TextView = view.findViewById(R.id.banner_corps)

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

    /** `objet` : titre toujours affiché ; `corps` : texte sous le titre, masqué s'il est vide. `onTap` : action en plus de fermer. */
    fun show(objet: String, corps: String?, seconds: Int, onTap: (() -> Unit)? = null) {
        this.onTap = onTap
        this.objet.text = objet
        val body = corps?.trim().orEmpty()
        this.corps.text = body
        this.corps.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
        val arriving = view.visibility != View.VISIBLE || leaving
        leaving = false
        view.visibility = View.VISIBLE
        if (arriving) Look.slideBanner(view, show = true)
        view.removeCallbacks(hide)
        view.postDelayed(hide, seconds * 1_000L)
    }
}
