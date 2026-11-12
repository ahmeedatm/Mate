package app.mate.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.OpenApp
import app.mate.ui.IconCache
import app.mate.ui.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Capuchon d'une appli ou d'une fenêtre du widget Fenêtres en thème Instrument : la touche d'`item_key_cap`, avec le numéro,
 * l'icône de l'appli grisée (ou son initiale en Departure Mono tant qu'elle n'est pas chargée), le libellé et la DEL
 * (`isSelected`) allumée pour ce qui est au premier plan.
 */
object WindowCapViews {
    fun inflate(context: Context): View = LayoutInflater.from(context).inflate(R.layout.item_key_cap, null, false)

    /** [scope] null : aperçu du catalogue, pas d'icône à charger (rien ne part sur le réseau). */
    fun bind(view: View, number: String, app: OpenApp, label: String, lit: Boolean, description: String, scope: CoroutineScope?) {
        val icon = view.findViewById<ImageView>(R.id.tile_icon)
        val initial = view.findViewById<TextView>(R.id.tile_initial)
        view.findViewById<TextView>(R.id.key_number).text = number
        view.findViewById<TextView>(R.id.tile_label).text = label
        initial.text = app.name.take(1).uppercase(Locale.FRENCH)
        view.isSelected = lit
        view.findViewById<View>(R.id.key_cap).translationY = if (lit) view.context.dp(KeyCaps.LIT_DP).toFloat() else 0f
        view.contentDescription = description
        view.tag = app.key
        fun show(bitmap: Bitmap) {
            icon.setImageBitmap(bitmap)
            icon.colorFilter = TileGridView.GRAY
            initial.visibility = View.GONE
        }
        if (scope == null) return
        IconCache.peekApp(app.name)?.let(::show) ?: scope.launch {
            val bitmap = IconCache.loadApp(BackendClient(view.context), app.name)
            if (bitmap != null && view.tag == app.key) show(bitmap)
        }
    }
}
