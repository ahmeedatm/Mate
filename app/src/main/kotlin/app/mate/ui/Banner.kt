package app.mate.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.lifecycle.LifecycleCoroutineScope
import app.mate.R
import app.mate.net.BackendClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Icône d'un bandeau : celle d'une appli de l'ordinateur (initiale de sa teinte en attendant), ou un symbole teinté. */
sealed class BannerIcon {
    data class App(val name: String) : BannerIcon()

    data class Symbol(@DrawableRes val icon: Int, @AttrRes val hue: Int) : BannerIcon()

    companion object {
        /** Message de l'ordinateur ou de l'appli, sans icône plus précise. */
        val MESSAGE = Symbol(R.drawable.ic_info, R.attr.c_hue_blue)
    }
}

/** Contenu d'un bandeau : `label` = appli d'une notification (null pour un message), `title` toujours là, `body` dessous. */
data class BannerContent(val title: String, val body: String? = null, val label: String? = null, val icon: BannerIcon = BannerIcon.MESSAGE)

/**
 * Bandeau à droite sous la cloche du bandeau du haut (banner.xml), par-dessus le mode courant ; un toucher le ferme. Il
 * arrive d'en haut et y repart (Look.slideBanner). Trait et icône de la couleur de l'appli (ou de la fonction), l'appli
 * et « à l'instant » pour une notification, l'objet, et le corps en dessous (masqué si vide, coupé s'il dépasse).
 */
class Banner(private val view: View, private val scope: LifecycleCoroutineScope) {

    private val image: ImageView = view.findViewById(R.id.banner_icon)
    private val initial: TextView = view.findViewById(R.id.banner_initial)
    private val objet: TextView = view.findViewById(R.id.banner_objet)
    private val corps: TextView = view.findViewById(R.id.banner_corps)
    private val label: TextView = view.findViewById(R.id.banner_label)
    private val whenText: View = view.findViewById(R.id.banner_when)
    private val accent: View = view.findViewById(R.id.banner_accent)

    /** En train de repartir : un nouveau message le fait revenir avant qu'il soit masqué. */
    private var leaving = false

    private val hide = Runnable {
        if (view.visibility != View.VISIBLE) return@Runnable
        leaving = true
        Look.slideBanner(view, show = false) { if (leaving) view.visibility = View.GONE }
    }

    private var onTap: (() -> Unit)? = null
    private var iconJob: Job? = null

    init {
        // Annoncé par TalkBack dès qu'il change, sans couper ce qui est en train d'être lu.
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        view.setOnClickListener {
            hide.run()
            onTap?.invoke()
        }
        // Icône d'appli : coins arrondis comme une tuile, sans dessiner de cadre.
        image.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, v.width * 0.26f)
        }
    }

    /** `onTap` : action en plus de fermer. */
    fun show(content: BannerContent, seconds: Int, onTap: (() -> Unit)? = null) {
        this.onTap = onTap
        objet.text = content.title
        corps.setTextOrGone(content.body?.trim())
        label.setTextOrGone(content.label?.trim())
        whenText.visibility = if (content.label.isNullOrBlank()) View.GONE else View.VISIBLE
        accent.backgroundTintList = ColorStateList.valueOf(showIcon(content.icon))
        val arriving = view.visibility != View.VISIBLE || leaving
        leaving = false
        view.visibility = View.VISIBLE
        if (arriving) Look.slideBanner(view, show = true)
        view.removeCallbacks(hide)
        view.postDelayed(hide, seconds * 1_000L)
    }

    /** Fait disparaître le bandeau tout de suite, sans animation : un écran modal (onboarding) s'ouvre par-dessus. */
    fun hideNow() {
        view.removeCallbacks(hide)
        leaving = false
        view.visibility = View.GONE
    }

    /** Pose l'icône et rend la teinte du trait d'accent. */
    private fun showIcon(icon: BannerIcon): Int {
        val context = view.context
        iconJob?.cancel()
        return when (icon) {
            is BannerIcon.Symbol -> {
                val color = themeColor(context, icon.hue)
                val sizeDp = (image.layoutParams.width / image.resources.displayMetrics.density).toInt()
                initial.visibility = View.GONE
                image.clipToOutline = false
                image.setImageDrawable(Look.badge(context, icon.icon, color, sizeDp, sizeDp * 5 / 9))
                image.visibility = View.VISIBLE
                color
            }
            is BannerIcon.App -> {
                val showBitmap = { bitmap: Bitmap ->
                    image.clipToOutline = true
                    image.setImageBitmap(bitmap)
                    image.visibility = View.VISIBLE
                    initial.visibility = View.GONE
                }
                val cached = IconCache.peekApp(icon.name)
                if (cached != null) {
                    showBitmap(cached)
                } else {
                    image.visibility = View.INVISIBLE
                    initial.text = icon.name.trim().take(1).uppercase()
                    Look.tintInitial(initial, icon.name)
                    initial.visibility = View.VISIBLE
                    iconJob = scope.launch { IconCache.loadApp(BackendClient(context), icon.name)?.let(showBitmap) }
                }
                themeColor(context, Look.nameHue(icon.name))
            }
        }
    }

    private fun TextView.setTextOrGone(value: String?) {
        text = value
        visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
