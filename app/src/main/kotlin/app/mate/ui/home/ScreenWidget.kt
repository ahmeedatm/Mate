package app.mate.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import app.mate.ui.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * L'écran de l'ordinateur en petit (suivre un export sans se lever). Une image JPEG de 640 px au plus toutes les 1,5 s
 * ([ScreenPreview]), une seule requête à la fois, et seulement page à l'écran : quitter la page ou éteindre l'écran de la
 * tablette arrête tout et libère les images. Les images ne sont jamais écrites sur le disque. Décodage réduit
 * (`inSampleSize`, ARGB 8888 : le RGB 565 rayait les dégradés) dans deux bitmaps qui alternent : celui qu'on décode n'est jamais celui qu'on voit.
 * Absent des modèles : l'utilisateur l'ajoute lui-même (la mémoire de la 4.2 n'est pas mesurée).
 */
class ScreenWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.widget_screen_image)
    }
    private val note = label(NOTE_SP, R.attr.c_text_dim).apply {
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(ColorUtils.setAlphaComponent(color(R.attr.c_bg), NOTE_ALPHA))
    }
    private val frame = FrameLayout(context).apply {
        addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    override val view = LinearLayout(context).asCard(8).apply {
        orientation = LinearLayout.VERTICAL
        addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private var job: Job? = null

    /** Pastille, Ligne et Colonne : l'écran recadré au centre ; lu au décodage pour garder assez de pixels. */
    private var crop = ScreenPreview.crops(family)

    /** Aperçu du catalogue : une image dessinée sur place, ni réseau ni boucle. */
    private var sample = false

    /** Image à l'écran, et celle où décoder la suivante ; jamais décodées en même temps que montrées. */
    private var shown: Bitmap? = null
    private var spare: Bitmap? = null
    private var failures = 0

    override fun applySize() {
        crop = ScreenPreview.crops(family)
        image.scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        // En petit, la phrase tient sur deux lignes au plus.
        note.textSize = if (crop) SMALL_NOTE_SP else NOTE_SP
        note.maxLines = if (crop) 2 else Int.MAX_VALUE
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is ScreenSample) return
        this.sample = true
        note.visibility = View.GONE
        image.setImageDrawable(ScreenSample.drawable(context))
    }

    /** Actif : une seule boucle d'images ; inactif (autre page, écran éteint) : plus aucune requête, images oubliées. */
    override fun setActive(active: Boolean) {
        if (sample) return
        if (!active) {
            // `job` reste là : annuler n'interrompt pas une requête déjà partie, la boucle suivante l'attend.
            job?.cancel()
            release()
        } else if (job?.isActive != true) {
            val previous = job
            job = host.scope.launch {
                previous?.join()
                while (isActive) {
                    refresh()
                    delay(ScreenPreview.nextDelayMs(failures))
                }
            }
            if (shown == null) showNote(context.getString(R.string.widget_screen_loading))
        }
    }

    private suspend fun refresh() {
        // Lus ici (fil principal) : le bitmap à réutiliser, qui n'est pas celui qu'on voit, et la taille de la vue.
        val reuse = spare
        val viewWidth = image.width
        val viewHeight = image.height
        val cropped = crop
        runCatchingCancellable {
            val bytes = BackendClient(context).screen.preview()
            withContext(Dispatchers.Default) { decode(bytes, reuse, viewWidth, viewHeight, cropped) }
        }
            .onSuccess { bitmap -> if (bitmap != null) show(bitmap) else fail(context.getString(R.string.error_bad_reply)) }
            .onFailure { fail(context.errorText(it)) }
    }

    /** JPEG réduit au plus près de la vue ; `reuse` reprend la mémoire d'une image précédente quand les tailles le permettent. */
    private fun decode(bytes: ByteArray, reuse: Bitmap?, viewWidth: Int, viewHeight: Int, cropped: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ScreenPreview.sampleSize(bounds.outWidth, bounds.outHeight, viewWidth, viewHeight, cropped)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inBitmap = reuse
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: IllegalArgumentException) {
            // La taille a changé (autre écran, autre réduction) : la mémoire d'avant ne convient plus.
            options.inBitmap = null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }

    private fun show(bitmap: Bitmap) {
        image.setImageBitmap(bitmap)
        spare = shown
        shown = bitmap
        failures = 0
        note.visibility = View.GONE
    }

    /** L'image précédente reste à l'écran sous la phrase : une panne passagère ne vide pas le widget. */
    private fun fail(message: String) {
        failures++
        showNote(message)
    }

    private fun showNote(text: String) {
        note.text = text
        note.visibility = View.VISIBLE
        // Sans image, la phrase est au centre ; avec une image, en bas.
        (note.layoutParams as FrameLayout.LayoutParams).gravity = if (shown == null) Gravity.CENTER else Gravity.BOTTOM
        note.requestLayout()
    }

    private fun release() {
        image.setImageDrawable(null)
        shown = null
        spare = null
        failures = 0
        note.visibility = View.GONE
    }

    private companion object {
        /** Voile de la phrase sur l'image (0xD9 : 85 %). */
        const val NOTE_ALPHA = 0xD9
        const val NOTE_SP = 14f
        const val SMALL_NOTE_SP = 12f
    }
}

/** Exemple de l'Écran de l'ordi pour le catalogue : un bureau dessiné aux couleurs du thème (deux fenêtres, une barre), sans capture. */
object ScreenSample : WidgetSample {
    private const val WIDTH = 320
    private const val HEIGHT = 200

    fun drawable(context: Context): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun rect(attr: Int, left: Float, top: Float, right: Float, bottom: Float, radius: Float = 6f) {
            paint.color = themeColor(context, attr)
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, paint)
        }
        canvas.drawColor(themeColor(context, R.attr.c_graphic))
        rect(R.attr.c_text_faint, 20f, 30f, 190f, 150f)
        rect(R.attr.c_text_dim, 120f, 60f, 300f, 175f)
        rect(R.attr.c_accent, 120f, 60f, 300f, 76f, 4f)
        rect(R.attr.c_bg, 0f, 184f, 320f, 200f, 0f)
        return BitmapDrawable(context.resources, bitmap)
    }
}
