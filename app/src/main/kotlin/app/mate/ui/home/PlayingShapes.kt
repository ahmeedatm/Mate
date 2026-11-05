package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/** Ce qui joue, réduit à ce que les petites formes de Lecture et de YouTube Music montrent. */
data class Playing(
    val title: String,
    val artist: String,
    val playing: Boolean,
    /** Avancement entre 0 et 1 ; null : inconnu (direct, ou lecteur qui ne le donne pas). */
    val progress: Float?,
    val cover: Bitmap?,
)

/**
 * Petites formes de Lecture et de YouTube Music, choisies par Ahmed sur captures (#182) : Pastille = la pochette remplit
 * la case, lecture / pause en bas à droite ; Colonne = l'affiche, la pochette remplit la carte, titre et boutons
 * par-dessus ; Ligne = vignette, titre et boutons côte à côte. Chaque fonction rend la vue (sans carte) et la fonction
 * qui la met à jour ; null = rien en lecture ([empty]). Les boutons appellent [action] (« play-pause », « previous »,
 * « next »).
 */
class PlayingShapes(
    private val context: Context,
    @StringRes private val empty: Int,
    private val action: (String) -> Unit,
) {
    class Shape(val view: View, val render: (Playing?) -> Unit)

    private fun color(id: Int) = themeColor(context, id)
    private fun dp(value: Int) = context.dp(value)
    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Pochette, ou une note de musique quand l'ordinateur n'en donne pas ; [radiusDp] > 0 : vignette posée sur la surface. */
    private fun cover(radiusDp: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        if (radiusDp > 0) {
            setBackgroundColor(color(R.attr.c_surface_raised))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Vrai : une pochette est posée. */
    private fun ImageView.show(art: Bitmap?): Boolean {
        if (art != null) {
            scaleType = ImageView.ScaleType.CENTER_CROP
            ImageViewCompat.setImageTintList(this, null)
            setImageBitmap(art)
        } else {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_music_note)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.attr.c_text_faint)))
        }
        return art != null
    }

    /**
     * Sur une pochette : voile du bas, texte et boutons blancs avec ombre. Sans pochette, la carte du thème reste visible
     * (fond, bordure du thème Contraste) : ni voile, texte et boutons aux couleurs du thème.
     */
    private fun paint(onImage: Boolean, scrim: View, texts: List<TextView>, buttons: List<ImageButton>) {
        scrim.visibility = if (onImage) View.VISIBLE else View.GONE
        texts.forEachIndexed { i, t ->
            t.setTextColor(color(if (onImage) R.attr.c_frame_text else if (i == 0) R.attr.c_text else R.attr.c_text_dim))
            if (onImage) t.setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), color(R.attr.c_frame_shadow)) else t.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
        buttons.forEach { ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(color(if (onImage) R.attr.c_frame_text else R.attr.c_text))) }
    }

    private fun text(size: Float, colorId: Int, medium: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color(colorId))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (medium) typeface = themeFont(context, R.attr.c_font_medium)
    }

    private fun button(icon: Int, label: Int, op: String, tint: Int = R.attr.c_text) = ImageButton(context, null, 0, R.style.IconButton).apply {
        setImageResource(icon)
        // Le tint du style n'est pas appliqué à un bouton créé en code.
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(tint)))
        contentDescription = context.getString(label)
        setOnClickListener { action(op) }
    }

    /** Lecture / pause rond, plein, dans la couleur d'accent. */
    private fun playButton() = ImageButton(context, null, 0, R.style.IconButton).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.attr.c_accent))
        }
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.attr.c_on_accent)))
        contentDescription = context.getString(R.string.media_play_pause)
        setOnClickListener { action(PLAY_PAUSE) }
    }

    private fun ImageButton.showPlaying(p: Playing?) {
        setImageResource(if (p?.playing == true) R.drawable.ic_pause else R.drawable.ic_play)
        isEnabled = p != null
        alpha = if (p != null) 1f else DISABLED_ALPHA
    }

    /** Précédent, lecture / pause, suivant ; [onImage] : posés sur la pochette, sans fond, l'icône blanche suffit. */
    private fun controls(play: ImageButton, onImage: Boolean) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        val tint = if (onImage) R.attr.c_frame_text else R.attr.c_text
        val previous = button(R.drawable.ic_skip_previous, R.string.media_previous, PREVIOUS, tint)
        val next = button(R.drawable.ic_skip, R.string.media_next, NEXT, tint)
        if (onImage) listOf(previous, next).forEach { it.background = null }
        addView(previous)
        addView(play, LinearLayout.LayoutParams(dp(PLAY_DP), dp(PLAY_DP)).apply { marginStart = dp(12); marginEnd = dp(12) })
        addView(next)
    }

    /** Voile du bas, pour lire le texte posé sur la pochette. */
    private fun scrim() = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, color(R.attr.c_scrim)))
    }

    private fun describe(view: View, p: Playing?) {
        view.contentDescription = p?.let { listOf(it.title, it.artist).filter(String::isNotEmpty).joinToString(", ") } ?: context.getString(empty)
    }

    /** Pastille : la pochette plein cadre, le titre dessus en bas, lecture / pause en bas à droite. */
    fun pill(): Shape {
        val art = cover(0)
        val title = text(15f, R.attr.c_text, medium = true).apply { maxLines = 2 }
        val play = playButton()
        val veil = scrim()
        val view = FrameLayout(context).apply {
            addView(art, match())
            addView(veil, match())
            addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(dp(16), 0, dp(76), dp(16))
            })
            addView(play, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(12)) })
        }
        return Shape(view) { p ->
            paint(art.show(p?.cover), veil, listOf(title), emptyList())
            title.text = p?.title ?: context.getString(empty)
            play.showPlaying(p)
            describe(view, p)
        }
    }

    /** Colonne : l'affiche ; la pochette remplit la carte, titre, artiste et les trois boutons par-dessus, en bas. */
    fun column(): Shape {
        val art = cover(0)
        val title = text(18f, R.attr.c_text, medium = true).apply { maxLines = 2 }
        val artist = text(14f, R.attr.c_text_dim)
        val play = playButton()
        val veil = scrim()
        val row = controls(play, onImage = true)
        val view = FrameLayout(context).apply {
            addView(art, match())
            addView(veil, match())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(14))
                addView(title)
                addView(artist)
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        val sides = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<ImageButton>().filter { it !== play }
        return Shape(view) { p ->
            paint(art.show(p?.cover), veil, listOf(title, artist), sides)
            title.text = p?.title ?: context.getString(empty)
            artist.text = p?.artist.orEmpty()
            play.showPlaying(p)
            describe(view, p)
        }
    }

    /** Ligne : vignette carrée, titre, artiste et avancement, puis les trois boutons à droite. */
    fun row(): Shape {
        val art = cover(12)
        val title = text(17f, R.attr.c_text, medium = true)
        val artist = text(14f, R.attr.c_text_dim)
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(color(R.attr.c_accent))
            progressBackgroundTintList = ColorStateList.valueOf(color(R.attr.c_stroke))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val play = playButton()
        val view = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(SquareFrame(context).apply { addView(art, match()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(artist)
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(10) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
            addView(controls(play, onImage = false))
        }
        return Shape(view) { p ->
            art.show(p?.cover)
            title.text = p?.title ?: context.getString(empty)
            artist.text = p?.artist.orEmpty()
            bar.visibility = if (p?.progress != null) View.VISIBLE else View.INVISIBLE
            bar.progress = ((p?.progress ?: 0f) * PROGRESS_MAX).toInt()
            play.showPlaying(p)
            describe(view, p)
        }
    }

    companion object {
        const val PLAY_PAUSE = "play-pause"
        const val PREVIOUS = "previous"
        const val NEXT = "next"
        private const val PROGRESS_MAX = 1000
        private const val PLAY_DP = 56
        private const val DISABLED_ALPHA = 0.4f

        /** Pochette d'exemple (catalogue) : des disques dans les teintes du thème, dessinés une fois, sans image à embarquer. */
        fun sampleCover(context: Context, size: Int = SAMPLE_PX): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(themeColor(context, R.attr.c_hue_violet))
            paint.color = themeColor(context, R.attr.c_hue_pink)
            canvas.drawCircle(size * 0.7f, size * 0.3f, size * 0.45f, paint)
            paint.color = themeColor(context, R.attr.c_hue_orange)
            canvas.drawCircle(size * 0.25f, size * 0.8f, size * 0.32f, paint)
            paint.color = themeColor(context, R.attr.c_hue_yellow)
            canvas.drawCircle(size * 0.62f, size * 0.68f, size * 0.12f, paint)
            return bitmap
        }

        private const val SAMPLE_PX = 256
    }
}

/** Cadre carré : côté = la hauteur reçue (vignette d'une ligne). */
private class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = MeasureSpec.getSize(heightMeasureSpec)
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}
