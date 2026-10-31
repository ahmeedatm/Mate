package app.mate.ui

import android.graphics.BitmapFactory
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.mate.MainActivity
import app.mate.Prefs
import app.mate.R
import app.mate.frame.FrameLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cadre photo : les photos du dossier « Cadre photo » de l'ordinateur, en plein écran, l'heure et la date en bas à
 * gauche. Pour l'écran, rien ne bouge entre deux photos : un fondu court, puis aucune image redessinée jusqu'à la
 * suivante (pas de zoom lent, qui ferait travailler le GPU 55 fois par seconde). Deux images en mémoire au plus.
 * MainActivity le lance après un moment sans toucher et le ferme au premier toucher (voir FrameLogic).
 */
class PhotoFrameView(private val activity: MainActivity) : FrameLayout(activity) {

    private val images = Array(2) {
        ImageView(context).apply {
            alpha = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    private var front = 0
    private var files: List<File> = emptyList()
    private val shuffle = FrameLogic.Shuffle()
    private var loading: Job? = null
    private val advance = Runnable { next() }
    private val minute: Runnable = object : Runnable {
        override fun run() {
            tick()
            postDelayed(this, MinuteClock.msToNextMinute(System.currentTimeMillis()) + MinuteClock.SLACK_MS)
        }
    }

    private val clock = TextView(context).apply {
        textSize = 72f
        typeface = themeFont(context, R.attr.c_font_display)
        includeFontPadding = false
        isSingleLine = true
    }
    private val date = TextView(context).apply {
        textSize = 22f
        typeface = themeFont(context, R.attr.c_font_regular)
    }
    // Toute la largeur, texte à gauche : mesurée au plus juste, l'heure perdait son dernier chiffre (« 20:2 ») en Rétro.
    private val time = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(clock, LinearLayout.LayoutParams(-1, -2))
        addView(date, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    val isShowing: Boolean get() = visibility == VISIBLE

    init {
        setBackgroundColor(themeColor(context, R.attr.c_frame_bg))
        visibility = GONE
        images.forEach { addView(it, LayoutParams(-1, -1)) }
        val shadow = themeColor(context, R.attr.c_frame_shadow)
        listOf(clock, date).forEach {
            it.setTextColor(themeColor(context, R.attr.c_frame_text))
            it.setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), shadow)
        }
        addView(time, LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(40), 0, dp(40), dp(32)) })
    }

    /** Affiche le cadre avec ces photos (déjà réduites, voir FrameCache). */
    fun show(photos: List<File>) {
        files = photos
        if (files.isEmpty()) return
        visibility = VISIBLE
        removeCallbacks(minute)
        minute.run()
        next()
    }

    /** Liste relue pendant l'affichage (photos ajoutées ou retirées sur l'ordinateur) ; vide : le cadre se ferme. */
    fun update(photos: List<File>) {
        files = photos
        if (files.isEmpty()) activity.closeFrame()
    }

    fun hide() {
        if (!isShowing) return
        removeCallbacks(advance)
        removeCallbacks(minute)
        loading?.cancel()
        visibility = GONE
        // Les bitmaps partent avec le cadre : rien ne reste en mémoire tant qu'il n'est pas affiché.
        images.forEach { it.animate().cancel(); it.setImageDrawable(null); it.alpha = 0f }
    }

    /** Heure et date, à chaque minute pleine tant que le cadre est affiché (`minute`). */
    fun tick() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
        date.text = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() }
    }

    private fun next() {
        removeCallbacks(advance)
        val name = shuffle.next(files.map { it.name }) ?: return
        val file = files.first { it.name == name }
        loading?.cancel()
        loading = activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) }
            if (!isShowing) return@launch
            if (bitmap != null) {
                val back = images[1 - front]
                val old = images[front]
                back.scaleType = if (FrameLogic.fills(bitmap.width, bitmap.height)) ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
                back.setImageBitmap(bitmap)
                back.bringToFront()
                time.bringToFront() // l'heure reste devant les photos
                back.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
                    old.alpha = 0f
                    old.setImageDrawable(null)
                }
                front = 1 - front
            }
            postDelayed(advance, Prefs.frameIntervalS(context) * 1000L)
        }
    }

    private companion object {
        const val FADE_MS = 600L
    }
}
