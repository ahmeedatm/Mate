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
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.ui.ProgressRing
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
 * Petites formes de Lecture et de YouTube Music (Pastille, Colonne) : chaque fonction rend la vue (sans carte) et la
 * fonction qui la met à jour. Les boutons appellent [onPlayPause], [onPrevious] et [onNext].
 */
class PlayingShapes(
    private val context: Context,
    private val onPlayPause: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
) {
    class Shape(val view: View, val render: (Playing?) -> Unit)

    private fun color(id: Int) = themeColor(context, id)
    private fun dp(value: Int) = context.dp(value)

    private fun cover(radiusDp: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { setColor(color(R.attr.c_surface_raised)) }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (radiusDp < 0) outline.setOval(0, 0, view.width, view.height)
                else outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun text(size: Float, colorId: Int, medium: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color(colorId))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (medium) typeface = themeFont(context, R.attr.c_font_medium)
    }

    /** Texte posé sur la pochette : blanc avec une ombre, lisible sur toute image. */
    private fun TextView.onCover() = apply {
        setTextColor(color(R.attr.c_frame_text))
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), color(R.attr.c_frame_shadow))
    }

    private fun button(icon: Int, label: Int, action: () -> Unit) = ImageButton(context, null, 0, R.style.IconButton).apply {
        setImageResource(icon)
        // Le tint du style n'est pas appliqué à un bouton créé en code.
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.attr.c_text)))
        contentDescription = context.getString(label)
        setOnClickListener { action() }
    }

    /** Lecture / pause rond, plein, dans la couleur d'accent : le seul geste d'une pastille. */
    private fun playButton() = ImageButton(context, null, 0, R.style.IconButton).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.attr.c_accent))
        }
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.attr.c_on_accent)))
        setOnClickListener { onPlayPause() }
    }

    private fun ImageButton.showPlaying(playing: Boolean) {
        setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        contentDescription = context.getString(R.string.media_play_pause)
    }

    private fun controls(play: ImageButton) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(button(R.drawable.ic_skip_previous, R.string.media_previous, onPrevious))
        addView(play, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(12); marginEnd = dp(12) })
        addView(button(R.drawable.ic_skip, R.string.media_next, onNext))
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** Pastille A : la pochette plein cadre, le titre dessus en bas, lecture / pause en bas à droite. */
    fun pillCover(): Shape {
        val art = cover(0)
        val title = text(15f, R.attr.c_text, medium = true).onCover()
        val play = playButton()
        val view = FrameLayout(context).apply {
            addView(art, match())
            addView(View(context).apply { background = scrim() }, match())
            addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(dp(16), 0, dp(80), dp(18))
            })
            addView(play, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(12), dp(12)) })
        }
        return Shape(view) { p ->
            art.setImageBitmap(p?.cover)
            title.text = p?.title.orEmpty()
            play.showPlaying(p?.playing == true)
        }
    }

    /** Pastille B : la pochette en disque, entourée de l'avancement, lecture / pause dessus ; le titre à côté. */
    fun pillDisc(): Shape {
        val ring = ProgressRing(context).apply { trackColor = color(R.attr.c_stroke) }
        val art = cover(-1)
        val play = playButton()
        val disc = FrameLayout(context).apply {
            addView(ring, match())
            addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
            addView(play, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }
        val title = text(16f, R.attr.c_text, medium = true).apply { maxLines = 2 }
        val artist = text(14f, R.attr.c_text_dim)
        val view = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(SquareFrame(context).apply { addView(disc, match()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(artist)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }
        return Shape(view) { p ->
            ring.progress = p?.progress ?: 0f
            art.setImageBitmap(p?.cover)
            title.text = p?.title.orEmpty()
            artist.text = p?.artist.orEmpty()
            play.showPlaying(p?.playing == true)
        }
    }

    /** Pastille C : le titre et l'artiste, les trois boutons dessous ; pas d'image. */
    fun pillText(): Shape {
        val title = text(17f, R.attr.c_text, medium = true)
        val artist = text(14f, R.attr.c_text_dim)
        val play = playButton()
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(artist)
            addView(controls(play), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        return Shape(view) { p ->
            title.text = p?.title ?: context.getString(R.string.media_empty)
            artist.text = p?.artist.orEmpty()
            play.showPlaying(p?.playing == true)
        }
    }

    /** Colonne A : pochette carrée en haut, titre, artiste, avancement, puis les trois boutons. */
    fun columnStack(): Shape {
        val art = cover(16)
        val title = text(17f, R.attr.c_text, medium = true)
        val artist = text(14f, R.attr.c_text_dim)
        val bar = progressBar()
        val play = playButton()
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(SquareFrame(context).apply { addView(art, match()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(artist)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(10) })
            addView(controls(play), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        return Shape(view) { p ->
            art.setImageBitmap(p?.cover)
            title.text = p?.title ?: context.getString(R.string.media_empty)
            artist.text = p?.artist.orEmpty()
            bar.progress = ((p?.progress ?: 0f) * PROGRESS_MAX).toInt()
            play.showPlaying(p?.playing == true)
        }
    }

    /** Colonne B : la pochette remplit la carte, le texte et les boutons par-dessus, en bas. */
    fun columnPoster(): Shape {
        val art = cover(0)
        val title = text(18f, R.attr.c_text, medium = true).onCover().apply { maxLines = 2 }
        val artist = text(14f, R.attr.c_text_dim).onCover()
        val play = playButton()
        fun onCoverButton(icon: Int, label: Int, action: () -> Unit) = button(icon, label, action).apply {
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color(R.attr.c_frame_text)))
            background = null
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(onCoverButton(R.drawable.ic_skip_previous, R.string.media_previous, onPrevious))
            addView(play, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(12); marginEnd = dp(12) })
            addView(onCoverButton(R.drawable.ic_skip, R.string.media_next, onNext))
        }
        val view = FrameLayout(context).apply {
            addView(art, match())
            addView(View(context).apply { background = scrim() }, match())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(14))
                addView(title)
                addView(artist)
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        return Shape(view) { p ->
            art.setImageBitmap(p?.cover)
            title.text = p?.title.orEmpty()
            artist.text = p?.artist.orEmpty()
            play.showPlaying(p?.playing == true)
        }
    }

    /** Colonne C : télécommande ; petite vignette et titre en haut, précédent, lecture et suivant empilés en grand. */
    fun columnRemote(): Shape {
        val art = cover(10)
        val title = text(15f, R.attr.c_text, medium = true)
        val artist = text(13f, R.attr.c_text_dim)
        val play = playButton()
        fun big(icon: Int, label: Int, action: () -> Unit) = button(icon, label, action).apply { scaleType = ImageView.ScaleType.CENTER }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(artist)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            addView(big(R.drawable.ic_skip_previous, R.string.media_previous, onPrevious), LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(play, LinearLayout.LayoutParams(dp(80), dp(80)).apply { setMargins(0, dp(12), 0, dp(12)) })
            addView(big(R.drawable.ic_skip, R.string.media_next, onNext), LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        }
        return Shape(view) { p ->
            art.setImageBitmap(p?.cover)
            title.text = p?.title ?: context.getString(R.string.media_empty)
            artist.text = p?.artist.orEmpty()
            play.showPlaying(p?.playing == true)
        }
    }

    private fun progressBar() = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = PROGRESS_MAX
        progressTintList = ColorStateList.valueOf(color(R.attr.c_accent))
        progressBackgroundTintList = ColorStateList.valueOf(color(R.attr.c_stroke))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Voile du bas, pour lire le texte posé sur la pochette. */
    private fun scrim() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.TRANSPARENT, color(R.attr.c_scrim)),
    )

    companion object {
        private const val PROGRESS_MAX = 1000

        /** Pochette d'exemple (catalogue, captures) : des disques dans les teintes du thème, dessinés une fois. */
        fun sampleCover(context: Context, size: Int = 256): Bitmap {
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
    }
}

/** Cadre carré, côté = la plus petite des deux dimensions données, centré. */
private class SquareFrame(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (h == 0) w else if (w == 0) h else minOf(w, h)
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
        setMeasuredDimension(if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) w else side, if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) h else side)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val side = minOf(right - left, bottom - top)
        val x = (right - left - side) / 2
        val y = (bottom - top - side) / 2
        for (i in 0 until childCount) getChildAt(i).layout(x, y, x + side, y + side)
    }
}
