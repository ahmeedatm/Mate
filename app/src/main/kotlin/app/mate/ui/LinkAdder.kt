package app.mate.ui

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.DeckLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Ajout de sites à la page Média du deck (vide au départ), par-dessus le deck : sites proposés à cocher
 * (toucher un site déjà ajouté le retire) ou adresse tapée. Le panneau reste ouvert pour en ajouter plusieurs.
 */
class LinkAdder @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val url: EditText
    private val name: EditText
    private val status: TextView
    private val list: LinearLayout

    private lateinit var scope: CoroutineScope
    private var onChanged: () -> Unit = {}
    private var links: List<DeckLink> = emptyList()
    private var suggestions: List<DeckLink>? = null

    init {
        setBackgroundResource(R.drawable.scrim)
        isClickable = true
        // Reprend le focus des champs : sinon il partirait sur la barre latérale.
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        visibility = GONE
        LayoutInflater.from(context).inflate(R.layout.view_link_adder, this, true)
        url = findViewById(R.id.links_url)
        name = findViewById(R.id.links_name)
        status = findViewById(R.id.links_status)
        list = findViewById(R.id.links_list)

        findViewById<ImageButton>(R.id.links_close).setOnClickListener { hide() }
        findViewById<Button>(R.id.links_add).setOnClickListener { addTyped() }
        name.setOnEditorActionListener { _, action, event ->
            // ✓ du clavier à l'écran : IME_ACTION_DONE ; Entrée d'un clavier physique : IME_NULL.
            if (action != EditorInfo.IME_ACTION_DONE && action != EditorInfo.IME_NULL) return@setOnEditorActionListener false
            if (event == null || event.action == KeyEvent.ACTION_DOWN) addTyped()
            true
        }
    }

    fun attach(scope: CoroutineScope, onChanged: () -> Unit) {
        this.scope = scope
        this.onChanged = onChanged
    }

    fun show() {
        url.setText("")
        name.setText("")
        status.visibility = GONE
        visibility = VISIBLE
        render()
        reload()
    }

    fun hide() {
        // Un champ masqué garderait le clavier ouvert (pas de bouton Retour sur la tablette).
        dismissKeyboard()
        visibility = GONE
    }

    private fun reload() {
        scope.launch {
            try {
                BackendClient(context).deck.links().let { links = it.links; suggestions = it.suggestions }
                render()
            } catch (e: Exception) {
                status.showError(e)
            }
        }
    }

    /** Sites proposés, puis ceux ajoutés à la main (pour pouvoir les retirer d'ici aussi). */
    private fun render() {
        list.removeAllViews()
        val proposed = suggestions ?: return list.addView(TextView(context).apply { setText(R.string.loading); textSize = 16f })
        val others = links.filter { link -> proposed.none { it.url == link.url } }
        (proposed + others).forEach { list.addView(row(it)) }
    }

    private fun row(site: DeckLink) = TextView(context).apply {
        val added = links.firstOrNull { it.url == site.url }
        text = if (added != null) context.getString(R.string.apps_added, added.label) else site.label
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.row_bg)
        setPadding(dp(16), 0, dp(16), 0)
        if (added != null) setTextColor(themeColor(context, R.attr.c_accent))
        setOnClickListener {
            dismissKeyboard()
            if (added?.id != null) remove(added) else add(site.url, site.label)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(6) }
    }

    private fun addTyped() {
        val typed = url.text.toString().trim()
        if (typed.isEmpty()) {
            status.setText(R.string.links_url_missing)
            status.visibility = VISIBLE
            url.requestFocus()
            return
        }
        dismissKeyboard()
        add(typed, name.text.toString().trim().ifEmpty { null }) {
            url.setText("")
            name.setText("")
        }
    }

    private fun add(address: String, label: String?, then: () -> Unit = {}) {
        scope.launch {
            try {
                BackendClient(context).deck.addLink(address, label)
                then()
                changed(context.getString(R.string.links_added_status, label ?: address))
            } catch (e: Exception) {
                status.showError(e)
            }
        }
    }

    private fun remove(link: DeckLink) {
        val id = link.id ?: return
        scope.launch {
            try {
                BackendClient(context).deck.removeLink(id)
                IconCache.forget(id)
                changed(context.getString(R.string.links_removed_status, link.label))
            } catch (e: Exception) {
                status.showError(e)
            }
        }
    }

    private fun changed(message: String) {
        status.text = message
        status.visibility = VISIBLE
        reload()
        onChanged()
    }
}
