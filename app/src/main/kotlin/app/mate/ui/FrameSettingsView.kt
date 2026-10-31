package app.mate.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.mate.MainActivity
import app.mate.Prefs
import app.mate.R
import app.mate.frame.FrameCache
import app.mate.frame.FrameLogic
import app.mate.net.BackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Écran « Cadre photo » de Depuis l'ordi (view_frame.xml) : photos copiées sur la tablette (vignettes réduites par 8 au
 * décodage), dossier à ouvrir sur l'ordinateur, Essayer ; délai sans toucher et durée d'une photo. Synchro à
 * l'ouverture de l'écran (au plus tous les quarts d'heure, voir FrameCache), jamais en boucle.
 */
class FrameSettingsView(private val fragment: Fragment, root: View) {

    private val context = root.context
    private val status: TextView = root.findViewById(R.id.frame_status)
    private val thumbs: LinearLayout = root.findViewById(R.id.frame_thumbs)
    private val main get() = fragment.requireActivity() as MainActivity

    init {
        setUpChoices(root.findViewById(R.id.frame_delays), FrameLogic.DELAYS_MIN, Prefs.frameDelayMin(context), {
            if (it == 0) context.getString(R.string.frame_never) else context.getString(R.string.frame_minutes, it)
        }) { Prefs.setFrameDelayMin(context, it) }
        setUpChoices(root.findViewById(R.id.frame_intervals), FrameLogic.INTERVALS_S, Prefs.frameIntervalS(context), {
            if (it < 60) context.getString(R.string.frame_seconds, it) else context.getString(R.string.frame_minutes, it / 60)
        }) { Prefs.setFrameIntervalS(context, it) }
        // L'ordinateur crée le dossier en le listant : il existe avant d'être ouvert.
        root.findViewById<Button>(R.id.frame_folder).setOnClickListener {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val docs = BackendClient(context).docs
                    docs.framePhotos()
                    docs.openOnComputer(FrameCache.FOLDER)
                } catch (e: Exception) {
                    status.text = context.errorText(e)
                }
            }
        }
        root.findViewById<Button>(R.id.frame_try).setOnClickListener { read(force = true, open = true) }
    }

    /** Pastilles d'un choix (une seule sélectionnée), enregistré au toucher. */
    private fun setUpChoices(row: LinearLayout, values: List<Int>, current: Int, label: (Int) -> String, save: (Int) -> Unit) {
        // Cinq pastilles de même largeur dans la colonne : marges réduites, une ligne (sinon « Jamais » se coupe).
        row.isBaselineAligned = false
        for (value in values) {
            val chip = Button(context, null, 0, R.style.Chip).apply {
                text = label(value)
                isSingleLine = true
                setPadding(context.dp(6), 0, context.dp(6), 0)
                isSelected = value == current
                setOnClickListener {
                    save(value)
                    for (i in 0 until row.childCount) row.getChildAt(i).isSelected = row.getChildAt(i) === this
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, context.dp(48), 1f).apply { if (row.childCount > 0) marginStart = context.dp(8) })
        }
    }

    /** Photos copiées de l'ordinateur ([force] : synchro tout de suite) ; [open] : lance le cadre ensuite. */
    fun read(force: Boolean = false, open: Boolean = false) {
        if (force) status.setText(R.string.frame_syncing)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val count = FrameCache.sync(context, force)
            val photos = withContext(Dispatchers.IO) { FrameCache.photos(context) }
            if (fragment.view == null) return@launch
            status.text = if (count == 0) context.getString(R.string.frame_empty)
            else context.resources.getQuantityString(R.plurals.frame_count, count, count)
            showThumbs(photos.take(THUMBS))
            if (open) main.openFrame(photos)
        }
    }

    /** Vignettes : les photos du cache réduites par 8 (160×100 environ), quatre par rangée. */
    private suspend fun showThumbs(photos: List<File>) {
        val bitmaps = withContext(Dispatchers.IO) {
            photos.mapNotNull { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 8 }) }
        }
        thumbs.removeAllViews()
        bitmaps.chunked(PER_ROW).forEach { row ->
            thumbs.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { addView(thumb(it), LinearLayout.LayoutParams(0, -1, 1f).apply { if (childCount > 0) marginStart = context.dp(8) }) }
                // Rangée incomplète : les vignettes gardent la largeur d'une rangée pleine.
                repeat(PER_ROW - row.size) { addView(View(context), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = context.dp(8) }) }
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (thumbs.childCount > 0) topMargin = context.dp(8) })
        }
        // Une seule rangée : la place d'une deuxième reste vide, les vignettes ne s'étirent pas.
        if (bitmaps.size in 1..PER_ROW) thumbs.addView(View(context), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(8) })
    }

    private fun thumb(bitmap: Bitmap) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageBitmap(bitmap)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private companion object {
        const val THUMBS = 8
        const val PER_ROW = 4
    }
}
