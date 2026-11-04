package app.mate.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val note = label(14f, R.attr.c_text_dim).apply {
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

    /** Image à l'écran, et celle où décoder la suivante ; jamais décodées en même temps que montrées. */
    private var shown: Bitmap? = null
    private var spare: Bitmap? = null
    private var failures = 0

    /** Actif : une seule boucle d'images ; inactif (autre page, écran éteint) : plus aucune requête, images oubliées. */
    override fun setActive(active: Boolean) {
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
        runCatchingCancellable {
            val bytes = BackendClient(context).screen.preview()
            withContext(Dispatchers.Default) { decode(bytes, reuse, viewWidth, viewHeight) }
        }
            .onSuccess { bitmap -> if (bitmap != null) show(bitmap) else fail(context.getString(R.string.error_bad_reply)) }
            .onFailure { fail(context.errorText(it)) }
    }

    /** JPEG réduit au plus près de la vue ; `reuse` reprend la mémoire d'une image précédente quand les tailles le permettent. */
    private fun decode(bytes: ByteArray, reuse: Bitmap?, viewWidth: Int, viewHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ScreenPreview.sampleSize(bounds.outWidth, bounds.outHeight, viewWidth, viewHeight)
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
    }
}
